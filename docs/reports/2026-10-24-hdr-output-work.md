# HDR metadata and configurable export work

This working branch is based on main `171f0da0c33bf42ac9bfa014ea70eb121385566d`.
It independently implements the requested features; it does not merge or
cherry-pick contributor PR #1. Device acceptance remains outstanding.

## Behavior

- A JPEG with an unsupported ISO minimum version may use strictly validated
  scalar Adobe gain-map XMP. Supported ISO retains priority; corrupt supported
  ISO data is rejected. Declared but unreadable JPEG HDR no longer becomes SDR.
- JPEG remuxing replaces emitted metadata blocks without re-encoding gain-map
  pixels. HEIF/JXL export regenerates ISO metadata when the input ISO was rejected.
- Settings can select a persisted SAF output folder and retain the source name.
  Each batch snapshots those choices. Automatic original replacement continues
  to use the original album; disabling it restores the stored folder preference.
- Names are normalized, restricted to usable characters and bounded to 255 UTF-8
  bytes, including collision suffixes. Publication creates a new item and accepts
  the provider's returned name instead of repeatedly renaming it.
- Source dates and the URI of a newly created export are stored beside the
  private output. Successful interrupted publications can be recovered by URI and
  full content fingerprint. Failed/cancelled writes attempt to remove only the
  new item; private output remains available for retry.
- File mtime is best effort through an output descriptor and path fallback.
  Storage precision or timestamp restrictions do not reject a verified export.
  Capture time is kept separately; missing source dates are not replaced with
  import wall-clock time.

## Verification at initial Gitea push

- Debug APK and Android test APK built successfully.
- JVM suite: 51 tests, zero failures/errors, one existing skipped test.
- Full host suite: all seven CTest targets passed, including gain-map
  preservation, metadata regression and the new JPEG metadata fallback tests.
- Repository integrity check and six release-identity tests passed.
- The original public issue #2 attachment (SHA-256
  `3f1e942f63799a3045efac8a7afd542c0258634fd63285d6d9cce43cbbcb0747`)
  probes and fully decodes as HDR. JPEG remuxing produces supported ISO metadata,
  retains the 1536 by 2048 gain map pixel-for-pixel, and retains maximum boost
  2.17938328. The resulting JPEG fully decodes as HDR.
- Parser negative cases cover wrong XMP namespace/version, duplicate
  attributes, invalid gamma, trailing numeric junk, overflow and HDR base
  rendition. Valid ISO still wins over conflicting XMP; truncated supported ISO
  fails rather than being concealed by XMP.
- Initial Lint analysis was blocked by the ignored local SDK path configuration;
  that configuration has been corrected and a fresh analysis is being run.

## Remaining validation

No Android device is connected. Instrumented provider tests, actual grant
revocation, UI/adaptive regression, device HDR decoding and real gallery display
have not run. The new provider tests simulate denied access, normalization,
cancellation, collision, recovery and unsupported mtime; compilation alone does
not establish that these runtime tests pass. Full-resolution HEIC/AVIF export of
the reported attachment and final Release checks are also pending.

A provider that refuses deletion can leave a failed external item. A previously
recorded URI whose bytes no longer match is never overwritten or deleted on
retry, because another application may have edited it. This protects existing
content but may leave a partial export after process death. Old tree grants are
retained for older batch retries; grant-quota behavior needs device coverage.

The original public sample is not committed or redistributed with this change.
