# Security review summary

**This is an internal review by the build agent that wrote the code. It is not an independent
review, not an audit and not a certification.** The brief requires an independent review of the
source, the backend and the actual release artifacts before moving from beta to broad use; that
has not been arranged (EXTERNAL_REVIEW_REQUIRED, owner's decision who does it).

| | |
| --- | --- |
| Date | 2026-11-21 |
| Reviewer | Build agent (Claude), self-review |
| Scope | Backend source, database schema and policies, onboarding web page, API contract, CI configuration. Mobile apps: reviewed through their builders' reports, not line by line |
| Not in scope | Release artifacts (none exist), a deployed environment (none exists), the identity provider's configuration, penetration testing, physical devices |
| Method | Threat model (`docs/THREAT_MODEL.md`), authorization and row-level security tests against a real PostgreSQL, abuse and malformed-input tests, static lint rules for common Python security mistakes |

## Findings and status

| # | Finding | Severity | Status |
| --- | --- | --- | --- |
| 1 | Three colluding households in one neighborhood can cause a false level-2 alert | Medium | Accepted for the pilot; audited and rate-limited; documented |
| 2 | Someone holding a leaked general invitation who knows an unregistered approved address can register as that household | Medium | Mitigated: household-bound invitations recommended as the default practice; lock after 5 wrong addresses; organizer can remove a household. Not eliminated |
| 3 | Administrator multi-factor sign-in is enforced by the backend when enabled, but there is no enrolment flow in the apps or the admin tool | Medium | Open. Production should not set `ADMIN_REQUIRE_MFA=true` until enrolment exists, which leaves administrators on single-factor e-mail codes |
| 4 | The in-process rate limiter for unauthenticated endpoints is per process and resets on restart | Low | Accepted while one instance runs; the detection-critical limits are in the database |
| 5 | No certificate pinning in the apps | Low | Accepted: pinning a hosted provider's certificate risks locking residents out on rotation; standard platform trust is used |
| 6 | The operator and the database provider can read all stored data; application-level access by the operator is not logged | Medium (privacy) | Accepted; mitigated by data minimization and short retention; stated in `docs/PRIVACY.md` |
| 7 | CI actions and base images are pinned by tag, not by hash or digest | Low | Open |
| 8 | Dependency vulnerability audit has not been run (no network at build time, repository not on GitHub) | Unknown | Open: run on first push |
| 9 | The mobile apps have never run against the backend or on a physical phone. iOS compiles and its 97 unit tests pass. Android's main code compiles, but its unit tests, packaging, lint and release (R8) build have never run | Unknown | Open: see `docs/STATUS.md` |
| 10 | iOS debug builds allow cleartext to localhost only; Android debug builds allow cleartext to the emulator host only | Informational | By design; release builds are HTTPS-only |
| 11 | Push notification text is visible on lock screens | Informational | Text contains no address or household identity |

## What the automated tests demonstrate

42 security tests in `backend/tests/test_security.py` cover: token verification failures; every
non-public route requiring a token; refusal of client-supplied household and neighborhood ids;
cross-household and cross-neighborhood access through the API; direct database access as each
restricted role (reads and writes that must fail); row-level security enabled on every table and
nothing granted to PUBLIC; replay and duplicate submission; another household's event id;
timestamp bounds; twelve kinds of malformed event; oversized and garbage bodies; request flooding;
invitation guessing; household event budgets; absence of tokens, addresses and e-mail addresses in
logs; absence of committed secrets; refusal of unsafe production settings; API docs and the sign-in
emulator being off in production.

Their last recorded results are in `docs/STATUS.md`.

## Recommended before wider use

1. Commission an independent review covering the items listed as not in scope, especially the
   release artifacts and a deployed staging environment.
2. Build the multi-factor enrolment flow for administrators (finding 3).
3. Run the dependency audit and act on it (finding 8).
4. Complete the physical-device test plans (finding 9).
5. Publish this summary, updated with the independent reviewer's scope, date, findings and
   remediation status, and link it from the apps' Transparency screens.
