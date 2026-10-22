# PullNotify

PullNotify is a native Android notification animation prototype built with Kotlin.

## Build APK
Open **Actions → Build PullNotify APK**, run the workflow, then download the `PullNotify-debug-apk` artifact from the successful run.

## Setup
1. Install and open PullNotify.
2. Enable notification access in Android Settings.
3. Allow display over other apps.
4. Select apps and test the character cue.

Android controls the system notification banner; this app displays a separate overlay cue and cannot move the system-owned banner. Notification content is not stored or transmitted by this project.

This CI workflow produces a debug APK for testing, not a production-signed release.