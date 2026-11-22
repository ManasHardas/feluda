# Neighborhood Watchdog (project folder: feluda)

Private, invitation-only neighborhood wireless connectivity anomaly network: Android app, iOS app, FastAPI backend, PostgreSQL. The build brief is `neighborhood_watchdog_master_build_prompt_v2.md`.

Live to-do list (read it at the start of every session):

@jtbd/todo.md

Rules that must hold in every change:
- This is an anomaly detector, not a jammer detector. Never write "confirmed jamming" in code, UI text, notifications or docs.
- Never mark something deployed, device-tested or approved unless it happened. Status words: IMPLEMENTED, AUTOMATED_TESTED, MANUAL_TEST_REQUIRED, EXTERNAL_CREDENTIAL_REQUIRED, EXTERNAL_REVIEW_REQUIRED, NOT_IMPLEMENTED (see `docs/STATUS.md`).
- No secrets, resident addresses or tokens in the repository. The repository is meant to be public.
- Decisions go in `ai/docs/decisions/`. Open and finished work goes in `jtbd/`.
- Backend: `make backend-test` must pass before a commit. The API contract in `shared/api-contracts/openapi.json` is generated from the backend (`make openapi`) and both apps follow it.
