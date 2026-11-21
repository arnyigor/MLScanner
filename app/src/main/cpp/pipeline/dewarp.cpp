// Port of the Python dewarp prototype. Read with dewarp.h for the API contract.
//
// The algorithm, in the order the stages run:
//   1. page mask      — everything except large near-black regions touching the frame border
//   2. ink mask       — Otsu-dark pixels inside the page mask, opened 3x3 to drop specks
//   3. content edges  — first/last row carrying ink, per column band (bands average out noise)
//   4. envelope field — degree-4 polynomial fits of both edges -> t(x), b(x) per column
//   5. apply          — remap y' -> t(x) + y' * (b(x) - t(x)) / Hc  (shift + vertical scale)
//   6. residual pass  — sub-line-spacing per-column shift that further aligns text lines
//   7. gate           — apply only if the independent straightness metric improves >= 20 %

#include "dewarp.h"

#include <opencv2/imgcodecs.hpp>
#include <opencv2/imgproc.hpp>

#include <algorithm>
#include <cstdio>
#include <cmath>
#include <limits>

namespace dewarp {
namespace {

constexpr double kNaN = std::numeric_limits<double>::quiet_NaN();

bool finite(double v) { return std::isfinite(v); }

// ---------------------------------------------------------------- small numeric helpers

// np.median: even-length arrays average the two middle elements.
double median(std::vector<double> v) {
  if (v.empty()) return kNaN;
  const size_t mid = v.size() / 2;
  std::nth_element(v.begin(), v.begin() + mid, v.end());
  const double hi = v[mid];
  if (v.size() % 2) return hi;
  std::nth_element(v.begin(), v.begin() + mid - 1, v.end());
  return 0.5 * (v[mid - 1] + hi);
}

// np.percentile(..., 90) with linear interpolation, for an 8-bit image.
double percentile90(const cv::Mat& m) {
  double hist[256] = {0};
  for (int y = 0; y < m.rows; ++y) {
    const uchar* row = m.ptr<uchar>(y);
    for (int x = 0; x < m.cols; ++x) hist[row[x]] += 1.0;
  }
  const double total = static_cast<double>(m.total());
  const double idx = 0.90 * (total - 1.0);
  const double lo = std::floor(idx);
  const double frac = idx - lo;
  auto valueAt = [&](double rank) {
    double acc = 0.0;
    for (int v = 0; v < 256; ++v) {
      acc += hist[v];
      if (acc > rank) return static_cast<double>(v);
    }
    return 255.0;
  };
  const double v0 = valueAt(lo);
  const double v1 = valueAt(std::min(lo + 1.0, total - 1.0));
  return v0 + frac * (v1 - v0);
}

// Gaussian blur of an Nx1 float profile (the Python code blurs (H,1) with ksize (1,0)).
cv::Mat blurProfile(const cv::Mat& profile, double sigma) {
  cv::Mat out;
  cv::GaussianBlur(profile, out, cv::Size(1, 0), sigma);
  return out;
}

// profiles[p](row) = mean ink over the columns of band p, smoothed along rows.
std::vector<cv::Mat> bandProfiles(const cv::Mat& ink, int bands, double sigma,
                                  std::vector<double>* centers) {
  const int W = ink.cols;
  std::vector<cv::Mat> profs;
  profs.reserve(bands);
  centers->clear();
  centers->reserve(bands);
  for (int i = 0; i < bands; ++i) {
    const int x0 = static_cast<int>(static_cast<double>(i) * W / bands);
    int x1 = static_cast<int>(static_cast<double>(i + 1) * W / bands);
    if (i == bands - 1) x1 = W;
    cv::Mat seg = ink.colRange(x0, x1);
    cv::Mat mean;
    cv::reduce(seg, mean, 1, cv::REDUCE_AVG, CV_32F);
    profs.push_back(blurProfile(mean, sigma));
    centers->push_back(0.5 * (x0 + x1));
  }
  return profs;
}

// numpy polyfit(deg) on (x, y) with NaNs marking invalid samples; returns coefficients
// in increasing power of the normalised coordinate u = (x - xm) / xs.
bool fitPoly(const std::vector<double>& x, const std::vector<double>& y, int deg,
             std::vector<double>* coef, const std::vector<bool>& keep, double xm, double xs) {
  std::vector<size_t> idx;
  idx.reserve(x.size());
  for (size_t i = 0; i < x.size(); ++i) {
    if (keep[i] && finite(y[i])) idx.push_back(i);
  }
  if (static_cast<int>(idx.size()) < deg + 2) return false;

  cv::Mat A(static_cast<int>(idx.size()), deg + 1, CV_64F);
  cv::Mat rhs(static_cast<int>(idx.size()), 1, CV_64F);
  for (size_t r = 0; r < idx.size(); ++r) {
    const double u = (x[idx[r]] - xm) / xs;
    double p = 1.0;
    for (int k = 0; k <= deg; ++k) {
      A.at<double>(static_cast<int>(r), k) = p;
      p *= u;
    }
    rhs.at<double>(static_cast<int>(r), 0) = y[idx[r]];
  }
  cv::Mat sol;
  if (!cv::solve(A, rhs, sol, cv::DECOMP_SVD)) return false;
  if (sol.rows != deg + 1 || sol.cols != 1) return false;
  coef->assign(deg + 1, 0.0);
  for (int k = 0; k <= deg; ++k) coef->at(k) = sol.at<double>(k, 0);
  return true;
}

double evalPoly(const std::vector<double>& coef, double u) {
  double p = 0.0;
  for (int k = static_cast<int>(coef.size()) - 1; k >= 0; --k) p = p * u + coef[k];
  return p;
}

// Deg-4 fit with iterative outlier rejection (3 * 1.4826 * median|r| + 1 px).
bool robustPoly(const std::vector<double>& x, const std::vector<double>& y, int deg,
                std::vector<double>* coef, std::vector<bool>* used) {
  size_t valid = 0;
  double xmin = 1e18, xmax = -1e18;
  for (size_t i = 0; i < y.size(); ++i) {
    if (!finite(y[i])) continue;
    ++valid;
    xmin = std::min(xmin, x[i]);
    xmax = std::max(xmax, x[i]);
  }
  if (static_cast<int>(valid) < deg + 2) return false;
  const double xm = 0.5 * (xmin + xmax);
  const double xs = std::max(0.5 * (xmax - xmin), 1e-9);

  std::vector<bool> keep(x.size(), true);
  for (size_t i = 0; i < x.size(); ++i) keep[i] = finite(y[i]);
  if (!fitPoly(x, y, deg, coef, keep, xm, xs)) return false;

  for (int it = 0; it < 4; ++it) {
    std::vector<double> res;
    for (size_t i = 0; i < x.size(); ++i) {
      if (!keep[i]) continue;
      res.push_back(std::fabs(y[i] - evalPoly(*coef, (x[i] - xm) / xs)));
    }
    if (res.empty()) break;
    const double s = median(res) * 1.4826 + 1.0;
    std::vector<bool> next(x.size(), false);
    size_t n = 0;
    for (size_t i = 0; i < x.size(); ++i) {
      if (!keep[i]) continue;
      if (std::fabs(y[i] - evalPoly(*coef, (x[i] - xm) / xs)) < 3.0 * s) {
        next[i] = true;
        ++n;
      }
    }
    if (static_cast<int>(n) < deg + 2) break;
    keep = next;
    std::vector<double> refit;
    if (!fitPoly(x, y, deg, &refit, keep, xm, xs)) break;
    *coef = refit;
  }
  // Coefficients are in u = (x - xm) / xs; fold the normalisation back into a callable form
  // by storing xm/xs in the caller through evalWith().
  coef->push_back(xm);
  coef->push_back(xs);
  if (used) *used = keep;
  return true;
}

double evalStored(const std::vector<double>& coefWithNorm, double x) {
  const double xs = coefWithNorm[coefWithNorm.size() - 1];
  const double xm = coefWithNorm[coefWithNorm.size() - 2];
  std::vector<double> c(coefWithNorm.begin(), coefWithNorm.end() - 2);
  return evalPoly(c, (x - xm) / xs);
}

// ---------------------------------------------------------------- stage 1: page mask

cv::Mat pageMask(const cv::Mat& gray, int erode = 7, double min_frac = 0.01) {
  const int H = gray.rows, W = gray.cols;
  // The mask only needs coarse geometry, but the blur that makes text irrelevant has sigma =
  // maxSide/40, i.e. hundreds of taps on a full-resolution page. Computing it on a downscaled
  // copy and upsampling the result is ~10-30x cheaper with no visible change (the mask is
  // eroded by 7 px afterwards anyway).
  const int kMaxSide = 700;
  double scale = 1.0;
  cv::Mat small = gray;
  if (std::max(H, W) > kMaxSide) {
    scale = static_cast<double>(kMaxSide) / std::max(H, W);
    cv::resize(gray, small, cv::Size(std::max(8, static_cast<int>(W * scale)),
                                     std::max(8, static_cast<int>(H * scale))),
               0, 0, cv::INTER_AREA);
  }
  const int sh = small.rows, sw = small.cols;

  const double sigma = std::max(sh, sw) / 40.0;
  cv::Mat blur;
  cv::GaussianBlur(small, blur, cv::Size(0, 0), sigma);

  const double p90 = percentile90(blur);
  const double thr = 0.30 * p90;
  cv::Mat dark = (blur < thr) / 255;  // 0/1
  if (g_debug) {
    std::printf("    [pageMask] small=%dx%d p90=%.1f thr=%.1f dark=%.4f", sw, sh, p90, thr,
                cv::mean(dark)[0]);
  }

  cv::Mat labels, stats, centroids;
  const int n = cv::connectedComponentsWithStats(dark, labels, stats, centroids, 8, CV_32S);
  cv::Mat bg = cv::Mat::zeros(sh, sw, CV_8U);
  for (int i = 1; i < n; ++i) {
    const int x = stats.at<int>(i, cv::CC_STAT_LEFT);
    const int y = stats.at<int>(i, cv::CC_STAT_TOP);
    const int w = stats.at<int>(i, cv::CC_STAT_WIDTH);
    const int h = stats.at<int>(i, cv::CC_STAT_HEIGHT);
    const int area = stats.at<int>(i, cv::CC_STAT_AREA);
    const bool touches = x <= 1 || y <= 1 || x + w >= sw - 1 || y + h >= sh - 1;
    if (touches && area > min_frac * sh * sw) bg.setTo(1, labels == i);
  }
  if (g_debug) std::printf(" bg=%.4f", cv::mean(bg)[0]);
  if (cv::mean(bg)[0] > 0.9) {
    if (g_debug) std::putchar(10);
    return cv::Mat::ones(H, W, CV_8U);
  }

  cv::Mat m;
  // bg holds 0/1, so inverting must be done by comparison, not bitwise_not (which would
  // turn 1 into 254 and then saturate everything to 255 on the scale-up below).
  cv::compare(bg, 0, m, cv::CMP_EQ);  // 255 where the pixel is page, 0 where it is background
  if (scale < 1.0) {
    cv::resize(m, m, cv::Size(W, H), 0, 0, cv::INTER_NEAREST);
  }
  cv::morphologyEx(m, m, cv::MORPH_CLOSE,
                   cv::getStructuringElement(cv::MORPH_RECT, cv::Size(15, 15)));
  cv::erode(m, m, cv::getStructuringElement(cv::MORPH_RECT, cv::Size(erode, erode)));
  m = (m > 0) / 255;
  if (g_debug) {
    std::printf(" page=%.4f", cv::mean(m)[0]);
    std::putchar(10);
  }
  return m;
}

// ---------------------------------------------------------------- stage 2: ink

cv::Mat otsuInk(const cv::Mat& gray) {
  cv::Mat b, bin;
  cv::GaussianBlur(gray, b, cv::Size(0, 0), 2.0);
  const double t = cv::threshold(b, bin, 0, 255, cv::THRESH_BINARY | cv::THRESH_OTSU);
  cv::Mat ink;
  cv::compare(gray, t, ink, cv::CMP_LT);
  ink = (ink / 255);
  const cv::Mat pm = pageMask(gray);
  if (g_debug) {
    std::printf("    [ink] otsu=%.1f raw=%.5f pm=%.4f", t, cv::mean(ink)[0], cv::mean(pm)[0]);
    std::putchar(10);
  }
  ink = ink & pm;
  return ink;
}

// ---------------------------------------------------------------- stage 3: content edges

struct Edges {
  std::vector<double> centers;  // band centers, px
  std::vector<double> top;      // first content row per band, NaN when unknown
  std::vector<double> bot;      // last content row per band, NaN when unknown
};

Edges contentEdges(const cv::Mat& ink, int bands, double min_frac = 0.03) {
  const int H = ink.rows, W = ink.cols;
  Edges e;
  for (int i = 0; i < bands; ++i) {
    const int x0 = static_cast<int>(static_cast<double>(i) * W / bands);
    int x1 = static_cast<int>(static_cast<double>(i + 1) * W / bands);
    if (i == bands - 1) x1 = W;
    cv::Mat mean;
    cv::reduce(ink.colRange(x0, x1), mean, 1, cv::REDUCE_AVG, CV_32F);
    int first = -1, last = -1, count = 0;
    for (int y = 0; y < H; ++y) {
      if (mean.at<float>(y, 0) > min_frac) {
        if (first < 0) first = y;
        last = y;
        ++count;
      }
    }
    e.centers.push_back(0.5 * (x0 + x1));
    if (count < 3) {
      e.top.push_back(kNaN);
      e.bot.push_back(kNaN);
    } else {
      e.top.push_back(first);
      e.bot.push_back(last);
    }
  }
  return e;
}

// ---------------------------------------------------------------- straightness metric

double medianAbsDev(std::vector<double> v, double med) {
  for (double& x : v) x = std::fabs(x - med);
  return median(v);
}

std::vector<int> findPeaks(const std::vector<double>& p, int min_dist, double min_prom) {
  double mx = 0.0;
  for (double v : p) mx = std::max(mx, v);
  const double th = min_prom * mx;
  std::vector<int> cand;
  for (int i = 1; i + 1 < static_cast<int>(p.size()); ++i) {
    if (p[i] >= p[i - 1] && p[i] > p[i + 1] && p[i] > th) cand.push_back(i);
  }
  std::sort(cand.begin(), cand.end(),
            [&](int a, int b) { return p[a] > p[b]; });
  std::vector<int> keep;
  for (int i : cand) {
    bool ok = true;
    for (int j : keep) {
      if (std::abs(i - j) < min_dist) {
        ok = false;
        break;
      }
    }
    if (ok) keep.push_back(i);
  }
  std::sort(keep.begin(), keep.end());
  return keep;
}

double lineCurvatureImpl(const cv::Mat& gray, int bands, int max_side, double* spacing_out) {
  const int H0 = gray.rows, W0 = gray.cols;
  double scale = 1.0;
  cv::Mat img = gray;
  if (std::max(H0, W0) > max_side) {
    scale = static_cast<double>(max_side) / std::max(H0, W0);
    cv::resize(gray, img, cv::Size(std::max(8, static_cast<int>(W0 * scale)),
                                   std::max(8, static_cast<int>(H0 * scale))),
               0, 0, cv::INTER_AREA);
  }
  const cv::Mat ink = inkMask(img);
  const int H = ink.rows, W = ink.cols;
  if (g_dump_ink) {  // first call only: the metric runs several times per image
    cv::Mat save;
    ink.convertTo(save, CV_8U, 255.0);
    cv::imwrite(g_dump_ink, save);
    g_dump_ink = nullptr;
  }

  // spacing from the spectrum of the de-trended row profile (same as the Python prototype)
  cv::Mat p;
  cv::reduce(ink, p, 1, cv::REDUCE_AVG, CV_32F);
  cv::Mat trend = blurProfile(p, H / 40.0);
  cv::Mat det = p - trend;
  det = det.reshape(1, 1);  // 1 x H row for the DFT
  cv::Mat win(1, H, CV_32F);
  for (int i = 0; i < H; ++i) {
    win.at<float>(0, i) = 0.5f * (1.0f - std::cos(2.0 * CV_PI * i / std::max(1, H - 1)));
  }
  cv::Mat sig = det.mul(win);
  cv::Mat spec;
  cv::dft(sig, spec, cv::DFT_COMPLEX_OUTPUT);
  double best = -1.0;
  int best_k = 0;
  for (int k = 1; k <= H / 2; ++k) {
    const double f = static_cast<double>(k) / H;
    if (!(f > 1.0 / 200 && f < 1.0 / 8)) continue;
    const cv::Vec2f v = spec.at<cv::Vec2f>(0, k);
    const double mag = std::sqrt(v[0] * v[0] + v[1] * v[1]);
    if (mag > best) {
      best = mag;
      best_k = k;
    }
  }
  double sp = best_k > 0 ? static_cast<double>(H) / best_k : 25.0;
  sp = std::min(std::max(sp, 6.0), 120.0);

  std::vector<double> centers;
  const std::vector<cv::Mat> profs = bandProfiles(ink, bands, std::max(1.5, sp / 5.0), &centers);
  const int ref = bands / 2;
  std::vector<float> refProf(H);
  for (int y = 0; y < H; ++y) refProf[y] = profs[ref].at<float>(y, 0);

  if (g_debug) {
    const double ink_mean = cv::mean(ink)[0] / 255.0;
    double rmax = 0.0;
    for (int y = 0; y < H; ++y) rmax = std::max(rmax, static_cast<double>(refProf[y]));
    std::printf("    [debug] ink=%.5f refmax=%.5f th=%.5f H=%d W=%d", ink_mean, rmax,
                0.15 * rmax, H, W);
    std::putchar(10);
  }
  const std::vector<int> peaks = findPeaks(std::vector<double>(refProf.begin(), refProf.end()),
                                           static_cast<int>(std::max(4.0, sp * 0.5)), 0.15);
  const int win_rows = static_cast<int>(std::max(3.0, sp * 0.35));

  // lines[k] -> per-band row of text line k; tracking spreads outward from the centre band
  std::vector<std::vector<double>> lines(peaks.size(), std::vector<double>(bands, kNaN));
  for (size_t k = 0; k < peaks.size(); ++k) lines[k][ref] = peaks[k];

  for (int dir : {1, -1}) {
    std::vector<double> cur(peaks.size(), 0.0);
    std::vector<bool> alive(peaks.size(), true);
    for (size_t k = 0; k < peaks.size(); ++k) cur[k] = peaks[k];
    const int stop = dir > 0 ? bands : -1;
    for (int b = ref + dir; b != stop; b += dir) {
      for (size_t k = 0; k < peaks.size(); ++k) {
        if (!alive[k]) continue;
        const int y0 = static_cast<int>(std::lround(cur[k]));
        const int lo = std::max(0, y0 - win_rows);
        const int hi = std::min(H, y0 + win_rows + 1);
        if (hi - lo < 3) {
          alive[k] = false;
          continue;
        }
        int best_j = lo;
        float best_v = profs[b].at<float>(lo, 0);
        for (int y = lo + 1; y < hi; ++y) {
          const float v = profs[b].at<float>(y, 0);
          if (v > best_v) {
            best_v = v;
            best_j = y;
          }
        }
        if (best_v < 0.25f * refProf[peaks[k]]) {
          alive[k] = false;
          continue;
        }
        cur[k] = best_j;
        lines[k][b] = best_j;
      }
    }
  }

  std::vector<double> vals;
  for (const auto& line : lines) {
    std::vector<double> ys;
    for (int b = 0; b < bands; ++b) {
      if (finite(line[b])) ys.push_back(line[b]);
    }
    if (ys.size() < std::max(8.0, 0.5 * bands)) continue;
    const double med = median(ys);
    const double mad = medianAbsDev(ys, med) + 1e-6;
    std::vector<double> kept;
    for (double y : ys) {
      if (std::fabs(y - med) < 3.0 * mad + 3.0) kept.push_back(y);
    }
    if (kept.size() < 4) continue;
    auto mm = std::minmax_element(kept.begin(), kept.end());
    vals.push_back(*mm.second - *mm.first);
  }
  if (spacing_out) *spacing_out = sp / scale;
  if (g_debug) debugDump(sp, peaks.size(), vals);
  return vals.empty() ? kNaN : median(vals) / scale;
}

// ---------------------------------------------------------------- stage 4/5: field + apply

bool envelopeFieldImpl(const cv::Mat& gray, Field* out, int deg, int bands) {
  const int H = gray.rows, W = gray.cols;
  const Edges e = contentEdges(robustInk(gray), bands);
  if (g_debug) {
    std::printf("    [edges] top:");
    for (size_t i = 0; i < e.top.size(); i += 8) std::printf(" %.0f", e.top[i]);
    std::printf(" | bot:");
    for (size_t i = 0; i < e.bot.size(); i += 8) std::printf(" %.0f", e.bot[i]);
    std::printf("\n");
  }
  std::vector<double> ct, cb;

  // A band that sees only a few short lines (ragged right edge, a table cell, a caption)
  // reports a content height far below the page height. Its top/bottom describe that
  // fragment, not the page envelope, so such bands must not enter the fit.
  std::vector<double> heights;
  for (size_t i = 0; i < e.centers.size(); ++i) {
    if (finite(e.top[i]) && finite(e.bot[i])) heights.push_back(e.bot[i] - e.top[i]);
  }
  if (heights.size() < 8) return false;
  const double ref_h = median(heights);
  std::vector<double> cx, cy_t, cy_b;
  for (size_t i = 0; i < e.centers.size(); ++i) {
    if (!finite(e.top[i]) || !finite(e.bot[i])) continue;
    const double d = e.bot[i] - e.top[i];
    if (d < 0.85 * ref_h || d > 1.15 * ref_h) continue;
    cx.push_back(e.centers[i]);
    cy_t.push_back(e.top[i]);
    cy_b.push_back(e.bot[i]);
  }
  if (cx.size() < 8) return false;
  if (g_debug) {
    std::printf("    [edges] kept %zu/%zu bands, ref_h=%.1f\n", cx.size(), heights.size(), ref_h);
  }
  if (!robustPoly(cx, cy_t, deg, &ct, nullptr)) return false;
  if (!robustPoly(cx, cy_b, deg, &cb, nullptr)) return false;

  size_t valid = 0;
  for (size_t i = 0; i < e.top.size(); ++i) {
    if (finite(e.top[i]) && finite(e.bot[i])) ++valid;
  }
  if (valid * 2 < static_cast<size_t>(bands)) return false;

  out->t.resize(W);
  out->b.resize(W);
  std::vector<double> h(W);
  for (int x = 0; x < W; ++x) {
    // Outside the observed columns there is no evidence of the envelope shape: a degree-4
    // polynomial evaluated there diverges (seen as t clamped to 0 and b to H-1 at the
    // margins, which then rolls the page). Hold the value of the nearest observed column.
    const double xc = std::min(std::max(static_cast<double>(x), cx.front()), cx.back());
    out->t[x] = std::min(std::max(evalStored(ct, xc), 0.0), H - 1.0);
    out->b[x] = std::min(std::max(evalStored(cb, xc), 0.0), H - 1.0);
    h[x] = out->b[x] - out->t[x];
  }
  const double med_h = median(h);
  if (!(med_h >= 0.4 * H)) return false;
  out->hc = med_h;
  out->out_h = static_cast<int>(std::lround(med_h));
  out->out_h = std::max(1, std::min(out->out_h, static_cast<int>(1.5 * H)));
  if (g_debug) {
    auto range = [](const std::vector<double>& v) {
      double lo = v.front(), hi = v.front();
      for (double x : v) { lo = std::min(lo, x); hi = std::max(hi, x); }
      return std::make_pair(lo, hi);
    };
    const auto rt = range(out->t), rb = range(out->b);
    std::printf("    [envelope] valid=%zu/%d t=%.1f..%.1f b=%.1f..%.1f hc=%.1f H=%d\n", valid,
                bands, rt.first, rt.second, rb.first, rb.second, med_h, H);
  }
  return true;
}

cv::Mat applyAffineImpl(const cv::Mat& gray, const Field& f) {
  const int W = gray.cols;
  const double Hc = std::max(1.0, f.hc);
  cv::Mat mapx(f.out_h, W, CV_32F), mapy(f.out_h, W, CV_32F);
  for (int y = 0; y < f.out_h; ++y) {
    for (int x = 0; x < W; ++x) {
      const double s = (f.b[x] - f.t[x]) / Hc;
      mapx.at<float>(y, x) = static_cast<float>(x);
      mapy.at<float>(y, x) = static_cast<float>(f.t[x] + y * s);
    }
  }
  cv::Mat out;
  cv::remap(gray, out, mapx, mapy, cv::INTER_CUBIC, cv::BORDER_REPLICATE);
  return out;
}

// ---------------------------------------------------------------- stage 6: residual shift

double corrAt(const cv::Mat& p, const cv::Mat& others, int d) {
  const int n = p.rows;
  const int lo = d > 0 ? d : 0;
  const int hi = d > 0 ? n : n + d;
  if (d > 0 && d >= n) return -1.0;
  if (d < 0 && -d >= n) return -1.0;
  double dot = 0.0, na = 0.0, nb = 0.0;
  for (int y = lo; y < hi; ++y) {
    const double a = p.at<float>(y - d, 0);
    const double b = others.at<float>(y, 0);
    dot += a * b;
    na += a * a;
    nb += b * b;
  }
  return dot / (std::sqrt(na) * std::sqrt(nb) + 1e-9);
}

std::vector<double> residualField(const cv::Mat& ink, double sp, int bands = 40,
                                  double range_ratio = 0.6, double lam = 0.03) {
  const int W = ink.cols;
  const double sigma = std::min(std::max(sp / 4.0, 1.5), 12.0);
  std::vector<double> centers;
  const std::vector<cv::Mat> profs = bandProfiles(ink, bands, sigma, &centers);
  const int ms = static_cast<int>(std::max(3.0, range_ratio * sp));
  std::vector<int> ds;
  for (int d = -ms; d <= ms; ++d) ds.push_back(d);
  const int D = static_cast<int>(ds.size());

  cv::Mat S = cv::Mat::zeros(profs[0].rows, 1, CV_32F);
  for (const auto& p : profs) S += p;

  std::vector<std::vector<double>> score(bands, std::vector<double>(D, 0.0));
  for (int i = 0; i < bands; ++i) {
    cv::Mat others = S - profs[i];
    for (int j = 0; j < D; ++j) score[i][j] = -corrAt(profs[i], others, ds[j]);
  }

  std::vector<double> acc(score[0]);
  std::vector<std::vector<int>> back(bands, std::vector<int>(D, 0));
  for (int i = 1; i < bands; ++i) {
    std::vector<double> next(D, 1e18);
    for (int jc = 0; jc < D; ++jc) {
      for (int jp = 0; jp < D; ++jp) {
        const double t = acc[jp] + lam * std::pow(ds[jp] - ds[jc], 2.0);
        if (t < next[jc]) {
          next[jc] = t;
          back[i][jc] = jp;
        }
      }
    }
    acc = next;
  }
  int k = static_cast<int>(std::min_element(acc.begin(), acc.end()) - acc.begin());
  std::vector<int> path(bands, 0);
  path[bands - 1] = k;
  for (int i = bands - 1; i > 0; --i) {
    k = back[i][k];
    path[i - 1] = k;
  }

  std::vector<double> field(W, 0.0);
  for (int x = 0; x < W; ++x) {
    // linear interpolation of -s(centers) at x, clamped outside
    if (x <= centers.front()) {
      field[x] = -ds[path.front()];
    } else if (x >= centers.back()) {
      field[x] = -ds[path.back()];
    } else {
      int i = 0;
      while (i + 1 < static_cast<int>(centers.size()) && centers[i + 1] < x) ++i;
      const double t = (x - centers[i]) / std::max(1e-9, centers[i + 1] - centers[i]);
      field[x] = -((1 - t) * ds[path[i]] + t * ds[path[i + 1]]);
    }
  }
  return field;
}

cv::Mat applyShift(const cv::Mat& gray, const std::vector<double>& field) {
  const int H = gray.rows, W = gray.cols;
  (void)H;
  cv::Mat mapx(H, W, CV_32F), mapy(H, W, CV_32F);
  for (int y = 0; y < H; ++y) {
    for (int x = 0; x < W; ++x) {
      mapx.at<float>(y, x) = static_cast<float>(x);
      mapy.at<float>(y, x) = static_cast<float>(y + field[x]);
    }
  }
  cv::Mat out;
  cv::remap(gray, out, mapx, mapy, cv::INTER_CUBIC, cv::BORDER_REPLICATE);
  return out;
}

// ---------------------------------------------------------------- stage 0: orientation

// How much sharper the left edge of the text block is than its right edge.
//
// Row periodicity says only that the lines are horizontal, so 90 and 270 tie. Direction needs a
// cue that is not left/right symmetric: upright text has a hard left margin (justified starts or
// paragraph indents) and a softer right edge (ragged ends), so the column profile rises steeply
// on the left and falls gradually on the right. A page viewed upside down shows the opposite.
double edgeAsymmetryImpl(const cv::Mat& gray, int max_side) {
  const int H0 = gray.rows, W0 = gray.cols;
  cv::Mat img = gray;
  if (std::max(H0, W0) > max_side) {
    const double scale = static_cast<double>(max_side) / std::max(H0, W0);
    cv::resize(gray, img, cv::Size(std::max(8, static_cast<int>(W0 * scale)),
                                   std::max(8, static_cast<int>(H0 * scale))),
               0, 0, cv::INTER_AREA);
  }
  const cv::Mat ink = inkMask(img);
  cv::Mat col;
  cv::reduce(ink, col, 0, cv::REDUCE_AVG, CV_32F);
  const int W = col.cols;
  if (W < 16) return 0.0;
  double hi = 0.0;
  for (int x = 0; x < W; ++x) hi = std::max(hi, static_cast<double>(col.at<float>(0, x)));
  if (hi <= 0.0) return 0.0;
  const double lo = 0.10 * hi, top = 0.90 * hi;

  auto ramp = [&](int from, int to, int step) {
    int first_lo = -1, first_top = -1;
    int x = from;
    while (x != to) {
      const double v = col.at<float>(0, x);
      if (first_lo < 0 && v >= lo) first_lo = x;
      if (first_lo >= 0 && v >= top) {
        first_top = x;
        break;
      }
      x += step;
    }
    if (first_lo < 0 || first_top < 0) return W / 2;  // never reaches the plateau
    return std::abs(first_top - first_lo);
  };
  const double left = ramp(0, W, 1);
  const double right = ramp(W - 1, -1, -1);
  return (right - left) / std::max(1.0, static_cast<double>(W));
}

double periodicityImpl(const cv::Mat& gray, int max_side) {
  const int H0 = gray.rows, W0 = gray.cols;
  cv::Mat img = gray;
  if (std::max(H0, W0) > max_side) {
    const double scale = static_cast<double>(max_side) / std::max(H0, W0);
    cv::resize(gray, img, cv::Size(std::max(8, static_cast<int>(W0 * scale)),
                                   std::max(8, static_cast<int>(H0 * scale))),
               0, 0, cv::INTER_AREA);
  }
  const cv::Mat ink = inkMask(img);
  const int H = ink.rows;
  if (H < 32) return 1.0;

  cv::Mat p;
  cv::reduce(ink, p, 1, cv::REDUCE_AVG, CV_32F);
  cv::Mat detM = p - blurProfile(p, H / 40.0);
  cv::Mat det = detM.reshape(1, 1);
  cv::Mat win(1, H, CV_32F);
  for (int i = 0; i < H; ++i) {
    win.at<float>(0, i) = 0.5f * (1.0f - std::cos(2.0 * CV_PI * i / std::max(1, H - 1)));
  }
  cv::Mat spec;
  cv::dft(det.mul(win), spec, cv::DFT_COMPLEX_OUTPUT);

  double best = 0.0, sum = 0.0;
  int count = 0;
  for (int k = 1; k <= H / 2; ++k) {
    const double f = static_cast<double>(k) / H;
    const cv::Vec2f v = spec.at<cv::Vec2f>(0, k);
    const double mag = std::sqrt(v[0] * v[0] + v[1] * v[1]);
    if (f > 1.0 / 200 && f < 1.0 / 8) {
      best = std::max(best, mag);
      sum += mag;
      ++count;
    }
  }
  if (count == 0) return 1.0;
  return best / (sum / count + 1e-9);  // peak-to-mean ratio: no lines give ~1
}

}  // namespace

bool g_debug = false;
const char* g_dump_ink = nullptr;

void debugDump(double sp, size_t peaks, const std::vector<double>& vals) {
  std::printf("    [debug] sp=%.2f peaks=%zu kept=%zu vals=", sp, peaks, vals.size());
  for (size_t i = 0; i < vals.size() && i < 12; ++i) std::printf("%.1f ", vals[i]);
  std::putchar(10);
}

// ---------------------------------------------------------------- public API

cv::Mat inkMask(const cv::Mat& gray) { return otsuInk(gray); }

cv::Mat robustInk(const cv::Mat& gray) {
  cv::Mat ink = otsuInk(gray);
  cv::Mat out;
  cv::morphologyEx(ink, out, cv::MORPH_OPEN,
                   cv::getStructuringElement(cv::MORPH_RECT, cv::Size(3, 3)));
  return out;
}

double lineCurvature(const cv::Mat& gray, int bands, int max_side, double* spacing_out) {
  return lineCurvatureImpl(gray, bands, max_side, spacing_out);
}

bool envelopeField(const cv::Mat& gray, Field* out, int deg, int bands) {
  return envelopeFieldImpl(gray, out, deg, bands);
}

cv::Mat applyAffine(const cv::Mat& gray, const Field& field) { return applyAffineImpl(gray, field); }

cv::Mat applyField(const cv::Mat& gray, const Field& field) {
  // Один remap вместо двух (affine + residual shift): сдвиг вертикальный и зависит
  // только от x, поэтому композиция точна: source_y = t[x] + (y + res[x]) * s.
  const int W = gray.cols;
  const double Hc = std::max(1.0, field.hc);
  cv::Mat mapx(field.out_h, W, CV_32F), mapy(field.out_h, W, CV_32F);
  for (int x = 0; x < W; ++x) {
    const double s = (field.b[x] - field.t[x]) / Hc;
    // Сдвиг residual задан в пространстве выхода affine, поэтому сначала clamp
    // индекса строки (как делал BORDER_REPLICATE при последовательных remap),
    // и только затем аффинное отображение в исходные координаты.
    const double res = field.residual.empty() ? 0.0 : field.residual[x];
    for (int y = 0; y < field.out_h; ++y) {
      const double yEff = std::min(std::max(static_cast<double>(y) + res, 0.0),
                                   static_cast<double>(field.out_h - 1));
      mapx.at<float>(y, x) = static_cast<float>(x);
      mapy.at<float>(y, x) = static_cast<float>(field.t[x] + yEff * s);
    }
  }
  cv::Mat out;
  cv::remap(gray, out, mapx, mapy, cv::INTER_CUBIC, cv::BORDER_REPLICATE);
  return out;
}

Orientation bestRotation(const cv::Mat& gray, double min_margin, int max_side) {
  Orientation best;
  cv::Mat cw, ccw;
  cv::rotate(gray, cw, cv::ROTATE_90_CLOCKWISE);
  cv::rotate(gray, ccw, cv::ROTATE_90_COUNTERCLOCKWISE);
  const double s0 = periodicityImpl(gray, max_side);
  const double s270 = periodicityImpl(cw, max_side);  // 270 undoes a page stored 90 clockwise
  const double s90 = periodicityImpl(ccw, max_side);
  if (g_debug) {
    std::printf("    [orientation] periodicity 0=%.2f 270=%.2f 90=%.2f\n", s0, s270, s90);
  }

  const double scores[3] = {s0, s270, s90};
  const int degrees[3] = {0, 270, 90};
  double peak = 0.0;
  for (double v : scores) peak = std::max(peak, v);
  // A page without line structure anywhere (blank sheet, photo of a flat surface) must not rotate.
  best.strength = peak;
  if (!finite(peak) || peak < 1.5) return best;

  // The page is only treated as sideways when the horizontal view is clearly the periodic one.
  // Measured: a real upright scan scores 6x its rotated copies, an upright page with weak rows
  // only 1.4x, and those cases must stay as they are - a wrong rotation destroys a good page.
  if (finite(scores[0]) && scores[0] >= 1.5 && peak < 2.5 * scores[0]) return best;

  // Only orientations that actually show horizontal lines are candidates; the vertical views of
  // an upright page score ~1 and are excluded here.
  int pick = -1;
  double pick_asym = 0.0, second_asym = 0.0;
  const cv::Mat views[3] = {gray, cw, ccw};
  for (int i = 0; i < 3; ++i) {
    if (scores[i] < 0.5 * peak) continue;
    const double asym = edgeAsymmetryImpl(views[i], max_side);
    if (g_debug) {
      std::printf("    [orientation] candidate %d deg: periodicity %.2f asym %.4f", degrees[i],
                  scores[i], asym);
      std::putchar(10);
    }
    if (pick < 0 || asym > pick_asym) {
      second_asym = pick < 0 ? 0.0 : pick_asym;
      pick = i;
      pick_asym = asym;
    } else {
      second_asym = std::max(second_asym, asym);
    }
  }
  if (pick < 0 || pick == 0) {
    best.strength = peak;
    return best;
  }
  // Upright text has a hard left margin and a ragged right edge; upside down the picture is
  // mirrored. When both views look alike the direction is unknown, so do not guess.
  if (pick_asym - second_asym < 0.15) {
    best.strength = peak;
    return best;
  }
  best.strength = scores[pick];
  best.margin = pick_asym;
  best.degrees = degrees[pick];
  return best;
}

double linePeriodicity(const cv::Mat& gray, int max_side) {
  return periodicityImpl(gray, max_side);
}

Result dewarpAuto(const cv::Mat& gray, double min_gain, int deg, int bands) {
  Result r;
  r.before = lineCurvatureImpl(gray, 30, 1100, nullptr);

  Field f;
  if (!envelopeFieldImpl(gray, &f, deg, bands)) {
    r.reason = "envelope unavailable";
    r.after = r.before;
    r.image = gray;
    return r;
  }
  cv::Mat candidate = applyAffineImpl(gray, f);
  double m = lineCurvatureImpl(candidate, 30, 1100, nullptr);
  r.after = m;
  r.field = f;

  const bool gain = finite(m) && finite(r.before) && m < r.before * (1.0 - min_gain);
  if (!gain) {
    r.reason = "no metric gain";
    r.image = gray;
    return r;
  }

  // sub-line-spacing refinement, accepted only when it improves the same metric
  double sp = kNaN;
  lineCurvatureImpl(candidate, 30, 1100, &sp);
  if (finite(sp) && sp > 4.0) {
    const std::vector<double> res = residualField(robustInk(candidate), sp);
    cv::Mat refined = applyShift(candidate, res);
    const double m2 = lineCurvatureImpl(refined, 30, 1100, nullptr);
    if (finite(m2) && m2 < m) {
      candidate = refined;
      r.after = m2;
      r.refined = true;
      r.field.residual = res;
    }
  }
  r.applied = true;
  r.image = candidate;
  return r;
}

}  // namespace dewarp
