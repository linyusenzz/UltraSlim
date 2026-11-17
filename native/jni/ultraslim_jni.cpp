#include <stdexcept>
#include <jni.h>
#include <exception>
#include <algorithm>
#include <cstring>

#if defined(__ANDROID__)
#include <android/bitmap.h>
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

JNIEXPORT jstring JNICALL Java_app_ultraslim_bridge_UltraHdrBridge_nativeVersion(JNIEnv* env,
                                                                                 jclass /*unused*/) {
  return env->NewStringUTF("libultrahdr 2.0.2 · libheif 4a3f74bc · libjxl 0.11.1 · x265 4.1 · SVT-AV1 3.0.0 · dav1d 1.5.1 · libde265 1.0.16 · libjpeg-turbo 3.1.1 · aom 3.12.1（无损增益图）");
}

JNIEXPORT jobject JNICALL Java_app_ultraslim_bridge_UltraHdrBridge_nativeDecodePreviewBitmap(
    JNIEnv* env, jclass /*unused*/, jint fd, jint maxDim, jlong maxWorkingBytes) {
#if defined(__ANDROID__)
  if (maxWorkingBytes <= 0) return nullptr;
  ultraslim::PreviewBitmapData decoded;
  try {
    decoded = ultraslim::decodePreviewRgba(
        static_cast<int>(fd), std::max(64, std::min(4096, int(maxDim))),
        static_cast<uint64_t>(maxWorkingBytes));
  } catch (const std::exception&) {
    return nullptr;
  }
  const int w = decoded.width, h = decoded.height;
  const size_t expected = static_cast<size_t>(w) * static_cast<size_t>(h) * 4;
  if (w <= 0 || h <= 0 || decoded.rgba.size() < expected) return nullptr;

  struct PreviewBitmapIds {
    jclass bitmapCls = nullptr;
    jmethodID createBitmap = nullptr;
    jobject argb8888 = nullptr;
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
    out.createBitmap = env->GetStaticMethodID(
        out.bitmapCls, "createBitmap",
        "(IILandroid/graphics/Bitmap$Config;ZLandroid/graphics/ColorSpace;)Landroid/graphics/Bitmap;");
    jfieldID cfgField = env->GetStaticFieldID(cfgLocal, "ARGB_8888", "Landroid/graphics/Bitmap$Config;");
    jobject cfgObj = cfgField ? env->GetStaticObjectField(cfgLocal, cfgField) : nullptr;
    if (cfgObj) {
      out.argb8888 = env->NewGlobalRef(cfgObj);
      env->DeleteLocalRef(cfgObj);
    }
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

  if (!ids.bitmapCls || !ids.createBitmap || !ids.argb8888 || !ids.csSrgb) return nullptr;

  bool hasAlpha = false;
  const uint8_t* src = decoded.rgba.data();
  const size_t pixelCount = static_cast<size_t>(w) * static_cast<size_t>(h);
  for (size_t i = 0; i < pixelCount; ++i) {
    if (src[i * 4 + 3] != 255) { hasAlpha = true; break; }
  }

  jobject cs = decoded.colorSpace == 1 && ids.csDisplayP3 ? ids.csDisplayP3
             : decoded.colorSpace == 2 && ids.csBt2020 ? ids.csBt2020
             : decoded.colorSpace == 3 ? ids.csLinearSrgb : ids.csSrgb;
  jobject bitmap = env->CallStaticObjectMethod(
      ids.bitmapCls, ids.createBitmap, w, h, ids.argb8888,
      hasAlpha ? JNI_TRUE : JNI_FALSE, cs);
  if (env->ExceptionCheck() || !bitmap) {
    env->ExceptionClear();
    return nullptr;
  }

  AndroidBitmapInfo info {};
  void* dstPixels = nullptr;
  if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
      info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
      AndroidBitmap_lockPixels(env, bitmap, &dstPixels) != ANDROID_BITMAP_RESULT_SUCCESS ||
      !dstPixels) {
    return nullptr;
  }

  auto* dstBytes = static_cast<uint8_t*>(dstPixels);
  const size_t rowBytes = static_cast<size_t>(w) * 4;
  if (!hasAlpha) {
    for (int y = 0; y < h; ++y) {
      std::memcpy(dstBytes + static_cast<size_t>(y) * info.stride, src + static_cast<size_t>(y) * rowBytes, rowBytes);
    }
  } else {
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
  }
  AndroidBitmap_unlockPixels(env, bitmap);
  return bitmap;
#else
  (void)env; (void)fd; (void)maxDim; (void)maxWorkingBytes;
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
