package com.cinema.cms.service;

import com.cinema.cms.dto.*;
import com.cinema.cms.entity.ConcessionInventory;
import com.cinema.cms.entity.ConcessionItem;
import com.cinema.cms.entity.InventoryTransaction;
import com.cinema.cms.entity.User;
import com.cinema.cms.repository.*;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InventoryService {
    static final int MAX_PAGE_SIZE = 100;
    private final ConcessionInventoryRepository inventory;
    private final ConcessionItemRepository items;
    private final InventoryTransactionRepository transactions;
    private final UserRepository users;

    public InventoryService(ConcessionInventoryRepository inventory, ConcessionItemRepository items, InventoryTransactionRepository transactions, UserRepository users) {
        this.inventory = inventory; this.items = items; this.transactions = transactions; this.users = users;
    }

    @Transactional(readOnly = true)
    public PageResponse<InventoryResponse> list(String keyword, String status, int page, int size) {
        QueryParams.checkPaging(page, size, MAX_PAGE_SIZE);
        InventoryResponse.Status wanted = parseStatus(status);
        String pattern = keyword == null || keyword.isBlank() ? null : "%" + keyword.strip().toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        Specification<ConcessionInventory> spec = (root, query, cb) -> {
            Join<ConcessionInventory, ConcessionItem> item = root.join("item");
            List<Predicate> p = new ArrayList<>();
            if (pattern != null) p.add(cb.like(cb.lower(item.get("name")), pattern, '\\'));
            if (wanted == InventoryResponse.Status.OUT_OF_STOCK) p.add(cb.equal(root.get("stockQuantity"), 0));
            if (wanted == InventoryResponse.Status.LOW_STOCK) {
                p.add(cb.greaterThan(root.<Integer>get("stockQuantity"), 0));
                p.add(cb.lessThanOrEqualTo(root.<Integer>get("stockQuantity"), root.<Integer>get("lowStockThreshold")));
            }
            if (wanted == InventoryResponse.Status.IN_STOCK) p.add(cb.greaterThan(root.<Integer>get("stockQuantity"), root.<Integer>get("lowStockThreshold")));
            return cb.and(p.toArray(new Predicate[0]));
        };
        Page<ConcessionInventory> found = inventory.findAll(spec, PageRequest.of(page, size, Sort.by(Sort.Order.asc("item.name"), Sort.Order.asc("itemId"))));
        return PageResponse.from(found, InventoryResponse::from);
    }

    @Transactional(readOnly = true)
    public List<InventoryResponse> alerts() { return inventory.findAlerts().stream().map(InventoryResponse::from).toList(); }

    @Transactional
    public InventoryMovementResponse restock(Integer itemId, RestockRequest r, Long managerId) {
        if (r.quantity().signum() <= 0) throw invalidQuantity("Số lượng nhập phải là số nguyên dương");
        int quantity = r.quantity().intValueExact();
        ConcessionInventory stock = inventory.findByIdForUpdate(itemId)
                .orElseThrow(() -> new ApiException(404, "CONCESSION_ITEM_NOT_FOUND", "Không tìm thấy món lẻ có tồn kho (combo không có tồn kho riêng)"));
        int before = stock.getStockQuantity();
        long after = (long) before + quantity;
        if (after > Integer.MAX_VALUE) throw invalidQuantity("Tồn kho sau khi nhập vượt giới hạn cho phép");
        stock.setStockQuantity((int) after);
        InventoryTransaction saved = transactions.saveAndFlush(InventoryTransaction.restock(itemId, quantity, before, (int) after, managerId, r.note()));
        inventory.saveAndFlush(stock);
        return InventoryMovementResponse.from(saved, stock.getItem().getName(), managerName(managerId));
    }

    @Transactional(readOnly = true)
    public PageResponse<InventoryMovementResponse> restocks(String itemId, String managerId, String from, String to, int page, int size) {
        QueryParams.checkPaging(page, size, MAX_PAGE_SIZE);
        Instant start = QueryParams.instant(from, "from"), end = QueryParams.instant(to, "to");
        if (start != null && end != null && start.isAfter(end)) throw new ApiException(400, "VALIDATION_ERROR", "from không được sau to");
        return movements(spec(QueryParams.integer(itemId, "itemId"), QueryParams.longValue(managerId, "managerId"), start, end, true), page, size);
    }

    @Transactional(readOnly = true)
    public PageResponse<InventoryMovementResponse> history(Integer itemId, int page, int size) {
        QueryParams.checkPaging(page, size, MAX_PAGE_SIZE);
        if (!inventory.existsById(itemId)) throw new ApiException(404, "CONCESSION_ITEM_NOT_FOUND", "Không tìm thấy món lẻ có tồn kho");
        return movements(spec(itemId, null, null, null, false), page, size);
    }

    private PageResponse<InventoryMovementResponse> movements(Specification<InventoryTransaction> spec, int page, int size) {
        Page<InventoryTransaction> found = transactions.findAll(spec, PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))));
        Map<Integer, String> itemNames = items.findAllById(found.getContent().stream().map(InventoryTransaction::getItemId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(ConcessionItem::getId, ConcessionItem::getName));
        Map<Long, String> managerNames = users.findAllById(found.getContent().stream().map(InventoryTransaction::getManagerId).filter(Objects::nonNull).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(User::getId, User::getFullName));
        return PageResponse.from(found, t -> InventoryMovementResponse.from(t, itemNames.get(t.getItemId()), managerNames.get(t.getManagerId())));
    }

    private static Specification<InventoryTransaction> spec(Integer itemId, Long managerId, Instant from, Instant to, boolean restocksOnly) {
        return (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (restocksOnly) p.add(cb.equal(root.get("movementType"), InventoryTransaction.RESTOCK));
            if (itemId != null) p.add(cb.equal(root.get("itemId"), itemId));
            if (managerId != null) p.add(cb.equal(root.get("managerId"), managerId));
            if (from != null) p.add(cb.greaterThanOrEqualTo(root.<Instant>get("createdAt"), from));
            if (to != null) p.add(cb.lessThanOrEqualTo(root.<Instant>get("createdAt"), to));
            return cb.and(p.toArray(new Predicate[0]));
        };
    }

    private String managerName(Long managerId) { return users.findById(managerId).map(User::getFullName).orElse(null); }
    private static InventoryResponse.Status parseStatus(String value) {
        if (value == null || value.isBlank()) return null;
        try { return InventoryResponse.Status.valueOf(value.strip().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { throw new ApiException(400, "VALIDATION_ERROR", "status phải là IN_STOCK, LOW_STOCK hoặc OUT_OF_STOCK"); }
    }
    private static ApiException invalidQuantity(String message) { return new ApiException(400, "INVALID_STOCK_QUANTITY", message); }
}