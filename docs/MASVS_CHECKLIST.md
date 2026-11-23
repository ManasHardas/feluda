# Mobile security checklist (OWASP MASVS)

Baseline: OWASP MASVS v2 control groups, https://mas.owasp.org/MASVS/ . Each row says what the apps
do and how it was checked. "Unit test" means an automated test in the app's test target. "Build"
means the property is visible in build configuration. "Not verified" means it has not been checked
on a real device or by an independent reviewer. This is a self-assessment, not a certification.

Platform specifics and file names are in `apps/android/README.md` and `apps/ios/README.md`.

Revised 2026-11-23: sign-in was removed (decision 0014) and both apps are being updated for
enrolment by invitation link. Rows that changed describe the intended design; they have not been
re-checked against the finished app code, and the earlier unit-test results do not cover them.

## MASVS-STORAGE

| Control | Android | iOS | Checked by |
| --- | --- | --- | --- |
| Sensitive data is stored securely | The device secret (the phone's only credential) encrypted with an AES-GCM key held in the Android Keystore, in app-private storage | The device secret in the Keychain, accessible after first unlock, this device only | Intended design; app code being updated; not verified on a device |
| No sensitive data leaks through backups | `allowBackup="false"`, data-extraction rules exclude everything | Keychain items are device-only; queue file excluded from sensitive content | Build |
| No sensitive data in logs | The device secret and invitation tokens are not logged | Same | To be re-checked after the app update |
| Event queue | Room database in app-private storage; holds only what will be uploaded | File with protection "complete until first user authentication" so background flushes work | Unit tests for queue behaviour |
| Home-network fingerprint | Salted hash, local only, deleted when the phone leaves | Same | Unit tests (from before the change) |

## MASVS-CRYPTO

| Control | Both platforms |
| --- | --- |
| No custom cryptography | Platform libraries only: Android Keystore AES-GCM, CryptoKit SHA-256, TLS by the OS |
| Keys are generated and kept by the platform | Keystore key is non-exportable; no key material in source or resources |
| Randomness | Platform secure random for install ids and salts |

## MASVS-AUTH

| Control | Both platforms |
| --- | --- |
| Authentication is enforced by the remote endpoint | Yes: every protected API call is verified server-side; the apps hold no authorization logic the server trusts |
| Session handling | There are no sessions. The phone sends its device secret on every call; it does not expire and is not refreshed. A 401 means the phone is no longer enrolled: the app deletes the secret and asks for an invitation. Leaving clears everything. This is weaker than a short-lived token: a copied secret works until the phone is removed (`docs/SECURITY.md`) |
| No passwords, no second factor | Nobody signs in. Possession of the device secret is the only proof |

## MASVS-NETWORK

| Control | Android | iOS |
| --- | --- | --- |
| All traffic uses TLS | Network security config forbids cleartext in release; debug allows only the emulator host | App Transport Security with no exceptions in release; debug allows only localhost |
| Certificate validation | Platform default trust store | Platform default |
| Certificate pinning | Not used (decision: a hosted provider rotating its certificate would lock residents out of a safety tool; revisit with a self-managed domain) | Same |
| Destinations | The configured API, FCM, and one disclosed fallback probe target | The configured API, APNs, and one disclosed fallback probe target |

## MASVS-PLATFORM

| Control | Android | iOS |
| --- | --- | --- |
| Minimal permissions | `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `CHANGE_NETWORK_STATE`, `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`. WorkManager and Firebase Messaging are expected to add `WAKE_LOCK`, `FOREGROUND_SERVICE` and `c2dm.permission.RECEIVE` in the merged manifest (not inspected yet). No location, phone state, camera, microphone, contacts or storage | Notifications only. Background modes: fetch and remote notifications. No location, local network, camera, microphone or contacts |
| Each permission is explained | In onboarding and Settings | In onboarding and Settings |
| No exported components beyond what is needed | Only the launcher activity is exported; the messaging service, boot receiver and startup provider are not. The merged manifest (library additions) has not been inspected | Not applicable |
| Deep links | The invitation page opens the app with `feluda://join#t=<token>`, so a custom URL scheme now carries the invitation token; the link can also be pasted. Another app on the phone can register the same scheme and receive the token. The token only enrols; it is limited in uses, expiring and revocable. Not verified on a device | Same scheme; iOS does not define which app wins if two claim it. Not verified on a device |
| WebViews | None | None |
| Notifications | Content comes from the server and contains no address | Same |
| Screens | No sensitive data that needs screenshot protection; addresses appear only on the resident's own household screen | Same |

## MASVS-CODE

| Control | Both platforms |
| --- | --- |
| Up-to-date platform target | Android target SDK 35, minimum 26. iOS deployment target 17.0 |
| Dependencies are pinned and scanned | Android: version catalog, Dependabot weekly. iOS: no third-party dependencies |
| Input from the server is validated | Typed decoding; unknown enum values handled as unknown (unit tests for the JSON contract) |
| Release builds | Android: R8 minification and resource shrinking build successfully; the minified app has not been launched, so keep rules are untested at run time. iOS: release configuration compiles for the simulator, no debug settings |
| Update mechanism | Firebase App Distribution and TestFlight; no in-app code loading |

## MASVS-RESILIENCE

Not a goal for this app. The source is public by design and there are no secrets in the client to
protect, so obfuscation, root or jailbreak detection and anti-tampering are deliberately absent. The
server never trusts the client: a modified app can only misreport its own household
(`docs/THREAT_MODEL.md`).

## MASVS-PRIVACY

| Control | Both platforms |
| --- | --- |
| Data minimization | Enrolment asks for the street and, optionally, a house number: no name, e-mail address or phone number. After that only connectivity state and the diagnostics in the protocol document; unknown values are sent as null, never guessed |
| Transparency | Onboarding education, explicit consent before monitoring, a Transparency screen with what is collected, permissions granted, last report, the queued and recently sent events, source link and security contact |
| Control | Monitoring toggle, notification preferences, remove a phone of the household, leave (which deletes what the phone reported; there is no account to delete) |
| No tracking | No advertising id, no analytics SDK, no third-party crash SDK |
| Declarations | iOS privacy manifest (`PrivacyInfo.xcprivacy`); store privacy answers listed in `docs/APP_DISTRIBUTION.md` |

## Automated checks that back this list

- App unit tests: state machine, gap detection, queue and backoff, fingerprint, contract encoding
  (`make android-test`, `make ios-test`). They last passed before sign-in was removed (iOS 98,
  Android 91); the suites are being updated and have not been re-run for the new flow.
- Backend tests that enforce the server side of these controls (`docs/SECURITY.md`).
- CI: dependency audit, secret scanning, CodeQL for the backend.

Not yet done: dynamic analysis on devices (traffic inspection, storage inspection, backup
extraction), and review by someone other than the authors.
