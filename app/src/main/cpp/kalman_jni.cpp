#include "kalman_core.h"

#include <jni.h>

#include <vector>

namespace {

struct DPin {
    JNIEnv* env;
    jdoubleArray arr;
    jdouble* p;
    jint mode;
    DPin(JNIEnv* e, jdoubleArray a, jint release_mode) : env(e), arr(a), p(nullptr), mode(release_mode) {
        if (a) p = env->GetDoubleArrayElements(a, nullptr);
    }
    ~DPin() {
        if (p) env->ReleaseDoubleArrayElements(arr, p, mode);
    }
    DPin(const DPin&) = delete;
    DPin& operator=(const DPin&) = delete;
};

struct IPin {
    JNIEnv* env;
    jintArray arr;
    jint* p;
    IPin(JNIEnv* e, jintArray a) : env(e), arr(a), p(nullptr) {
        if (a) p = env->GetIntArrayElements(a, nullptr);
    }
    ~IPin() {
        if (p) env->ReleaseIntArrayElements(arr, p, JNI_ABORT);
    }
    IPin(const IPin&) = delete;
    IPin& operator=(const IPin&) = delete;
};

} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_davidlang_torque_fuelsmoothing_KalmanNative_infoPd(JNIEnv* env, jclass, jdoubleArray info) {
    if (!info || env->GetArrayLength(info) != kalman::INFO_N) return JNI_FALSE;
    DPin pin(env, info, JNI_ABORT);
    if (!pin.p) return JNI_FALSE;
    return kalman::info_pd(pin.p) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_davidlang_torque_fuelsmoothing_KalmanNative_fit(
    JNIEnv* env, jclass, jint mode,
    jdoubleArray L, jdoubleArray F, jdoubleArray sd, jintArray seg,
    jdoubleArray C0, jintArray first,
    jint haveSaved, jdoubleArray qSaved, jdoubleArray info,
    jdouble anchorK, jdouble anchorC0,
    jintArray matched, jdoubleArray zSaved, jdoubleArray oSaved,
    jdoubleArray qOut, jdoubleArray zOut, jdoubleArray oOut,
    jdoubleArray gOut, jdoubleArray infoOut, jdoubleArray stats) {
    if (!L || !F || !sd || !seg || !C0 || !first || !qOut || !zOut || !oOut || !gOut || !infoOut || !stats) return 2;
    const jint nobs = env->GetArrayLength(L);
    const jint ns = env->GetArrayLength(C0);
    if (env->GetArrayLength(F) != nobs || env->GetArrayLength(sd) != nobs || env->GetArrayLength(seg) != nobs) return 2;
    if (env->GetArrayLength(first) != ns || env->GetArrayLength(zOut) != ns || env->GetArrayLength(oOut) != ns) return 2;
    if (env->GetArrayLength(qOut) != kalman::NF || env->GetArrayLength(gOut) != kalman::NB) return 2;
    if (env->GetArrayLength(infoOut) != kalman::INFO_N || env->GetArrayLength(stats) != 5) return 2;

    DPin pL(env, L, JNI_ABORT), pF(env, F, JNI_ABORT), pSd(env, sd, JNI_ABORT);
    IPin pSeg(env, seg), pFirst(env, first);
    DPin pC0(env, C0, JNI_ABORT);
    DPin pQ(env, qSaved, JNI_ABORT), pInfo(env, info, JNI_ABORT);
    IPin pMatch(env, matched);
    DPin pZ(env, zSaved, JNI_ABORT), pO(env, oSaved, JNI_ABORT);
    DPin oQ(env, qOut, 0), oZ(env, zOut, 0), oO(env, oOut, 0), oG(env, gOut, 0), oInfo(env, infoOut, 0), oSt(env, stats, 0);
    if (!pL.p || !pF.p || !pSd.p || !pSeg.p || !pC0.p || !pFirst.p || !oQ.p || !oZ.p || !oO.p || !oG.p || !oInfo.p || !oSt.p) return 2;

    kalman::Problem prob;
    prob.mode = mode;
    prob.L.assign(pL.p, pL.p + nobs);
    prob.F.assign(pF.p, pF.p + nobs);
    prob.sd.assign(pSd.p, pSd.p + nobs);
    prob.seg.assign(pSeg.p, pSeg.p + nobs);
    prob.C0.assign(pC0.p, pC0.p + ns);
    prob.first.assign(pFirst.p, pFirst.p + ns);
    prob.haveSaved = haveSaved;
    prob.anchorK = anchorK;
    prob.anchorC0 = anchorC0;
    if (haveSaved && pQ.p && env->GetArrayLength(qSaved) == kalman::NF) {
        for (int i = 0; i < kalman::NF; i++) prob.qSaved[i] = pQ.p[i];
    }
    if (pInfo.p && env->GetArrayLength(info) == kalman::INFO_N) {
        for (int i = 0; i < kalman::INFO_N; i++) prob.info[i] = pInfo.p[i];
    }
    if (pMatch.p && matched && env->GetArrayLength(matched) == ns) prob.matched.assign(pMatch.p, pMatch.p + ns);
    if (pZ.p && zSaved && env->GetArrayLength(zSaved) == ns) prob.zSaved.assign(pZ.p, pZ.p + ns);
    if (pO.p && oSaved && env->GetArrayLength(oSaved) == ns) prob.oSaved.assign(pO.p, pO.p + ns);

    const kalman::FitResult fit = kalman::fit(prob);
    if (fit.status != 0) return fit.status;
    for (int i = 0; i < kalman::NF; i++) oQ.p[i] = fit.q[i];
    for (int j = 0; j < ns; j++) {
        oZ.p[j] = fit.z[static_cast<size_t>(j)];
        oO.p[j] = fit.o[static_cast<size_t>(j)];
    }
    for (int i = 0; i < kalman::NB; i++) oG.p[i] = fit.g[static_cast<size_t>(i)];
    for (int i = 0; i < kalman::INFO_N; i++) oInfo.p[i] = fit.info[i];
    oSt.p[0] = fit.g98;
    oSt.p[1] = fit.sd98;
    oSt.p[2] = fit.kTail;
    oSt.p[3] = static_cast<double>(fit.iters);
    oSt.p[4] = static_cast<double>(fit.stop);
    return 0;
}
