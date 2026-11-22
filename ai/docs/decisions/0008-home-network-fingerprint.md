# 0008 Home-network fingerprint kept on the phone; no location permission

**Problem.** A phone leaving the house loses Wi-Fi exactly like a phone whose router went away.
Reading the Wi-Fi name needs location permission on both platforms, which the brief rules out.

**Decision.** During setup the resident confirms "I am on my home Wi-Fi". The app stores a salted
SHA-256 of the default gateway addresses the operating system exposes without location permission
(including the router's IPv6 link-local address, which is usually unique). On later associations it
compares and sends only `home_network_match` = true, false or null. The fingerprint never leaves
the phone.

The backend ignores losses with `home_network_match = false`, drops a household's onset when
another phone of the same household is healthy at home shortly after, and treats a weak fading
signal on one Android phone as "left the house".

**Limits.** Networks that expose only a common private IPv4 gateway (192.168.1.1) with no IPv6
router address give a weak fingerprint. iPhones give no signal strength, so a single iPhone leaving
home still creates a household-level record (see 0010 for why it is not pushed).

**Rejected.** Location permission for the network name; geofencing; sending any network identifier
to the backend.
