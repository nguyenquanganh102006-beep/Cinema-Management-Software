// Development simulator only. PostgreSQL tests, not this in-memory server, establish transaction safety.
import { fixtures } from "./fixtures";
import type {
  Booking,
  BookingInput,
  Hold,
  Seat,
  Session,
  Role,
  Ticket,
  CartLine,
  Refund,
  Restock,
  Concession,
} from "../types";
const now = () => new Date().toISOString();
const zoneDate = (s: string) =>
  new Intl.DateTimeFormat("en-CA", {
    timeZone: "Asia/Ho_Chi_Minh",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).format(new Date(s));
class Failure extends Error {
  constructor(
    public status: number,
    public code: string,
    message: string,
    public details: Record<string, string> = {},
  ) {
    super(message);
  }
}
const fail = (status: number, code: string, message: string, details: Record<string, string> = {}): never => {
  throw new Failure(status, code, message, details);
};
export function createMockApi() {
  const db = fixtures();
  let scenario = "";
  const sessions = new Map<string, Session>();
  const transfers = new Map<string, Session>();
  const owners = new Map<number, string>();
  const holds = new Map<string, Hold & { owner: string; createdAt: number }>();
  const bookings: Booking[] = [];
  const bookingOwners = new Map<number, number>();
  const bookingSessions = new Map<number, string>();
  const restocks: Restock[] = [];
  const overrides = new Map<string, {
    owner: string;
    expires: number;
    bookingId: number;
    ticketIds: number[];
    reason: string;
    cashConfirmedAt?: string;
    refund?: Refund;
  }>();
  let sequence = 100;
  const demo = (
    role: Role,
    id = role === "MANAGER" ? 1 : role === "STAFF" ? 2 : 3,
  ): Session => ({
    accessToken: "mock-session-" + crypto.randomUUID(),
    expiresIn: 1800,
    user: {
      id,
      username:
        role === "MANAGER"
          ? "manager01"
          : role === "STAFF"
            ? "staff01"
            : "customer01",
      fullName:
        role === "MANAGER"
          ? "Quản lý mẫu"
          : role === "STAFF"
            ? "Nhân viên mẫu"
            : "Khách hàng mẫu",
      email: role.toLowerCase() + "@mock.invalid",
      phone: "090100000" + id,
      role,
    },
  });
  const seatMap = new Map<number, Seat[]>();
  db.showtimes.forEach((s) => seatMap.set(s.id, structuredClone(db.seats)));
  function refundSelection(body: Record<string, unknown>): number[] {
    const ids = body.ticketIds;
    if (!Number.isSafeInteger(body.bookingId) || Number(body.bookingId) <= 0 ||
      !Array.isArray(ids) || !ids.length || ids.length > 200 ||
      ids.some((id) => !Number.isSafeInteger(id) || id <= 0) || new Set(ids).size !== ids.length ||
      typeof body.reason !== "string" || !body.reason.trim() || body.reason.length > 255)
      fail(400, "VALIDATION_ERROR", "Cần mã vé duy nhất và lý do hợp lệ");
    return (ids as number[]).slice().sort((a, b) => a - b);
  }
  function refundReason(ticket: Ticket, booking: Booking): string | undefined {
    if (ticket.status === "CANCELLED") return "TICKET_ALREADY_CANCELLED";
    if (ticket.status === "USED") return "TICKET_NOT_REFUNDABLE";
    if (booking.status !== "PAID" || booking.payment?.paymentStatus !== "PAID") return "REFUND_NOT_ELIGIBLE";
    if (Date.parse(ticket.startTime) - Date.now() < 1800000) return "REFUND_DEADLINE_PASSED";
  }
  function refundableTickets(booking: Booking, ids: number[]): Ticket[] {
    const tickets = booking.tickets.filter((t) => ids.includes(t.ticketId));
    if (tickets.length !== ids.length) fail(400, "VALIDATION_ERROR", "Vé phải thuộc cùng đơn");
    for (const ticket of tickets) {
      const reason = refundReason(ticket, booking);
      if (reason) fail(422, reason, "Vé không còn đủ điều kiện hoàn");
    }
    return tickets;
  }
  function inventory() {
    for (const item of db.concessions) {
      if (!item.active) {
        item.availableQuantity = 0;
        item.lowStock = false;
        continue;
      }
      if (item.combo) {
        item.availableQuantity = item.components?.length ? Math.min(
          ...(item.components ?? []).map((c) =>
            Math.floor(
              (db.concessions.find((i) => i.id === c.itemId)?.active
                ? db.inventory.find((i) => i.itemId === c.itemId)?.stockQuantity ?? 0 : 0) / c.quantity,
            ),
          ),
        ) : 0;
        item.lowStock = (item.components ?? []).some((c) => {
          const stock = db.inventory.find((i) => i.itemId === c.itemId);
          return !stock || !db.concessions.find((i) => i.id === c.itemId)?.active || stock.stockQuantity <= stock.lowStockThreshold;
        });
      } else {
        item.availableQuantity =
          db.inventory.find((i) => i.itemId === item.id)?.stockQuantity ?? 0;
        item.lowStock = item.availableQuantity <= (db.inventory.find((i) => i.itemId === item.id)?.lowStockThreshold ?? 10);
      }
    }
    return db.concessions;
  }
  function expire() {
    for (const [token, hold] of holds)
      if (Date.now() >= Date.parse(hold.holdUntil)) {
        seatMap.get(hold.showtimeId)?.forEach((s) => {
          if (hold.seatIds.includes(s.seatId) && s.status === "HOLDING")
            s.status = "AVAILABLE";
        });
        holds.delete(token);
      }
  }
  function quote(showtimeId: number, ids: number[]) {
    const show = db.showtimes.find((s) => s.id === showtimeId);
    if (!show) fail(404, "SHOWTIME_NOT_FOUND", "Không có suất chiếu");
    const time = new Date(show!.startTime);
    const parts = new Intl.DateTimeFormat("en-US", {
      timeZone: "Asia/Ho_Chi_Minh",
      weekday: "short",
      hour: "numeric",
      minute: "numeric",
      hour12: false,
    }).formatToParts(time);
    const day = parts.find((p) => p.type === "weekday")?.value;
    const hour = Number(parts.find((p) => p.type === "hour")?.value);
    const minute = Number(parts.find((p) => p.type === "minute")?.value);
    const timeSurcharge =
      ["Fri", "Sat", "Sun"].includes(day ?? "") ||
      hour > 18 ||
      (hour === 18 && minute > 0)
        ? db.pricing.find((p) => p.ruleType === "TIME_SURCHARGE")!.amount
        : 0;
    const tickets = ids.map((seatId) => {
      const seat = seatMap.get(showtimeId)?.find((s) => s.seatId === seatId);
      if (!seat) fail(400, "INVALID_SEAT", "Ghế không hợp lệ");
      const basePrice = db.pricing.find(
        (p) => p.ruleType === "BASE_PRICE",
      )!.amount;
      const roomSurcharge =
        show!.auditoriumType === "3D"
          ? 20000
          : show!.auditoriumType === "IMAX"
            ? 40000
            : 0;
      const seatSurcharge =
        seat!.seatType === "STANDARD"
          ? 0
          : (db.pricing.find((p) => p.targetRef === seat!.seatType)?.amount ??
            15000);
      return {
        seatId,
        basePrice,
        roomSurcharge,
        seatSurcharge,
        timeSurcharge,
        finalPrice: basePrice + roomSurcharge + seatSurcharge + timeSurcharge,
      };
    });
    return { tickets, total: tickets.reduce((n, t) => n + t.finalPrice, 0) };
  }
  function recalculate(booking: Booking, cart: CartLine[]) {
    booking.concessions = cart.map((c) => {
      const item = db.concessions.find((i) => i.id === c.itemId);
      if (
        !item ||
        !item.active ||
        c.quantity < 1 ||
        !Number.isInteger(c.quantity)
      )
        fail(
          422,
          "INSUFFICIENT_STOCK",
          "Món không còn bán hoặc số lượng không hợp lệ",
        );
      return {
        itemId: c.itemId,
        name: item!.name,
        quantity: c.quantity,
        unitPriceSnapshot: item!.price,
      };
    });
    booking.totalAmount =
      booking.plannedSeats.reduce((n, s) => n + s.price, 0) +
      booking.concessions.reduce(
        (n, c) => n + c.quantity * c.unitPriceSnapshot,
        0,
      );
  }
  function finalize(
    booking: Booking,
    method: "CASH" | "VIETQR" | "GATEWAY_ONLINE",
  ) {
    if (booking.status === "PAID") return;
    if (booking.status !== "PENDING")
      fail(409, "BOOKING_NOT_PENDING", "Đơn không còn chờ thanh toán");
    const hold = [...holds.values()].find(
      (h) => owners.get(booking.bookingId) === h.holdToken,
    );
    if (
      !hold ||
      Date.now() >= Date.parse(booking.holdUntil) ||
      scenario === "exception"
    ) {
      if (method !== "CASH") {
        booking.status = "EXCEPTION_PENDING_DECISION";
        booking.exceptionReason = "Hết hạn giữ ghế sau khi nhận tiền";
        return;
      }
      fail(409, "SEAT_HOLD_EXPIRED", "Ghế đã hết hạn");
    }
    const needed = new Map<number, number>();
    for (const c of booking.concessions) {
      const item = db.concessions.find((i) => i.id === c.itemId)!;
      for (const component of item.combo
        ? (item.components ?? [])
        : [{ itemId: item.id, quantity: 1 }])
        needed.set(
          component.itemId,
          (needed.get(component.itemId) ?? 0) + component.quantity * c.quantity,
        );
    }
    if (
      scenario === "stock" ||
      [...needed].some(
        ([id, n]) =>
          (db.inventory.find((i) => i.itemId === id)?.stockQuantity ?? 0) < n,
      )
    ) {
      if (method !== "CASH") {
        booking.status = "EXCEPTION_PENDING_DECISION";
        booking.exceptionReason = "Đã nhận tiền nhưng F&B không đủ";
        return;
      }
      fail(
        422,
        "INSUFFICIENT_STOCK",
        "Không đủ F&B. Sửa đơn trước khi thu tiền.",
      );
    }
    for (const [id, n] of needed)
      db.inventory.find((i) => i.itemId === id)!.stockQuantity -= n;
    booking.payment = {
      ...booking.payment,
      paymentStatus: "PAID",
      paymentMethod: method,
      amount: booking.totalAmount,
      paidAt: now(),
    };
    booking.status = "PAID";
    booking.tickets = booking.plannedSeats.map((s) => ({
      ticketId: ++sequence,
      ticketCode: "TK-" + sequence,
      seatId: s.seatId,
      seatLabel: s.seatLabel,
      priceSnapshot: s.price,
      status: "VALID",
      showtimeId: booking.showtime.id,
      movieTitle: booking.showtime.movieTitle,
      auditoriumName: booking.showtime.auditoriumName,
      startTime: booking.showtime.startTime,
      ageRating: booking.showtime.ageRating,
      qrPayload: "TK-" + sequence,
    }));
    seatMap.get(booking.showtime.id)?.forEach((s) => {
      if (hold!.seatIds.includes(s.seatId)) s.status = "BOOKED";
    });
    holds.delete(hold!.holdToken);
  }
  // Immutable paid fixtures support gate/refund/history UI without a payment gateway.
  for (const channel of ["POS", "ONLINE"] as const) {
    const show = db.showtimes[0];
    const price = quote(show.id, [channel === "POS" ? 5 : 6]).total;
    const booking: Booking = {
      bookingId: channel === "POS" ? 1 : 2,
      bookingCode: channel === "POS" ? "BK-POS-DEMO" : "BK-ONLINE-DEMO",
      status: "PAID",
      channel,
      showtime: show,
      holdUntil: now(),
      serverTime: now(),
      plannedSeats: [
        {
          seatId: channel === "POS" ? 5 : 6,
          seatLabel: channel === "POS" ? "A5" : "A6",
          price,
        },
      ],
      concessions: [
        { itemId: 1, name: "Bắp rang", quantity: 1, unitPriceSnapshot: 40000 },
      ],
      totalAmount: price + 40000,
      tickets: [
        {
          ticketId: channel === "POS" ? 1 : 2,
          ticketCode: channel === "POS" ? "TK-DEMO-1" : "TK-DEMO-2",
          seatId: channel === "POS" ? 5 : 6,
          seatLabel: channel === "POS" ? "A5" : "A6",
          priceSnapshot: price,
          status: "VALID",
          showtimeId: show.id,
          movieTitle: show.movieTitle,
          auditoriumName: show.auditoriumName,
          startTime: show.startTime,
          ageRating: show.ageRating,
          qrPayload: channel === "POS" ? "TK-DEMO-1" : "TK-DEMO-2",
        },
      ],
      payment: {
        paymentStatus: "PAID",
        paymentMethod: channel === "POS" ? "CASH" : "GATEWAY_ONLINE",
        amount: price + 40000,
        paidAt: now(),
      },
    };
    bookings.push(booking);
    bookingOwners.set(booking.bookingId, 3);
    seatMap
      .get(show.id)!
      .find((s) => s.seatId === (channel === "POS" ? 5 : 6))!.status = "BOOKED";
  }
  function page<T>(rows: T[], url: URL) {
    const page = Number(url.searchParams.get("page") ?? 0),
      size = Number(url.searchParams.get("size") ?? 20);
    return {
      data: { items: rows.slice(page * size, (page + 1) * size),
        page,
        size,
        totalElements: rows.length,
        totalPages: Math.ceil(rows.length / size),
      },
    };
  }
  const json = (data: unknown, status = 200) =>
    Response.json({ data }, { status });
  async function handle(request: Request): Promise<Response> {
    try {
      expire();
      const url = new URL(request.url);
      const path = url.pathname.replace(/^.*\/api\/v1/, "");
      const method = request.method;
      const body =
        method === "GET" || method === "DELETE"
          ? {}
          : ((await request.json().catch(() => ({}))) as Record<
              string,
              unknown
            >);
      const token =
        request.headers.get("Authorization")?.replace("Bearer ", "") ?? "";
      const actor = sessions.get(token);
      if (path === "/auth/login" && method === "POST") {
        const username = String(body.username).split("@")[0].toLowerCase();
        if (body.password !== "Reference123!")
          return fail(
            401,
            "AUTH_INVALID_CREDENTIALS",
            "Tên đăng nhập hoặc mật khẩu không đúng",
          );
        if (username === "locked")
          return fail(401, "ACCOUNT_LOCKED", "Tài khoản bị khóa");
        const session = demo(
          username.startsWith("manager")
            ? "MANAGER"
            : username.startsWith("staff")
              ? "STAFF"
              : "CUSTOMER",
        );
        sessions.set(session.accessToken, session);
        return json(session);
      }
      if (path === "/auth/register" && method === "POST")
        return json(
          {
            ...demo("CUSTOMER").user,
            username: body.username,
            fullName: body.fullName,
          },
          201,
        );
      if (path === "/auth/transfers/consume") {
        const source = transfers.get(String(body.code));
        if (!source || source.user.role !== body.role)
          fail(401, "AUTH_TRANSFER_INVALID", "Mã chuyển phiên không hợp lệ");
        transfers.delete(String(body.code));
        const session = {
          ...source!,
          accessToken: "mock-session-" + crypto.randomUUID(),
        };
        sessions.set(session.accessToken, session);
        return json(session);
      }
      if (!actor) fail(401, "AUTH_TOKEN_INVALID", "Phiên không còn hiệu lực");
      const role = actor!.user.role;
      const requireRole = (...roles: Role[]) => {
        if (!roles.includes(role)) fail(403, "ACCESS_DENIED", "Không đủ quyền");
      };
      if (path === "/auth/me") return json(actor!.user);
      if (path === "/auth/logout") {
        sessions.delete(token);
        return new Response(null, { status: 204 });
      }
      if (path === "/auth/transfers") {
        const code = crypto.randomUUID().replaceAll("-", "").repeat(2);
        transfers.set(code, actor!);
        sessions.delete(token);
        return json({
          redirectUrl: `http://localhost:${role === "MANAGER" ? 3001 : role === "STAFF" ? 3002 : 3003}/#auth-transfer=${code}`,
        });
      }
      const movieMatch = path.match(/^\/movies(?:\/(\d+))?(?:\/status)?$/);
      if (movieMatch) {
        const id = Number(movieMatch[1]);
        if (method === "GET")
          return id
            ? json(
                db.movies.find((m) => m.id === id) ??
                  fail(404, "MOVIE_NOT_FOUND", "Không tìm thấy phim"),
              )
            : Response.json(
                page(
                  db.movies.filter(
                    (m) =>
                      (!url.searchParams.get("keyword") ||
                        m.title
                          .toLowerCase()
                          .includes(
                            url.searchParams.get("keyword")!.toLowerCase(),
                          )) &&
                      (!url.searchParams.has("active") ||
                        String(m.active) === url.searchParams.get("active")),
                  ),
                  url,
                ),
              );
        requireRole("MANAGER");
        if (!body.title && !id) fail(400, "VALIDATION_ERROR", "Cần tên phim");
        const row = id ? db.movies.find((m) => m.id === id) : undefined;
        if (id && !row) fail(404, "MOVIE_NOT_FOUND", "Không có phim");
        if (row) Object.assign(row, body);
        else
          db.movies.push({
            ...body,
            active: true,
            id: ++sequence,
          } as unknown as (typeof db.movies)[number]);
        return json(row ?? db.movies.at(-1));
      }
      if (path === "/showtimes" && method === "GET")
        return json(
          db.showtimes.filter(
            (s) =>
              (!url.searchParams.get("date") ||
                zoneDate(s.startTime) === url.searchParams.get("date")) &&
              (!url.searchParams.get("movieId") ||
                s.movieId === Number(url.searchParams.get("movieId"))),
          ),
        );
      const showId = Number(path.match(/^\/showtimes\/(\d+)$/)?.[1]);
      if (showId && method === "GET")
        return json(
          db.showtimes.find((s) => s.id === showId) ??
            fail(404, "SHOWTIME_NOT_FOUND", "Không có suất"),
        );
      const mapId = Number(path.match(/^\/showtimes\/(\d+)\/seats$/)?.[1]);
      if (mapId) {
        requireRole("STAFF", "CUSTOMER");
        return json({
          showtimeId: mapId,
          serverTime: now(),
          seats: seatMap.get(mapId)?.map((s) => ({
            ...s,
            heldByMe: [...holds.values()].some(
              (h) =>
                h.owner === token &&
                h.showtimeId === mapId &&
                h.seatIds.includes(s.seatId),
            ),
          })),
          ownHolds: [...holds.values()]
            .filter((h) => h.owner === token && h.showtimeId === mapId)
            .map(({ owner, createdAt, ...h }) => ({ ...h, serverTime: now() })),
        });
      }
      const holdShow = Number(path.match(/^\/showtimes\/(\d+)\/holds$/)?.[1]);
      if (holdShow) {
        requireRole("STAFF", "CUSTOMER");
        const ids = body.seatIds as number[];
        if (
          !ids?.length ||
          ids.some((i) => !Number.isInteger(i) || i <= 0) ||
          new Set(ids).size !== ids.length
        )
          fail(400, "VALIDATION_ERROR", "Ghế không hợp lệ");
        const inventory = seatMap.get(holdShow);
        if (!inventory) fail(404, "SHOWTIME_NOT_FOUND", "Không có suất");
        const selected = [
          ...new Set(
            ids.flatMap((id) => {
              const seat = inventory!.find((s) => s.seatId === id);
              if (!seat) fail(400, "INVALID_SEAT", "Không có ghế");
              return seat!.pairSeatId ? [id, seat!.pairSeatId] : [id];
            }),
          ),
        ].sort((a, b) => a - b);
        const existing = [...holds.values()].find(
          (h) =>
            h.owner === token &&
            h.showtimeId === holdShow &&
            h.seatIds.join() === selected.join(),
        );
        if (existing) {
          const { owner, createdAt, ...result } = existing;
          return json({ ...result, serverTime: now() });
        }
        if (
          scenario === "conflict" ||
          selected.some(
            (id) =>
              inventory!.find((s) => s.seatId === id)?.status !== "AVAILABLE",
          )
        )
          fail(
            409,
            "SEAT_NOT_AVAILABLE",
            "Ghế vừa được giữ. Vui lòng chọn lại.",
          );
        const held = {
          holdToken: crypto.randomUUID(),
          showtimeId: holdShow,
          seatIds: selected,
          serverTime: now(),
          holdUntil: new Date(
            Date.now() + (scenario === "expiry" ? 2000 : 300000),
          ).toISOString(),
          extensionUsed: false,
          owner: token,
          createdAt: Date.now(),
        };
        holds.set(held.holdToken, held);
        selected.forEach(
          (id) => (inventory!.find((s) => s.seatId === id)!.status = "HOLDING"),
        );
        const { owner, createdAt, ...result } = held;
        return json(result);
      }
      const holdMatch = path.match(/^\/holds\/([^/]+)(\/extend)?$/);
      if (holdMatch) {
        requireRole("STAFF", "CUSTOMER");
        const held = holds.get(holdMatch[1]);
        if (!held) {
          if (method === "DELETE") return new Response(null, { status: 204 });
          fail(409, "SEAT_HOLD_EXPIRED", "Hold hết hạn");
        }
        if (held!.owner !== token)
          fail(403, "SEAT_HOLD_NOT_OWNED", "Không sở hữu hold");
        if (holdMatch[2]) {
          requireRole("STAFF");
          if (held!.extensionUsed)
            fail(409, "SEAT_HOLD_ALREADY_EXTENDED", "Chỉ gia hạn một lần");
          if (Date.now() < held!.createdAt + 240000)
            fail(409, "SEAT_HOLD_TOO_EARLY", "Chưa tới phút thứ 4");
          if (body.waitingForTransfer !== true)
            fail(400, "VALIDATION_ERROR", "Phải đang chờ chuyển khoản");
          held!.holdUntil = new Date(
            Date.parse(held!.holdUntil) + 120000,
          ).toISOString();
          held!.extensionUsed = true;
          const { owner, createdAt, ...result } = held!;
          return json({ ...result, serverTime: now() });
        }
        seatMap.get(held!.showtimeId)?.forEach((s) => {
          if (held!.seatIds.includes(s.seatId)) s.status = "AVAILABLE";
        });
        holds.delete(held!.holdToken);
        return new Response(null, { status: 204 });
      }
      if (path === "/pricing/quote" && method === "POST") {
        requireRole("STAFF", "CUSTOMER", "MANAGER");
        const ids = body.seatIds;
        if (!Number.isSafeInteger(body.showtimeId) || Number(body.showtimeId) <= 0 ||
            !Array.isArray(ids) || ids.length === 0 || ids.length > 100 ||
            ids.some((id) => !Number.isSafeInteger(id) || id <= 0) || new Set(ids).size !== ids.length)
          fail(400, "VALIDATION_ERROR", "Danh sách ghế hoặc suất chiếu không hợp lệ");
        const showtimeId = body.showtimeId as number;
        const result = quote(showtimeId, [...(ids as number[])].sort((a, b) => a - b));
        return json({ showtimeId, seats: result.tickets, ticketTotal: result.total });
      }
      if (path === "/concessions" && method === "GET") {
        const keyword = url.searchParams.get("keyword")?.toLowerCase() ?? "";
        const active = role === "MANAGER" ? url.searchParams.get("active") : "true";
        const combo = url.searchParams.get("combo");
        return Response.json(page(inventory().filter((i) => i.name.toLowerCase().includes(keyword)
          && (!active || String(i.active) === active) && (!combo || String(i.combo) === combo)), url));
      }
      if (/^\/concessions\/\d+$/.test(path) && method === "GET") {
        const item = inventory().find((i) => i.id === Number(path.split("/")[2]));
        if (!item || (role !== "MANAGER" && !item.active)) fail(404, "CONCESSION_ITEM_NOT_FOUND", "Không có món");
        return json(item);
      }
      if (path === "/bookings" && method === "POST") {
        requireRole("STAFF", "CUSTOMER");
        const input = body as unknown as BookingInput;
        const existing = bookings.find((b) => owners.get(b.bookingId) === input.holdToken);
        if (existing) fail(409, "BOOKING_ALREADY_EXISTS", "Phiên giữ ghế này đã có Booking",
          bookingSessions.get(existing.bookingId) === token ? { bookingId: String(existing.bookingId) } : {});
        const held = holds.get(input.holdToken);
        if (
          !held ||
          held.owner !== token ||
          held.showtimeId !== input.showtimeId
        )
          fail(409, "SEAT_HOLD_EXPIRED", "Hold không hợp lệ");
        const show = db.showtimes.find((s) => s.id === input.showtimeId)!;
        if (
          role === "CUSTOMER" &&
          show.ageRating !== "P" &&
          !input.ageConfirmed
        )
          fail(422, "AGE_CONFIRMATION_REQUIRED", "Cần cam kết đủ tuổi");
        const q = quote(input.showtimeId, held!.seatIds);
        const booking: Booking = {
          bookingId: ++sequence,
          bookingCode: "BK-" + sequence,
          status: "PENDING",
          channel: role === "STAFF" ? "POS" : "ONLINE",
          showtime: show,
          holdUntil: held!.holdUntil,
          serverTime: now(),
          plannedSeats: q.tickets.map((t) => {
            const seat = seatMap
              .get(show.id)!
              .find((s) => s.seatId === t.seatId)!;
            return {
              seatId: t.seatId,
              seatLabel: seat.row + seat.number,
              price: t.finalPrice,
            };
          }),
          concessions: [],
          totalAmount: q.total,
          tickets: [],
        };
        recalculate(booking, input.concessions);
        bookings.push(booking);
        owners.set(booking.bookingId, input.holdToken);
        bookingOwners.set(booking.bookingId, actor!.user.id);
        bookingSessions.set(booking.bookingId, token);
        return json(booking);
      }
      const bookingId = Number(
        path.match(/^\/(?:me\/|pos\/)?bookings\/(\d+)/)?.[1],
      );
      if (bookingId) {
        const booking =
          bookings.find((b) => b.bookingId === bookingId) ??
          fail(404, "BOOKING_NOT_FOUND", "Không có đơn");
        if (
          role === "CUSTOMER" &&
          (booking.channel !== "ONLINE" ||
            bookingOwners.get(bookingId) !== actor!.user.id)
        )
          fail(404, "BOOKING_NOT_FOUND", "Không có đơn");
        if (path.startsWith("/pos/")) {
          requireRole("STAFF");
          if (booking.channel !== "POS")
            fail(404, "BOOKING_NOT_FOUND", "Không phải đơn POS");
        }
        if (method === "GET" && path.endsWith("/tickets")) return json(booking.tickets);
        if (method === "GET") {
          booking.serverTime = now();
          return json(booking);
        }
        if (method === "PATCH" || path.endsWith("/cancel")) fail(404, "RESOURCE_NOT_FOUND", "API không hỗ trợ sửa hoặc hủy đơn");
        if (path.includes("/payments/")) {
          const online = path.endsWith("/vnpay");
          if (!online) requireRole("STAFF");
          if (
            (online && booking.channel !== "ONLINE") ||
            (!online && booking.channel !== "POS")
          )
            fail(403, "ACCESS_DENIED", "Sai kênh");
          if (
            booking.showtime.ageRating !== "P" &&
            !online &&
            !path.endsWith("/confirm") &&
            body.ageDocumentChecked !== true
          )
            fail(422, "AGE_VERIFICATION_REQUIRED", "Cần kiểm giấy tờ");
          if (online) {
            booking.payment = {
              paymentStatus: "PENDING",
              paymentMethod: "GATEWAY_ONLINE",
              amount: booking.totalAmount,
            };
            return json({
              paymentUrl: `/payment-return?mockBooking=${bookingId}`,
              expiresAt: booking.holdUntil,
            });
          }
          if (path.endsWith("/cash")) {
            if (Number(body.cashReceived) < booking.totalAmount)
              fail(422, "INSUFFICIENT_CASH", "Tiền khách đưa chưa đủ");
            finalize(booking, "CASH");
            booking.payment!.cashReceived = Number(body.cashReceived);
            booking.payment!.change =
              Number(body.cashReceived) - booking.totalAmount;
            return json(booking.payment);
          }
          if (path.endsWith("/vietqr")) {
            booking.payment = {
              paymentStatus: "PENDING",
              paymentMethod: "VIETQR",
              amount: booking.totalAmount,
              qrPayload: `MOCK-VIETQR:${booking.bookingCode}:${booking.totalAmount}`,
              bankName: "Ngân hàng mô phỏng",
              bankAccount: "0000000000",
              transferContent: booking.bookingCode,
            };
            return json(booking.payment);
          }
          if (path.endsWith("/confirm")) {
            if (booking.payment?.paymentMethod !== "VIETQR")
              fail(409, "PAYMENT_NOT_PENDING", "Chưa có VietQR");
            finalize(booking, "VIETQR");
            return json(booking.payment);
          }
        }
      }
      if (path === "/me/bookings" || path === "/pos/bookings") {
        if (path.startsWith("/pos")) requireRole("STAFF");
        else requireRole("CUSTOMER");
        return Response.json(
          page(
            bookings
              .filter(
                (b) =>
                  b.channel === (path.startsWith("/pos") ? "POS" : "ONLINE") &&
                  (role !== "CUSTOMER" ||
                    bookingOwners.get(b.bookingId) === actor!.user.id) &&
                  (!url.searchParams.get("bookingCode") ||
                    b.bookingCode.includes(
                      url.searchParams.get("bookingCode")!,
                    )) &&
                  (!url.searchParams.get("date") ||
                    zoneDate(b.payment?.paidAt ?? b.serverTime) ===
                      url.searchParams.get("date")) &&
                  (!url.searchParams.get("paymentMethod") ||
                    b.payment?.paymentMethod ===
                      url.searchParams.get("paymentMethod")),
              )
              .reverse(),
            url,
          ),
        );
      }
      const ticketCodePath = path.match(/^\/tickets\/(TK-[^/]+)$/);
      if (ticketCodePath && method === "GET") {
        requireRole("STAFF", "CUSTOMER");
        const ticket = bookings.flatMap((b) => b.tickets).find((t) => t.ticketCode === decodeURIComponent(ticketCodePath[1]));
        if (!ticket) fail(404, "TICKET_NOT_FOUND", "Không có vé");
        return json(ticket);
      }
      if (path === "/tickets/lookup") {
        requireRole("STAFF");
        const code = url.searchParams.get("code");
        const tickets = bookings.flatMap((b) =>
          b.bookingCode === code
            ? b.tickets
            : b.tickets.filter((t) => t.ticketCode === code),
        );
        if (!tickets.length) fail(404, "TICKET_NOT_FOUND", "Không tìm thấy vé");
        return json(tickets);
      }
      if (path === "/tickets/validate") {
        requireRole("STAFF");
        if (!Number.isSafeInteger(body.showtimeId) || Number(body.showtimeId) <= 0 || !String(body.ticketCode ?? "").trim())
          fail(400, "VALIDATION_ERROR", "Mã vé và suất chiếu hợp lệ là bắt buộc");
        const ticket =
          bookings
            .flatMap((b) => b.tickets)
            .find((t) => t.ticketCode === body.ticketCode) ??
          fail(404, "TICKET_NOT_FOUND", "Không có vé");
        if (ticket.status !== "VALID")
          fail(409, "TICKET_NOT_VALID", "Vé đã dùng hoặc hủy");
        if (ticket.showtimeId !== Number(body.showtimeId))
          fail(422, "TICKET_WRONG_SHOWTIME", "Vé sai suất chiếu");
        if (ticket.ageRating !== "P" && body.ageDocumentChecked !== true)
          fail(422, "AGE_VERIFICATION_REQUIRED", "Chưa kiểm tuổi");
        ticket.status = "USED";
        return json(ticket);
      }
      if (path === "/refunds/eligibility" && method === "GET") {
        requireRole("STAFF", "MANAGER");
        const bookingCode = url.searchParams.get("bookingCode");
        const ticketCode = url.searchParams.get("ticketCode");
        if (Boolean(bookingCode) === Boolean(ticketCode))
          fail(400, "VALIDATION_ERROR", "Cần đúng một loại mã đơn hoặc vé");
        const code = bookingCode ?? ticketCode;
        const booking =
          bookings.find(
            (b) =>
              (bookingCode && b.bookingCode === code) ||
              (ticketCode && b.tickets.some((t) => t.ticketCode === code)),
          ) ?? fail(404, "BOOKING_NOT_FOUND", "Không có đơn hoặc vé");
        return json({
          bookingId: booking.bookingId,
          bookingCode: booking.bookingCode,
          channel: booking.channel,
          showtimeId: booking.showtime.id,
          startsAt: booking.showtime.startTime,
          serverTime: new Date().toISOString(),
          tickets: booking.tickets.map((t) => ({
            ticketId: t.ticketId,
            ticketCode: t.ticketCode,
            seatId: t.seatId,
            row: t.seatLabel.replace(/[0-9]/g, ""),
            seatNumber: Number(t.seatLabel.match(/[0-9]+$/)?.[0] ?? 0),
            status: t.status,
            priceSnapshot: t.priceSnapshot,
            refundableAmount: refundReason(t, booking) ? 0 : t.priceSnapshot,
            eligible: !refundReason(t, booking),
            reasonCode: refundReason(t, booking),
          })),
        });
      }
      if (path === "/manager-overrides" && method === "POST") {
        requireRole("STAFF");
        const ticketIds = refundSelection(body);
        if (body.scope !== "REFUND_APPROVAL" || typeof body.username !== "string" || !body.username.trim() || body.username.length > 50 ||
          typeof body.pin !== "string" || !/^[0-9]{6,12}$/.test(body.pin))
          fail(400, "VALIDATION_ERROR", "Yêu cầu duyệt không đúng định dạng");
        if (body.username !== "manager01" || body.pin !== "123456")
          fail(403, "MANAGER_PIN_INVALID", "PIN Manager không đúng");
        const booking = bookings.find((b) => b.bookingId === Number(body.bookingId))
          ?? fail(404, "BOOKING_NOT_FOUND", "Không có đơn");
        const tickets = refundableTickets(booking, ticketIds);
        const overrideToken = Array.from({ length: 64 }, () => Math.floor(Math.random() * 16).toString(16)).join("");
        const expiresAt = new Date(Date.now() + 180000).toISOString();
        overrides.set(overrideToken, {
          owner: token,
          expires: Date.now() + 180000,
          bookingId: booking.bookingId,
          ticketIds,
          reason: String(body.reason).trim(),
        });
        return json({
          overrideToken,
          expiresAt,
          approvedRefundAmount: tickets.reduce((sum, t) => sum + t.priceSnapshot, 0),
        });
      }
      if (path === "/manager-overrides/cash-confirmation" && method === "POST") {
        requireRole("STAFF");
        const submittedToken = body.overrideToken;
        if (typeof submittedToken !== "string" || !/^[a-f0-9]{64}$/.test(submittedToken))
          fail(400, "VALIDATION_ERROR", "Mã phê duyệt không đúng định dạng");
        const override = overrides.get(submittedToken as string)
          ?? fail(403, "MANAGER_OVERRIDE_REQUIRED", "Không tìm thấy phê duyệt");
        if (override.owner !== token)
          fail(403, "MANAGER_OVERRIDE_MISMATCH", "Phê duyệt không thuộc phiên Staff này");
        if (override.cashConfirmedAt) return json({ cashConfirmedAt: override.cashConfirmedAt });
        if (override.refund) fail(409, "REFUND_CASH_NOT_CONFIRMED", "Không có dấu thời gian bàn giao tiền");
        if (override.expires <= Date.now()) fail(403, "MANAGER_OVERRIDE_EXPIRED", "Phê duyệt đã hết hạn");
        const booking = bookings.find((b) => b.bookingId === override.bookingId)
          ?? fail(404, "BOOKING_NOT_FOUND", "Không có đơn");
        refundableTickets(booking, override.ticketIds);
        override.cashConfirmedAt = now();
        return json({ cashConfirmedAt: override.cashConfirmedAt });
      }
      if (path === "/refunds" && method === "POST") {
        requireRole("STAFF");
        const ids = refundSelection(body);
        if (typeof body.overrideToken !== "string" || !/^[a-f0-9]{64}$/.test(body.overrideToken))
          fail(400, "VALIDATION_ERROR", "Mã phê duyệt không đúng định dạng");
        const override = overrides.get(String(body.overrideToken))
          ?? fail(403, "MANAGER_OVERRIDE_REQUIRED", "Không tìm thấy phê duyệt");
        if (
          override.owner !== token ||
          override.bookingId !== Number(body.bookingId) ||
          JSON.stringify(override.ticketIds) !== JSON.stringify(ids) ||
          override.reason !== String(body.reason).trim()
        )
          fail(403, "MANAGER_OVERRIDE_MISMATCH", "Phê duyệt đã gắn với giao dịch khác");
        if (override.refund) return json(override.refund);
        const booking =
          bookings.find((b) => b.bookingId === Number(body.bookingId)) ??
          fail(404, "BOOKING_NOT_FOUND", "Không có đơn");
        const tickets = refundableTickets(booking, ids);
        if (!override.cashConfirmedAt && override.expires <= Date.now())
          fail(403, "MANAGER_OVERRIDE_EXPIRED", "Phê duyệt đã hết hạn");
        if (typeof override.cashConfirmedAt !== "string")
          fail(409, "REFUND_CASH_NOT_CONFIRMED", "Manager chưa xác nhận bàn giao tiền mặt");
        const cashConfirmedAt = override.cashConfirmedAt as string;
        const refund: Refund = {
          refundId: ++sequence,
          bookingId: booking.bookingId,
          refundAmount: tickets.reduce((n, t) => n + t.priceSnapshot, 0),
          ticketIds: ids,
          status: "COMPLETED",
          cashConfirmedAt,
          refundStatus: booking.tickets.every((t) => t.status === "CANCELLED" || ids.includes(t.ticketId)) ? "FULL" : "PARTIAL",
        };
        tickets.forEach((t) => {
          t.status = "CANCELLED";
          seatMap
            .get(t.showtimeId)!
            .find((s) => s.seatId === t.seatId)!.status = "AVAILABLE";
        });
        booking.refunds = [...(booking.refunds ?? []), refund];
        override.refund = refund;
        return json(refund);
      }
      if (path.startsWith("/reports/")) {
        requireRole("MANAGER");
        const from = url.searchParams.get("from") ?? "",
          to = url.searchParams.get("to") ?? "";
        if (from > to)
          fail(
            400,
            "VALIDATION_ERROR",
            "Ngày bắt đầu phải trước ngày kết thúc",
          );
        const paid = bookings.filter(
          (b) =>
            b.status === "PAID" &&
            (!from || zoneDate(b.payment!.paidAt!) >= from) &&
            (!to || zoneDate(b.payment!.paidAt!) <= to),
        );
        const totalRevenue = paid.reduce((n, b) => n + b.totalAmount, 0),
          refundAmount = paid.reduce(
            (n, b) =>
              n + (b.refunds ?? []).reduce((s, r) => s + r.refundAmount, 0),
            0,
          );
        const period = {
          from,
          to,
          timezone: "Asia/Ho_Chi_Minh",
          basis: "PAYMENT_DATE",
          asOf: new Date().toISOString(),
        };
        const ticketRevenue = paid
          .flatMap((b) => b.plannedSeats)
          .reduce((n, s) => n + s.price, 0);
        const data: Record<string, unknown> = {
          revenue: {
            period,
            totalRevenue,
            refundAmount,
            netRevenue: totalRevenue - refundAmount,
            exceptionPaidAmount: paid.filter((b) => b.status === "EXCEPTION_PENDING_DECISION").reduce((n, b) => n + b.totalAmount, 0),
          },
          "revenue-breakdown": {
            period,
            ticketRevenue,
            concessionRevenue: totalRevenue - ticketRevenue,
            ticketRefundAmount: refundAmount,
            netTicketRevenue: ticketRevenue - refundAmount,
            netRevenue: totalRevenue - refundAmount,
          },
          "revenue-by-channel": {
            period,
            posRevenue: paid
              .filter((b) => b.channel === "POS")
              .reduce((n, b) => n + b.totalAmount, 0),
            onlineRevenue: paid
              .filter((b) => b.channel === "ONLINE")
              .reduce((n, b) => n + b.totalAmount, 0),
            channels: ["POS", "ONLINE"].map((channel) => {
              const rows = paid.filter((b) => b.channel === channel);
              const grossRevenue = rows.reduce((sum, b) => sum + b.totalAmount, 0);
              const channelRefund = rows.reduce((sum, b) => sum + (b.refunds ?? []).reduce((refundSum, r) => refundSum + r.refundAmount, 0), 0);
              return { channel, grossRevenue, refundAmount: channelRefund, netRevenue: grossRevenue - channelRefund };
            }),
          },
          "ticket-count": {
            period,
            ticketCount: paid
              .flatMap((b) => b.tickets)
              .filter((t) => t.status !== "CANCELLED").length,
            grossTicketCount: paid.flatMap((b) => b.tickets).length,
            cancelledTicketCount: paid.flatMap((b) => b.tickets).filter((t) => t.status === "CANCELLED").length,
          },
          occupancy: {
            period,
            seatsSold: paid
              .flatMap((b) => b.tickets)
              .filter((t) => t.status !== "CANCELLED").length,
            totalSeats: db.seats.length * db.showtimes.length,
            occupancyRate: Math.round(
              (paid
                .flatMap((b) => b.tickets)
                .filter((t) => t.status !== "CANCELLED").length /
                (db.seats.length * db.showtimes.length)) *
                100,
            ),
            showtimes: db.showtimes.map((show) => {
              const seatsSold = paid.filter((b) => b.showtime.id === show.id).flatMap((b) => b.tickets).filter((t) => t.status !== "CANCELLED").length;
              const totalSeats = db.seats.length;
              return { showtimeId: show.id, movieId: show.movieId, startsAt: show.startTime, seatsSold, totalSeats, occupancyRate: totalSeats ? Math.round((seatsSold / totalSeats) * 100) : 0 };
            }),
          },
          "inventory-alerts": db.inventory.filter(
            (i) => i.stockQuantity <= i.lowStockThreshold,
          ).map((i) => ({ ...i, status: i.stockQuantity <= 0 ? "OUT_OF_STOCK" : "LOW_STOCK" })),
          "movies/ranking": {
            period,
            direction: "DESC",
            movies: db.movies
            .map((m) => ({
              movieId: m.id,
              title: m.title,
              ticketCount: paid
                .filter((b) => b.showtime.movieId === m.id)
                .flatMap((b) => b.tickets)
                .filter((t) => t.status !== "CANCELLED").length,
              totalRevenue: paid
                .filter((b) => b.showtime.movieId === m.id)
                .reduce(
                  (n, b) => n + b.totalAmount,
                  0,
                ),
              refundAmount: paid.filter((b) => b.showtime.movieId === m.id).reduce((n, b) => n + (b.refunds ?? []).reduce((sum, refund) => sum + refund.refundAmount, 0), 0),
              revenue: paid.filter((b) => b.showtime.movieId === m.id).reduce((n, b) => n + b.totalAmount - (b.refunds ?? []).reduce((sum, refund) => sum + refund.refundAmount, 0), 0),
            }))
            .sort((a, b) => b.revenue - a.revenue),
          },
        };
        const reportName = path.slice("/reports/".length);
        if (reportName === "movies/ranking") {
          const ranking = data[reportName] as { period: typeof period; direction: string; movies: { revenue: number }[] };
          const direction = url.searchParams.get("direction") === "ASC" ? "ASC" : "DESC";
          const movies = [...ranking.movies].sort((a, b) => direction === "ASC" ? a.revenue - b.revenue : b.revenue - a.revenue);
          return json({ ...ranking, direction, movies: movies.slice(0, Number(url.searchParams.get("limit") ?? 5)) });
        }
        return json(data[reportName]);
      }
      if (path === "/inventory/alerts") {
        requireRole("MANAGER", "STAFF");
        return json(
          db.inventory.filter((i) => i.stockQuantity <= i.lowStockThreshold),
        );
      }
      requireRole("MANAGER");
      if (path === "/inventory/restocks") {
        const rows = restocks.filter(
          (r) =>
            (!url.searchParams.get("itemId") ||
              r.itemId === Number(url.searchParams.get("itemId"))) &&
            (!url.searchParams.get("managerId") ||
              r.managerId === Number(url.searchParams.get("managerId"))) &&
            (!url.searchParams.get("from") ||
              zoneDate(r.createdAt) >= url.searchParams.get("from")!.slice(0, 10)) &&
            (!url.searchParams.get("to") ||
              zoneDate(r.createdAt) <= url.searchParams.get("to")!.slice(0, 10)),
        );
        return Response.json(page(rows.reverse(), url));
      }
      const restockItem = Number(
        path.match(/^\/inventory\/(\d+)\/restocks$/)?.[1],
      );
      if (restockItem) {
        const item =
          db.inventory.find((i) => i.itemId === restockItem) ??
          fail(404, "ITEM_NOT_FOUND", "Không có món");
        const quantity = Number(body.quantity);
        if (quantity <= 0 || !Number.isInteger(quantity))
          fail(400, "VALIDATION_ERROR", "Số lượng phải dương");
        const beforeQuantity = item.stockQuantity;
        item.stockQuantity += quantity;
        const record = {
          movementId: ++sequence,
          itemId: item.itemId,
          itemName: item.itemName,
          quantity,
          beforeQuantity,
          afterQuantity: item.stockQuantity,
          managerId: actor!.user.id,
          managerName: actor!.user.fullName,
          note: String(body.note),
          createdAt: now(),
        };
        restocks.push(record);
        return json(record);
      }
      if (path === "/inventory") {
        requireRole("MANAGER");
        const keyword = url.searchParams.get("keyword")?.toLowerCase() ?? "", status = url.searchParams.get("status");
        return Response.json(page(db.inventory.filter((i) => i.itemName.toLowerCase().includes(keyword) && (!status || status === (i.stockQuantity === 0 ? "OUT_OF_STOCK" : i.stockQuantity <= i.lowStockThreshold ? "LOW_STOCK" : "IN_STOCK"))), url));
      }
      const history = path.match(/^\/inventory\/(\d+)\/history$/);
      if (history && method === "GET") {
        requireRole("MANAGER");
        return Response.json(page(restocks.filter((r) => r.itemId === Number(history[1])).reverse(), url));
      }
      const seatsRoom = path.match(/^\/auditoriums\/(\d+)\/seats$/);
      if (seatsRoom) {
        const room = Number(seatsRoom[1]);
        const list = db.seats;
        if (method === "GET") return json(list);
        const seatsInput = Array.isArray(body.seats)
          ? (body.seats as Record<string, unknown>[])[0]
          : body;
        const seat = {
          seatId: ++sequence,
          row: String(seatsInput.row),
          number: Number(seatsInput.number),
          seatType: (seatsInput.seatType ?? seatsInput.type ?? "STANDARD") as Seat["seatType"],
          pairSeatId: null,
          status: "AVAILABLE",
          heldByMe: false,
        } as Seat;
        db.seats.push(seat);
        return json(Array.isArray(body.seats) ? [seat] : seat);
      }
      if (path.startsWith("/seats/sweetbox-pairs")) {
        const left = Number(body.seatId ?? body.seatId1 ?? path.split("/").at(-1)),
          right = Number(body.pairSeatId ?? body.seatId2);
        const a = db.seats.find((s) => s.seatId === left),
          b = db.seats.find((s) => s.seatId === right);
        if (method === "DELETE") {
          if (a) {
            const paired = db.seats.find((s) => s.seatId === a.pairSeatId);
            if (paired) {
              paired.pairSeatId = null;
              paired.seatType = "STANDARD";
            }
            a.pairSeatId = null;
            a.seatType = "STANDARD";
          }
          return new Response(null, { status: 204 });
        }
        if (!a || !b || a.row !== b.row || Math.abs(a.number - b.number) !== 1)
          fail(409, "INVALID_SWEETBOX_PAIR", "Cặp phải liền kề cùng hàng");
        a!.pairSeatId = right;
        b!.pairSeatId = left;
        a!.seatType = b!.seatType = "SWEETBOX";
        return json([a, b]);
      }
      const seatId = Number(path.match(/^\/seats\/(\d+)$/)?.[1]);
      if (seatId) {
        const seat = db.seats.find((s) => s.seatId === seatId);
        if (body.type && !body.seatType) body.seatType = body.type;
        Object.assign(seat ?? {}, body);
        return json(seat);
      }
      if (path.startsWith("/concessions/combos")) {
        requireRole("MANAGER");
        const comboId = Number(path.split("/")[3]);
        const existing = comboId
          ? db.concessions.find((c) => c.id === comboId)
          : undefined;
        if (existing) Object.assign(existing, body, { combo: true });
        else
          db.concessions.push({
            ...body,
            id: ++sequence,
            combo: true,
            active: body.active ?? true,
          } as unknown as Concession);
        inventory();
        return json(existing ?? db.concessions.at(-1), existing ? 200 : 201);
      }
      if (path.match(/^\/concessions\/\d+\/status$/)) {
        requireRole("MANAGER");
        const cId = Number(path.split("/")[2]);
        const existing = db.concessions.find((c) => c.id === cId);
        if (existing) existing.active = Boolean(body.active);
        inventory();
        return json(existing);
      }
      if (path.match(/^\/auditoriums\/\d+\/status$/)) {
        requireRole("MANAGER");
        const rId = Number(path.split("/")[2]);
        const existing = db.rooms.find((r) => r.id === rId);
        if (existing) existing.active = Boolean(body.active);
        return json(existing);
      }
      const resource = path.split("/")[1];
      const id = Number(path.split("/")[2]);
      const collections: Record<string, unknown[]> = {
        auditoriums: db.rooms,
        showtimes: db.showtimes,
        "pricing-rules": db.pricing,
        concessions: db.concessions,
        staff: db.staff,
      };
      const collection = collections[resource] as
        Record<string, unknown>[] | undefined;
      if (collection) {
        if (method === "GET") {
          if (["staff", "concessions"].includes(resource)) {
            const keyword = url.searchParams.get("keyword")?.toLowerCase() ?? "", active = url.searchParams.get("active"), combo = url.searchParams.get("combo"), status = url.searchParams.get("status");
            return Response.json(page(collection.filter((r) => String(r.name ?? r.fullName ?? "").toLowerCase().includes(keyword) && (!active || String(r.active) === active) && (!combo || String(r.combo) === combo) && (!status || r.status === status)), url));
          }
          return json(collection);
        }
        const existing = id ? collection.find((r) => r.id === id) : undefined;
        if (id && !existing)
          fail(404, "RESOURCE_NOT_FOUND", "Không có dữ liệu");
        if (resource === "showtimes") {
          const movie = db.movies.find((m) => m.id === Number(body.movieId));
          const room = db.rooms.find((r) => r.id === Number(body.auditoriumId ?? existing?.auditoriumId));
          if (!movie || !room)
            fail(400, "VALIDATION_ERROR", "Phim/phòng không hợp lệ");
          const start = Date.parse(String(body.startTime)),
            end = start + (movie!.durationMinutes + 15) * 60000;
          if (
            scenario === "schedule" ||
            db.showtimes.some(
              (s) =>
                s.id !== id &&
                s.auditoriumId === room!.id &&
                start < Date.parse(s.endTime) &&
                end > Date.parse(s.startTime),
            )
          )
            fail(409, "SHOWTIME_CONFLICT", "Lịch trùng phòng");
          Object.assign(body, {
            movieId: movie!.id,
            auditoriumId: room!.id,
            movieTitle: movie!.title,
            ageRating: movie!.ageRating,
            durationMinutes: movie!.durationMinutes,
            auditoriumName: room!.name,
            auditoriumType: room!.auditoriumType,
            endTime: new Date(end).toISOString(),
            basePrice: 70000,
          });
        }
        if (resource === "staff") {
          if (path.endsWith("/reset-password")) {
            const password = String(body.newPassword ?? "");
            if (Object.keys(body).some((key) => key !== "newPassword") || password.length < 8 || new TextEncoder().encode(password).length > 72)
              fail(400, "VALIDATION_ERROR", "Mật khẩu phải từ 8 ký tự và tối đa 72 byte UTF-8");
            return new Response(null, { status: 204 });
          }
          if (path.endsWith("/status")) {
            if (Object.keys(body).some((key) => key !== "active") || typeof body.active !== "boolean")
              fail(400, "VALIDATION_ERROR", "Trạng thái yêu cầu active boolean");
            if (existing)
              existing.status =
                body.active === false || body.status === "LOCKED"
                  ? "INACTIVE"
                  : "ACTIVE";
            return json(existing);
          }
          if (!existing) {
            if (String(body.password ?? "").length < 8)
              fail(400, "VALIDATION_ERROR", "Cần mật khẩu ít nhất 8 ký tự");
            body.status = "ACTIVE";
          }
          delete body.password;
        }
        if (resource === "auditoriums" && body.type && !body.auditoriumType) {
          body.auditoriumType = body.type;
        }
        if (existing) Object.assign(existing, body);
        else collection.push({ ...body, id: ++sequence });
        if (resource === "concessions") inventory();
        return json(existing ?? collection.at(-1), existing ? 200 : 201);
      }
      return fail(404, "RESOURCE_NOT_FOUND", "API chưa được mô phỏng");
    } catch (e) {
      if (e instanceof Failure)
        return Response.json(
          {
            timestamp: now(),
            status: e.status,
            code: e.code,
            message: e.message,
            details: e.details,
            path: new URL(request.url).pathname,
          },
          { status: e.status },
        );
      throw e;
    }
  }
  return {
    handle,
    setScenario: (value: string) => {
      scenario = value;
    },
    completeOnline: (id: number) => {
      const b = bookings.find((b) => b.bookingId === id);
      if (b) finalize(b, "GATEWAY_ONLINE");
    },
    db,
  };
}
export const mockApi = createMockApi();
