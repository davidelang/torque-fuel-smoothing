package com.davidlang.torque.fuelsmoothing

/** JNI entry for libkalmanfit. Loaded from [Kalman.update], not from a static initializer. */
internal class KalmanNative {
    companion object {
        @JvmStatic
        external fun infoPd(info: DoubleArray): Boolean

        @JvmStatic
        external fun fit(
            mode: Int,
            L: DoubleArray,
            F: DoubleArray,
            sd: DoubleArray,
            seg: IntArray,
            C0: DoubleArray,
            first: IntArray,
            haveSaved: Int,
            qSaved: DoubleArray,
            info: DoubleArray,
            anchorK: Double,
            anchorC0: Double,
            matched: IntArray,
            zSaved: DoubleArray,
            oSaved: DoubleArray,
            qOut: DoubleArray,
            zOut: DoubleArray,
            oOut: DoubleArray,
            gOut: DoubleArray,
            infoOut: DoubleArray,
            stats: DoubleArray,
        ): Int
    }
}
