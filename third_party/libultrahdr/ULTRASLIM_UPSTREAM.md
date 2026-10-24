# UltraSlim vendored libultrahdr provenance

Verified against upstream on 2026-10-09.

- Official repository: https://github.com/google/libultrahdr
- Base release: v2.0.2
- Tag commit: `e5f5a022fe96fc4dc2ee35c19f733a50df807abe`
- GitHub release: https://github.com/google/libultrahdr/releases/tag/v2.0.2
- Source archive: https://codeload.github.com/google/libultrahdr/tar.gz/refs/tags/v2.0.2
- Observed archive SHA-256: `aa8d193bb887c348c419780511dd03b374f4e07af8812b6d3f80c8537cf1ef2c`

This is a source snapshot inside UltraSlim, not a separate Git checkout or
submodule. Running `git remote -v` here reports the UltraSlim repository remote,
not an alternative libultrahdr upstream. `native/CMakeLists.txt` compiles this
snapshot directly with `add_subdirectory`; it does not fetch a moving upstream.

At audit time, 209 of 214 tracked vendored files matched the release exactly.
The remaining five contain these local changes:

| File | Local changes |
| --- | --- |
| `cmake/Findlibheif.cmake` | Apply `-D` separately to each feature-probe definition. |
| `lib/include/ultrahdr/ultrahdrcommon.h` | Separate HEVC quality remapping and full-range AVIF quality; AV1 must not inherit the HEVC quality cap. |
| `lib/src/heifultrahdr.cpp` | Speed/thread controls, encoder chroma-parameter tolerance, quality remapping. |
| `lib/src/avifultrahdr.cpp` | Speed/thread controls, encoder chroma-parameter tolerance, quality remapping. |
| `lib/src/jpegrutils.cpp` | Initialize Apple gain-map `use_base_cg` to true. |

Additional local fix on 2026-10-14: `lib/src/ultrahdr_api.cpp` checks both the
HEIF gain-map metadata getter and fraction-to-float conversion before copying
metadata. Malformed tmap versions/fractions previously copied uninitialized
boost values. Such inputs retain gain-map detection but now keep zeroed metadata;
valid metadata and the serialized ISO layout are unchanged. Covered by the host
`preview_probe` parity tests for HEIC/AVIF strict and compatibility outputs.

The 209/214 comparison excludes this newly added provenance note. Some upstream
fixtures/docs are present locally but not tracked due to the vendored .gitignore;
these were not counted as matched tracked files. Raw audit details are in
`build/validation-dependencies/libultrahdr/` (ignored).

## Local JPEG metadata compatibility (2026-10-24)

`jpegr.cpp` now permits an unsupported ISO minimum version to fall back to a
strictly validated scalar Adobe XMP packet from the gain-map JPEG. Valid ISO
retains priority; corruption in a supported ISO layout is not ignored. The strict
mode in `jpegrutils.cpp` / `jpegrutils.h` checks namespace, version, duplicate
attributes, complete numeric tokens and finite, ordered gain-map parameters.
JPEG remuxing removes replaced gain-map metadata blocks while retaining coding
segments and scan bytes. This is a local compatibility change, not an upstream
version upgrade. `native/host_test/jpeg_metadata_fallback.cpp` covers selection,
rejection, remuxing and decoded gain-map pixel preservation.

## Release versus main

v2.0.2 was the latest published release at audit time. Upstream main was
`66821e0a261aa3a06c0e7c889f52eced52850be1`, 32 commits ahead. Version 2.0.2 alone
therefore does not imply that all upstream fixes are included. Relevant fixes
absent from this snapshot include:

- `924967a75909cd5433bb6dcc835abab57d785313`: write the actual green component in RGB888 output.
- `2472ff7ad1f204f802495980c61b6a8c12fc1df0`: correct ARM NEON wide-gamut conversion and P3/BT.2020 dispatch.
- `b4880a1c21e7f7e9ed84a82ed78f8e1cd00262c9`: signal sRGB using the sRGB NCLX transfer characteristic, not BT.709.
- `d911e4b6dd873a1bee5e6a782a71d7623e2b5f69`: write the final ISO gain-map metadata layout.
- Additional metadata validation, odd-size conversion and runtime-routing changes.

These need route-specific regression coverage before an upgrade/backport.
In particular, blindly replacing metadata serialization can change compatibility
with existing files; preserve the app's strict/compatible contracts and local
patches during any update.

## Relation to the HEIC color investigation

UltraSlim's default preserve path probes metadata using libultrahdr, decodes JPEG
pixels with libjpeg, then encodes using its own `tryReuseGainMap` /
`encodeSdrWithLibheif` / `attachGainMap` code and patched libheif. This path is not
an unmodified call to upstream `HeifUltraHdr::encodeHeicUltraHdr`.

For the supplied JPEG-to-HEIC sample, this path does not call upstream
`putRgb888Pixel` or `convert_raw_input_to_ycbcr_neon`; its primary NCLX transfer is
already sRGB (13). The observed old-file GBR 4:4:4 behavior and compatible-file
YCbCr 4:2:0 behavior are implemented in UltraSlim. The upstream fixes are real,
but their existence does not establish them as the cause of that sample's hue
problem. The user confirmed the separately named compatible file displays
correctly in iPhone Photos; this confirms that sample, not all possible inputs.

## Selective backports (2026-10-10)

The source changes from `924967a759`, `2472ff7ad1`, and `b4880a1c21` have now been applied to the vendored snapshot, retaining the existing local changes. `native/host_test/upstream_color.cpp` covers distinct RGB channel writes, ARM NEON versus scalar P3/BT.2020/BT.709 conversion, and public HEIC/AVIF encoding with sRGB transfer 13. Existing roundtrip/preservation suites also pass.

This is still v2.0.2 with selective backports, not a full main upgrade. The ISO metadata-layout rewrite was not included. App export still uses the existing preserve/compatible path; it was not switched to HDR-only rebuild.
