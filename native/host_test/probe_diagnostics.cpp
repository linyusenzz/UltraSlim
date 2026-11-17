// Verify the optimized metadata path against the exact previous parser. The
// implementation include exposes test-only counters without a production API.
#include "../jni/transcode.cpp"
#include <fcntl.h>
#include <iostream>

static void check(bool ok, const char* message) {
  if (!ok) { std::cerr << "FAIL: " << message << '\n'; std::exit(1); }
}
static std::vector<uint8_t> load(const std::string& path) {
  int fd = open(path.c_str(), O_RDONLY); check(fd >= 0, "open fixture");
  auto bytes = ultraslim::readAll(fd); close(fd); return bytes;
}
static void save(const std::string& path, const std::vector<uint8_t>& bytes) {
  int fd = open(path.c_str(), O_CREAT | O_TRUNC | O_WRONLY, 0600);
  check(fd >= 0 && ultraslim::writeAll(fd, bytes.data(), bytes.size()), "write fixture"); close(fd);
}
static void equal(const ultraslim::ProbeResult& a, const ultraslim::ProbeResult& b) {
  check(a.errorCode == b.errorCode && a.error == b.error && a.container == b.container &&
      a.width == b.width && a.height == b.height && a.hasExif == b.hasExif &&
      a.hasGainMap == b.hasGainMap && a.gainmapWidth == b.gainmapWidth &&
      a.gainmapHeight == b.gainmapHeight && a.maxContentBoost == b.maxContentBoost &&
      a.hdrCapacityMax == b.hdrCapacityMax, "all probe fields match original parser");
}
static void parity(const std::string& path, const std::vector<uint8_t>& bytes, bool requireBounded = false) {
  save(path, bytes);
  int fd = open(path.c_str(), O_RDONLY); check(fd >= 0, "open parity input");
  const auto expected = ultraslim::probeImageMemory(bytes);
  equal(expected, ultraslim::probeImage(fd));
  if (requireBounded) {
    check(lseek(fd, 7, SEEK_SET) == 7, "set production probe cursor");
    equal(expected, ultraslim::probeImage(fd));
    check(lseek(fd, 0, SEEK_CUR) == 7, "production probe selects bounded JPEG path");
    ultraslim::ProbeResult actual;
    check(lseek(fd, 7, SEEK_SET) == 7 && ultraslim::probeJpegReader(fd, actual), "bounded JPEG path succeeds");
    equal(expected, actual);
    check(lseek(fd, 0, SEEK_CUR) == 7, "bounded JPEG path preserves fd position");
  }
  close(fd);
}
static std::vector<uint8_t> jpeg(bool progressive) {
  jpeg_compress_struct c {}; jpeg_error_mgr error {}; c.err = jpeg_std_error(&error);
  jpeg_create_compress(&c); unsigned char* data = nullptr; unsigned long size = 0;
  jpeg_mem_dest(&c, &data, &size);
  c.image_width = 96; c.image_height = 64; c.input_components = 3; c.in_color_space = JCS_RGB;
  jpeg_set_defaults(&c); jpeg_set_quality(&c, 90, TRUE);
  if (progressive) jpeg_simple_progression(&c);
  jpeg_start_compress(&c, TRUE);
  const unsigned char exif[] = {'E','x','i','f',0,0,'I','I',42,0,8,0,0,0,0,0,0,0,0,0};
  jpeg_write_marker(&c, JPEG_APP0 + 1, exif, sizeof(exif));
  std::vector<uint8_t> row(c.image_width * 3);
  while (c.next_scanline < c.image_height) {
    for (size_t x = 0; x < row.size(); ++x) row[x] = uint8_t(x * 31 + c.next_scanline * 47);
    JSAMPROW scanline = row.data(); jpeg_write_scanlines(&c, &scanline, 1);
  }
  jpeg_finish_compress(&c); std::vector<uint8_t> bytes(data, data + size);
  free(data); jpeg_destroy_compress(&c); return bytes;
}
static std::vector<uint8_t> hdrJpeg(bool progressive) {
  auto base = jpeg(progressive), gainmap = jpeg(progressive);
  ultraslim::EncoderPtr encoder(uhdr_create_encoder()); check(bool(encoder), "HDR fixture encoder");
  uhdr_compressed_image_t image {};
  image.data = base.data(); image.data_sz = image.capacity = base.size();
  image.cg = UHDR_CG_BT_709; image.ct = UHDR_CT_SRGB; image.range = UHDR_CR_FULL_RANGE;
  check(uhdr_enc_set_compressed_image(encoder.get(), &image, UHDR_BASE_IMG).error_code == UHDR_CODEC_OK, "HDR fixture base");
  image.data = gainmap.data(); image.data_sz = image.capacity = gainmap.size();
  uhdr_gainmap_metadata_t metadata {};
  for (int c = 0; c < 3; ++c) {
    metadata.min_content_boost[c] = 1; metadata.max_content_boost[c] = 4;
    metadata.gamma[c] = 1; metadata.offset_sdr[c] = metadata.offset_hdr[c] = 1.0f / 64;
  }
  metadata.hdr_capacity_min = 1; metadata.hdr_capacity_max = 4; metadata.use_base_cg = true;
  check(uhdr_enc_set_gainmap_image(encoder.get(), &image, &metadata).error_code == UHDR_CODEC_OK &&
        uhdr_encode(encoder.get()).error_code == UHDR_CODEC_OK, "HDR fixture mux");
  const auto* stream = uhdr_get_encoded_stream(encoder.get());
  const auto* start = static_cast<const uint8_t*>(stream->data);
  return {start, start + stream->data_sz};
}
static void tail(const std::string& path, const std::vector<uint8_t>& bytes, bool hdr) {
  save(path, bytes);
  int fd = open(path.c_str(), O_RDWR); check(fd >= 0, "open tail fixture");
  constexpr size_t size = 64u * 1024 * 1024;
  check(ftruncate(fd, size) == 0, "create 64 MiB trailing payload");
  ultraslim::ProbeResult result; ultraslim::JpegProbeStats stats;
  check(ultraslim::probeJpegReader(fd, result, &stats), "bounded trailing-payload probe");
  equal(ultraslim::probeImageMemory(bytes), result);
  check(stats.headerBytes < 65536, "metadata footprint excludes entropy and video");
  if (hdr) check(stats.bytesRead < bytes.size() + 2 * 65536, "HDR stops after second EOI");
  else check(stats.bytesRead >= size, "SDR scans tail to preserve existing image-selection semantics");
  std::cout << (hdr ? "HDR" : "SDR") << " tail file_bytes=" << size << " bytes_read=" << stats.bytesRead
            << " retained_header_bytes=" << stats.headerBytes << " scanner_buffer_bound=131072\n";
  close(fd);
}
struct TraceRecord { std::string operation, stage; double ms; uint64_t bytes; };
static void collectTrace(const ultraslim::NativeTraceEvent& event, void* context) noexcept {
  static_cast<std::vector<TraceRecord>*>(context)->push_back({event.operation, event.stage, event.durationMs, event.bytes});
}
static void traceParity(const std::string& source, const std::string& output) {
  auto convert = [&](const std::string& target) {
    int in = open(source.c_str(), O_RDONLY), out = open(target.c_str(), O_CREAT | O_TRUNC | O_RDWR, 0600);
    check(in >= 0 && out >= 0, "trace conversion files");
    ultraslim::TranscodeOptions options; options.outCodec = 0; options.quality = 100; options.retrySmaller = true;
    auto result = ultraslim::transcodeImage(in, out, options); close(in); close(out);
    check(result.errorCode == 0, "trace conversion succeeds"); return result;
  };
  convert(output + "/trace-disabled.jpg");
  std::vector<TraceRecord> records;
  ultraslim::setNativeTraceSink(collectTrace, &records);
  const auto result = convert(output + "/trace-enabled.jpg");
  check(!records.empty(), "trace observer receives stages");
  int probeFd = open(source.c_str(), O_RDONLY); check(probeFd >= 0, "trace probe input");
  check(ultraslim::probeImage(probeFd).errorCode == 0, "production traced probe succeeds"); close(probeFd);
  check(std::any_of(records.begin(), records.end(), [](const TraceRecord& r) {
        return r.operation == "probe" && r.stage == "probe.jpeg.bounded";
      }) && std::none_of(records.begin(), records.end(), [](const TraceRecord& r) {
        return r.operation == "probe" && r.stage == "input.read";
      }), "production probe enters bounded JPEG path without whole-file fallback");
  const auto count = records.size();
  std::thread other([&] { int fd = open(source.c_str(), O_RDONLY); ultraslim::probeImage(fd); close(fd); }); other.join();
  check(records.size() == count, "trace sink is local to calling thread");
  ultraslim::setNativeTraceSink(nullptr);
  check(load(output + "/trace-disabled.jpg") == load(output + "/trace-enabled.jpg"), "tracing leaves output bytes unchanged");
  auto find = [&](const char* stage) {
    return std::find_if(records.begin(), records.end(), [&](const TraceRecord& r) { return r.operation == "transcode" && r.stage == stage; });
  };
  check(find("input.read") != records.end() && find("input.read")->bytes == load(source).size(), "trace measures actual input read bytes");
  for (const char* stage : {"probe.memory", "decode.jpeg", "encode.initial", "encode.retry", "total"})
    check(find(stage) != records.end(), "trace covers input, decode, initial encode, retry and total");
  check(find("total")->ms >= result.durationMs, "total timing includes work outside legacy durationMs");
  for (const auto& record : records) check(std::isfinite(record.ms) && record.ms >= 0, "finite stage duration");
}
int main(int argc, char** argv) {
  // Run each mode in a fresh process with /usr/bin/time -v to compare measured
  // peak RSS. This path intentionally does not load fixtures or run other tests.
  if (argc == 4 && std::string(argv[1]) == "--benchmark") {
    int fd = open(argv[3], O_RDONLY); check(fd >= 0, "benchmark input");
    ultraslim::ProbeResult result;
    if (std::string(argv[2]) == "original") result = ultraslim::probeImageMemory(ultraslim::readAll(fd));
    else {
      std::vector<TraceRecord> records; ultraslim::setNativeTraceSink(collectTrace, &records);
      result = ultraslim::probeImage(fd);
      ultraslim::setNativeTraceSink(nullptr);
      uint64_t readBytes = 0;
      bool bounded = false;
      for (const auto& record : records) {
        if (record.stage == "probe.signature" || record.stage == "probe.jpeg.bounded") readBytes += record.bytes;
        if (record.stage == "probe.jpeg.bounded") bounded = true;
        check(record.stage != "input.read", "benchmark must not fall back to full read");
      }
      check(bounded, "benchmark exercises production bounded path");
      std::cout << "bytes_read=" << readBytes << '\n';
    }
    close(fd); check(result.errorCode == 0, "benchmark probe succeeds");
    std::cout << "size=" << result.width << 'x' << result.height << " hdr=" << result.hasGainMap << '\n'; return 0;
  }
  check(argc == 3, "usage: probe_diagnostics fixtures output");
  const std::string fixtures = argv[1], output = argv[2], path = output + "/jpeg-probe-corpus.jpg";
  const auto apple = load(fixtures + "/apple_gainmap_new.jpg");
  parity(path, apple, true);
  tail(output + "/jpeg-probe-hdr-tail.jpg", apple, true);
  for (bool progressive : {false, true}) {
    auto sdr = jpeg(progressive), hdr = hdrJpeg(progressive);
    check(ultraslim::probeImageMemory(hdr).hasGainMap, "generated Google-style HDR fixture");
    for (const auto& source : {sdr, hdr}) {
      parity(path, source, true);
      for (size_t cut : {size_t(3), size_t(31), source.size() / 2, source.size() - 2}) {
        auto broken = source; broken.resize(cut); parity(path, broken);
      }
      // A maximum APP segment crosses the 64 KiB reader boundary. Unknown
      // markers remain byte-for-byte in the metadata parser's input header.
      std::vector<uint8_t> app(65537, 0); app[0] = 0xff; app[1] = 0xef; app[2] = app[3] = 0xff;
      auto boundary = source; boundary.insert(boundary.begin() + 2, app.begin(), app.end()); parity(path, boundary, true);
      auto oversized = source;
      for (int i = 0; i < 17; ++i) oversized.insert(oversized.begin() + 2, app.begin(), app.end());
      parity(path, oversized);
      // Corrupted MPF offsets must retain the pinned parser's current verdict.
      auto brokenMpf = source;
      const std::string mpf("MPF\0", 4);
      auto marker = std::search(brokenMpf.begin(), brokenMpf.end(), mpf.begin(), mpf.end());
      if (marker != brokenMpf.end() && brokenMpf.end() - marker > 16) {
        std::fill(marker + 8, marker + 16, 0xff); parity(path, brokenMpf, true);
      }
      auto extra = source; extra.insert(extra.end(), {0xff, 0xd8, 0xff, 0xe1, 0xff, 0xff}); parity(path, extra);
    }
    // Segment counts must not accumulate in the scanner's JpegInfoBuilder.
    // These post-EOI APP1 markers are ignored by the primary header parser.
    auto repeated = sdr;
    for (int i = 0; i < 20000; ++i) repeated.insert(repeated.end(), {0xff, 0xe1, 0, 8, 'E', 'x', 'i', 'f', 0, 0});
    parity(path, repeated, true);
    auto corruptedMetadata = hdr;
    const std::string iso = "urn:iso:std:iso:ts:21496:-1";
    auto marker = std::search(corruptedMetadata.begin(), corruptedMetadata.end(), iso.begin(), iso.end());
    if (marker != corruptedMetadata.end() && corruptedMetadata.end() - marker > int(iso.size()) + 3) {
      marker[iso.size() + 1] = 0xff; parity(path, corruptedMetadata);
    }
    int pipes[2]; check(pipe(pipes) == 0, "nonseekable input");
    std::thread writer([&] { ultraslim::writeAll(pipes[1], hdr.data(), hdr.size()); close(pipes[1]); });
    equal(ultraslim::probeImageMemory(hdr), ultraslim::probeImage(pipes[0])); close(pipes[0]); writer.join();
  }
  const auto sdr = jpeg(false);
  tail(output + "/jpeg-probe-sdr-tail.jpg", sdr, false);
  save(output + "/jpeg-trace-source.jpg", sdr);
  traceParity(output + "/jpeg-trace-source.jpg", output);
  std::cout << "PASS: bounded JPEG probe parity, malformed/MPF/chunk/pipe cases and opt-in diagnostics\n";
}
