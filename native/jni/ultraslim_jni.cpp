#include <stdexcept>
#include <jni.h>
#include <exception>
#include <algorithm>
#include <sys/stat.h>
#include <time.h>

#include <cstring>

#if defined(__ANDROID__)
#include <android/bitmap.h>
#endif

#if defined(__ARM_NEON)
#include <arm_neon.h>
#endif

#include "transcode.h"

namespace {

// FindClass/GetMethodID 每次调用的开销虽小，但转码循环里每图至少一次；
// 缓存为全局引用 + 静态初始化（C++11 起线程安全）。
struct ProbeResultIds {
  jclass cls;
  jmethodID ctor;
};

const ProbeResultIds& probeResultIds(JNIEnv* env) {
  static const ProbeResultIds ids = [env] {
    jclass local = env->FindClass("app/ultraslim/bridge/ProbeResult");
    jclass global = static_cast<jclass>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    return ProbeResultIds{global, env->GetMethodID(global, "<init>", "(ILjava/lang/String;ZIIIIIZFF)V")};
  }();
  return ids;
}

struct TranscodeResultIds {
  jclass cls;
  jmethodID ctor;
};

const TranscodeResultIds& transcodeResultIds(JNIEnv* env) {
  static const TranscodeResultIds ids = [env] {
    jclass local = env->FindClass("app/ultraslim/bridge/TranscodeResult");
    jclass global = static_cast<jclass>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    return TranscodeResultIds{global, env->GetMethodID(global, "<init>", "(ILjava/lang/String;ZIIIIJJDZZF)V")};
  }();
  return ids;
}

jobject toProbeResult(JNIEnv* env, const ultraslim::ProbeResult& r) {
  const auto& ids = probeResultIds(env);
  jstring err = env->NewStringUTF(r.error.c_str());
  return env->NewObject(ids.cls, ids.ctor, r.errorCode, err, r.hasGainMap ? JNI_TRUE : JNI_FALSE,
                        r.container, r.width, r.height, r.gainmapWidth, r.gainmapHeight,
                        r.hasExif ? JNI_TRUE : JNI_FALSE, r.maxContentBoost, r.hdrCapacityMax);
}

jobject toTranscodeResult(JNIEnv* env, const ultraslim::TranscodeResult& r) {
  const auto& ids = transcodeResultIds(env);
  jstring err = env->NewStringUTF(r.error.c_str());
  return env->NewObject(ids.cls, ids.ctor, r.errorCode, err,
                        r.inputHadGainMap ? JNI_TRUE : JNI_FALSE, r.width, r.height,
                        r.gainmapWidth, r.gainmapHeight, static_cast<jlong>(r.inputBytes),
                        static_cast<jlong>(r.outputBytes), static_cast<jdouble>(r.durationMs),
                        r.hasGainMap ? JNI_TRUE : JNI_FALSE, r.hasExif ? JNI_TRUE : JNI_FALSE,
                        r.maxContentBoost);
}

}  // namespace

extern "C" {

JNIEXPORT jobject JNICALL Java_app_ultraslim_bridge_UltraHdrBridge_nativeProbe(JNIEnv* env,
                                                                               jclass /*unused*/,
                                                                               jint fd) {
  try { return toProbeResult(env, ultraslim::probeImage(static_cast<int>(fd))); }
  catch(const std::exception&) { ultraslim::ProbeResult r; r.errorCode=99; r.error="图片过大或内存不足"; return toProbeResult(env,r); }
}

JNIEXPORT jobject JNICALL Java_app_ultraslim_bridge_UltraHdrBridge_nativeTranscode(
    JNIEnv* env, jclass /*unused*/, jint inFd, jint outFd, jint outCodec, jint quality,
    jint gainmapQuality, jint gainmapScale, jboolean multiChannelGainmap, jboolean keepExif,
    jboolean allowSdrTranscode, jint speedLevel, jint resizePercent, jint codecThreads,
    jboolean keepLocation, jbyteArray xmp, jboolean retrySmaller,
    jboolean avifGainmapCompatibility, jboolean heicGainmapCompatibility,
    jboolean jxlJpegReconstruction, jlong maxInputBytes) {
  try {
    ultraslim::TranscodeOptions o;
    if (xmp) {
      const auto size = env->GetArrayLength(xmp);
      if (size > 1024 * 1024) throw std::runtime_error("XMP 超过容量限制");
      o.xmp.resize(size);
      env->GetByteArrayRegion(xmp, 0, size, reinterpret_cast<jbyte*>(o.xmp.data()));
      if (env->ExceptionCheck()) return nullptr;
    }
    o.retrySmaller = retrySmaller == JNI_TRUE;
    o.avifGainmapCompatibility = avifGainmapCompatibility == JNI_TRUE;
    o.heicGainmapCompatibility = heicGainmapCompatibility == JNI_TRUE;
    o.jxlJpegReconstruction = jxlJpegReconstruction == JNI_TRUE;
    o.maxInputBytes = static_cast<int64_t>(maxInputBytes);
    o.outCodec = outCodec;
    o.quality = quality;
    o.gainmapQuality = gainmapQuality;
    o.gainmapScale = gainmapScale;
    o.multiChannelGainmap = multiChannelGainmap == JNI_TRUE;
    o.keepExif = keepExif == JNI_TRUE;
    o.keepLocation = keepLocation == JNI_TRUE;
    o.allowSdrTranscode = allowSdrTranscode == JNI_TRUE;
    o.speedLevel = speedLevel;
    o.resizePercent = resizePercent;
    o.codecThreads = codecThreads;
    return toTranscodeResult(env, ultraslim::transcodeImage(inFd, outFd, o));
  } catch (const std::exception& e) {
    ultraslim::TranscodeResult r;
    r.errorCode = 99;
    r.error = std::string("JNI 异常: ") + e.what();
    return toTranscodeResult(env, r);
  } catch (...) {
    ultraslim::TranscodeResult r;
    r.errorCode = 99;
    r.error = "JNI 未知异常";
    return toTranscodeResult(env, r);
  }
}

JNIEXPORT jboolean JNICALL Java_app_ultraslim_bridge_UltraHdrBridge_nativeUpdateFileTime(
    JNIEnv*, jclass, jint fd, jlong milliseconds) {
  struct stat info{};
  if (fd < 0 || milliseconds <= 0 || fstat(fd, &info) != 0 || !S_ISREG(info.st_mode)) return JNI_FALSE;
  struct timespec times[2]{};
  times[0].tv_nsec = UTIME_OMIT;
  times[1].tv_sec = milliseconds / 1000;
  times[1].tv_nsec = (milliseconds % 1000) * 1000000;
  return futimens(fd, times) == 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL Java_app_ultraslim_bridge_UltraHdrBridge_nativeVersion(JNIEnv* env,
                                                                                 jclass /*unused*/) {
  return env->NewStringUTF("libultrahdr 2.0.2 · libheif 4a3f74bc · libjxl 0.11.1 · x265 4.1 · SVT-AV1 3.0.0 · dav1d 1.5.1 · libde265 1.0.16 · libjpeg-turbo 3.1.1 · aom 3.12.1（无损增益图）");
}

JNIEXPORT jobject JNICALL Java_app_ultraslim_bridge_UltraHdrBridge_nativeDecodePreviewBitmap(
    JNIEnv* env, jclass /*unused*/, jint fd, jint maxDim, jlong maxWorkingBytes) {
#if defined(__ANDROID__)
  if (maxWorkingBytes <= 0) return nullptr;
  const int clampedDim = std::max(64, std::min(4096, int(maxDim)));
  ultraslim::PreviewBitmapData decoded;
  try {
    decoded = ultraslim::decodePreviewRgba(
        static_cast<int>(fd), clampedDim,
        static_cast<uint64_t>(maxWorkingBytes));
  } catch (const std::exception&) {
    return nullptr;
  }
  const int w = decoded.width, h = decoded.height;
  const size_t expected = static_cast<size_t>(w) * static_cast<size_t>(h) * 4;
  if (w <= 0 || h <= 0 || decoded.rgba.size() < expected) return nullptr;

  struct PreviewBitmapIds {
    jclass bitmapCls = nullptr;
    jmethodID createBitmap5 = nullptr;
    jmethodID createBitmap3 = nullptr;
    jobject argb8888 = nullptr;
    jobject rgb565 = nullptr;
    jobject csSrgb = nullptr;
    jobject csDisplayP3 = nullptr;
    jobject csBt2020 = nullptr;
    jobject csLinearSrgb = nullptr;
  };
  static const PreviewBitmapIds ids = [env] {
    PreviewBitmapIds out;
    jclass bmpLocal = env->FindClass("android/graphics/Bitmap");
    jclass cfgLocal = env->FindClass("android/graphics/Bitmap$Config");
    jclass csLocal = env->FindClass("android/graphics/ColorSpace");
    jclass namedLocal = env->FindClass("android/graphics/ColorSpace$Named");
    if (!bmpLocal || !cfgLocal || !csLocal || !namedLocal) {
      env->ExceptionClear();
      return out;
    }
    out.bitmapCls = static_cast<jclass>(env->NewGlobalRef(bmpLocal));
    out.createBitmap5 = env->GetStaticMethodID(
        out.bitmapCls, "createBitmap",
        "(IILandroid/graphics/Bitmap$Config;ZLandroid/graphics/ColorSpace;)Landroid/graphics/Bitmap;");
    out.createBitmap3 = env->GetStaticMethodID(
        out.bitmapCls, "createBitmap",
        "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
    auto resolveCfg = [&](const char* name) -> jobject {
      jfieldID f = env->GetStaticFieldID(cfgLocal, name, "Landroid/graphics/Bitmap$Config;");
      if (!f) { env->ExceptionClear(); return nullptr; }
      jobject obj = env->GetStaticObjectField(cfgLocal, f);
      if (!obj) return nullptr;
      jobject g = env->NewGlobalRef(obj);
      env->DeleteLocalRef(obj);
      return g;
    };
    out.argb8888 = resolveCfg("ARGB_8888");
    out.rgb565 = resolveCfg("RGB_565");
    jmethodID getCs = env->GetStaticMethodID(
        csLocal, "get", "(Landroid/graphics/ColorSpace$Named;)Landroid/graphics/ColorSpace;");
    auto resolveCs = [&](const char* name) -> jobject {
      if (!getCs) return nullptr;
      jfieldID f = env->GetStaticFieldID(namedLocal, name, "Landroid/graphics/ColorSpace$Named;");
      if (!f) { env->ExceptionClear(); return nullptr; }
      jobject n = env->GetStaticObjectField(namedLocal, f);
      if (!n) return nullptr;
      jobject cs = env->CallStaticObjectMethod(csLocal, getCs, n);
      env->DeleteLocalRef(n);
      if (env->ExceptionCheck() || !cs) { env->ExceptionClear(); return nullptr; }
      jobject g = env->NewGlobalRef(cs);
      env->DeleteLocalRef(cs);
      return g;
    };
    out.csSrgb = resolveCs("SRGB");
    out.csDisplayP3 = resolveCs("DISPLAY_P3");
    out.csBt2020 = resolveCs("BT2020");
    out.csLinearSrgb = resolveCs("LINEAR_SRGB");
    env->DeleteLocalRef(bmpLocal);
    env->DeleteLocalRef(cfgLocal);
    env->DeleteLocalRef(csLocal);
    env->DeleteLocalRef(namedLocal);
    return out;
  }();

  if (!ids.bitmapCls || !ids.createBitmap5 || !ids.argb8888 || !ids.csSrgb) return nullptr;

  bool hasAlpha = false;
  const uint8_t* src = decoded.rgba.data();
  const size_t pixelCount = static_cast<size_t>(w) * static_cast<size_t>(h);
  for (size_t i = 0; i < pixelCount; ++i) {
    if (src[i * 4 + 3] != 255) { hasAlpha = true; break; }
  }

  const bool useRgb565 = clampedDim <= 512 && !hasAlpha && decoded.colorSpace == 0 &&
                         ids.createBitmap3 != nullptr && ids.rgb565 != nullptr;
  jobject bitmap = nullptr;
  if (useRgb565) {
    bitmap = env->CallStaticObjectMethod(ids.bitmapCls, ids.createBitmap3, w, h, ids.rgb565);
  } else {
    jobject cs = decoded.colorSpace == 1 && ids.csDisplayP3 ? ids.csDisplayP3
               : decoded.colorSpace == 2 && ids.csBt2020 ? ids.csBt2020
               : decoded.colorSpace == 3 ? ids.csLinearSrgb : ids.csSrgb;
    bitmap = env->CallStaticObjectMethod(
        ids.bitmapCls, ids.createBitmap5, w, h, ids.argb8888,
        hasAlpha ? JNI_TRUE : JNI_FALSE, cs);
  }
  if (env->ExceptionCheck() || !bitmap) {
    env->ExceptionClear();
    return nullptr;
  }

  AndroidBitmapInfo info {};
  void* dstPixels = nullptr;
  if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
      AndroidBitmap_lockPixels(env, bitmap, &dstPixels) != ANDROID_BITMAP_RESULT_SUCCESS ||
      !dstPixels) {
    return nullptr;
  }

  auto* dstBytes = static_cast<uint8_t*>(dstPixels);
  const size_t rowBytes = static_cast<size_t>(w) * 4;
  if (useRgb565 && info.format == ANDROID_BITMAP_FORMAT_RGB_565) {
    for (int y = 0; y < h; ++y) {
      const uint8_t* sRow = src + static_cast<size_t>(y) * rowBytes;
      auto* dRow = reinterpret_cast<uint16_t*>(dstBytes + static_cast<size_t>(y) * info.stride);
      int x = 0;
#if defined(__ARM_NEON)
      for (; x + 16 <= w; x += 16) {
        const uint8x16x4_t rgba = vld4q_u8(sRow + static_cast<size_t>(x) * 4);
        const uint16x8_t rLo = vshlq_n_u16(vmovl_u8(vshr_n_u8(vget_low_u8(rgba.val[0]), 3)), 11);
        const uint16x8_t gLo = vshlq_n_u16(vmovl_u8(vshr_n_u8(vget_low_u8(rgba.val[1]), 2)), 5);
        const uint16x8_t bLo = vmovl_u8(vshr_n_u8(vget_low_u8(rgba.val[2]), 3));
        const uint16x8_t rHi = vshlq_n_u16(vmovl_u8(vshr_n_u8(vget_high_u8(rgba.val[0]), 3)), 11);
        const uint16x8_t gHi = vshlq_n_u16(vmovl_u8(vshr_n_u8(vget_high_u8(rgba.val[1]), 2)), 5);
        const uint16x8_t bHi = vmovl_u8(vshr_n_u8(vget_high_u8(rgba.val[2]), 3));
        vst1q_u16(dRow + x, vorrq_u16(vorrq_u16(rLo, gLo), bLo));
        vst1q_u16(dRow + x + 8, vorrq_u16(vorrq_u16(rHi, gHi), bHi));
      }
#endif
      for (; x < w; ++x) {
        const uint16_t r5 = static_cast<uint16_t>(sRow[x * 4 + 0] >> 3);
        const uint16_t g6 = static_cast<uint16_t>(sRow[x * 4 + 1] >> 2);
        const uint16_t b5 = static_cast<uint16_t>(sRow[x * 4 + 2] >> 3);
        dRow[x] = static_cast<uint16_t>((r5 << 11) | (g6 << 5) | b5);
      }
    }
  } else if (info.format == ANDROID_BITMAP_FORMAT_RGBA_8888 && !hasAlpha) {
    for (int y = 0; y < h; ++y) {
      std::memcpy(dstBytes + static_cast<size_t>(y) * info.stride, src + static_cast<size_t>(y) * rowBytes, rowBytes);
    }
  } else if (info.format == ANDROID_BITMAP_FORMAT_RGBA_8888) {
    for (int y = 0; y < h; ++y) {
      const uint8_t* sRow = src + static_cast<size_t>(y) * rowBytes;
      uint8_t* dRow = dstBytes + static_cast<size_t>(y) * info.stride;
      for (int x = 0; x < w; ++x) {
        const uint32_t a = sRow[x * 4 + 3];
        if (a == 255) {
          std::memcpy(dRow + x * 4, sRow + x * 4, 4);
        } else if (a == 0) {
          std::memset(dRow + x * 4, 0, 4);
        } else {
          dRow[x * 4 + 0] = static_cast<uint8_t>((sRow[x * 4 + 0] * a + 127) / 255);
          dRow[x * 4 + 1] = static_cast<uint8_t>((sRow[x * 4 + 1] * a + 127) / 255);
          dRow[x * 4 + 2] = static_cast<uint8_t>((sRow[x * 4 + 2] * a + 127) / 255);
          dRow[x * 4 + 3] = static_cast<uint8_t>(a);
        }
      }
    }
  } else {
    AndroidBitmap_unlockPixels(env, bitmap);
    return nullptr;
  }
  AndroidBitmap_unlockPixels(env, bitmap);
  return bitmap;
#else
  (void)env; (void)fd; (void)maxDim; (void)maxWorkingBytes;
  return nullptr;
#endif
}

JNIEXPORT jobject JNICALL Java_app_ultraslim_bridge_UltraHdrBridge_nativeDecodeGainmapBitmap(
    JNIEnv* env, jclass /*unused*/, jint fd, jint maxDim, jlong maxWorkingBytes, jfloatArray outMetadata) {
#if defined(__ANDROID__)
  if (maxWorkingBytes <= 0 || outMetadata == nullptr || env->GetArrayLength(outMetadata) < 18) return nullptr;
  ultraslim::PreviewGainmapData gm;
  try {
    if (!ultraslim::decodePreviewGainmap(
            static_cast<int>(fd), std::max(64, std::min(4096, int(maxDim))),
            static_cast<uint64_t>(maxWorkingBytes), gm)) {
      return nullptr;
    }
  } catch (const std::exception&) {
    return nullptr;
  }
  if (gm.width <= 0 || gm.height <= 0 || gm.pixels.empty()) return nullptr;

  struct GainmapBitmapIds {
    jclass bitmapCls = nullptr;
    jmethodID createBitmap3 = nullptr;
    jobject alpha8 = nullptr;
    jobject argb8888 = nullptr;
  };
  static const GainmapBitmapIds ids = [env] {
    GainmapBitmapIds out;
    jclass bmpLocal = env->FindClass("android/graphics/Bitmap");
    jclass cfgLocal = env->FindClass("android/graphics/Bitmap$Config");
    if (!bmpLocal || !cfgLocal) { env->ExceptionClear(); return out; }
    out.bitmapCls = static_cast<jclass>(env->NewGlobalRef(bmpLocal));
    out.createBitmap3 = env->GetStaticMethodID(
        out.bitmapCls, "createBitmap", "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
    auto resolveCfg = [&](const char* name) -> jobject {
      jfieldID f = env->GetStaticFieldID(cfgLocal, name, "Landroid/graphics/Bitmap$Config;");
      if (!f) { env->ExceptionClear(); return nullptr; }
      jobject obj = env->GetStaticObjectField(cfgLocal, f);
      if (!obj) return nullptr;
      jobject g = env->NewGlobalRef(obj);
      env->DeleteLocalRef(obj);
      return g;
    };
    out.alpha8 = resolveCfg("ALPHA_8");
    out.argb8888 = resolveCfg("ARGB_8888");
    env->DeleteLocalRef(bmpLocal);
    env->DeleteLocalRef(cfgLocal);
    return out;
  }();
  if (!ids.bitmapCls || !ids.createBitmap3 || !ids.alpha8 || !ids.argb8888) return nullptr;

  jobject bitmap = env->CallStaticObjectMethod(
      ids.bitmapCls, ids.createBitmap3, gm.width, gm.height,
      gm.singleChannel ? ids.alpha8 : ids.argb8888);
  if (env->ExceptionCheck() || !bitmap) {
    env->ExceptionClear();
    return nullptr;
  }
  AndroidBitmapInfo info {};
  void* dstPixels = nullptr;
  if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
      AndroidBitmap_lockPixels(env, bitmap, &dstPixels) != ANDROID_BITMAP_RESULT_SUCCESS ||
      !dstPixels) {
    return nullptr;
  }
  auto* dstBytes = static_cast<uint8_t*>(dstPixels);
  const size_t rowBytes = static_cast<size_t>(gm.width) * (gm.singleChannel ? 1u : 4u);
  if ((gm.singleChannel && info.format != ANDROID_BITMAP_FORMAT_A_8) ||
      (!gm.singleChannel && info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) ||
      gm.pixels.size() < rowBytes * static_cast<size_t>(gm.height)) {
    AndroidBitmap_unlockPixels(env, bitmap);
    return nullptr;
  }
  for (int y = 0; y < gm.height; ++y) {
    std::memcpy(dstBytes + static_cast<size_t>(y) * info.stride,
                gm.pixels.data() + static_cast<size_t>(y) * rowBytes, rowBytes);
  }
  AndroidBitmap_unlockPixels(env, bitmap);

  jfloat meta[] = {
      gm.minContentBoost[0], gm.minContentBoost[1], gm.minContentBoost[2],
      gm.maxContentBoost[0], gm.maxContentBoost[1], gm.maxContentBoost[2],
      gm.gamma[0], gm.gamma[1], gm.gamma[2],
      gm.offsetSdr[0], gm.offsetSdr[1], gm.offsetSdr[2],
      gm.offsetHdr[0], gm.offsetHdr[1], gm.offsetHdr[2],
      gm.hdrCapacityMin, gm.hdrCapacityMax, static_cast<jfloat>(gm.alternatePrimaries),
  };
  env->SetFloatArrayRegion(outMetadata, 0, sizeof(meta) / sizeof(meta[0]), meta);
  return bitmap;
#else
  (void)env; (void)fd; (void)maxDim; (void)maxWorkingBytes; (void)outMetadata;
  return nullptr;
#endif
}

JNIEXPORT jbyteArray JNICALL Java_app_ultraslim_bridge_UltraHdrBridge_nativeDecodePreview(
    JNIEnv* env, jclass /*unused*/, jint fd, jint maxDim, jlong maxWorkingBytes) {
  if (maxWorkingBytes <= 0) return nullptr;
  std::vector<uint8_t> jpeg;
  try { jpeg = ultraslim::decodePreviewJpeg(static_cast<int>(fd), std::max(64,std::min(4096,int(maxDim))), 82,
                                           static_cast<uint64_t>(maxWorkingBytes)); } catch(const std::exception&) { return nullptr; }
  if (jpeg.empty()) return nullptr;
  jbyteArray arr = env->NewByteArray(static_cast<jsize>(jpeg.size()));
  if (arr == nullptr) return nullptr;
  env->SetByteArrayRegion(arr, 0, static_cast<jsize>(jpeg.size()),
                          reinterpret_cast<const jbyte*>(jpeg.data()));
  return arr;
}

JNIEXPORT jbyteArray JNICALL Java_app_ultraslim_bridge_UltraHdrBridge_nativeReadHeifXmp(
    JNIEnv* env, jclass, jint fd) {
  try {
    auto bytes = ultraslim::readHeifXmp(fd);
    if (bytes.empty()) return nullptr;
    auto result = env->NewByteArray(static_cast<jsize>(bytes.size()));
    if (result) env->SetByteArrayRegion(result, 0, static_cast<jsize>(bytes.size()), reinterpret_cast<const jbyte*>(bytes.data()));
    return result;
  } catch (...) { return nullptr; }
}

// Debug aid: raw Y plane of the primary image, packed as [w32][h32][pixels...].
JNIEXPORT jbyteArray JNICALL Java_app_ultraslim_bridge_UltraHdrBridge_nativeDebugPlaneY(
    JNIEnv* env, jclass /*unused*/, jint fd) {
  int w = 0, h = 0;
  std::vector<uint8_t> plane = ultraslim::debugDecodePlaneY(fd, w, h);
  if (plane.empty()) return nullptr;
  jbyteArray arr = env->NewByteArray(static_cast<jsize>(plane.size() + 8));
  if (arr == nullptr) return nullptr;
  jbyte header[8] = {static_cast<jbyte>(w & 0xFF), static_cast<jbyte>((w >> 8) & 0xFF),
                     static_cast<jbyte>((w >> 16) & 0xFF), static_cast<jbyte>((w >> 24) & 0xFF),
                     static_cast<jbyte>(h & 0xFF), static_cast<jbyte>((h >> 8) & 0xFF),
                     static_cast<jbyte>((h >> 16) & 0xFF), static_cast<jbyte>((h >> 24) & 0xFF)};
  env->SetByteArrayRegion(arr, 0, 8, header);
  env->SetByteArrayRegion(arr, 8, static_cast<jsize>(plane.size()),
                          reinterpret_cast<const jbyte*>(plane.data()));
  return arr;
}

}  // extern "C"
