# Neighborhood Watchdog

A private, invitation-only network that notices unusual Wi-Fi disruptions across a neighborhood.
Residents' phones report when home Wi-Fi, internet or cellular connectivity is lost or restored.
When several nearby households report the same thing at about the same time, everyone taking part
is alerted so they can check their cameras and alarms.

Nobody signs in. A home joins with a private invitation link from the neighborhood organizer; the
resident types the street and, only if they wish, a house number. There are no accounts, e-mail
addresses or passwords.

**This is a wireless anomaly detector, not a jammer detector.** A phone app cannot measure radio
interference or prove that anyone caused a disruption. Every result is an inference from
connectivity reports and is labelled that way. It does not replace an alarm system, and a quiet
app does not mean a household is safe.

## Status

Pilot software, not yet deployed or tried on physical phones. Sign-in was removed on 2026-11-23:
the backend and both apps are changed and their automated tests pass. See [`docs/STATUS.md`](docs/STATUS.md)
for exactly what is implemented, what is covered by automated tests, and what still needs accounts,
devices or an independent review.

## What is in this repository

| Path | What | Build |
| --- | --- | --- |
| [`apps/android`](apps/android) | Android app (Kotlin, Jetpack Compose) | `make android-build` |
| [`apps/ios`](apps/ios) | iOS app (Swift, SwiftUI) | `make ios-build` |
| [`apps/admin`](apps/admin) | Command-line tool for neighborhood organizers | Python standard library |
| [`backend`](backend) | API, correlation engine and notifications (Python, FastAPI, PostgreSQL) | `make backend-test` |
| [`shared`](shared) | API contract (OpenAPI) and the behaviour both apps follow | `make openapi` |
| [`infrastructure`](infrastructure) | Hosting configuration and scripts | |
| [`docs`](docs) | Architecture, security, privacy, operations | |

## Quick start (backend, local)

Needs Docker and [uv](https://docs.astral.sh/uv/).

```bash
make setup         # install backend dependencies
make docker-up     # PostgreSQL on localhost:54329
make migrate       # create the schema
make seed          # a simulated neighborhood with 24 households
make run           # API on http://localhost:8000  (interactive docs at /docs)
```

In another terminal, replay a scenario and see what the detection engine concludes:

```bash
make simulate                 # list scenarios A to J
make simulate SCENARIO=C      # three AT&T and three Xfinity households lose Wi-Fi together
```

Run the tests: `make backend-test` (or `make backend-test-docker` with no local Python).
All commands: `make help`.

## How it decides

Reports become household episodes, episodes close in time (60 seconds) and, where the organizer
has recorded household coordinates, in space (500 metres) become clusters, and each cluster is classified by transparent rules: Wi-Fi up but no internet
points to a provider outage; phones losing charging power points to a power cut; Wi-Fi lost across
several homes on different providers while cellular stays up is a correlated wireless disruption,
and becomes high priority only if it persists. Every incident carries a score breakdown, the
evidence for it and the evidence that is missing. Details: [`docs/DETECTION_PROTOCOL.md`](docs/DETECTION_PROTOCOL.md).

## Privacy in one paragraph

Collected: the street, a house number only if the resident chooses to give one, and connectivity
events. Never collected: names, e-mail addresses, phone numbers, browsing, Wi-Fi names or
passwords, location, camera or microphone data, or logins for other services. Other residents see only anonymous totals. Raw
events are kept 30 days. Details: [`docs/PRIVACY.md`](docs/PRIVACY.md).

## Documentation

| | |
| --- | --- |
| [Architecture](docs/ARCHITECTURE.md) | Components, data flow, platform differences |
| [Detection protocol](docs/DETECTION_PROTOCOL.md) | Rules, score, thresholds, incident lifecycle |
| [API](docs/API.md) | Endpoints and conventions; contract in `shared/api-contracts/openapi.json` |
| [Security](docs/SECURITY.md), [Threat model](docs/THREAT_MODEL.md), [MASVS checklist](docs/MASVS_CHECKLIST.md), [Security review](docs/SECURITY_REVIEW.md) | Controls, risks accepted, self-review |
| [Privacy](docs/PRIVACY.md) | Data, access, retention, deletion |
| [Deployment](docs/DEPLOYMENT.md) | Environments, secrets, migrations, rollback, cost estimate |
| [Operations](docs/OPERATIONS.md) | Runbook |
| [Onboarding](docs/ONBOARDING.md) | For neighborhood organizers |
| [App distribution](docs/APP_DISTRIBUTION.md) | Firebase App Distribution and TestFlight |
| [Testing](docs/TESTING.md) | Automated suites, scenarios, manual device tests |
| [Release verification](docs/RELEASE_VERIFICATION.md) | SBOM, checksums, reproducibility limits |
| [Hand-off](docs/HANDOFF.md) | Summary for whoever takes this over |
| [Decisions](ai/docs/decisions) | Why things are the way they are |

## Reporting a security problem

See [`SECURITY.md`](SECURITY.md). Please do not open a public issue for a vulnerability.

## Contributing

See [`CONTRIBUTING.md`](CONTRIBUTING.md).

## License

MIT. See `LICENSE`.
