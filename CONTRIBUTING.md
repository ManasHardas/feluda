# Contributing

## Ground rules

1. **Never claim confirmation.** Code, UI text, notifications and documents say "connectivity
   interruption", "wireless connectivity anomaly", "correlated wireless disruption", "suspected RF
   interference" or "elevated neighborhood alert". The word "jamming" appears only in the sentence
   that says it has not been confirmed. Tests enforce this.
2. **Never invent a measurement.** If a platform cannot observe something, the value is null.
3. **No new data without a reason.** Adding a field, a permission or a third-party SDK needs an
   update to `docs/PRIVACY.md` and a decision record in `ai/docs/decisions/`.
4. **No secrets, addresses, tokens or real resident data in the repository,** including in tests,
   issues and screenshots.
5. **Say what was verified.** Use the status words in `docs/STATUS.md`. Do not write that something
   was deployed or tested on a device unless it was.

## Building and verifying

| Part | Needs | Build | Test |
| --- | --- | --- | --- |
| Backend | Docker, [uv](https://docs.astral.sh/uv/) 0.5+, Python 3.12 (uv installs it) | `make setup` | `make backend-lint backend-test openapi-check` |
| Android | JDK 17, Android SDK platform 35 | `make android-build` | `make android-test` |
| iOS | Xcode 16 or later, [XcodeGen](https://github.com/yonaskolb/XcodeGen) if you change `project.yml` | `make ios-build` | `make ios-test` |

`make test` runs everything. CI runs the same commands (`.github/workflows`).

Versions are pinned: Python dependencies in `backend/uv.lock`, Android in
`apps/android/gradle/libs.versions.toml` and the Gradle wrapper, tools in the workflows. Update them
in their own pull request.

## Workflow

- Branch from `main`: `feat/...`, `fix/...`, `docs/...`. Open a pull request; do not push to `main`.
- Commit messages: `type(scope): what changed`, with the reason in the body when it is not obvious.
- A change to the API: edit the schemas in `backend/watchdog/schemas.py`, run `make openapi`,
  commit the regenerated contract, and update both apps and
  `shared/documentation/CLIENT_PROTOCOL.md` in the same pull request or a linked one.
- A change to the database: add a new file in `backend/migrations/`. Never edit an applied one (CI
  rejects that). Keep it compatible with the previous backend release. New tables need row-level
  security and a test in `backend/tests/test_security.py`.
- A change to detection: add or update a unit test and a scenario; update
  `docs/DETECTION_PROTOCOL.md`.
- Security-sensitive paths (`.github/CODEOWNERS`) need review from an owner.

## Repository settings to apply when it is published

These cannot be set from the code and have not been applied yet:

- Protect `main`: require a pull request with one approving review, require review from code
  owners, require the status checks "Lint and unit tests", "Integration, security and scenario
  tests", "API schema validation", "Database migration validation", "Secret scanning"; no force
  pushes; no deletion.
- Enable secret scanning with push protection, Dependabot alerts and private vulnerability
  reporting.
- Create the environments `staging`, `production` (required reviewer) and `beta` for the deploy and
  distribution workflows.
- Replace `@OWNER` in `.github/CODEOWNERS`.

## Reporting bugs

Open an issue with what you did, what you expected and what happened. For anything that could be a
security problem, use the private process in `SECURITY.md` instead.
