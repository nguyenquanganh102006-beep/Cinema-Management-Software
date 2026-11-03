# Master data implementation decisions

## Scope

In scope: Staff, Movies, Auditoriums and Seats, Sweetbox pairs, Pricing rules, F&B catalog (items and combos) and Inventory (stock view, restock, restock history, low-stock alerts), all Manager-facing APIs under `/api/v1`.

Out of scope, belongs to later sprints: showtimes, seat holds, POS checkout, payment, ticketing, refunds, reports, the price quote endpoint (`POST /pricing/quote` needs a showtime).

The schema already contains the master data tables and constraints (extra movie fields, `auditoriums.is_active`, seat-pair constraints, pricing constraints, `inventory_transactions`). No table is recreated. The only new migration is V5 (see Database).

## Cross-cutting decisions

**Authorization.** Management endpoints are protected on the backend with `@PreAuthorize("hasRole('MANAGER')")` at class level. Two read exceptions follow the backend plan: `GET /movies` and `GET /concessions` are open to any authenticated role, and `GET /inventory/alerts` is open to Manager and Staff. Non-managers only ever see active movies and active F&B items; an inactive one answers 404, and a non-manager cannot widen the filter with `active=false`.

**Contract.**
- Success wraps `data` as in Foundation. Paged lists return `data = {items, page, size, totalElements, totalPages}` (`PageResponse`); `ApiResponse` itself is unchanged. Page size is 0-based and capped at 100, otherwise 400 `VALIDATION_ERROR`. Small reference lists (auditoriums, seats, pricing rules, alerts) are not paged.
- `POST` creates and returns 201. `PATCH /{id}` **replaces all editable fields** (an omitted optional field is cleared), it is not a partial merge. Status changes use `PATCH /{id}/status` with `{"active": boolean}`. Fields that must not be editable (role, username, rule code, pair pointers) are absent from the request DTOs, and unknown JSON fields are rejected by the Foundation Jackson setting.
- Request DTOs trim strings; optional blank strings become null. Entities are never returned.
- There is **no DELETE endpoint** for any resource. Removal is deactivation, which keeps history and foreign keys intact.

**Concurrency.** Writes that depend on a read use pessimistic row locks inside one `@Transactional` method: the auditorium row for every seat write and capacity change, the seat's auditorium for seat updates, the inventory row for restocks, the pricing rule row for updates, the concession row for edits and status changes. Database constraints stay as the final guard, and a violation that slips past a service check is translated into the same business error code instead of the generic `RESOURCE_CONFLICT`.

**Error codes added.** Names are ours unless marked (plan).

| Area | Codes |
|---|---|
| Staff | `STAFF_NOT_FOUND`, `USERNAME_ALREADY_EXISTS`, `EMAIL_ALREADY_EXISTS`, `PHONE_ALREADY_EXISTS` |
| Movies | `MOVIE_NOT_FOUND` (plan) |
| Auditoriums, seats | `AUDITORIUM_NOT_FOUND` (plan), `AUDITORIUM_NAME_EXISTS`, `AUDITORIUM_CAPACITY_EXCEEDED`, `AUDITORIUM_CAPACITY_BELOW_SEATS`, `SEAT_NOT_FOUND` (plan), `SEAT_POSITION_TAKEN`, `SEAT_IS_PAIRED` |
| Sweetbox | `SWEETBOX_PAIR_INVALID` (plan), `SWEETBOX_ALREADY_PAIRED`, `SWEETBOX_PAIR_NOT_FOUND` |
| Pricing | `PRICING_RULE_NOT_FOUND`, `PRICING_RULE_INVALID`, `PRICING_RULE_EXISTS`, `PRICING_RULE_CODE_EXISTS` |
| F&B, inventory | `CONCESSION_ITEM_NOT_FOUND` (plan), `CONCESSION_NAME_EXISTS`, `CONCESSION_COMBO_INVALID`, `CONCESSION_ITEM_IN_ACTIVE_COMBO`, `CONCESSION_COMBO_COMPONENT_INACTIVE`, `INVALID_STOCK_QUANTITY` (plan) |

**Entity ids follow the SQL type.** Hibernate only validates, so the Java type must match the column: `auditorium_id`, `rule_id` and `item_id` are `SERIAL` and map to `Integer`; `seat_id`, `movement_id` and `user_id` are `BIGSERIAL` and map to `Long`.

**Query parameters.** Where a parameter would otherwise be type-converted by Spring (booleans, ids, instants in new list endpoints), the service reads it as a string and returns 400 on a bad format. `GlobalExceptionHandler` does not handle `MethodArgumentTypeMismatchException`, so an unparsable typed parameter or path variable currently becomes 500. **[OPEN]** Add a handler returning 400 `VALIDATION_ERROR` in a separate small change, because the file is shared.

## Staff

- The API manages only users with role `STAFF`. Manager and Customer accounts are invisible: their ids answer 404 `STAFF_NOT_FOUND`.
- Create takes username, full name, email, phone and an initial password. The role is fixed to `STAFF` in the service and is not in the request; `role`, `status` and other internal fields are rejected as unknown. Username and email are lowercased, the password uses the Foundation BCrypt rules (8 characters, at most 72 UTF-8 bytes) and is never returned.
- Update changes full name, email and phone only. Username is immutable.
- Deactivation sets `INACTIVE` and increments `security_version`, so existing tokens stop working immediately and do not come back on reactivation.
- **[OPEN]** What `active=true` does to a `LOCKED` account (currently sets `ACTIVE`).
- **[OPEN]** Password reset is not implemented (the backend plan lists `POST /staff/{id}/reset-password`, the sprint brief does not). A reset must also increment `security_version`.
- **[OPEN]** Where the Manager's override PIN is created and changed. `users.manager_pin_hash` exists but no API sets it for new managers.

## Movies

- Required: title (200), duration in minutes (> 0), age rating (`P`, `T13`, `T16`, `T18`) and **release date**, because `release_date` is `NOT NULL` even though the brief does not list it. **[OPEN]**
- Optional: description (limited to 5000 characters, our limit, the column is unbounded), genre, country, language, director, poster, trailer. Poster and trailer must be `http` or `https` URLs.
- Status is changed only through the status endpoint. Duplicate titles are allowed because the schema has no constraint.
- **[OPEN]** Deactivating a movie that has upcoming showtimes. Left as a `TODO` for the showtime sprint.
- **[OPEN]** Whether the movie list needs a release date or "coming soon" notion for the customer portal.

## Auditoriums and seats

- **Capacity.** `total_seats` is a declared capacity (`> 0`, required by the schema, so an auditorium cannot be created empty). The number of seats may never exceed it, and capacity cannot be lowered below the current seat count. **[OPEN]** The alternative is a computed capacity.
- **Type mapping.** `auditorium_type_enum` has the value `3D`, which cannot be a Java constant. The Java enum `AuditoriumType` uses `THREE_D`, while API and database use the exact string value (`STANDARD_2D`, `3D`, `IMAX`). The entity stores the string and uses `@ColumnTransformer(write = "cast(? as auditorium_type_enum)")` with an explicit `columnDefinition`. `@JdbcTypeCode(NAMED_ENUM)` on a `String` field fails Hibernate startup with a `NullPointerException`, so it must not be used here.
- **Names.** Auditorium names are unique ignoring case. V1's plain `UNIQUE` is case-sensitive, so V5 adds a unique index on `lower(name)`; the service compares with `lower()` to match it.
- **Seats.** Row is 1–5 letters, upper-cased; number is positive; each `(auditorium, row, number)` is unique. Seats are added in batches of 1–500, all or none. Seats have no status column in the schema, so a seat cannot be disabled. **[OPEN]** Add `seats.is_active` (new migration) or drop the idea.
- A paired seat cannot change position or type until unpaired (`SEAT_IS_PAIRED`).
- No delete for auditoriums or seats. `showtime_seats` references `seats` without cascade, so deleting used seats would fail later anyway.
- **[OPEN]** Auditorium deactivation when showtimes exist. `TODO` for the showtime sprint.

## Sweetbox pairs

- `POST /seats/sweetbox-pairs` with `seatId` and `pairSeatId`. Both seats must exist, be different, belong to the **same auditorium**, and both have type `SWEETBOX`. **[OPEN]** Whether to require `SWEETBOX` type.
- A seat can belong to one pair only. A seat that already points to another seat, or is pointed to by another seat, is rejected with 409. Both pointers are written in one transaction, so the pair is always symmetric. The unique index on `pair_seat_id` is the backstop.
- `DELETE /seats/sweetbox-pairs/{seatId}` clears the pair from either seat, and also repairs a one-way pointer found in old data. A seat with no pair answers 404 `SWEETBOX_PAIR_NOT_FOUND`.
- **[OPEN]** Same row and adjacent seat numbers are **not** enforced. Foundation left adjacency to later modules; the check belongs in `SweetboxService.pair` if the team wants it now.
- **[OPEN]** One ticket or two for a pair, and whether a hold counts as one or two seats. This affects ticketing, hold and refund sprints, so decide early.

## Pricing

- Rule values are the approved seed from V3 and are not changed by the code: base price 70,000 VND; room surcharge 2D 0, 3D 20,000, IMAX 40,000; seat surcharge STANDARD 0, VIP 15,000, SWEETBOX 15,000; time surcharge 10,000 applied **once** when the showtime is Friday to Sunday **or** starts after 18:00.
- Seat price = base + room surcharge + seat surcharge + time surcharge. The application of the formula belongs to the quote, not to this sprint.
- **[OPEN]** Confirm before coding any further: Sweetbox +15,000 is per physical seat of the pair; "weekend" starts on Friday (V3 comment) and not Saturday; "after 18:00" excludes exactly 18:00.
- Valid `(rule_type, target_ref)` pairs are exactly the eight seeded ones (`PricingRuleCatalog`). `PATCH` changes only `amount` and `description`; code, type and target are identity used by the price engine.
- Amount is non-negative with at most 8 integer and 2 decimal digits (`NUMERIC(10,2)`); zero is allowed, including for the base price. **[OPEN]** Whether the base price must be positive, and whether VND should be restricted to whole numbers.
- `POST` is implemented but almost unusable: every valid pair already exists and rules cannot be deleted, so it answers 409. **[OPEN]** Remove it unless new room or seat types are planned.
- Changing a price affects only showtimes and bookings created afterwards, assuming they store the price at creation. Verify in the showtime sprint.
- **[OPEN]** Audit trail of who changed a price and when (not implemented).

## F&B catalog

- Items are simple (`is_combo = false`) or combos. Creating a simple item also creates its inventory row (stock 0, threshold 10, the schema default) in the same transaction. Combos have no inventory row.
- Names are unique ignoring case at service level. The database constraint is case-sensitive, so two concurrent requests differing only by case can both pass. **[OPEN]** Add a `lower(name)` unique index like V5 in a later migration.
- Combo components must be existing simple items that are **active**, unique within the combo, with positive quantity; a combo cannot contain a combo. Updating a combo replaces the whole component list.
- Deactivating an item that belongs to an active combo is rejected (`CONCESSION_ITEM_IN_ACTIVE_COMBO`); deactivate the combo first. Activating a combo requires every component to be active. **[OPEN]** Block, as now, or deactivate affected combos automatically.
- Item and combo edits use separate endpoints; an id of the wrong kind answers 404.

## Inventory

- Stock exists per simple item. Restock takes a positive **integer** quantity and an optional note (500 characters). The request field is a `BigDecimal` so that `1.5` or `5.0` is rejected; as a plain `Integer`, Jackson would silently truncate. Quantity must keep the stock within the integer range, otherwise `INVALID_STOCK_QUANTITY`.
- One transaction locks the inventory row, adds the quantity, and appends an `inventory_transactions` row of type `RESTOCK` with quantity, before and after balance, manager and timestamp. The manager is read from the token, never from the request. The API offers no way to update or delete history rows.
- Alerts list **active** simple items whose stock is **less than or equal to** the threshold, lowest stock first. Status is `OUT_OF_STOCK` at 0, `LOW_STOCK` at or below the threshold, otherwise `IN_STOCK`.
- The threshold is the schema default (10) and has no edit endpoint. **[OPEN]** Whether managers need to set it, per item.
- Restocking an inactive item is allowed.
- **[OPEN]** Correcting a wrong restock. With append-only history the correction would be an `ADJUSTMENT` movement, which this sprint does not create.
- Not covered by tests yet: rollback when writing the history row fails (backend plan `TC-RESTOCK-02`).

## Database

- **V5** `V5__auditorium_name_case_insensitive_unique.sql` adds `uq_auditoriums_name_ci` on `auditoriums(lower(name))`. It fails if existing rows already differ only by case. Migrations V1–V4 are untouched.
- `FoundationIntegrationTest` counts successful Flyway migrations (now 5). Every later migration must update it, which makes it a merge conflict hotspot; consider asserting that all migrations succeeded instead of counting them.
- If a second pull request needs a migration at the same time, agree the version number first, because Flyway rejects two files with the same version.

## Testing

Integration tests run against real PostgreSQL through Testcontainers (`postgres:18.1`) or `DB_TEST_URL`. Each group covers input validation, business rules, role checks (401/403) and database constraints. Tests that need real concurrency (auditorium names, seat writes, sweetbox pairing, restocks) commit data and use unique names. The pricing tests run inside a rolled-back transaction because they edit the seeded rules that `FoundationIntegrationTest` asserts.
