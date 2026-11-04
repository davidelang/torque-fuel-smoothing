#include "kalman_core.h"

#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

static int g_fails = 0;

static void check(bool ok, const char* msg) {
    if (ok) {
        std::printf("ok %s\n", msg);
        return;
    }
    std::fprintf(stderr, "FAIL %s\n", msg);
    g_fails++;
}

static void fill_q(double* q, double value) {
    for (int i = 0; i < kalman::NF; i++) q[i] = value;
}

static bool dense_solve(std::vector<double> a, std::vector<double> b, std::vector<double>& x) {
    const int n = static_cast<int>(b.size());
    x.assign(static_cast<size_t>(n), 0.0);
    double scale = 0;
    for (double v : a) scale = std::max(scale, std::abs(v));
    for (int c = 0; c < n; c++) {
        int piv = c;
        for (int r = c + 1; r < n; r++)
            if (std::abs(a[static_cast<size_t>(r * n + c)]) > std::abs(a[static_cast<size_t>(piv * n + c)])) piv = r;
        if (std::abs(a[static_cast<size_t>(piv * n + c)]) <= 1e-14 * std::max(1.0, scale)) return false;
        for (int k = c; k < n; k++) std::swap(a[static_cast<size_t>(c * n + k)], a[static_cast<size_t>(piv * n + k)]);
        std::swap(b[static_cast<size_t>(c)], b[static_cast<size_t>(piv)]);
        const double d = a[static_cast<size_t>(c * n + c)];
        for (int k = c; k < n; k++) a[static_cast<size_t>(c * n + k)] /= d;
        b[static_cast<size_t>(c)] /= d;
        for (int r = 0; r < n; r++) {
            if (r == c) continue;
            const double f = a[static_cast<size_t>(r * n + c)];
            if (f == 0.0) continue;
            for (int k = c; k < n; k++) a[static_cast<size_t>(r * n + k)] -= f * a[static_cast<size_t>(c * n + k)];
            b[static_cast<size_t>(r)] -= f * b[static_cast<size_t>(c)];
        }
    }
    x.swap(b);
    return true;
}

static kalman::Problem base_problem() {
    kalman::Problem prob;
    prob.mode = 0;
    // Steep free bins so bins 0–10 hit TAIL_FLOOR. Two epochs, mixed censoring and a below-zero gallon.
    for (int j = 0; j < 2; j++) {
        prob.C0.push_back(j == 0 ? 0.0 : 12.0);
        prob.first.push_back(j == 0 ? 1 : 0);
    }
    const double levels[] = {40, 55, 70, 96, -10, 30, 20, 110, 15, 8};
    const double fuels[] = {1, 2, 3, 4, 5, 0.5, 1.5, 2.5, 6, 7};
    const int segs[] = {0, 0, 0, 0, 0, 1, 1, 1, 1, 1};
    for (int i = 0; i < 10; i++) {
        prob.L.push_back(levels[i]);
        prob.F.push_back(fuels[i]);
        prob.sd.push_back(1.2);
        prob.seg.push_back(segs[i]);
    }
    return prob;
}

static void test_jacobian() {
    // Off the censor and linv kinks: in-bin rows, one below-zero gallon, one past the end,
    // a floored low tail, and a continuation hole away from the Student-t knot.
    kalman::Problem prob;
    prob.mode = 0;
    prob.C0 = {0.0, 12.0};
    prob.first = {1, 0};
    const double levels[] = {40, 55, 70, 96, -10, 30, 20, 110, 15, 50};
    const double fuels[] = {2, 4, 6, 8, 40, 1, 3, 2, 5, 6};
    const int segs[] = {0, 0, 0, 0, 0, 1, 1, 1, 1, 1};
    for (int i = 0; i < 10; i++) {
        prob.L.push_back(levels[i]);
        prob.F.push_back(fuels[i]);
        prob.sd.push_back(1.2);
        prob.seg.push_back(segs[i]);
    }
    const int n = kalman::nparam(prob);
    std::vector<double> p(static_cast<size_t>(n), std::log(0.4));
    for (int j = 0; j < kalman::NT; j++) p[static_cast<size_t>(j)] = std::log(0.05 + 0.08 * j);
    p[static_cast<size_t>(kalman::NF)] = 22.0;
    p[static_cast<size_t>(kalman::NF + 1)] = -80.0;
    p[static_cast<size_t>(kalman::NF + 2)] = 0.4;
    p[static_cast<size_t>(kalman::NF + 3)] = -0.25;
    std::vector<double> r, J;
    kalman::eval(prob, p.data(), &r, &J);
    bool finite = true;
    for (double v : r)
        if (!std::isfinite(v)) finite = false;
    check(finite, "jacobian residual finite");
    const int m = static_cast<int>(r.size());
    double worst = 0;
    int worst_i = -1;
    int worst_j = -1;
    for (int j = 0; j < n; j++) {
        const double h = 1e-6 * std::max(1.0, std::abs(p[static_cast<size_t>(j)]));
        std::vector<double> pp = p;
        std::vector<double> pm = p;
        pp[static_cast<size_t>(j)] += h;
        pm[static_cast<size_t>(j)] -= h;
        std::vector<double> rp, rm;
        kalman::eval(prob, pp.data(), &rp, nullptr);
        kalman::eval(prob, pm.data(), &rm, nullptr);
        for (int i = 0; i < m; i++) {
            const double cd = (rp[static_cast<size_t>(i)] - rm[static_cast<size_t>(i)]) / (2.0 * h);
            const double an = J[static_cast<size_t>(i) * static_cast<size_t>(n) + static_cast<size_t>(j)];
            const double err = std::abs(an - cd);
            if (err > worst) {
                worst = err;
                worst_i = i;
                worst_j = j;
            }
        }
    }
    std::printf("jacobian worst abs err %.3e at row %d col %d\n", worst, worst_i, worst_j);
    check(worst < 2e-4, "analytic Jacobian vs central difference");

    kalman::Problem inc = prob;
    inc.mode = 1;
    inc.haveSaved = 1;
    fill_q(inc.qSaved, std::log(0.4));
    for (int i = 0; i < kalman::NF; i++) {
        for (int c = 0; c <= i; c++) inc.info[i * (i + 1) / 2 + c] = (i == c) ? 4.0 : 0.0;
    }
    inc.anchorK = 16;
    inc.anchorC0 = 0;
    std::vector<double> ri, Ji;
    kalman::eval(inc, p.data(), &ri, &Ji);
    const int mi = static_cast<int>(ri.size());
    double worst_i_err = 0;
    for (int j = 0; j < n; j++) {
        const double h = 1e-6 * std::max(1.0, std::abs(p[static_cast<size_t>(j)]));
        std::vector<double> pp = p, pm = p;
        pp[static_cast<size_t>(j)] += h;
        pm[static_cast<size_t>(j)] -= h;
        std::vector<double> rp, rm;
        kalman::eval(inc, pp.data(), &rp, nullptr);
        kalman::eval(inc, pm.data(), &rm, nullptr);
        for (int i = 0; i < mi; i++) {
            const double cd = (rp[static_cast<size_t>(i)] - rm[static_cast<size_t>(i)]) / (2.0 * h);
            const double an = Ji[static_cast<size_t>(i) * static_cast<size_t>(n) + static_cast<size_t>(j)];
            worst_i_err = std::max(worst_i_err, std::abs(an - cd));
        }
    }
    std::printf("inc jacobian worst abs err %.3e\n", worst_i_err);
    check(worst_i_err < 2e-4, "incremental Jacobian vs central difference");

    // First new segment continues the saved anchor: k = kTail - (C0 - c0Tail) - z.
    kalman::Problem cont;
    cont.mode = 1;
    cont.haveSaved = 1;
    cont.C0 = {14.0};
    cont.first = {0};
    cont.anchorK = 30.0;
    cont.anchorC0 = 4.0;
    for (int i = 0; i < kalman::NF; i++) {
        cont.qSaved[i] = std::log(0.34);
        for (int c = 0; c <= i; c++) cont.info[i * (i + 1) / 2 + c] = (i == c) ? 3.0 : 0.0;
    }
    for (int i = 0; i < 4; i++) {
        cont.L.push_back(45.0 - 3.0 * i);
        cont.F.push_back(0.8 * i);
        cont.sd.push_back(1.0);
        cont.seg.push_back(0);
    }
    const int nc = kalman::nparam(cont);
    std::vector<double> pc(static_cast<size_t>(nc), std::log(0.34));
    pc[static_cast<size_t>(kalman::NF)] = 0.7;
    pc[static_cast<size_t>(kalman::NF + 1)] = 0.1;
    std::vector<double> rc, Jc;
    kalman::eval(cont, pc.data(), &rc, &Jc);
    double worst_c = 0;
    for (int j = 0; j < nc; j++) {
        const double h = 1e-6 * std::max(1.0, std::abs(pc[static_cast<size_t>(j)]));
        std::vector<double> pp = pc, pm = pc;
        pp[static_cast<size_t>(j)] += h;
        pm[static_cast<size_t>(j)] -= h;
        std::vector<double> rp, rm;
        kalman::eval(cont, pp.data(), &rp, nullptr);
        kalman::eval(cont, pm.data(), &rm, nullptr);
        for (int i = 0; i < static_cast<int>(rc.size()); i++) {
            const double cd = (rp[static_cast<size_t>(i)] - rm[static_cast<size_t>(i)]) / (2.0 * h);
            const double an = Jc[static_cast<size_t>(i) * static_cast<size_t>(nc) + static_cast<size_t>(j)];
            worst_c = std::max(worst_c, std::abs(an - cd));
        }
    }
    std::printf("anchor continuation jacobian worst %.3e\n", worst_c);
    check(worst_c < 2e-4, "anchor continuation Jacobian vs central difference");
}

static void test_sd98() {
    kalman::Problem prob = base_problem();
    // Mild map so the normal equations are positive definite away from the floor kink.
    const int n = kalman::nparam(prob);
    std::vector<double> p(static_cast<size_t>(n), std::log(0.36));
    p[static_cast<size_t>(kalman::NF)] = 20;
    p[static_cast<size_t>(kalman::NF + 1)] = 0.1;
    p[static_cast<size_t>(kalman::NF + 2)] = 0.0;
    p[static_cast<size_t>(kalman::NF + 3)] = 0.0;
    double info[kalman::INFO_N];
    double sd = 0;
    double g98 = 0;
    const int st = kalman::posterior(prob, p.data(), info, &sd, &g98);
    check(st == 0, "posterior sd98 status");
    double dg[kalman::NF];
    kalman::capacity_grad(p.data(), dg);
    double worst_g = 0;
    for (int s = 0; s < kalman::NF; s += 9) {
        const double h = 1e-6;
        std::vector<double> qp(p.begin(), p.begin() + kalman::NF);
        qp[static_cast<size_t>(s)] += h;
        const double cd = (kalman::capacity(qp.data()) - g98) / h;
        worst_g = std::max(worst_g, std::abs(cd - dg[s]));
    }
    std::printf("capacity grad worst %.3e\n", worst_g);
    check(worst_g < 1e-4, "analytic dG(98) vs central difference");

    std::vector<double> r, J;
    kalman::eval(prob, p.data(), &r, &J);
    const int m = static_cast<int>(r.size());
    std::vector<double> H(static_cast<size_t>(n * n), 0.0);
    for (int i = 0; i < m; i++) {
        const double* row = J.data() + static_cast<size_t>(i) * static_cast<size_t>(n);
        for (int a = 0; a < n; a++) {
            if (row[a] == 0.0) continue;
            for (int b = 0; b < n; b++) H[static_cast<size_t>(a * n + b)] += row[a] * row[b];
        }
    }
    std::vector<double> rhs(static_cast<size_t>(n), 0.0);
    for (int s = 0; s < kalman::NF; s++) rhs[static_cast<size_t>(s)] = dg[s];
    std::vector<double> x;
    const bool solved = dense_solve(H, rhs, x);
    check(solved, "dense normal-equation solve");
    double var = 0;
    if (solved) {
        for (int s = 0; s < kalman::NF; s++) var += dg[s] * x[static_cast<size_t>(s)];
    }
    const double dense = var > 0 ? std::sqrt(var) : -1;
    std::printf("sd98 schur %.8f dense %.8f\n", sd, dense);
    check(solved && std::abs(sd - dense) <= 1e-6 * std::max(1.0, std::abs(dense)), "sd98 matches dense solve");
}

static kalman::Problem batch_problem(const std::vector<double>& L, const std::vector<double>& F, double C0, int first) {
    kalman::Problem prob;
    prob.mode = 0;
    prob.C0.push_back(C0);
    prob.first.push_back(first);
    for (size_t i = 0; i < L.size(); i++) {
        prob.L.push_back(L[i]);
        prob.F.push_back(F[i]);
        prob.sd.push_back(0.8);
        prob.seg.push_back(0);
    }
    return prob;
}

static void test_two_batch() {
    std::vector<double> L1, F1, L2, F2;
    for (int i = 0; i < 12; i++) {
        const double f = 0.4 * i;
        F1.push_back(f);
        L1.push_back(70.0 - f / 0.36);
    }
    for (int i = 0; i < 8; i++) {
        const double f = 0.5 * i;
        F2.push_back(f);
        L2.push_back(40.0 - f / 0.36);
    }
    kalman::Problem b1 = batch_problem(L1, F1, 0.0, 1);
    kalman::FitResult full1 = kalman::fit(b1);
    check(full1.status == 0, "batch1 full fit");
    std::printf("batch1 stop %d iters %d g98 %.4f\n", full1.stop, full1.iters, full1.g98);

    kalman::Problem empty = b1;
    empty.mode = 1;
    empty.haveSaved = 1;
    empty.L.clear();
    empty.F.clear();
    empty.sd.clear();
    empty.seg.clear();
    empty.C0.clear();
    empty.first.clear();
    for (int i = 0; i < kalman::NF; i++) empty.qSaved[i] = full1.q[i];
    std::memcpy(empty.info, full1.info, sizeof(full1.info));
    kalman::FitResult held = kalman::fit(empty);
    check(held.status == 0, "empty incremental fit");
    double dq = 0;
    for (int i = 0; i < kalman::NF; i++) dq = std::max(dq, std::abs(held.q[i] - full1.q[i]));
    std::printf("empty second batch max |dq| %.3e\n", dq);
    check(dq < 1e-10, "second batch with no new rows leaves q put");

    kalman::Problem both;
    both.mode = 0;
    both.C0 = {0.0, 8.0};
    both.first = {1, 1};
    for (size_t i = 0; i < L1.size(); i++) {
        both.L.push_back(L1[i]);
        both.F.push_back(F1[i]);
        both.sd.push_back(0.8);
        both.seg.push_back(0);
    }
    for (size_t i = 0; i < L2.size(); i++) {
        both.L.push_back(L2[i]);
        both.F.push_back(F2[i]);
        both.sd.push_back(0.8);
        both.seg.push_back(1);
    }
    kalman::FitResult full_both = kalman::fit(both);
    check(full_both.status == 0, "two-batch full fit");

    kalman::Problem inc;
    inc.mode = 1;
    inc.haveSaved = 1;
    inc.C0 = {8.0};
    inc.first = {1};
    inc.L = L2;
    inc.F = F2;
    inc.sd.assign(L2.size(), 0.8);
    inc.seg.assign(L2.size(), 0);
    for (int i = 0; i < kalman::NF; i++) inc.qSaved[i] = full1.q[i];
    std::memcpy(inc.info, full1.info, sizeof(full1.info));
    kalman::FitResult inc_fit = kalman::fit(inc);
    check(inc_fit.status == 0, "two-batch incremental fit");
    double dq2 = 0;
    for (int i = 0; i < kalman::NF; i++) dq2 = std::max(dq2, std::abs(inc_fit.q[i] - full_both.q[i]));
    std::printf("full vs inc max |dq| %.6f g98 full %.4f inc %.4f\n", dq2, full_both.g98, inc_fit.g98);
    check(std::abs(full_both.g98 - inc_fit.g98) < 0.5, "full fit against incremental posterior on G(98)");
    check(dq2 < 0.05, "full fit against incremental posterior on q");
}

static double smooth_cost(const double* q) {
    kalman::Problem prob;
    prob.mode = 0;
    std::vector<double> p(kalman::NF, 0.0);
    for (int i = 0; i < kalman::NF; i++) p[static_cast<size_t>(i)] = q[i];
    std::vector<double> r;
    kalman::eval(prob, p.data(), &r, nullptr);
    // full, no observations: prior NF rows then NB-2 smoothness rows.
    double s = 0;
    for (int i = kalman::NF; i < kalman::NF + (kalman::NB - 2); i++) {
        const double v = r[static_cast<size_t>(i)];
        s += v * v;
    }
    return s;
}

static void test_no_second_smooth() {
    kalman::Problem inc;
    inc.mode = 1;
    inc.haveSaved = 1;
    for (int i = 0; i < kalman::NF; i++) {
        inc.qSaved[i] = std::log(0.3) + ((i % 5 == 0) ? 0.15 : 0.0);
        for (int c = 0; c <= i; c++) inc.info[i * (i + 1) / 2 + c] = (i == c) ? 2.5 : 0.0;
    }
    std::vector<double> p(kalman::NF);
    for (int i = 0; i < kalman::NF; i++) p[static_cast<size_t>(i)] = inc.qSaved[i];
    std::vector<double> r0;
    kalman::eval(inc, p.data(), &r0, nullptr);
    check(static_cast<int>(r0.size()) == kalman::NF, "incremental residual with no new rows is the information block only");
    double z = 0;
    for (double v : r0) z += v * v;
    check(z < 1e-18, "incremental residual at the saved q is zero");
    const double smooth = smooth_cost(inc.qSaved);
    std::printf("smoothness cost at saved q %.6f\n", smooth);
    check(smooth > 1e-4, "saved q is not a smoothness mode");

    p[20] += 0.2;
    std::vector<double> r1;
    kalman::eval(inc, p.data(), &r1, nullptr);
    double cost = 0;
    for (double v : r1) cost += v * v;
    // Identity-scaled information 2.5 on the diagonal: Mahalanobis cost is 2.5 * dq^2.
    const double maha = 2.5 * 0.2 * 0.2;
    std::printf("inc cost %.8f mahalanobis %.8f\n", cost, maha);
    check(std::abs(cost - maha) < 1e-8, "incremental residual has no second smoothness prior");
    check(cost + 1e-6 < maha + smooth, "smoothness is not added on top of the information");
}

int main() {
    test_jacobian();
    test_sd98();
    test_two_batch();
    test_no_second_smooth();
    if (g_fails) {
        std::fprintf(stderr, "%d checks failed\n", g_fails);
        return 1;
    }
    std::printf("all checks passed\n");
    return 0;
}
