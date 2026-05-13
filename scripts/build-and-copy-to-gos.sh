#!/bin/sh

# Use to quickly build and replace release version in the GOS build tree
# Before running, do `ln -s "$HOME/.android/debug.keystore" app/release_key.jks` to use your debug key for
# release signing or create a keystore in `app/release_key.jks`

set -e

if [ -z "${GOS_ROOT:-}" ]; then
  echo "GOS_ROOT must be set before running this script." >&2
  exit 1
fi

SIGNING_STORE_PASSWORD=android \
  SIGNING_KEY_ALIAS=androiddebugkey \
  SIGNING_KEY_PASSWORD=android \
  ./gradlew :app:assembleRelease

cp app/build/outputs/apk/release/Gallery-arm64-v8a-release.apk "$GOS_ROOT/external/Gallery/prebuilt/Gallery-arm64-v8a-release.apk"
cp app/build/outputs/apk/release/Gallery-x86_64-release.apk "$GOS_ROOT/external/Gallery/prebuilt/Gallery-x86_64-release.apk"
