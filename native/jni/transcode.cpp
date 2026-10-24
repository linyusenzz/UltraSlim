#include "transcode.h"
#include "image_metadata.h"

#include <errno.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <csetjmp>
#include <cerrno>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <memory>
#include <limits>
#include <future>
#include <thread>
#include <vector>

#include <jpeglib.h>
#include <jerror.h>
// Load libjpeg's configuration before the helper includes jerror.h, so optional
// warning codes have the same enum values as the linked libjpeg library.
#include "ultrahdr/icc.h"
#include "ultrahdr/jpegdecoderhelper.h"
#include <libheif/heif.h>

#include <jxl/encode.h>
#include <jxl/decode.h>
#include <jxl/color_encoding.h>
#include <jxl/thread_parallel_runner.h>

#include "ultrahdr/gainmapmetadata.h"
#include "ultrahdr/jpegr.h"
#include "ultrahdr/ultrahdrcommon.h"
#include "ultrahdr_api.h"

#if defined(__ARM_NEON)
#include <arm_neon.h>
#endif

// strong definitions; libultrahdr's patched HEIF/AVIF encoders read these via a weak default
thread_local int ultraslim_codec_speed_level = 1;
thread_local int ultraslim_codec_threads = 0;

extern "C" void ultraslim_heif_gainmap_retry_cache(int enabled);

namespace ultraslim {
namespace {

// Hard pixel ceiling shared by every decoder path (base image, gain map, preview,
// SDR/JXL). Oversized input must fail with an error code instead of OOM-killing.
constexpr int64_t kMaxPixels = 100000000;
// Upper bound for a single decoded metadata box (Exif / jhgm).
constexpr uint64_t kMaxBoxBytes = 100ull * 1024 * 1024;

// 只读视图：校验已编码结果时不再整份复制输出缓冲（重建路径的输出可达数百 MB）。
// 各 probe 助手只读取、不保留指针，调用期间底层缓冲必须存活。
struct BytesView {
  const uint8_t* ptr = nullptr;
  size_t len = 0;
  BytesView() = default;
  BytesView(const uint8_t* p, size_t n) : ptr(p), len(n) {}
  BytesView(const std::vector<uint8_t>& v) : ptr(v.data()), len(v.size()) {}  // NOLINT
  const uint8_t* data() const { return ptr; }
  size_t size() const { return len; }
  bool empty() const { return len == 0; }
};

struct GainMapRetryScope {
  explicit GainMapRetryScope(bool enabled) { ultraslim_heif_gainmap_retry_cache(enabled); }
  ~GainMapRetryScope() { ultraslim_heif_gainmap_retry_cache(0); }
};

template<class Encode>
int encodeWithRetry(int fd, long inputBytes, int quality, bool retry, Encode encode) {
  int rc = encode(quality);
  struct stat st {};
  if (rc == 0 && retry && quality > 80 && fstat(fd, &st) == 0 && st.st_size >= inputBytes) {
    if (ftruncate(fd, 0) != 0 || lseek(fd, 0, SEEK_SET) < 0) return 23;
    rc = encode(std::max(80, quality - 5));
  }
  return rc;
}

// Bound pixel preprocessing parallelism; encoders run after these workers finish.
template<class Work>
void parallelRows(int height, size_t pixels, Work work) {
  const int count = pixels < 1024 * 1024 ? 1 : std::min(4, std::max(1, ultraslim_codec_threads));
  std::vector<std::future<void>> tasks;
  for (int i = 1; i < count; ++i) {
    const int begin = height * i / count, end = height * (i + 1) / count;
    tasks.emplace_back(std::async(std::launch::async, [=, &work] { work(begin, end); }));
  }
  work(0, height / count);
  for (auto& task : tasks) task.get();
}

void storeBE16(uint16_t v, uint8_t* p) {
  p[0] = static_cast<uint8_t>(v >> 8);
  p[1] = static_cast<uint8_t>(v & 0xff);
}
void storeBE32(uint32_t v, uint8_t* p) {
  p[0] = static_cast<uint8_t>(v >> 24);
  p[1] = static_cast<uint8_t>((v >> 16) & 0xff);
  p[2] = static_cast<uint8_t>((v >> 8) & 0xff);
  p[3] = static_cast<uint8_t>(v & 0xff);
}
uint16_t loadBE16(const uint8_t* p) {
  return static_cast<uint16_t>((static_cast<uint16_t>(p[0]) << 8) | p[1]);
}
uint32_t loadBE32(const uint8_t* p) {
  return (static_cast<uint32_t>(p[0]) << 24) |
         (static_cast<uint32_t>(p[1]) << 16) |
         (static_cast<uint32_t>(p[2]) << 8) |
         static_cast<uint32_t>(p[3]);
}

struct JhgmLayout {
  BytesView iso;
  BytesView codestream;
};

bool parseJhgmLayout(BytesView box, JhgmLayout& out) {
  out = {};
  if (box.size() < 3 || box.data()[0] != 0) return false;
  size_t cursor = 3;
  const size_t isoSize = loadBE16(box.data() + 1);
  if (isoSize > box.size() - cursor) return false;
  out.iso = BytesView(box.data() + cursor, isoSize);
  cursor += isoSize;
  if (cursor == box.size()) return false;
  const size_t colorEncodingSize = box.data()[cursor++];
  if (colorEncodingSize > box.size() - cursor) return false;
  cursor += colorEncodingSize;
  if (box.size() - cursor < 4) return false;
  const size_t altIccSize = loadBE32(box.data() + cursor);
  cursor += 4;
  if (altIccSize > box.size() - cursor) return false;
  cursor += altIccSize;
  if (cursor == box.size()) return false;
  out.codestream = BytesView(box.data() + cursor, box.size() - cursor);
  return true;
}

void rgbaToRgbFast(const uint8_t* src, uint8_t* dst, size_t count) {
  size_t i = 0;
#if defined(__ARM_NEON)
  for (; i + 16 <= count; i += 16) {
    uint8x16x4_t rgba = vld4q_u8(src + i * 4);
    uint8x16x3_t rgb = { rgba.val[0], rgba.val[1], rgba.val[2] };
    vst3q_u8(dst + i * 3, rgb);
  }
#endif
  for (; i < count; ++i) {
    dst[i * 3 + 0] = src[i * 4 + 0];
    dst[i * 3 + 1] = src[i * 4 + 1];
    dst[i * 3 + 2] = src[i * 4 + 2];
  }
}

void rgbaToGrayFast(const uint8_t* src, uint8_t* dst, size_t count) {
  size_t i = 0;
#if defined(__ARM_NEON)
  for (; i + 16 <= count; i += 16) {
    uint8x16x4_t rgba = vld4q_u8(src + i * 4);
    vst1q_u8(dst + i, rgba.val[0]);
  }
#endif
  for (; i < count; ++i) {
    dst[i] = src[i * 4];
  }
}

// A reader owns only its cursor/cache; pread never changes the caller's fd position.
// Keep userdata alive until every context/image handle that references it is released.
struct HeifFdReader {
  int fd;
  int64_t size = 0;
  int64_t position = 0;
  int64_t cacheStart = 0;
  size_t cacheSize = 0;
  size_t bytesRead = 0;
  uint8_t cache[16384];

  explicit HeifFdReader(int input) : fd(input) {}
  bool open() {
    struct stat st {};
    if (fstat(fd, &st) != 0 || !S_ISREG(st.st_mode) || st.st_size <= 0 ||
        st.st_size > 256LL * 1024 * 1024) return false;
    size = st.st_size;
    return true;
  }
  int read(void* output, size_t count) {
    if (position < 0 || position > size || count > uint64_t(size - position)) return 1;
    auto* dst = static_cast<uint8_t*>(output);
    while (count) {
      if (position < cacheStart || uint64_t(position - cacheStart) >= cacheSize) {
        cacheStart = position;
        cacheSize = 0;
        const size_t wanted = std::min<size_t>(sizeof(cache), size - position);
        while (cacheSize < wanted) {
          ssize_t got;
          do { got = pread(fd, cache + cacheSize, wanted - cacheSize, cacheStart + cacheSize); }
          while (got < 0 && errno == EINTR);
          if (got <= 0) return 1;
          cacheSize += size_t(got);
          bytesRead += size_t(got);
        }
      }
      const size_t offset = size_t(position - cacheStart);
      const size_t copied = std::min(count, cacheSize - offset);
      memcpy(dst, cache + offset, copied);
      position += copied; dst += copied; count -= copied;
    }
    return 0;
  }
  static const heif_reader& callbacks() {
    static const heif_reader reader = [] {
      heif_reader r {};
      r.reader_api_version = 2;
      r.get_position = [](void* p) { return static_cast<HeifFdReader*>(p)->position; };
      r.read = [](void* data, size_t n, void* p) { return static_cast<HeifFdReader*>(p)->read(data, n); };
      r.seek = [](int64_t offset, void* p) {
        auto& s = *static_cast<HeifFdReader*>(p);
        if (offset < 0 || offset > s.size) return 1;
        s.position = offset; return 0;
      };
      r.wait_for_file_size = [](int64_t target, void* p) {
        return target >= 0 && target <= static_cast<HeifFdReader*>(p)->size
            ? heif_reader_grow_status_size_reached : heif_reader_grow_status_size_beyond_eof;
      };
      r.request_range = [](uint64_t start, uint64_t end, void* p) {
        heif_reader_range_request_result result {};
        const auto size = uint64_t(static_cast<HeifFdReader*>(p)->size);
        result.status = start <= end && end <= size ? heif_reader_grow_status_size_reached
                                                    : heif_reader_grow_status_size_beyond_eof;
        result.range_end = size;
        return result;
      };
      // This libheif revision calls the v2 hints unconditionally.
      r.preload_range_hint = [](uint64_t, uint64_t, void*) {};
      r.release_file_range = [](uint64_t, uint64_t, void*) {};
      return r;
    }();
    return reader;
  }
  int container() {
    uint8_t header[64] {};
    const size_t length = std::min<int64_t>(sizeof(header), size);
    position = 0;
    if (read(header, length) != 0) return -1;
    position = 0;
    // Match sniffContainer's JPEG priority even for conflicting/corrupt headers.
    if (length >= 2 && header[0] == 0xff && header[1] == 0xd8) return -1;
    if (length < 12 || memcmp(header + 4, "ftyp", 4) != 0) return -1;
    for (size_t i = 8; i + 4 <= length; i += 4)
      if (!memcmp(header + i, "avif", 4) || !memcmp(header + i, "avis", 4)) return 2;
    return 1;
  }
};

// Admission estimate, not a hard cap on codec-private allocations. Include decoded
// planes/conversion scratch, final preview/JPEG copies and a fixed codec allowance.
// Dimensions are validated before multiplication and before pixel allocation.
bool previewFits(int w, int h, int maxDim, uint64_t budget, uint64_t inputBytes = 0) {
  if (w <= 0 || h <= 0 || int64_t(w) * h > kMaxPixels) return false;
  const int edge = std::max(w, h);
  const int64_t nw = edge > maxDim ? std::max<int64_t>(1, int64_t(w) * maxDim / edge) : w;
  const int64_t nh = edge > maxDim ? std::max<int64_t>(1, int64_t(h) * maxDim / edge) : h;
  const uint64_t estimated = uint64_t(w) * h * 20 + uint64_t(nw) * nh * 16 +
                             inputBytes + 16u * 1024 * 1024;
  return estimated <= budget;
}

std::vector<uint8_t> readAll(int fd, uint64_t maxBytes = 256u * 1024 * 1024) {
  maxBytes = std::min<uint64_t>(maxBytes, 256u * 1024 * 1024);
  std::vector<uint8_t> out;
  struct stat st {};
  lseek(fd, 0, SEEK_SET);
  if (fstat(fd, &st) == 0 && S_ISREG(st.st_mode) && st.st_size > 0) {
    if (uint64_t(st.st_size) > maxBytes) return {};
    out.resize(static_cast<size_t>(st.st_size));
    size_t total = 0;
    while (total < out.size()) {
      ssize_t n;
      // EINTR 重试：进程收到 SIGQUIT（如 ANR dump）等信号会打断一次 read，误判为读取失败。
      do { n = read(fd, out.data() + total, out.size() - total); } while (n < 0 && errno == EINTR);
      if (n <= 0) break;
      total += static_cast<size_t>(n);
    }
    out.resize(total);
    return out;
  }
  uint8_t chunk[1 << 16];
  while (true) {
    ssize_t n;
    do { n = read(fd, chunk, sizeof(chunk)); } while (n < 0 && errno == EINTR);
    if (n <= 0) break;
    if (out.size() + uint64_t(n) > maxBytes) return {};
    out.insert(out.end(), chunk, chunk + n);
  }
  return out;
}

bool writeAll(int fd, const void* data, size_t size) {
  const uint8_t* p = static_cast<const uint8_t*>(data);
  size_t left = size;
  while (left > 0) {
    ssize_t n;
    do { n = write(fd, p, left); } while (n < 0 && errno == EINTR);
    if (n <= 0) return false;
    p += n;
    left -= static_cast<size_t>(n);
  }
  return true;
}

int sniffContainer(const uint8_t* d, size_t n) {
  if (n >= 2 && d[0] == 0xFF && d[1] == 0xD8) return 0;  // JPEG
  if (n >= 12 && memcmp(d + 4, "ftyp", 4) == 0) {
    for (size_t i = 8; i + 4 <= n && i < 64; i += 4) {
      if (memcmp(d + i, "avif", 4) == 0 || memcmp(d + i, "avis", 4) == 0) return 2;
    }
    return 1;  // some HEIF flavour
  }
  const JxlSignature sig = JxlSignatureCheck(d, n);
  if (sig == JXL_SIG_CODESTREAM || sig == JXL_SIG_CONTAINER) return 3;  // JXL
  return -1;
}

std::string describe(const uhdr_error_info_t& e, const char* what) {
  std::string s(what);
  if (e.has_detail) {
    s += ": ";
    s += e.detail;
  }
  return s;
}

struct DecoderDeleter {
  void operator()(uhdr_codec_private_t* p) const { uhdr_release_decoder(p); }
};
struct EncoderDeleter {
  void operator()(uhdr_codec_private_t* p) const { uhdr_release_encoder(p); }
};
using DecoderPtr = std::unique_ptr<uhdr_codec_private_t, DecoderDeleter>;
using EncoderPtr = std::unique_ptr<uhdr_codec_private_t, EncoderDeleter>;

struct HeifImageDeleter {
  void operator()(heif_image* p) const { heif_image_release(p); }
};
using HeifImagePtr = std::unique_ptr<heif_image, HeifImageDeleter>;

struct NclxDeleter {
  void operator()(heif_color_profile_nclx* p) const { heif_nclx_color_profile_free(p); }
};
using NclxPtr = std::unique_ptr<heif_color_profile_nclx, NclxDeleter>;

// ---------------------------------------------------------------- SDR helpers

struct SdrImage {
  std::vector<uint8_t> rgba;  // RGBA8888
  int width = 0;
  int height = 0;
  std::vector<uint8_t> icc;
  heif_color_profile_nclx profile {};
  bool hasProfile = false;
  int colorChannels = 3;
  bool hasAlpha = false;
  std::vector<uint8_t> exif;  // JPEG APP1 payload starting with "Exif\0\0" (may be empty)
};

void setUnsupportedAlphaError(TranscodeResult& r) {
  r.errorCode = 33;
  r.error = "暂不支持转码含 Alpha 通道的图片，已保留原图";
}

void orientPixels(SdrImage& img, int orientation) {
  if (orientation <= 1 || orientation > 8 || img.width <= 0 || img.height <= 0 ||
      img.rgba.size() < static_cast<size_t>(img.width) * img.height * 4) return;
  const int w = img.width, h = img.height;
  const int nw = orientation >= 5 ? h : w;
  const int nh = orientation >= 5 ? w : h;
  auto* pix = reinterpret_cast<uint32_t*>(img.rgba.data());

  // 翻转类（2/3/4）不改变尺寸，原地交换即可，省掉整幅副本。
  if (orientation == 2 || orientation == 3 || orientation == 4) {
    switch (orientation) {
      case 2:
        for (int y = 0; y < h; ++y) {
          uint32_t* row = pix + static_cast<size_t>(y) * w;
          for (int x = 0; x < w / 2; ++x) std::swap(row[x], row[w - 1 - x]);
        }
        break;
      case 3:
        // 上下两行整行对调；奇数高度时中行不参与对调，单独反转。
        for (int y = 0; y < h / 2; ++y) {
          uint32_t* top = pix + static_cast<size_t>(y) * w;
          uint32_t* bottom = pix + static_cast<size_t>(h - 1 - y) * w;
          for (int x = 0; x < w; ++x) std::swap(top[x], bottom[w - 1 - x]);
        }
        if (h & 1) {
          uint32_t* middle = pix + static_cast<size_t>(h / 2) * w;
          for (int x = 0; x < w / 2; ++x) std::swap(middle[x], middle[w - 1 - x]);
        }
        break;
      default:  // 4: flip vertical
        for (int y = 0; y < h / 2; ++y) {
          uint32_t* top = pix + static_cast<size_t>(y) * w;
          uint32_t* bottom = pix + static_cast<size_t>(h - 1 - y) * w;
          for (int x = 0; x < w; ++x) std::swap(top[x], bottom[x]);
        }
        break;
    }
    img.width = nw;
    img.height = nh;
    return;
  }

  // 转置类（5-8）必须落到新缓冲；按 16x16 分块，读写都落在 cache 里，
  // 逐点跨行写整幅（w*h 随机跨度）会持续 cache miss。
  std::vector<uint8_t> pixels(img.rgba.size());
  auto* dst = reinterpret_cast<uint32_t*>(pixels.data());
  constexpr int kBlock = 16;
  for (int by = 0; by < h; by += kBlock) {
    const int yEnd = std::min(by + kBlock, h);
    for (int bx = 0; bx < w; bx += kBlock) {
      const int xEnd = std::min(bx + kBlock, w);
      for (int y = by; y < yEnd; ++y) {
        const uint32_t* sRow = pix + static_cast<size_t>(y) * w;
        for (int x = bx; x < xEnd; ++x) {
          // 输出尺寸 nh 行 × nw 列（nw == h），dx 落在 [0,nh)、dy 落在 [0,nw)。
          const int dx = (orientation <= 6) ? x : w - 1 - x;
          const int dy = (orientation == 5 || orientation == 8) ? y : h - 1 - y;
          dst[static_cast<size_t>(dx) * nw + dy] = sRow[x];
        }
      }
    }
  }
  img.rgba = std::move(pixels);
  img.width = nw;
  img.height = nh;
}

struct JpegErrMgr {
  jpeg_error_mgr pub;
  jmp_buf jump;
  char message[JMSG_LENGTH_MAX];
  bool damagedScan = false;
  void (*defaultEmitMessage)(j_common_ptr, int) = nullptr;
};

void jpegErrExit(j_common_ptr cinfo) {
  auto* err = reinterpret_cast<JpegErrMgr*>(cinfo->err);
  (*cinfo->err->format_message)(cinfo, err->message);
  longjmp(err->jump, 1);
}

void jpegDecodeMessage(j_common_ptr cinfo, int level) {
  auto* err = reinterpret_cast<JpegErrMgr*>(cinfo->err);
  // jpeg_mem_src synthesizes EOI and the entropy decoder can fill all remaining
  // rows. A complete output_scanline count therefore does not prove intact input.
  if (level < 0 && (err->pub.msg_code == JWRN_JPEG_EOF ||
                    err->pub.msg_code == JWRN_HIT_MARKER ||
                    err->pub.msg_code == JWRN_HUFF_BAD_CODE ||
#if JPEG_LIB_VERSION >= 70 || defined(C_ARITH_CODING_SUPPORTED) || defined(D_ARITH_CODING_SUPPORTED)
                    err->pub.msg_code == JWRN_ARITH_BAD_CODE ||
#endif
                    err->pub.msg_code == JWRN_BOGUS_PROGRESSION)) {
    err->damagedScan = true;
  }
  if (err->defaultEmitMessage) err->defaultEmitMessage(cinfo, level);
}

int jpegChannels(const std::vector<uint8_t>& data) {
  jpeg_decompress_struct c {}; JpegErrMgr err {}; c.err=jpeg_std_error(&err.pub);err.pub.error_exit=jpegErrExit;
  if(setjmp(err.jump)){jpeg_destroy_decompress(&c);return 0;}
  jpeg_create_decompress(&c);jpeg_mem_src(&c,data.data(),data.size());jpeg_read_header(&c,TRUE);
  int channels=c.num_components;jpeg_destroy_decompress(&c);return channels;
}

// libjpeg-turbo only scales by 1/2, 1/4, 1/8. Pick the largest shrink that still
// covers targetLongEdge; the caller fine-scales the rest. 0 keeps full resolution.
void applyJpegDecodeScale(jpeg_decompress_struct& cinfo, int targetLongEdge) {
  if (targetLongEdge <= 0) return;
  const int longEdge = std::max(static_cast<int>(cinfo.image_width), static_cast<int>(cinfo.image_height));
  int denom = 1;
  while (denom < 8 && longEdge / (denom * 2) >= targetLongEdge) denom *= 2;
  cinfo.scale_num = 1;
  cinfo.scale_denom = denom;
}

bool decodeJpegSdr(const std::vector<uint8_t>& data, SdrImage& out, std::string& err,
                   int targetLongEdge = 0, uint64_t budget = UINT64_MAX) {
  JpegErrMgr jerr {};
  jerr.message[0] = '\0';
  jpeg_decompress_struct cinfo {};
  cinfo.err = jpeg_std_error(&jerr.pub);
  jerr.pub.error_exit = jpegErrExit;
  jerr.defaultEmitMessage = jerr.pub.emit_message;
  jerr.pub.emit_message = jpegDecodeMessage;
  bool created = false;
  if (setjmp(jerr.jump)) {
    if (created) jpeg_destroy_decompress(&cinfo);
    err = jerr.message[0] ? jerr.message : "JPEG 解码失败";
    return false;
  }
  jpeg_create_decompress(&cinfo);
  created = true;
  jpeg_mem_src(&cinfo, data.data(), static_cast<unsigned long>(data.size()));
  jpeg_save_markers(&cinfo, JPEG_APP0 + 1, 0xFFFF);
  jpeg_save_markers(&cinfo, JPEG_APP0 + 2, 0xFFFF);
  if (jpeg_read_header(&cinfo, TRUE) != JPEG_HEADER_OK) {
    jpeg_destroy_decompress(&cinfo);
    err = "无法解析 JPEG";
    return false;
  }
  unsigned char* profile = nullptr; unsigned int profileSize = 0;
  if(jpeg_read_icc_profile(&cinfo, &profile, &profileSize)) { out.icc.assign(profile,profile+profileSize); free(profile); }
  applyJpegDecodeScale(cinfo, targetLongEdge);
  cinfo.out_color_space = JCS_EXT_RGBA;
  jpeg_calc_output_dimensions(&cinfo);
  if (!previewFits(cinfo.output_width, cinfo.output_height,
                   targetLongEdge > 0 ? targetLongEdge : std::max(cinfo.output_width, cinfo.output_height),
                   budget, data.size())) {
    jpeg_destroy_decompress(&cinfo);
    err = "图片尺寸或预览内存超出限制";
    return false;
  }
  jpeg_start_decompress(&cinfo);
  out.width = static_cast<int>(cinfo.output_width);
  out.height = static_cast<int>(cinfo.output_height);
  const size_t stride = static_cast<size_t>(out.width) * 4;
  if (out.width <= 0 || out.height <= 0 || int64_t(out.width)*out.height > kMaxPixels) { jpeg_destroy_decompress(&cinfo); err="图片尺寸超出限制"; return false; }
  out.rgba.resize(stride * static_cast<size_t>(out.height));
  while (cinfo.output_scanline < cinfo.output_height) {
    JSAMPROW row = out.rgba.data() + static_cast<size_t>(cinfo.output_scanline) * stride;
    if (jpeg_read_scanlines(&cinfo, &row, 1) != 1) break;
  }
  if (cinfo.output_scanline < cinfo.output_height) {
    // 截断/损坏的码流：剩余行保持零值会产出灰底图，按失败处理。
    jpeg_destroy_decompress(&cinfo);
    err = "JPEG 数据不完整";
    return false;
  }
  for (jpeg_saved_marker_ptr m = cinfo.marker_list; m != nullptr; m = m->next) {
    if (m->marker == JPEG_APP0 + 1 && m->data_length >= 6 &&
        memcmp(m->data, "Exif\0\0", 6) == 0) {
      out.exif.assign(m->data, m->data + m->data_length);
      break;
    }
  }
  jpeg_finish_decompress(&cinfo);
  jpeg_destroy_decompress(&cinfo);
  if (jerr.damagedScan) {
    out.rgba.clear();
    err = "JPEG 数据不完整或扫描数据损坏";
    return false;
  }
  return out.width > 0 && out.height > 0;
}

bool probeJpegDims(BytesView data, int& w, int& h, bool& hasExif) {
  JpegErrMgr jerr {};
  jerr.message[0] = '\0';
  jpeg_decompress_struct cinfo {};
  cinfo.err = jpeg_std_error(&jerr.pub);
  jerr.pub.error_exit = jpegErrExit;
  bool created = false;
  if (setjmp(jerr.jump)) {
    if (created) jpeg_destroy_decompress(&cinfo);
    return false;
  }
  jpeg_create_decompress(&cinfo);
  created = true;
  jpeg_mem_src(&cinfo, data.data(), static_cast<unsigned long>(data.size()));
  jpeg_save_markers(&cinfo, JPEG_APP0 + 1, 0xFFFF);
  jpeg_save_markers(&cinfo, JPEG_APP0 + 2, 0xFFFF);
  const int rc = jpeg_read_header(&cinfo, TRUE);
  if (rc == JPEG_HEADER_OK) {
    w = static_cast<int>(cinfo.image_width);
    h = static_cast<int>(cinfo.image_height);
    for (jpeg_saved_marker_ptr m = cinfo.marker_list; m != nullptr; m = m->next) {
      if (m->marker == JPEG_APP0 + 1 && m->data_length >= 6 &&
          memcmp(m->data, "Exif\0\0", 6) == 0) {
        hasExif = true;
        break;
      }
    }
  }
  jpeg_destroy_decompress(&cinfo);
  return rc == JPEG_HEADER_OK;
}

// HEIF stores Exif as a 4-byte big-endian offset followed by a TIFF header.
// Canonical form in this file is the JPEG APP1 payload ("Exif\0\0" + TIFF).
bool readHeifExif(heif_image_handle* handle, std::vector<uint8_t>& out) {
  heif_item_id id = 0;
  if (heif_image_handle_get_list_of_metadata_block_IDs(handle, "Exif", &id, 1) <= 0) return false;
  const size_t size = heif_image_handle_get_metadata_size(handle, id);
  if (size <= 4) return false;
  std::vector<uint8_t> raw(size);
  if (heif_image_handle_get_metadata(handle, id, raw.data()).code != heif_error_Ok) return false;
  const uint32_t offset = (static_cast<uint32_t>(raw[0]) << 24) | (static_cast<uint32_t>(raw[1]) << 16) |
                          (static_cast<uint32_t>(raw[2]) << 8) | raw[3];
  const size_t tiff = 4u + offset;
  if (tiff >= raw.size()) return false;
  out.clear();
  out.insert(out.end(), {'E', 'x', 'i', 'f', 0, 0});
  out.insert(out.end(), raw.begin() + static_cast<std::ptrdiff_t>(tiff), raw.end());
  return true;
}

// TIFF bytes for heif_context_add_exif_metadata (no "Exif\0\0", no 4-byte offset).
const uint8_t* exifTiff(const std::vector<uint8_t>& app1, size_t& size) {
  if (app1.size() >= 6 && memcmp(app1.data(), "Exif\0\0", 6) == 0) {
    size = app1.size() - 6;
    return app1.data() + 6;
  }
  size = app1.size();
  return app1.data();
}

std::vector<uint8_t> resizeRgbaPlane(const uint8_t* src, int sw, int sh, size_t stride,
                                     int newW, int newH);
void scaledSize(int sw, int sh, int percent, int& w, int& h);

bool decodeHeifHandle(heif_image_handle* handle, SdrImage& out, std::string& err, bool wantExif,
                      int previewLimit = 0, uint64_t budget = UINT64_MAX,
                      int targetWidth = 0, int targetHeight = 0) {
  out.hasAlpha = heif_image_handle_has_alpha_channel(handle) != 0;
  const int width = heif_image_handle_get_width(handle), height = heif_image_handle_get_height(handle);
  const int rawWidth = heif_image_handle_get_ispe_width(handle), rawHeight = heif_image_handle_get_ispe_height(handle);
  const int limit = previewLimit > 0 ? previewLimit : std::max(width, height);
  if (!previewFits(width, height, limit, budget) ||
      (rawWidth > 0 && rawHeight > 0 && !previewFits(rawWidth, rawHeight, limit, budget))) {
    err = "HEIF 图片尺寸或预览内存超出限制";
    return false;
  }
  // Set this only after selecting the thumbnail. Parsing with this limit would
  // reject a safe thumbnail solely because an unrelated primary item is huge.
  auto* context = heif_image_handle_get_context(handle);
  if (context) {
    heif_context_get_security_limits(context)->max_image_size_pixels = kMaxPixels;
    heif_context_free(context);
  }
  heif_image* himg = nullptr;
  heif_error e =
      heif_decode_image(handle, &himg, heif_colorspace_RGB, heif_chroma_interleaved_RGBA, nullptr);
  if (e.code != heif_error_Ok) {
    err = e.message ? e.message : "HEIF 解码失败";
    return false;
  }
  out.width = heif_image_get_width(himg, heif_channel_interleaved);
  out.height = heif_image_get_height(himg, heif_channel_interleaved);
  int srcStride = 0;
  const uint8_t* plane = heif_image_get_plane_readonly(himg, heif_channel_interleaved, &srcStride);
  if (plane == nullptr || !previewFits(out.width, out.height, limit, budget) ||
      srcStride <= 0 || uint64_t(srcStride) < uint64_t(out.width) * 4) {
    heif_image_release(himg);
    err = "HEIF 解码结果为空或超出限制";
    return false;
  }
  // Keep libheif's RGBA plane alive only while copying/resizing into the final
  // preview. No full-sized SdrImage copy is needed before downsampling.
  const int edge = std::max(out.width, out.height);
  const bool resizeToTarget = targetWidth > 0 && targetHeight > 0 &&
      (targetWidth < out.width || targetHeight < out.height);
  // Conversion targets come from scaledSize, including its even-dimension rounding.
  // Copy/resize from libheif's plane directly instead of retaining another full RGBA.
  const int nw = resizeToTarget ? targetWidth :
      previewLimit > 0 && edge > limit ? std::max<int64_t>(1, int64_t(out.width) * limit / edge) : out.width;
  const int nh = resizeToTarget ? targetHeight :
      previewLimit > 0 && edge > limit ? std::max<int64_t>(1, int64_t(out.height) * limit / edge) : out.height;
  std::unique_ptr<heif_image, decltype(&heif_image_release)> decoded(himg, heif_image_release);
  out.rgba = resizeRgbaPlane(plane, out.width, out.height, size_t(srcStride), nw, nh);
  out.width = nw; out.height = nh;
  decoded.reset();
  if (wantExif) readHeifExif(handle, out.exif);
  size_t iccSize=heif_image_handle_get_raw_color_profile_size(handle);
  if(iccSize>0 && iccSize<16*1024*1024) {out.icc.resize(iccSize); if(heif_image_handle_get_raw_color_profile(handle,out.icc.data()).code!=heif_error_Ok) out.icc.clear();}
  heif_color_profile_nclx* n=nullptr;
  if(heif_image_handle_get_nclx_color_profile(handle,&n).code==heif_error_Ok && n){out.profile=*n;out.hasProfile=true;heif_nclx_color_profile_free(n);}
  return true;
}

// Reuse native mono/identity-GBR gain map planes without another codec decode.
// Other matrices retain libheif's established color-conversion path.
bool copyNativeGainMap(heif_image* image, SdrImage& out) {
  const bool mono = heif_image_get_chroma_format(image) == heif_chroma_monochrome;
  heif_color_profile_nclx* profile = nullptr;
  heif_image_get_nclx_color_profile(image, &profile);
  const bool fullRange = profile && profile->full_range_flag;
  const bool identity = profile && profile->matrix_coefficients == heif_matrix_coefficients_RGB_GBR && profile->full_range_flag;
  if (profile) heif_nclx_color_profile_free(profile);
  if (mono && !fullRange) return false;
  if (!mono && !(identity && heif_image_get_chroma_format(image) == heif_chroma_444)) return false;
  out.width = heif_image_get_primary_width(image);
  out.height = heif_image_get_primary_height(image);
  if (out.width <= 0 || out.height <= 0 || int64_t(out.width) * out.height > kMaxPixels) return false;
  int ys = 0, us = 0, vs = 0;
  const auto* y = heif_image_get_plane_readonly(image, heif_channel_Y, &ys);
  const auto* u = mono ? y : heif_image_get_plane_readonly(image, heif_channel_Cb, &us);
  const auto* v = mono ? y : heif_image_get_plane_readonly(image, heif_channel_Cr, &vs);
  if (!y || !u || !v) return false;
  if (mono) us = vs = ys;
  out.rgba.resize(size_t(out.width) * out.height * 4);
  parallelRows(out.height, size_t(out.width) * out.height, [&](int begin, int end) {
    for (int row = begin; row < end; ++row) {
      auto* dst = out.rgba.data() + size_t(row) * out.width * 4;
      for (int x = 0; x < out.width; ++x) {
        dst[4*x] = v[row*vs+x]; dst[4*x+1] = y[row*ys+x];
        dst[4*x+2] = u[row*us+x]; dst[4*x+3] = 255;
      }
    }
  });
  return true;
}

bool probeHeifDims(BytesView data, int& w, int& h, bool& hasExif) {
  heif_context* ctx = heif_context_alloc();
  heif_error e = heif_context_read_from_memory_without_copy(ctx, data.data(), data.size(), nullptr);
  if (e.code != heif_error_Ok) {
    heif_context_free(ctx);
    return false;
  }
  heif_image_handle* handle = nullptr;
  e = heif_context_get_primary_image_handle(ctx, &handle);
  if (e.code == heif_error_Ok && handle != nullptr) {
    w = heif_image_handle_get_width(handle);
    h = heif_image_handle_get_height(handle);
    heif_item_id ids[4];
    hasExif = heif_image_handle_get_list_of_metadata_block_IDs(handle, "Exif", ids, 4) > 0;
  }
  const bool ok = e.code == heif_error_Ok && handle != nullptr;
  if (handle) heif_image_handle_release(handle);
  heif_context_free(ctx);
  return ok;
}

bool decodeHeifSdr(const std::vector<uint8_t>& data, SdrImage& out, std::string& err,
                   int resizePercent = 100) {
  heif_context* ctx = heif_context_alloc();
  heif_error e = heif_context_read_from_memory_without_copy(ctx, data.data(), data.size(), nullptr);
  if (e.code != heif_error_Ok) {
    err = e.message ? e.message : "无法解析 HEIF";
    heif_context_free(ctx);
    return false;
  }
  heif_image_handle* handle = nullptr;
  e = heif_context_get_primary_image_handle(ctx, &handle);
  if (e.code != heif_error_Ok || handle == nullptr) {
    err = e.message ? e.message : "没有主图像";
    heif_context_free(ctx);
    return false;
  }
  int targetWidth = 0, targetHeight = 0;
  if (resizePercent < 100) scaledSize(heif_image_handle_get_width(handle),
      heif_image_handle_get_height(handle), resizePercent, targetWidth, targetHeight);
  const bool ok = decodeHeifHandle(handle, out, err, true, 0, UINT64_MAX, targetWidth, targetHeight);
  heif_image_handle_release(handle);
  heif_context_free(ctx);
  return ok;
}

// Prefer an embedded thumbnail when it already covers the requested long edge.
bool decodeHeifPreview(const std::vector<uint8_t>& data, SdrImage& out, std::string& err, int maxDim,
                       uint64_t budget, HeifFdReader* reader = nullptr, bool* parseFailed = nullptr) {
  if (parseFailed) *parseFailed = false;
  std::unique_ptr<heif_context, decltype(&heif_context_free)> ownedContext(heif_context_alloc(), heif_context_free);
  heif_context* ctx = ownedContext.get();
  if (!ctx) return false;
  if (reader) reader->position = 0;
  heif_error e = reader ? heif_context_read_from_reader(ctx, &HeifFdReader::callbacks(), reader, nullptr)
                        : heif_context_read_from_memory_without_copy(ctx, data.data(), data.size(), nullptr);
  if (e.code != heif_error_Ok) {
    if (parseFailed) *parseFailed = e.code != heif_error_Memory_allocation_error;
    err = e.message ? e.message : "无法解析 HEIF";
    return false;
  }
  heif_image_handle* handle = nullptr;
  e = heif_context_get_primary_image_handle(ctx, &handle);
  if (e.code != heif_error_Ok || handle == nullptr) {
    if (parseFailed) *parseFailed = e.code != heif_error_Memory_allocation_error;
    err = e.message ? e.message : "没有主图像";
    return false;
  }
  std::unique_ptr<heif_image_handle, decltype(&heif_image_handle_release)> ownedHandle(handle, heif_image_handle_release);
  bool ok = false;
  const int thumbs = heif_image_handle_get_number_of_thumbnails(handle);
  if (maxDim > 0 && thumbs > 0) {
    heif_item_id id = 0;
    if (heif_image_handle_get_list_of_thumbnail_IDs(handle, &id, 1) > 0) {
      heif_image_handle* thumb = nullptr;
      if (heif_image_handle_get_thumbnail(handle, id, &thumb).code == heif_error_Ok && thumb != nullptr) {
        std::unique_ptr<heif_image_handle, decltype(&heif_image_handle_release)> ownedThumb(thumb, heif_image_handle_release);
        const int longEdge = std::max(heif_image_handle_get_width(thumb), heif_image_handle_get_height(thumb));
        if (longEdge >= maxDim || (maxDim <= 512 && longEdge >= 160)) {
          ok = decodeHeifHandle(thumb, out, err, false, maxDim, budget);
          if (ok) readHeifExif(handle, out.exif);
        }
      }
    }
  }
  if (!ok) ok = decodeHeifHandle(handle, out, err, true, maxDim, budget);
  return ok;
}

using JxlGainMapView = JhgmLayout;

// Keep the existing host-test entry point while sharing the production parser.
bool parseJxlGainMap(BytesView box, JxlGainMapView& parsed) {
  return parseJhgmLayout(box, parsed);
}

bool probeJxl(BytesView data, ProbeResult& r) {
  JxlDecoder* dec = JxlDecoderCreate(nullptr);
  if (!dec) return false;

  if (JxlDecoderSubscribeEvents(dec, JXL_DEC_BASIC_INFO | JXL_DEC_BOX | JXL_DEC_BOX_COMPLETE) != JXL_DEC_SUCCESS) {
    JxlDecoderDestroy(dec);
    return false;
  }
  JxlDecoderSetDecompressBoxes(dec, JXL_TRUE);
  JxlDecoderSetInput(dec, data.data(), data.size());
  JxlDecoderCloseInput(dec);

  JxlBasicInfo info {};
  std::vector<uint8_t> currBoxData;
  JxlBoxType currBoxType = {0};
  std::vector<uint8_t> jhgmBox;
  bool jhgmSeen = false;
  bool decoded = false;

  for (;;) {
    JxlDecoderStatus status = JxlDecoderProcessInput(dec);
    if (status == JXL_DEC_ERROR) break;
    if (status == JXL_DEC_SUCCESS) {
      decoded = true;
      break;
    }

    if (status == JXL_DEC_BASIC_INFO) {
      if (JxlDecoderGetBasicInfo(dec, &info) == JXL_DEC_SUCCESS) {
        r.width = info.xsize;
        r.height = info.ysize;
      }
    } else if (status == JXL_DEC_BOX) {
      if (JxlDecoderGetBoxType(dec, currBoxType, JXL_TRUE) == JXL_DEC_SUCCESS) {
        if (memcmp(currBoxType, "Exif", 4) == 0) {
          r.hasExif = true;
        }
        if (memcmp(currBoxType, "jhgm", 4) == 0) {
          jhgmSeen = true;
          uint64_t raw_size = 0;
          if (JxlDecoderGetBoxSizeContents(dec, &raw_size) == JXL_DEC_SUCCESS && raw_size > 0 && raw_size < kMaxBoxBytes) {
            currBoxData.resize(static_cast<size_t>(raw_size));
          } else {
            currBoxData.resize(64 * 1024);
          }
          JxlDecoderSetBoxBuffer(dec, currBoxData.data(), currBoxData.size());
        }
      }
    } else if (status == JXL_DEC_BOX_NEED_MORE_OUTPUT) {
      const size_t remaining = JxlDecoderReleaseBoxBuffer(dec);
      const size_t used = currBoxData.size() - remaining;
      const size_t doubled = currBoxData.size() * 2;
      if (doubled > kMaxBoxBytes) {
        // 声明尺寸未知时倍增也要受 kMaxBoxBytes 约束，超限视为损坏数据。
        r.errorCode = 32;
        r.error = "JXL box 超过大小限制";
        break;
      }
      currBoxData.resize(doubled);
      JxlDecoderSetBoxBuffer(dec, currBoxData.data() + used, currBoxData.size() - used);
    } else if (status == JXL_DEC_BOX_COMPLETE) {
      const size_t remaining = JxlDecoderReleaseBoxBuffer(dec);
      currBoxData.resize(currBoxData.size() - remaining);
      if (memcmp(currBoxType, "jhgm", 4) == 0) {
        jhgmBox = std::move(currBoxData);
      }
      currBoxData.clear();
    }
  }
  JxlDecoderDestroy(dec);
  if (!decoded || r.width <= 0 || r.height <= 0) return false;
  if (!jhgmSeen) return true;

  auto invalidGainMap = [&](const char* message) {
    if (r.errorCode == 0) r.errorCode = 4;
    r.error = message;
    return false;
  };
  JhgmLayout layout;
  if (!parseJhgmLayout(BytesView(jhgmBox), layout)) {
    return invalidGainMap("JXL 增益图布局异常");
  }
  std::vector<uint8_t> iso(layout.iso.data(), layout.iso.data() + layout.iso.size());
  ultrahdr::uhdr_gainmap_metadata_frac frac {};
  ultrahdr::uhdr_gainmap_metadata_ext_t ext {};
  if (ultrahdr::uhdr_gainmap_metadata_frac::decodeGainmapMetadata(iso, &frac).error_code != UHDR_CODEC_OK ||
      ultrahdr::uhdr_gainmap_metadata_frac::gainmapMetadataFractionToFloat(&frac, &ext).error_code != UHDR_CODEC_OK) {
    return invalidGainMap("JXL 增益图元数据异常");
  }

  JxlDecoder* gmDec = JxlDecoderCreate(nullptr);
  bool validCodestream = false;
  int gainmapWidth = 0, gainmapHeight = 0;
  if (gmDec && JxlDecoderSubscribeEvents(gmDec, JXL_DEC_BASIC_INFO) == JXL_DEC_SUCCESS &&
      JxlDecoderSetInput(gmDec, layout.codestream.data(), layout.codestream.size()) == JXL_DEC_SUCCESS) {
    JxlDecoderCloseInput(gmDec);
    for (;;) {
      const JxlDecoderStatus status = JxlDecoderProcessInput(gmDec);
      if (status == JXL_DEC_BASIC_INFO) {
        JxlBasicInfo gmInfo {};
        if (JxlDecoderGetBasicInfo(gmDec, &gmInfo) == JXL_DEC_SUCCESS && gmInfo.xsize > 0 && gmInfo.ysize > 0 &&
            gmInfo.xsize <= INT32_MAX && gmInfo.ysize <= INT32_MAX) {
          gainmapWidth = static_cast<int>(gmInfo.xsize);
          gainmapHeight = static_cast<int>(gmInfo.ysize);
          validCodestream = true;
        }
        break;
      }
      if (status == JXL_DEC_ERROR || status == JXL_DEC_SUCCESS || status == JXL_DEC_NEED_MORE_INPUT) break;
    }
  }
  if (gmDec) JxlDecoderDestroy(gmDec);
  if (!validCodestream) return invalidGainMap("JXL 增益图 codestream 异常");

  r.hasGainMap = true;
  r.gainmapWidth = gainmapWidth;
  r.gainmapHeight = gainmapHeight;
  r.maxContentBoost = ext.max_content_boost[0];
  r.hdrCapacityMax = ext.hdr_capacity_max;
  return true;
}

bool decodeJxlToSdr(const std::vector<uint8_t>& data, SdrImage& out,
                    std::vector<uint8_t>* outJhgm, std::string& err,
                    int previewLimit = 0, uint64_t budget = UINT64_MAX) {
  JxlDecoder* dec = JxlDecoderCreate(nullptr);
  if (!dec) { err = "无法创建 JXL 解码器"; return false; }
  void* runner = JxlThreadParallelRunnerCreate(nullptr, ultraslim_codec_threads > 0 ? ultraslim_codec_threads : 4);
  if (runner) JxlDecoderSetParallelRunner(dec, JxlThreadParallelRunner, runner);

  uint32_t events = JXL_DEC_BASIC_INFO | JXL_DEC_COLOR_ENCODING | JXL_DEC_FULL_IMAGE | JXL_DEC_BOX | JXL_DEC_BOX_COMPLETE;
  if (JxlDecoderSubscribeEvents(dec, events) != JXL_DEC_SUCCESS) {
    JxlDecoderDestroy(dec);
    if (runner) JxlThreadParallelRunnerDestroy(runner);
    err = "JXL 事件订阅失败";
    return false;
  }
  JxlDecoderSetDecompressBoxes(dec, JXL_TRUE);
  JxlDecoderSetInput(dec, data.data(), data.size());
  JxlDecoderCloseInput(dec);

  bool success = false;
  JxlBasicInfo info {};
  std::vector<uint8_t> currBoxData;
  JxlBoxType currBoxType = {0};

  for (;;) {
    JxlDecoderStatus status = JxlDecoderProcessInput(dec);
    if (status == JXL_DEC_ERROR) {
      err = "JXL 解码错误";
      break;
    }
    if (status == JXL_DEC_SUCCESS) {
      success = true;
      break;
    }
    if (status == JXL_DEC_BASIC_INFO) {
      if (JxlDecoderGetBasicInfo(dec, &info) != JXL_DEC_SUCCESS) {
        err = "获取 JXL 基本信息失败";
        break;
      }
      if (info.xsize > INT32_MAX || info.ysize > INT32_MAX ||
          !previewFits(info.xsize, info.ysize, previewLimit > 0 ? previewLimit : std::max(info.xsize, info.ysize), budget, data.size())) {
        err = "图片尺寸或预览内存超出限制";
        break;
      }
      out.colorChannels = info.num_color_channels;
      out.hasAlpha = info.alpha_bits != 0;
      out.width = info.xsize;
      out.height = info.ysize;
    } else if (status == JXL_DEC_COLOR_ENCODING) {
      size_t icc_size = 0;
      if (JxlDecoderGetICCProfileSize(dec, JXL_COLOR_PROFILE_TARGET_DATA, &icc_size) == JXL_DEC_SUCCESS &&
          icc_size > 0 && icc_size < 16 * 1024 * 1024) {
        out.icc.resize(icc_size);
        if (JxlDecoderGetColorAsICCProfile(dec, JXL_COLOR_PROFILE_TARGET_DATA, out.icc.data(), out.icc.size()) != JXL_DEC_SUCCESS) {
          out.icc.clear();
        }
      }
    } else if (status == JXL_DEC_NEED_IMAGE_OUT_BUFFER) {
      size_t buf_size = 0;
      JxlPixelFormat pf = {4, JXL_TYPE_UINT8, JXL_LITTLE_ENDIAN, 0};
      if (JxlDecoderImageOutBufferSize(dec, &pf, &buf_size) != JXL_DEC_SUCCESS) {
        err = "获取 JXL 输出大小失败";
        break;
      }
      out.rgba.resize(buf_size);
      if (JxlDecoderSetImageOutBuffer(dec, &pf, out.rgba.data(), out.rgba.size()) != JXL_DEC_SUCCESS) {
        err = "设置 JXL 输出缓冲失败";
        break;
      }
    } else if (status == JXL_DEC_BOX) {
      if (JxlDecoderGetBoxType(dec, currBoxType, JXL_TRUE) == JXL_DEC_SUCCESS) {
        if (memcmp(currBoxType, "Exif", 4) == 0 || (outJhgm && memcmp(currBoxType, "jhgm", 4) == 0)) {
          uint64_t raw_size = 0;
          if (JxlDecoderGetBoxSizeContents(dec, &raw_size) == JXL_DEC_SUCCESS && raw_size > 0 && raw_size < kMaxBoxBytes) {
            currBoxData.resize(static_cast<size_t>(raw_size));
          } else {
            currBoxData.resize(64 * 1024);
          }
          JxlDecoderSetBoxBuffer(dec, currBoxData.data(), currBoxData.size());
        }
      }
    } else if (status == JXL_DEC_BOX_NEED_MORE_OUTPUT) {
      const size_t remaining = JxlDecoderReleaseBoxBuffer(dec);
      const size_t used = currBoxData.size() - remaining;
      const size_t doubled = currBoxData.size() * 2;
      if (doubled > kMaxBoxBytes) {
        // 声明尺寸未知时倍增也要受 kMaxBoxBytes 约束，超限视为损坏数据。
        err = "JXL box 超过大小限制";
        success = false;
        break;
      }
      currBoxData.resize(doubled);
      JxlDecoderSetBoxBuffer(dec, currBoxData.data() + used, currBoxData.size() - used);
    } else if (status == JXL_DEC_BOX_COMPLETE) {
      const size_t remaining = JxlDecoderReleaseBoxBuffer(dec);
      currBoxData.resize(currBoxData.size() - remaining);
      if (memcmp(currBoxType, "Exif", 4) == 0) {
        if (currBoxData.size() > 4) {
          uint32_t offset = loadBE32(currBoxData.data());
          if (4 + offset < currBoxData.size()) {
            out.exif = {'E', 'x', 'i', 'f', 0, 0};
            out.exif.insert(out.exif.end(), currBoxData.begin() + 4 + offset, currBoxData.end());
          }
        }
      } else if (outJhgm && memcmp(currBoxType, "jhgm", 4) == 0) {
        *outJhgm = std::move(currBoxData);
      }
      currBoxData.clear();
    }
  }

  JxlDecoderDestroy(dec);
  if (runner) JxlThreadParallelRunnerDestroy(runner);
  return success && !out.rgba.empty();
}

heif_error fdWriteCallback(heif_context* /*ctx*/, const void* data, size_t size, void* userdata) {
  const int fd = *static_cast<int*>(userdata);
  if (writeAll(fd, data, size)) {
    return heif_error {heif_error_Ok, heif_suberror_Unspecified, nullptr};
  }
  return heif_error {heif_error_Encoding_error, heif_suberror_Cannot_write_output_data,
                     "write failed"};
}

int evenAtLeast2(int v) { return std::max(2, v - (v & 1)); }

void scaledSize(int sw, int sh, int percent, int& w, int& h) {
  if (percent >= 100) {
    w = sw;
    h = sh;
    return;
  }
  const int64_t scale = std::max(1, percent);
  w = evenAtLeast2(static_cast<int>(static_cast<int64_t>(sw) * scale / 100));
  h = evenAtLeast2(static_cast<int>(static_cast<int64_t>(sh) * scale / 100));
}

// Fixed-point bilinear. Sample positions are computed once per axis.
std::vector<uint8_t> resizeRgbaPlane(const uint8_t* s, int sw, int sh, size_t stride,
                                     int newW, int newH) {
  if (!s || newW <= 0 || newH <= 0 || sw <= 0 || sh <= 0 || stride < size_t(sw) * 4 ||
      int64_t(newW) * newH > kMaxPixels) return {};
  std::vector<uint8_t> out(static_cast<size_t>(newW) * newH * 4);
  if (sw == newW && sh == newH) {
    const size_t rowBytes = size_t(sw) * 4;
    for (int y = 0; y < sh; ++y) memcpy(out.data() + size_t(y) * rowBytes, s + size_t(y) * stride, rowBytes);
    return out;
  }

  std::vector<int> x0s(static_cast<size_t>(newW)), x1s(static_cast<size_t>(newW)),
      wxs(static_cast<size_t>(newW));
  for (int x = 0; x < newW; x++) {
    // Pixel-center mapping: u = (x + 0.5) * sw / newW - 0.5 in 1/32768 fixed-point units.
    // fx = ((2*x + 1) * sw * 32768) / (2 * newW) - 16384.
    const int64_t fx = (static_cast<int64_t>(x) * 2 + 1) * sw * 32768 / (2 * newW) - 16384;
    int x0 = 0, x1 = 0, wx = 0;
    if (fx > 0) {
      x0 = static_cast<int>(fx >> 15);
      wx = static_cast<int>(fx & 32767);
      if (x0 >= sw - 1) {
        x0 = x1 = sw - 1;
        wx = 0;
      } else {
        x1 = x0 + 1;
      }
    }
    x0s[static_cast<size_t>(x)] = x0;
    x1s[static_cast<size_t>(x)] = x1;
    wxs[static_cast<size_t>(x)] = wx;
  }

  uint8_t* d = out.data();
  parallelRows(newH, size_t(newW) * newH, [&](int begin, int end) {
    for (int y = begin; y < end; y++) {
      const int64_t fy = (static_cast<int64_t>(y) * 2 + 1) * sh * 32768 / (2 * newH) - 16384;
      int y0 = 0, y1 = 0, wy = 0;
      if (fy > 0) {
        y0 = static_cast<int>(fy >> 15);
        wy = static_cast<int>(fy & 32767);
        if (y0 >= sh - 1) {
          y0 = y1 = sh - 1;
          wy = 0;
        } else {
          y1 = y0 + 1;
        }
      }
      const int wy0 = 32768 - wy;
      const uint8_t* row0 = s + static_cast<size_t>(y0) * stride;
      const uint8_t* row1 = s + static_cast<size_t>(y1) * stride;
      uint8_t* dst = d + static_cast<size_t>(y) * static_cast<size_t>(newW) * 4;
      for (int x = 0; x < newW; x++) {
        const int x0 = x0s[static_cast<size_t>(x)];
        const int x1 = x1s[static_cast<size_t>(x)];
        const int wx = wxs[static_cast<size_t>(x)];
        const int wx0 = 32768 - wx;
        const uint8_t* p00 = row0 + static_cast<size_t>(x0) * 4;
        const uint8_t* p01 = row0 + static_cast<size_t>(x1) * 4;
        const uint8_t* p10 = row1 + static_cast<size_t>(x0) * 4;
        const uint8_t* p11 = row1 + static_cast<size_t>(x1) * 4;
        const int64_t w00 = static_cast<int64_t>(wx0) * wy0;
        const int64_t w01 = static_cast<int64_t>(wx) * wy0;
        const int64_t w10 = static_cast<int64_t>(wx0) * wy;
        const int64_t w11 = static_cast<int64_t>(wx) * wy;
        for (int c = 0; c < 4; c++) {
          const int64_t v = static_cast<int64_t>(p00[c]) * w00 +
                            static_cast<int64_t>(p01[c]) * w01 +
                            static_cast<int64_t>(p10[c]) * w10 +
                            static_cast<int64_t>(p11[c]) * w11;
          dst[c] = static_cast<uint8_t>((v + (1 << 29)) >> 30);
        }
        dst += 4;
      }
    }
  });
  return out;
}

SdrImage resizeRgba(const SdrImage& src, int newW, int newH) {
  SdrImage out;
  out.width = newW; out.height = newH;
  out.exif = src.exif; out.icc = src.icc; out.profile = src.profile; out.hasProfile = src.hasProfile;
  out.hasAlpha = src.hasAlpha;
  out.rgba = resizeRgbaPlane(src.rgba.data(), src.width, src.height, size_t(src.width) * 4, newW, newH);
  return out;
}

void maybeDownscale(SdrImage& img, int nw, int nh) {
  if (nw > 0 && nh > 0 && (nw < img.width || nh < img.height)) img = resizeRgba(img, nw, nh);
}

// Encodes an SDR RGBA8888 image to JPEG in memory with libjpeg-turbo.
// JPEG has no NCLX box. Generate an ICC for supported SDR profiles instead of losing the gamut.
bool prepareJpegProfile(SdrImage& img) {
  if(!img.icc.empty() || !img.hasProfile) return true;
  const int primaries=img.profile.color_primaries, transfer=img.profile.transfer_characteristics;
  const auto gamut=primaries==12?UHDR_CG_DISPLAY_P3:primaries==9?UHDR_CG_BT_2100:UHDR_CG_BT_709;
  if((primaries!=1&&primaries!=2&&primaries!=9&&primaries!=12)||(transfer!=13&&transfer!=2&&transfer!=8)) return false;
  auto profile=ultrahdr::IccHelper::writeIccProfile(transfer==8?UHDR_CT_LINEAR:UHDR_CT_SRGB,gamut);
  if(!profile || profile->getLength()<=ultrahdr::kICCIdentifierSize) return false;
  auto* data=static_cast<uint8_t*>(profile->getData());
  img.icc.assign(data+ultrahdr::kICCIdentifierSize,data+profile->getLength());return true;
}

uhdr_color_gamut_t getGamutFromIcc(const uint8_t* data, size_t size) {
  if (!data || size == 0) return UHDR_CG_UNSPECIFIED;
  constexpr size_t kPrefixSize = ultrahdr::kICCIdentifierSize;
  if (size > kPrefixSize && memcmp(data, ultrahdr::kICCIdentifier, sizeof(ultrahdr::kICCIdentifier)) == 0) {
    return ultrahdr::IccHelper::readIccColorGamut(const_cast<uint8_t*>(data), size);
  }
  std::vector<uint8_t> withPrefix(kPrefixSize + size);
  memcpy(withPrefix.data(), ultrahdr::kICCIdentifier, sizeof(ultrahdr::kICCIdentifier));
  withPrefix[sizeof(ultrahdr::kICCIdentifier)] = 1;
  withPrefix[sizeof(ultrahdr::kICCIdentifier) + 1] = 1;
  memcpy(withPrefix.data() + kPrefixSize, data, size);
  return ultrahdr::IccHelper::readIccColorGamut(withPrefix.data(), withPrefix.size());
}

uhdr_color_gamut_t getGamutFromIcc(const std::vector<uint8_t>& icc) {
  return getGamutFromIcc(icc.data(), icc.size());
}

// 实测（26MP 噪声图，q90/95/100）：接管 libjpeg 缓冲省掉的 assign 拷贝仅占编码时间的
// 1-2%，而 mem_dest 的最终指针/长度只在 term_destination（压缩结束）时才写回，
// 提前读取会把输出截断到初始缓冲大小。收益与风险不成比例，故保留由 libjpeg 自管缓冲。
std::vector<uint8_t> encodeJpegToMem(const SdrImage& img, int quality, bool keepExif = false) {
  std::vector<uint8_t> out;
  JpegErrMgr jerr {};
  jerr.message[0] = '\0';
  jpeg_compress_struct cinfo {};
  cinfo.err = jpeg_std_error(&jerr.pub);
  jerr.pub.error_exit = jpegErrExit;
  uint8_t* mem = nullptr;
  unsigned long memSize = 0;
  volatile bool created = false;
  volatile bool memoryDestination = false;
  if (setjmp(jerr.jump)) {
    if (created && memoryDestination) (*cinfo.dest->term_destination)(&cinfo);
    if (created) jpeg_destroy_compress(&cinfo);
    if (!memoryDestination) mem = nullptr;
    free(mem);
    return {};
  }
  jpeg_create_compress(&cinfo);
  created = true;
  jpeg_mem_dest(&cinfo, &mem, &memSize);
  memoryDestination = true;
  cinfo.image_width = img.width;
  cinfo.image_height = img.height;
  cinfo.input_components = 4;
  cinfo.in_color_space = JCS_EXT_RGBA;
  jpeg_set_defaults(&cinfo);
  jpeg_set_quality(&cinfo, std::max(1, std::min(100, quality)), TRUE);
  jpeg_start_compress(&cinfo, TRUE);
  if(!img.icc.empty()) jpeg_write_icc_profile(&cinfo,img.icc.data(),img.icc.size());
  if (keepExif && !img.exif.empty()) {
    jpeg_write_marker(&cinfo, JPEG_APP0 + 1, img.exif.data(), static_cast<unsigned int>(img.exif.size()));
  }
  const size_t stride = static_cast<size_t>(img.width) * 4;
  while (cinfo.next_scanline < cinfo.image_height) {
    JSAMPROW row = const_cast<uint8_t*>(img.rgba.data()) + static_cast<size_t>(cinfo.next_scanline) * stride;
    jpeg_write_scanlines(&cinfo, &row, 1);
  }
  jpeg_finish_compress(&cinfo);
  jpeg_destroy_compress(&cinfo);
  if (mem != nullptr && memSize > 0) out.assign(mem, mem + memSize);
  free(mem);
  return out;
}

// Encodes an SDR RGBA8888 image to JPEG with libjpeg-turbo.
int encodeJpegSdr(const SdrImage& img, int quality, bool keepExif, int outFd, std::string& err) {
  FILE* f = fdopen(dup(outFd), "wb");
  if (f == nullptr) {
    err = "无法打开输出文件";
    return 30;
  }
  JpegErrMgr jerr {};
  jerr.message[0] = '\0';
  jpeg_compress_struct cinfo {};
  cinfo.err = jpeg_std_error(&jerr.pub);
  jerr.pub.error_exit = jpegErrExit;
  bool created = false;
  if (setjmp(jerr.jump)) {
    if (created) jpeg_destroy_compress(&cinfo);
    fclose(f);
    err = jerr.message[0] ? jerr.message : "JPEG 编码失败";
    return 31;
  }
  jpeg_create_compress(&cinfo);
  created = true;
  jpeg_stdio_dest(&cinfo, f);
  cinfo.image_width = img.width;
  cinfo.image_height = img.height;
  cinfo.input_components = 4;
  cinfo.in_color_space = JCS_EXT_RGBA;
  jpeg_set_defaults(&cinfo);
  jpeg_set_quality(&cinfo, std::max(1, std::min(100, quality)), TRUE);
  jpeg_start_compress(&cinfo, TRUE);
  if(!img.icc.empty()) jpeg_write_icc_profile(&cinfo,img.icc.data(),img.icc.size());
  if (keepExif && !img.exif.empty()) {
    jpeg_write_marker(&cinfo, JPEG_APP0 + 1, img.exif.data(), static_cast<unsigned int>(img.exif.size()));
  }
  const size_t stride = static_cast<size_t>(img.width) * 4;
  while (cinfo.next_scanline < cinfo.image_height) {
    JSAMPROW row = const_cast<uint8_t*>(img.rgba.data()) + static_cast<size_t>(cinfo.next_scanline) * stride;
    jpeg_write_scanlines(&cinfo, &row, 1);
  }
  jpeg_finish_compress(&cinfo);
  jpeg_destroy_compress(&cinfo);
  // stdio 缓冲的写失败要到 flush/close 才暴露；不检查会静默产出截断文件。
  const int flushed = fflush(f);
  const int closed = fclose(f);  // closes only the dup'd fd
  if (flushed != 0 || closed != 0) {
    err = std::string("写入输出文件失败：") + strerror(errno);
    return 30;
  }
  return 0;
}

void yuvPixel(const uint8_t* p, uint8_t& y, uint8_t& u, uint8_t& v) {
  const int r = p[0], g = p[1], b = p[2];
  y = static_cast<uint8_t>(((66 * r + 129 * g + 25 * b + 128) >> 8) + 16);
  u = static_cast<uint8_t>(((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128);
  v = static_cast<uint8_t>(((112 * r - 94 * g - 18 * b + 128) >> 8) + 128);
}

// BT.709 limited-range. The nclx matrix tag written next to the coded planes
// must match the coefficient set used here: decoders assume the 709 basis for
// 709/P3-tagged content, so coding in 601 while tagging 709 shifts saturated
// colors.
void yuvPixel709(const uint8_t* p, uint8_t& y, uint8_t& u, uint8_t& v) {
  const int r = p[0], g = p[1], b = p[2];
  y = static_cast<uint8_t>(((47 * r + 157 * g + 16 * b + 128) >> 8) + 16);
  u = static_cast<uint8_t>(((-26 * r - 87 * g + 112 * b + 128) >> 8) + 128);
  v = static_cast<uint8_t>(((112 * r - 102 * g - 10 * b + 128) >> 8) + 128);
}

#if defined(__ARM_NEON)
void storeY8(uint8_t* dst, const uint8_t* rgba) {
  const uint8x8x4_t p = vld4_u8(rgba);
  const uint16x8_t r = vmovl_u8(p.val[0]);
  const uint16x8_t g = vmovl_u8(p.val[1]);
  const uint16x8_t b = vmovl_u8(p.val[2]);
  uint16x8_t acc = vmulq_n_u16(r, 66);
  acc = vmlaq_n_u16(acc, g, 129);
  acc = vmlaq_n_u16(acc, b, 25);
  acc = vaddq_u16(acc, vdupq_n_u16(128));
  vst1_u8(dst, vadd_u8(vmovn_u16(vshrq_n_u16(acc, 8)), vdup_n_u8(16)));
}

void storeY8_709(uint8_t* dst, const uint8_t* rgba) {
  const uint8x8x4_t p = vld4_u8(rgba);
  const uint16x8_t r = vmovl_u8(p.val[0]);
  const uint16x8_t g = vmovl_u8(p.val[1]);
  const uint16x8_t b = vmovl_u8(p.val[2]);
  uint16x8_t acc = vmulq_n_u16(r, 47);
  acc = vmlaq_n_u16(acc, g, 157);
  acc = vmlaq_n_u16(acc, b, 16);
  acc = vaddq_u16(acc, vdupq_n_u16(128));
  vst1_u8(dst, vadd_u8(vmovn_u16(vshrq_n_u16(acc, 8)), vdup_n_u8(16)));
}

// 四取样点求和（相邻两像素 × 两行），保持标量路径的 /4 语义。
uint16x8_t pairSum4(uint8x16_t a, uint8x16_t b) {
  return vaddq_u16(
      vpaddq_u16(vmovl_u8(vget_low_u8(a)), vmovl_u8(vget_high_u8(a))),
      vpaddq_u16(vmovl_u8(vget_low_u8(b)), vmovl_u8(vget_high_u8(b))));
}

// 8 个色度取样点：与标量 ((c1*r + c2*g + c3*b + 128) >> 8) + 128 逐值一致。
// 系数按算术右移（floor）语义，累加范围 ±28815 在 int16 内，不会溢出。
void chroma8(uint8_t* dst, const uint8_t* row1, const uint8_t* row2,
             int c1, int c2, int c3) {
  const uint8x16x4_t p1 = vld4q_u8(row1);
  const uint8x16x4_t p2 = vld4q_u8(row2);
  const uint16x8_t r = vshrq_n_u16(pairSum4(p1.val[0], p2.val[0]), 2);
  const uint16x8_t g = vshrq_n_u16(pairSum4(p1.val[1], p2.val[1]), 2);
  const uint16x8_t b = vshrq_n_u16(pairSum4(p1.val[2], p2.val[2]), 2);
  const int16x8_t rg = vaddq_s16(vmulq_n_s16(vreinterpretq_s16_u16(r), int16_t(c1)),
                                 vmulq_n_s16(vreinterpretq_s16_u16(g), int16_t(c2)));
  const int16x8_t acc = vaddq_s16(vaddq_s16(rg, vmulq_n_s16(vreinterpretq_s16_u16(b), int16_t(c3))),
                                  vdupq_n_s16(128));
  vst1_u8(dst, vqmovun_s16(vaddq_s16(vshrq_n_s16(acc, 8), vdupq_n_s16(128))));
}

// 8 像素 4:4:4 YCbCr 转换：与标量 yuvPixel / yuvPixel709 逐值一致。
void storeYuv444_8(uint8_t* yDst, uint8_t* uDst, uint8_t* vDst, const uint8_t* rgba, bool bt709) {
  const uint8x8x4_t p = vld4_u8(rgba);
  const uint16x8_t ru = vmovl_u8(p.val[0]);
  const uint16x8_t gu = vmovl_u8(p.val[1]);
  const uint16x8_t bu = vmovl_u8(p.val[2]);
  uint16x8_t yAcc = vmulq_n_u16(ru, bt709 ? 47 : 66);
  yAcc = vmlaq_n_u16(yAcc, gu, bt709 ? 157 : 129);
  yAcc = vmlaq_n_u16(yAcc, bu, bt709 ? 16 : 25);
  yAcc = vaddq_u16(yAcc, vdupq_n_u16(128));
  vst1_u8(yDst, vadd_u8(vmovn_u16(vshrq_n_u16(yAcc, 8)), vdup_n_u8(16)));

  const int16x8_t r = vreinterpretq_s16_u16(ru);
  const int16x8_t g = vreinterpretq_s16_u16(gu);
  const int16x8_t b = vreinterpretq_s16_u16(bu);
  const int16x8_t uRg = vaddq_s16(vmulq_n_s16(r, bt709 ? -26 : -38),
                                  vmulq_n_s16(g, bt709 ? -87 : -74));
  const int16x8_t uAcc = vaddq_s16(vaddq_s16(uRg, vmulq_n_s16(b, 112)), vdupq_n_s16(128));
  vst1_u8(uDst, vqmovun_s16(vaddq_s16(vshrq_n_s16(uAcc, 8), vdupq_n_s16(128))));

  const int16x8_t vRg = vaddq_s16(vmulq_n_s16(r, 112),
                                  vmulq_n_s16(g, bt709 ? -102 : -94));
  const int16x8_t vAcc = vaddq_s16(vaddq_s16(vRg, vmulq_n_s16(b, bt709 ? -10 : -18)), vdupq_n_s16(128));
  vst1_u8(vDst, vqmovun_s16(vaddq_s16(vshrq_n_s16(vAcc, 8), vdupq_n_s16(128))));
}
#endif

// Converts RGBA8888 to limited-range YUV420 directly into destination plane pointers with strides.
void rgbaToYuv420Direct(const SdrImage& img,
                        uint8_t* yDst, int yStride,
                        uint8_t* uDst, int uStride,
                        uint8_t* vDst, int vStride,
                        bool bt709) {
  const int w = img.width, h = img.height;
  const int cw = (w + 1) / 2, ch = (h + 1) / 2;
  const uint8_t* rgba = img.rgba.data();
  parallelRows(h, size_t(w) * h, [&](int begin, int end) {
    for (int y = begin; y < end; y++) {
      uint8_t* yRow = yDst + static_cast<size_t>(y) * yStride;
      const uint8_t* src = rgba + static_cast<size_t>(y) * w * 4;
      int x = 0;
  #if defined(__ARM_NEON)
      if (bt709) { for (; x + 8 <= w; x += 8) storeY8_709(yRow + x, src + x * 4); }
      else { for (; x + 8 <= w; x += 8) storeY8(yRow + x, src + x * 4); }
  #endif
      for (; x < w; x++) {
        uint8_t yy, uu, vv;
        if (bt709) yuvPixel709(src + x * 4, yy, uu, vv);
        else yuvPixel(src + x * 4, yy, uu, vv);
        yRow[x] = yy;
      }
      // Even heights are fully covered by the preceding even row. An odd last row is not.
      const bool uncoveredLastRow = (y == h - 1) && ((h & 1) != 0);
      if ((y & 1) != 0 && !uncoveredLastRow) continue;
      const int y2 = std::min(y + 1, h - 1);
      const uint8_t* src2 = rgba + static_cast<size_t>(y2) * w * 4;
      uint8_t* uRow = uDst + static_cast<size_t>(y / 2) * uStride;
      uint8_t* vRow = vDst + static_cast<size_t>(y / 2) * vStride;
      int cx = 0;
  #if defined(__ARM_NEON)
      // 每 16 个像素产生 8 个色度取样点；w 为奇数时最后一列要复用像素，
      // 逐个取样点的 x1 会被 clamp 到 w-1，NEON 的成对读取会越界，故留给标量尾部。
      const int pairLimit = (w & 1) ? (w - 1) : w;
      const int u1 = bt709 ? -26 : -38;
      const int u2 = bt709 ? -87 : -74;
      const int v2 = bt709 ? -102 : -94;
      const int v3 = bt709 ? -10 : -18;
      for (; cx * 2 + 16 <= pairLimit; cx += 8) {
        chroma8(uRow + cx, src + cx * 8, src2 + cx * 8, u1, u2, 112);
        chroma8(vRow + cx, src + cx * 8, src2 + cx * 8, 112, v2, v3);
      }
  #endif
      for (; cx < cw; cx++) {
        const int x0 = std::min(cx * 2, w - 1);
        const int x1 = std::min(x0 + 1, w - 1);
        int r = 0, g = 0, b = 0;
        const uint8_t* samples[4] = {src + x0 * 4, src + x1 * 4, src2 + x0 * 4, src2 + x1 * 4};
        for (const uint8_t* q : samples) {
          r += q[0];
          g += q[1];
          b += q[2];
        }
        r /= 4;
        g /= 4;
        b /= 4;
        if (bt709) {
          uRow[cx] = static_cast<uint8_t>(((-26 * r - 87 * g + 112 * b + 128) >> 8) + 128);
          vRow[cx] = static_cast<uint8_t>(((112 * r - 102 * g - 10 * b + 128) >> 8) + 128);
        } else {
          uRow[cx] = static_cast<uint8_t>(((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128);
          vRow[cx] = static_cast<uint8_t>(((112 * r - 94 * g - 18 * b + 128) >> 8) + 128);
        }
      }
    }
  });
}

int resolveCodecThreads(int requested) {
  if (requested > 0) return requested;
  unsigned cores = std::thread::hardware_concurrency();
  if (cores == 0) cores = 4;
  return std::max(1, static_cast<int>(cores) / 2);
}

void configureEncoder(heif_encoder* encoder, int outCodec, int speedLevel, int threads) {
  const int level = speedLevel < 0 || speedLevel > 2 ? 1 : speedLevel;
  if (outCodec == 1) {
    static const char* kPresets[] = {"slow", "medium", "veryfast"};
    heif_encoder_set_parameter(encoder, "preset", kPresets[level]);
    if (threads > 0) {
      char pools[16];
      snprintf(pools, sizeof pools, "%d", threads);
      heif_encoder_set_parameter(encoder, "x265:pools", pools);
      heif_encoder_set_parameter(encoder, "x265:frame-threads", "1");
    }
  } else {
    static const int kSpeeds[] = {4, 6, 9};
    heif_encoder_set_parameter_integer(encoder, "speed", kSpeeds[level]);
    if (threads > 0) heif_encoder_set_parameter_integer(encoder, "threads", threads);
  }
}

struct GainMapPayload {
  mutable std::vector<uint8_t> encodedJxl;
  bool cacheForRetry = false;
  bool avifGainmapCompatibility = false;
  bool heicGainmapCompatibility = false;
  SdrImage image;
  std::vector<uint8_t> iso;
  int codec = 1;
  bool multiChannel = true;
  // Not owned. Null selects a BT.2100 HLG profile for the derived rendition.
  const heif_color_profile_nclx* derivedNclx = nullptr;
};

// YCbCr planes for one HEIC/AVIF encode, already converted from RGBA. libheif's
// encoders only read the input image, so one instance can serve a retrySmaller
// retry; the expensive RGB->YUV pass then happens once per image, not per attempt.
struct PreparedBasePlanes {
  HeifImagePtr image;
  heif_color_profile_nclx nclx {};
  bool oddAvif = false;
};

struct PreparedGainMapPlanes {
  HeifImagePtr image;
  heif_color_profile_nclx nclx {};
  // Derived-rendition profile; ownedDerived keeps an allocated fallback alive.
  const heif_color_profile_nclx* derived = nullptr;
  NclxPtr ownedDerived;
  bool compatibleHeic = false;
};

// Builds the base image planes and the NCLX profile written to the coded item.
// The caller may release the RGBA buffer right after this returns.
int prepareBasePlanes(const SdrImage& img, int outCodec, bool hdrGainMap,
                      PreparedBasePlanes& out, std::string& err) {
  uhdr_color_gamut_t gamut = UHDR_CG_UNSPECIFIED;
  if (img.hasProfile) {
    if (img.profile.color_primaries == heif_color_primaries_SMPTE_EG_432_1) gamut = UHDR_CG_DISPLAY_P3;
    else if (img.profile.color_primaries == heif_color_primaries_ITU_R_BT_2020_2_and_2100_0) gamut = UHDR_CG_BT_2100;
    else if (img.profile.color_primaries == heif_color_primaries_ITU_R_BT_709_5) gamut = UHDR_CG_BT_709;
  }
  if (gamut == UHDR_CG_UNSPECIFIED && !img.icc.empty()) {
    gamut = getGamutFromIcc(img.icc);
  }

  // Display P3 in HEIF/AVIF industry standard convention uses BT.601 matrix (as does libultrahdr:
  // sdr_cg_matrix = sdr_intent->cg == UHDR_CG_DISPLAY_P3 ? UHDR_CG_BT_601 : sdr_intent->cg).
  // Rec.709 uses BT.709 matrix.
  const bool use601 = (gamut == UHDR_CG_DISPLAY_P3) ||
      (img.hasProfile && img.profile.matrix_coefficients == heif_matrix_coefficients_ITU_R_BT_601_6);
  // HDR needs exact base/gain-map geometry, without a clean-aperture crop.
  // SDR uses SVT 4:2:0, including large odd grids: the container pads the coded
  // canvas to even dimensions and clap preserves the original visible size.
  // Android 15/16 decode this SDR path, whereas Android 16 rejects odd 4:4:4 grids.
  const bool oddAvif = outCodec == 2 && ((img.width | img.height) & 1) && hdrGainMap;
  heif_image* himg = nullptr;
  heif_error e = heif_image_create(img.width, img.height, heif_colorspace_YCbCr,
                                   oddAvif ? heif_chroma_444 : heif_chroma_420, &himg);
  if (e.code != heif_error_Ok) {
    err = std::string("创建图像失败: ") + e.message;
    return 21;
  }
  out.image.reset(himg);
  const int cw = oddAvif ? img.width : (img.width + 1) / 2;
  const int ch = oddAvif ? img.height : (img.height + 1) / 2;
  if(heif_image_add_plane(himg, heif_channel_Y, img.width, img.height, 8).code!=heif_error_Ok ||
     heif_image_add_plane(himg, heif_channel_Cb, cw, ch, 8).code!=heif_error_Ok ||
     heif_image_add_plane(himg, heif_channel_Cr, cw, ch, 8).code!=heif_error_Ok) {
    err="无法分配编码内存";return 31;
  }
  int yStride = 0, uStride = 0, vStride = 0;
  uint8_t* yDst = heif_image_get_plane(himg, heif_channel_Y, &yStride);
  uint8_t* uDst = heif_image_get_plane(himg, heif_channel_Cb, &uStride);
  uint8_t* vDst = heif_image_get_plane(himg, heif_channel_Cr, &vStride);
  if (yDst == nullptr || uDst == nullptr || vDst == nullptr) {
    err="获取图像平面失败";return 31;
  }
  if (oddAvif) {
    parallelRows(img.height, size_t(img.width) * img.height, [&](int begin, int end) {
      for (int y = begin; y < end; ++y) {
        int x = 0;
#if defined(__ARM_NEON)
        for (; x + 8 <= img.width; x += 8) {
          storeYuv444_8(yDst + y * yStride + x, uDst + y * uStride + x, vDst + y * vStride + x,
                        img.rgba.data() + (size_t(y) * img.width + x) * 4, /*bt709=*/!use601);
        }
#endif
        for (; x < img.width; ++x) {
          const uint8_t* pixel = img.rgba.data() + (size_t(y) * img.width + x) * 4;
          if (use601) yuvPixel(pixel, yDst[y*yStride+x], uDst[y*uStride+x], vDst[y*vStride+x]);
          else yuvPixel709(pixel, yDst[y*yStride+x], uDst[y*uStride+x], vDst[y*vStride+x]);
        }
      }
    });
  } else rgbaToYuv420Direct(img, yDst, yStride, uDst, uStride, vDst, vStride, /*bt709=*/!use601);
  heif_color_primaries primaries = heif_color_primaries_ITU_R_BT_709_5;
  if (gamut == UHDR_CG_DISPLAY_P3) {
    primaries = heif_color_primaries_SMPTE_EG_432_1;
  } else if (gamut == UHDR_CG_BT_2100) {
    primaries = heif_color_primaries_ITU_R_BT_2020_2_and_2100_0;
  } else if (gamut == UHDR_CG_BT_709) {
    primaries = heif_color_primaries_ITU_R_BT_709_5;
  } else if (img.hasProfile && img.profile.color_primaries != heif_color_primaries_unspecified) {
    primaries = img.profile.color_primaries;
  }

  heif_transfer_characteristics transfer = heif_transfer_characteristic_IEC_61966_2_1;
  if (img.hasProfile && img.profile.transfer_characteristics != heif_transfer_characteristic_unspecified) {
    transfer = img.profile.transfer_characteristics;
  }

  out.nclx = heif_color_profile_nclx {};
  out.nclx.version = 1;
  out.nclx.color_primaries = primaries;
  out.nclx.transfer_characteristics = transfer;
  out.nclx.matrix_coefficients = use601 ? heif_matrix_coefficients_ITU_R_BT_601_6 : heif_matrix_coefficients_ITU_R_BT_709_5;
  out.nclx.full_range_flag = 0;
  heif_image_set_nclx_color_profile(himg, &out.nclx);
  out.oddAvif = oddAvif;
  return 0;
}

// Converts the preserved gain map's RGBA into the plane layout its lossless codec needs.
// Independent of the base quality, so it survives a retrySmaller retry.
int prepareGainMapPlanes(const GainMapPayload& gm, PreparedGainMapPlanes& out, std::string& err) {
  if (gm.iso.empty() || gm.image.width <= 0 || gm.image.height <= 0) {
    err = "gain map 数据不完整";
    return 24;
  }
  // The default preserves samples exactly. Android MediaCodec misinterprets
  // Identity/GBR as BT.601; the explicit compatibility mode supplies real YCbCr.
  const bool avif = gm.codec == 2;
  const bool compatibleHeic = !avif && gm.heicGainmapCompatibility;
  const bool compatibleRgb = gm.multiChannel && (avif ? gm.avifGainmapCompatibility : compatibleHeic);
  heif_image* image = nullptr;
  heif_error e = heif_image_create(gm.image.width, gm.image.height,
      (gm.multiChannel || compatibleHeic) ? heif_colorspace_YCbCr : heif_colorspace_monochrome,
      compatibleHeic ? heif_chroma_420 : (gm.multiChannel ? heif_chroma_444 : heif_chroma_monochrome), &image);
  if (e.code != heif_error_Ok) { err = e.message; return 24; }
  HeifImagePtr imageOwner(image);
  const heif_channel planes[] = {heif_channel_Y, heif_channel_Cb, heif_channel_Cr};
  if (compatibleHeic) {
    // Platform HEVC decoders need 4:2:0 even for a monochrome auxiliary image.
    // Keep Y at the original map dimensions. RGB chroma is explicitly lossy;
    // a mono map uses full-range Y and neutral chroma to retain its samples.
    for (int c = 0; c < 3; ++c) {
      e = heif_image_add_plane(image, planes[c], c ? (gm.image.width + 1) / 2 : gm.image.width,
                              c ? (gm.image.height + 1) / 2 : gm.image.height, 8);
      if (e.code != heif_error_Ok) { err = e.message; return 24; }
    }
    int ys = 0, us = 0, vs = 0;
    auto* yp = heif_image_get_plane(image, heif_channel_Y, &ys);
    auto* up = heif_image_get_plane(image, heif_channel_Cb, &us);
    auto* vp = heif_image_get_plane(image, heif_channel_Cr, &vs);
    if (gm.multiChannel) {
      rgbaToYuv420Direct(gm.image, yp, ys, up, us, vp, vs, false);
    } else {
      parallelRows(gm.image.height, size_t(gm.image.width) * gm.image.height, [&](int yBegin, int yEnd) {
        for (int y = yBegin; y < yEnd; ++y)
          rgbaToGrayFast(gm.image.rgba.data() + size_t(y) * gm.image.width * 4, yp + y * ys, gm.image.width);
      });
      for (int y = 0; y < (gm.image.height + 1) / 2; ++y) {
        memset(up + y * us, 128, (gm.image.width + 1) / 2);
        memset(vp + y * vs, 128, (gm.image.width + 1) / 2);
      }
    }
  } else if (gm.multiChannel) {
    for (int c = 0; c < 3; ++c) {
      e = heif_image_add_plane(image, planes[c], gm.image.width, gm.image.height, 8);
      if (e.code != heif_error_Ok) { err = e.message; return 24; }
    }
    int yStride = 0, uStride = 0, vStride = 0;
    uint8_t* yDst = heif_image_get_plane(image, heif_channel_Y, &yStride);
    uint8_t* uDst = heif_image_get_plane(image, heif_channel_Cb, &uStride);
    uint8_t* vDst = heif_image_get_plane(image, heif_channel_Cr, &vStride);
    // 各线程写互不重叠的行段；相机尺寸增益图可省上百毫秒。
    parallelRows(gm.image.height, size_t(gm.image.width) * gm.image.height, [&](int yBegin, int yEnd) {
      for (int y = yBegin; y < yEnd; ++y) {
        if (compatibleRgb) {
          // Keep every map pixel and all HDR metadata. Only the color
          // representation changes; the resulting YCbCr planes encode losslessly.
          int x = 0;
#if defined(__ARM_NEON)
          for (; x + 8 <= gm.image.width; x += 8) {
            storeYuv444_8(yDst + y * yStride + x, uDst + y * uStride + x, vDst + y * vStride + x,
                          gm.image.rgba.data() + (size_t(y) * gm.image.width + x) * 4, /*bt709=*/false);
          }
#endif
          for (; x < gm.image.width; ++x) {
            yuvPixel(gm.image.rgba.data() + (size_t(y) * gm.image.width + x) * 4,
                     yDst[y * yStride + x], uDst[y * uStride + x], vDst[y * vStride + x]);
          }
          continue;
        }
        int x = 0;
#if defined(__ARM_NEON)
        for (; x + 16 <= gm.image.width; x += 16) {
          auto rgba = vld4q_u8(gm.image.rgba.data() + (size_t(y) * gm.image.width + x) * 4);
          vst1q_u8(yDst + y * yStride + x, rgba.val[1]);
          vst1q_u8(uDst + y * uStride + x, rgba.val[2]);
          vst1q_u8(vDst + y * vStride + x, rgba.val[0]);
        }
#endif
        for (; x < gm.image.width; ++x) {
          const size_t idx = (size_t(y) * gm.image.width + x) * 4;
          const int r = gm.image.rgba[idx];
          const int g = gm.image.rgba[idx + 1];
          const int b = gm.image.rgba[idx + 2];
          // Identity matrix: store G/B/R without rounding or color conversion.
          yDst[y * yStride + x] = g;
          uDst[y * uStride + x] = b;
          vDst[y * vStride + x] = r;
        }
      }
    });
  } else {
    e = heif_image_add_plane(image, heif_channel_Y, gm.image.width, gm.image.height, 8);
    if (e.code != heif_error_Ok) { err = e.message; return 24; }
    int stride = 0;
    uint8_t* dst = heif_image_get_plane(image, heif_channel_Y, &stride);
    parallelRows(gm.image.height, size_t(gm.image.width) * gm.image.height, [&](int yBegin, int yEnd) {
      for (int y = yBegin; y < yEnd; ++y) {
        rgbaToGrayFast(gm.image.rgba.data() + size_t(y) * gm.image.width * 4,
                       dst + y * stride, gm.image.width);
      }
    });
  }
  heif_color_profile_nclx nclx {};
  nclx.version = 1;
  nclx.color_primaries = heif_color_primaries_unspecified;
  nclx.transfer_characteristics = heif_transfer_characteristic_unspecified;
  nclx.matrix_coefficients = (compatibleRgb || compatibleHeic) ? heif_matrix_coefficients_ITU_R_BT_601_6
                                         : heif_matrix_coefficients_RGB_GBR;
  nclx.full_range_flag = compatibleRgb ? 0 : 1;
  heif_image_set_nclx_color_profile(image, &nclx);

  out.nclx = nclx;
  out.derived = gm.derivedNclx;
  if (out.derived == nullptr) {
    auto owned = NclxPtr(heif_nclx_color_profile_alloc());
    if (owned != nullptr) {
      heif_nclx_color_profile_set_color_primaries(owned.get(), heif_color_primaries_ITU_R_BT_2020_2_and_2100_0);
      heif_nclx_color_profile_set_transfer_characteristics(owned.get(), heif_transfer_characteristic_ITU_R_BT_2100_0_HLG);
      heif_nclx_color_profile_set_matrix_coefficients(
          owned.get(), heif_matrix_coefficients_ITU_R_BT_2020_2_non_constant_luminance);
      owned->full_range_flag = 1;
      out.derived = owned.get();
      out.ownedDerived = std::move(owned);
    }
  }
  out.compatibleHeic = compatibleHeic;
  out.image = std::move(imageOwner);
  return 0;
}

// Attaches an already-converted gain map to the encoded base. Each attempt gets
// fresh context/encoder handles; the prepared planes are only read.
int attachGainMap(heif_context* ctx, heif_image_handle* base, heif_encoder* encoder,
                  const GainMapPayload& gm, const PreparedGainMapPlanes& prepared, std::string& err) {
  const bool avif = gm.codec == 2;
  const heif_encoder_descriptor* descriptor = nullptr;
  if (heif_context_get_encoder_descriptors(ctx, avif ? heif_compression_AV1 : heif_compression_HEVC,
          avif ? "aom" : "x265", &descriptor, 1) != 1) {
    err = "缺少无损 Gain Map 编码器"; return 24;
  }
  heif_encoder* mapEncoder = nullptr;
  heif_error e = heif_context_get_encoder(ctx, descriptor, &mapEncoder);
  if (e.code != heif_error_Ok) { err = e.message; return 24; }
  std::unique_ptr<heif_encoder, decltype(&heif_encoder_release)> mapOwner(mapEncoder, heif_encoder_release);
  configureEncoder(mapEncoder, gm.codec, ultraslim_codec_speed_level, ultraslim_codec_threads);
  if (avif) {
    // 无损增益图用独立的 speed 映射（覆盖 configureEncoder 的通用值）；索引先 clamp。
    const int level = ultraslim_codec_speed_level < 0 || ultraslim_codec_speed_level > 2 ? 1 : ultraslim_codec_speed_level;
    const int speeds[] = {4, 6, 8};
    heif_encoder_set_parameter_integer(mapEncoder, "speed", speeds[level]);
  }
  e = heif_encoder_set_lossless(mapEncoder, 1);
  if (e.code != heif_error_Ok) { err = e.message; return 24; }
  heif_encoder_set_parameter(mapEncoder, "chroma", prepared.compatibleHeic ? "420" : "444");
  heif_encoding_options* gmOptions = heif_encoding_options_alloc();
  // libheif 只把该指针存进自己的 options 并在本次调用内读取；栈上的局部副本与
  // 原来每次现算一份 nclx 的行为一致，只是几何形状改由 prepare 阶段决定。
  heif_color_profile_nclx gmNclx = prepared.nclx;
  if (gmOptions != nullptr) {
    gmOptions->output_nclx_profile = &gmNclx;
  }
  heif_image_handle* gmHandle = nullptr;
  e = heif_context_encode_gain_map_image(ctx, base, mapEncoder, prepared.image.get(), gmOptions,
                                        gm.iso.data(), static_cast<int>(gm.iso.size()),
                                        prepared.derived, &gmHandle);
  if (gmOptions != nullptr) {
    heif_encoding_options_free(gmOptions);
  }
  if (gmHandle) heif_image_handle_release(gmHandle);
  if (e.code != heif_error_Ok) {
    err = e.message ? e.message : "写入 gain map 失败";
    return 24;
  }
  return 0;
}

// Encodes one attempt from planes that were converted once, before the retry loop.
int encodePreparedWithLibheif(const SdrImage& img, int outCodec, int quality, bool keepExif, int outFd,
                              int speedLevel, int threads, const GainMapPayload* gainmap,
                              const PreparedBasePlanes& prepared,
                              const PreparedGainMapPlanes* preparedMap, std::string& err,
                              const std::vector<uint8_t>* xmp) {
  heif_context* ctx = heif_context_alloc();
  heif_encoder* encoder = nullptr;
  const heif_encoder_descriptor* descriptor = nullptr;
  heif_context_get_encoder_descriptors(ctx, outCodec == 2 ? heif_compression_AV1 : heif_compression_HEVC,
      outCodec == 2 ? (prepared.oddAvif ? "aom" : "svt") : "x265", &descriptor, 1);
  heif_error e = descriptor ? heif_context_get_encoder(ctx, descriptor, &encoder) :
      heif_error{heif_error_Encoder_plugin_error, heif_suberror_Unspecified, "编码器不可用"};
  if (e.code != heif_error_Ok) {
    err = std::string("获取编码器失败: ") + (e.message ? e.message : "");
    heif_context_free(ctx);
    return 20;
  }
  heif_encoder_set_lossy_quality(encoder, outCodec == 2 ?
      ultrahdr::remapQualityForAvif(quality) : ultrahdr::remapQualityForLibheif(quality));
  heif_encoder_set_parameter(encoder, "chroma", prepared.oddAvif ? "444" : "420");
  configureEncoder(encoder, outCodec, speedLevel, threads);
  if (prepared.oddAvif && speedLevel == 2) heif_encoder_set_parameter_integer(encoder, "speed", 8);

  heif_image_handle* outHandle = nullptr;
  if(!img.icc.empty()) heif_image_set_raw_color_profile(prepared.image.get(),"prof",img.icc.data(),img.icc.size());
  // heif_encoding_options_alloc 失败时传 nullptr：libheif 会用默认选项（并自行从图像
  // 取 nclx），仍能编码，只是不再额外写第二个 colr box。绝不判空后直接解引用。
  heif_color_profile_nclx baseNclx = prepared.nclx;
  auto* encOpts=heif_encoding_options_alloc();
  if(encOpts!=nullptr) {
    encOpts->save_two_colr_boxes_when_ICC_and_nclx_available=1; encOpts->output_nclx_profile=&baseNclx;
  }
  e = heif_context_encode_image(ctx, prepared.image.get(), encoder, encOpts, &outHandle);
  if(encOpts!=nullptr) heif_encoding_options_free(encOpts);
  if (e.code != heif_error_Ok) {
    err = std::string("编码失败: ") + e.message;
    heif_encoder_release(encoder);
    heif_context_free(ctx);
    return 22;
  }
  if (keepExif && !img.exif.empty() && outHandle != nullptr) {
    size_t tiffSize = 0;
    const uint8_t* tiff = exifTiff(img.exif, tiffSize);
    if (tiffSize > 0) {
      auto exifError=heif_context_add_exif_metadata(ctx, outHandle, tiff, static_cast<int>(tiffSize));
      if(exifError.code!=heif_error_Ok) { err="EXIF 写入失败"; heif_image_handle_release(outHandle); heif_encoder_release(encoder); heif_context_free(ctx); return 28; }
    }
  }
  if (xmp != nullptr && !xmp->empty()) {
    const auto xmpError = heif_context_add_XMP_metadata(ctx, outHandle, xmp->data(), static_cast<int>(xmp->size()));
    if (xmpError.code != heif_error_Ok) {
      err = "实况 XMP 写入失败";
      heif_image_handle_release(outHandle); heif_encoder_release(encoder); heif_context_free(ctx); return 28;
    }
  }
  if (gainmap != nullptr && preparedMap != nullptr && outHandle != nullptr) {
    const int gmErr = attachGainMap(ctx, outHandle, encoder, *gainmap, *preparedMap, err);
    if (gmErr != 0) {
      heif_image_handle_release(outHandle);
      heif_encoder_release(encoder);
      heif_context_free(ctx);
      return gmErr;
    }
  }
  heif_writer writer {};
  writer.writer_api_version = 1;
  writer.write = fdWriteCallback;
  e = heif_context_write(ctx, &writer, &outFd);
  if (e.code != heif_error_Ok) {
    err = std::string("写出文件失败: ") + e.message;
    if (outHandle) heif_image_handle_release(outHandle);
    heif_encoder_release(encoder);
    heif_context_free(ctx);
    return 23;
  }
  if (outHandle) heif_image_handle_release(outHandle);
  heif_encoder_release(encoder);
  heif_context_free(ctx);
  return 0;
}

// JXL 主图帧的 RGB 缓冲（3B/px）。RGBA→RGB 与质量无关，转换一次即可供重试复用；
// libjxl 的 AddImageFrame 内部会自行复制（非流式路径），缓冲只需活到调用返回。
void prepareJxlRgb(const SdrImage& img, std::vector<uint8_t>& out) {
  const size_t pixels = static_cast<size_t>(img.width) * img.height;
  out.resize(pixels * 3);
  parallelRows(img.height, pixels, [&](int yBegin, int yEnd) {
    const size_t offset = static_cast<size_t>(yBegin) * img.width;
    const size_t count = static_cast<size_t>(yEnd - yBegin) * img.width;
    rgbaToRgbFast(img.rgba.data() + offset * 4, out.data() + offset * 3, count);
  });
}

int encodeJxl(const SdrImage& base, int quality, bool keepExif, int outFd,
              int speedLevel, int threads, GainMapPayload* gainmap,
              std::vector<uint8_t>& jxlRgb, bool retrySmaller, std::string& err) {
  const int th = threads > 0 ? threads : resolveCodecThreads(threads);

  // 重试时复用缓存的 jhgm，避免整份拷贝无损增益图码流。
  std::vector<uint8_t> jhgmLocal;
  const std::vector<uint8_t>* jhgm = gainmap ? &gainmap->encodedJxl : nullptr;
  if (gainmap != nullptr && jhgm->empty()) {
    if (gainmap->iso.empty() || gainmap->image.width <= 0 || gainmap->image.height <= 0) {
      err = "gain map 数据不完整";
      return 24;
    }

    JxlEncoder* gm_enc = JxlEncoderCreate(nullptr);
    if (!gm_enc) { err = "无法创建 JXL Gain Map 编码器"; return 24; }
    void* gm_runner = JxlThreadParallelRunnerCreate(nullptr, th);
    if (gm_runner) JxlEncoderSetParallelRunner(gm_enc, JxlThreadParallelRunner, gm_runner);
    JxlEncoderUseContainer(gm_enc, JXL_FALSE);

    JxlBasicInfo gm_info;
    JxlEncoderInitBasicInfo(&gm_info);
    gm_info.xsize = gainmap->image.width;
    gm_info.ysize = gainmap->image.height;
    gm_info.bits_per_sample = 8;
    gm_info.exponent_bits_per_sample = 0;
    gm_info.num_color_channels = gainmap->multiChannel ? 3 : 1;
    gm_info.alpha_bits = 0;
    gm_info.uses_original_profile = JXL_TRUE;
    if (JxlEncoderSetBasicInfo(gm_enc, &gm_info) != JXL_ENC_SUCCESS) {
      err = "设置 Gain Map 基本信息失败";
      JxlEncoderDestroy(gm_enc);
      if (gm_runner) JxlThreadParallelRunnerDestroy(gm_runner);
      return 24;
    }

    JxlColorEncoding gm_color;
    JxlColorEncodingSetToSRGB(&gm_color, gainmap->multiChannel ? JXL_FALSE : JXL_TRUE);
    JxlEncoderSetColorEncoding(gm_enc, &gm_color);

    JxlEncoderFrameSettings* gm_settings = JxlEncoderFrameSettingsCreate(gm_enc, nullptr);
    int gm_effort = (speedLevel == 2) ? 3 : ((speedLevel == 0) ? 7 : 5);
    JxlEncoderFrameSettingsSetOption(gm_settings, JXL_ENC_FRAME_SETTING_EFFORT, gm_effort);

    const size_t pixelCount = static_cast<size_t>(gainmap->image.width) * gainmap->image.height;
    if (JxlEncoderSetFrameLossless(gm_settings, JXL_TRUE) != JXL_ENC_SUCCESS) {
      err = "无法无损编码 Gain Map";
      JxlEncoderDestroy(gm_enc);
      if (gm_runner) JxlThreadParallelRunnerDestroy(gm_runner);
      return 24;
    }

    if (gainmap->multiChannel) {
      std::vector<uint8_t> gm_rgb(pixelCount * 3);
      parallelRows(gainmap->image.height, pixelCount, [&](int yBegin, int yEnd) {
        const size_t offset = static_cast<size_t>(yBegin) * gainmap->image.width;
        const size_t count = static_cast<size_t>(yEnd - yBegin) * gainmap->image.width;
        rgbaToRgbFast(gainmap->image.rgba.data() + offset * 4, gm_rgb.data() + offset * 3, count);
      });
      JxlPixelFormat pf = {3, JXL_TYPE_UINT8, JXL_LITTLE_ENDIAN, 0};
      if (JxlEncoderAddImageFrame(gm_settings, &pf, gm_rgb.data(), gm_rgb.size()) != JXL_ENC_SUCCESS) {
        err = "添加 Gain Map 帧失败";
        JxlEncoderDestroy(gm_enc);
        if (gm_runner) JxlThreadParallelRunnerDestroy(gm_runner);
        return 24;
      }
    } else {
      std::vector<uint8_t> gm_gray(pixelCount);
      parallelRows(gainmap->image.height, pixelCount, [&](int yBegin, int yEnd) {
        const size_t offset = static_cast<size_t>(yBegin) * gainmap->image.width;
        const size_t count = static_cast<size_t>(yEnd - yBegin) * gainmap->image.width;
        rgbaToGrayFast(gainmap->image.rgba.data() + offset * 4, gm_gray.data() + offset, count);
      });
      JxlPixelFormat pf = {1, JXL_TYPE_UINT8, JXL_LITTLE_ENDIAN, 0};
      if (JxlEncoderAddImageFrame(gm_settings, &pf, gm_gray.data(), gm_gray.size()) != JXL_ENC_SUCCESS) {
        err = "添加 Gain Map 帧失败";
        JxlEncoderDestroy(gm_enc);
        if (gm_runner) JxlThreadParallelRunnerDestroy(gm_runner);
        return 24;
      }
    }
    // AddImageFrame has copied the samples. Retries reuse the encoded jhgm below,
    // so the source gain-map RGBA need not overlap either codec's working set.
    std::vector<uint8_t>().swap(gainmap->image.rgba);
    JxlEncoderCloseInput(gm_enc);

    std::vector<uint8_t> gm_codestream;
    std::vector<uint8_t> chunk(64 * 1024);
    while (true) {
      uint8_t* next_out = chunk.data();
      size_t avail_out = chunk.size();
      JxlEncoderStatus s = JxlEncoderProcessOutput(gm_enc, &next_out, &avail_out);
      if (s == JXL_ENC_ERROR) {
        err = "编码 Gain Map codestream 失败";
        JxlEncoderDestroy(gm_enc);
        if (gm_runner) JxlThreadParallelRunnerDestroy(gm_runner);
        return 24;
      }
      gm_codestream.insert(gm_codestream.end(), chunk.data(), next_out);
      if (s == JXL_ENC_SUCCESS) break;
    }
    JxlEncoderDestroy(gm_enc);
    if (gm_runner) JxlThreadParallelRunnerDestroy(gm_runner);

    size_t jhgm_size = 1 + 2 + gainmap->iso.size() + 1 + 4 + gm_codestream.size();
    jhgmLocal.resize(jhgm_size);
    jhgmLocal[0] = 0; // version 0
    storeBE16(static_cast<uint16_t>(gainmap->iso.size()), jhgmLocal.data() + 1);
    memcpy(jhgmLocal.data() + 3, gainmap->iso.data(), gainmap->iso.size());
    size_t cursor = 3 + gainmap->iso.size();
    jhgmLocal[cursor++] = 0; // no extra color encoding
    storeBE32(0, jhgmLocal.data() + cursor); // no alt ICC
    cursor += 4;
    memcpy(jhgmLocal.data() + cursor, gm_codestream.data(), gm_codestream.size());
    if (gainmap->cacheForRetry) {
      gainmap->encodedJxl = std::move(jhgmLocal);
      jhgm = &gainmap->encodedJxl;
    } else {
      jhgm = &jhgmLocal;
    }
  }

  // Primary image
  JxlEncoder* enc = JxlEncoderCreate(nullptr);
  if (!enc) { err = "无法创建 JXL 编码器"; return 20; }
  void* runner = JxlThreadParallelRunnerCreate(nullptr, th);
  if (runner) JxlEncoderSetParallelRunner(enc, JxlThreadParallelRunner, runner);

  const bool hasBoxes = (gainmap != nullptr) || (keepExif && !base.exif.empty());
  if (hasBoxes) {
    if (JxlEncoderUseContainer(enc, JXL_TRUE) != JXL_ENC_SUCCESS ||
        JxlEncoderUseBoxes(enc) != JXL_ENC_SUCCESS) {
      err = "无法创建 JXL 元数据容器";
      JxlEncoderDestroy(enc);
      if (runner) JxlThreadParallelRunnerDestroy(runner);
      return 28;
    }
  }

  JxlBasicInfo basic_info;
  JxlEncoderInitBasicInfo(&basic_info);
  basic_info.xsize = base.width;
  basic_info.ysize = base.height;
  basic_info.bits_per_sample = 8;
  basic_info.exponent_bits_per_sample = 0;
  basic_info.num_color_channels = 3;
  basic_info.alpha_bits = 0;
  if (JxlEncoderSetBasicInfo(enc, &basic_info) != JXL_ENC_SUCCESS) {
    err = "设置主图基本信息失败";
    JxlEncoderDestroy(enc);
    if (runner) JxlThreadParallelRunnerDestroy(runner);
    return 21;
  }

  if (!base.icc.empty()) {
    JxlEncoderSetICCProfile(enc, base.icc.data(), base.icc.size());
  } else {
    JxlColorEncoding color_encoding;
    JxlColorEncodingSetToSRGB(&color_encoding, JXL_FALSE);
    if (base.hasProfile && base.profile.color_primaries == heif_color_primaries_SMPTE_EG_432_1) {
      color_encoding.primaries = JXL_PRIMARIES_P3;
    }
    JxlEncoderSetColorEncoding(enc, &color_encoding);
  }

  if (keepExif && !base.exif.empty()) {
    size_t tiffSize = 0;
    const uint8_t* tiffData = exifTiff(base.exif, tiffSize);
    if (tiffSize > 0) {
      std::vector<uint8_t> exifBox(4 + tiffSize, 0);
      memcpy(exifBox.data() + 4, tiffData, tiffSize);
      if (JxlEncoderAddBox(enc, "Exif", exifBox.data(), exifBox.size(), JXL_FALSE) != JXL_ENC_SUCCESS) {
        err = "EXIF 写入失败";
        JxlEncoderDestroy(enc);
        if (runner) JxlThreadParallelRunnerDestroy(runner);
        return 28;
      }
    }
  }

  if (jhgm != nullptr && !jhgm->empty()) {
    if (JxlEncoderAddBox(enc, "jhgm", jhgm->data(), jhgm->size(), JXL_FALSE) != JXL_ENC_SUCCESS) {
      err = "JXL 增益图写入失败";
      JxlEncoderDestroy(enc);
      if (runner) JxlThreadParallelRunnerDestroy(runner);
      return 24;
    }
  }

  if (hasBoxes) JxlEncoderCloseBoxes(enc);

  JxlEncoderFrameSettings* settings = JxlEncoderFrameSettingsCreate(enc, nullptr);
  int effort = (speedLevel == 2) ? 3 : ((speedLevel == 0) ? 7 : 5);
  JxlEncoderFrameSettingsSetOption(settings, JXL_ENC_FRAME_SETTING_EFFORT, effort);
  float distance = JxlEncoderDistanceFromQuality(static_cast<float>(quality));
  JxlEncoderSetFrameDistance(settings, distance);

  const size_t totalPixels = static_cast<size_t>(base.width) * base.height;
  JxlPixelFormat pf = {3, JXL_TYPE_UINT8, JXL_LITTLE_ENDIAN, 0};
  if (jxlRgb.size() != totalPixels * 3 ||
      JxlEncoderAddImageFrame(settings, &pf, jxlRgb.data(), jxlRgb.size()) != JXL_ENC_SUCCESS) {
    err = "添加主图帧失败";
    JxlEncoderDestroy(enc);
    if (runner) JxlThreadParallelRunnerDestroy(runner);
    return 21;
  }
  // Keep only the RGB needed by a possible second encode. The encoder owns its copy.
  if (!retrySmaller) std::vector<uint8_t>().swap(jxlRgb);
  JxlEncoderCloseInput(enc);

  std::vector<uint8_t> chunk(64 * 1024);
  while (true) {
    uint8_t* next_out = chunk.data();
    size_t avail_out = chunk.size();
    JxlEncoderStatus s = JxlEncoderProcessOutput(enc, &next_out, &avail_out);
    if (s == JXL_ENC_ERROR) {
      err = "JXL 编码失败";
      JxlEncoderDestroy(enc);
      if (runner) JxlThreadParallelRunnerDestroy(runner);
      return 20;
    }
    size_t bytes_to_write = chunk.size() - avail_out;
    if (bytes_to_write > 0) {
      if (!writeAll(outFd, chunk.data(), bytes_to_write)) {
        err = "写入输出文件失败";
        JxlEncoderDestroy(enc);
        if (runner) JxlThreadParallelRunnerDestroy(runner);
        return 30;
      }
    }
    if (s == JXL_ENC_SUCCESS) break;
  }
  JxlEncoderDestroy(enc);
  if (runner) JxlThreadParallelRunnerDestroy(runner);
  return 0;
}

// ------------------------------------------------------------------- uhdr HDR

// A declared but unreadable gain map must never be silently published as SDR.
// Inspect metadata parsed from JPEG segments, not arbitrary strings in pixel data.
bool jpegDeclaresHdr(BytesView data) {
  auto declared = [](const void* xmp, size_t size, size_t isoSize) {
    if (isoSize > 0) return true;
    if (!xmp || !size) return false;
    const std::string text(static_cast<const char*>(xmp), size);
    return text.find("http://ns.adobe.com/hdr-gain-map/1.0/") != std::string::npos ||
           text.find("HDRGainMapVersion") != std::string::npos;
  };
  ultrahdr::JpegDecoderHelper primary;
  if (primary.parseImage(data.data(), data.size()).error_code == UHDR_CODEC_OK &&
      declared(primary.getXMPPtr(), primary.getXMPSize(), primary.getIsoMetadataSize())) return true;
  uhdr_compressed_image_t input{};
  input.data = const_cast<uint8_t*>(data.data()); input.data_sz = input.capacity = data.size();
  ultrahdr::jpeg_info_struct base{}, map{};
  ultrahdr::jpegr_info_struct info{}; info.primaryImgInfo = &base; info.gainmapImgInfo = &map;
  ultrahdr::JpegR parser;
  return parser.getJPEGRInfo(&input, &info).error_code == UHDR_CODEC_OK &&
         declared(map.xmpData.data(), map.xmpData.size(), map.isoData.size());
}

// Probes with the uhdr decoder; returns true when the image parses and carries a gain map.
bool probeWithUhdr(BytesView data, ProbeResult* probe, TranscodeResult* tr,
                   DecoderPtr* outDecoder = nullptr) {
  DecoderPtr dec(uhdr_create_decoder());
  if (!dec) return false;
  uhdr_compressed_image_t img {};
  img.data = const_cast<uint8_t*>(data.data());
  img.data_sz = img.capacity = data.size();
  img.cg = UHDR_CG_UNSPECIFIED;
  img.ct = UHDR_CT_UNSPECIFIED;
  img.range = UHDR_CR_UNSPECIFIED;
  if (uhdr_dec_set_image(dec.get(), &img).error_code != UHDR_CODEC_OK) return false;
  const auto status = uhdr_dec_probe(dec.get());
  if (status.error_code != UHDR_CODEC_OK) {
    if (probe && sniffContainer(data.data(), data.size()) == 0 && jpegDeclaresHdr(data)) {
      probe->errorCode = 4;
      probe->error = describe(status, "HDR 增益图损坏或元数据不受支持，请保留原图");
    }
    return false;
  }
  const int w = uhdr_dec_get_image_width(dec.get());
  const int h = uhdr_dec_get_image_height(dec.get());
  const int gw = uhdr_dec_get_gainmap_width(dec.get());
  const int gh = uhdr_dec_get_gainmap_height(dec.get());
  const bool hasGainMap = gw > 0 && gh > 0;
  if (!hasGainMap) return false;
  if (w <= 0 || h <= 0 || static_cast<int64_t>(w) * h > kMaxPixels ||
      static_cast<int64_t>(gw) * gh > kMaxPixels) {
    if (probe) { probe->errorCode = 4; probe->error = "HDR 图像超过安全尺寸限制"; }
    return false;
  }
  if (probe != nullptr) {
    probe->width = w;
    probe->height = h;
    probe->gainmapWidth = gw;
    probe->gainmapHeight = gh;
    probe->hasGainMap = true;
    if (uhdr_mem_block_t* exif = uhdr_dec_get_exif(dec.get())) {
      probe->hasExif = exif->data != nullptr && exif->data_sz > 0;
    }
    if (uhdr_gainmap_metadata_t* meta = uhdr_dec_get_gainmap_metadata(dec.get())) {
      probe->maxContentBoost = meta->max_content_boost[0];
      probe->hdrCapacityMax = meta->hdr_capacity_max;
    }
  }
  if (tr != nullptr) {
    tr->width = w;
    tr->height = h;
    tr->gainmapWidth = gw;
    tr->gainmapHeight = gh;
    tr->inputHadGainMap = true;
  }
  if (outDecoder != nullptr) *outDecoder = std::move(dec);
  return true;
}

std::vector<uint8_t> isoFromMetadata(const uhdr_gainmap_metadata_t& meta) {
  uhdr_gainmap_metadata_t copy = meta;
  ultrahdr::uhdr_gainmap_metadata_ext_t ext(copy, ultrahdr::kJpegrVersion);
  ultrahdr::uhdr_gainmap_metadata_frac frac {};
  if (ultrahdr::uhdr_gainmap_metadata_frac::gainmapMetadataFloatToFraction(&ext, &frac).error_code !=
      UHDR_CODEC_OK) {
    return {};
  }
  std::vector<uint8_t> out;
  if (ultrahdr::uhdr_gainmap_metadata_frac::encodeGainmapMetadata(&frac, out).error_code !=
      UHDR_CODEC_OK) {
    return {};
  }
  return out;
}

std::vector<uint8_t> copyBlock(const uhdr_mem_block_t* block) {
  if (block == nullptr || block->data == nullptr || block->data_sz == 0) return {};
  const auto* bytes = static_cast<const uint8_t*>(block->data);
  return {bytes, bytes + block->data_sz};
}

bool looksLikeJpeg(const std::vector<uint8_t>& bytes) {
  return bytes.size() >= 2 && bytes[0] == 0xFF && bytes[1] == 0xD8;
}

int muxJpegUltraHdr(const std::vector<uint8_t>& baseJpeg, const std::vector<uint8_t>& gmJpeg,
                    uhdr_gainmap_metadata_t* meta, int outFd, const std::vector<uint8_t>& exif, std::string& err) {
  EncoderPtr enc(uhdr_create_encoder());
  if (!enc || meta == nullptr) return -1;
  uhdr_compressed_image_t base {};
  base.data = const_cast<uint8_t*>(baseJpeg.data());
  base.data_sz = base.capacity = baseJpeg.size();
  base.cg = UHDR_CG_BT_709;
  base.ct = UHDR_CT_SRGB;
  base.range = UHDR_CR_LIMITED_RANGE;
  uhdr_compressed_image_t gm {};
  gm.data = const_cast<uint8_t*>(gmJpeg.data());
  gm.data_sz = gm.capacity = gmJpeg.size();
  gm.cg = UHDR_CG_BT_709;
  gm.ct = UHDR_CT_UNSPECIFIED;
  gm.range = UHDR_CR_FULL_RANGE;
  if (uhdr_enc_set_compressed_image(enc.get(), &base, UHDR_BASE_IMG).error_code != UHDR_CODEC_OK) {
    return -1;
  }
  if (uhdr_enc_set_gainmap_image(enc.get(), &gm, meta).error_code != UHDR_CODEC_OK) return -1;
  if (uhdr_enc_set_output_format(enc.get(), UHDR_CODEC_JPG).error_code != UHDR_CODEC_OK) return -1;
  if (!exif.empty()) { uhdr_mem_block_t block{}; block.data=const_cast<uint8_t*>(exif.data()); block.data_sz=block.capacity=exif.size();
    if(uhdr_enc_set_exif_data(enc.get(),&block).error_code!=UHDR_CODEC_OK)return -1; }
  const auto encoded = uhdr_encode(enc.get());
  if (encoded.error_code != UHDR_CODEC_OK) { err = describe(encoded, "JPEG Gain Map 封装失败"); return -1; }
  uhdr_compressed_image_t* out = uhdr_get_encoded_stream(enc.get());
  if (out == nullptr || out->data == nullptr || out->data_sz == 0) return -1;
  return writeAll(outFd, out->data, out->data_sz) ? 0 : -1;
}

// 0 = preserved and wrote output; -1 = unsupported. Never silently rebuild the gain map.
int tryReuseGainMap(std::vector<uint8_t>& data, int container, DecoderPtr& decOwner,
                    const TranscodeOptions& opt, int outFd, TranscodeResult& r) {
  uhdr_codec_private_t* dec = decOwner.get();
  uhdr_gainmap_metadata_t* rawMeta = dec ? uhdr_dec_get_gainmap_metadata(dec) : nullptr;
  uhdr_gainmap_metadata_t metaStorage {};
  if (rawMeta != nullptr) metaStorage = *rawMeta;
  uhdr_gainmap_metadata_t* meta = rawMeta ? &metaStorage : nullptr;
  const int srcW = dec ? uhdr_dec_get_image_width(dec) : r.width;
  const int srcH = dec ? uhdr_dec_get_image_height(dec) : r.height;
  const int gmW = dec ? uhdr_dec_get_gainmap_width(dec) : r.gainmapWidth;
  const int gmH = dec ? uhdr_dec_get_gainmap_height(dec) : r.gainmapHeight;
  if (container != 3 && (meta == nullptr || srcW < 1 || srcH < 1 || gmW < 1 || gmH < 1)) {r.error="原始增益图参数不完整";return -1;}

  SdrImage base, gm;
  bool multiChannel = false;
  std::vector<uint8_t> originalMapJpeg;
  std::string err;
  std::vector<uint8_t> iso;
  heif_color_profile_nclx* derived = nullptr;

  if (container == 0) {
    std::vector<uint8_t> baseBytes = copyBlock(uhdr_dec_get_base_image(dec));
    std::vector<uint8_t> gmBytes = copyBlock(uhdr_dec_get_gainmap_image(dec));
    auto baseIcc = copyBlock(uhdr_dec_get_icc(dec));
    // 主图/增益图/ICC 已提取完毕；在展开 RGBA 像素前释放 UHDR 解码器与完整输入缓冲。
    decOwner.reset();
    dec = nullptr;
    data.clear();
    data.shrink_to_fit();
    if (!looksLikeJpeg(baseBytes) || !looksLikeJpeg(gmBytes)) {r.error="无法读取原始 JPEG 主图或增益图";return -1;}
    const int channels=jpegChannels(gmBytes);
    if(channels==0) {r.error="无法读取原始增益图通道";return -1;}
    multiChannel = channels > 1;
    const int targetLong =
        opt.resizePercent >= 100 ? 0 : std::max(2, std::max(srcW, srcH) * opt.resizePercent / 100);
    gm.width = gmW; gm.height = gmH;
    if (!decodeJpegSdr(baseBytes, base, err, targetLong)) {
      r.error="读取原始 Gain Map 失败："+err;return -1;
    }
    baseBytes.clear();
    baseBytes.shrink_to_fit();
    if (opt.outCodec != 0 && !decodeJpegSdr(gmBytes, gm, err)) {
      r.error="读取原始 Gain Map 失败："+err;return -1;
    }
    ultrahdr::JpegDecoderHelper mapParser;
    if (mapParser.parseImage(gmBytes.data(), gmBytes.size()).error_code == UHDR_CODEC_OK && mapParser.getIsoMetadataSize() > 0) {
      const auto* bytes = static_cast<const uint8_t*>(mapParser.getIsoMetadataPtr());
      constexpr size_t namespaceSize = sizeof("urn:iso:std:iso:ts:21496:-1");
      if (mapParser.getIsoMetadataSize() > namespaceSize)
        iso.assign(bytes + namespaceSize, bytes + mapParser.getIsoMetadataSize());
      ultrahdr::uhdr_gainmap_metadata_frac parsed{};
      if (ultrahdr::uhdr_gainmap_metadata_frac::decodeGainmapMetadata(iso, &parsed).error_code != UHDR_CODEC_OK)
        iso.clear(); // XMP fallback must not republish the rejected ISO block.
    }
    if (iso.empty()) iso = isoFromMetadata(*meta);
    // The derived HDR rendition must keep the source application gamut too.
    void* iccData = meta->use_base_cg ? static_cast<void*>(baseIcc.data()) : mapParser.getICCPtr();
    size_t iccSize = meta->use_base_cg ? baseIcc.size() : mapParser.getICCSize();
    auto gamut = iccSize == 0 && meta->use_base_cg ? UHDR_CG_BT_709 : getGamutFromIcc(static_cast<const uint8_t*>(iccData), iccSize);
    if (gamut == UHDR_CG_UNSPECIFIED) { r.error = "无法原样保留此 HDR 色彩空间，请保留原图"; return -1; }
    if (opt.outCodec == 0) {
      originalMapJpeg = std::move(gmBytes);
    } else {
      gmBytes.clear();
      gmBytes.shrink_to_fit();
    }
    derived = heif_nclx_color_profile_alloc();
    if (!derived) return -1;
    heif_nclx_color_profile_set_color_primaries(derived, gamut == UHDR_CG_DISPLAY_P3 ? heif_color_primaries_SMPTE_EG_432_1 :
        gamut == UHDR_CG_BT_2100 ? heif_color_primaries_ITU_R_BT_2020_2_and_2100_0 : heif_color_primaries_ITU_R_BT_709_5);
    heif_nclx_color_profile_set_transfer_characteristics(derived, heif_transfer_characteristic_ITU_R_BT_2100_0_HLG);
    heif_nclx_color_profile_set_matrix_coefficients(
        derived,
        gamut == UHDR_CG_DISPLAY_P3 ? heif_matrix_coefficients_chromaticity_derived_non_constant_luminance :
        gamut == UHDR_CG_BT_2100 ? heif_matrix_coefficients_ITU_R_BT_2020_2_non_constant_luminance :
        heif_matrix_coefficients_ITU_R_BT_709_5);
    derived->full_range_flag = 1;
  } else if (container == 3) {
    std::vector<uint8_t> jhgm;
    const bool baseDecoded = decodeJxlToSdr(data, base, &jhgm, err);
    if (base.hasAlpha) {
      setUnsupportedAlphaError(r);
      return -1;
    }
    if (!baseDecoded) {
      r.error = "读取原始 JXL 图像失败: " + err;
      return -1;
    }
    decOwner.reset();
    dec = nullptr;
    data.clear();
    data.shrink_to_fit();
    JhgmLayout layout;
    if (!parseJhgmLayout(BytesView(jhgm), layout)) {
      r.error = "JXL 增益图布局异常";
      return -1;
    }
    iso.assign(layout.iso.data(), layout.iso.data() + layout.iso.size());
    std::vector<uint8_t> gm_stream(layout.codestream.data(), layout.codestream.data() + layout.codestream.size());
    jhgm.clear();
    jhgm.shrink_to_fit();
    if (!decodeJxlToSdr(gm_stream, gm, nullptr, err)) {
      r.error = "解码 JXL 增益图失败: " + err;
      return -1;
    }
    gm_stream.clear();
    gm_stream.shrink_to_fit();
    multiChannel = gm.colorChannels > 1;
    derived = heif_nclx_color_profile_alloc();
    if (derived) {
      heif_nclx_color_profile_set_color_primaries(derived, heif_color_primaries_ITU_R_BT_2020_2_and_2100_0);
      heif_nclx_color_profile_set_transfer_characteristics(derived, heif_transfer_characteristic_ITU_R_BT_2100_0_HLG);
      heif_nclx_color_profile_set_matrix_coefficients(
          derived, heif_matrix_coefficients_ITU_R_BT_2020_2_non_constant_luminance);
      derived->full_range_flag = 1;
    }
  } else {
    heif_context* ctx = heif_context_alloc();
    heif_error e = heif_context_read_from_memory_without_copy(ctx, data.data(), data.size(), nullptr);
    if (e.code != heif_error_Ok) {
      heif_context_free(ctx);
      return -1;
    }
    heif_image_handle* handle = nullptr;
    e = heif_context_get_primary_image_handle(ctx, &handle);
    if (e.code == heif_error_Ok && handle != nullptr && heif_image_handle_has_alpha_channel(handle)) {
      setUnsupportedAlphaError(r);
      heif_image_handle_release(handle);
      heif_context_free(ctx);
      return -1;
    }
    heif_image_handle* gmHandle = nullptr;
    if (e.code == heif_error_Ok && handle != nullptr) {
      e = heif_image_handle_get_gain_map_image_handle(handle, &gmHandle);
    }
    bool decoded = false;
    if (e.code == heif_error_Ok && handle != nullptr && gmHandle != nullptr) {
      const int lumaDepth = heif_image_handle_get_luma_bits_per_pixel(gmHandle);
      const int chromaDepth = heif_image_handle_get_chroma_bits_per_pixel(gmHandle);
      heif_colorspace sourceSpace = heif_colorspace_undefined;
      heif_chroma sourceChroma = heif_chroma_undefined;
      const auto layout = heif_image_handle_get_preferred_decoding_colorspace(gmHandle, &sourceSpace, &sourceChroma);
      bool reusedMap = false;
      bool highBitDepth = lumaDepth > 8 || chromaDepth > 8;
      const bool knownLayout = layout.code == heif_error_Ok && sourceChroma != heif_chroma_undefined &&
          lumaDepth > 0 && (sourceChroma == heif_chroma_monochrome || chromaDepth > 0);
      if (knownLayout) {
        // Header inspection is sufficient: the pixels only need decoding once below.
        multiChannel = sourceChroma != heif_chroma_monochrome;
      } else {
        heif_image* nativeMap = nullptr;
        const auto mapError = heif_decode_image(gmHandle, &nativeMap, heif_colorspace_undefined, heif_chroma_undefined, nullptr);
        if (nativeMap) {
          multiChannel = heif_image_get_chroma_format(nativeMap) != heif_chroma_monochrome;
          for (auto channel : {heif_channel_Y, heif_channel_Cb, heif_channel_Cr, heif_channel_R, heif_channel_G, heif_channel_B})
            if (heif_image_has_channel(nativeMap, channel)) highBitDepth |= heif_image_get_bits_per_pixel_range(nativeMap, channel) > 8;
          reusedMap = !highBitDepth && mapError.code == heif_error_Ok && copyNativeGainMap(nativeMap, gm);
          heif_image_release(nativeMap);
        }
        if (mapError.code != heif_error_Ok) {
          heif_image_handle_release(gmHandle); heif_image_handle_release(handle); heif_context_free(ctx); return -1;
        }
      }
      if (highBitDepth) {
        r.error = "此增益图超过 8 位，当前编码器无法原样保留，已保留原图";
        heif_image_handle_release(gmHandle); heif_image_handle_release(handle); heif_context_free(ctx); return -1;
      }
      const size_t metaSize = heif_image_handle_get_gain_map_metadata_size(handle);
      if (metaSize > 0) {
        iso.resize(metaSize);
        if (heif_image_handle_get_gain_map_metadata(handle, iso.data()).code != heif_error_Ok) {
          iso.clear();
        }
      }
      heif_color_profile_nclx* got = nullptr;
      if (heif_image_handle_get_derived_image_nclx_color_profile(handle, &got).code != heif_error_Ok) {
        if (got != nullptr) heif_nclx_color_profile_free(got);
        derived = nullptr;
      } else {
        derived = got;
      }
      int targetWidth = 0, targetHeight = 0;
      if (opt.resizePercent < 100) scaledSize(srcW, srcH, opt.resizePercent, targetWidth, targetHeight);
      decoded = decodeHeifHandle(handle, base, err, true, 0, UINT64_MAX, targetWidth, targetHeight) &&
          (reusedMap || decodeHeifHandle(gmHandle, gm, err, false));
    }
    if (gmHandle) heif_image_handle_release(gmHandle);
    if (handle) heif_image_handle_release(handle);
    heif_context_free(ctx);
    decOwner.reset();
    dec = nullptr;
    data.clear();
    data.shrink_to_fit();
    if (!decoded) {
      if (derived) heif_nclx_color_profile_free(derived);
      return -1;
    }
    if (iso.empty() && meta) iso = isoFromMetadata(*meta);
  }

  if (iso.empty() && opt.outCodec != 0) {
    if (derived) heif_nclx_color_profile_free(derived);
    return -1;
  }

  // Source dimensions, not the JPEG-scaled buffer: libjpeg already shrank toward this size.
  int bw = 0, bh = 0;
  scaledSize(srcW, srcH, opt.resizePercent, bw, bh);
  maybeDownscale(base, bw, bh);
  // The preserved map is kept at source resolution even when the base is resized:
  // any map-to-base sampling factor is legal per ISO 21496-1, while naively
  // downsampling a fine map destroys highlight boost. Scale changes go through
  // the rebuild path instead.
  const int orientation = container == 0 ? ExifEditor(base.exif).orientation() : 1;
  const bool retainJpegOrientation = opt.outCodec == 0 && !originalMapJpeg.empty();
  if(container==0 && !retainJpegOrientation){orientPixels(base,orientation);orientPixels(gm,orientation);}
  ExifEditor(base.exif).normalize(base.width,base.height,opt.keepLocation, retainJpegOrientation ? orientation : 1);
  if (!opt.keepExif) {
    base.exif.clear();
    if (retainJpegOrientation && orientation != 1) {
      base.exif = {'E','x','i','f',0,0,'I','I',42,0,8,0,0,0,1,0,0x12,1,3,0,1,0,0,0,static_cast<uint8_t>(orientation),0,0,0,0,0,0,0};
    }
  }
  const int outW = base.width, outH = base.height, outGmW = gm.width, outGmH = gm.height;

  // Resolve metadata before moving iso into the encoder's retry payload. JXL
  // inputs have no UHDR decoder, so inspecting the moved-from vector loses boost.
  float maxContentBoost = meta ? meta->max_content_boost[0] : 1.0f;
  if (!meta) {
    ultrahdr::uhdr_gainmap_metadata_frac frac {};
    ultrahdr::uhdr_gainmap_metadata_ext_t ext {};
    if (ultrahdr::uhdr_gainmap_metadata_frac::decodeGainmapMetadata(iso, &frac).error_code == UHDR_CODEC_OK &&
        ultrahdr::uhdr_gainmap_metadata_frac::gainmapMetadataFractionToFloat(&frac, &ext).error_code == UHDR_CODEC_OK) {
      maxContentBoost = ext.max_content_boost[0];
    }
  }

  GainMapRetryScope mapCache(opt.retrySmaller);
  int rc = -1;
  if (opt.outCodec == 0) {
    if (originalMapJpeg.empty()) {
      if (derived) heif_nclx_color_profile_free(derived);
      r.error = "JPEG 无法无损保存此增益图，请选择 HEIC 或 AVIF 或 JXL";
      return -1;
    }
    if(!prepareJpegProfile(base)) { r.error="无法保留 JPEG 色彩配置";if (derived) heif_nclx_color_profile_free(derived); return -1; }
    rc = encodeWithRetry(outFd, r.inputBytes, opt.quality, opt.retrySmaller, [&](int quality) {
      const auto baseJpeg = encodeJpegToMem(base, quality, !base.exif.empty());
      return baseJpeg.empty() ? -1 : muxJpegUltraHdr(baseJpeg, originalMapJpeg, meta, outFd, base.exif, err);
    });
  } else if (opt.outCodec == 3) {
    GainMapPayload payload;
    payload.cacheForRetry = opt.retrySmaller;
    payload.image = std::move(gm);
    payload.iso = std::move(iso);
    payload.codec = opt.outCodec;
    payload.multiChannel = multiChannel;
    payload.derivedNclx = derived;
    // RGBA→RGB 与质量无关：转换一次后释放 base 的 RGBA，重试只重跑编码器。
    std::vector<uint8_t> jxlRgb;
    prepareJxlRgb(base, jxlRgb);
    base.rgba.clear();
    base.rgba.shrink_to_fit();
    rc = encodeWithRetry(outFd, r.inputBytes, opt.quality, opt.retrySmaller, [&](int quality) {
      return encodeJxl(base, quality, opt.keepExif, outFd, opt.speedLevel,
                        ultraslim_codec_threads, &payload, jxlRgb, opt.retrySmaller, err);
    });
  } else {
    GainMapPayload payload;
    payload.cacheForRetry = opt.retrySmaller;
    payload.avifGainmapCompatibility = opt.avifGainmapCompatibility;
    payload.heicGainmapCompatibility = opt.heicGainmapCompatibility;
    payload.image = std::move(gm);
    payload.iso = std::move(iso);
    payload.codec = opt.outCodec;
    payload.multiChannel = multiChannel;
    payload.derivedNclx = derived;
    // RGBA→YUV 与质量无关：转换一次后立即释放两份 RGBA，重试只重跑编码器。
    // 顺序刻意串行，让 base/gm 的平面不与对方的 RGBA 叠加。
    PreparedBasePlanes basePlanes;
    const int baseErr = prepareBasePlanes(base, opt.outCodec, true, basePlanes, err);
    if (baseErr != 0) {
      if (derived) heif_nclx_color_profile_free(derived);
      r.error = "无法保留 Gain Map：" + err;
      return -1;
    }
    base.rgba.clear();
    base.rgba.shrink_to_fit();
    PreparedGainMapPlanes mapPlanes;
    const int mapErr = prepareGainMapPlanes(payload, mapPlanes, err);
    if (mapErr != 0) {
      if (derived) heif_nclx_color_profile_free(derived);
      r.error = "无法保留 Gain Map：" + err;
      return -1;
    }
    payload.image.rgba.clear();
    payload.image.rgba.shrink_to_fit();
    rc = encodeWithRetry(outFd, r.inputBytes, opt.quality, opt.retrySmaller, [&](int quality) {
      return encodePreparedWithLibheif(base, opt.outCodec, quality, opt.keepExif, outFd,
                                       opt.speedLevel, ultraslim_codec_threads, &payload,
                                       basePlanes, &mapPlanes, err, &opt.xmp);
    });
  }
  if (derived) heif_nclx_color_profile_free(derived);
  if (rc != 0) { if (!err.empty()) r.error = "无法保留 Gain Map：" + err; return -1; }
  r.width = outW;
  r.height = outH;
  r.gainmapWidth = outGmW;
  r.gainmapHeight = outGmH;
  r.inputHadGainMap = true;
  // 输出的增益图元数据与输入一致，直接报告而非整读输出文件再 probe。
  r.hasGainMap = true;
  r.hasExif = !base.exif.empty();
  r.maxContentBoost = maxContentBoost;
  return 0;
}

}  // namespace

ProbeResult probeViews(BytesView data, DecoderPtr* outDecoder = nullptr) {
  ProbeResult r;
  if (data.empty()) {
    r.errorCode = 1;
    r.error = "无法读取图片数据";
    return r;
  }
  r.container = sniffContainer(data.data(), data.size());
  if (r.container < 0) {
    r.errorCode = 2;
    r.error = "不支持的图片格式";
    return r;
  }
  if (r.container == 3) {
    if (!probeJxl(data, r)) {
      if (r.errorCode == 0) r.errorCode = 4;
      if (r.error.empty()) r.error = "解析 JXL 图像失败";
    }
    return r;
  }
  if (probeWithUhdr(data, &r, nullptr, outDecoder)) {
    if (r.container != 0) { int w, h; probeHeifDims(data, w, h, r.hasExif); }
    return r;  // gain map image
  }
  if (r.errorCode) return r;
  // SDR fallback: plain JPEG / HEIF / AVIF without a gain map
  bool ok = false;
  if (r.container == 0) {
    ok = probeJpegDims(data, r.width, r.height, r.hasExif);
  } else {
    ok = probeHeifDims(data, r.width, r.height, r.hasExif);
  }
  if (!ok) {
    r.errorCode = 4;
    r.error = "解析图像失败";
    return r;
  }
  return r;
}

ProbeResult probeImageMemory(const std::vector<uint8_t>& data, DecoderPtr* outDecoder = nullptr) {
  return probeViews(BytesView(data), outDecoder);
}

std::vector<uint8_t> extractHeifPrimaryXmp(heif_context* ctx) {
  heif_image_handle* raw = nullptr;
  if (heif_context_get_primary_image_handle(ctx, &raw).code != heif_error_Ok || !raw) return {};
  std::unique_ptr<heif_image_handle, decltype(&heif_image_handle_release)> handle(raw, heif_image_handle_release);
  const int count = heif_image_handle_get_number_of_metadata_blocks(raw, "mime");
  if (count <= 0 || count > 1024) return {};
  std::vector<heif_item_id> ids(count);
  const int got = heif_image_handle_get_list_of_metadata_block_IDs(raw, "mime", ids.data(), count);
  for (int i = 0; i < got; ++i) {
    const char* type = heif_image_handle_get_metadata_content_type(raw, ids[i]);
    if (!type || strcmp(type, "application/rdf+xml") != 0) continue;
    const size_t size = heif_image_handle_get_metadata_size(raw, ids[i]);
    if (size == 0 || size > 1024 * 1024) return {};
    std::vector<uint8_t> bytes(size);
    if (heif_image_handle_get_metadata(raw, ids[i], bytes.data()).code == heif_error_Ok) return bytes;
  }
  return {};
}

// Read the primary item XMP for motion-photo detection.
std::vector<uint8_t> readHeifXmp(int fd) {
  HeifFdReader reader(fd);
  if (reader.open()) {
    const int container = reader.container();
    if (container != 1 && container != 2) return {};
    std::unique_ptr<heif_context, decltype(&heif_context_free)> ctx(heif_context_alloc(), heif_context_free);
    if (ctx && heif_context_read_from_reader(ctx.get(), &HeifFdReader::callbacks(), &reader, nullptr).code == heif_error_Ok) {
      return extractHeifPrimaryXmp(ctx.get());
    }
  }
  auto data = readAll(fd);
  if (data.empty()) return {};
  const int container = sniffContainer(data.data(), data.size());
  if (container != 1 && container != 2) return {};
  std::unique_ptr<heif_context, decltype(&heif_context_free)> ctx(heif_context_alloc(), heif_context_free);
  if (!ctx || heif_context_read_from_memory_without_copy(ctx.get(), data.data(), data.size(), nullptr).code != heif_error_Ok) return {};
  return extractHeifPrimaryXmp(ctx.get());
}

// Mirror the vendored uhdr_dec_probe HEIF branch. A gain-map handle establishes
// HDR even if its ISO metadata is missing/malformed; in that case UHDR reports
// zeroed boosts. Keep JPEG/JXL on their existing parsers.
ProbeResult probeHeifReader(HeifFdReader& reader, int container) {
  ProbeResult r;
  r.container = container;
  reader.position = 0;
  std::unique_ptr<heif_context, decltype(&heif_context_free)> ctx(heif_context_alloc(), heif_context_free);
  if (!ctx || heif_context_read_from_reader(ctx.get(), &HeifFdReader::callbacks(), &reader, nullptr).code != heif_error_Ok) {
    r.errorCode = 4; r.error = "解析图像失败"; return r;
  }
  heif_image_handle* raw = nullptr;
  if (heif_context_get_primary_image_handle(ctx.get(), &raw).code != heif_error_Ok || !raw) {
    r.errorCode = 4; r.error = "解析图像失败"; return r;
  }
  std::unique_ptr<heif_image_handle, decltype(&heif_image_handle_release)> handle(raw, heif_image_handle_release);
  r.width = heif_image_handle_get_width(raw); r.height = heif_image_handle_get_height(raw);
  heif_item_id id;
  r.hasExif = heif_image_handle_get_list_of_metadata_block_IDs(raw, "Exif", &id, 1) > 0;
  // UHDR only takes the HEIF path for a supported/maybe file type.
  uint8_t header[64] {};
  const size_t length = std::min<int64_t>(sizeof(header), reader.size);
  reader.position = 0;
  if (reader.read(header, length) != 0) { r.errorCode = 4; r.error = "解析图像失败"; return r; }
  const auto type = heif_check_filetype(header, int(length));
  if (type != heif_filetype_yes_supported && type != heif_filetype_maybe) return r;
  heif_image_handle* gm = nullptr;
  if (heif_image_handle_get_gain_map_image_handle(raw, &gm).code == heif_error_Ok && gm) {
    std::unique_ptr<heif_image_handle, decltype(&heif_image_handle_release)> gainmap(gm, heif_image_handle_release);
    const int gw = heif_image_handle_get_width(gm), gh = heif_image_handle_get_height(gm);
    if (gw > 0 && gh > 0) {
      r.hasGainMap = true; r.gainmapWidth = gw; r.gainmapHeight = gh;
      r.maxContentBoost = r.hdrCapacityMax = 0.0f;
      const size_t size = heif_image_handle_get_gain_map_metadata_size(raw);
      if (size > 0) {
        std::vector<uint8_t> bytes(size);
        ultrahdr::uhdr_gainmap_metadata_frac fraction;
        ultrahdr::uhdr_gainmap_metadata_ext_t metadata;
        if (heif_image_handle_get_gain_map_metadata(raw, bytes.data()).code == heif_error_Ok &&
            ultrahdr::uhdr_gainmap_metadata_frac::decodeGainmapMetadata(bytes, &fraction).error_code == UHDR_CODEC_OK &&
            ultrahdr::uhdr_gainmap_metadata_frac::gainmapMetadataFractionToFloat(&fraction, &metadata).error_code == UHDR_CODEC_OK) {
          r.maxContentBoost = metadata.max_content_boost[0];
          r.hdrCapacityMax = metadata.hdr_capacity_max;
        }
      }
    }
  }
  return r;
}

ProbeResult probeImage(int fd) {
  HeifFdReader reader(fd);
  if (reader.open()) {
    const int container = reader.container();
    if (container == 1 || container == 2) {
      auto result = probeHeifReader(reader, container);
      if (result.errorCode == 0) return result;
      // CApi/memory readers expose different available ranges (notably large
      // ftyp boxes). Reuse the original parser on failure, after ctx/handles die.
    }
  }
  std::vector<uint8_t> data = readAll(fd);
  if (data.empty()) {
    ProbeResult r;
    r.errorCode = 1;
    r.error = "无法读取图片数据";
    return r;
  }
  return probeImageMemory(data);
}

static TranscodeResult transcodeUnchecked(std::vector<uint8_t> data, DecoderPtr probedDec,
                                          const ProbeResult& probe, int outFd, const TranscodeOptions& options) {
  TranscodeResult r;
  const auto start = std::chrono::steady_clock::now();
  ultraslim_codec_speed_level = options.speedLevel;
  ultraslim_codec_threads = resolveCodecThreads(options.codecThreads);

  if (data.empty()) {
    r.errorCode = 1;
    r.error = "无法读取图片数据";
    return r;
  }
  r.inputBytes = static_cast<long>(data.size());
  const int container = sniffContainer(data.data(), data.size());
  if (container < 0) {
    r.errorCode = 2;
    r.error = "不支持的图片格式";
    return r;
  }

  // transcodeImage 已对整份输入做过 probe；此处只补齐转码必需的解析，
  // 不再重复跑一遍完整 probe。HDR 输入的 uhdr decoder 由 probe 直接移交。
  DecoderPtr dec;
  if (container == 3) {
    r.width = probe.width;
    r.height = probe.height;
    if (probe.hasGainMap) {
      r.inputHadGainMap = true;
      r.gainmapWidth = probe.gainmapWidth;
      r.gainmapHeight = probe.gainmapHeight;
    }
  } else {
    if (!probedDec && probe.hasGainMap) {
      dec.reset(uhdr_create_decoder());
      uhdr_compressed_image_t img {};
      img.data = data.data();
      img.data_sz = img.capacity = data.size();
      img.cg = UHDR_CG_UNSPECIFIED;
      img.ct = UHDR_CT_UNSPECIFIED;
      img.range = UHDR_CR_UNSPECIFIED;
      const bool ok = dec && uhdr_dec_set_image(dec.get(), &img).error_code == UHDR_CODEC_OK &&
                      uhdr_dec_probe(dec.get()).error_code == UHDR_CODEC_OK;
      if (!ok) dec.reset();
    } else {
      dec = std::move(probedDec);
    }
    if (dec) {
      const int gw = uhdr_dec_get_gainmap_width(dec.get());
      const int gh = uhdr_dec_get_gainmap_height(dec.get());
      if (gw > 0 && gh > 0) {
        r.inputHadGainMap = true;
        r.width = uhdr_dec_get_image_width(dec.get());
        r.height = uhdr_dec_get_image_height(dec.get());
        r.gainmapWidth = gw;
        r.gainmapHeight = gh;
      }
    }
  }

  // rebuild 路径的编码结果仍驻留内存，直接从缓冲校验；其余路径从输出 fd 校验。
  ProbeResult memVerified;
  bool verifiedInMemory = false;
  if (r.inputHadGainMap) {
    const int srcW = r.width;
    const int srcH = r.height;
    const int srcGmW = r.gainmapWidth;
    const int srcGmH = r.gainmapHeight;
    // Recompress the base and keep the original gain map. Downsampling a fine map
    // destroys highlight boost, so legacy scale/quality options must not degrade
    // it: the map is carried over whenever the output format can hold it, and
    // unsupported cases fail explicitly instead of silently rebuilding.
    const bool canPreserve = options.preserveGainMap;
    if (!canPreserve && options.outCodec == 3) {
      r.errorCode = 31;
      r.error = "JXL 仅支持原样保留 Gain Map";
      return r;
    }
    bool preserved = false;
    if (canPreserve) {
      preserved = tryReuseGainMap(data, container, dec, options, outFd, r) == 0;
      if (!preserved) {
        // Preserve-or-refuse: leave nothing half-written and surface the reason
        // (unsupported preservation is error 31, primary alpha is error 33).
        if (lseek(outFd, 0, SEEK_SET) >= 0) ftruncate(outFd, 0);
        if (r.error.empty()) r.error = "无法原样保留此 Gain Map，请保留原图";
        if (r.errorCode == 0) r.errorCode = 31;
        return r;
      }
    }
    if (preserved) {
      struct stat st {};
      if (fstat(outFd, &st) == 0) r.outputBytes = static_cast<long>(st.st_size);
      r.durationMs =
          std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
      return r;
    }
    r.width = srcW;
    r.height = srcH;
    r.gainmapWidth = srcGmW;
    r.gainmapHeight = srcGmH;

    std::vector<uint8_t> orientationExif = copyBlock(uhdr_dec_get_exif(dec.get()));
    const int orientation = container==0 ? ExifEditor(orientationExif).orientation() : 1;
    // ---------------- HDR path: decode with libultrahdr, rebuild the gain map
    uhdr_dec_set_out_img_format(dec.get(), UHDR_IMG_FMT_32bppRGBA1010102);
    uhdr_dec_set_out_color_transfer(dec.get(), UHDR_CT_HLG);
    if (options.resizePercent < 100) {
      int nw = 0, nh = 0;
      scaledSize(r.width, r.height, options.resizePercent, nw, nh);
      uhdr_add_effect_resize(dec.get(), nw, nh);
    }
    if(orientation==2) uhdr_add_effect_mirror(dec.get(),UHDR_MIRROR_HORIZONTAL);
    if(orientation==3) uhdr_add_effect_rotate(dec.get(),180);
    if(orientation==4) uhdr_add_effect_mirror(dec.get(),UHDR_MIRROR_VERTICAL);
    if(orientation==5){uhdr_add_effect_mirror(dec.get(),UHDR_MIRROR_HORIZONTAL);uhdr_add_effect_rotate(dec.get(),270);}
    if(orientation==6) uhdr_add_effect_rotate(dec.get(),90);
    if(orientation==7){uhdr_add_effect_mirror(dec.get(),UHDR_MIRROR_HORIZONTAL);uhdr_add_effect_rotate(dec.get(),90);}
    if(orientation==8) uhdr_add_effect_rotate(dec.get(),270);
    uhdr_error_info_t st = uhdr_decode(dec.get());
    if (st.error_code != UHDR_CODEC_OK) {
      r.errorCode = 6;
      r.error = describe(st, "解码图像失败");
      return r;
    }
    uhdr_raw_image_t* raw = uhdr_get_decoded_image(dec.get());
    if (raw == nullptr || raw->planes[0] == nullptr) {
      r.errorCode = 7;
      r.error = "解码结果为空";
      return r;
    }
    raw->range = UHDR_CR_FULL_RANGE;  // encoder expects full-range RGB
    r.width = static_cast<int>(raw->w);
    r.height = static_cast<int>(raw->h);

    std::vector<uint8_t> containerExif;
    uhdr_mem_block_t exifBlock {};
    uhdr_mem_block_t* exif = options.keepExif ? uhdr_dec_get_exif(dec.get()) : nullptr;
    if (options.keepExif && container != 0) {
      auto* ctx = heif_context_alloc();
      if (heif_context_read_from_memory_without_copy(ctx, data.data(), data.size(), nullptr).code == heif_error_Ok) {
        heif_image_handle* handle = nullptr;
        if (heif_context_get_primary_image_handle(ctx, &handle).code == heif_error_Ok) {
          readHeifExif(handle, containerExif);
          heif_image_handle_release(handle);
        }
      }
      heif_context_free(ctx);
      if (!containerExif.empty()) {
        exifBlock.data = containerExif.data();
        exifBlock.data_sz = exifBlock.capacity = containerExif.size();
        exif = &exifBlock;
      }
    }
    // EXIF 回退提取完成后压缩输入不再被读取；提前释放，重建/编码期间少驻留一份完整输入。
    data.clear();
    data.shrink_to_fit();

    if(options.keepExif && exif && exif->data && exif->data_sz) {
      if(containerExif.empty()) containerExif=copyBlock(exif);
      ExifEditor(containerExif).normalize(r.width,r.height,options.keepLocation);
      exifBlock.data=containerExif.data(); exifBlock.data_sz=exifBlock.capacity=containerExif.size(); exif=&exifBlock;
    }
    EncoderPtr enc(uhdr_create_encoder());
    if (!enc) {
      r.errorCode = 8;
      r.error = "创建编码器失败";
      return r;
    }
    st = uhdr_enc_set_raw_image(enc.get(), raw, UHDR_HDR_IMG);
    if (st.error_code != UHDR_CODEC_OK) {
      r.errorCode = 9;
      r.error = describe(st, "设置原始图像失败");
      return r;
    }
    st = uhdr_enc_set_output_format(
        enc.get(), options.outCodec == 2
                       ? UHDR_CODEC_AVIF
                       : options.outCodec == 1 ? UHDR_CODEC_HEIF : UHDR_CODEC_JPG);
    if (st.error_code != UHDR_CODEC_OK) {
      r.errorCode = 10;
      r.error = describe(st, "设置输出格式失败");
      return r;
    }
    uhdr_enc_set_quality(enc.get(), options.quality, UHDR_BASE_IMG);
    uhdr_enc_set_quality(enc.get(), options.gainmapQuality, UHDR_GAIN_MAP_IMG);
    uhdr_enc_set_gainmap_scale_factor(enc.get(), options.gainmapScale);
    uhdr_enc_set_using_multi_channel_gainmap(enc.get(), options.multiChannelGainmap ? 1 : 0);
    if (exif != nullptr && exif->data != nullptr && exif->data_sz > 0) {
      uhdr_enc_set_exif_data(enc.get(), exif);
    }

    st = uhdr_encode(enc.get());
    if (st.error_code != UHDR_CODEC_OK) {
      r.errorCode = 11;
      r.error = describe(st, "编码失败");
      return r;
    }
    uhdr_compressed_image_t* out = uhdr_get_encoded_stream(enc.get());
    if (out == nullptr || out->data == nullptr || out->data_sz == 0) {
      r.errorCode = 12;
      r.error = "编码结果为空";
      return r;
    }
    if (!writeAll(outFd, out->data, out->data_sz)) {
      if (lseek(outFd, 0, SEEK_SET) >= 0) ftruncate(outFd, 0);
      r.errorCode = 13;
      r.error = std::string("写入输出文件失败: ") + strerror(errno);
      return r;
    }
    r.outputBytes = static_cast<long>(out->data_sz);
    // 直接对编码缓冲做只读校验，不再整份复制（输出可达数百 MB）。
    memVerified = probeViews(BytesView(static_cast<const uint8_t*>(out->data), out->data_sz));
    verifiedInMemory = true;
  } else {
    // ---------------- SDR path: decode with libjpeg/libheif, encode with libheif
    if (!options.allowSdrTranscode) {
      r.errorCode = 14;
      r.error = "图片不包含 gain map";
      return r;
    }
    SdrImage sdr;
    std::string err;
    int srcW = 0, srcH = 0;
    int jpegTarget = 0;
    if (container == 0 && options.resizePercent < 100) {
      bool ignoredExif = false;
      if (probeJpegDims(data, srcW, srcH, ignoredExif)) {
        jpegTarget = std::max(2, std::max(srcW, srcH) * options.resizePercent / 100);
      }
    }
    const bool ok = container == 0 ? decodeJpegSdr(data, sdr, err, jpegTarget)
                  : container == 3 ? decodeJxlToSdr(data, sdr, nullptr, err)
                  : decodeHeifSdr(data, sdr, err, options.resizePercent);
    if (sdr.hasAlpha) {
      if (lseek(outFd, 0, SEEK_SET) >= 0) ftruncate(outFd, 0);
      setUnsupportedAlphaError(r);
      return r;
    }
    if (!ok) {
      r.errorCode = 15;
      r.error = err;
      return r;
    }
    // 像素/EXIF/ICC 都已复制进 sdr；提前释放，编码期间少驻留一份完整输入。
    data.clear();
    data.shrink_to_fit();
    if (options.resizePercent < 100 && (container == 0 || container == 3)) {
      const int basisW = srcW > 0 ? srcW : sdr.width;
      const int basisH = srcH > 0 ? srcH : sdr.height;
      int nw = 0, nh = 0;
      scaledSize(basisW, basisH, options.resizePercent, nw, nh);
      maybeDownscale(sdr, nw, nh);
    }
    r.width = sdr.width;
    r.height = sdr.height;
    if(container == 0) orientPixels(sdr, ExifEditor(sdr.exif).orientation());
    ExifEditor(sdr.exif).normalize(sdr.width, sdr.height, options.keepLocation);
    r.width = sdr.width;
    r.height = sdr.height;
    if (!options.keepExif) sdr.exif.clear();
    // HEIC/AVIF/JXL：像素预处理与质量无关，先转换一次再进重试闭包，转换后立即释放 RGBA。
    PreparedBasePlanes sdrPlanes;
    std::vector<uint8_t> sdrJxlRgb;
    const bool reuseSdrPlanes = options.outCodec == 1 || options.outCodec == 2;
    const bool reuseSdrJxl = options.outCodec == 3;
    if (reuseSdrPlanes && prepareBasePlanes(sdr, options.outCodec, false, sdrPlanes, err) != 0) {
      r.errorCode = 31;
      r.error = err;
      return r;
    }
    if (reuseSdrJxl) prepareJxlRgb(sdr, sdrJxlRgb);
    if (reuseSdrPlanes || reuseSdrJxl) {
      sdr.rgba.clear();
      sdr.rgba.shrink_to_fit();
    }
    int encErr = encodeWithRetry(outFd, r.inputBytes, options.quality, options.retrySmaller, [&](int quality) {
      int code;
      if (options.outCodec == 0) {
        if(!prepareJpegProfile(sdr)) { err="此色彩配置无法安全转换为 JPEG，请使用 HEIC/AVIF/JXL"; return 29; }
        code = encodeJpegSdr(sdr, quality, options.keepExif, outFd, err);
      } else if (options.outCodec == 3) {
        code = encodeJxl(sdr, quality, options.keepExif, outFd,
                           options.speedLevel, ultraslim_codec_threads, nullptr, sdrJxlRgb, options.retrySmaller, err);
      } else {
        code = encodePreparedWithLibheif(sdr, options.outCodec, quality, options.keepExif, outFd,
                                         options.speedLevel, ultraslim_codec_threads, nullptr,
                                         sdrPlanes, nullptr, err, &options.xmp);
      }
      return code;
    });
    if (encErr != 0) {
      // 与 preserve 失败一致：失败时清掉已写入的部分内容，不留半截文件。
      if (lseek(outFd, 0, SEEK_SET) >= 0) ftruncate(outFd, 0);
      r.errorCode = encErr;
      r.error = err;
      return r;
    }
    struct stat st {};
    if (fstat(outFd, &st) == 0) r.outputBytes = static_cast<long>(st.st_size);
    uint8_t header[16] {};
    const ssize_t headerLen = pread(outFd, header, sizeof(header), 0);
    if (r.outputBytes <= 0 || headerLen <= 0 ||
        sniffContainer(header, static_cast<size_t>(headerLen)) != options.outCodec) {
      r.errorCode = 25; r.error = "输出校验失败：图像或 Gain Map 不完整"; return r;
    }
    r.hasGainMap = false;
    r.hasExif = !sdr.exif.empty();
    r.durationMs =
        std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
    return r;
  }

  const ProbeResult verified = verifiedInMemory ? memVerified : probeImage(outFd);
  if (verified.errorCode != 0 || (r.inputHadGainMap && !verified.hasGainMap)) {
    r.errorCode = 25; r.error = "输出校验失败：图像或 Gain Map 不完整"; return r;
  }
  r.width = verified.width; r.height = verified.height;
  r.gainmapWidth = verified.gainmapWidth; r.gainmapHeight = verified.gainmapHeight;
  r.hasGainMap = verified.hasGainMap;
  r.hasExif = verified.hasExif;
  r.maxContentBoost = verified.maxContentBoost;
  r.durationMs =
      std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
  return r;
}


TranscodeResult transcodeImage(int inFd, int outFd, const TranscodeOptions& options) {
  try {
    std::vector<uint8_t> data = readAll(inFd);
    if (data.empty()) {
      TranscodeResult r; r.errorCode = 1; r.error = "无法读取图片数据"; return r;
    }
    DecoderPtr probeDecoder;
    auto p = probeImageMemory(data, &probeDecoder);
    if (p.errorCode || p.width <= 0 || p.height <= 0 || static_cast<int64_t>(p.width) * p.height > kMaxPixels) {
      TranscodeResult r; r.errorCode = 26; r.error = p.errorCode ? p.error : "图片超过安全解码限制（1 亿像素）"; return r;
    }
    if(options.xmp.size()>1024*1024||options.outCodec<0||options.outCodec>3||options.quality<1||options.quality>100||options.gainmapQuality<1||options.gainmapQuality>100||options.gainmapScale<1||options.gainmapScale>128||options.resizePercent<1||options.resizePercent>100||options.speedLevel<0||options.speedLevel>2) {
      TranscodeResult r;r.errorCode=30;r.error="转码参数超出范围";return r;
    }
    return transcodeUnchecked(std::move(data), std::move(probeDecoder), p, outFd, options);
  } catch (const std::exception& e) {
    TranscodeResult r; r.errorCode = 27; r.error = std::string("转码资源不足或数据异常: ") + e.what(); return r;
  }
}

std::vector<uint8_t> debugDecodePlaneY(int fd, int& w, int& h) {
  std::vector<uint8_t> empty;
  std::vector<uint8_t> data = readAll(fd);
  if (data.empty()) return empty;

  heif_context* ctx = heif_context_alloc();
  if (heif_context_read_from_memory_without_copy(ctx, data.data(), data.size(), nullptr).code !=
      heif_error_Ok) {
    heif_context_free(ctx);
    return empty;
  }
  heif_image_handle* handle = nullptr;
  heif_error e = heif_context_get_primary_image_handle(ctx, &handle);
  if (e.code != heif_error_Ok || handle == nullptr) {
    heif_context_free(ctx);
    return empty;
  }
  heif_image* himg = nullptr;
  e = heif_decode_image(handle, &himg, heif_colorspace_undefined, heif_chroma_undefined, nullptr);
  if (e.code != heif_error_Ok || himg == nullptr) {
    heif_image_handle_release(handle);
    heif_context_free(ctx);
    return empty;
  }
  w = heif_image_get_width(himg, heif_channel_Y);
  h = heif_image_get_height(himg, heif_channel_Y);
  int stride = 0;
  const uint8_t* plane = heif_image_get_plane_readonly(himg, heif_channel_Y, &stride);
  std::vector<uint8_t> out(static_cast<size_t>(w) * h);
  for (int y = 0; y < h; y++) {
    memcpy(out.data() + static_cast<size_t>(w) * y, plane + static_cast<size_t>(stride) * y, w);
  }
  heif_image_release(himg);
  heif_image_handle_release(handle);
  heif_context_free(ctx);
  return out;
}

std::vector<uint8_t> decodePreviewJpeg(int fd, int maxDim, int quality, uint64_t maxWorkingBytes) {
  std::vector<uint8_t> empty;
  if (maxWorkingBytes < 16u * 1024 * 1024) return empty;
  // 该入口不经过 transcodeUnchecked，thread_local 线程数需自行初始化，
  // 否则 maybeDownscale/orientPixels 的 parallelRows 全部退化为单线程。
  if (ultraslim_codec_threads <= 0) ultraslim_codec_threads = resolveCodecThreads(0);
  HeifFdReader reader(fd);
  const bool regular = reader.open();
  const int fdContainer = regular ? reader.container() : -1;
  const bool streamHeif = fdContainer == 1 || fdContainer == 2;
  std::vector<uint8_t> data;
  if (!streamHeif) {
    // Avoid a full compressed-input allocation already larger than the budget.
    struct stat st {};
    if (fstat(fd, &st) == 0 && S_ISREG(st.st_mode) && st.st_size > 0 && uint64_t(st.st_size) > maxWorkingBytes) return empty;
    data = readAll(fd, maxWorkingBytes);
    if (data.empty() || data.size() > maxWorkingBytes) return empty;
  }
  const int container = streamHeif ? fdContainer : sniffContainer(data.data(), data.size());
  if (container < 0) return empty;

  SdrImage sdr;
  std::string err;
  const int limit = std::max(1, maxDim);
  bool parseFailed = false;
  bool ok = container == 0 ? decodeJpegSdr(data, sdr, err, limit, maxWorkingBytes)
          : container == 3 ? decodeJxlToSdr(data, sdr, nullptr, err, limit, maxWorkingBytes)
          : decodeHeifPreview(data, sdr, err, limit,
                              maxWorkingBytes - data.size(), streamHeif ? &reader : nullptr, &parseFailed);
  if (!ok && streamHeif && parseFailed) {
    // The failed context is already destroyed. Never retry pixel admission or
    // codec failures; only reconcile reader-vs-memory container parsing.
    constexpr uint64_t allowance = 16u * 1024 * 1024;
    data = readAll(fd, maxWorkingBytes - allowance);
    if (data.empty()) return empty;
    ok = decodeHeifPreview(data, sdr, err, limit, maxWorkingBytes - data.size());
  }
  if (!ok || sdr.width <= 0 || sdr.height <= 0) return empty;

  const int larger = std::max(sdr.width, sdr.height);
  if (larger > limit) {
    const int nw = std::max<int64_t>(1, int64_t(sdr.width) * limit / larger);
    const int nh = std::max<int64_t>(1, int64_t(sdr.height) * limit / larger);
    sdr = resizeRgba(sdr, nw, nh);
  }
  if (container == 0) orientPixels(sdr, ExifEditor(sdr.exif).orientation());
  if(!prepareJpegProfile(sdr)) return empty;
  return encodeJpegToMem(sdr, quality, false);
}

}  // namespace ultraslim
