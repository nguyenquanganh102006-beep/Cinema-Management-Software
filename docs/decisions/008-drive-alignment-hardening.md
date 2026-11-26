# Review follow-up — Drive requirement alignment

Ngày: 25/11/2026. This records implementation decisions made while reconciling the
current `feature/frontend` branch with the canonical Functional Requirements, Business
Rules, Use Case and NFR documents in Drive. Those Drive documents remain the standards;
this file records code behavior and local evidence, not replacements for the source docs.

Drive sources: [Functional Requirements](https://docs.google.com/document/d/1Bc2AoPzDmaTv1B-dGWc7_DJJFcSwgdmtqIOIYTADqQY/edit), [Business Rules](https://docs.google.com/document/d/1TwBIYWgnaNe86iIkVuWqcS82k8SPF2l11h7jGimOh58/edit), [Use Cases and NFR](https://docs.google.com/document/d/1sokWK204hw6Ct4XryGXc7DcnpUM-y6EpgebJWwjmbfQ/edit), and [Analysis/NFR](https://docs.google.com/document/d/1A-rNdfN8ofhO_7OQVHeli0SIMlwqn7Fzs0liAWRqL4I/edit).

## Showtime availability and history

- Movie and auditorium status remains soft-deactivation so existing foreign keys and
  history survive. Staff/Customer showtime list/detail reads hide a schedule if either
  master record is inactive. Manager may still inspect it.
- Price quote, seat map, seat hold and new Booking creation reject a schedule linked to
  an inactive movie or auditorium with `409 SHOWTIME_UNAVAILABLE`.
- Deactivation does not cancel an already-created Booking. Its history stays readable and
  its existing payment flow may finish against the saved Booking/showtime data; the Drive
  requirements do not define automatic cancellation or refund for this edge case.
- Migration V9 backfills each showtime's movie title/rating/duration and auditorium
  name/type. Ticket gate age checks, customer/POS history, pricing and showtime responses
  use those snapshots, so later catalog edits do not rewrite issued-sale history.
- A room's physical seat layout remains locked after it has any showtime. Sweetbox pairs
  require adjacent seats on the same row, and a pair consumes/issues two physical seats.

## Auditability

- Migration V10 adds append-only `audit_events`; `GET /api/v1/audit-events` is Manager-only.
- Authentication success/failure, movie/auditorium changes, showtime changes, pricing
  changes, restocks, POS payment confirmation, VietQR confirmation, ticket gate validation,
  and VNPay IPN outcomes are persisted. Failed login records use a SHA-256 hash of the
  normalized login identifier and never store a password.
- Refunds and Manager override approvals/rejections also write audit events. Their dedicated
  domain records and inventory movement rows remain the source for detailed state.

## VietQR

- `qrPayload` is now EMVCo Merchant-Presented Mode / NAPAS VietQR account-transfer TLV,
  with VND, amount, transfer content and CRC-16/CCITT-FALSE.
- Source FR-43/BR-19 calls this "VietQR tĩnh" and requires a Booking-specific amount
  and transfer reference. The builder uses EMVCo tag `01=11`, includes those fields, and
  relies on Staff to verify the incoming transfer before confirming payment. It does not
  claim a bank webhook or automatic balance reconciliation. See the
  [EMVCo MPM specification](https://www.emvco.com/documents-search/?post_id=12643&type=specifications)
  and [NAPAS VietQR service](https://napas.com.vn/dich-vu-thanh-toan-bang-ma-qr).
- Operators must configure `CINEMA_BANK_BIN` (six NAPAS digits), `CINEMA_BANK_ACCOUNT`
  and `CINEMA_BANK_NAME`. The BIN and account must refer to the same destination bank.
- Staff still confirms receipt manually after checking the bank account. No bank callback
  or account reconciliation integration is claimed. The payload has not been scanned by
  a live bank application as part of this code review.

## Frontend acceptance

The three portals contain real checkout, POS, ticket and management pages. Pair/unpair,
combo editing and pricing quote preview are present in source. Their remaining screen and
error-boundary matrices are still unverified; the dated G5 checkpoint is not upgraded to
an acceptance sign-off by this code change. See [G5 evidence](../testing/g5-validation-2026-11-23.md).
