# Showtime API

Base path `/api/v1/showtimes`. Authorization uses the existing Foundation Bearer JWT and current account role/status checks. No new authentication mechanism.

| Method | Path | Role | Result |
|---|---|---|---|
| POST | `/showtimes` | MANAGER | 201, creates showtime and all AVAILABLE inventory atomically |
| GET | `/showtimes` | Any authenticated user | 200, filtered and paged list |
| GET | `/showtimes/{id}` | Any authenticated user | 200, detail; 404 if missing |
| PATCH | `/showtimes/{id}` | MANAGER | 200, replaces editable movie/time fields |

## Create

```json
{"movieId": 1, "auditoriumId": 2, "startTime": "2026-11-14T19:30:00+07:00"}
```

All fields are required and non-null. IDs must be positive JSON integers in the Long/Integer range; decimals and numeric strings are rejected. Time must be an ISO date-time string with an offset. Unknown fields are rejected by Foundation Jackson configuration, including `endTime`, `basePrice`, `status` and seat data.

The service verifies an active movie and auditorium, positive movie duration and non-empty physical seat inventory. Sweetbox seats must form symmetric, adjacent pairs in the same row and auditorium. A configured capacity may exceed the current physical seat count: inventory uses actual physical seats, following Master Data's declared-capacity convention.

`endTime = startTime + movie.durationMinutes + 15 minutes`. Occupancy uses `[startTime, endTime)`; an adjacent show may begin exactly at endTime. The common BASE_PRICE / STANDARD_2D rule supplies basePrice, without room/seat/time surcharges. No ticket quote is implemented here.

## Update

```json
{"movieId": 1, "startTime": "2026-11-14T20:00:00+07:00"}
```

Both fields are required, following Master Data's replacement PATCH convention. This differs from the old standalone Showtime's optional-field PATCH. Omitted or explicit null fields return 400. The auditorium is immutable in this scope. `auditoriumId`, `endTime`, `basePrice` and unknown properties return 400.

Any Ticket (VALID, USED or CANCELLED), or any inventory row not AVAILABLE, blocks modification. This includes expired HOLDING rows until the responsible hold flow releases them. Update recalculates the end time and excludes itself from conflict detection. Existing basePrice and every showtime-seat ID/mapping are preserved; no inventory is deleted or rebuilt. Reads do not recompute historical times when the master movie duration changes.

## Responses

Single resource:

```json
{
  "data": {
    "id": 10, "movieId": 1, "auditoriumId": 2,
    "startTime": "2026-11-14T19:30:00+07:00",
    "endTime": "2026-11-14T21:45:00+07:00",
    "basePrice": 70000.00
  }
}
```

List: `{"data":{"items":[...],"page":0,"size":20,"totalElements":1,"totalPages":1}}`, using shared `ApiResponse<PageResponse<ShowtimeResponse>>`. No separate `meta` wrapper or JPA entities.

Optional filters: `movieId`, `auditoriumId`, `date` (`YYYY-MM-DD`). `page` defaults to 0; `size` defaults to 20, range 1–100. Invalid formats/ranges or an offset beyond JPA's supported integer range return 400. Sort is `startTime ASC, id ASC`.

Date means the local **start date** in `Asia/Ho_Chi_Minh`, using a half-open midnight-to-midnight instant range. A show starting 23:30 and ending the next day belongs to its start date. There is no undocumented future-only or movie/room-active filter on historical reads.

Storage uses Foundation's `Instant` / TIMESTAMPTZ. Response times use the business-zone offset. Request times are truncated to PostgreSQL microseconds before conflict detection/storage. Supported business-local years are 0001–9999, including computed end time.

## Error contract

The existing `GlobalExceptionHandler` returns `timestamp/status/code/message/details/path`. No changes to that shared handler or SecurityConfig were needed.

| HTTP | Code | Meaning |
|---|---|---|
| 400 | VALIDATION_ERROR | Invalid JSON/body, unknown or missing fields, malformed id/filter/time |
| 401 | Foundation auth codes | Missing, invalid, expired or revoked JWT |
| 403 | ACCESS_DENIED | Staff/Customer attempted a write |
| 404 | MOVIE_NOT_FOUND / AUDITORIUM_NOT_FOUND / SHOWTIME_NOT_FOUND | Missing resource |
| 409 | SHOWTIME_CONFLICT | Same-room occupancy overlaps |
| 409 | SHOWTIME_CANNOT_BE_MODIFIED | Ticket history or unavailable inventory |
| 422 | MOVIE_INACTIVE / AUDITORIUM_INACTIVE | Inactive master data on a write |
| 422 | MOVIE_DURATION_INVALID | Invalid master duration |
| 422 | AUDITORIUM_HAS_NO_SEATS | No physical seats |
| 422 | SWEETBOX_PAIR_INVALID | Malformed physical Sweetbox configuration |
| 422 | PRICING_RULE_MISSING / PRICING_RULE_INVALID | Common base unavailable or invalid |

Database integrity failures retain Foundation's generic 409 RESOURCE_CONFLICT; unexpected errors retain generic 500. A transaction cannot publish success if inventory flush/commit fails.

## Scope boundary

No DELETE, showtime status endpoint, room reassignment, seat-map endpoint, hold, scheduler, pricing quote, booking, checkout, payment, ticket issuance or refund. See [decisions and traceability](../decisions/003-showtime.md) for sources and [validation](../architecture/showtime-validation.md) for executed checks.
