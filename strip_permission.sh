#!/bin/bash
sed \
  -e '/<uses-permission android:name="android.permission.INTERNET"\/>/d' \
  -e '/<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"\/>/d' \
  app/src/main/AndroidManifest.xml > app/src/main/AndroidManifest2.xml
mv app/src/main/AndroidManifest2.xml app/src/main/AndroidManifest.xml
