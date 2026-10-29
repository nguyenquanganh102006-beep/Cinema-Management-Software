# Foundation implementation decisions

Date: 2026-10-29. Implements the accepted foundation plan; source comparisons remain in `docs/architecture/foundation-context.md`.

## Runtime and structure

Java 21, Spring Boot 3.5.16, Maven Wrapper 3.9.12, springdoc 2.8.17, JJWT 0.13.0, Flyway 11.20.3 (explicit override for PostgreSQL 18 support). Boot 3.5 was chosen to keep the conventional Spring Security 6/Jackson 2 stack used by this course architecture. It is a pinned choice, not a claim that this is the newest Spring Boot major. Check support/security updates before a public deployment.

References: [Boot 3.5 requirements](https://docs.spring.io/spring-boot/3.5/system-requirements.html), [springdoc compatibility](https://springdoc.org/v2/), [JJWT usage](https://github.com/jwtk/jjwt).

Backend keeps the five requested packages. Shared security/config services live in `service`, advice in `controller`, enums in `entity`. No business API stubs or feature-specific package hierarchy.

React/ReactDOM 19.3.1, TypeScript 5.9, Vite 7, npm workspaces with one committed lockfile. Use Node 22.12+ and npm 11; npm 10's optional-peer resolution failed during initial installation. Exact resolved dependencies are in `frontend/package-lock.json`.

## Authentication and identity

- Phone is required; schema already makes it unique and NOT NULL. Accept an optional + followed by 9–15 digits. Username uses 3–50 ASCII letters/digits/dot/underscore/hyphen, no @. Username/email are trimmed and lowercased, password is unchanged.
- Register rejects unknown JSON fields, including role/status. Backend assigns CUSTOMER/ACTIVE. Password minimum 8 characters and maximum 72 UTF-8 bytes for BCrypt, work factor 12.
- Login's `username` field accepts username or email. Same error for unknown identity/wrong password. Locked/inactive status is exposed only after matching the password.
- JWT HS256, issuer cinema-management, 30-minute default, sub/role/iat/exp/jti/sv. Secret must be at least 32 UTF-8 bytes and should be randomly generated. No refresh token.
- `sv` matches users.security_version. Future reset/change-password service must increment security_version in the same transaction as password_hash. Each request reads current role/status from DB so locking or changing a role takes effect immediately.
- Logout stores jti/expiry in PostgreSQL; revoke is durable and per token. Cleanup only removes entries after expiry. FR-04 takes precedence over G4's client-only stateless logout suggestion. No Redis needed.
- Frontend stores session in memory. Reload asks for login again. Login on another role's portal automatically dispatches to the configured role portal through a single-use transfer code (30 seconds). Source JWT is revoked, destination exchanges the code for its own JWT. Only a SHA-256 code hash is persisted; code is carried in the URL fragment and immediately removed, never JWT/password. Portal URLs are configured on the server using PORTAL_MANAGER_URL/PORTAL_STAFF_URL/PORTAL_CUSTOMER_URL; HTTPS is required except loopback development.
- Bearer Authorization header, no credential cookies, CSRF disabled for this transport. CORS explicit allowlist. A future cookie-based design must revisit CSRF.

## Contract

Success wraps `data`, including login. Logout returns 204 with no body. Error DTO has timestamp/status/code/message/details/path. Registration uniqueness returns 409 RESOURCE_CONFLICT; input errors 400 VALIDATION_ERROR. Account errors use 401, role errors 403. Swagger is enabled only in the local profile by default.

HTTP framework errors preserve their status/headers (including Allow on 405); unsupported content type is 415 rather than 500. CORS rejection uses the same JSON error schema. Auth OpenAPI operations declare JSON success/error schemas and status codes. Portal transfer adds two Auth endpoints documented separately; it does not add a refresh-token flow.

## Database

V1 imports source DDL only, replaces TIMESTAMP with TIMESTAMPTZ, omits DROP SCHEMA and all demo transactions. V2 fills documented gaps; V3 seeds corrected pricing. Database is owned by Flyway; Hibernate validates only. User is the only mapped business entity in Foundation.

V4 adds auth_transfers(code_hash, user_id, target_role, security_version, expires_at). Consumption locks the row, rechecks account/role/security version/expiry, and deletes it in the same transaction that creates the destination token. Expired rows are cleaned by a scheduled task. Existing V1–V3 checksums remain unchanged.

SQL names remain compatible: STANDARD is Normal, 3D will need an explicit Java mapping, GATEWAY_ONLINE represents the online gateway. No new roles table, and no payment enum renamed without a decision from the relevant sprint.

Age audit: bookings.age_confirmed_at records Customer declaration; age_verifications records staff ID/time/stage and Booking (POS) or Ticket (Gate). No identity document numbers/images. Inventory transactions store signed quantity and before/after balance; RESTOCK requires a manager reference. Enforcement of staff/manager roles and immutable history belongs to the consuming services, not only FK constraints.

TIMESTAMPTZ stores absolute instants; JVM Hibernate uses UTC; business error timestamps use Asia/Ho_Chi_Minh. Future reporting and pricing must explicitly apply this zone. Fresh database only: existing timestamp-without-zone data needs an explicit timezone conversion before adopting these migrations.

Local users are created only under profile local using environment credentials, and never have passwords reset silently on restart. No source booking/ticket/refund demo fixtures are imported. SWEETBOX pair symmetry/adjacency, schedule overlap, payment/hold expiration and report net revenue remain responsibilities of later modules.
