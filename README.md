# app-a

Minimal Android app (package com.example.appa) built and shipped from the TrueNAS devbox.

Ship loop: push to master -> .github/workflows/release.yml signs assembleRelease with the
KEYSTORE_* Actions secrets and attaches the APK to a private GitHub Release -> Obtainium
on the phone updates from that release (same signing key every time).

Local: container app-a on devbox (ssh -p 2222 dev@192.168.178.250), SDK at /opt/android-sdk,
AVD ci, emulator headless, adb on PATH via /opt/android-sdk/platform-tools.
