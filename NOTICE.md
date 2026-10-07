# HandyTuner — notices and credits

```
HandyTuner
Copyright (C) 2026 ElectricBits

This program is free software; you can redistribute it and/or modify it under the terms of the
GNU General Public License version 2 as published by the Free Software Foundation.

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
General Public License for more details. You should have received a copy of it with this program
(LICENSE); if not, see <https://www.gnu.org/licenses/old-licenses/gpl-2.0.html>.
```

SPDX-License-Identifier: `GPL-2.0-only`. HandyTuner changes performance, fan, display and network settings on
your device; use it at your own risk. "Reset everything to stock" (Diagnostics) puts back what it changed.

The PULSE engine in `pulse/` is copyrighted by keiretrogaming and its contributors, and is used and modified
here under the GPL; see below. Files ElectricBits wrote carry a `Copyright (C) 2026 ElectricBits` header;
PULSE files ElectricBits changed carry a note saying so, and the changes are listed in `pulse/NOTICE.md`.

## The PULSE engine (`pulse/`)

HandyTuner includes the engine of **PULSE** by keiretrogaming (GPL-2.0), which is why HandyTuner is GPL-2.0.
PULSE's own notice, its license and the list of changes made here are in `pulse/NOTICE.md` and `pulse/LICENSE`.
PULSE builds on **ClusterTune** by AurelioB and **O2P Tweaks** by FeralAI (both GPL-2.0).

- PULSE: https://github.com/keiretrogaming/pulse
- ClusterTune: https://github.com/AurelioB/cluster-tune
- O2P Tweaks: https://github.com/FeralAI/o2ptweaks.app

HandyTuner is not the official PULSE and is not endorsed by its authors.

## Bundled assets

| What | License | Text |
|---|---|---|
| Nunito font | SIL Open Font License 1.1 | `licenses/Nunito-OFL.txt` |
| JetBrains Mono font | SIL Open Font License 1.1 | `licenses/JetBrainsMono-OFL.txt` |
| Chakra Petch, IBM Plex Mono fonts (PULSE engine) | SIL Open Font License 1.1 | `pulse/licenses/` |
| Material Symbols icons | Apache License 2.0 | `licenses/MaterialSymbols-Apache-2.0.txt` |
| AndroidX, Jetpack Compose, Kotlin, kotlinx libraries | Apache License 2.0 | shipped inside the APK |
| HandyHelper mascot, logo and app icon | GPL-2.0, as part of HandyTuner | made with AI image tools, finished and upscaled by the author |
| Sounds and music | GPL-2.0, as part of HandyTuner | generated in code (`SoundKit.kt`) |

## Trademarks

AYN, Odin and Odin 2 Portal are trademarks of their owners. HandyTuner is an independent project, not made by,
affiliated with or endorsed by AYN. Names of other apps (Cocoon, GameNative, PULSE) are used only to say what
HandyTuner works with.

## Privacy

HandyTuner has no accounts, ads, analytics or tracking, and sends nothing about you anywhere. It only uses the
network when you ask it to: the Network test (pings, and a download from Cloudflare's public speed test), the
DNS benchmark (lookups to your own network's DNS and a fixed list of well-known public providers), and links you
tap. "Export log file" saves HandyTuner's own log to your Downloads folder; it never leaves the device unless you
share it.

## AI assistance

AI assistance was used to write this app. The author reviews and tests the code on a real device.
