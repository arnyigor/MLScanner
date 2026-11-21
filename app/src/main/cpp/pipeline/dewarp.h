// Document dewarp: per-column affine field (vertical shift + vertical scale) recovered from
// the content envelope, gated by an independent straightness metric.
//
// Port of the validated Python prototype (page_scan.py + dewarp.py): identical stage order and
// identical parameters, so the numeric results can be compared one-to-one.

#pragma once

#include <opencv2/core.hpp>

#include <string>
#include <vector>

namespace dewarp {

// Per-column mapping: output row y' samples source row t(x) + y' * (b(x) - t(x)) / Hc.
struct Field {
    std::vector<double> t;  // content top row per column
    std::vector<double> b;  // content bottom row per column
    std::vector<double> residual;  // optional shift in affine-output pixels per column
    double hc = 0.0;        // median(b - t) before clamping — the scale denominator
    int out_h = 0;          // rows in the corrected page (== round(hc))
};

struct Result {
    bool applied = false;
    double before = NAN;  // straightness metric on the input, px
    double after = NAN;   // straightness metric on the result (or on the candidate, if rejected)
    Field field;
    cv::Mat image;  // corrected page when applied, otherwise the input
    std::string reason;
    bool refined = false;
};

// Straightness metric: median peak-to-peak vertical wander of tracked text lines, in
// original-scale pixels. NaN when too few bands keep a line.
double lineCurvature(const cv::Mat& gray, int bands = 30, int max_side = 1100,
                     double* spacing_out = nullptr);

// Orientation of the text lines.
//
// Criterion: the strength of the periodic peak in the row profile of the ink mask. Upright
// text alternates ink/blank rows with a dominant period, so the spectrum has a clear peak;
// on a page lying on its side the row profile is nearly flat and the peak disappears. The
// straightness metric cannot be used here: on a rotated page it locks onto spurious peaks
// that happen to wander little.
struct Orientation {
    int degrees = 0;      // 0, 90 or 270 counter-clockwise
    double strength = 0.0;  // periodicity strength of the chosen orientation
    double margin = 0.0;  // relative advantage over the runner-up; 0 when nothing rotated
};
Orientation bestRotation(const cv::Mat& gray, double min_margin = 0.20, int max_side = 700);

// Peak-to-mean ratio of the row-profile spectrum inside the text-line frequency band.
// ~1 means no line structure along rows (vertical lines, blank page, photo).
double linePeriodicity(const cv::Mat& gray, int max_side = 700);

// Full pipeline: estimate the field, correct, refine, and keep the result only when the
// independent metric improves by at least min_gain.
Result dewarpAuto(const cv::Mat& gray, double min_gain = 0.20, int deg = 4, int bands = 72);

extern bool g_debug;          // test harness only
extern const char* g_dump_ink;  // test harness only: save the ink mask of the metric
extern void debugDump(double sp, size_t peaks, const std::vector<double>& vals);

// Individual stages, exposed for testing.
cv::Mat inkMask(const cv::Mat& gray);
cv::Mat robustInk(const cv::Mat& gray);
bool envelopeField(const cv::Mat& gray, Field* out, int deg = 4, int bands = 72);
cv::Mat applyAffine(const cv::Mat& gray, const Field& field);
cv::Mat applyField(const cv::Mat& gray, const Field& field);

}  // namespace dewarp
