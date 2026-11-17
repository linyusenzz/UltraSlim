// Regression cases adapted from google/libultrahdr PRs #491 and #499.
// Licensed under Apache-2.0 OR MIT, as upstream.
#include <cmath>
#include <cstring>
#include <cstdlib>
#include <iostream>
#include <string>
#include <vector>
#include "ultrahdr/jpegrutils.h"
using namespace ultrahdr;
struct Check {
  bool ok;
  explicit Check(bool value) : ok(value) {}
  template<class T> Check& operator<<(const T& value) { if (!ok) std::cerr << value; return *this; }
  ~Check() { if (!ok) { std::cerr << "\nFAIL\n"; std::exit(1); } }
};
#define ASSERT_EQ(a,b) Check((a)==(b)) << __LINE__ << ": "
#define EXPECT_EQ(a,b) ASSERT_EQ(a,b)
#define EXPECT_FLOAT_EQ(a,b) Check(std::abs((a)-(b)) <= 1e-6f * std::max(1.0f, std::abs(float(b)))) << __LINE__ << ": "
// Apple XMP without HDRGainMapHeadroom, so that the headroom is read from EXIF.
static std::vector<uint8_t> appleXmpWithoutHeadroom() {
  const std::string nameSpace = "http://ns.adobe.com/xap/1.0/\0";
  const size_t nameSpaceLength = nameSpace.size() + 1;
  const std::string xmp =
      "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF "
      "xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"><rdf:Description "
      "xmlns:HDRGainMap=\"http://ns.apple.com/HDRGainMap/1.0/\">"
      "<HDRGainMap:HDRGainMapVersion>65536</HDRGainMap:HDRGainMapVersion>"
      "</rdf:Description></rdf:RDF></x:xmpmeta>";
  std::vector<uint8_t> xmpData;
  xmpData.insert(xmpData.end(), reinterpret_cast<const uint8_t*>(nameSpace.c_str()),
                 reinterpret_cast<const uint8_t*>(nameSpace.c_str()) + nameSpaceLength);
  xmpData.insert(xmpData.end(), xmp.begin(), xmp.end());
  return xmpData;
}

static void putU16(std::vector<uint8_t>& v, size_t pos, uint16_t x, bool bigEndian) {
  v[pos + (bigEndian ? 0 : 1)] = x >> 8;
  v[pos + (bigEndian ? 1 : 0)] = x & 0xff;
}

static void putU32(std::vector<uint8_t>& v, size_t pos, uint32_t x, bool bigEndian) {
  putU16(v, pos + (bigEndian ? 0 : 2), x >> 16, bigEndian);
  putU16(v, pos + (bigEndian ? 2 : 0), x & 0xffff, bigEndian);
}

// EXIF with little endian TIFF data: IFD0 holds an Exif sub-IFD pointer, the sub-IFD
// holds the Maker Notes, which are Apple Maker Notes with tags 33 and 48 (rationals).
// Offsets of the IFDs and rationals are parameters, relative to the TIFF header and the
// Maker Notes respectively.
struct AppleExifOffsets {
  uint32_t ifd0 = 8;
  uint32_t exifIfd = 26;
  uint32_t makerNotes = 44;
  uint32_t tag33 = 46;
  uint32_t tag48 = 54;
};

static std::vector<uint8_t> appleExif(const AppleExifOffsets& o) {
  const size_t kTiff = 6;
  std::vector<uint8_t> exif(kTiff + 44 + 62, 0);
  memcpy(exif.data(), "Exif\0\0II*\0", 10);
  putU32(exif, kTiff + 4, o.ifd0, false);
  // IFD0 at TIFF + 8: one entry, the Exif sub-IFD pointer.
  putU16(exif, kTiff + 8, 1, false);
  putU16(exif, kTiff + 10, 0x8769, false);
  putU16(exif, kTiff + 12, 4, false);
  putU32(exif, kTiff + 14, 1, false);
  putU32(exif, kTiff + 18, o.exifIfd, false);
  // Exif sub-IFD at TIFF + 26: one entry, the Maker Notes.
  putU16(exif, kTiff + 26, 1, false);
  putU16(exif, kTiff + 28, 0x927c, false);
  putU16(exif, kTiff + 30, 7, false);
  putU32(exif, kTiff + 32, 62, false);
  putU32(exif, kTiff + 36, o.makerNotes, false);
  // Apple Maker Notes at TIFF + 44, big endian, offsets relative to their start.
  const size_t kMaker = kTiff + 44;
  const uint8_t kHeader[] = {'A', 'p', 'p', 'l', 'e', ' ', 'i', 'O', 'S', 0, 0, 1, 'M', 'M'};
  memcpy(&exif[kMaker], kHeader, sizeof(kHeader));
  putU16(exif, kMaker + 14, 2, true);
  putU16(exif, kMaker + 16, 33, true);
  putU16(exif, kMaker + 18, 10, true);
  putU32(exif, kMaker + 20, 1, true);
  putU32(exif, kMaker + 24, o.tag33, true);
  putU16(exif, kMaker + 28, 48, true);
  putU16(exif, kMaker + 30, 10, true);
  putU32(exif, kMaker + 32, 1, true);
  putU32(exif, kMaker + 36, o.tag48, true);
  // Rationals: tag 33 = 1/2, tag 48 = 5/1000.
  putU32(exif, kMaker + 46, 1, true);
  putU32(exif, kMaker + 50, 2, true);
  putU32(exif, kMaker + 54, 5, true);
  putU32(exif, kMaker + 58, 1000, true);
  return exif;
}

static void readAppleHeadroomFromExif() {
  std::vector<uint8_t> xmpData = appleXmpWithoutHeadroom();
  std::vector<uint8_t> exif = appleExif(AppleExifOffsets());
  uhdr_gainmap_metadata_ext_t metadata;
  ASSERT_EQ(getMetadataFromXMP(xmpData.data(), xmpData.size(), exif.data(), exif.size(), &metadata)
                .error_code,
            UHDR_CODEC_OK);
  // maker33 < 1 and maker48 <= 0.01: stops = -20 * maker48 + 1.8.
  EXPECT_FLOAT_EQ(metadata.hdr_capacity_max, (float)pow(2.0, 1.7));
  EXPECT_FLOAT_EQ(metadata.max_content_boost[0], (float)pow(2.0, 1.7));
}

// Every offset read from the EXIF data must be rejected when it points past the end of the
// data, including values that would wrap around when added to the position of the TIFF
// header or of the Maker Notes.
static void rejectAppleExifOffsetsPastEnd() {
  std::vector<uint8_t> xmpData = appleXmpWithoutHeadroom();
  const size_t exifSize = appleExif(AppleExifOffsets()).size();
  const uint32_t kBadOffsets[] = {(uint32_t)exifSize, (uint32_t)exifSize - 1,
                                  0xffffffffu,        0xfffffffau,
                                  0xfffffff2u,        0xffffffe0u,
                                  0xffffffd2u,        0x80000000u};
  for (uint32_t bad : kBadOffsets) {
    for (int field = 0; field < 5; ++field) {
      AppleExifOffsets o;
      uint32_t* fields[] = {&o.ifd0, &o.exifIfd, &o.makerNotes, &o.tag33, &o.tag48};
      *fields[field] = bad;
      std::vector<uint8_t> exif = appleExif(o);
      uhdr_gainmap_metadata_ext_t metadata;
      EXPECT_EQ(
          getMetadataFromXMP(xmpData.data(), xmpData.size(), exif.data(), exif.size(), &metadata)
              .error_code,
          UHDR_CODEC_ERROR)
          << "field " << field << " offset " << bad;
    }
  }
}

static void readAppleHeadroomXmpAsLinearBoost() {
  const std::string nameSpace = "http://ns.adobe.com/xap/1.0/\0";
  const size_t nameSpaceLength = nameSpace.size() + 1;
  const std::string xmp =
      "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF "
      "xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"><rdf:Description "
      "xmlns:HDRGainMap=\"http://ns.apple.com/HDRGainMap/1.0/\">"
      "<HDRGainMap:HDRGainMapVersion>131072</HDRGainMap:HDRGainMapVersion>"
      "<HDRGainMap:HDRGainMapHeadroom>4.0</HDRGainMap:HDRGainMapHeadroom>"
      "</rdf:Description></rdf:RDF></x:xmpmeta>";

  std::vector<uint8_t> xmpData;
  xmpData.reserve(nameSpaceLength + xmp.size());
  xmpData.insert(xmpData.end(), reinterpret_cast<const uint8_t*>(nameSpace.c_str()),
                 reinterpret_cast<const uint8_t*>(nameSpace.c_str()) + nameSpaceLength);
  xmpData.insert(xmpData.end(), reinterpret_cast<const uint8_t*>(xmp.c_str()),
                 reinterpret_cast<const uint8_t*>(xmp.c_str()) + xmp.size());

  uhdr_gainmap_metadata_ext_t metadata;
  ASSERT_EQ(getMetadataFromXMP(xmpData.data(), xmpData.size(), /*exif_data=*/nullptr,
                               /*exif_size=*/0, &metadata)
                .error_code,
            UHDR_CODEC_OK);
  EXPECT_FLOAT_EQ(metadata.max_content_boost[0], 4.0f);
  EXPECT_FLOAT_EQ(metadata.hdr_capacity_max, 4.0f);
}

int main() {
  readAppleHeadroomFromExif();
  rejectAppleExifOffsetsPastEnd();
  readAppleHeadroomXmpAsLinearBoost();
  // Adobe's GainMapMax remains logarithmic; only Apple switches to linear.
  uhdr_gainmap_metadata_ext_t expected(kJpegrVersion);
  for (int c = 0; c < 3; ++c) {
    expected.min_content_boost[c] = 1;
    expected.max_content_boost[c] = 4;
    expected.gamma[c] = 1;
    expected.offset_sdr[c] = expected.offset_hdr[c] = 0;
  }
  expected.hdr_capacity_min = 1;
  expected.hdr_capacity_max = 4;
  expected.use_base_cg = true;
  const char ns[] = "http://ns.adobe.com/xap/1.0/";
  std::string xmp(ns, sizeof(ns));
  xmp += generateXmpForSecondaryImage(expected);
  uhdr_gainmap_metadata_ext_t parsed;
  ASSERT_EQ(getMetadataFromXMP(reinterpret_cast<uint8_t*>(xmp.data()), xmp.size(),
                              nullptr, 0, &parsed).error_code, UHDR_CODEC_OK);
  EXPECT_FLOAT_EQ(parsed.max_content_boost[0], 4.0f);
  EXPECT_FLOAT_EQ(parsed.hdr_capacity_max, 4.0f);
  std::cout << "PASS: Apple linear XMP headroom, EXIF headroom, 40 invalid offsets\n";
}
