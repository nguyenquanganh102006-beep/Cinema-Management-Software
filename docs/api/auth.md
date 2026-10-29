# Authentication API

Base: `http://localhost:8080/api/v1`. JSON request keys are strict; unknown fields return 400. Success is `{ "data": ... }`. Protected calls use `Authorization: Bearer <accessToken>`.

| Method | Path | Permission | Success |
|---|---|---|---|
| POST | /auth/register | Public | 201 UserResponse |
| POST | /auth/login | Public | 200 LoginResponse |
| GET | /auth/me | Any authenticated role | 200 UserResponse |
| POST | /auth/logout | Any authenticated role | 204, empty body |
| POST | /auth/transfers | Any authenticated role | 200 redirectUrl for configured role portal |
| POST | /auth/transfers/consume | Public, valid one-time code | 200 LoginResponse |

Register:

```json
{"username":"customer02","fullName":"Nguyễn Văn A","email":"customer02@example.com","phone":"0901234567","password":"your-local-password"}
```

Username: 3–50 ASCII letters, digits, dot, underscore or hyphen; normalized lowercase. Email: valid, max 150, normalized lowercase. Name: nonblank, max 100. Phone: optional + then 9–15 digits, unique. Password: at least 8 characters, max 72 UTF-8 bytes; not trimmed. Registration always creates CUSTOMER/ACTIVE; response never contains password/PIN hashes.

Login uses the field username for either username or email:

```json
{"username":"customer02@example.com","password":"your-local-password"}
```

Login result:

```json
{"data":{"accessToken":"<JWT>","expiresIn":1800,"user":{"id":1,"username":"customer02","fullName":"Nguyễn Văn A","email":"customer02@example.com","phone":"0901234567","role":"CUSTOMER"}}}
```

Me returns the same user shape. Logout takes no body, revokes only the supplied token until its expiry. Reuse of a revoked token returns 401; another token for the same user remains valid. An expired/revoked token cannot call logout again (401); the frontend can then clear local auth.

Error shape:

```json
{"timestamp":"2026-10-29T15:00:00+07:00","status":400,"code":"VALIDATION_ERROR","message":"Dữ liệu không hợp lệ","details":{"username":"must not be blank"},"path":"/api/v1/auth/register"}
```

| Status | Codes/meaning |
|---|---|
| 400 | VALIDATION_ERROR, invalid/missing/unknown fields or malformed JSON |
| 401 | AUTH_INVALID_CREDENTIALS, ACCOUNT_LOCKED, ACCOUNT_INACTIVE, AUTH_TOKEN_EXPIRED, AUTH_TOKEN_INVALID, AUTH_TRANSFER_INVALID |
| 403 | ACCESS_DENIED for role restrictions; CORS_ORIGIN_DENIED for rejected preflight/origin |
| 405 | METHOD_NOT_ALLOWED; preserves Allow header |
| 406 | NOT_ACCEPTABLE |
| 415 | UNSUPPORTED_MEDIA_TYPE |
| 409 | RESOURCE_CONFLICT for username/email/phone uniqueness |
| 500 | INTERNAL_ERROR, no stack trace in response |
| 503 | SERVICE_UNAVAILABLE when the authentication filter cannot read the database |

Frontend keeps token in memory. Refreshing the page requires login. Swagger at `/swagger-ui/index.html` and `/v3/api-docs` under profile local. Role-restricted test endpoints exist only in the test application; no fake Manager APIs are shipped.

Role dispatch: after login on a different portal, POST /auth/transfers with the source Bearer token and no body. Server selects destination from the current user's role and configured portal URLs, generates a random 256-bit code, stores only its SHA-256 hash with 30-second expiry, and revokes the source token in one transaction. Response is `{data:{redirectUrl:"http://localhost:3001#auth-transfer=<one-time-code>"}}`. JWT is never in this URL. Frontend replaces its location; the target immediately clears the fragment and calls POST /auth/transfers/consume with `{code:"<64-lowercase-hex>",role:"MANAGER"}`. Backend requires matching stored role, ACTIVE account, unchanged security_version and unexpired code, locks/deletes the code on success, and returns the normal LoginResponse. Reuse, expiry, changed account or mismatched role returns 401 AUTH_TRANSFER_INVALID. Missing/invalid fields return 400. Two concurrent consume requests have one winner.
