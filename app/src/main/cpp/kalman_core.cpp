#include "kalman_core.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <vector>

namespace kalman {
namespace {

constexpr double BW = 1.0;
constexpr double S_TH = 3.0;
constexpr double S_D2 = 0.05;
constexpr double TAIL_FLOOR = 0.02;
constexpr double H_MU = 0.05;
constexpr double H_SP = 0.2;
constexpr double H_SN = 0.1;
constexpr double H_NU = 1.0;
constexpr double S_O = 1.5;
constexpr double ALLCLIP = -5.0;
constexpr double HI = 99.85;
constexpr int LM_CAP = 40;

double ln033() {
    static const double v = std::log(0.33);
    return v;
}

bool finite_v(double x) { return std::isfinite(x); }

double sumsq(const std::vector<double>& r) {
    double s = 0;
    for (double v : r) s += v * v;
    return s;
}

bool all_finite(const std::vector<double>& r) {
    for (double v : r)
        if (!finite_v(v)) return false;
    return true;
}

// Lower Cholesky of a symmetric row-major matrix. Overwrites A with L (upper cleared).
bool chol(double* A, int n, double piv_min) {
    double scale = 0;
    for (int i = 0; i < n; i++) scale = std::max(scale, std::abs(A[i * n + i]));
    const double floor = piv_min * std::max(1.0, scale);
    for (int i = 0; i < n; i++) {
        for (int j = 0; j <= i; j++) {
            double s = A[i * n + j];
            for (int k = 0; k < j; k++) s -= A[i * n + k] * A[j * n + k];
            if (i == j) {
                if (!finite_v(s) || !(s > floor)) return false;
                A[i * n + j] = std::sqrt(s);
            } else {
                A[i * n + j] = s / A[j * n + j];
            }
        }
        for (int j = i + 1; j < n; j++) A[i * n + j] = 0;
    }
    return true;
}

void chol_solve(const double* L, int n, const double* b, double* x) {
    std::vector<double> y(static_cast<size_t>(n));
    for (int i = 0; i < n; i++) {
        double s = b[i];
        for (int k = 0; k < i; k++) s -= L[i * n + k] * y[static_cast<size_t>(k)];
        y[static_cast<size_t>(i)] = s / L[i * n + i];
    }
    for (int i = n - 1; i >= 0; i--) {
        double s = y[static_cast<size_t>(i)];
        for (int k = i + 1; k < n; k++) s -= L[k * n + i] * x[k];
        x[i] = s / L[i * n + i];
    }
}

void unpack_info(const double* info, double* A) {
    int k = 0;
    for (int r = 0; r < NF; r++) {
        for (int c = 0; c <= r; c++) {
            A[r * NF + c] = info[k];
            A[c * NF + r] = info[k];
            k++;
        }
    }
}

void pack_info(const double* A, double* info) {
    int k = 0;
    for (int r = 0; r < NF; r++)
        for (int c = 0; c <= r; c++) info[k++] = A[r * NF + c];
}

// θ(q) and ∂θ/∂q (100 * NF, nullable). Floored extrapolated bins have derivative 0.
void full_th(const double* q, double* th, double* dth) {
    double d[NT];
    double xm = 0;
    double ym = 0;
    for (int j = 0; j < NT; j++) {
        xm += static_cast<double>(NX + j);
        d[j] = std::exp(q[j]);
        ym += d[j];
    }
    xm /= NT;
    ym /= NT;
    double sxx = 0;
    double sxy = 0;
    for (int j = 0; j < NT; j++) {
        const double dx = static_cast<double>(NX + j) - xm;
        sxx += dx * dx;
        sxy += dx * (d[j] - ym);
    }
    const double a = sxy / sxx;
    const double b = ym - a * xm;
    if (dth) std::memset(dth, 0, sizeof(double) * static_cast<size_t>(NB * NF));
    for (int i = 0; i < NX; i++) {
        const double line = a * static_cast<double>(i) + b;
        if (line <= TAIL_FLOOR) {
            th[i] = std::log(TAIL_FLOOR);
        } else {
            th[i] = std::log(line);
            if (dth) {
                for (int j = 0; j < NT; j++) {
                    const double dx = static_cast<double>(NX + j) - xm;
                    const double dline = (static_cast<double>(i) - xm) * dx / sxx + 1.0 / NT;
                    dth[i * NF + j] = dline * d[j] / line;
                }
            }
        }
    }
    for (int i = 0; i < NF; i++) {
        th[NX + i] = q[i];
        if (dth) dth[(NX + i) * NF + i] = 1.0;
    }
}

void dens_of(const double* th, double* dens) {
    for (int i = 0; i < NB; i++) dens[i] = std::exp(th[i]);
}

void knots_of(const double* dens, double* gKnot) {
    gKnot[0] = 0;
    double c = 0;
    for (int i = 0; i < NB; i++) {
        c += dens[i] * BW;
        gKnot[i + 1] = c;
    }
}

double interp_e(double x, const double* gKnot) {
    if (x <= 0) return gKnot[0];
    if (x >= 100.0) return gKnot[NB];
    int i = static_cast<int>(std::floor(x / BW));
    if (i < 0) i = 0;
    if (i > NB - 1) i = NB - 1;
    const double frac = (x - BW * static_cast<double>(i)) / BW;
    return gKnot[i] * (1.0 - frac) + gKnot[i + 1] * frac;
}

struct Hit {
    int kind; // 0 below 0, 1 past the end, 2 inside bin
    int bin;
    double dL_dv;
    double level;
};

Hit linv_hit(double v, const double* gKnot, const double* dens) {
    Hit h{};
    if (v < 0) {
        h.kind = 0;
        h.bin = 0;
        h.dL_dv = 1.0 / dens[0];
        h.level = v / dens[0];
        return h;
    }
    if (v > gKnot[NB]) {
        h.kind = 1;
        h.bin = NB - 1;
        h.dL_dv = 1.0 / dens[NB - 1];
        h.level = 100.0 + (v - gKnot[NB]) / dens[NB - 1];
        return h;
    }
    int i = 0;
    while (i < NB && gKnot[i + 1] < v) i++;
    const double span = gKnot[i + 1] - gKnot[i];
    const double frac = span == 0.0 ? 0.0 : (v - gKnot[i]) / span;
    h.kind = 2;
    h.bin = i;
    h.dL_dv = dens[i] == 0.0 ? 0.0 : 1.0 / dens[i];
    h.level = BW * static_cast<double>(i) + frac * BW;
    return h;
}

void add_dens(int t, double dL_dd, const double* dens, const double* dth, double* row) {
    if (dL_dd == 0.0 || !finite_v(dL_dd)) return;
    if (t >= NX) {
        const int s = t - NX;
        row[s] += dL_dd * dens[t] * dth[t * NF + s];
        return;
    }
    for (int s = 0; s < NT; s++) {
        const double d = dth[t * NF + s];
        if (d != 0.0) row[s] += dL_dd * dens[t] * d;
    }
}

// ∂level/∂q into row[0..NF).
void level_q(const Hit& h, double v, const double* gKnot, const double* dens, const double* dth, double* row) {
    if (h.kind == 0) {
        add_dens(0, -v / (dens[0] * dens[0]), dens, dth, row);
        return;
    }
    if (h.kind == 1) {
        const double dlast = dens[NB - 1];
        const double base = -BW / dlast;
        const double extra = -(v - gKnot[NB]) / (dlast * dlast);
        for (int t = 0; t < NB; t++) add_dens(t, t == NB - 1 ? base + extra : base, dens, dth, row);
        return;
    }
    const int i = h.bin;
    const double di = dens[i];
    for (int t = 0; t <= i; t++) {
        const double dL = t < i ? (-BW / di) : (-(v - gKnot[i]) / (di * di));
        add_dens(t, dL, dens, dth, row);
    }
}

void k_values(const Problem& prob, const double* z, double* k) {
    const int ns = prob.ns();
    for (int j = 0; j < ns; j++) {
        if (prob.first[j]) k[j] = z[j];
        else if (j == 0) k[j] = prob.anchorK - (prob.C0[0] - prob.anchorC0) - z[j];
        else k[j] = k[j - 1] - (prob.C0[j] - prob.C0[j - 1]) - z[j];
    }
}

// coeff[j * ns + m] = ∂k[j]/∂z[m]. A continuation z moves later k until the next fill.
void k_coeff(const Problem& prob, std::vector<double>& coeff) {
    const int ns = prob.ns();
    coeff.assign(static_cast<size_t>(ns * ns), 0.0);
    for (int m = 0; m < ns; m++) {
        const double s = prob.first[m] ? 1.0 : -1.0;
        for (int j = m; j < ns; j++) {
            if (j > m && prob.first[j]) break;
            coeff[static_cast<size_t>(j * ns + m)] = s;
        }
    }
}

double tres(double h, double* d_dh) {
    const double scale = h >= H_MU ? H_SP : H_SN;
    const double u = (h - H_MU) / scale;
    const double uu = u * u;
    const double inside = (H_NU + 1.0) * std::log1p(uu / H_NU);
    const double s = std::sqrt(std::max(0.0, inside));
    const double val = (u >= 0.0 ? s : -s);
    if (d_dh) {
        if (s < 1e-12) {
            *d_dh = std::sqrt((H_NU + 1.0) / H_NU) / scale;
        } else {
            const double ds_du = (H_NU + 1.0) * u / (s * (H_NU + uu));
            const double dt_du = u >= 0.0 ? ds_du : -ds_du;
            *d_dh = dt_du / scale;
        }
    }
    return val;
}

int row_count(const Problem& prob) {
    const int ns = prob.ns();
    const int nobs = prob.nobs();
    int ncont = 0;
    for (int j = 0; j < ns; j++)
        if (!prob.first[j]) ncont++;
    if (prob.mode == 0) return nobs + NF + (NB - 2) + ns + ncont;
    return NF + nobs + ns + ncont;
}

bool factor_info(const double* info, double* L) {
    unpack_info(info, L);
    return chol(L, NF, 1e-14);
}

void segment_means(const Problem& prob, const double* q, double* k) {
    const int ns = prob.ns();
    double th[NB];
    double dens[NB];
    double gKnot[NB + 1];
    full_th(q, th, nullptr);
    dens_of(th, dens);
    knots_of(dens, gKnot);
    std::vector<double> sum(static_cast<size_t>(ns), 0.0);
    std::vector<int> cnt(static_cast<size_t>(ns), 0);
    const int nobs = prob.nobs();
    for (int i = 0; i < nobs; i++) {
        const int j = prob.seg[static_cast<size_t>(i)];
        if (j < 0 || j >= ns) continue;
        sum[static_cast<size_t>(j)] += interp_e(prob.L[static_cast<size_t>(i)], gKnot) + prob.F[static_cast<size_t>(i)];
        cnt[static_cast<size_t>(j)]++;
    }
    for (int j = 0; j < ns; j++) {
        const int c = cnt[static_cast<size_t>(j)];
        k[j] = c > 0 ? sum[static_cast<size_t>(j)] / static_cast<double>(c) : 0.0;
    }
}

bool solve_spd(double* A, int n, const double* b, double* x) {
    if (!chol(A, n, 1e-14)) return false;
    chol_solve(A, n, b, x);
    for (int i = 0; i < n; i++)
        if (!finite_v(x[i])) return false;
    return true;
}

void jtj(const std::vector<double>& J, const std::vector<double>& r, int m, int n, std::vector<double>& H, std::vector<double>& jtr, std::vector<double>& scale) {
    H.assign(static_cast<size_t>(n * n), 0.0);
    jtr.assign(static_cast<size_t>(n), 0.0);
    scale.assign(static_cast<size_t>(n), 0.0);
    for (int i = 0; i < m; i++) {
        const double ri = r[static_cast<size_t>(i)];
        const double* row = J.data() + static_cast<size_t>(i) * static_cast<size_t>(n);
        for (int a = 0; a < n; a++) {
            const double Ja = row[a];
            if (Ja == 0.0) continue;
            jtr[static_cast<size_t>(a)] += Ja * ri;
            scale[static_cast<size_t>(a)] += Ja * Ja;
            double* Hr = H.data() + static_cast<size_t>(a * n);
            for (int b = a; b < n; b++) Hr[b] += Ja * row[b];
        }
    }
    for (int a = 0; a < n; a++) {
        scale[static_cast<size_t>(a)] = std::max(1e-8, std::sqrt(scale[static_cast<size_t>(a)]));
        for (int b = 0; b < a; b++) H[static_cast<size_t>(a * n + b)] = H[static_cast<size_t>(b * n + a)];
    }
}

// S = Hqq - Hqs Hss^{-1} Hsq. ns2 == 0 copies Hqq.
bool schur_q(const std::vector<double>& H, int n, int nq, int ns2, std::vector<double>& S) {
    S.assign(static_cast<size_t>(nq * nq), 0.0);
    if (ns2 == 0) {
        for (int r = 0; r < nq; r++)
            for (int c = 0; c < nq; c++) S[static_cast<size_t>(r * nq + c)] = H[static_cast<size_t>(r * n + c)];
        return true;
    }
    std::vector<double> L(static_cast<size_t>(ns2 * ns2));
    for (int r = 0; r < ns2; r++)
        for (int c = 0; c < ns2; c++)
            L[static_cast<size_t>(r * ns2 + c)] = H[static_cast<size_t>((nq + r) * n + (nq + c))];
    if (!chol(L.data(), ns2, 1e-12)) return false;
    std::vector<double> b(static_cast<size_t>(ns2));
    std::vector<double> y(static_cast<size_t>(ns2));
    for (int j = 0; j < nq; j++) {
        for (int t = 0; t < ns2; t++) b[static_cast<size_t>(t)] = H[static_cast<size_t>((nq + t) * n + j)];
        chol_solve(L.data(), ns2, b.data(), y.data());
        for (int r = 0; r < nq; r++) {
            double acc = H[static_cast<size_t>(r * n + j)];
            const double* row = H.data() + static_cast<size_t>(r * n + nq);
            for (int t = 0; t < ns2; t++) acc -= row[t] * y[static_cast<size_t>(t)];
            S[static_cast<size_t>(r * nq + j)] = acc;
        }
    }
    for (int r = 0; r < nq; r++) {
        for (int c = 0; c < r; c++) {
            const double v = 0.5 * (S[static_cast<size_t>(r * nq + c)] + S[static_cast<size_t>(c * nq + r)]);
            S[static_cast<size_t>(r * nq + c)] = v;
            S[static_cast<size_t>(c * nq + r)] = v;
        }
    }
    return true;
}

} // namespace

int nparam(const Problem& prob) { return NF + 2 * prob.ns(); }

double capacity(const double* q) {
    double th[NB];
    double dens[NB];
    full_th(q, th, nullptr);
    dens_of(th, dens);
    double s = 0;
    for (int i = 0; i < 98; i++) s += dens[i] * BW;
    return s;
}

void capacity_grad(const double* q, double* dg) {
    double th[NB];
    double dth[NB * NF];
    double dens[NB];
    full_th(q, th, dth);
    dens_of(th, dens);
    for (int s = 0; s < NF; s++) dg[s] = 0;
    for (int i = 0; i < 98; i++) {
        const double di = dens[i] * BW;
        if (i >= NX) {
            dg[i - NX] += di * dth[i * NF + (i - NX)];
        } else {
            for (int s = 0; s < NT; s++) dg[s] += di * dth[i * NF + s];
        }
    }
}

bool info_pd(const double* info) {
    if (!info) return false;
    for (int i = 0; i < INFO_N; i++)
        if (!finite_v(info[i])) return false;
    double L[NF * NF];
    return factor_info(info, L);
}

void initial_p(const Problem& prob, std::vector<double>& p) {
    const int ns = prob.ns();
    p.assign(static_cast<size_t>(NF + 2 * ns), 0.0);
    double qsrc[NF];
    if (prob.haveSaved) {
        for (int i = 0; i < NF; i++) qsrc[i] = prob.qSaved[i];
    } else {
        const double q0 = ln033();
        for (int i = 0; i < NF; i++) qsrc[i] = q0;
    }
    for (int i = 0; i < NF; i++) p[static_cast<size_t>(i)] = qsrc[i];
    if (ns == 0) return;
    std::vector<double> k(static_cast<size_t>(ns));
    segment_means(prob, qsrc, k.data());
    const bool match_ok = prob.haveSaved && static_cast<int>(prob.matched.size()) == ns &&
        static_cast<int>(prob.zSaved.size()) == ns && static_cast<int>(prob.oSaved.size()) == ns;
    for (int j = 0; j < ns; j++) {
        if (match_ok && prob.matched[static_cast<size_t>(j)]) {
            p[static_cast<size_t>(NF + j)] = prob.zSaved[static_cast<size_t>(j)];
            p[static_cast<size_t>(NF + ns + j)] = prob.oSaved[static_cast<size_t>(j)];
            continue;
        }
        double z;
        if (prob.first[j]) z = k[static_cast<size_t>(j)];
        else if (j == 0) z = prob.anchorK - (prob.C0[0] - prob.anchorC0) - k[0];
        else z = k[static_cast<size_t>(j - 1)] - (prob.C0[j] - prob.C0[j - 1]) - k[static_cast<size_t>(j)];
        p[static_cast<size_t>(NF + j)] = z;
    }
}

void eval(const Problem& prob, const double* p, std::vector<double>* residual, std::vector<double>* jac) {
    const int ns = prob.ns();
    const int nobs = prob.nobs();
    const int n = NF + 2 * ns;
    const int m = row_count(prob);
    if (residual) residual->assign(static_cast<size_t>(m), 0.0);
    if (jac) jac->assign(static_cast<size_t>(m) * static_cast<size_t>(n), 0.0);
    auto row_at = [&](int i) -> double* {
        if (!jac) return nullptr;
        return jac->data() + static_cast<size_t>(i) * static_cast<size_t>(n);
    };
    auto set_r = [&](int i, double v) {
        if (residual) (*residual)[static_cast<size_t>(i)] = v;
    };

    double th[NB];
    double dth[NB * NF];
    double dens[NB];
    double gKnot[NB + 1];
    full_th(p, th, jac ? dth : nullptr);
    dens_of(th, dens);
    knots_of(dens, gKnot);

    std::vector<double> k;
    std::vector<double> coeff;
    if (ns > 0) {
        k.resize(static_cast<size_t>(ns));
        k_values(prob, p + NF, k.data());
        if (jac) k_coeff(prob, coeff);
    }

    int row = 0;
    if (prob.mode == 1) {
        double Lfac[NF * NF];
        if (!factor_info(prob.info, Lfac)) {
            if (residual) {
                for (int i = 0; i < m; i++) (*residual)[static_cast<size_t>(i)] = std::nan("");
            }
            return;
        }
        for (int i = 0; i < NF; i++) {
            double s = 0;
            for (int j = i; j < NF; j++) s += Lfac[j * NF + i] * (p[j] - prob.qSaved[j]);
            set_r(row, s);
            if (double* Jr = row_at(row)) {
                for (int j = i; j < NF; j++) Jr[j] = Lfac[j * NF + i];
            }
            row++;
        }
    }

    for (int i = 0; i < nobs; i++) {
        const int j = prob.seg[static_cast<size_t>(i)];
        const double sd = prob.sd[static_cast<size_t>(i)];
        const double lv = prob.L[static_cast<size_t>(i)];
        const double v = (j >= 0 && j < ns) ? k[static_cast<size_t>(j)] - prob.F[static_cast<size_t>(i)] : std::nan("");
        const double oj = (j >= 0 && j < ns) ? p[NF + ns + j] : 0.0;
        const Hit hit = linv_hit(v, gKnot, dens);
        const double floored = hit.level > 0.0 ? hit.level : 0.0;
        const double pr = floored + oj;
        double ri = 0;
        double dres_dpr = 0;
        if (!(sd > 0.0) || !finite_v(v) || !finite_v(lv)) {
            ri = std::nan("");
        } else if (lv <= ALLCLIP) {
            if (pr > 0.0) {
                ri = pr / sd;
                dres_dpr = 1.0 / sd;
            }
        } else if (lv >= HI) {
            if (pr < HI) {
                ri = (HI - pr) / sd;
                dres_dpr = -1.0 / sd;
            }
        } else {
            ri = (lv - pr) / sd;
            dres_dpr = -1.0 / sd;
        }
        set_r(row, ri);
        if (double* Jr = row_at(row)) {
            if (dres_dpr != 0.0 && j >= 0 && j < ns) {
                const double dpr_dlinv = hit.level > 0.0 ? 1.0 : 0.0;
                if (dpr_dlinv != 0.0) {
                    std::vector<double> dq(static_cast<size_t>(NF), 0.0);
                    level_q(hit, v, gKnot, dens, dth, dq.data());
                    const double w = dres_dpr * dpr_dlinv;
                    for (int s = 0; s < NF; s++) Jr[s] = w * dq[static_cast<size_t>(s)];
                    const double dL_dz_base = w * hit.dL_dv;
                    for (int m = 0; m < ns; m++) {
                        const double c = coeff[static_cast<size_t>(j * ns + m)];
                        if (c != 0.0) Jr[NF + m] = dL_dz_base * c;
                    }
                }
                Jr[NF + ns + j] = dres_dpr;
            }
        }
        row++;
    }

    if (prob.mode == 0) {
        for (int j = 0; j < NF; j++) {
            set_r(row, (p[j] - ln033()) / S_TH);
            if (double* Jr = row_at(row)) Jr[j] = 1.0 / S_TH;
            row++;
        }
        for (int j = 0; j < NB - 2; j++) {
            set_r(row, (th[j + 2] - 2.0 * th[j + 1] + th[j]) / S_D2);
            if (double* Jr = row_at(row)) {
                for (int s = 0; s < NF; s++) {
                    const double d = dth[(j + 2) * NF + s] - 2.0 * dth[(j + 1) * NF + s] + dth[j * NF + s];
                    if (d != 0.0) Jr[s] = d / S_D2;
                }
            }
            row++;
        }
    }

    for (int j = 0; j < ns; j++) {
        set_r(row, p[NF + ns + j] / S_O);
        if (double* Jr = row_at(row)) Jr[NF + ns + j] = 1.0 / S_O;
        row++;
    }
    for (int j = 0; j < ns; j++) {
        if (prob.first[j]) continue;
        double dt = 0;
        const double tv = tres(p[NF + j], jac ? &dt : nullptr);
        set_r(row, tv);
        if (double* Jr = row_at(row)) Jr[NF + j] = dt;
        row++;
    }
}

int posterior(const Problem& prob, const double* p, double* info, double* sd_out, double* g98_out) {
    std::vector<double> r;
    std::vector<double> J;
    eval(prob, p, &r, &J);
    if (!all_finite(r)) return 2;
    const int m = static_cast<int>(r.size());
    const int n = nparam(prob);
    std::vector<double> H, jtr, scale;
    jtj(J, r, m, n, H, jtr, scale);
    const int ns2 = 2 * prob.ns();
    std::vector<double> S;
    if (!schur_q(H, n, NF, ns2, S)) return 1;
    if (info) pack_info(S.data(), info);
    double dg[NF];
    capacity_grad(p, dg);
    if (g98_out) *g98_out = capacity(p);
    std::vector<double> L = S;
    double rhs[NF];
    double x[NF];
    for (int i = 0; i < NF; i++) rhs[i] = dg[i];
    if (!solve_spd(L.data(), NF, rhs, x)) return 1;
    double v = 0;
    for (int i = 0; i < NF; i++) v += dg[i] * x[i];
    if (!finite_v(v)) return 2;
    if (v < 0.0 && v > -1e-8) v = 0.0;
    if (v < 0.0) return 1;
    if (sd_out) *sd_out = std::sqrt(v);
    return 0;
}

FitResult fit(const Problem& prob) {
    FitResult out;
    const int ns = prob.ns();
    const int n = nparam(prob);
    if (prob.mode == 1 && !info_pd(prob.info)) {
        out.status = 1;
        return out;
    }
    std::vector<double> p;
    initial_p(prob, p);
    std::vector<double> r;
    eval(prob, p.data(), &r, nullptr);
    if (!all_finite(r)) {
        out.status = 2;
        return out;
    }
    double cost = sumsq(r);
    if (!finite_v(cost)) {
        out.status = 2;
        return out;
    }
    double lambda = 1e-3;
    int stop = 3;
    int iters = 0;
    bool done = false;
    while (iters < LM_CAP && !done) {
        iters++;
        std::vector<double> J;
        eval(prob, p.data(), &r, &J);
        if (!all_finite(r)) {
            out.status = 2;
            return out;
        }
        const int m = static_cast<int>(r.size());
        std::vector<double> H, jtr, scale;
        jtj(J, r, m, n, H, jtr, scale);
        double gnorm = 0;
        for (int c = 0; c < n; c++) gnorm = std::max(gnorm, std::abs(jtr[static_cast<size_t>(c)]) / scale[static_cast<size_t>(c)]);
        if (gnorm < 1e-6) {
            done = true;
            stop = 0;
            break;
        }
        const double g_old = capacity(p.data());
        bool improved = false;
        double step_rel = 1e300;
        for (int inner = 0; inner < 12; inner++) {
            std::vector<double> A = H;
            for (int c = 0; c < n; c++) A[static_cast<size_t>(c * n + c)] += lambda * scale[static_cast<size_t>(c)] * scale[static_cast<size_t>(c)];
            std::vector<double> neg(static_cast<size_t>(n));
            for (int c = 0; c < n; c++) neg[static_cast<size_t>(c)] = -jtr[static_cast<size_t>(c)];
            std::vector<double> delta(static_cast<size_t>(n));
            if (!solve_spd(A.data(), n, neg.data(), delta.data())) {
                lambda = std::min(lambda * 8.0, 1e12);
                continue;
            }
            std::vector<double> p2(static_cast<size_t>(n));
            step_rel = 0;
            for (int c = 0; c < n; c++) {
                p2[static_cast<size_t>(c)] = p[static_cast<size_t>(c)] + delta[static_cast<size_t>(c)];
                step_rel = std::max(step_rel, std::abs(delta[static_cast<size_t>(c)]) / std::max(1.0, std::abs(p[static_cast<size_t>(c)])));
            }
            std::vector<double> r2;
            eval(prob, p2.data(), &r2, nullptr);
            if (!all_finite(r2)) {
                out.status = 2;
                return out;
            }
            const double cost2 = sumsq(r2);
            if (!finite_v(cost2)) {
                out.status = 2;
                return out;
            }
            if (cost2 < cost) {
                p.swap(p2);
                r.swap(r2);
                cost = cost2;
                lambda = std::max(lambda / 3.0, 1e-12);
                improved = true;
                break;
            }
            lambda = std::min(lambda * 8.0, 1e12);
        }
        if (improved && step_rel < 1e-5) {
            done = true;
            stop = 1;
            break;
        }
        if (improved && std::abs(capacity(p.data()) - g_old) < 0.01) {
            done = true;
            stop = 2;
            break;
        }
        if (!improved && lambda >= 1e12) {
            done = true;
            stop = 4;
            break;
        }
    }
    if (!done) stop = 3;
    for (int i = 0; i < n; i++) {
        if (!finite_v(p[static_cast<size_t>(i)])) {
            out.status = 2;
            return out;
        }
    }
    double info[INFO_N];
    double sd = 0;
    double g98 = 0;
    const int pst = posterior(prob, p.data(), info, &sd, &g98);
    if (pst != 0) {
        out.status = pst;
        out.iters = iters;
        out.stop = stop;
        return out;
    }
    if (!finite_v(g98) || !finite_v(sd)) {
        out.status = 2;
        return out;
    }
    for (int i = 0; i < NF; i++) out.q[i] = p[static_cast<size_t>(i)];
    out.z.resize(static_cast<size_t>(ns));
    out.o.resize(static_cast<size_t>(ns));
    for (int j = 0; j < ns; j++) {
        out.z[static_cast<size_t>(j)] = p[static_cast<size_t>(NF + j)];
        out.o[static_cast<size_t>(j)] = p[static_cast<size_t>(NF + ns + j)];
    }
    double th[NB];
    full_th(out.q, th, nullptr);
    out.g.resize(NB);
    for (int i = 0; i < NB; i++) out.g[static_cast<size_t>(i)] = std::exp(th[i]);
    out.g98 = g98;
    out.sd98 = sd;
    if (ns > 0) {
        std::vector<double> k(static_cast<size_t>(ns));
        k_values(prob, out.z.data(), k.data());
        out.kTail = k[static_cast<size_t>(ns - 1)];
    }
    std::memcpy(out.info, info, sizeof(info));
    out.iters = iters;
    out.stop = stop;
    out.status = 0;
    return out;
}

} // namespace kalman
