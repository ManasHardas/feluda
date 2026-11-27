# 0015 Releases come only from the release branch; the server decides which app versions still work

**Decision (owner, 2026-11-24).** Once residents install the app they rarely update it, so nothing
reaches their phones without the owner reviewing it first, and the first release must already be
able to tell old versions to update.

- **Branches.** `main` holds reviewed work. `release` holds exactly what residents run. Work arrives
  on `main` only through pull requests that pass every test suite. `release` changes only by a pull
  request from `main` (the "Release guard" check refuses anything else), merged when the owner says
  "merge". GitHub rulesets enforce both; that needs the repository to be public, which the owner chose.
- **Previews.** Every pull request that touches an app is built and sent to the owner's phones only
  (TestFlight internal group "Owner", Firebase group "owner"), talking to the staging server. The
  owner tries it before saying "merge". Staging runs `main`'s backend and is redeployed on every
  backend change merged into `main`.
- **Releases.** A push to `release` (the merge) runs the "Release" workflow: production database
  migrations and backend deploy, then both apps built against production and sent to residents
  (TestFlight group "Neighbors" through Apple's beta review; Firebase group "neighbors"), then a tag
  `vX.Y.Z` and a GitHub release. The version is `VERSION` on `main`, raised in the reviewed pull
  request together with a `CHANGELOG.md` section that residents see as the release notes.
  The workflow refuses a version that was already released.
- **Distribution.** TestFlight (iPhone) and Firebase App Distribution (Android), not the stores
  (owner's choice). TestFlight builds stop working after 90 days, so "TestFlight refresh" rebuilds
  the released code with a new build number every second month; TestFlight installs it automatically
  where automatic updates are on.
- **Server address.** Released apps talk to `https://api.feluda.app`, a domain the owner controls, so
  the backend can move to another host without an app update. Previews use staging.
- **Old versions.** Apps send `X-App-Version: <platform>/<x.y.z>` on every request. The server
  refuses a version below `MIN_APP_VERSION_<PLATFORM>` with 426 `update_required`, and the app then
  stops sending and shows where to update. `GET /api/v1/app/compatibility` also tells an app at
  start-up whether a newer version exists (`LATEST_APP_VERSION_<PLATFORM>`, set by the release).
  Raise a minimum only after the newer version has been distributed, and only when the backend
  can no longer serve the older one; until then backend changes stay compatible with every
  supported version.

**Why.** A mistake in a released app cannot be called back: phones keep running it. Review plus a
real preview on the owner's phones before anything reaches residents, an address the project owns,
and a server-side way to retire versions are the only controls available after installation.

**Consequences.** Every pull request runs all test suites (free on a public repository). Build
numbers are minutes since 2026-01-01, shared by previews and releases, so they always increase.
Signing keys live in GitHub secrets and in the owner's backup; see `docs/RELEASE_PROCESS.md`.
