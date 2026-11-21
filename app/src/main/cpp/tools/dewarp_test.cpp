// Standalone harness for the dewarp port: reads 8-bit PGM pages, prints the same numbers the
// Python prototype reports, and optionally writes corrected PGMs for a pixel comparison.
//
// Built for the device (arm64/x86_64) with the NDK, not shipped in the app.

#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include <opencv2/core.hpp>
#include <opencv2/imgcodecs.hpp>

#include "dewarp.h"

namespace dewarp {
extern bool g_debug;
extern const char* g_dump_ink;
}

namespace {

bool readPgm(const std::string& path, cv::Mat* out) {
  cv::Mat m = cv::imread(path, cv::IMREAD_GRAYSCALE);
  if (m.empty()) return false;
  *out = m;
  return true;
}

}  // namespace

int main(int argc, char** argv) {
  const char* write_dir = nullptr;
  bool orient_only = false;
  bool auto_orient = false;
  std::vector<std::string> files;
  for (int i = 1; i < argc; ++i) {
    if (std::strcmp(argv[i], "--orient") == 0) {
      orient_only = true;
    } else if (std::strcmp(argv[i], "--auto-orient") == 0) {
      auto_orient = true;
    } else if (std::strcmp(argv[i], "--dump-ink") == 0 && i + 1 < argc) {
      dewarp::g_dump_ink = argv[++i];
    } else if (std::strcmp(argv[i], "--debug") == 0) {
      dewarp::g_debug = true;
    } else if (std::strcmp(argv[i], "--write") == 0 && i + 1 < argc) {
      write_dir = argv[++i];
    } else {
      files.emplace_back(argv[i]);
    }
  }
  if (files.empty()) {
    std::fprintf(stderr, "usage: dewarp_test [--write DIR] page.pgm ...\n");
    return 2;
  }
  if (orient_only) {
    int wrong = 0;
    for (const std::string& f : files) {
      cv::Mat gray;
      if (!readPgm(f, &gray)) {
        std::printf("%-40s READ_FAILED\n", f.c_str());
        ++wrong;
        continue;
      }
      cv::Mat cw, ccw;
      cv::rotate(gray, cw, cv::ROTATE_90_CLOCKWISE);
      cv::rotate(gray, ccw, cv::ROTATE_90_COUNTERCLOCKWISE);
      const int d0 = dewarp::bestRotation(gray).degrees;
      // A page stored rotated 90 clockwise is restored by 90 counter-clockwise, and vice versa.
      const int d_cw = dewarp::bestRotation(cw).degrees;    // expect 90
      const int d_ccw = dewarp::bestRotation(ccw).degrees;  // expect 270
      const bool ok = d0 == 0 && d_cw == 90 && d_ccw == 270;
      if (!ok) ++wrong;
      std::printf("%-40s upright=%d rot90=%d(exp 90) rot270=%d(exp 270) %s\n", f.c_str(), d0,
                  d_cw, d_ccw, ok ? "OK" : "MISMATCH");
    }
    std::printf("ORIENT_FAILURES=%d\n", wrong);
    return wrong == 0 ? 0 : 1;
  }
  int applied = 0;
  int failures = 0;
  for (const std::string& f : files) {
    cv::Mat gray;
    if (!readPgm(f, &gray)) {
      std::printf("%-44s READ_FAILED\n", f.c_str());
      ++failures;
      continue;
    }
    const dewarp::Orientation ori = dewarp::bestRotation(gray);
    if (auto_orient && ori.degrees != 0) {
      cv::Mat turned;
      cv::rotate(gray, turned,
                 ori.degrees == 90 ? cv::ROTATE_90_COUNTERCLOCKWISE : cv::ROTATE_90_CLOCKWISE);
      gray = turned;
    }
    const dewarp::Result r = dewarp::dewarpAuto(gray);    if (r.applied) {
      ++applied;
      const cv::Mat replay = dewarp::applyField(gray, r.field);
      const double replayMax = cv::norm(replay, r.image, cv::NORM_INF);
      // JNI transports floats; measure quantisation separately from missing field data.
      dewarp::Field transported = r.field;
      transported.hc = static_cast<float>(transported.hc);
      for (double& v : transported.t) v = static_cast<float>(v);
      for (double& v : transported.b) v = static_cast<float>(v);
      for (double& v : transported.residual) v = static_cast<float>(v);
      cv::Mat delta;
      cv::absdiff(dewarp::applyField(gray, transported), r.image, delta);
      const double floatMean = cv::mean(delta)[0];
      std::printf("REPLAY max=%.0f float_mean=%.6f residual_columns=%zu\n",
                  replayMax, floatMean, r.field.residual.size());
      if (replayMax != 0.0 || floatMean > 1.0) ++failures;
    }
    std::string name = f;
    const size_t slash = name.find_last_of("/\\");
    if (slash != std::string::npos) name = name.substr(slash + 1);
    std::printf("%-44s in=%dx%d applied=%d before=%.2f after=%.2f out=%dx%d refined=%d %s\n",
                name.c_str(), gray.cols, gray.rows, r.applied ? 1 : 0, r.before, r.after,
                r.image.cols, r.image.rows, r.refined ? 1 : 0, r.reason.c_str());
    std::fflush(stdout);
    if (write_dir) {
      std::string base = name;
      const size_t dot = base.find_last_of('.');
      if (dot != std::string::npos) base = base.substr(0, dot);
      cv::imwrite(std::string(write_dir) + "/" + base + ".out.png", r.image);
    }
  }
  std::printf("TOTAL applied=%d/%zu\n", applied, files.size());
  std::printf("FAILURES=%d\n", failures);
  return failures == 0 ? 0 : 1;
}
