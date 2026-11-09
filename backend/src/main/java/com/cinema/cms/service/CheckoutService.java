package com.cinema.cms.service;

import com.cinema.cms.dto.*;
import com.cinema.cms.entity.*;
import com.cinema.cms.repository.*;
import java.math.*;
import java.security.SecureRandom;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Completes a PENDING Booking in one transaction. Cash, VietQR and the VNPay IPN all call
 * {@link #finalizePaid(Long, PaymentMethod, BigDecimal)}.
 */
@Service
public class CheckoutService {
    private static final int MAX_TICKET_CODE_ATTEMPTS = 10;
    private static final int TICKET_CODE_RANDOM_BYTES = 24;

    private final BookingRepository bookings;
    private final BookingSeatRepository bookingSeats;
    private final BookingConcessionRepository bookingConcessions;
    private final PaymentRepository payments;
    private final TicketRepository tickets;
    private final ConcessionItemRepository concessionItems;
    private final ConcessionInventoryRepository inventory;
    private final InventoryTransactionRepository inventoryTransactions;
    private final PricingService pricing;
    private final CheckoutSeatService checkoutSeats;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public CheckoutService(
            BookingRepository bookings,
            BookingSeatRepository bookingSeats,
            BookingConcessionRepository bookingConcessions,
            PaymentRepository payments,
            TicketRepository tickets,
            ConcessionItemRepository concessionItems,
            ConcessionInventoryRepository inventory,
            InventoryTransactionRepository inventoryTransactions,
            PricingService pricing,
            CheckoutSeatService checkoutSeats,
            Clock clock) {
        this.bookings = bookings;
        this.bookingSeats = bookingSeats;
        this.bookingConcessions = bookingConcessions;
        this.payments = payments;
        this.tickets = tickets;
        this.concessionItems = concessionItems;
        this.inventory = inventory;
        this.inventoryTransactions = inventoryTransactions;
        this.pricing = pricing;
        this.checkoutSeats = checkoutSeats;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CheckoutResult finalizePaid(
            Long bookingId, PaymentMethod method, BigDecimal expectedAmount) {
        if (bookingId == null || bookingId <= 0) throw notFound();
        if (method == null)
            throw new ApiException(400, "VALIDATION_ERROR", "Phương thức thanh toán không hợp lệ");

        Booking booking =
                bookings.findByIdForUpdate(bookingId).orElseThrow(CheckoutService::notFound);
        if (booking.getStatus() == BookingStatus.PAID) throw alreadyPaid();
        if (booking.getStatus() != BookingStatus.PENDING) throw invalidBookingState();
        if (booking.getHoldToken() == null || booking.getHoldOwner() == null)
            throw invalidBookingState();

        List<Long> plannedSeatIds =
                bookingSeats.findByBookingId(bookingId).stream()
                        .map(seat -> seat.getId().getSeatId())
                        .toList();
        if (plannedSeatIds.isEmpty()) throw invalidBookingState();

        // Must happen before any other read of ShowtimeSeat: booking clears the hold token.
        List<ShowtimeSeat> bookedSeats =
                checkoutSeats.markBooked(
                        booking.getHoldToken(),
                        booking.getHoldOwner(),
                        booking.getShowtimeId(),
                        plannedSeatIds);

        // Re-price tickets from the current rules. The client's quoted amount is authoritative:
        // never silently replace it when the price changed.
        PricingQuoteResponse quote = pricing.quote(booking.getShowtimeId(), plannedSeatIds);
        BigDecimal ticketTotal = money(quote.ticketTotal());

        List<BookingConcession> concessionLines =
                bookingConcessions.findByBookingId(bookingId);
        Map<Integer, ConcessionItem> itemsById = loadItems(concessionLines);
        BigDecimal concessionTotal = zero();
        Map<Integer, Integer> requiredStock = new TreeMap<>();
        List<SaleLine> saleLines = new ArrayList<>();
        for (BookingConcession line : concessionLines) {
            ConcessionItem item = itemsById.get(line.getId().getItemId());
            if (item == null)
                throw new ApiException(
                        404, "CONCESSION_ITEM_NOT_FOUND", "Không tìm thấy sản phẩm F&B");

            BigDecimal unitPrice = money(item.getPrice());
            concessionTotal =
                    money(
                            concessionTotal.add(
                                    unitPrice.multiply(BigDecimal.valueOf(line.getQuantity()))));

            if (item.isCombo()) {
                // A combo has no stock of its own. Only its components leave the warehouse.
                for (Map.Entry<Integer, Integer> component : item.getComponents().entrySet()) {
                    int quantity = multiplyQuantity(line.getQuantity(), component.getValue());
                    requiredStock.merge(component.getKey(), quantity, CheckoutService::addQuantity);
                    saleLines.add(new SaleLine(component.getKey(), quantity));
                }
            } else {
                requiredStock.merge(item.getId(), line.getQuantity(), CheckoutService::addQuantity);
                saleLines.add(new SaleLine(item.getId(), line.getQuantity()));
            }
        }

        BigDecimal totalAmount = money(ticketTotal.add(concessionTotal));
        if (totalAmount.compareTo(booking.getTotalAmount()) != 0) throw priceChanged();

        Payment payment = payments.findByBookingId(bookingId).orElse(null);
        if (payment != null) {
            if (payment.getStatus() != PaymentStatus.PENDING) throw paymentInvalidState();
            if (payment.getPaymentMethod() != method) throw paymentInvalidState();
            if (payment.getAmount() == null || payment.getAmount().compareTo(totalAmount) != 0)
                throw paymentAmountMismatch();
        }
        if (expectedAmount != null) {
            BigDecimal reference = payment == null ? totalAmount : payment.getAmount();
            if (expectedAmount.compareTo(reference) != 0) throw paymentAmountMismatch();
        }

        // Aggregate every line and combo component by item ID, then lock in ascending item ID
        // order so concurrent checkouts cannot deadlock and stock can never go negative.
        Map<Integer, Integer> beforeStock = new HashMap<>();
        Map<Integer, ConcessionInventory> lockedStock = new LinkedHashMap<>();
        for (Map.Entry<Integer, Integer> need : requiredStock.entrySet()) {
            ConcessionInventory stock =
                    inventory.findByIdForUpdate(need.getKey())
                            .orElseThrow(CheckoutService::outOfStock);
            int before = stock.getStockQuantity();
            if (before == 0) throw outOfStock();
            if (before < need.getValue()) throw insufficientStock();
            beforeStock.put(need.getKey(), before);
            lockedStock.put(need.getKey(), stock);
        }

        saleLines.sort(Comparator.comparingInt(SaleLine::itemId));
        Map<Integer, Integer> running = new HashMap<>(beforeStock);
        for (SaleLine line : saleLines) {
            int before = running.get(line.itemId());
            int after = before - line.quantity();
            if (after < 0) throw insufficientStock();
            running.put(line.itemId(), after);
            inventoryTransactions.save(
                    InventoryTransaction.sale(
                            line.itemId(), -line.quantity(), before, after, clock));
        }
        for (Map.Entry<Integer, ConcessionInventory> entry : lockedStock.entrySet()) {
            entry.getValue().setStockQuantity(running.get(entry.getKey()));
        }
        inventory.saveAll(lockedStock.values());

        Instant paidAt = now();
        if (payment == null) payment = new Payment(bookingId, method, totalAmount);
        payment.markPaid(paidAt);
        payments.save(payment);

        Map<Long, BigDecimal> priceBySeatId = new HashMap<>();
        for (TicketPrice price : quote.seats()) {
            priceBySeatId.put(price.seatId(), money(price.finalPrice()));
        }
        Set<String> generatedCodes = new HashSet<>();
        List<Ticket> createdTickets = new ArrayList<>(bookedSeats.size());
        for (ShowtimeSeat seat : bookedSeats) {
            BigDecimal price = priceBySeatId.get(seat.getSeatId());
            if (price == null) throw priceChanged();
            createdTickets.add(
                    new Ticket(
                            uniqueTicketCode(generatedCodes),
                            bookingId,
                            seat.getId(),
                            price,
                            clock));
        }
        tickets.saveAll(createdTickets);

        for (BookingConcession line : concessionLines) {
            ConcessionItem item = itemsById.get(line.getId().getItemId());
            line.updateUnitPriceSnapshot(money(item.getPrice()));
        }
        bookingConcessions.saveAll(concessionLines);

        booking.markPaid(paidAt);
        bookings.save(booking);

        return new CheckoutResult(booking, payment, createdTickets);
    }

    private Map<Integer, ConcessionItem> loadItems(List<BookingConcession> lines) {
        if (lines.isEmpty()) return Map.of();
        List<Integer> itemIds =
                lines.stream().map(line -> line.getId().getItemId()).toList();
        Map<Integer, ConcessionItem> items = new HashMap<>();
        concessionItems.findAllById(itemIds).forEach(item -> items.put(item.getId(), item));
        return items;
    }

    private String uniqueTicketCode(Set<String> generatedCodes) {
        for (int attempt = 0; attempt < MAX_TICKET_CODE_ATTEMPTS; attempt++) {
            byte[] bytes = new byte[TICKET_CODE_RANDOM_BYTES];
            random.nextBytes(bytes);
            String code =
                    "TK-" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            if (!generatedCodes.contains(code) && !tickets.existsByTicketCode(code)) {
                generatedCodes.add(code);
                return code;
            }
        }
        throw new IllegalStateException("Could not generate a unique ticket code");
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    }

    private static int multiplyQuantity(int first, int second) {
        try {
            return Math.multiplyExact(first, second);
        } catch (ArithmeticException e) {
            throw insufficientStock();
        }
    }

    private static int addQuantity(int first, int second) {
        try {
            return Math.addExact(first, second);
        } catch (ArithmeticException e) {
            throw insufficientStock();
        }
    }

    private record SaleLine(int itemId, int quantity) {}

    private static ApiException notFound() {
        return new ApiException(404, "BOOKING_NOT_FOUND", "Không tìm thấy Booking");
    }

    private static ApiException alreadyPaid() {
        return new ApiException(409, "BOOKING_ALREADY_PAID", "Booking đã được thanh toán");
    }

    private static ApiException invalidBookingState() {
        return new ApiException(
                409, "BOOKING_INVALID_STATE", "Booking không ở trạng thái chờ thanh toán");
    }

    private static ApiException priceChanged() {
        return new ApiException(
                409, "PRICE_CHANGED", "Giá đã thay đổi, vui lòng tạo lại Booking");
    }

    private static ApiException paymentAmountMismatch() {
        return new ApiException(
                409, "PAYMENT_AMOUNT_MISMATCH", "Số tiền thanh toán không khớp");
    }

    private static ApiException paymentInvalidState() {
        return new ApiException(
                409, "PAYMENT_INVALID_STATE", "Payment không ở trạng thái chờ thanh toán");
    }

    private static ApiException insufficientStock() {
        return new ApiException(409, "INSUFFICIENT_STOCK", "Không đủ tồn kho F&B");
    }

    private static ApiException outOfStock() {
        return new ApiException(409, "OUT_OF_STOCK", "Món F&B đã hết hàng");
    }
}
