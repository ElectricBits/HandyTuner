# Contributing to HandyTuner

Thanks for helping! Bug reports, device reports and code are all welcome.

## Reporting a bug or testing a new device

HandyTuner is only tested on the **AYN Odin 2 Portal** so far, so reports from other devices are especially useful.

1. In HandyTuner, open **Diagnostics → Export log file**. It saves `HandyTuner-log-….txt` to your Downloads folder.
   The log holds only HandyTuner's own messages, nothing from your other apps.
2. Open a [bug report](https://github.com/ElectricBits/HandyTuner/issues/new/choose) and attach the log.

If something went wrong on your device, **Diagnostics → Reset everything to stock** puts back what HandyTuner changed.

## Sending code

1. **Fork** the repository and create a branch for your change.
2. Build and run the tests (JDK 17 and the Android SDK are needed):

   ```sh
   ./gradlew testDebugUnitTest assembleDebug
   ```

3. **Test it on a real handheld**, and say in your pull request which device and Android version you used.
4. Open a **pull request** describing what changed and why. Small, focused pull requests are much easier to review.

For anything big, please open an issue first so we can agree on the approach before you put the work in.

### Guidelines

- Match the style of the code around your change: Kotlin, short functions, plain-language comments that explain *why*.
- **Safety first.** HandyTuner changes system settings through the device's own service. Every setting it changes must
  be put back by "Reset everything to stock". Never write values that haven't been measured on real hardware (a
  24 fps frame cap once hung an Odin), and never touch `pservice`.
- Keep the app respectful: no tracking, no ads, and no network use the user didn't ask for.
- Add or update a unit test for any logic you change.
- New files start with this header:

  ```kotlin
  // HandyTuner — Copyright (C) <year> <your name>
  // SPDX-License-Identifier: GPL-2.0-only
  ```

- If you change a file in `pulse/` (the PULSE engine), add a dated line about it to `pulse/NOTICE.md`.

## License of contributions

HandyTuner is licensed under the **GNU General Public License, version 2 only** (`GPL-2.0-only`). By submitting a
contribution, you agree that it is licensed under the same terms. You keep the copyright to your own changes.

Please sign off each commit (`git commit -s`). This adds a `Signed-off-by:` line, which certifies the
[Developer Certificate of Origin](https://developercertificate.org/): that you wrote the change, or otherwise have the
right to submit it under this license. Don't submit code copied from projects whose license isn't compatible with
GPL-2.0.
