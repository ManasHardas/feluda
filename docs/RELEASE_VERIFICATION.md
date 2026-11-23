# Release verification, SBOM and reproducibility

The source is public so that residents and reviewers can check what the software does. This page
says how to relate a released artifact to the source, and is explicit about where that link is
weaker than "bit-for-bit identical".

Status: the release workflow is written but has never run (no release tag has been pushed).

## What a release contains

Pushing a signed tag `vX.Y.Z` runs `.github/workflows/release.yml`, which builds everything from
that commit and attaches to a draft GitHub release:

| Artifact | Contents |
| --- | --- |
| Backend container image | `ghcr.io/<owner>/<repo>/backend:vX.Y.Z`, with an SBOM and provenance attached to the image, and a build provenance attestation |
| `backend-image-digest.txt` | The image digest |
| `sbom-backend.cdx.json` | CycloneDX SBOM of the backend's direct and transitive Python dependencies, from `uv.lock` |
| Android APK and AAB | Signed if signing secrets are configured, otherwise unsigned; with a build provenance attestation |
| `sbom-android.cdx.json` | Not produced yet: the Android build has no CycloneDX task (see below) |
| `android-build-info.txt`, `ios-build-info.txt` | Commit, JDK, Gradle, Xcode, Swift and OS versions used |
| `sbom-ios.cdx.json` | CycloneDX document with an empty component list: the iOS app has no third-party dependencies |
| `SHA256SUMS.txt` | Checksums of every attached file |

Tags should be signed (`git tag -s vX.Y.Z`) by a key published on the maintainer's GitHub profile.
Verify with `git tag -v vX.Y.Z`. Verify an attestation with
`gh attestation verify <file> --repo <owner>/<repo>`.

## Pinned inputs

| Part | Pinned by |
| --- | --- |
| Backend dependencies | Exact versions in `backend/pyproject.toml`, hashes in `backend/uv.lock` |
| Backend toolchain | Python and uv versions in `backend/Dockerfile` and the workflows |
| Android dependencies and plugins | Version catalog `apps/android/gradle/libs.versions.toml`; Gradle wrapper version and checksum in `gradle-wrapper.properties` |
| Android toolchain | JDK 17 (Temurin) in CI; compile SDK and build tools in the Gradle files |
| iOS | No third-party dependencies. Xcode version is whatever the `macos-15` runner image provides and is recorded in `ios-build-info.txt`; it is not pinned to a minor version |
| CI actions | Pinned to major versions, not commit hashes (a known gap; see below) |

## Known vulnerabilities and remediation decisions

The "Security" workflow audits backend dependencies weekly and on every change, scans history for
secrets, and reviews new dependencies in pull requests. Decisions about findings that are not fixed
immediately are recorded here.

| Date | Component | Advisory | Decision |
| --- | --- | --- | --- |
| 2026-11-22 | PyJWT 2.10.1 | 17 advisories (PYSEC-2025-183, PYSEC-2026-120 and others, GHSA-pxh4-856f-4h89) | Upgraded to 2.15.1. Since 2026-11-23 PyJWT only signs the APNs provider token; it no longer verifies anything |
| 2026-11-22 | Starlette 0.41.3 (through FastAPI 0.115.6) | 7 advisories (PYSEC-2026-161, -248, -249, -1941, -1942, -2280, -2281) | Upgraded FastAPI to 0.165.0, which brings Starlette 1.10.0 |
| 2026-11-22 | All backend dependencies, including the optional `errors` extra | pip-audit: no known vulnerabilities | None needed |
| 2026-11-22 | Android: the 155 resolved packages of `releaseRuntimeClasspath` (direct and transitive) | OSV database (api.osv.dev, Maven ecosystem): no known advisories. The lookup was checked with a known-vulnerable control (okhttp 3.12.0), which it flagged | None needed. Build plugins and test-only dependencies were not checked; iOS has no third-party dependencies |

## Android: comparing a local build with a distributed one

1. Check out the tag, set the same public configuration values the release used
   (`API_BASE_URL` and the others are compiled into the app; they are listed in the release notes),
   and build: `cd apps/android && ./gradlew --no-daemon assembleRelease`.
2. Obtain the distributed APK (Firebase App Distribution download, or
   `adb shell pm path app.feluda` then `adb pull`).
3. Compare contents, ignoring the signature:

   ```bash
   unzip -q local.apk -d local && unzip -q distributed.apk -d dist
   diff -r local dist            # expected differences: META-INF/ (signature files) only
   ```

   or use `apksigcopier compare distributed.apk --unsigned local-unsigned.apk`, which copies the
   signature onto the local build and checks that the result is identical.
4. Verify who signed it: `apksigner verify --print-certs distributed.apk` and compare the
   certificate SHA-256 with the one published in the release notes.

What can differ even from the same commit: a different JDK or Android Gradle Plugin patch version;
build paths if a tool embeds them; and file order or timestamps if the toolchain changes its
defaults. This build has **not** yet been shown to be reproducible; step 3 has not been carried out
on two machines. If the app is ever distributed through Google Play, Play App Signing re-signs it
with a key Google holds and may deliver device-specific split APKs generated from the bundle, so
the installed files will not match a local APK; compare the AAB instead and expect signature and
packaging differences.

Android SBOM: **not produced yet.** The Android build does not apply the CycloneDX Gradle plugin,
so the release workflow's `cyclonedxBom` step only emits a warning and no Android SBOM is attached.
Until the plugin is added (`jtbd/todo.md`),
`./gradlew :app:dependencies --configuration releaseRuntimeClasspath` lists the direct and
transitive dependencies in text form.

## iOS: what can and cannot be verified

Recorded for each build: source commit, Xcode and Swift versions, macOS version, and the archive's
build number. A reviewer can build the tagged source in the same Xcode version and compare
behaviour and the unsigned binary's symbols.

**An App Store or TestFlight binary cannot in general be reproduced bit-for-bit from source.**
Code signing embeds signatures and a provisioning profile; Apple re-signs and may re-process the
app on its side; and encrypted segments differ per delivery. The practical checks are: the build
number in the app's Transparency screen matches a release tag; the app has no third-party code; and
the network destinations it contacts (observable with a proxy on a test device) are only the
configured API and the disclosed fallback probe target (plus Apple's push service, which the
system contacts, not the app).

## Known gaps

- GitHub Actions are pinned by major version tag, not by commit hash.
- Base container images are pinned by tag, not by digest.
- No reproducibility test runs in CI.
- Gradle dependency verification metadata (`verification-metadata.xml`) is not generated.

These are listed in `jtbd/todo.md`.
