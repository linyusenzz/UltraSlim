#include "ultrahdr_api.h"
#include "ultrahdr/jpegr.h"
#include "ultrahdr/jpegrutils.h"
#include "ultrahdr/gainmapmetadata.h"
#include <cmath>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <iterator>
#include <stdexcept>
#include <vector>

using namespace ultrahdr;
static void require(bool ok, const char* message) { if (!ok) throw std::runtime_error(message); }
static void ok(uhdr_error_info_t e) { if (e.error_code) throw std::runtime_error(e.has_detail ? e.detail : "codec error"); }
static std::vector<uint8_t> read(const char* path) {
  std::ifstream file(path, std::ios::binary);
  std::vector<uint8_t> bytes((std::istreambuf_iterator<char>(file)), {});
  require(!bytes.empty(), "empty fixture"); return bytes;
}
static std::vector<uint8_t> xmp(const std::string& xml) {
  const char ns[] = "http://ns.adobe.com/xap/1.0/";
  std::vector<uint8_t> bytes(ns, ns + sizeof(ns));
  bytes.insert(bytes.end(), xml.begin(), xml.end()); return bytes;
}
static uhdr_gainmap_metadata_ext_t metadata(float boost) {
  uhdr_gainmap_metadata_ext_t m;
  m.version = "1.0"; m.use_base_cg = true; m.hdr_capacity_min = 1; m.hdr_capacity_max = boost;
  for (int c = 0; c < 3; ++c) {
    m.min_content_boost[c] = 1; m.max_content_boost[c] = boost; m.gamma[c] = 1;
    m.offset_sdr[c] = m.offset_hdr[c] = 0;
  }
  return m;
}
static void parserCases() {
  JpegR parser; auto m = metadata(4); auto packet = xmp(generateXmpForSecondaryImage(m));
  const char ns[] = "urn:iso:std:iso:ts:21496:-1";
  std::vector<uint8_t> unsupported(ns, ns + sizeof(ns)); unsupported.insert(unsupported.end(), {0, 2});
  auto parse = [&](std::vector<uint8_t> iso, std::vector<uint8_t> xml, float expected, bool success) {
    uhdr_gainmap_metadata_ext_t result;
    auto status = parser.parseGainMapMetadata(iso.data(), iso.size(), xml.data(), xml.size(), nullptr, 0, &result);
    require((status.error_code == UHDR_CODEC_OK) == success, "unexpected metadata parse result");
    if (success) require(std::fabs(result.max_content_boost[0] - expected) < 0.0001f, "wrong selected metadata");
  };
  parse({}, packet, 4, true); parse(unsupported, packet, 4, true);
  auto isoMeta = metadata(2); uhdr_gainmap_metadata_frac fractions{};
  ok(uhdr_gainmap_metadata_frac::gainmapMetadataFloatToFraction(&isoMeta, &fractions));
  std::vector<uint8_t> payload; ok(uhdr_gainmap_metadata_frac::encodeGainmapMetadata(&fractions, payload));
  std::vector<uint8_t> valid(ns, ns + sizeof(ns)); valid.insert(valid.end(), payload.begin(), payload.end());
  parse(valid, packet, 2, true); // Valid ISO still wins over conflicting XMP.
  auto truncated = valid; truncated.resize(sizeof(ns) + 3); parse(truncated, packet, 0, false);
  parse(unsupported, {}, 0, false);
  auto bad = [&](const std::string& before, const std::string& after) {
    auto xml = generateXmpForSecondaryImage(m); auto pos = xml.find(before);
    require(pos != std::string::npos, "missing mutation field"); xml.replace(pos, before.size(), after);
    parse(unsupported, xmp(xml), 0, false);
  };
  bad("hdrgm:Version=\"1.0\"", "hdrgm:Version=\"2.0\"");
  bad("http://ns.adobe.com/hdr-gain-map/1.0/", "http://example.invalid/hdr-gain-map/1.0/");
  bad("hdrgm:Gamma=\"1\"", "hdrgm:Gamma=\"0\"");
  bad("hdrgm:Gamma=\"1\"", "hdrgm:Gamma=\"1junk\"");
  bad("hdrgm:Gamma=\"1\"", "hdrgm:Gamma=\"1\" hdrgm:Gamma=\"2\"");
  bad("hdrgm:GainMapMax=\"2\"", "hdrgm:GainMapMax=\"9999\"");
  bad("hdrgm:BaseRenditionIsHDR=\"False\"", "hdrgm:BaseRenditionIsHDR=\"True\"");
  puts("PASS: ISO priority, XMP-only, unsupported ISO fallback, corrupt ISO and invalid XMP rejection");
}
static void roundtrip(const char* path, const char* destination) {
  auto bytes = read(path); auto* decoder = uhdr_create_decoder();
  uhdr_compressed_image_t input{}; input.data = bytes.data(); input.data_sz = input.capacity = bytes.size();
  input.cg = UHDR_CG_UNSPECIFIED; input.ct = UHDR_CT_UNSPECIFIED; input.range = UHDR_CR_UNSPECIFIED;
  ok(uhdr_dec_set_image(decoder, &input)); ok(uhdr_dec_probe(decoder));
  auto meta = *uhdr_dec_get_gainmap_metadata(decoder);
  const int width = uhdr_dec_get_gainmap_width(decoder), height = uhdr_dec_get_gainmap_height(decoder);
  auto* base = uhdr_dec_get_base_image(decoder); auto* map = uhdr_dec_get_gainmap_image(decoder);
  JpegDecoderHelper original; ok(original.decompressImage(map->data, map->data_sz, DECODE_STREAM));
  auto* encoder = uhdr_create_encoder();
  uhdr_compressed_image_t baseImage{}, mapImage{};
  baseImage.data = base->data; baseImage.data_sz = baseImage.capacity = base->data_sz;
  baseImage.cg = UHDR_CG_DISPLAY_P3; baseImage.ct = UHDR_CT_SRGB; baseImage.range = UHDR_CR_FULL_RANGE;
  mapImage.data = map->data; mapImage.data_sz = mapImage.capacity = map->data_sz;
  mapImage.cg = UHDR_CG_UNSPECIFIED; mapImage.ct = UHDR_CT_UNSPECIFIED; mapImage.range = UHDR_CR_FULL_RANGE;
  ok(uhdr_enc_set_compressed_image(encoder, &baseImage, UHDR_BASE_IMG));
  ok(uhdr_enc_set_gainmap_image(encoder, &mapImage, &meta)); ok(uhdr_encode(encoder));
  auto* encoded = uhdr_get_encoded_stream(encoder);
  auto* verify = uhdr_create_decoder(); ok(uhdr_dec_set_image(verify, encoded));
  ok(uhdr_dec_set_out_img_format(verify, UHDR_IMG_FMT_64bppRGBAHalfFloat));
  ok(uhdr_dec_set_out_color_transfer(verify, UHDR_CT_LINEAR)); ok(uhdr_dec_probe(verify));
  require(uhdr_dec_get_gainmap_width(verify) == width && uhdr_dec_get_gainmap_height(verify) == height, "map dimensions changed");
  require(std::fabs(uhdr_dec_get_gainmap_metadata(verify)->max_content_boost[0] - meta.max_content_boost[0]) < 0.0001f, "boost changed");
  auto* outputMap = uhdr_dec_get_gainmap_image(verify);
  JpegDecoderHelper output; ok(output.decompressImage(outputMap->data, outputMap->data_sz, DECODE_STREAM));
  require(original.getDecompressedImageSize() == output.getDecompressedImageSize(), "map size changed");
  require(memcmp(original.getDecompressedImagePtr(), output.getDecompressedImagePtr(), output.getDecompressedImageSize()) == 0, "map pixels changed");
  const size_t namespaceSize = sizeof("urn:iso:std:iso:ts:21496:-1");
  auto* iso = static_cast<const uint8_t*>(output.getIsoMetadataPtr());
  require(output.getIsoMetadataSize() > namespaceSize + 2 && iso[namespaceSize] == 0 && iso[namespaceSize + 1] == 0, "noncanonical ISO survived");
  ok(uhdr_decode(verify));
  if (destination) { std::ofstream out(destination, std::ios::binary); out.write(static_cast<char*>(encoded->data), encoded->data_sz); require(out.good(), "write failed"); }
  printf("PASS: %s -> canonical JPEG; %dx%d gain map pixels identical; boost %.9g; HDR decode OK\n", path, width, height, meta.max_content_boost[0]);
  uhdr_release_decoder(verify); uhdr_release_encoder(encoder); uhdr_release_decoder(decoder);
}
int main(int argc, char** argv) {
  try { parserCases(); if (argc > 1) roundtrip(argv[1], argc > 2 ? argv[2] : nullptr); return 0; }
  catch (const std::exception& e) { fprintf(stderr, "FAIL: %s\n", e.what()); return 1; }
}
