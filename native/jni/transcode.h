#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace ultraslim {

// Diagnostic only: timings are inclusive, so nested stages must not be summed.
// No file names, image metadata or pixels are reported. The callback is invoked
// synchronously on the calling thread and must not throw or re-enter this API.
struct NativeTraceEvent {
  const char* operation;
  const char* stage;
  double durationMs;
  uint64_t bytes;
};
using NativeTraceSink = void (*)(const NativeTraceEvent&, void*) noexcept;
// Applies to the current thread; nullptr removes the observer. Without a sink,
// ULTRASLIM_TRACE=1 (host) / debug.ultraslim.trace=1 (Android) enables diagnostic
// stderr/logcat output. Disabled tracing does not sample clocks per stage.
void setNativeTraceSink(NativeTraceSink sink, void* userData = nullptr);

struct ProbeResult {
  int errorCode = 0;  // 0 = ok, otherwise failed
  std::string error;
  bool hasGainMap = false;
  int container = -1;  // 0 = JPEG, 1 = HEIF, 2 = AVIF, 3 = JXL, -1 = unknown
  int width = 0;
  int height = 0;
  int gainmapWidth = 0;
  int gainmapHeight = 0;
  bool hasExif = false;
  float maxContentBoost = 1.0f;
  float hdrCapacityMax = 1.0f;
};

struct TranscodeOptions {
  int outCodec = 1;  // 0 = JPEG, 1 = HEIF, 2 = AVIF, 3 = JXL
  int quality = 90;
  // Legacy rebuild parameters are only used by host fixture generation.
  bool preserveGainMap = true;
  // Explicit AVIF RGB-map compatibility: full-resolution BT.601 4:4:4, with
  // RGB/YUV rounding. False keeps the original pixel-exact GBR representation.
  bool avifGainmapCompatibility = false;
  // HEIC uses BT.601 4:2:0 for platform HDR decoding. RGB chroma is lossy;
  // mono values use full-range Y with neutral chroma and remain exact.
  bool heicGainmapCompatibility = false;
  // When outCodec == 3 (JXL) and input is JPEG, losslessly transcode JPEG DCT
  // coefficients and store a jbrd reconstruction box (--lossless_jpeg=1 --allow_jpeg_reconstruction=1).
  bool jxlJpegReconstruction = false;
  bool retrySmaller = false; // One base-quality retry, reusing decoded pixels and lossless gain map.
  int gainmapQuality = 60;
  int gainmapScale = 4;
  bool multiChannelGainmap = true;
  bool keepExif = true;
  bool keepLocation = true;
  bool allowSdrTranscode = true;  // re-encode plain SDR images too (no gain map)
  int speedLevel = 1;             // 0 = 画质优先, 1 = 平衡, 2 = 速度优先
  int resizePercent = 100;        // 25..100, output resolution as % of source
  // Optional prefix byte limit on inFd (e.g. still portion of a Motion Photo, excluding trailing MP4).
  int64_t maxInputBytes = 0;
  // Threads for one encoder instance. 0 = half the machine, so two parallel jobs fit.
  std::vector<uint8_t> xmp;  // Optional HEIF motion metadata, authored before video append.
  int codecThreads = 0;
};

struct PreviewBitmapData {
  std::vector<uint8_t> rgba;  // RGBA8888
  int width = 0;
  int height = 0;
  int colorSpace = 0;  // 0 = sRGB, 1 = Display P3, 2 = BT.2020, 3 = linear sRGB
};

struct PreviewGainmapData {
  std::vector<uint8_t> pixels;  // 1B/px when singleChannel == true, else RGBA8888 (4B/px)
  int width = 0;
  int height = 0;
  bool singleChannel = true;
  float minContentBoost[3] = {1.0f, 1.0f, 1.0f};
  float maxContentBoost[3] = {1.0f, 1.0f, 1.0f};
  float gamma[3] = {1.0f, 1.0f, 1.0f};
  float offsetSdr[3] = {0.0f, 0.0f, 0.0f};
  float offsetHdr[3] = {0.0f, 0.0f, 0.0f};
  float hdrCapacityMin = 1.0f;
  float hdrCapacityMax = 1.0f;
  int alternatePrimaries = 0;  // CICP primaries, or zero to use the base gamut.
};

struct TranscodeResult {
  int errorCode = 0;
  std::string error;
  bool inputHadGainMap = false;
  // Output properties from the post-encode verification (or the writer itself on
  // the preserve path), so callers never re-read the output file to learn them.
  bool hasGainMap = false;
  bool hasExif = false;
  float maxContentBoost = 1.0f;
  int width = 0;
  int height = 0;
  int gainmapWidth = 0;
  int gainmapHeight = 0;
  long inputBytes = 0;
  long outputBytes = 0;
  double durationMs = 0.0;
};

ProbeResult probeImage(int fd);
std::vector<uint8_t> readHeifXmp(int fd);
TranscodeResult transcodeImage(int inFd, int outFd, const TranscodeOptions& options);

/**
 * Decodes the primary (SDR) rendition of an image and re-encodes it as a small
 * JPEG, for previews of files the platform decoder may not understand
 * (e.g. ISO 21496-1 gain map HEIC). Returns an empty vector on failure.
 * maxWorkingBytes is a conservative pixel-work admission estimate, not a hard
 * allocator limit for codec internals. Checked before allocating decoded pixels.
 */
std::vector<uint8_t> decodePreviewJpeg(int fd, int maxDim, int quality = 82,
                                     uint64_t maxWorkingBytes = UINT64_MAX);
PreviewBitmapData decodePreviewRgba(int fd, int maxDim,
                                    uint64_t maxWorkingBytes = UINT64_MAX);
bool decodePreviewGainmap(int fd, int maxDim, uint64_t maxWorkingBytes,
                          PreviewGainmapData& out);

/** Debug: decode the primary image with heif_chroma_undefined and return the raw Y plane. */
std::vector<uint8_t> debugDecodePlaneY(int fd, int& w, int& h);

}  // namespace ultraslim
