# HandyTuner — notes for AI agents and contributors

## Build and test
- Needs JDK 17 and the Android SDK (`ANDROID_HOME`).
- Tests: `./gradlew :app:testDebugUnitTest`
- Debug APK: `./gradlew :app:assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`
- The PULSE engine is the `pulse/` module (GPL-2.0); the same Gradle build builds and tests it.

## Testing on a device
- Test every changed screen with the controller (D-pad, A, B), not only touch. Touch-only testing once shipped a crash (0.11.3).
- After testing, `adb logcat -b crash` must be empty.
- Never `adb install` while a game or emulator is running, even in the background: the install's memory spike makes Android kill the game.
- An install removes HandyTuner from the enabled accessibility services, so check overlay features only after the last install of a session, and ask the owner to switch it back on.
- Never run `uiautomator dump` on the device: it restarts the accessibility service and can trip HandyTuner's safe mode.
- Don't stop the owner's game, don't reboot the device, and never kill `pservice`.
- Verify on the device before calling anything working; commit only verified work.

## Release discipline
- Do not cut a GitHub release for every change. Batch fixes and ship on a weekly cadence; ship immediately only for crashes or critical bugs.
- Before cutting a release, check the changelog: if there is nothing user-visible to announce, do not release.
- Build/packaging/metadata-only fixes (gradle config, manifests, CI) do not need a new release. If a downstream like F-Droid needs the fix, point their recipe at the fix commit instead of cutting a new version.
- Never move or rewrite a published tag, and never replace an APK on a published release. A published release is immutable — ship a new version instead.
- Every release must have real changelog notes describing user-visible changes.
- Build the release APK from a fresh clone of the release tag (copy `local.properties` in for signing), never from the dev folder: a dev-folder build of 0.11.2-beta didn't match its own tag, so F-Droid couldn't verify it. Before uploading, `tools/verify-reproducible.sh <tag> <apk>` must print REPRODUCIBLE.
