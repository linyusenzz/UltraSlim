// Include the implementation so parity tests can retain the original in-memory
// parser and pixel-resize reference without exporting test-only production APIs.
#include "../jni/transcode.cpp"
#include <fcntl.h>
#include <cstdlib>
#include <iostream>

static void check(bool ok, const char* message) {
  if (!ok) { std::cerr << "FAIL: " << message << '\n'; std::exit(1); }
}
static int openFile(const std::string& path) {
  int fd = open(path.c_str(), O_RDONLY); check(fd >= 0, "open fixture"); return fd;
}
static void writeFile(const std::string& path, const std::vector<uint8_t>& data) {
  int fd = open(path.c_str(), O_CREAT | O_TRUNC | O_WRONLY, 0600);
  check(fd >= 0 && ultraslim::writeAll(fd, data.data(), data.size()), "write fixture"); close(fd);
}
static void equalProbe(const ultraslim::ProbeResult& a, const ultraslim::ProbeResult& b) {
  check(a.errorCode == b.errorCode && a.error == b.error && a.container == b.container &&
        a.width == b.width && a.height == b.height && a.hasGainMap == b.hasGainMap &&
        a.gainmapWidth == b.gainmapWidth && a.gainmapHeight == b.gainmapHeight && a.hasExif == b.hasExif &&
        a.maxContentBoost == b.maxContentBoost && a.hdrCapacityMax == b.hdrCapacityMax, "probe field parity");
}
static void parity(const std::string& path) {
  int fd = openFile(path); auto data = ultraslim::readAll(fd);
  auto old = ultraslim::probeImageMemory(data); auto fresh = ultraslim::probeImage(fd);
  equalProbe(old, fresh);
  ultraslim::DecoderPtr decoder;
  equalProbe(old, ultraslim::probeImageMemory(data, &decoder));
  if (old.hasGainMap && old.container != 3) check(bool(decoder), "decoder handoff retains metadata");
  close(fd);
}
static std::vector<uint8_t> load(const std::string& path) {
  int fd = openFile(path); auto data = ultraslim::readAll(fd); close(fd); return data;
}
static void preview(const std::string& path, int limit) {
  int fd = openFile(path);
  check(ultraslim::decodePreviewJpeg(fd, limit, 82, 1).empty(), "tiny preview budget rejects");
  check(ultraslim::decodePreviewJpeg(fd, limit, 82, 16u * 1024 * 1024).empty(), "header budget rejects before pixel allocation");
  auto bytes = ultraslim::decodePreviewJpeg(fd, limit);
  check(!bytes.empty(), "preview succeeds");
  int w = 0, h = 0; bool exif = false;
  check(ultraslim::probeJpegDims(bytes, w, h, exif) && std::max(w, h) <= limit, "preview dimensions bounded");
  // Same decoder, original full-copy -> resize -> JPEG pipeline. Equality covers
  // channel order, row padding, interpolation, ICC/NCLX and HEIF transforms.
  auto data = ultraslim::readAll(fd); ultraslim::SdrImage original; std::string error;
  if (ultraslim::sniffContainer(data.data(), data.size()) == 1 || ultraslim::sniffContainer(data.data(), data.size()) == 2) {
    check(ultraslim::decodeHeifSdr(data, original, error), "reference full decode");
    int edge = std::max(original.width, original.height);
    if (edge > limit) original = ultraslim::resizeRgba(original, std::max(1, original.width * limit / edge), std::max(1, original.height * limit / edge));
    check(ultraslim::prepareJpegProfile(original), "reference color profile");
    check(bytes == ultraslim::encodeJpegToMem(original, 82), "direct-plane preview matches reference JPEG exactly");
  }
  close(fd);
}
static void conversionResizeParity(const std::string& path) {
  auto bytes = load(path);
  ultraslim::SdrImage full; std::string error;
  check(ultraslim::decodeHeifSdr(bytes, full, error), "full conversion reference decode");
  for (int percent : {1, 37, 50, 99, 100}) {
    int width, height; ultraslim::scaledSize(full.width, full.height, percent, width, height);
    auto reference = full;
    ultraslim::maybeDownscale(reference, width, height);
    ultraslim::SdrImage direct;
    check(ultraslim::decodeHeifSdr(bytes, direct, error, percent), "direct conversion resize decode");
    check(direct.width == reference.width && direct.height == reference.height && direct.rgba == reference.rgba,
          "conversion direct-plane resize matches full-copy reference pixels and even rounding");
    check(direct.exif == reference.exif && direct.icc == reference.icc &&
          direct.hasProfile == reference.hasProfile, "conversion resize retains EXIF and color profile");
    if (direct.hasProfile) check(direct.profile.color_primaries == reference.profile.color_primaries &&
        direct.profile.transfer_characteristics == reference.profile.transfer_characteristics &&
        direct.profile.matrix_coefficients == reference.profile.matrix_coefficients &&
        direct.profile.full_range_flag == reference.profile.full_range_flag, "conversion resize retains NCLX");
  }
}
static void jxlBufferLifetime(const std::string& source, const std::string& output) {
  auto data = load(source); ultraslim::SdrImage base; std::string error;
  check(ultraslim::decodeJpegSdr(data, base, error), "JXL lifetime source decode");
  // Compare the same encode with and without retained retry pixels. Decoding after
  // source storage is released also exercises AddImageFrame's ownership contract.
  for (bool retry : {false, true}) {
    std::vector<uint8_t> rgb; ultraslim::prepareJxlRgb(base, rgb);
    const auto path = output + (retry ? "/jxl-retained.jxl" : "/jxl-released.jxl");
    int fd = open(path.c_str(), O_CREAT | O_TRUNC | O_RDWR, 0600);
    check(fd >= 0 && ultraslim::encodeJxl(base, 90, true, fd, 2, 2, nullptr, rgb, retry, error) == 0,
          "JXL encode after input-buffer lifetime change");
    close(fd);
    check(retry ? !rgb.empty() : rgb.capacity() == 0, "only retry retains JXL main RGB allocation");
    ultraslim::SdrImage decoded;
    check(ultraslim::decodeJxlToSdr(load(path), decoded, nullptr, error) &&
          decoded.width == base.width && decoded.height == base.height && decoded.exif == base.exif,
          "JXL released-input output decodes with dimensions and EXIF");
  }
  check(load(output + "/jxl-retained.jxl") == load(output + "/jxl-released.jxl"),
        "JXL buffer retention does not change encoded bytes");
}
static void jxlGainmapBufferLifetime(const std::string& source, const std::string& output) {
  auto data = load(source); ultraslim::DecoderPtr decoder;
  check(ultraslim::probeImageMemory(data, &decoder).hasGainMap && bool(decoder), "JXL lifetime HDR source");
  ultraslim::SdrImage base; ultraslim::GainMapPayload payload; std::string error;
  auto baseBytes = ultraslim::copyBlock(uhdr_dec_get_base_image(decoder.get()));
  auto gmBytes = ultraslim::copyBlock(uhdr_dec_get_gainmap_image(decoder.get()));
  check(ultraslim::decodeJpegSdr(baseBytes, base, error) &&
        ultraslim::decodeJpegSdr(gmBytes, payload.image, error), "JXL lifetime HDR pixels");
  const auto originalMap = payload.image;
  payload.iso = ultraslim::isoFromMetadata(*uhdr_dec_get_gainmap_metadata(decoder.get()));
  payload.multiChannel = ultraslim::jpegChannels(gmBytes) > 1;
  payload.cacheForRetry = true;
  std::vector<uint8_t> rgb; ultraslim::prepareJxlRgb(base, rgb);
  for (int quality : {90, 85}) {
    const auto path = output + "/jxl-map-lifetime-" + std::to_string(quality) + ".jxl";
    int fd = open(path.c_str(), O_CREAT | O_TRUNC | O_RDWR, 0600);
    check(fd >= 0 && ultraslim::encodeJxl(base, quality, true, fd, 2, 2, &payload, rgb, true, error) == 0,
          "JXL HDR encode reuses cached map after releasing its source pixels");
    close(fd);
    check(payload.image.rgba.capacity() == 0 && !payload.encodedJxl.empty(),
          "JXL retry retains compressed gain map instead of source RGBA");
    ultraslim::SdrImage decodedBase, decodedMap; std::vector<uint8_t> jhgm;
    check(ultraslim::decodeJxlToSdr(load(path), decodedBase, &jhgm, error), "JXL HDR lifetime output decodes");
    ultraslim::JxlGainMapView parsed;
    check(ultraslim::parseJxlGainMap(jhgm, parsed), "JXL lifetime ISO payload");
    const std::vector<uint8_t> stream(parsed.codestream.data(), parsed.codestream.data() + parsed.codestream.size());
    check(ultraslim::decodeJxlToSdr(stream, decodedMap, nullptr, error) &&
          decodedMap.width == originalMap.width && decodedMap.height == originalMap.height &&
          decodedMap.rgba == originalMap.rgba, "released JXL gain-map pixels remain exact through retry");
    check(std::vector<uint8_t>(parsed.iso.data(), parsed.iso.data() + parsed.iso.size()) == payload.iso,
          "JXL lifetime retains exact ISO metadata");
  }
}
static std::string convert(const std::string& source, const std::string& target, int codec, bool compatibility) {
  int in = openFile(source), out = open(target.c_str(), O_CREAT | O_TRUNC | O_RDWR, 0600);
  ultraslim::TranscodeOptions options; options.outCodec = codec; options.speedLevel = 2; options.codecThreads = 2;
  options.heicGainmapCompatibility = options.avifGainmapCompatibility = compatibility;
  auto result = ultraslim::transcodeImage(in, out, options); close(in); close(out);
  if (result.errorCode) std::cerr << result.error << '\n';
  check(result.errorCode == 0, "create conversion fixture"); return target;
}

struct BoxSpan {
  size_t start = 0;
  size_t header = 0;
  size_t payloadSize = 0;
};

static bool findBox(const std::vector<uint8_t>& data, const char* type, BoxSpan& span) {
  size_t position = 0;
  while (position < data.size()) {
    if (data.size() - position < 8) return false;
    uint64_t size = ultraslim::loadBE32(data.data() + position);
    size_t header = 8;
    if (size == 1) {
      if (data.size() - position < 16) return false;
      size = 0;
      for (int i = 0; i < 8; ++i) size = (size << 8) | data[position + 8 + i];
      header = 16;
    } else if (size == 0) {
      size = data.size() - position;
    }
    if (size < header || size > data.size() - position) return false;
    if (memcmp(data.data() + position + 4, type, 4) == 0) {
      span = {position, header, static_cast<size_t>(size) - header};
      return true;
    }
    position += static_cast<size_t>(size);
  }
  return false;
}

static std::vector<uint8_t> quadrantPixels(int width, int height, bool alpha) {
  std::vector<uint8_t> pixels(size_t(width) * height * 4);
  for (int y = 0; y < height; ++y) for (int x = 0; x < width; ++x) {
    const bool right = x >= width / 2, bottom = y >= height / 2;
    uint8_t* p = pixels.data() + (size_t(y) * width + x) * 4;
    p[0] = right ? 20 : 240;
    p[1] = bottom ? 240 : 20;
    p[2] = right == bottom ? 240 : 20;
    p[3] = alpha ? static_cast<uint8_t>((x * 37 + y * 61) & 255) : 255;
  }
  return pixels;
}

static std::vector<uint8_t> jxlFixture(int width, int height, JxlOrientation orientation,
                                       int exifOrientation, bool alpha, const std::vector<uint8_t>& rgba,
                                       const std::vector<uint8_t>& jhgm = {}) {
  check(width > 0 && height > 0 && rgba.size() == size_t(width) * height * 4, "JXL fixture dimensions");
  JxlEncoder* enc = JxlEncoderCreate(nullptr);
  check(enc != nullptr, "JXL fixture encoder");
  const bool boxes = exifOrientation > 0 || !jhgm.empty();
  check(JxlEncoderUseContainer(enc, boxes ? JXL_TRUE : JXL_FALSE) == JXL_ENC_SUCCESS, "JXL fixture container");
  if (boxes) check(JxlEncoderUseBoxes(enc) == JXL_ENC_SUCCESS, "JXL fixture boxes");
  JxlBasicInfo info;
  JxlEncoderInitBasicInfo(&info);
  info.xsize = width;
  info.ysize = height;
  info.bits_per_sample = 8;
  info.num_color_channels = 3;
  info.orientation = orientation;
  info.alpha_bits = alpha ? 8 : 0;
  info.num_extra_channels = alpha ? 1 : 0;
  info.uses_original_profile = JXL_TRUE;
  check(JxlEncoderSetBasicInfo(enc, &info) == JXL_ENC_SUCCESS, "JXL fixture basic info");
  if (alpha) {
    JxlExtraChannelInfo extra;
    JxlEncoderInitExtraChannelInfo(JXL_CHANNEL_ALPHA, &extra);
    check(JxlEncoderSetExtraChannelInfo(enc, 0, &extra) == JXL_ENC_SUCCESS, "JXL fixture alpha info");
  }
  JxlColorEncoding color;
  JxlColorEncodingSetToSRGB(&color, JXL_FALSE);
  check(JxlEncoderSetColorEncoding(enc, &color) == JXL_ENC_SUCCESS, "JXL fixture color");
  if (exifOrientation > 0) {
    std::vector<uint8_t> exif = {0, 0, 0, 0, 'I', 'I', 42, 0, 8, 0, 0, 0, 1, 0,
                                 0x12, 1, 3, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
    exif[22] = static_cast<uint8_t>(exifOrientation);
    check(JxlEncoderAddBox(enc, "Exif", exif.data(), exif.size(), JXL_FALSE) == JXL_ENC_SUCCESS,
          "JXL fixture EXIF");
  }
  if (!jhgm.empty()) {
    check(JxlEncoderAddBox(enc, "jhgm", jhgm.data(), jhgm.size(), JXL_FALSE) == JXL_ENC_SUCCESS,
          "JXL fixture jhgm");
  }
  if (boxes) JxlEncoderCloseBoxes(enc);
  JxlEncoderFrameSettings* settings = JxlEncoderFrameSettingsCreate(enc, nullptr);
  check(settings != nullptr, "JXL fixture frame settings");
  check(JxlEncoderFrameSettingsSetOption(settings, JXL_ENC_FRAME_SETTING_EFFORT, 3) == JXL_ENC_SUCCESS,
        "JXL fixture effort");
  check(JxlEncoderSetFrameLossless(settings, JXL_TRUE) == JXL_ENC_SUCCESS, "JXL fixture lossless");
  std::vector<uint8_t> packed;
  const uint8_t* input = rgba.data();
  if (!alpha) {
    packed.reserve(size_t(width) * height * 3);
    for (size_t i = 0; i < rgba.size(); i += 4) {
      packed.insert(packed.end(), {rgba[i], rgba[i + 1], rgba[i + 2]});
    }
    input = packed.data();
  }
  JxlPixelFormat format = {alpha ? 4u : 3u, JXL_TYPE_UINT8, JXL_LITTLE_ENDIAN, 0};
  const size_t inputSize = size_t(width) * height * (alpha ? 4 : 3);
  check(JxlEncoderAddImageFrame(settings, &format, input, inputSize) == JXL_ENC_SUCCESS, "JXL fixture frame");
  JxlEncoderCloseInput(enc);
  std::vector<uint8_t> encoded, chunk(64 * 1024);
  for (;;) {
    uint8_t* next = chunk.data();
    size_t available = chunk.size();
    const JxlEncoderStatus status = JxlEncoderProcessOutput(enc, &next, &available);
    check(status != JXL_ENC_ERROR, "JXL fixture encoding");
    encoded.insert(encoded.end(), chunk.data(), next);
    if (status == JXL_ENC_SUCCESS) break;
    check(status == JXL_ENC_NEED_MORE_OUTPUT, "JXL fixture output status");
  }
  JxlEncoderDestroy(enc);
  return encoded;
}

static ultraslim::SdrImage decodeJxlFixture(const std::vector<uint8_t>& data) {
  ultraslim::SdrImage image;
  std::string error;
  check(ultraslim::decodeJxlToSdr(data, image, nullptr, error), "decode JXL fixture");
  return image;
}

static double meanPixelError(const ultraslim::SdrImage& a, const ultraslim::SdrImage& b) {
  check(a.width == b.width && a.height == b.height && a.rgba.size() == b.rgba.size(), "pixel comparison dimensions");
  uint64_t total = 0;
  for (size_t i = 0; i < a.rgba.size(); ++i) total += std::abs(int(a.rgba[i]) - int(b.rgba[i]));
  return a.rgba.empty() ? 0.0 : double(total) / a.rgba.size();
}

static void expectTranscodeError(const std::string& source, const std::string& target, int expected) {
  int in = openFile(source), out = open(target.c_str(), O_CREAT | O_TRUNC | O_RDWR, 0600);
  check(out >= 0, "open rejected output");
  ultraslim::TranscodeOptions options;
  options.outCodec = 1;
  options.codecThreads = 2;
  auto result = ultraslim::transcodeImage(in, out, options);
  struct stat st {};
  check(fstat(out, &st) == 0, "stat rejected output");
  close(in);
  close(out);
  if (result.errorCode != expected) std::cerr << result.error << '\n';
  check(result.errorCode == expected, "expected transcode rejection");
  check(st.st_size == 0, "rejected transcode leaves empty output");
}

static void expectAlphaRejected(const std::string& source, const std::string& output, int codec) {
  int in = openFile(source), out = open(output.c_str(), O_CREAT | O_TRUNC | O_RDWR, 0600);
  check(out >= 0, "open alpha output");
  ultraslim::TranscodeOptions options;
  options.outCodec = codec;
  options.codecThreads = 2;
  auto result = ultraslim::transcodeImage(in, out, options);
  struct stat st {};
  check(fstat(out, &st) == 0, "stat alpha output");
  close(in);
  close(out);
  if (result.errorCode != 33) std::cerr << result.error << '\n';
  check(result.errorCode == 33, "primary alpha is error 33");
  check(st.st_size == 0, "alpha rejection leaves empty output");
}

static void checkPreview(const std::string& source) {
  int fd = openFile(source);
  auto preview = ultraslim::decodePreviewJpeg(fd, 64, 100);
  close(fd);
  check(!preview.empty(), "alpha input still has a preview");
}

static void alphaHeifFixture(const std::string& path, int width, int height,
                             const std::vector<uint8_t>& rgba) {
  heif_context* ctx = heif_context_alloc();
  heif_encoder* encoder = nullptr;
  check(heif_context_get_encoder_for_format(ctx, heif_compression_HEVC, &encoder).code == heif_error_Ok,
        "alpha HEIF encoder");
  heif_encoder_set_parameter_string(encoder, "preset", "ultrafast");
  heif_image* image = nullptr;
  check(heif_image_create(width, height, heif_colorspace_RGB, heif_chroma_interleaved_RGBA, &image).code == heif_error_Ok,
        "alpha HEIF image");
  check(heif_image_add_plane(image, heif_channel_interleaved, width, height, 8).code == heif_error_Ok,
        "alpha HEIF plane");
  int stride = 0;
  uint8_t* plane = heif_image_get_plane(image, heif_channel_interleaved, &stride);
  check(plane != nullptr && stride >= width * 4, "alpha HEIF stride");
  for (int y = 0; y < height; ++y) memcpy(plane + size_t(y) * stride, rgba.data() + size_t(y) * width * 4, width * 4);
  heif_encoding_options* options = heif_encoding_options_alloc();
  check(options != nullptr, "alpha HEIF options");
  options->save_alpha_channel = 1;
  heif_image_handle* handle = nullptr;
  const auto encoded = heif_context_encode_image(ctx, image, encoder, options, &handle);
  heif_encoding_options_free(options);
  check(encoded.code == heif_error_Ok && handle != nullptr, "encode alpha HEIF");
  check(heif_context_write_to_file(ctx, path.c_str()).code == heif_error_Ok, "write alpha HEIF");
  heif_image_handle_release(handle);
  heif_image_release(image);
  heif_encoder_release(encoder);
  heif_context_free(ctx);
}

static void targetedNativeRegressions(const std::string& fixtures, const std::string& output) {
  int scaledW = 0, scaledH = 0;
  ultraslim::scaledSize(INT32_MAX, INT32_MAX, 50, scaledW, scaledH);
  check(scaledW == 1073741822 && scaledH == 1073741822, "scaledSize uses 64-bit intermediates");
  ultraslim::scaledSize(INT32_MAX, 500000000, 99, scaledW, scaledH);
  check(scaledW == 2126008810 && scaledH == 495000000, "scaledSize extreme aspect ratio");

  const auto validPath = convert(fixtures + "/apple_gainmap_new.jpg", output + "/native-valid-hdr.jxl", 3, false);
  const auto validData = load(validPath);
  const auto validProbe = ultraslim::probeImageMemory(validData);
  check(validProbe.errorCode == 0 && validProbe.hasGainMap, "valid JXL gain map probe");
  BoxSpan box;
  check(findBox(validData, "jhgm", box) && box.header == 8, "locate jhgm box");
  const size_t payload = box.start + box.header;
  const std::vector<uint8_t> jhgm(validData.begin() + payload, validData.begin() + payload + box.payloadSize);
  ultraslim::JhgmLayout layout;
  check(ultraslim::parseJhgmLayout(ultraslim::BytesView(jhgm), layout), "parse valid jhgm layout");
  const size_t codestreamOffset = static_cast<size_t>(layout.codestream.data() - jhgm.data());
  for (size_t cut = 0; cut <= codestreamOffset; ++cut) {
    ultraslim::JhgmLayout partial;
    check(!ultraslim::parseJhgmLayout(ultraslim::BytesView(jhgm.data(), cut), partial),
          "every truncated jhgm header is rejected");
  }
  const size_t isoSize = ultraslim::loadBE16(jhgm.data() + 1);
  const size_t colorOffset = 3 + isoSize;
  check(colorOffset < jhgm.size(), "jhgm color field offset");
  const size_t altOffset = colorOffset + 1 + jhgm[colorOffset];
  check(altOffset + 4 <= jhgm.size(), "jhgm alternate ICC field offset");
  auto oversizedIcc = jhgm;
  ultraslim::storeBE32(UINT32_MAX, oversizedIcc.data() + altOffset);
  ultraslim::JhgmLayout invalidLayout;
  check(!ultraslim::parseJhgmLayout(ultraslim::BytesView(oversizedIcc), invalidLayout),
        "alternate ICC length cannot overflow the cursor");

  const size_t truncatedSize = 3 + isoSize;
  check(truncatedSize < codestreamOffset && box.header + truncatedSize <= UINT32_MAX, "truncated jhgm fixture size");
  auto broken = validData;
  broken.erase(broken.begin() + payload + truncatedSize, broken.begin() + payload + box.payloadSize);
  ultraslim::storeBE32(static_cast<uint32_t>(box.header + truncatedSize), broken.data() + box.start);
  const auto brokenPath = output + "/native-truncated-jhgm.jxl";
  writeFile(brokenPath, broken);
  const auto brokenProbe = ultraslim::probeImageMemory(broken);
  check(brokenProbe.errorCode != 0, "malformed jhgm never falls back to SDR");
  expectTranscodeError(brokenPath, output + "/native-truncated-jhgm-out", 26);
  int directOut = open((output + "/native-truncated-jhgm-direct").c_str(), O_CREAT | O_TRUNC | O_RDWR, 0600);
  check(directOut >= 0, "open direct jhgm output");
  ultraslim::TranscodeOptions preserveOptions;
  preserveOptions.outCodec = 3;
  preserveOptions.codecThreads = 2;
  ultraslim::TranscodeResult directResult;
  directResult.width = validProbe.width;
  directResult.height = validProbe.height;
  directResult.gainmapWidth = validProbe.gainmapWidth;
  directResult.gainmapHeight = validProbe.gainmapHeight;
  ultraslim::DecoderPtr noDecoder;
  const int directRc = ultraslim::tryReuseGainMap(broken, 3, noDecoder, preserveOptions, directOut, directResult);
  struct stat directStat {};
  check(fstat(directOut, &directStat) == 0, "stat direct jhgm output");
  close(directOut);
  check(directRc == -1 && !directResult.error.empty() && directStat.st_size == 0,
        "preserve uses the same bounded jhgm parser");

  const int width = 12, height = 8;
  const auto opaque = quadrantPixels(width, height, false);
  const auto orientedData = jxlFixture(width, height, JXL_ORIENT_ROTATE_90_CW, 8, false, opaque);
  const auto orientedPath = output + "/native-oriented.jxl";
  writeFile(orientedPath, orientedData);
  ultraslim::SdrImage source;
  source.width = width;
  source.height = height;
  source.rgba = opaque;
  auto expected = source;
  ultraslim::orientPixels(expected, 6);
  auto direct = decodeJxlFixture(orientedData);
  check(ultraslim::ExifEditor(direct.exif).orientation() == 8, "JXL fixture has non-identity EXIF");
  check(direct.width == expected.width && direct.height == expected.height && direct.rgba == expected.rgba,
        "lossless JXL fixture applies codestream orientation once");
  const auto orientedOutput = convert(orientedPath, output + "/native-oriented-out.jxl", 3, false);
  auto converted = decodeJxlFixture(load(orientedOutput));
  check(converted.width == expected.width && converted.height == expected.height,
        "SDR JXL transcode does not rotate twice");
  check(meanPixelError(converted, expected) < 30.0, "SDR JXL transcode keeps single-rotation pixels");
  check(ultraslim::ExifEditor(converted.exif).orientation() == 1, "transcoded EXIF orientation normalized");
  int orientedFd = openFile(orientedPath);
  const auto previewJpeg = ultraslim::decodePreviewJpeg(orientedFd, 64, 100);
  close(orientedFd);
  check(!previewJpeg.empty(), "oriented JXL preview");
  ultraslim::SdrImage preview;
  std::string previewError;
  check(ultraslim::decodeJpegSdr(previewJpeg, preview, previewError), "decode oriented JXL preview");
  check(preview.width == expected.width && preview.height == expected.height,
        "JXL preview does not rotate twice");
  check(meanPixelError(preview, expected) < 30.0, "JXL preview keeps single-rotation pixels");

  const auto alphaPixels = quadrantPixels(width, height, true);
  const auto alphaData = jxlFixture(width, height, JXL_ORIENT_IDENTITY, 0, true, alphaPixels);
  const auto alphaPath = output + "/native-alpha.jxl";
  writeFile(alphaPath, alphaData);
  check(decodeJxlFixture(alphaData).hasAlpha, "JXL alpha fixture has main alpha");
  const auto alphaProbe = ultraslim::probeImageMemory(alphaData);
  check(alphaProbe.errorCode == 0 && !alphaProbe.hasGainMap, "alpha JXL SDR probe");
  for (int codec : {0, 1, 2, 3}) {
    expectAlphaRejected(alphaPath, output + "/native-alpha-out-" + std::to_string(codec), codec);
  }
  checkPreview(alphaPath);

  const auto hdrAlphaData = jxlFixture(width, height, JXL_ORIENT_IDENTITY, 0, true, alphaPixels, jhgm);
  const auto hdrAlphaPath = output + "/native-alpha-hdr.jxl";
  writeFile(hdrAlphaPath, hdrAlphaData);
  const auto hdrAlphaProbe = ultraslim::probeImageMemory(hdrAlphaData);
  check(hdrAlphaProbe.errorCode == 0 && hdrAlphaProbe.hasGainMap, "alpha JXL HDR probe");
  for (int codec : {1, 3}) {
    expectAlphaRejected(hdrAlphaPath, output + "/native-alpha-hdr-out-" + std::to_string(codec), codec);
  }

  const auto alphaMap = jxlFixture(6, 4, JXL_ORIENT_IDENTITY, 0, true, quadrantPixels(6, 4, true));
  std::vector<uint8_t> alphaMapJhgm(jhgm.begin(), jhgm.begin() + codestreamOffset);
  alphaMapJhgm.insert(alphaMapJhgm.end(), alphaMap.begin(), alphaMap.end());
  const auto alphaMapData = jxlFixture(width, height, JXL_ORIENT_IDENTITY, 0, false, opaque, alphaMapJhgm);
  const auto alphaMapPath = output + "/native-alpha-gainmap.jxl";
  writeFile(alphaMapPath, alphaMapData);
  const auto alphaMapProbe = ultraslim::probeImageMemory(alphaMapData);
  check(alphaMapProbe.errorCode == 0 && alphaMapProbe.hasGainMap, "alpha gain map probe");
  convert(alphaMapPath, output + "/native-alpha-gainmap-out.jxl", 3, false);

  const auto alphaHeifPath = output + "/native-alpha.heic";
  alphaHeifFixture(alphaHeifPath, width, height, alphaPixels);
  auto* alphaContext = heif_context_alloc();
  check(heif_context_read_from_file(alphaContext, alphaHeifPath.c_str(), nullptr).code == heif_error_Ok,
        "read alpha HEIF fixture");
  heif_image_handle* alphaHandle = nullptr;
  check(heif_context_get_primary_image_handle(alphaContext, &alphaHandle).code == heif_error_Ok && alphaHandle &&
        heif_image_handle_has_alpha_channel(alphaHandle), "HEIF fixture has primary alpha");
  heif_image_handle_release(alphaHandle);
  heif_context_free(alphaContext);
  for (int codec : {0, 1, 2, 3}) {
    expectAlphaRejected(alphaHeifPath, output + "/native-alpha-heif-out-" + std::to_string(codec), codec);
  }
  checkPreview(alphaHeifPath);
  std::cout << "PASS: scaledSize, bounded jhgm, JXL orientation and primary-alpha regressions\n";
}

static void thumbnailFixture(const std::string& path, bool largeFtyp = false) {
  auto* ctx = heif_context_alloc(); heif_encoder* enc = nullptr;
  if (largeFtyp) for (uint32_t i = 0; i < 300; ++i) heif_context_add_compatible_brand(ctx, heif_brand2(0x7a000000u + i));
  check(heif_context_get_encoder_for_format(ctx, heif_compression_HEVC, &enc).code == heif_error_Ok, "thumbnail encoder");
  heif_encoder_set_parameter_string(enc, "preset", "ultrafast");
  heif_image* image = nullptr;
  check(heif_image_create(1024, 768, heif_colorspace_RGB, heif_chroma_interleaved_RGB, &image).code == heif_error_Ok, "thumbnail source");
  check(heif_image_add_plane(image, heif_channel_interleaved, 1024, 768, 8).code == heif_error_Ok, "thumbnail plane");
  int stride; auto* pixels = heif_image_get_plane(image, heif_channel_interleaved, &stride);
  for (int y = 0; y < 768; ++y) for (int x = 0; x < 1024; ++x) {
    pixels[y * stride + 3*x] = x % 256; pixels[y * stride + 3*x + 1] = y % 256; pixels[y * stride + 3*x + 2] = 73;
  }
  heif_image_handle *base = nullptr, *thumb = nullptr;
  check(heif_context_encode_image(ctx, image, enc, nullptr, &base).code == heif_error_Ok, "encode thumbnail base");
  check(heif_context_encode_thumbnail(ctx, image, base, enc, nullptr, 192, &thumb).code == heif_error_Ok && thumb, "encode embedded thumbnail");
  check(heif_context_write_to_file(ctx, path.c_str()).code == heif_error_Ok, "save thumbnail fixture");
  heif_image_handle_release(thumb); heif_image_handle_release(base); heif_image_release(image); heif_encoder_release(enc); heif_context_free(ctx);
}
static void rotatedOddFixture(const std::string& path, int codec, int orientation) {
  auto* ctx = heif_context_alloc(); heif_encoder* encoder = nullptr;
  const heif_encoder_descriptor* descriptor = nullptr;
  check(heif_context_get_encoder_descriptors(ctx, codec == 1 ? heif_compression_HEVC : heif_compression_AV1,
      codec == 1 ? "x265" : "aom", &descriptor, 1) == 1, "odd fixture encoder descriptor");
  check(heif_context_get_encoder(ctx, descriptor, &encoder).code == heif_error_Ok, "odd fixture encoder");
  if (codec == 1) heif_encoder_set_parameter_string(encoder, "preset", "ultrafast");
  else heif_encoder_set_parameter_integer(encoder, "speed", 8);
  heif_image* image = nullptr;
  check(heif_image_create(163, 99, heif_colorspace_RGB, heif_chroma_interleaved_RGB, &image).code == heif_error_Ok &&
        heif_image_add_plane(image, heif_channel_interleaved, 163, 99, 8).code == heif_error_Ok, "odd fixture plane");
  int stride; auto* pixels = heif_image_get_plane(image, heif_channel_interleaved, &stride);
  for (int y = 0; y < 99; ++y) for (int x = 0; x < 163; ++x) {
    pixels[y * stride + 3*x] = uint8_t(x * 13 + y * 7);
    pixels[y * stride + 3*x + 1] = uint8_t(x * 3 + y * 11);
    pixels[y * stride + 3*x + 2] = uint8_t(x + y * 17);
  }
  auto* options = heif_encoding_options_alloc(); check(options, "odd fixture options");
  options->image_orientation = static_cast<heif_orientation>(orientation);
  heif_image_handle* handle = nullptr;
  check(heif_context_encode_image(ctx, image, encoder, options, &handle).code == heif_error_Ok &&
        heif_context_write_to_file(ctx, path.c_str()).code == heif_error_Ok, "write rotated odd fixture");
  heif_encoding_options_free(options); heif_image_handle_release(handle);
  heif_image_release(image); heif_encoder_release(encoder); heif_context_free(ctx);
}
int main(int argc, char** argv) {
  check(argc == 3, "usage preview_probe fixtures output");
  const std::string fixtures = argv[1], output = argv[2];
  // The C reader advertises only the requested initial 1024 bytes; the memory
  // reader advertises the entire file. A larger valid ftyp requires fallback.
  const auto largeFtyp = output + "/preview-large-ftyp.heic";
  thumbnailFixture(largeFtyp, true);
  auto largeBytes = load(largeFtyp);
  check(ultraslim::loadBE32(largeBytes.data()) > 1024, "large ftyp fixture");
  auto memoryProbe = ultraslim::probeImageMemory(largeBytes);
  check(memoryProbe.errorCode == 0 && memoryProbe.width == 1024, "memory accepts large ftyp");
  int largeFd = openFile(largeFtyp);
  ultraslim::HeifFdReader largeReader(largeFd); check(largeReader.open(), "large ftyp reader");
  check(ultraslim::probeHeifReader(largeReader, largeReader.container()).errorCode == 4, "direct streaming rejects large ftyp");
  equalProbe(memoryProbe, ultraslim::probeImage(largeFd));
  check(!ultraslim::decodePreviewJpeg(largeFd, 128, 82, 20u * 1024 * 1024).empty(), "preview parse fallback succeeds");
  check(ultraslim::decodePreviewJpeg(largeFd, 513, 82, 20u * 1024 * 1024).empty(), "fallback still admits selected pixels");
  close(largeFd);
  const auto largeInput = output + "/preview-large-ftyp-input.heic";
  writeFile(largeInput, largeBytes);
  largeFd = open(largeInput.c_str(), O_RDWR);
  check(largeFd >= 0 && ftruncate(largeFd, 32u * 1024 * 1024) == 0, "large fallback input");
  check(ultraslim::decodePreviewJpeg(largeFd, 128, 82, 20u * 1024 * 1024).empty(), "fallback input allocation respects budget");
  close(largeFd);
  std::vector<uint8_t> conflicting = {0xff, 0xd8, 0, 0, 'f', 't', 'y', 'p', 'h', 'e', 'i', 'c'};
  writeFile(output + "/probe-conflicting-signature", conflicting);
  parity(output + "/probe-conflicting-signature");
  ultraslim_codec_threads = 2;
  const auto jpg = fixtures + "/apple_gainmap_new.jpg";
  const auto sdr = fixtures + "/minnie-320x240-yuv-icc.jpg";
  parity(jpg); parity(sdr); preview(jpg, 97); jxlBufferLifetime(sdr, output);
  jxlGainmapBufferLifetime(jpg, output);
  // Padded stride and exact no-resize copy must ignore row padding.
  std::vector<uint8_t> packed(7 * 5 * 4), padded(40 * 5, 0xff);
  for (size_t i = 0; i < packed.size(); ++i) packed[i] = uint8_t(i * 31);
  for (int y = 0; y < 5; ++y) memcpy(padded.data() + y * 40, packed.data() + y * 28, 28);
  check(ultraslim::resizeRgbaPlane(padded.data(), 7, 5, 40, 7, 5) == packed, "padded plane exact copy");
  check(ultraslim::resizeRgbaPlane(padded.data(), 7, 5, 40, 3, 2) == ultraslim::resizeRgbaPlane(packed.data(), 7, 5, 28, 3, 2), "padded plane resize");
  check(!ultraslim::previewFits(10001, 10000, 64, UINT64_MAX), "pixel ceiling predecode");
  check(!ultraslim::previewFits(INT32_MAX, INT32_MAX, 64, UINT64_MAX), "overflow dimensions rejected");
  for (int codec : {1, 2}) for (bool compatibility : {false, true}) {
    const auto path = convert(jpg, output + "/probe-hdr-" + std::to_string(codec) + (compatibility ? "-compat" : "-strict"), codec, compatibility);
    parity(path); preview(path, 97); preview(path, 4096); conversionResizeParity(path);
    auto data = load(path);
    int directFd = openFile(path);
    ultraslim::HeifFdReader directReader(directFd); check(directReader.open(), "direct reader opens");
    equalProbe(ultraslim::probeImageMemory(data), ultraslim::probeHeifReader(directReader, directReader.container()));
    ultraslim::SdrImage rejected; std::string rejectedError; bool parseFailed = true;
    check(!ultraslim::decodeHeifPreview({}, rejected, rejectedError, 97, 16u * 1024 * 1024, &directReader, &parseFailed) && !parseFailed,
          "pixel budget rejection cannot request memory fallback");
    close(directFd);
    auto* ctx = heif_context_alloc(); check(heif_context_read_from_memory_without_copy(ctx, data.data(), data.size(), nullptr).code == heif_error_Ok, "read metadata fixture");
    heif_image_handle* base = nullptr; heif_context_get_primary_image_handle(ctx, &base);
    std::vector<uint8_t> metadata(heif_image_handle_get_gain_map_metadata_size(base));
    check(!metadata.empty() && heif_image_handle_get_gain_map_metadata(base, metadata.data()).code == heif_error_Ok, "get ISO metadata");
    heif_image_handle_release(base); heif_context_free(ctx);
    auto where = std::search(data.begin(), data.end(), metadata.begin(), metadata.end());
    check(where != data.end() && where != data.begin(), "locate ISO payload");
    const size_t offset = where - data.begin();
    auto broken = data; broken[offset - 1] = 1; // unsupported ToneMapImage version
    writeFile(path + "-version", broken); parity(path + "-version");
    broken = data; std::fill(broken.begin() + offset, broken.begin() + offset + metadata.size(), 0xff);
    writeFile(path + "-metadata", broken); parity(path + "-metadata");
    broken = data; memcpy(broken.data() + 8, "hevx", 4); // UHDR's unsupported-brand fallback
    writeFile(path + "-brand", broken); parity(path + "-brand");
    broken = data; broken.resize(32); writeFile(path + "-truncated", broken); parity(path + "-truncated");
    // Large trailing data is never read by the streaming metadata probe.
    const auto sparse = path + "-sparse"; writeFile(sparse, data);
    int fd = open(sparse.c_str(), O_RDWR); check(fd >= 0 && ftruncate(fd, 32 * 1024 * 1024) == 0, "sparse fixture");
    ultraslim::HeifFdReader reader(fd); check(reader.open(), "reader opens");
    const int container = reader.container();
    equalProbe(ultraslim::probeImageMemory(data), ultraslim::probeHeifReader(reader, container));
    check(reader.bytesRead < 1024 * 1024, "probe reads metadata, not whole payload");
    check(lseek(fd, 0, SEEK_CUR) == 0, "reader leaves fd cursor unchanged");
    check(ultraslim::readHeifXmp(fd).empty() && lseek(fd, 0, SEEK_CUR) == 0, "streaming XMP probe leaves fd cursor unchanged");
    std::cout << "probe file=32MiB read=" << reader.bytesRead << " bytes\n"; close(fd);
  }
  for (int codec : {1, 2, 3}) {
    auto path = convert(sdr, output + "/probe-sdr-" + std::to_string(codec), codec, false);
    parity(path); preview(path, 97);
    if (codec != 3) conversionResizeParity(path);
  }
  for (int codec : {1, 2}) for (int orientation = 1; orientation <= 8; ++orientation) {
    const auto path = output + "/resize-odd-" + std::to_string(codec) + "-" + std::to_string(orientation);
    rotatedOddFixture(path, codec, orientation); conversionResizeParity(path);
  }
  const auto thumb = output + "/preview-thumbnail.heic"; thumbnailFixture(thumb); parity(thumb);
  int fd = openFile(thumb);
  const uint64_t smallBudget = 20u * 1024 * 1024;
  check(!ultraslim::decodePreviewJpeg(fd, 128, 82, smallBudget).empty(), "budget uses selected thumbnail, not full primary");
  check(ultraslim::decodePreviewJpeg(fd, 513, 82, smallBudget).empty(), "primary over budget rejects when thumbnail insufficient");
  close(fd);
  auto huge = load(thumb);
  for (size_t i = 4; i + 16 <= huge.size(); ++i) if (!memcmp(huge.data() + i, "ispe", 4)) {
    ultraslim::storeBE32(10001, huge.data() + i + 8); ultraslim::storeBE32(10000, huge.data() + i + 12);
  }
  auto hugePrimary = load(thumb);
  for (size_t i = 4; i + 16 <= hugePrimary.size(); ++i) if (!memcmp(hugePrimary.data() + i, "ispe", 4)) {
    if (ultraslim::loadBE32(hugePrimary.data() + i + 8) == 1024) {
      ultraslim::storeBE32(10001, hugePrimary.data() + i + 8); ultraslim::storeBE32(10000, hugePrimary.data() + i + 12);
    }
  }
  writeFile(thumb + "-huge-primary", hugePrimary); fd = openFile(thumb + "-huge-primary");
  check(!ultraslim::decodePreviewJpeg(fd, 128, 82, smallBudget).empty(), "oversized unselected primary does not reject safe thumbnail"); close(fd);
  writeFile(thumb + "-huge", huge); fd = openFile(thumb + "-huge");
  check(ultraslim::decodePreviewJpeg(fd, 128).empty(), "oversized handles rejected before decode"); close(fd);
  // Nonseekable JPEG uses the existing whole-file fallback.
  auto bytes = load(sdr); int pipefd[2]; check(pipe(pipefd) == 0, "pipe");
  std::thread writer([&] { ultraslim::writeAll(pipefd[1], bytes.data(), bytes.size()); close(pipefd[1]); });
  equalProbe(ultraslim::probeImageMemory(bytes), ultraslim::probeImage(pipefd[0])); close(pipefd[0]); writer.join();
  targetedNativeRegressions(fixtures, output);
  std::cout << "PASS: streaming probe parity, metadata failures, bounded previews, direct-plane pixels and thumbnail budget\n";
}
