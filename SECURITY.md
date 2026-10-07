# Security policy

HandyTuner runs with system-level access on your handheld (through the device's own `PServer` service), so
security problems matter. Thank you for reporting them responsibly.

## Reporting a vulnerability

**Please don't open a public issue for security problems.** Instead, report it privately:

- **Preferred:** [Report a vulnerability](https://github.com/ElectricBits/HandyTuner/security/advisories/new) on GitHub
  (Security → Report a vulnerability). Only the maintainer can see it.
- Or send a private message to **ElectricBits** on the [HandyHelper Realm Discord](https://discord.gg/uGQQ2n36QR).

Please include what's affected, how to reproduce it, the device and Android version, and the HandyTuner version.
You'll get a reply as soon as possible. Once it's fixed, you'll be credited in the release notes unless you'd rather not be.

## Supported versions

Only the latest release gets security fixes. HandyTuner is a beta made by one person who is still learning, so fixes
may take a little time, but every report is taken seriously.

## Scope

In scope: HandyTuner's own code and its built-in PULSE engine — for example ways another app could make HandyTuner run
commands or change settings, or settings HandyTuner fails to put back on Reset. Problems in AYN's system software
itself should be reported to AYN.
