#!/bin/sh
# Regenerates NeighborhoodWatchdog/Info-Debug.plist from Info.plist.
# The only difference: an App Transport Security exception limited to "localhost", so a Debug build
# can talk to a backend on http://localhost:8000. The Release Info.plist has no ATS exceptions.
set -eu
cd "$(dirname "$0")/.."
SRC=NeighborhoodWatchdog/Info.plist
DST=NeighborhoodWatchdog/Info-Debug.plist
cp "$SRC" "$DST"
PB=/usr/libexec/PlistBuddy
"$PB" -c "Add :NSAppTransportSecurity dict" "$DST"
"$PB" -c "Add :NSAppTransportSecurity:NSExceptionDomains dict" "$DST"
"$PB" -c "Add :NSAppTransportSecurity:NSExceptionDomains:localhost dict" "$DST"
"$PB" -c "Add :NSAppTransportSecurity:NSExceptionDomains:localhost:NSExceptionAllowsInsecureHTTPLoads bool true" "$DST"
plutil -lint "$DST"
