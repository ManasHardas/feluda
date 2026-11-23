# Release notes template

Copy into the GitHub release, the Firebase App Distribution notes and TestFlight "What to Test".
Keep the resident-facing part short and plain.

```text
Neighborhood Watchdog <version> (build <number>)

What changed
- <one line per change residents will notice>

What to test
- <one or two things, e.g. "Switch Wi-Fi off for a minute at home and check the main screen turns yellow">

Known issues
- <e.g. "iPhone: the app reports only while it is open or when iOS wakes it">

Reminder
- This app detects connectivity anomalies. It cannot confirm deliberate interference and does not replace your alarm system.
- A grey status means unknown, not safe.

For reviewers
- Source: <repository URL> at tag <tag> (commit <sha>)
- Android signing certificate SHA-256: <fingerprint>
- Public build configuration: API_BASE_URL=<...>, AUTH_BASE_URL=<...>
- Checksums and SBOMs are attached to the GitHub release.
- Security review status: <link to docs/SECURITY_REVIEW.md at this tag>
```
