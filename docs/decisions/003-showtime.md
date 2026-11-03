# Sprint C Showtime implementation decisions

Date: 2026-11-03, Asia/Saigon. Sprint ownership follows the user's assignment: A Foundation, B Master Data, C Showtime, D POS/Seat Holding, E Checkout/Ticket, F Refund/Reports.

## Verified baseline

Official repository: `clone/Cinema-Management-Software` in the supplied workspace. At first inspection, main was clean at `1aae6e4` (initial commit). A fetch advanced origin/develop to `ae58471`, merge PR #2. The graph proves:

- Foundation `a64ef34` was merged by `0e49de8` (PR #1).
- Master Data commits `8399659` through `d909011` descend from `0e49de8`.
- `origin/develop` at `ae58471` contains both sprint tips. Its tree equals `d909011`.
- Working branch `feature/showtime` was created at `ae58471`. No merge was needed. At preparation time no commit, push or PR was performed. The user later explicitly requested publication to origin/feature/showtime; that remote initially pointed to ancestor `1aae6e4`, permitting a normal fast-forward push without changing develop or main.

No applicable AGENTS.md was found. Historical A/B commits place application code directly under `com.cinema.cms.{controller,service,dto,repository,entity}`, tests under `backend/src/test/java/com/cinema/cms`, API docs under `docs/api`, decisions under `docs/decisions`, migrations under `backend/src/main/resources/db/migration` and setup/test scripts under `scripts`. C follows those locations. The old external backend is reference material only.

## Sources and traceability

Sources read include the original workspace's FUNCTIONAL REQUIREMENTS, BUSINESS RULES, NON-FUNCTIONAL REQUIREMENTS, use case, specifications, architecture v3, mandatory screens, database description/ERD and AD/SD/SMD diagrams. Their content was inspected previously; documents are not duplicated into this repository. The current repository's `docs/database/foundation-schema.md`, `001-foundation.md`, `002-master-data.md`, migrations V1–V5 and actual A/B code govern integration details. The old backup is not the current schema authority.

| Requirement / use case | Source | Implementation | Status / action |
|---|---|---|---|
| FR-17, UC-08 create showtime | FR, architecture v3 §15 | ShowtimeController/Service, shared Movie/Auditorium/Seat repositories | Implemented; active existing master data required |
| FR-18, BR-08 duration + buffer | FR, BR | ShowtimeTimeService | Implemented; persisted end includes 15 minutes, midnight and bounds tested |
| FR-19, BR-09 no same-room overlap | FR, BR, architecture v3 | Auditorium row lock, ShowtimeRepository.countOverlaps | Implemented; half-open intervals and real concurrent writes tested |
| FR-20, BR-10 inventory initialization | FR, BR | ShowtimeSeat, ShowtimeSeatRepository, create transaction | Implemented; all physical seats, AVAILABLE, flush/rollback tested |
| BR-11/12 shared inventory boundary | BR, schema handoff | One existing showtime_seats table | Implemented initialization only; no separate POS/Online stock |
| FR-13, BR-04 physical Sweetbox pair | FR, BR, foundation schema handoff | ShowtimeService.validateSeats | Reject malformed pair at creation; no change to B's pair API and no hold/ticket behavior |
| FR-21, UC-09 browse/filter | FR, architecture v3 §15, mandatory screens | list/detail, PageResponse | Implemented; movie/room/date filters, stable page and historical reads |
| PATCH /showtimes/{id}, BR-40 | Architecture v3 §15, history preservation; B contract | update transaction, ticket existence query, inventory locks | Implemented safe movie/time replacement; no room change/deletion |
| BR-01/06, common base | BR, V3 seed, B PricingRuleCatalog | Shared PricingRuleRepository lookup by type/target | Implemented; amount is not hardcoded; no ticket surcharge/quote |
| FR-03, NFR-06 | FR/NFR, Foundation security | Existing JWT/RBAC + controller @PreAuthorize | Implemented; authenticated reads, Manager writes |
| NFR-02/03/11 | NFR, schema/locking handoff | READ_COMMITTED, shared room lock, JPA flush, existing constraints | PostgreSQL rollback/uniqueness/concurrency covered |
| NFR-15/21/22 | NFR, A/B conventions | ApiException, shared handler, Clock, business zone, OpenAPI/docs | Implemented within C; no shared-format replacement |
| FR-22 onward seat map/hold | FR, user sprint ownership | Future D | Out of scope; no placeholder endpoint |
| Ticket/checkout/payment/refund/report | User sprint ownership, FR | Future E/F and collaborators | Out of scope; ticket table queried only to preserve history |

## Integration choices

1. **Five shared packages.** Ordinary Spring services with constructor injection and `@Transactional`. No standalone Store/Transactions ports, parallel application, dependency or duplicated master entity.
2. **Existing schema.** Instant maps TIMESTAMPTZ, not the old backup's LocalDateTime/timestamp. Scalar FK ids follow B entity conventions. Showtime IDs/movie IDs/seat IDs are Long; auditorium IDs are Integer. ShowtimeSeat has the existing PostgreSQL enum and `@Version Integer`. No migration is required; V1–V5 and POM are untouched.
3. **Existing API shapes.** DTO uses `id` like B and existing `ApiResponse`/`PageResponse`; error code VALIDATION_ERROR comes from Foundation. Unknown properties are already rejected globally. New field-specific numeric deserializers prevent fractional JSON ids from silently selecting a different master record; they do not alter global Jackson behavior.
4. **PATCH convention.** Master Data explicitly replaces all editable fields. C therefore requires both movieId and startTime, with immutable room/basePrice and no null merge semantics. Architecture v3 lists PATCH without prescribing a body; the optional-field behavior existed only in the old standalone C implementation. The difference is documented for frontend consumers.
5. **Common base.** The official V3 rule code is BASE_STANDARD, not old BASE_2D. Lookup uses the stable BASE_PRICE / STANDARD_2D identity. Price remains the showtime's creation snapshot on update; checkout pricing remains E's responsibility. There is no fictitious call to a nonexistent quote service.
6. **Inventory.** Initialize exactly the physical seats present under the room lock, including each physical Sweetbox seat. Capacity may exceed count per B's declared-capacity decision. Never rebuild/delete inventory on PATCH.
7. **Read query.** Entities here contain only six scalar showtime fields and no lazy master relationships. List uses one bounded query plus a count when needed, maps DTOs inside the transaction and avoids N+1. Date follows local start day, not all days occupied.

## Transaction and lock contract

CREATE: normalize input → lock existing Auditorium PESSIMISTIC_WRITE → read master movie/physical seats → validate pairs → compute end → query overlap → read base → insert Showtime → insert all inventory → flush → commit.

PATCH: read immutable auditorium id as a scalar → lock Auditorium → lock Showtime → lock all ShowtimeSeat rows ordered by seat id → reject unavailable rows or any Ticket history → validate movie → recalculate time → query overlap excluding self → update/flush/commit. Base and inventory remain unchanged. Room-first ordering serializes every C schedule writer and matches B's seat-layout write lock. READ_COMMITTED ensures overlap queries after waiting see the previous writer's commit.

The service is the transaction entry point. Do not wrap it in an outer transaction with a different isolation/snapshot. No HTTP calls occur inside these transactions. A query check without the room lock is insufficient: two creates could both observe no conflicting row before insertion.

Future D/E writers must lock the same inventory rows in ascending seat-id order and re-read the current showtime after acquiring inventory locks. They must not hold seat locks and then wait for a Showtime/Auditorium lock that PATCH owns. Ticket insertions must follow that protocol; the C guard cannot protect arbitrary SQL writers that ignore it. No D/E workflow was invented here.

## Old Showtime assessment

| External old component | Classification | Action in official repository |
|---|---|---|
| Time/buffer/offset cases | ADAPT | Preserve math and explicit business zone; change storage to Instant/TIMESTAMPTZ, microsecond precision and shared Clock |
| ShowtimeConflictService | ADAPT | Keep overlap predicate in the database query under real room lock; standalone helper not copied |
| ShowtimeService | REWRITE | Replace fake ports with shared repositories, Spring transaction, real master entities and API conventions |
| CommonBasePriceResolver / ShowtimeBasePriceResolver | ADAPT | Use B's real type/target lookup directly, without an extra adapter/service layer |
| ShowtimeStore / ShowtimeTransactions | REMOVE from candidate contribution | Real Spring Data repositories and transaction annotations replace these ports |
| ShowtimeException | REMOVE from candidate contribution | Reuse shared ApiException/handler |
| Create/Update/Response DTOs | ADAPT | Existing validation, id naming, full-field PATCH and Instant-based output |
| ShowtimeFilter / ShowtimePage | REMOVE from candidate contribution | Reuse shared QueryParams/PageResponse instead |
| Old fake test runner | REWRITE | JUnit + MockMvc + real PostgreSQL tests, independent time unit tests |
| External test-showtime.ps1 / build | REMOVE from candidate contribution | Maven Wrapper and existing test conventions replace them; no copies committed |

During implementation, external reference files were left untouched. In the subsequent user-requested cleanup, the superseded external backend, old test script, two obsolete C documents and build outputs were moved to the Windows Recycle Bin after dependency checks. Original requirements, diagrams and database backup remain. No old component was copied unchanged (KEEP) because its integration contracts differed.

## Decisions still owned by the team

- B allows changing movie status/metadata and auditorium status/type; it does not lock every such update against scheduling. Requirements do not state a definitive policy for deactivating a master record with future shows. C rejects inactive data when writing, preserves historical reads and adds no speculative ban or refactor to B. A later concurrent deactivation may leave an existing show linked to inactive master data; that policy needs an explicit decision.
- Seat layout/pair metadata is mutable in B. C captures inventory at creation; it does not propagate later layout additions into existing shows. Decide edit restrictions or snapshot rules before D/E rely on layout stability.
- B's Sweetbox creation API permits non-adjacent pairs, while BR-04 requires adjacency. C refuses to schedule that invalid layout, without changing B's API. B can repair it using existing unpair/edit/pair operations.
- Ticket USED lifecycle, late IPN after hold expiry, financial exceptions, Sweetbox partial refund and revenue definitions remain outside C. No arbitrary state or workaround is introduced here.
- No requirement establishes a showtime status lifecycle, DELETE endpoint, past-date creation ban or room reassignment workflow; none is added.

These handoff decisions do not block the four documented C operations on valid current master data. See [API](../api/showtimes.md) and [validation report](../architecture/showtime-validation.md).
