#pragma once

#include <vector>

// Shared full/incremental Kalman map fit. The host test and the JNI library both call this.
namespace kalman {

constexpr int NB = 100;
constexpr int NX = 10;
constexpr int NT = 15;
constexpr int NF = NB - NX;
constexpr int INFO_N = NF * (NF + 1) / 2;

struct Problem {
    int mode = 0; // 0 full (priors on), 1 incremental (saved information, new rows only)
    std::vector<double> L, F, sd;
    std::vector<int> seg;
    std::vector<double> C0;
    std::vector<int> first; // 1 = new epoch
    int haveSaved = 0;
    double qSaved[NF]{};
    double info[INFO_N]{}; // lower triangle, row r >= c packed r*(r+1)/2+c
    double anchorK = 0;
    double anchorC0 = 0;
    std::vector<int> matched; // full warm start: 1 keeps zSaved/oSaved
    std::vector<double> zSaved, oSaved;

    int ns() const { return static_cast<int>(C0.size()); }
    int nobs() const { return static_cast<int>(L.size()); }
};

struct FitResult {
    int status = 0; // 0 ok, 1 singular Schur / covariance, 2 non-finite
    double q[NF]{};
    std::vector<double> z, o, g;
    double g98 = 0;
    double sd98 = 0;
    double kTail = 0;
    double info[INFO_N]{};
    int iters = 0;
    int stop = 0; // 0 grad, 1 step, 2 unused, 3 iters, 4 stall
};

int nparam(const Problem& prob);

// residual and, if jac != nullptr, row-major Jacobian (m * nparam).
void eval(const Problem& prob, const double* p, std::vector<double>* residual, std::vector<double>* jac);

void initial_p(const Problem& prob, std::vector<double>& p);

// Undamped Gauss-Newton information and sd98 at p. status 0 / 1 / 2.
int posterior(const Problem& prob, const double* p, double* info, double* sd_out, double* g98_out);

FitResult fit(const Problem& prob);

bool info_pd(const double* info);

double capacity(const double* q);
void capacity_grad(const double* q, double* dg);

} // namespace kalman
