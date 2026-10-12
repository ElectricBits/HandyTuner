## Release discipline
- Do not cut a GitHub release for every change. Batch fixes and ship on a weekly cadence; ship immediately only for crashes or critical bugs.
- Before cutting a release, check the changelog: if there is nothing user-visible to announce, do not release.
- Build/packaging/metadata-only fixes (gradle config, manifests, CI) do not need a new release. If a downstream like F-Droid needs the fix, point their recipe at the fix commit instead of cutting a new version.
- Never move or rewrite a published tag, and never replace an APK on a published release. A published release is immutable — ship a new version instead.
- Every release must have real changelog notes describing user-visible changes.
- Build the release APK from a fresh clone of the release tag (copy `local.properties` in for signing), never from the dev folder: a dev-folder build of 0.11.2-beta didn't match its own tag, so F-Droid couldn't verify it. Before uploading, `tools/verify-reproducible.sh <tag> <apk>` must print REPRODUCIBLE.
