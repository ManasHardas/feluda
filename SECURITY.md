# Security policy

## Reporting a vulnerability

Please report security problems privately. Do not open a public issue or discuss them in the
neighborhood chat until they are fixed.

- Preferred: GitHub's private vulnerability reporting (repository → Security → Report a
  vulnerability), once the repository is published and the feature is enabled.
- Or e-mail the security contact: **SECURITY CONTACT NOT YET SET** (the owner must fill this in
  before publishing; the same address is shown in the apps and on the registration page through the
  `SECURITY_CONTACT` setting).

Include: what is affected (app and version, or backend), how to reproduce it, and what an attacker
could gain. If the report involves real residents' data, do not include that data.

## What to expect

This is a volunteer-run pilot, so there is no paid bounty and no guaranteed response time. The aim
is to acknowledge a report within a few days, agree on a fix and a disclosure date with the
reporter, credit them if they wish, and record the outcome in `docs/SECURITY_REVIEW.md`.

## Scope

In scope: the Android and iOS apps, the backend, the database schema and policies, the registration
page, and the build and release pipeline in this repository.

Out of scope: the hosting, database and identity providers themselves (report those to the
provider), denial of service by volume, and findings that need a resident's unlocked phone or
mailbox.

## Please do not

- Test against a deployment with real residents without the operator's permission. Run your own
  copy instead (`README.md`, quick start).
- Access, change or delete data that is not yours.
- Generate alerts that reach real residents.

## Supported versions

Only the latest released build of each app and the current `main` of the backend.

## What the project does about security

Controls, the threat model and the latest review are in `docs/SECURITY.md`,
`docs/THREAT_MODEL.md` and `docs/SECURITY_REVIEW.md`. No independent audit has been performed yet,
and the project makes no claim that the software is free of vulnerabilities.
