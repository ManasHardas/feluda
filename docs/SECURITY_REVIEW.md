# Security review summary

**This is an internal review by the build agent that wrote the code. It is not an independent
review, not an audit and not a certification.** The brief requires an independent review of the
source, the backend and the actual release artifacts before moving from beta to broad use; that
has not been arranged (EXTERNAL_REVIEW_REQUIRED, owner's decision who does it).

| | |
| --- | --- |
| Date | 2026-11-21. Findings 2, 3, 9 and 12 to 15 were rewritten or added on 2026-11-23 after sign-in was removed (decision 0014), from the changed backend code and its tests. That revision is also a self-review |
| Reviewer | Build agent (Claude), self-review |
| Scope | Backend source, database schema and policies, onboarding web page, API contract, CI configuration. Mobile apps: reviewed through their builders' reports, not line by line |
| Not in scope | Release artifacts (none exist), a deployed environment (none exists), the apps' new enrolment code (being written), penetration testing, physical devices |
| Method | Threat model (`docs/THREAT_MODEL.md`), authorization and row-level security tests against a real PostgreSQL, abuse and malformed-input tests, static lint rules for common Python security mistakes |

## Findings and status

| # | Finding | Severity | Status |
| --- | --- | --- | --- |
| 1 | Three colluding households in one neighborhood can cause a false level-2 alert | Medium | Accepted for the pilot; audited and rate-limited; documented |
| 2 | The invitation link is the only entry gate. Whoever holds a live link can enrol phones, name any street, and claim an address that nobody has enrolled yet. There is no approved-address list and no lock any more | Medium | Accepted by the owner (decision 0014). Mitigated: links are per home, limited in uses, expiring and revocable; a home that already takes part cannot be joined with a foreign link; the organizer sees and can remove every household. Not eliminated |
| 3 | Administration is protected by one shared operator secret for the whole deployment: no per-person identity, no second factor, no per-person audit trail, and it reaches every neighborhood | Medium | Accepted by the owner for one organizer (decision 0014); revisit before there are several. This replaces the earlier finding about missing multi-factor enrolment, which no longer applies because administrators do not sign in |
| 4 | The in-process rate limiter for unauthenticated endpoints is per process and resets on restart | Low | Accepted while one instance runs; the detection-critical limits are in the database |
| 5 | No certificate pinning in the apps | Low | Accepted: pinning a hosted provider's certificate risks locking residents out on rotation; standard platform trust is used |
| 6 | The operator and the database provider can read all stored data; application-level access by the operator is not logged | Medium (privacy) | Accepted; mitigated by data minimization and short retention; stated in `docs/PRIVACY.md` |
| 7 | CI actions and base images are pinned by tag, not by hash or digest | Low | Open |
| 8 | The first dependency audit (2026-11-22) found known advisories in the pinned PyJWT and Starlette versions | High until fixed | Fixed: dependencies upgraded, audit clean, all tests pass. The pins had been chosen from memory without an audit; the weekly CI audit is what keeps this from recurring |
| 9 | The mobile apps have never run on a physical phone, and they are being rewritten for enrolment by link. Their earlier builds and unit tests (iOS 98, Android 91) predate that change and say nothing about the new flow | Unknown | Open: see `docs/STATUS.md` |
| 10 | iOS debug builds allow cleartext to localhost only; Android debug builds allow cleartext to the emulator host only | Informational | By design; release builds are HTTPS-only |
| 11 | Push notification text is visible on lock screens | Informational | Text contains no address or household identity |
| 12 | A phone is recognised by a device secret alone. It does not expire, is not rotated, and has no second factor; a copied secret works until the phone is removed and nothing detects the copy | Medium | Accepted by the owner (decision 0014). The secret is stored hashed on the server and is meant to live only in the platform keystore; household or organizer can remove a phone |
| 13 | One invitation link can create up to `max_uses` separate households if a different house number is given for each enrolment. A default link (4 phones) is enough to reach the 3-household minimum for a level-2 alert, by calling the API without real phones | Medium | **Fixed 2026-11-23.** The first enrolment binds the link to its household; later phones with that link join it whatever they type, so one link creates at most one household. Test `test_one_link_is_one_household_whatever_is_typed`. One person can still enrol as several homes with several links, which is the organizer's gate |
| 14 | The invitation page passes the token to the app through the custom link scheme `feluda://`. On Android another installed app can register the same scheme and receive the token | Low | Open; not verified on devices. Pasting the link into the app avoids it. Verified app links would remove it |
| 15 | The development value of `ADMIN_API_TOKEN` is published in the repository (`Makefile`, `.env.example`, `docker-compose.yml`) and is long enough to pass the production length check | Low | **Fixed 2026-11-23.** The settings loader refuses the development placeholder in staging and production. Test `test_production_settings_refuse_unsafe_configuration` |

## What the automated tests demonstrate

41 security tests in `backend/tests/test_security.py` cover: every non-public route requiring a
credential; device secrets and the operator secret not being interchangeable; the administrator API
being off without an operator secret; refusal of client-supplied household and neighborhood ids;
cross-household and cross-neighborhood access through the API; direct database access as each
restricted role (reads and writes that must fail); row-level security enabled on every table and
nothing granted to PUBLIC; replay and duplicate submission; another household's event id;
timestamp bounds; twelve kinds of malformed event; oversized and garbage bodies; request flooding;
invitation guessing; household event budgets; absence of invitation tokens, device secrets, the
operator secret and addresses in logs; absence of committed secrets; refusal of unsafe production
settings; API docs being off in production. `backend/tests/test_onboarding.py` adds the enrolment
rules: nothing personal accepted or stored, the use limit, a foreign link refused for a home that
already takes part, and leaving deleting what the phone reported.

Their last recorded results are in `docs/STATUS.md`.

## Recommended before wider use

1. Commission an independent review covering the items listed as not in scope, especially the
   release artifacts and a deployed staging environment.
2. Decide on per-person administrator credentials (finding 3) before a second person administers.
3. Run the dependency audit and act on it (finding 8).
4. Complete the physical-device test plans (finding 9).
5. Publish this summary, updated with the independent reviewer's scope, date, findings and
   remediation status, and link it from the apps' Transparency screens.
