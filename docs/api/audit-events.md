# Persistent audit events

`GET /api/v1/audit-events` is available to MANAGER only. Results are newest first,
ordered by `occurredAt DESC, id DESC`; `page` starts at 0 and `size` defaults to 50
with a maximum of 100.

```http
GET /api/v1/audit-events?page=0&size=50
Authorization: Bearer <manager-jwt>
```

Each item contains:

| Field | Meaning |
|---|---|
| `id` | Persistent event ID |
| `occurredAt` | Server timestamp in UTC |
| `actorUserId` | User that performed the action; null for unauthenticated gateway events |
| `action`, `outcome` | Event kind and result |
| `subjectType`, `subjectId` | Affected user/resource/payment when known |
| `details` | Concise business context; never passwords, JWTs, PINs or payment secrets |

Events cover authentication attempts, movie/auditorium/showtime/pricing changes,
restocks, POS payment confirmation, VietQR confirmation, ticket gate validation, VNPay
IPN outcomes, Manager override approval/rejection and completed refunds. Dedicated
domain records and inventory movements also remain available. Audit events are append-only through the API; there is no update or
delete route.

`401` is returned without a valid session, `403 ACCESS_DENIED` for non-Managers, and
`400 VALIDATION_ERROR` for invalid paging parameters.
