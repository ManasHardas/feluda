# MASTER ENGINEERING PROMPT: BUILD NEIGHBORHOOD WATCHDOG

## 0. YOUR ROLE AND MISSION

You are acting as a senior, autonomous engineering team consisting of:

-   Principal Software Architect
-   Senior Android Engineer
-   Senior iOS Engineer
-   Backend Engineer
-   Database Architect
-   Security and Privacy Engineer
-   DevOps / Infrastructure Engineer
-   QA and Automation Engineer
-   Product Designer

Your assignment is to design, implement, test, document, and prepare for
deployment a complete, functional, private, invitation-only mobile
application called **Neighborhood Watchdog**.

Do not merely produce a technical specification, architecture diagram,
or prototype.

Build the actual application, including:

1.  Android application.
2.  iOS application.
3.  Backend services.
4.  Database and migrations.
5.  Authentication and neighborhood membership.
6.  Wireless connectivity monitoring.
7.  Neighborhood-level anomaly correlation.
8.  Notification and alerting system.
9.  Deployment infrastructure.
10. CI/CD pipelines.
11. Private beta distribution.
12. Security, privacy, and operational documentation.

Work directly in the Git repository provided to you.

Android, iOS, and backend must remain independently buildable and
deployable while sharing API contracts and data models.

You are expected to make reasonable engineering decisions autonomously.
Do not stop to ask questions unless a decision is genuinely impossible
without user input.

Where external credentials, developer accounts, certificates, or paid
services are required, implement the integration, document the exact
setup procedure, and clearly identify the outstanding manual action.

**Do not claim that something has been deployed, tested on physical
hardware, approved by Apple, or published unless that action has
actually occurred.**

------------------------------------------------------------------------

# 1. PRODUCT OVERVIEW

## 1.1 Product objective

Neighborhood Watchdog is a private, invitation-only, neighborhood-wide
wireless connectivity monitoring and suspicious interference detection
network.

The application is intended to help residents identify unusual,
potentially coordinated wireless disruptions that could affect home
security cameras, alarms, and other connected security devices.

The suspected threat scenario is:

1.  A criminal attempts to burglarize a residence.
2.  Wireless connectivity is disrupted.
3.  Wi-Fi-dependent cameras and security devices become unavailable.
4.  The criminal attempts to enter the property while the resident's
    security infrastructure is impaired.

The application should identify unusual wireless disruptions at
individual households and correlate reports from other nearby
participating households.

The system should be capable of identifying patterns such as:

-   Several households losing Wi-Fi within a short period.
-   Households connected through different ISPs experiencing similar
    disruptions.
-   Cellular connectivity remaining operational while Wi-Fi connectivity
    fails.
-   Multiple independent households reporting similar connectivity
    failures.
-   Repeated or geographically correlated wireless disruptions.

The system must distinguish between ordinary connectivity failures and
suspicious correlated wireless disruptions.

**Important: This is a wireless anomaly detection system, not a
certified jammer detector.**

A phone application cannot conclusively identify deliberate RF jamming
using ordinary mobile operating system APIs. Never represent an
inference as a confirmed fact.

The product must use terminology such as:

-   Connectivity interruption
-   Wireless connectivity anomaly
-   Correlated wireless disruption
-   Suspected RF interference
-   Elevated neighborhood alert

Never claim confirmed jamming without independently sufficient RF
evidence.

## 1.2 Product principles

The following principles are non-negotiable:

1.  Zero-cost or near-zero-cost infrastructure for a small neighborhood.
2.  Private, invitation-only access.
3.  No public application listing required for the initial pilot.
4.  No dependence on a particular ISP, router manufacturer, or security
    vendor.
5.  Support AT&T, Xfinity, Eero, and other common consumer network
    configurations.
6.  Support Android and iPhone, with clearly documented
    platform-specific capabilities.
7.  Monitoring should be event-driven, supplemented by periodic health
    checks where supported.
8.  Minimize battery consumption.
9.  Use cellular connectivity for independent event reporting whenever
    available.
10. Minimize personal information collected and stored.
11. Make the system resilient to false reports, malicious participants,
    and network failures.
12. Do not imply that missing reports mean a household is safe.

------------------------------------------------------------------------

# 2. REQUIRED PROJECT STRUCTURE

Build the application as a monorepo.

Android, iOS, and backend must remain independently buildable and
deployable.

Use a clear repository structure similar to:

``` text
neighborhood-watchdog/
|
|-- apps/
|   |
|   |-- android/
|   |   |-- app/
|   |   |-- build.gradle.kts
|   |   |-- README.md
|   |
|   |-- ios/
|   |   |-- NeighborhoodWatchdog/
|   |   |-- NeighborhoodWatchdog.xcodeproj
|   |   |-- README.md
|   |
|   |-- admin/
|       |-- README.md
|       |-- optional restricted administrator tooling only, if justified
|
|-- backend/
|   |
|   |-- api/
|   |-- services/
|   |-- correlation/
|   |-- notifications/
|   |-- authentication/
|   |-- migrations/
|   |-- tests/
|   |-- Dockerfile
|
|-- shared/
|   |
|   |-- api-contracts/
|   |-- schemas/
|   |-- documentation/
|
|-- infrastructure/
|   |
|   |-- supabase/
|   |-- deployment/
|   |-- monitoring/
|   |-- scripts/
|
|-- docs/
|   |
|   |-- ARCHITECTURE.md
|   |-- SECURITY.md
|   |-- PRIVACY.md
|   |-- THREAT_MODEL.md
|   |-- DETECTION_PROTOCOL.md
|   |-- API.md
|   |-- DEPLOYMENT.md
|   |-- ONBOARDING.md
|   |-- TESTING.md
|   |-- OPERATIONS.md
|   |-- APP_DISTRIBUTION.md
|
|-- scripts/
|-- .github/
|   |-- workflows/
|
|-- .env.example
|-- .gitignore
|-- Makefile
|-- README.md
```

You may modify this structure if you have a strong architectural reason.
Document the decision.

Do not create unnecessary microservices. Prefer a modular monolith
initially.

------------------------------------------------------------------------

# 3. TECHNOLOGY STACK

Use the following default technology choices unless a specific
incompatibility justifies an alternative.

## 3.1 Android

-   Language: Kotlin
-   UI: Jetpack Compose
-   Architecture: MVVM with clean separation of concerns
-   Dependency injection: Hilt
-   Networking: Retrofit / OkHttp
-   Serialization: Kotlin Serialization
-   Background monitoring: Android-supported connectivity callbacks and
    WorkManager for appropriate deferred tasks
-   Secure local storage: Android Keystore
-   Push notifications: Firebase Cloud Messaging
-   Minimum Android version: choose a practical supported baseline and
    document it.

Use native Android APIs.

Relevant APIs include:

-   ConnectivityManager
-   NetworkCallback
-   WifiManager
-   WorkManager
-   TelephonyManager, where appropriate and permission-compliant

Do not assume every API is available in every Android version.

Do not request unnecessary permissions.

## 3.2 iOS

-   Language: Swift
-   UI: SwiftUI
-   Architecture: MVVM
-   Networking: URLSession
-   Connectivity monitoring: Network framework, including NWPathMonitor
-   Secure local storage: Keychain
-   Push notifications: Apple Push Notification service
-   Background execution: only documented, permitted iOS background
    mechanisms

Use native iOS APIs.

The iOS application must explicitly acknowledge that background
execution is controlled by iOS.

Do not invent background execution capabilities.

Do not use continuous location tracking as a workaround for background
monitoring.

Do not request location permission unless a specific, justified feature
requires it.

## 3.3 Backend

Preferred:

-   Python
-   FastAPI
-   PostgreSQL
-   PostGIS where useful
-   Supabase for managed PostgreSQL and authentication infrastructure
-   Supabase Edge Functions or a lightweight FastAPI service for
    business logic
-   Firebase Cloud Messaging for Android notifications
-   APNs for iOS notifications

Keep the backend portable.

All core application logic should be testable without requiring a
production Supabase account.

Use Docker for local development.

Do not make the application dependent on Supabase-specific functionality
where a standard PostgreSQL implementation is sufficient.

## 3.4 Infrastructure

Primary objective: minimize operating expenses.

Use:

-   Supabase free tier where its current limits are sufficient.
-   Managed PostgreSQL.
-   Free-tier hosting where technically reliable.
-   GitHub Actions for CI/CD.
-   Firebase services where appropriate.

Do not assume a free tier provides unlimited resources or guaranteed
uptime.

Provide a cost estimate for:

-   10 households
-   50 households
-   100 households
-   500 households

Identify any point at which free-tier limitations become problematic.

Do not provision expensive infrastructure without explicit approval.

------------------------------------------------------------------------

# 4. USER ONBOARDING AND PRIVATE NEIGHBORHOOD MEMBERSHIP

## 4.1 Invitation model

This is not a public application.

An existing neighborhood organizer sends invitations through WhatsApp or
another private communication channel.

The invitation contains a secure, single-use registration link.

The expected user journey:

1.  Resident receives invitation.
2.  Resident clicks the invitation.
3.  Resident opens a registration page.
4.  Resident enters household information.
5.  Backend validates the invitation.
6.  Resident creates an account.
7.  Resident installs the appropriate mobile application.
8.  Resident authenticates.
9.  Resident completes device setup.
10. Monitoring begins after explicit consent.

The invitation must be associated with exactly one neighborhood.

Do not permit users to join another neighborhood simply by changing a
neighborhood identifier in a request.

## 4.2 Household registration

Collect only necessary information.

Registration fields:

-   House number
-   Street name
-   Neighborhood invitation code
-   Email address or another suitable account recovery mechanism
-   Optional display label for the household

Do not require residents to provide their full legal names.

Normalize addresses before storage.

Resolve the submitted address against the neighborhood's approved
household records.

The backend should maintain a mapping:

-   household_id
-   neighborhood_id
-   normalized_house_number
-   normalized_street_name
-   membership_status
-   created_at

Do not expose the address mapping to other residents.

Other users should only see anonymized household references or aggregate
neighborhood information.

## 4.3 Invitation security

Implement:

-   Cryptographically random invitation tokens.
-   At least 128 bits of entropy.
-   Single-use invitations.
-   Configurable expiry.
-   Server-side token validation.
-   Rate limiting.
-   Invitation revocation.
-   Audit logging.
-   Protection against invitation enumeration.

Never put raw household addresses or permanent authentication tokens in
invitation URLs.

Store only hashed invitation tokens in the database.

A WhatsApp group membership is not equivalent to verified authorization.

Use an explicit invitation mechanism controlled by a neighborhood
administrator.

## 4.4 Household verification

Provide an administrative mechanism to prepopulate or approve
participating addresses.

Prevent duplicate household registrations unless explicitly allowed.

Support multiple authorized residents or devices per household.

Provide an administrator-controlled household removal mechanism.

------------------------------------------------------------------------

# 5. PRIVACY AND SECURITY REQUIREMENTS

Privacy and security are first-class product requirements.

Treat household addresses, network characteristics, and incident
timestamps as sensitive information.

Being a member of a private neighborhood group does not eliminate the
need for individual consent and data minimization.

## 5.1 Data minimization

Do not collect:

-   Browsing history.
-   DNS browsing history.
-   Website URLs.
-   Personal messages.
-   Wi-Fi passwords.
-   Router administration passwords.
-   Device contents.
-   Continuous GPS coordinates.
-   Unnecessary device identifiers.
-   Camera footage.
-   Microphone recordings.

The application must never attempt to access a resident's security
camera footage.

The application does not need credentials for Ring, SimpliSafe, Eero,
AT&T, Xfinity, or other services.

## 5.2 Household identity

Use internal UUIDs for:

-   Users
-   Households
-   Neighborhoods
-   Devices
-   Connectivity events
-   Incidents

Separate personally identifying information from incident telemetry.

Implement authorization so a household cannot query another household's
address, device identifiers, or detailed event history.

Only authorized neighborhood administrators may access household
membership information.

## 5.3 Location privacy

Use registered household addresses to establish neighborhood membership
and geographic relationships.

The mobile application should not transmit continuous GPS coordinates.

For correlation, prefer server-side geographic calculations based on the
approved household location.

Use PostGIS or an equivalent spatial approach.

The backend should calculate which participating households fall within
a configurable radius, initially 500 meters.

Do not expose exact coordinates or addresses in neighborhood incident
reports.

Use anonymized household counts and aggregate geographic information in
the UI.

## 5.4 Authentication

Implement secure authentication.

Preferred approach:

-   Supabase Auth or an equivalent secure identity provider.
-   Email OTP or magic-link authentication.
-   Short-lived access tokens.
-   Refresh-token rotation.
-   Secure local storage.
-   Server-side authorization on every protected endpoint.

Do not rely on client-side neighborhood membership checks.

All authorization must be enforced by the backend.

## 5.5 Database security

Implement PostgreSQL Row Level Security wherever appropriate.

Requirements:

-   Users can access their own account information.
-   Users can access their own household information.
-   Users can submit events for authorized devices.
-   Users cannot modify neighborhood membership.
-   Users cannot read other households' raw event telemetry.
-   Only authorized administrators can invite, suspend, or remove
    members.
-   The correlation service can access only the information required to
    perform correlation.

Never embed Supabase service-role keys in mobile applications.

Never embed private API keys in source code.

## 5.6 Encryption

Use HTTPS/TLS for all network communication.

Use managed database encryption at rest where supported.

Use platform secure storage for credentials.

Do not invent custom cryptographic algorithms.

Use established cryptographic libraries.

Document encryption and key rotation.

## 5.7 Abuse prevention

Assume that a malicious or compromised participant could attempt to
generate false incidents.

Implement:

-   Per-device rate limits.
-   Per-household rate limits.
-   Duplicate event detection.
-   Timestamp validation.
-   Event replay protection.
-   Device registration and revocation.
-   Request authentication.
-   Basic anomaly detection for suspicious reporting behavior.
-   Audit logs for administrative operations.

A single device must never be able to generate a neighborhood-wide
emergency alert by itself.

Do not allow an ordinary resident to fabricate an incident affecting
other households.

## 5.8 Data retention

Implement configurable retention policies.

Suggested defaults:

-   Raw connectivity events: 30 days.
-   Aggregated incident records: 90 days.
-   Security audit records: 90 days.
-   Account data: until account deletion.
-   Backups: according to documented provider retention.

Provide a deletion mechanism for residents who leave the neighborhood.

Document what remains in aggregated or legally required records after
deletion.

## 5.9 Security standard

Use OWASP MASVS as the mobile security baseline.

Address:

-   Secure storage.
-   Authentication.
-   Network security.
-   Platform interaction.
-   Code security.
-   Privacy.
-   Resilience.

Produce a security checklist and automated tests.

Reference: https://mas.owasp.org/MASVS/

------------------------------------------------------------------------

# 6. CORE FEATURE: HOUSEHOLD CONNECTIVITY MONITORING

This is the most important mobile application feature.

The application must monitor the connectivity state of the resident's
phone and identify unusual changes.

Use event-driven monitoring supplemented by periodic health checks where
permitted.

## 6.1 Connectivity states

Represent household monitoring using explicit states:

-   HEALTHY
-   WIFI_DISCONNECTED
-   WIFI_CONNECTED_NO_INTERNET
-   INTERNET_UNAVAILABLE
-   CELLULAR_AVAILABLE
-   CELLULAR_UNAVAILABLE
-   NETWORK_TRANSITION
-   MONITORING_PAUSED
-   MONITORING_PERMISSION_REQUIRED
-   UNKNOWN

Do not collapse all failures into one generic disconnected state.

## 6.2 Detection events

Record events such as:

-   Wi-Fi connection lost.
-   Wi-Fi connection restored.
-   Default network changed.
-   Cellular network became available.
-   Cellular network became unavailable.
-   Internet reachability changed.
-   Signal strength changed, when supported.
-   Wi-Fi network association changed, when supported.
-   Monitoring service resumed.
-   Monitoring service stopped.
-   User disabled monitoring.

Every event should include:

-   Event UUID.
-   Household UUID.
-   Device UUID.
-   Timestamp in UTC.
-   Event type.
-   Platform.
-   OS version.
-   Application version.
-   Connectivity state.
-   Available diagnostic metadata.
-   Confidence and capability indicators.

Never invent unavailable measurements.

## 6.3 Local connectivity checks

Where permitted, check:

1.  Is the phone connected to Wi-Fi?
2.  Is the local network reachable?
3.  Is an internet connection available?
4.  Is cellular connectivity available?
5.  Did the network change recently?
6.  Has the Wi-Fi connection recovered?

Do not assume that the router uses 192.168.1.1.

Use the actual network information exposed by the operating system where
available.

Do not treat a failed ICMP ping as definitive evidence of network
failure.

Use multiple reasonable connectivity checks.

## 6.4 Independent event reporting

When Wi-Fi fails, the application should attempt to report the event
using cellular connectivity.

On Android, investigate supported network-specific routing using
ConnectivityManager and Network APIs.

Do not assume that the phone automatically routes an HTTP request over
cellular just because cellular is available.

On iOS, use supported network path selection and URLSession behavior.

Do not claim guaranteed cellular routing if the operating system does
not permit it.

The application should:

-   Attempt independent cellular reporting where supported.
-   Queue events locally if reporting fails.
-   Retry with exponential backoff.
-   Prevent duplicate submissions.
-   Preserve event timestamps.
-   Report delayed events with their original occurrence time.
-   Distinguish occurrence time from reporting time.

If neither network is available, retain the event locally and report it
when connectivity returns.

## 6.5 Wi-Fi bands

The product should investigate whether both 2.4 GHz and 5 GHz are
affected.

However, the application must acknowledge that:

-   A phone normally associates with one Wi-Fi access point on one band
    at a time.
-   A standard mobile application cannot continuously measure both
    bands.
-   Wi-Fi scanning is restricted and throttled on Android.
-   iOS does not provide unrestricted general-purpose Wi-Fi scanning.
-   RF energy that is not represented by Wi-Fi access points may not be
    visible to ordinary Wi-Fi APIs.

Do not report that both bands have failed unless there is actual
evidence.

Use explicit evidence classifications:

-   BAND_2_4_OBSERVED
-   BAND_5_OBSERVED
-   BAND_6_OBSERVED
-   BAND_UNKNOWN
-   MULTI_BAND_FAILURE_NOT_VERIFIED

The app should not claim RF spectrum analysis.

------------------------------------------------------------------------

# 7. ANDROID IMPLEMENTATION

Build a complete native Android application.

## 7.1 Monitoring implementation

Use ConnectivityManager.NetworkCallback to observe network state
changes.

Investigate:

-   onAvailable
-   onLost
-   onCapabilitiesChanged
-   onLinkPropertiesChanged

Use WifiManager only for functionality supported by the current Android
version and available permissions.

Implement a dedicated connectivity monitoring component.

Separate monitoring logic from the UI.

## 7.2 Background operation

Android background restrictions must be respected.

Evaluate the use of:

-   NetworkCallback
-   WorkManager
-   Foreground service only if genuinely justified and permitted
-   Persistent local event storage

Do not create a permanent foreground service simply to keep the
application alive unless there is a valid platform and policy
justification.

Provide a visible monitoring status.

If Android terminates or restricts monitoring, report the monitoring
gap.

Do not silently claim the device is being monitored.

## 7.3 Permissions

Request only permissions necessary for actual features.

Explain each permission to the user.

Do not request location permission simply to access data that can be
obtained through another supported mechanism.

Document Android-version-specific restrictions.

## 7.4 Android testing

Test:

-   Wi-Fi disconnection.
-   Wi-Fi reconnection.
-   Wi-Fi connected without internet.
-   Cellular available while Wi-Fi fails.
-   Cellular unavailable.
-   Airplane mode.
-   Device reboot.
-   App process termination.
-   Background restrictions.
-   Battery saver.
-   Permission revocation.
-   Network transitions.
-   Delayed event uploads.

Include physical-device test instructions.

------------------------------------------------------------------------

# 8. IOS IMPLEMENTATION

Build a native SwiftUI iOS application.

## 8.1 Monitoring

Use NWPathMonitor to observe network path changes.

Track:

-   Wi-Fi interface availability.
-   Cellular interface availability.
-   Network path changes.
-   Internet reachability where observable.
-   Monitoring status.
-   Last successful observation.

Do not assume that an available network path guarantees the application
can access every internet destination.

## 8.2 Background limitations

iOS may suspend applications in the background.

The application must not pretend to provide uninterrupted monitoring.

Use only permitted background execution capabilities.

Implement the best supported event-driven behavior and clearly expose
monitoring gaps.

When the application resumes, reconcile current connectivity state and
report the gap.

Never use unauthorized background modes.

Never use location tracking as a pretext to maintain background
execution.

## 8.3 iOS testing

Test:

-   Wi-Fi loss.
-   Cellular transition.
-   Wi-Fi recovery.
-   App backgrounding.
-   Device locking.
-   App termination.
-   Airplane mode.
-   Cellular loss.
-   Permission changes.
-   Push notification delivery.
-   Background suspension and resume.

Use real iPhones, not only simulators.

------------------------------------------------------------------------

# 9. DETECTION AND CLASSIFICATION ENGINE

This is the core differentiating capability.

Implement a modular event-driven detection engine.

It must distinguish:

1.  Individual household connectivity failure.
2.  Likely ISP or upstream outage.
3.  Router or power failure.
4.  Local Wi-Fi failure.
5.  Possible RF interference.
6.  Correlated neighborhood wireless disruption.
7.  Suspicious correlated disruption requiring resident attention.

## 9.1 Individual household classification

When a connectivity event arrives, evaluate:

A. Is Wi-Fi disconnected?

B. Is cellular connectivity available?

C. Is the router reachable?

D. Is the internet reachable?

E. Did the phone switch networks?

F. Is the monitoring device reporting a reboot or monitoring gap?

G. Are there repeated failures?

H. Is the failure isolated to one household?

Do not assume every Wi-Fi failure is an RF event.

## 9.2 Classification logic

Use evidence-based classifications.

Suggested categories:

NORMAL

No significant connectivity anomaly.

HOUSEHOLD_CONNECTIVITY_ISSUE

One household reports an interruption.

POSSIBLE_ISP_OUTAGE

Multiple connectivity indicators suggest an upstream internet problem.

POSSIBLE_ROUTER_OR_POWER_FAILURE

The local network is unavailable without sufficient evidence of RF
interference.

POSSIBLE_WIRELESS_INTERFERENCE

A household reports a wireless anomaly while other connectivity
indicators remain operational.

CORRELATED_NEIGHBORHOOD_DISRUPTION

Multiple independent households report similar wireless failures within
a defined geographic and temporal window.

HIGH_PRIORITY_SUSPICIOUS_DISRUPTION

Multiple independent indicators support an elevated suspicion of
coordinated wireless interference.

These are operational classifications, not proof of criminal activity.

## 9.3 Confidence model

Implement a configurable scoring model.

Do not hardcode arbitrary claims such as "three houses means jamming."

Evaluate:

-   Number of independent affected households.
-   Number of active participating households.
-   Percentage of reporting households.
-   Time difference between events.
-   Geographic proximity.
-   ISP diversity.
-   Cellular availability.
-   Router reachability.
-   Available Wi-Fi diagnostic evidence.
-   Monitoring coverage.
-   Historical baseline.

Use a transparent, explainable score.

Every score must have a breakdown explaining which indicators
contributed.

Do not use machine learning initially.

A deterministic rules-based model is easier to validate and explain.

Make thresholds configurable through backend configuration.

## 9.4 Avoid correlated false positives

Consider scenarios such as:

-   An ISP-wide outage.
-   A neighborhood power outage.
-   A planned router firmware update.
-   A widespread DNS failure.
-   A mobile carrier outage.
-   A device operating system update.
-   Several residents rebooting their routers simultaneously.

Do not classify these automatically as jamming.

ISP diversity is useful evidence but not definitive proof.

If AT&T and Xfinity households both experience disruptions, increase
suspicion only when the other indicators support it.

## 9.5 Detection explanation

Every incident must include an explanation.

Example:

"Five participating households reported Wi-Fi connectivity interruptions
within 35 seconds. The affected households use different internet
providers. Cellular connectivity remained available for four reporting
devices. This pattern is consistent with a correlated wireless
disruption. Deliberate interference has not been confirmed."

Never display an unexplained confidence percentage.

------------------------------------------------------------------------

# 10. NEIGHBORHOOD CORRELATION ENGINE

## 10.1 Neighborhood isolation

Each household belongs to one neighborhood.

A household must never be able to query events from another
neighborhood.

The backend must enforce this independently of the mobile client.

## 10.2 Geographic correlation

Use the registered household location.

Initially configure:

-   Correlation radius: 500 meters.
-   Temporal window: 60 seconds.
-   Configurable minimum affected-household count.
-   Configurable minimum affected-household percentage.

These are initial experimental parameters, not validated detection
thresholds.

Support multiple simultaneous incidents.

Use PostGIS if it simplifies spatial calculations.

Do not expose addresses to other households.

## 10.3 Active household denominator

Track the number of households that were recently reporting normally.

An offline household must not automatically count as an affected
household.

Distinguish:

-   Confirmed monitoring activity.
-   Known monitoring gap.
-   No recent telemetry.
-   Connectivity failure reported.

Missing telemetry must never be treated as proof of a disruption.

## 10.4 Event deduplication

Implement idempotency.

A single connectivity event must not generate multiple incident reports.

Use stable event UUIDs.

Support delayed uploads and out-of-order event delivery.

Use event occurrence time for correlation, not only backend receipt
time.

------------------------------------------------------------------------

# 11. BACKEND API

Implement documented, versioned REST APIs.

Use /api/v1.

Suggested endpoints:

-   POST /api/v1/auth/register
-   POST /api/v1/auth/verify-invitation
-   POST /api/v1/households/join
-   GET /api/v1/households/me
-   GET /api/v1/neighborhoods/me
-   GET /api/v1/connectivity/status
-   POST /api/v1/connectivity/events
-   GET /api/v1/incidents
-   GET /api/v1/incidents/{incident_id}
-   POST /api/v1/devices/register
-   POST /api/v1/devices/revoke
-   POST /api/v1/notifications/register
-   GET /api/v1/monitoring/health
-   POST /api/v1/admin/invitations
-   POST /api/v1/admin/households/suspend
-   POST /api/v1/admin/households/remove
-   GET /api/v1/admin/neighborhood/summary

Use strict request validation.

Use typed API schemas.

Generate OpenAPI documentation.

Include authentication and authorization tests.

Do not trust household_id or neighborhood_id values supplied by clients
without server-side verification.

------------------------------------------------------------------------

# 12. DATABASE DESIGN

Design normalized PostgreSQL tables.

At minimum:

-   users
-   neighborhoods
-   households
-   household_members
-   invitations
-   devices
-   device_sessions
-   connectivity_events
-   incident_candidates
-   incidents
-   incident_households
-   notification_tokens
-   notification_deliveries
-   admin_audit_logs
-   system_configuration

Include:

-   UUID primary keys.
-   Foreign keys.
-   Appropriate indexes.
-   Created and updated timestamps.
-   UTC timestamps.
-   Appropriate uniqueness constraints.
-   Data retention support.
-   Neighborhood isolation.
-   RLS policies where supported.

Use migrations.

Never manually modify production database schemas without migrations.

Create a seed script for a simulated neighborhood containing at least 20
households.

Include simulated AT&T and Xfinity households.

------------------------------------------------------------------------

# 13. NOTIFICATION SYSTEM

Implement push notifications.

Android:

Firebase Cloud Messaging.

iOS:

Apple Push Notification service.

SMS should be optional and disabled by default because it introduces
operating costs.

## 13.1 Alert levels

LEVEL 0: NORMAL

No meaningful connectivity issue.

LEVEL 1: HOUSEHOLD WARNING

One household reports a connectivity interruption.

Notify only that household.

LEVEL 2: NEIGHBORHOOD ANOMALY

Multiple households report correlated wireless disruptions.

Notify participating households within the affected neighborhood.

LEVEL 3: HIGH-PRIORITY SECURITY ALERT

Multiple independent indicators suggest a significant neighborhood
wireless disruption.

Send a prominent notification to participating households.

Do not automatically contact law enforcement.

## 13.2 Alert content

Example:

"NEIGHBORHOOD WIRELESS ALERT

Multiple participating households have reported simultaneous Wi-Fi
disruptions.

Possible wireless interference has been detected.

This does not confirm deliberate jamming.

Please check your alarm and camera connectivity through their respective
applications and verify that your security system's backup protection is
operating."

## 13.3 Notification reliability

Implement:

-   Retry logic.
-   Delivery tracking.
-   Duplicate suppression.
-   Notification preferences.
-   Quiet hours for noncritical messages.
-   Critical notification handling consistent with OS permissions.
-   Notification expiration.
-   Incident resolution notifications.

Do not promise that a push notification will be delivered if a phone has
no connectivity.

------------------------------------------------------------------------

# 14. MOBILE APPLICATION UI

Keep the interface extremely simple.

The primary audience is ordinary neighborhood residents, not technical
users.

## 14.1 Main screen

Display:

-   Household monitoring status.
-   Wi-Fi connectivity.
-   Internet connectivity.
-   Cellular availability.
-   Last successful monitoring event.
-   Monitoring permission status.
-   Neighborhood incident status.

Suggested states:

GREEN: No current connectivity anomaly.

YELLOW: Household connectivity interruption.

ORANGE: Multiple nearby connectivity disruptions.

RED: High-priority suspicious neighborhood disruption.

Always explain what the color means.

## 14.2 Incident screen

Display:

-   Incident start time.
-   Number of affected households.
-   Geographic scope in approximate terms.
-   Correlation window.
-   ISP diversity, where known.
-   Evidence supporting the classification.
-   Evidence that is missing.
-   Current incident status.
-   Recommended actions.

Do not display household addresses.

## 14.3 Settings screen

Include:

-   Monitoring enabled/disabled.
-   Notification preferences.
-   Privacy information.
-   Household membership.
-   Device registration.
-   Application version.
-   Last successful monitoring event.
-   Leave neighborhood.
-   Delete account.

## 14.4 Onboarding education

Before enabling monitoring, clearly explain:

-   What is monitored.
-   What is transmitted.
-   What is not collected.
-   Why cellular connectivity is used.
-   Why background monitoring has limitations.
-   How neighborhood correlation works.
-   How to disable monitoring.
-   How to leave the network.

Require explicit consent.

------------------------------------------------------------------------

# 15. ADMINISTRATOR CAPABILITIES

Build a minimal administrative interface or documented admin API.

Administrators should be able to:

-   Create a neighborhood.
-   Register approved household addresses.
-   Generate invitations.
-   Revoke invitations.
-   View household membership.
-   Suspend a household.
-   Remove a household.
-   View aggregate connectivity status.
-   View incident history.
-   Change detection thresholds.
-   View notification delivery failures.
-   Export anonymized incident data.

Administrators must not be able to access unnecessary personal device
information.

Require strong authentication for administrator access.

Log all administrative actions.

------------------------------------------------------------------------

# 16. DISTRIBUTION AND PRIVATE PUBLICATION

The application must be distributed privately.

There must be no requirement to publish the application publicly during
the pilot.

## 16.1 Android distribution

Preferred: Firebase App Distribution.

Implement a repeatable build and distribution process.

Requirements:

-   Generate signed Android APK or appropriate Android build artifact.
-   Configure Firebase App Distribution.
-   Support tester groups.
-   Provide invitation instructions.
-   Provide a release notes template.
-   Document certificate and signing-key management.
-   Automate distribution through GitHub Actions where credentials are
    available.

Alternative: Google Play Console internal testing.

Do not assume a Google Play production release is required for the
initial pilot.

Official documentation:

https://firebase.google.com/docs/app-distribution

https://support.google.com/googleplay/android-developer/answer/9845334

## 16.2 iOS distribution

Preferred: TestFlight external testing.

Requirements:

-   Configure bundle identifier.
-   Configure signing.
-   Configure provisioning.
-   Configure App Store Connect application.
-   Generate archive.
-   Upload build.
-   Provide beta testing information.
-   Configure external tester groups.
-   Document first-build review requirements.
-   Provide private invitation instructions.

External TestFlight distribution requires Apple's review of the first
external build. Apple supports up to 10,000 external testers.

Do not confuse TestFlight distribution with public App Store
publication.

Official documentation:

https://developer.apple.com/testflight/

https://developer.apple.com/help/app-store-connect/test-a-beta-version/invite-external-testers

Do not promise immediate approval.

## 16.3 WhatsApp onboarding

The WhatsApp invitation must lead to an appropriate registration flow.

The registration page should:

-   Explain the product.
-   Verify the invitation.
-   Collect household information.
-   Explain privacy.
-   Offer the appropriate installation link.
-   Provide installation instructions.
-   Link to support documentation.

Do not embed reusable authentication secrets in WhatsApp links.

------------------------------------------------------------------------

# 17. DEPLOYMENT ARCHITECTURE

Use Supabase as the initial managed database and authentication
provider.

Implement a portable backend service.

The architecture should be:

``` text
Android application
       |
       | HTTPS
       |
iOS application
       |
       | HTTPS
       |
Backend API
       |
       +---- Authentication
       |
       +---- Event ingestion
       |
       +---- Correlation engine
       |
       +---- Notification service
       |
       +---- PostgreSQL / PostGIS
```

Use a modular backend.

Do not introduce Kafka, Redis, Kubernetes, or other infrastructure
unless justified by measured requirements.

For the initial neighborhood size, a simple PostgreSQL-backed
event-processing architecture is sufficient.

## 17.1 Deployment environments

Create:

-   Development
-   Staging
-   Production

Do not share production credentials with development.

Provide:

-   Environment variable templates.
-   Database migration scripts.
-   Deployment scripts.
-   Health checks.
-   Backup instructions.
-   Rollback instructions.
-   Monitoring instructions.

## 17.2 Infrastructure as code

Where useful, use Terraform or reproducible deployment configuration.

Do not make infrastructure dependent on a developer's local machine.

## 17.3 Secrets

Use environment variables and managed secrets.

Never commit:

-   API keys.
-   Signing certificates.
-   Service-role keys.
-   APNs private keys.
-   Firebase service-account credentials.
-   Database passwords.
-   Production tokens.

Provide .env.example with placeholder values only.

------------------------------------------------------------------------

# 18. PUBLIC SOURCE CODE, RELEASE VERIFIABILITY, AND TRUST

The source repository must be public. Neighborhood access and operational data remain private.

The purpose of a public repository is to let residents, independent developers, and security reviewers inspect what the application does and how it handles their data.

## 18.1 Public repository

- Create or prepare a public GitHub repository.
- Keep source code, build configuration, API contracts, database migrations, and non-sensitive deployment templates in the repository.
- Never commit production data, resident addresses, invitation tokens, credentials, signing keys, certificates, or private configuration.
- Keep infrastructure configuration free of secrets.
- Include a clear security contact and vulnerability-reporting process.
- Include a SECURITY.md policy explaining how to report vulnerabilities.
- Include a CONTRIBUTING.md with build and verification instructions.
- Include a CODEOWNERS file for critical code paths where appropriate.
- Protect the default branch and require review for security-sensitive changes.

## 18.2 Software Bill of Materials

Generate and publish an SBOM for each release using a suitable standard such as SPDX or CycloneDX.

Include direct and transitive dependencies where tooling permits.

Automate dependency vulnerability scanning in CI.

Document known vulnerabilities and remediation decisions.

## 18.3 Reproducible and verifiable builds

Make builds reproducible to the greatest extent practical.

- Pin dependency versions.
- Use lockfiles.
- Pin build toolchain versions.
- Record compiler and SDK versions.
- Provide documented build commands.
- Generate build provenance and release checksums.
- Publish signed release tags.
- Attach SBOMs and build metadata to releases.
- Separate signing secrets from the public repository.
- Document any platform signing or packaging steps that prevent bit-for-bit reproducibility.

For Android, provide a documented process for comparing a locally built artifact with the distributed artifact, and explain any differences introduced by Play App Signing or store processing.

For iOS, document the source revision, build environment, archive metadata, and signing/repackaging limitations. Do not falsely claim that an App Store binary can always be reproduced bit-for-bit from source.

## 18.4 Independent security review

Before moving from beta to broad neighborhood use:

- Arrange an independent review of the application source and backend.
- Review permissions, network calls, data collection, authentication, authorization, storage, and update mechanisms.
- Review the actual release artifacts and their relationship to the source.
- Publish a concise security review summary, including scope, date, findings, and remediation status.
- Do not claim a formal audit or certification unless one was actually performed.

## 18.5 In-app transparency

Include a Transparency section in the mobile applications with:

- Public source repository link.
- Current app version and build number.
- Privacy policy.
- Data collected and transmitted.
- Permissions currently granted.
- Last successful event report.
- Monitoring status and known platform limitations.
- Security review summary or report link.
- Contact details for reporting security issues.

Residents must be able to inspect what the app reports and disable monitoring.

## 18.6 Trust messaging

Do not use claims such as "completely safe," "unhackable," or "guaranteed private."

Use factual language describing the controls implemented and the evidence available to verify them.

---

# 18. CI/CD AND GIT WORKFLOW

Set up GitHub Actions.

Required workflows:

1.  Backend lint and unit tests.
2.  Backend integration tests.
3.  Android build and tests.
4.  iOS build and tests on macOS runners.
5.  Database migration validation.
6.  API schema validation.
7.  Security dependency scanning.
8.  Release artifact generation.
9.  Optional private beta distribution.

Use meaningful commit messages.

Use feature branches.

Do not push secrets.

Do not automatically deploy unreviewed changes to production.

Include a Makefile with common commands.

Examples:

``` bash
make setup
make backend-test
make android-build
make ios-build
make test
make docker-up
make docker-down
make migrate
make seed
```

------------------------------------------------------------------------

# 20. TESTING AND QUALITY ASSURANCE

Testing must be comprehensive.

## 19.1 Unit tests

Test:

-   Connectivity event classification.
-   Event deduplication.
-   Timestamp normalization.
-   Neighborhood matching.
-   Geographic correlation.
-   Confidence scoring.
-   Authentication.
-   Authorization.
-   Invitation expiry.
-   Invitation reuse prevention.
-   Notification routing.
-   Rate limiting.

## 19.2 Integration tests

Test:

-   Household onboarding.
-   Device registration.
-   Event ingestion.
-   Event persistence.
-   Neighborhood correlation.
-   Incident generation.
-   Push notification creation.
-   Account removal.
-   Household suspension.

## 19.3 Security tests

Test:

-   Unauthorized household access.
-   Cross-neighborhood access.
-   Invitation enumeration.
-   Replay attacks.
-   Duplicate event submission.
-   Malformed payloads.
-   Excessive event reporting.
-   Privilege escalation.
-   Token expiration.
-   Administrative authorization.
-   Secret exposure.

## 19.4 Simulation tests

Create a deterministic incident simulator.

Simulate:

Scenario A: One household loses Wi-Fi.

Expected: Household connectivity warning.

Scenario B: Five Xfinity households lose internet connectivity
simultaneously while Wi-Fi remains associated.

Expected: Possible ISP outage, not automatic jamming classification.

Scenario C: Three AT&T and three Xfinity households report simultaneous
Wi-Fi disconnections while cellular connectivity remains available.

Expected: Correlated wireless disruption.

Scenario D: Ten households report simultaneous failures during a
neighborhood power outage.

Expected: Power or local infrastructure issue if supported by available
evidence; do not automatically label jamming.

Scenario E: One malicious device submits 100 false events.

Expected: Rate limiting and no fabricated neighborhood-wide alert.

Scenario F: Multiple events arrive 10 minutes late.

Expected: Correct correlation using occurrence timestamps, with
delayed-reporting metadata.

Scenario G: A resident disables monitoring.

Expected: Monitoring status becomes inactive or unknown, never healthy.

Scenario H: A household has no cellular connection.

Expected: Event is queued locally where possible and reported when
connectivity returns.

Scenario I: iOS suspends the application.

Expected: Monitoring gap is explicitly recorded when detectable and
reconciled on resume.

Scenario J: Two unrelated households report failures 30 minutes apart.

Expected: No correlated incident.

All scenarios must have automated tests.

------------------------------------------------------------------------

# 21. OBSERVABILITY AND OPERATIONS

Implement:

-   Structured backend logs.
-   Request correlation IDs.
-   Error tracking.
-   API health endpoint.
-   Database health checks.
-   Notification failure tracking.
-   Incident processing metrics.
-   Application crash reporting, with privacy-conscious configuration.

Do not log raw addresses, invitation tokens, access tokens, or sensitive
network information.

Provide an operational runbook.

Document how to:

-   Disable a neighborhood.
-   Revoke a compromised device.
-   Revoke an invitation.
-   Disable notifications.
-   Investigate false positives.
-   Restore database backups.
-   Rotate credentials.
-   Handle a suspected data breach.

------------------------------------------------------------------------

# 22. DEVELOPMENT EXECUTION PLAN

Do not attempt to build everything in one giant unverified
code-generation step.

Work autonomously in phases, keeping the application buildable
throughout.

## Phase 1: Repository and architecture

-   Initialize project structure.
-   Configure Git.
-   Create architecture documentation.
-   Define API contracts.
-   Define database schema.
-   Create local development environment.
-   Configure CI.

Deliverable: clean repository with successful initial builds and tests.

## Phase 2: Backend foundation

-   Database migrations.
-   Authentication.
-   Neighborhood membership.
-   Invitation flow.
-   Device registration.
-   Event ingestion.
-   Basic API tests.

Deliverable: working backend with local database.

## Phase 3: Android application

-   Registration.
-   Authentication.
-   Household onboarding.
-   Connectivity monitoring.
-   Local event storage.
-   Event reporting.
-   Dashboard.
-   Push notifications.

Deliverable: functional Android application.

## Phase 4: iOS application

-   Registration.
-   Authentication.
-   Household onboarding.
-   NWPathMonitor integration.
-   Monitoring status.
-   Event reporting where permitted.
-   Dashboard.
-   Push notifications.

Deliverable: functional iOS application with documented monitoring
limitations.

## Phase 5: Detection engine

-   Event classification.
-   ISP outage differentiation.
-   Neighborhood correlation.
-   Configurable thresholds.
-   Incident creation.
-   Confidence explanations.

Deliverable: tested detection engine.

## Phase 6: Notifications

-   FCM integration.
-   APNs integration.
-   Incident routing.
-   Alert levels.
-   Duplicate suppression.

Deliverable: end-to-end alerting.

## Phase 7: Security hardening

-   Authorization review.
-   RLS validation.
-   Rate limiting.
-   Privacy review.
-   Threat model.
-   Security testing.

Deliverable: security review report.

## Phase 8: Deployment

-   Staging deployment.
-   Production deployment preparation.
-   Database migrations.
-   Secrets management.
-   Monitoring.
-   Backup procedures.

Deliverable: reproducible deployment instructions and a functioning
staging environment.

## Phase 9: Private beta distribution

-   Android Firebase App Distribution.
-   iOS TestFlight.
-   Tester onboarding.
-   Release documentation.

Deliverable: installable beta packages and documented distribution
process.

------------------------------------------------------------------------

# 23. DEFINITION OF DONE

The project is not complete simply because source files exist.

The following must be true:

1.  Android application compiles.
2.  iOS application compiles.
3.  Backend starts locally.
4.  Database migrations execute successfully.
5.  User registration works.
6.  Invitation verification works.
7.  Household membership is enforced server-side.
8.  Device registration works.
9.  Connectivity events can be recorded.
10. Events can be reported independently of the home Wi-Fi when
    supported.
11. Failed event uploads are queued.
12. Neighborhood correlation works.
13. Incident classification is explainable.
14. Push notification integrations are implemented.
15. Security tests pass.
16. Simulation tests pass.
17. Privacy documentation is complete.
18. Deployment documentation is complete.
19. CI pipelines run successfully.
20. Private beta distribution is documented and ready for account
    configuration.

For anything requiring external accounts or physical devices, clearly
mark the status:

-   IMPLEMENTED
-   AUTOMATED_TESTED
-   MANUAL_TEST_REQUIRED
-   EXTERNAL_CREDENTIAL_REQUIRED
-   EXTERNAL_REVIEW_REQUIRED
-   NOT_IMPLEMENTED

Never mark a feature complete based on source code alone.

------------------------------------------------------------------------

# 24. REQUIRED FINAL DELIVERABLES

At the end of the implementation, produce a concise engineering handoff
containing:

1.  What was built.
2.  Repository structure.
3.  Architecture decisions.
4.  Technology choices.
5.  Database schema.
6.  API documentation.
7.  Security architecture.
8.  Privacy architecture.
9.  Android build instructions.
10. iOS build instructions.
11. Backend deployment instructions.
12. Firebase setup instructions.
13. Apple Developer and TestFlight setup instructions.
14. GitHub Actions configuration.
15. Environment variables required.
16. Automated test results.
17. Outstanding manual tasks.
18. Known limitations.
19. Estimated monthly infrastructure cost.
20. Recommended next steps.

Include exact commands to:

-   Run locally.
-   Run tests.
-   Build Android.
-   Build iOS.
-   Start backend.
-   Apply database migrations.
-   Seed sample households.
-   Deploy staging.
-   Generate beta distribution artifacts.

------------------------------------------------------------------------

# 25. AUTONOMOUS ENGINEERING RULES

These instructions are especially important.

1.  Do not stop after generating architecture documentation.
2.  Do not generate placeholder functions for core functionality.
3.  Do not leave TODOs in critical authentication, monitoring, event
    ingestion, or detection paths.
4.  Do not hardcode production credentials.
5.  Do not fake connectivity measurements.
6.  Do not fake successful notification delivery.
7.  Do not claim jamming detection based solely on a Wi-Fi
    disconnection.
8.  Do not assume identical Android and iOS capabilities.
9.  Do not collect unnecessary personal information.
10. Do not build a complex infrastructure stack prematurely.
11. Do not silently skip tests.
12. Do not claim physical-device testing unless performed.
13. Do not claim deployment without deployment confirmation.
14. Do not stop at the first compilation error.
15. Fix errors systematically and rerun relevant tests.

When a design decision is ambiguous:

-   Evaluate alternatives.
-   Choose the simplest reliable approach.
-   Document the decision.
-   Continue implementation.

When external credentials are unavailable:

-   Implement the integration.
-   Use mock credentials only in local tests.
-   Provide exact configuration instructions.
-   Continue with all other permitted work.

When a platform restriction prevents a requested feature:

-   Implement the best supported alternative.
-   Document the limitation.
-   Do not use prohibited APIs or misleading workarounds.

**Your ultimate objective is a functioning, secure, maintainable
neighborhood monitoring system, not a demonstration application that
merely looks finished.**

Begin by inspecting the repository, creating the implementation plan,
and then proceed through the phases in order. Do not wait for further
instructions unless genuinely blocked.
