// JNI entry points for the app's own native module (libml_scanner_jni.so).
//
// Two calls, deliberately separate:
//   nativeDewarpField — estimates the correction field; returns it, so the Kotlin side can
//                       log/inspect it and decide whether a remap is worth doing.
//   nativeDewarpApply — applies a field returned by the call above.
//
// Field layout (float[]): [outW, outH, t0..t{w-1}, b0..b{w-1}, hc, residual0..residual{w-1}]

#include <jni.h>

#include <string>
#include <vector>

#include <android/log.h>
#include <opencv2/core.hpp>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "ScanNative", __VA_ARGS__)

#include "dewarp.h"

namespace {

const char *abiName() {
#if defined(__aarch64__)
  return "arm64-v8a";
#elif defined(__arm__)
  return "armeabi-v7a";
#elif defined(__x86_64__)
  return "x86_64";
#elif defined(__i386__)
  return "x86";
#else
  return "unknown";
#endif
}

cv::Mat toMat(JNIEnv *env, jbyteArray pixels, jint width, jint height) {
  if (pixels == nullptr || width <= 0 || height <= 0) return {};
  if (env->GetArrayLength(pixels) < width * height) return {};
  cv::Mat m(height, width, CV_8U);
  env->GetByteArrayRegion(pixels, 0, width * height, reinterpret_cast<jbyte *>(m.data));
  return m;
}

}  // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_arny_mlscanner_data_preprocessing_NativeScan_nativeBuildInfo(JNIEnv *env,
                                                                    jclass /*clazz*/) {
  std::string info = "ml_scanner_jni abi=";
  info += abiName();
  info += " cpp=";
  info += std::to_string(__cplusplus);
  return env->NewStringUTF(info.c_str());
}

// Returns the rotation (0, 90 or 270) that makes text lines horizontal.
JNIEXPORT jint JNICALL
Java_com_arny_mlscanner_data_preprocessing_NativeScan_nativeBestRotation(JNIEnv *env,
                                                                      jclass /*clazz*/,
                                                                      jbyteArray pixels,
                                                                      jint width, jint height) {
  const cv::Mat gray = toMat(env, pixels, width, height);
  if (gray.empty()) return 0;
  const dewarp::Orientation o = dewarp::bestRotation(gray);
  LOGI("orientation: %d deg (periodicity %.2f, margin %.2f)", o.degrees, o.strength, o.margin);
  return o.degrees;
}

// Returns the per-column correction field, or an empty array when the page needs no dewarp.
JNIEXPORT jfloatArray JNICALL
Java_com_arny_mlscanner_data_preprocessing_NativeScan_nativeDewarpField(JNIEnv *env,
                                                                      jclass /*clazz*/,
                                                                      jbyteArray pixels,
                                                                      jint width, jint height) {
  const cv::Mat gray = toMat(env, pixels, width, height);
  if (gray.empty()) return env->NewFloatArray(0);

  const dewarp::Result r = dewarp::dewarpAuto(gray);
  LOGI("dewarp field: applied=%d curvature %.2f -> %.2f px, out %dx%d", r.applied ? 1 : 0,
       r.before, r.after, r.image.cols, r.image.rows);
  const int w = static_cast<int>(r.field.t.size());
  if (!r.applied || w != width) return env->NewFloatArray(0);

  std::vector<float> out(static_cast<size_t>(3 + 3 * w), 0.0f);
  out[2 + 2 * w] = static_cast<float>(r.field.hc);
  out[0] = static_cast<float>(r.image.cols);
  out[1] = static_cast<float>(r.image.rows);
  for (int x = 0; x < w; ++x) {
    out[2 + x] = static_cast<float>(r.field.t[x]);
    out[2 + w + x] = static_cast<float>(r.field.b[x]);
    if (!r.field.residual.empty()) {
      out[3 + 2 * w + x] = static_cast<float>(r.field.residual[x]);
    }
  }
  jfloatArray arr = env->NewFloatArray(static_cast<jsize>(out.size()));
  env->SetFloatArrayRegion(arr, 0, static_cast<jsize>(out.size()), out.data());
  return arr;
}

// Applies a field produced by nativeDewarpField; returns outH * width grayscale bytes.
JNIEXPORT jbyteArray JNICALL
Java_com_arny_mlscanner_data_preprocessing_NativeScan_nativeDewarpApply(JNIEnv *env,
                                                                      jclass /*clazz*/,
                                                                      jbyteArray pixels,
                                                                      jint width, jint height,
                                                                      jfloatArray field) {
  const cv::Mat gray = toMat(env, pixels, width, height);
  if (gray.empty() || field == nullptr) return env->NewByteArray(0);

  const jsize n = env->GetArrayLength(field);
  if (n != 3 + 3 * width) return env->NewByteArray(0);
  std::vector<float> f(static_cast<size_t>(n));
  env->GetFloatArrayRegion(field, 0, n, f.data());

  dewarp::Field df;
  df.t.resize(width);
  df.b.resize(width);
  for (int x = 0; x < width; ++x) {
    df.t[x] = f[2 + x];
    df.b[x] = f[2 + width + x];
  }
  df.out_h = static_cast<int>(f[1]);
  df.hc = f[2 + 2 * width];
  df.residual.resize(width);
  bool hasResidual = false;
  for (int x = 0; x < width; ++x) {
    df.residual[x] = f[3 + 2 * width + x];
    hasResidual = hasResidual || df.residual[x] != 0.0;
  }
  if (!hasResidual) df.residual.clear();

  const cv::Mat out = dewarp::applyField(gray, df);
  const int len = out.cols * out.rows;
  jbyteArray arr = env->NewByteArray(len);
  env->SetByteArrayRegion(arr, 0, len, reinterpret_cast<const jbyte *>(out.data));
  return arr;
}

}  // extern "C"
