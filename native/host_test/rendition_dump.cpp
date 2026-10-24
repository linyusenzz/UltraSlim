// Dumps the SDR and HDR renditions of a gain map image as raw RGBA8888 files.
// usage: rendition_dump <input> <out_prefix>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "ultrahdr_api.h"
#include "ultrahdr/gainmapmath.h"

namespace {

bool writeRaw(const char* path, const std::vector<uint8_t>& data, int w, int h) {
  FILE* f = fopen(path, "wb");
  if (!f) return false;
  fprintf(f, "%d %d\n", w, h);
  fwrite(data.data(), 1, data.size(), f);
  fclose(f);
  return true;
}

uhdr_codec_private_t* makeDecoder(const std::vector<uint8_t>& data, uhdr_img_fmt_t fmt,
                                  uhdr_color_transfer_t ct) {
  uhdr_codec_private_t* dec = uhdr_create_decoder();
  uhdr_compressed_image_t img {};
  img.data = const_cast<uint8_t*>(data.data());
  img.data_sz = img.capacity = data.size();
  img.cg = UHDR_CG_UNSPECIFIED;
  img.ct = UHDR_CT_UNSPECIFIED;
  img.range = UHDR_CR_UNSPECIFIED;
  if (uhdr_dec_set_image(dec, &img).error_code != UHDR_CODEC_OK) return nullptr;
  uhdr_dec_set_out_img_format(dec, fmt);
  uhdr_dec_set_out_color_transfer(dec, ct);
  return dec;
}

}  // namespace

int main(int argc, char** argv) {
  if (argc < 3) {
    fprintf(stderr, "usage: %s <input> <out_prefix>\n", argv[0]);
    return 2;
  }
  FILE* f = fopen(argv[1], "rb");
  if (!f) {
    fprintf(stderr, "cannot open %s\n", argv[1]);
    return 1;
  }
  fseek(f, 0, SEEK_END);
  long sz = ftell(f);
  fseek(f, 0, SEEK_SET);
  std::vector<uint8_t> data(sz);
  fread(data.data(), 1, sz, f);
  fclose(f);

  std::string prefix = argv[2];

  // metadata dump
  if (uhdr_codec_private_t* dec = makeDecoder(data, UHDR_IMG_FMT_32bppRGBA8888, UHDR_CT_SRGB)) {
    if (uhdr_dec_probe(dec).error_code == UHDR_CODEC_OK) {
      if (uhdr_gainmap_metadata_t* m = uhdr_dec_get_gainmap_metadata(dec)) {
        printf("meta: minBoost=[%.3f %.3f %.3f] maxBoost=[%.3f %.3f %.3f] gamma=[%.3f %.3f %.3f]\n",
               m->min_content_boost[0], m->min_content_boost[1], m->min_content_boost[2],
               m->max_content_boost[0], m->max_content_boost[1], m->max_content_boost[2],
               m->gamma[0], m->gamma[1], m->gamma[2]);
        printf("      offSdr=[%.4f %.4f %.4f] offHdr=[%.4f %.4f %.4f] capMin=%.2f capMax=%.2f use_base_cg=%d\n",
               m->offset_sdr[0], m->offset_sdr[1], m->offset_sdr[2],
               m->offset_hdr[0], m->offset_hdr[1], m->offset_hdr[2],
               m->hdr_capacity_min, m->hdr_capacity_max, m->use_base_cg);
      }
    }
    uhdr_release_decoder(dec);
  }

  // SDR rendition
  if (uhdr_codec_private_t* dec = makeDecoder(data, UHDR_IMG_FMT_32bppRGBA8888, UHDR_CT_SRGB)) {
    if (uhdr_decode(dec).error_code == UHDR_CODEC_OK) {
      uhdr_raw_image_t* raw = uhdr_get_decoded_image(dec);
      std::vector<uint8_t> pixels(raw->w * raw->h * 4);
      const size_t srcStride = static_cast<size_t>(raw->stride[0]) * 4;
      for (size_t y = 0; y < raw->h; y++) {
        memcpy(pixels.data() + y * raw->w * 4,
               static_cast<uint8_t*>(raw->planes[0]) + y * srcStride, raw->w * 4);
      }
      writeRaw((prefix + "_sdr.raw").c_str(), pixels, raw->w, raw->h);
      printf("sdr: %zux%zu cg=%d ct=%d\n", raw->w, raw->h, raw->cg, raw->ct);
    } else {
      fprintf(stderr, "sdr decode failed\n");
    }
    uhdr_release_decoder(dec);
  }

  // HDR rendition (RGBA1010102 HLG) → simple tonemap to RGBA8888
  if (uhdr_codec_private_t* dec = makeDecoder(data, UHDR_IMG_FMT_32bppRGBA1010102, UHDR_CT_HLG)) {
    if (uhdr_decode(dec).error_code == UHDR_CODEC_OK) {
      uhdr_raw_image_t* raw = uhdr_get_decoded_image(dec);
      std::vector<uint8_t> pixels(raw->w * raw->h * 4);
      const size_t srcStride = static_cast<size_t>(raw->stride[0]);
      double linSum[3] = {0.0, 0.0, 0.0};
      size_t overSdrWhite = 0, overStrong = 0, overPeak = 0, totalPx = 0;
      {
        const uint32_t* p32 = reinterpret_cast<const uint32_t*>(raw->planes[0]);
        uint32_t mx = 0;
        for (size_t y = 0; y < raw->h; y += 16)
          for (size_t x = 0; x < raw->w; x += 16)
            mx = std::max(mx, p32[y * srcStride + x] & 0x3FF);
        printf("hdr raw: w=%zu h=%zu stride=%zu maxR10=%u cg=%d ct=%d range=%d\n", raw->w, raw->h,
               srcStride, mx, raw->cg, raw->ct, raw->range);
      }
      for (size_t y = 0; y < raw->h; y++) {
        const uint32_t* row =
            reinterpret_cast<const uint32_t*>(static_cast<uint8_t*>(raw->planes[0]) + y * srcStride * 4);
        uint8_t* dst = pixels.data() + y * raw->w * 4;
        for (size_t x = 0; x < raw->w; x++) {
          uint32_t px = row[x];
          ultrahdr::Color c;
          c.r = (px & 0x3FF) / 1023.0f;
          c.g = ((px >> 10) & 0x3FF) / 1023.0f;
          c.b = ((px >> 20) & 0x3FF) / 1023.0f;
          // HLG 信号 0.75 ≈ SDR 参考白（203nit），0.9 以上为强高光，1.0 = 1000nit 峰值
          float sig = std::max(c.r, std::max(c.g, c.b));
          totalPx++;
          if (sig > 0.75f) overSdrWhite++;
          if (sig > 0.90f) overStrong++;
          if (sig >= 0.999f) overPeak++;
          c = ultrahdr::hlgInvOetf(c);
          linSum[0] += c.r;
          linSum[1] += c.g;
          linSum[2] += c.b;
          // exposure + Reinhard tonemap + gamma
          const float exposure = 1.0f;
          auto tm = [&](float v) -> uint8_t {
            v = v * exposure;
            v = v / (1.0f + v);
            v = powf(v, 1.0f / 2.2f);
            return static_cast<uint8_t>(v * 255.0f + 0.5f);
          };
          dst[0] = tm(c.r);
          dst[1] = tm(c.g);
          dst[2] = tm(c.b);
          dst[3] = 255;
        }
      }
      writeRaw((prefix + "_hdr.raw").c_str(), pixels, raw->w, raw->h);
      printf("hdr: %zux%zu cg=%d ct=%d  linearMean=[%.4f %.4f %.4f]\n", raw->w, raw->h, raw->cg,
             raw->ct, linSum[0] / (raw->w * raw->h), linSum[1] / (raw->w * raw->h),
             linSum[2] / (raw->w * raw->h));
      printf("hdr高光: >0.75(SDR白)=%.3f%%  >0.90=%.3f%%  ≈1.0(峰值)=%.3f%%\n",
             100.0 * overSdrWhite / totalPx, 100.0 * overStrong / totalPx, 100.0 * overPeak / totalPx);
    } else {
      fprintf(stderr, "hdr decode failed\n");
    }
    uhdr_release_decoder(dec);
  }
  return 0;
}
