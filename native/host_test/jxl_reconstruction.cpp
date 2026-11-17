// Verify JXL lossless JPEG bitstream reconstruction (SDR + HDR jhgm + EXIF orientation + GPS scrub)
// and streaming Motion Photo tail handling.
#include "../jni/transcode.cpp"
#include <fcntl.h>
#include <cstdlib>
#include <iostream>

#ifndef O_BINARY
#define O_BINARY 0
#endif

static void check(bool ok, const char* message) {
  if (!ok) {
    std::cerr << "FAIL: " << message << '\n';
    std::exit(1);
  }
}

static std::vector<uint8_t> loadFile(const std::string& path) {
  int fd = open(path.c_str(), O_RDONLY | O_BINARY);
  check(fd >= 0, "open file");
  auto data = ultraslim::readAll(fd);
  close(fd);
  return data;
}

static void saveFile(const std::string& path, const std::vector<uint8_t>& data) {
  int fd = open(path.c_str(), O_CREAT | O_TRUNC | O_WRONLY | O_BINARY, 0600);
  check(fd >= 0 && ultraslim::writeAll(fd, data.data(), data.size()), "save file");
  close(fd);
}

static std::vector<uint8_t> reconstructJpegFromJxl(const std::vector<uint8_t>& jxlBytes) {
  JxlDecoder* dec = JxlDecoderCreate(nullptr);
  check(dec != nullptr, "JxlDecoderCreate");
  check(JxlDecoderSubscribeEvents(dec, JXL_DEC_JPEG_RECONSTRUCTION | JXL_DEC_FULL_IMAGE) == JXL_DEC_SUCCESS,
        "subscribe JXL_DEC_JPEG_RECONSTRUCTION");
  check(JxlDecoderSetInput(dec, jxlBytes.data(), jxlBytes.size()) == JXL_DEC_SUCCESS, "set JXL input");
  JxlDecoderCloseInput(dec);

  std::vector<uint8_t> out(64 * 1024);
  size_t used = 0;
  bool sawRecon = false;
  for (;;) {
    JxlDecoderStatus s = JxlDecoderProcessInput(dec);
    if (s == JXL_DEC_JPEG_RECONSTRUCTION) {
      sawRecon = true;
      check(JxlDecoderSetJPEGBuffer(dec, out.data(), out.size()) == JXL_DEC_SUCCESS, "set initial JPEG buffer");
    } else if (s == JXL_DEC_JPEG_NEED_MORE_OUTPUT) {
      const size_t rem = JxlDecoderReleaseJPEGBuffer(dec);
      used = out.size() - rem;
      out.resize(out.size() * 2);
      check(JxlDecoderSetJPEGBuffer(dec, out.data() + used, out.size() - used) == JXL_DEC_SUCCESS,
            "grow JPEG buffer");
    } else if (s == JXL_DEC_FULL_IMAGE || s == JXL_DEC_SUCCESS) {
      if (sawRecon) {
        const size_t rem = JxlDecoderReleaseJPEGBuffer(dec);
        used = out.size() - rem;
      }
      break;
    } else {
      JxlDecoderDestroy(dec);
      check(false, "unexpected JxlDecoder status during JPEG reconstruction");
    }
  }
  JxlDecoderDestroy(dec);
  check(sawRecon && used > 0, "JXL contains reconstructible JPEG");
  out.resize(used);
  return out;
}

// Minimal valid Exif APP1 payload with Orientation=6 and a GPS IFD tag (0x8825).
static std::vector<uint8_t> makeOrientedGpsExif(uint16_t orientation) {
  // "Exif\0\0" + Little-endian TIFF: IFD0 at offset 8 with 2 entries:
  //   0x0112 Orientation SHORT(3) count=1 value=orientation
  //   0x8825 GPSIFD      LONG(4)  count=1 offset=38
  // Next IFD = 0 (offset 34..37)
  // GPS IFD at offset 38: 1 entry (0x0000 GPSVersionID BYTE count=4 value=2.3.0.0), next=0
  return {
      'E','x','i','f',0,0,
      'I','I', 42,0, 8,0,0,0,
      2,0,
      0x12,0x01, 3,0, 1,0,0,0, static_cast<uint8_t>(orientation & 0xFF), static_cast<uint8_t>(orientation >> 8), 0,0,
      0x25,0x88, 4,0, 1,0,0,0, 38,0,0,0,
      0,0,0,0,
      1,0,
      0x00,0x00, 1,0, 4,0,0,0, 2,3,0,0,
      0,0,0,0
  };
}

static std::vector<uint8_t> injectExifIntoJpeg(const std::vector<uint8_t>& src, const std::vector<uint8_t>& app1Payload) {
  std::vector<uint8_t> out;
  out.push_back(0xFF);
  out.push_back(0xD8);
  const uint16_t segLen = static_cast<uint16_t>(app1Payload.size() + 2);
  out.push_back(0xFF);
  out.push_back(0xE1);
  out.push_back(static_cast<uint8_t>(segLen >> 8));
  out.push_back(static_cast<uint8_t>(segLen & 0xFF));
  out.insert(out.end(), app1Payload.begin(), app1Payload.end());
  out.insert(out.end(), src.begin() + 2, src.end());
  return out;
}

int main(int argc, char** argv) {
  check(argc == 3, "usage: jxl_reconstruction fixtures output_dir");
  const std::string fixtures = argv[1];
  const std::string outDir = argv[2];
  ultraslim_codec_threads = 2;

  // 1. SDR JPEG -> JXL lossless reconstruction (100% byte-for-byte identical)
  const std::string sdrPath = fixtures + "/minnie-320x240-yuv-icc.jpg";
  const auto sdrOrig = loadFile(sdrPath);
  {
    const std::string jxlOut = outDir + "/recon-sdr.jxl";
    int inFd = open(sdrPath.c_str(), O_RDONLY | O_BINARY);
    int outFd = open(jxlOut.c_str(), O_CREAT | O_TRUNC | O_RDWR | O_BINARY, 0600);
    ultraslim::TranscodeOptions opt;
    opt.outCodec = 3;
    opt.jxlJpegReconstruction = true;
    opt.speedLevel = 2;
    auto res = ultraslim::transcodeImage(inFd, outFd, opt);
    close(inFd);
    close(outFd);
    check(res.errorCode == 0 && !res.hasGainMap && res.width == 320 && res.height == 240,
          "SDR JXL reconstruction succeeds");
    const auto jxlBytes = loadFile(jxlOut);
    check(reconstructJpegFromJxl(jxlBytes) == sdrOrig,
          "SDR JXL reconstructs exact byte-for-byte original JPEG");
  }

  // 2. Ultra HDR Gain Map JPEG -> JXL lossless reconstruction + jhgm preservation
  const std::string hdrPath = fixtures + "/apple_gainmap_new.jpg";
  const auto hdrOrig = loadFile(hdrPath);
  {
    ultraslim::DecoderPtr dec;
    const auto hdrProbe = ultraslim::probeImageMemory(hdrOrig, &dec);
    check(hdrProbe.hasGainMap && bool(dec), "HDR fixture has gain map");
    const auto expectedBase = ultraslim::copyBlock(uhdr_dec_get_base_image(dec.get()));
    const auto gmBytes = ultraslim::copyBlock(uhdr_dec_get_gainmap_image(dec.get()));
    ultraslim::SdrImage expectedGm;
    std::string err;
    check(ultraslim::decodeJpegSdr(gmBytes, expectedGm, err), "decode reference gain map");

    const std::string jxlOut = outDir + "/recon-hdr.jxl";
    int inFd = open(hdrPath.c_str(), O_RDONLY | O_BINARY);
    int outFd = open(jxlOut.c_str(), O_CREAT | O_TRUNC | O_RDWR | O_BINARY, 0600);
    ultraslim::TranscodeOptions opt;
    opt.outCodec = 3;
    opt.jxlJpegReconstruction = true;
    opt.keepExif = true;
    opt.keepLocation = true;
    opt.speedLevel = 2;
    auto res = ultraslim::transcodeImage(inFd, outFd, opt);
    close(inFd);
    close(outFd);
    check(res.errorCode == 0 && res.hasGainMap && res.hasExif &&
              res.width == hdrProbe.width && res.height == hdrProbe.height,
          "HDR JXL reconstruction succeeds with gain map and EXIF");

    const auto jxlBytes = loadFile(jxlOut);
    const auto jxlProbe = ultraslim::probeImageMemory(jxlBytes);
    check(jxlProbe.errorCode == 0 && jxlProbe.hasGainMap && jxlProbe.hasExif &&
              jxlProbe.width == hdrProbe.width && jxlProbe.height == hdrProbe.height,
          "HDR JXL output probes with matching dimensions, EXIF and gain map");
    check(reconstructJpegFromJxl(jxlBytes) == expectedBase,
          "HDR JXL reconstructs exact byte-for-byte primary JPEG");

    ultraslim::SdrImage decodedBase, decodedGm;
    std::vector<uint8_t> jhgm;
    check(ultraslim::decodeJxlToSdr(jxlBytes, decodedBase, &jhgm, err), "decode HDR JXL");
    ultraslim::JxlGainMapView view;
    check(ultraslim::parseJxlGainMap(jhgm, view), "parse jhgm box");
    const std::vector<uint8_t> gmStream(view.codestream.data(), view.codestream.data() + view.codestream.size());
    check(ultraslim::decodeJxlToSdr(gmStream, decodedGm, nullptr, err) &&
              decodedGm.width == expectedGm.width && decodedGm.height == expectedGm.height &&
              decodedGm.rgba == expectedGm.rgba,
          "HDR JXL preserves exact gain-map pixels");
  }

  // Both XMP namespaces can carry coordinates independently of EXIF.
  for (const char* ns : {"http://ns.adobe.com/xap/1.0/", "http://ns.adobe.com/xmp/extension/"}) {
    std::vector<uint8_t> payload(ns, ns + strlen(ns) + 1);
    const std::string gps = "exif:GPSLatitude=12,34.5N";
    payload.insert(payload.end(), gps.begin(), gps.end());
    const auto withXmp = injectExifIntoJpeg(sdrOrig, payload);
    std::vector<uint8_t> primary;
    int w, h, orientation;
    bool hasExif;
    std::string err;
    for (bool keepExif : {false, true}) {
      check(ultraslim::prepareJpegForJxlReconstruction(withXmp, keepExif, false,
            w, h, orientation, hasExif, primary, err), "prepare private JPEG");
      check(std::search(primary.begin(), primary.end(), gps.begin(), gps.end()) == primary.end(),
            "remove XMP location from reconstructible JPEG");
    }
    check(ultraslim::prepareJpegForJxlReconstruction(withXmp, true, true,
          w, h, orientation, hasExif, primary, err) && primary == withXmp, "preserve permitted XMP");
  }
  {
    ultraslim::SdrImage img;
    img.hasProfile = true;
    img.profile.color_primaries = heif_color_primaries_ITU_R_BT_709_5;
    img.profile.transfer_characteristics = heif_transfer_characteristic_linear;
    img.width = img.height = 1;
    img.rgba = {128,128,128,255};
    int cs = -1;
    check(ultraslim::prepareBitmapColor(img, cs) && cs == 3 && img.rgba[0] == 128,
          "linear sRGB preview retains linear transfer");
    img.profile.color_primaries = heif_color_primaries_SMPTE_EG_432_1;
    img.rgba = {128,128,128,73};
    check(ultraslim::prepareBitmapColor(img, cs) && cs == 1 &&
              std::abs(int(img.rgba[0]) - 188) <= 2 && img.rgba[3] == 73,
          "linear P3 data converts to Display P3 without changing alpha");
  }

  // 3. Orientation=6 + GPS scrub + single-rotation JXL decode (no double rotation!)
  {
    const auto exif6Gps = makeOrientedGpsExif(6);
    check(ultraslim::ExifEditor(const_cast<std::vector<uint8_t>&>(exif6Gps)).hasGps(), "fixture EXIF has GPS");
    const auto orientedJpeg = injectExifIntoJpeg(sdrOrig, exif6Gps);
    const std::string orientedIn = outDir + "/oriented6-gps.jpg";
    const std::string orientedJxl = outDir + "/oriented6-scrubbed.jxl";
    saveFile(orientedIn, orientedJpeg);

    int inFd = open(orientedIn.c_str(), O_RDONLY | O_BINARY);
    int outFd = open(orientedJxl.c_str(), O_CREAT | O_TRUNC | O_RDWR | O_BINARY, 0600);
    ultraslim::TranscodeOptions opt;
    opt.outCodec = 3;
    opt.jxlJpegReconstruction = true;
    opt.keepExif = true;
    opt.keepLocation = false;  // scrub GPS!
    opt.speedLevel = 2;
    auto res = ultraslim::transcodeImage(inFd, outFd, opt);
    close(inFd);
    close(outFd);
    check(res.errorCode == 0 && res.width == 240 && res.height == 320 && res.hasExif,
          "Oriented JPEG->JXL reports rotated display dimensions 240x320");

    const auto jxlBytes = loadFile(orientedJxl);
    const auto recon = reconstructJpegFromJxl(jxlBytes);
    ultraslim::SdrImage reconSdr;
    std::string err;
    check(ultraslim::decodeJpegSdr(recon, reconSdr, err), "reconstructed GPS-scrubbed JPEG decodes");
    check(!ultraslim::ExifEditor(reconSdr.exif).hasGps() &&
              ultraslim::ExifEditor(reconSdr.exif).orientation() == 6,
          "GPS scrubbed while Orientation=6 retained in reconstructed JPEG");

    // Preview and JXL->SDR transcode must NOT double-rotate the JXL!
    int jxlFd = open(orientedJxl.c_str(), O_RDONLY | O_BINARY);
    const auto previewBmp = ultraslim::decodePreviewRgba(jxlFd, 512);
    close(jxlFd);
    check(previewBmp.width == 240 && previewBmp.height == 320,
          "JXL preview decodes at single-rotated 240x320 without double rotation");

    // Non-JPEG input (JXL -> JXL) with jxlJpegReconstruction=true falls back cleanly to standard JXL encode
    const std::string jxlReenc = outDir + "/oriented6-reenc.jxl";
    int reIn = open(orientedJxl.c_str(), O_RDONLY | O_BINARY);
    int reOut = open(jxlReenc.c_str(), O_CREAT | O_TRUNC | O_RDWR | O_BINARY, 0600);
    opt.quality = 90;
    auto reRes = ultraslim::transcodeImage(reIn, reOut, opt);
    close(reIn);
    close(reOut);
    check(reRes.errorCode == 0 && reRes.width == 240 && reRes.height == 320,
          "Non-JPEG input falls back to standard JXL encode without double rotation");

    // !keepExif on oriented JPEG strips camera/GPS EXIF while retaining display orientation 6
    const std::string noExifJxl = outDir + "/oriented6-noexif.jxl";
    int neIn = open(orientedIn.c_str(), O_RDONLY | O_BINARY);
    int neOut = open(noExifJxl.c_str(), O_CREAT | O_TRUNC | O_RDWR | O_BINARY, 0600);
    opt.keepExif = false;
    auto neRes = ultraslim::transcodeImage(neIn, neOut, opt);
    close(neIn);
    close(neOut);
    check(neRes.errorCode == 0 && neRes.width == 240 && neRes.height == 320,
          "!keepExif JXL reconstruction preserves display orientation 240x320");
    const auto neRecon = reconstructJpegFromJxl(loadFile(noExifJxl));
    ultraslim::SdrImage neSdr;
    check(ultraslim::decodeJpegSdr(neRecon, neSdr, err) &&
              !ultraslim::ExifEditor(neSdr.exif).hasGps() &&
              ultraslim::ExifEditor(neSdr.exif).orientation() == 6,
          "!keepExif reconstructed JPEG retains only minimal Orientation=6 APP1");
  }

  // 4. Motion Photo trailing MP4 early-stop in probeJpegReader, maxInputBytes slice & streaming preview
  {
    auto mp = sdrOrig;
    const uint8_t ftypBox[] = {0, 0, 0, 24, 'f', 't', 'y', 'p', 'm', 'p', '4', '2', 0, 0, 0, 0, 'm', 'p', '4', '2', 'i', 's', 'o', 'm'};
    mp.insert(mp.end(), std::begin(ftypBox), std::end(ftypBox));
    mp.resize(8u * 1024 * 1024, 0x5A);  // 8 MiB simulated video payload
    const std::string mpPath = outDir + "/motion-photo-sdr.jpg";
    saveFile(mpPath, mp);

    int fd = open(mpPath.c_str(), O_RDONLY | O_BINARY);
    ultraslim::ProbeResult pr;
    ultraslim::JpegProbeStats stats;
    check(ultraslim::probeJpegReader(fd, pr, &stats) && pr.errorCode == 0 && pr.width == 320 && pr.height == 240,
          "Motion Photo bounded probe succeeds");
    check(stats.bytesRead < sdrOrig.size() + 2 * 65536,
          "Motion Photo probe stops at ftyp after primary EOI without scanning MP4");

    // Even if total file size > budget, streaming JPEG preview succeeds!
    const auto bmp = ultraslim::decodePreviewRgba(fd, 256, 20u * 1024 * 1024);
    check(bmp.width == 256 && bmp.height == 192 && !bmp.rgba.empty(),
          "Streaming JPEG preview succeeds on Motion Photo");

    // maxInputBytes slices only the still prefix without reading the 8 MiB video tail
    const std::string mpJxl = outDir + "/motion-photo-still.jxl";
    int outFd = open(mpJxl.c_str(), O_CREAT | O_TRUNC | O_RDWR | O_BINARY, 0600);
    ultraslim::TranscodeOptions opt;
    opt.outCodec = 3;
    opt.jxlJpegReconstruction = true;
    opt.maxInputBytes = static_cast<int64_t>(sdrOrig.size());
    opt.speedLevel = 2;
    auto mpRes = ultraslim::transcodeImage(fd, outFd, opt);
    close(outFd);
    close(fd);
    check(mpRes.errorCode == 0 && mpRes.inputBytes == static_cast<long>(sdrOrig.size()) &&
              reconstructJpegFromJxl(loadFile(mpJxl)) == sdrOrig,
          "maxInputBytes slices exact still JPEG prefix for Motion Photo transcode");
  }

  // 5. Parallel orientPixels parity across all 8 orientations (odd dimensions, RGBA + gray)
  {
    for (int ori = 2; ori <= 8; ++ori) {
      ultraslim::SdrImage a, b;
      a.width = b.width = 73;
      a.height = b.height = 51;
      a.rgba.resize(73 * 51 * 4);
      b.gray.resize(73 * 51);
      for (int i = 0; i < 73 * 51; ++i) {
        const uint8_t v = static_cast<uint8_t>((i * 37 + 11) & 0xFF);
        a.rgba[i * 4 + 0] = v;
        a.rgba[i * 4 + 1] = static_cast<uint8_t>(v ^ 0x55);
        a.rgba[i * 4 + 2] = static_cast<uint8_t>(v + 17);
        a.rgba[i * 4 + 3] = 255;
        b.gray[i] = v;
      }
      ultraslim::orientPixels(a, ori);
      ultraslim::orientPixels(b, ori);
      check(a.width == b.width && a.height == b.height, "orientPixels RGBA/gray dimensions match");
      for (int i = 0; i < a.width * a.height; ++i) {
        check(a.rgba[i * 4] == b.gray[i], "orientPixels RGBA and 1-channel gray match pixel-for-pixel");
      }
    }
  }

  std::cout << "PASS: JXL lossless JPEG reconstruction (SDR/HDR/EXIF/GPS), single-rotation JXL, Motion Photo tail early-stop, and orientPixels parity\n";
  return 0;
}
