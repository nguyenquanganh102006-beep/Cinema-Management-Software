# Sprint C Showtime validation and contribution report

Date: 2026-11-03, Asia/Saigon. Working branch `feature/showtime`; base `ae58471` from origin/develop (Foundation and Master Data both merged). The working tree was initially clean. No commit/push/PR was performed during preparation; publication to feature/showtime was subsequently requested explicitly by the user. No destructive Git command was performed.

## Validation

Final clean verification completed successfully on 2026-11-03 at 20:44 Asia/Saigon. PostgreSQL/API results refer to actual PostgreSQL, not the old fake store.

| Check | Result | Evidence |
|---|---|---|
| Baseline compile/package | PASS | Official Maven Wrapper, existing code before C, Java 24.0.2 with release 21 |
| Baseline test environment | Corrected | Initial C-locale test database failed an existing Vietnamese case-folding F&B test; a separate ICU/Unicode database fixed the environment, without changing B |
| Final Maven clean verify | PASS | BUILD SUCCESS; 114 tests, 0 failures, 0 errors, 0 skipped; 55.032 seconds |
| C time unit tests | PASS | 4 cases in ShowtimeTimeServiceTest |
| C PostgreSQL/API/business tests | PASS | 28 cases in ShowtimeIntegrationTest, including parameterized cases and real concurrent transactions |
| A/B regression tests | PASS | 82 cases in the existing eight integration test classes, unchanged |
| Hibernate mappings/Flyway | PASS | Application startup validates new entities against PostgreSQL; repeatable migration test passes, V1–V5 untouched |
| Git whitespace/new-file review | PASS | git diff --check and direct checks of all 17 untracked files; only README is modified among pre-existing tracked files |

Environment: isolated PostgreSQL 18.3 on loopback port 55439, database `cinema_showtime_icu_test` with ICU und collation. Temporary cluster is outside the repository and separate from existing development databases; no live cinema database was connected or restored. Maven downloads/build outputs and test logs remain ignored under backend/target. JDK installed locally is 24.0.2; Maven compiles with the repository's Java 21 release target. Running these tests under an actual JDK 21 and Docker/Testcontainers transport was not performed locally; the unchanged CI already selects Java 21/PostgreSQL.

The tests cover create/detail, same-room interval containment/intersection/buffer/midnight/boundaries, common base and price snapshot, valid/malformed Sweetbox layout, JPA enum/Instant/version/default roundtrip, unique inventory/FK protection, real rollback after a later inventory insert fails, list filters/order/date/page/count without N+1, strict JSON/null/unknown fields, errors/OpenAPI, authentication and roles, PATCH history/unavailable-seat guards, preservation of mappings and metadata history, concurrent CREATE/CREATE, CREATE/PATCH and PATCH/PATCH. Failure injection is a temporary test-only PostgreSQL trigger scoped to a unique fixture seat and removed in finally; it is not a migration or production component.

NFR latency/load targets and frontend/browser flows were not benchmarked. No UI behavior changed; frontend build/E2E was not run for this backend-only contribution. The real gateway, hold/checkout/refund modules are outside this sprint and are not claimed tested.

## File structure and ownership

Actual C contribution; paths omitted from this tree are unchanged. NEW denotes files introduced by Sprint C. Staging, commit and push are performed only following the user's subsequent explicit publication request.

```text
repository/
├── README.md                                           MODIFIED
├── backend/src/main/java/com/cinema/cms/
│   ├── controller/ShowtimeController.java               NEW
│   ├── dto/CreateShowtimeRequest.java                   NEW
│   ├── dto/UpdateShowtimeRequest.java                   NEW
│   ├── dto/ShowtimeResponse.java                        NEW
│   ├── dto/ShowtimeRequestValues.java                   NEW
│   ├── entity/Showtime.java                             NEW
│   ├── entity/ShowtimeSeat.java                         NEW
│   ├── entity/SeatStatus.java                           NEW
│   ├── repository/ShowtimeRepository.java               NEW
│   ├── repository/ShowtimeSeatRepository.java           NEW
│   ├── service/ShowtimeService.java                     NEW
│   └── service/ShowtimeTimeService.java                 NEW
├── backend/src/test/java/com/cinema/cms/
│   ├── ShowtimeIntegrationTest.java                     NEW
│   └── service/ShowtimeTimeServiceTest.java              NEW
└── docs/
    ├── api/showtimes.md                                 NEW
    ├── decisions/003-showtime.md                        NEW
    └── architecture/showtime-validation.md              NEW
```

UNCHANGED reuse: Movie/Auditorium/Seat/PricingRule entities and their repositories, ApiResponse, PageResponse, QueryParams, ApiException, GlobalExceptionHandler, JWT/security/filter/RBAC, shared Clock, Maven Wrapper/POM, application configuration, CI workflow, V1–V5, all A/B tests and frontend. README only corrects the implemented-backend summary and adds C document links. No existing application code is modified. REMOVED: none in the official repository.

| Component | A/B convention observed in history | C location/compatibility | Result |
|---|---|---|---|
| Source | backend/src/main/java/com/cinema/cms | Same root and five packages; no feature subproject | PASS |
| DTO | dto + record requests/responses, Bean Validation, ApiResponse/PageResponse | Same wrappers, names and validation; no Entity exposure | PASS |
| Entity | entity, scalar FK ids, IDENTITY and PostgreSQL enums | Reuses master ids; Instant, enum and @Version validated on PostgreSQL | PASS |
| Repository | repository + Spring Data, parameterized queries and row locks | Shared auditorium lock; C-only repositories | PASS |
| Service | service + constructor DI + transactional business methods | Same pattern, READ_COMMITTED writes | PASS |
| Controller | controller + /api/v1 + JWT method authorization + OpenAPI | Four scoped endpoints, shared handler | PASS |
| Tests | backend/src/test/java/com/cinema/cms + JUnit/MockMvc/PostgreSQL | Same integration pattern; time-only tests in service package | PASS |
| Documentation | docs/api, docs/decisions, docs/architecture | Three focused documents and README links | PASS |
| Migration | Existing Flyway versioned directory | Existing tables suffice; no migration or checksum change | N/A |
| Configuration/scripts | Shared .env.example/application.yml/scripts | No new config, dependency, script or secret required | N/A |

## Review and commit readiness

The intended change set is 17 NEW files and one MODIFIED README (18 files). No source was copied from outside clone, no nested repository was created and no original external document was duplicated. Build/log/cache/temp files are excluded by the existing .gitignore. No database dump, environment credential, access token or runtime data is part of the contribution.

Suggested title, following A/B history: `feat(showtime): implement scheduling and seat inventory initialization`. Review against `develop`. The four endpoints and sources are in [API](../api/showtimes.md); the full requirement matrix, old-code classification, database/lock contract and unresolved cross-sprint decisions are in [decisions](../decisions/003-showtime.md).

Final suite counts: Foundation 19, Auditorium/Seat 11, Concession 10, Inventory 9, Movie 7, Pricing 9, Staff 9, Sweetbox 8, Showtime integration 28 and Showtime time 4. The final command was `mvnw.cmd -B -ntp clean verify` with DB_TEST_URL/USERNAME/PASSWORD pointing only to the isolated test database. No stale class files were included in the packaged jar because the final build began with clean.

Conclusion: **DONE** for the four verified Sprint C operations and contribution preparation. No required C build/test remains failing. Team-owned future workflow decisions are listed explicitly in 003-showtime; full-system D/E/F integration is not claimed. The contribution contains 18 changed/new files. Subsequent user-authorized publication targets only feature/showtime; Git history and remote tracking provide the commit/push status. The temporary PostgreSQL cluster is stopped after verification; reruns need an available dedicated test database or Docker as described in README.

## Subsequent workspace cleanup

At the user's request, moved these superseded/generated files to the Windows Recycle Bin: workspace-level backend (14 Java files), old build (21 class files), old docs/SPRINT_C.md and SHOWTIME_PERSISTENCE_CONTRACT.md, scripts/test-showtime.ps1, the now-unused workspace .gitignore (only ignored /build/), empty .qodo directories and the official backend/target generated outputs (156 files). Total: 195 files, approximately 61.3 MiB removed from the active workspace, recoverable from the Recycle Bin. Every target was resolved and checked inside the supplied workspace. Automatic review rejected the original bulk permanent-deletion command; no files were deleted by that rejected command.

No official source, tests, migration, configuration or business document was removed. The 114-test successful run above precedes build-output cleanup; reports and jars can be regenerated with Maven. Source did not change during cleanup, so tests were not rerun. These two C reports were updated to reflect removal of the former external reference code. Original Word/Markdown requirements, PNG diagrams and backup_cms.backup remain intact.
