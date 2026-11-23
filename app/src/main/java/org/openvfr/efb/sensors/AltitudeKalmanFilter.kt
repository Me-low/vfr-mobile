package org.openvfr.efb.sensors

import kotlin.math.max

/**
 * Filtre de Kalman à 3 états pour la fusion haute fréquence Baromètre + GPS :
 * x = [ z (altitude réelle AMSL en m), vz (vario en m/s), b_baro (biais baro/QNH en m) ]
 */
class AltitudeKalmanFilter {

    private val x = DoubleArray(3)
    private val p = DoubleArray(9)
    private var initialized = false
    private var lastTimestampNs = 0L

    private val qAltitude = 0.05
    private val qVerticalSpeed = 0.85
    private val qBaroBias = 0.002

    fun reset() {
        initialized = false
        lastTimestampNs = 0L
    }

    fun applyManualQnhOffset(qnhAltitudeMeters: Double, currentBaroStdMeters: Double) {
        if (!initialized) return
        x[2] = currentBaroStdMeters - qnhAltitudeMeters
        x[0] = qnhAltitudeMeters
        p[8] = 1.0
    }

    fun predict(timestampNs: Long) {
        if (!initialized || lastTimestampNs == 0L) {
            lastTimestampNs = timestampNs
            return
        }
        val dt = (timestampNs - lastTimestampNs) * 1e-9
        lastTimestampNs = timestampNs
        if (dt <= 0.0 || dt > 5.0) return

        x[0] += x[1] * dt

        val p00 = p[0]; val p01 = p[1]; val p02 = p[2]
        val p10 = p[3]; val p11 = p[4]; val p12 = p[5]
        val p20 = p[6]; val p21 = p[7]; val p22 = p[8]

        val fp00 = p00 + dt * p10
        val fp01 = p01 + dt * p11
        val fp02 = p02 + dt * p12

        p[0] = fp00 + dt * fp01 + qAltitude * dt
        p[1] = fp01
        p[2] = fp02

        p[3] = p10 + dt * p11
        p[4] = p11 + qVerticalSpeed * dt
        p[5] = p12

        p[6] = p20 + dt * p21
        p[7] = p21
        p[8] = p22 + qBaroBias * dt
    }

    fun updateBarometer(baroAltitudeMeters: Double, timestampNs: Long) {
        if (!initialized) {
            x[0] = baroAltitudeMeters
            x[1] = 0.0
            x[2] = 0.0
            p[0] = 25.0; p[4] = 4.0; p[8] = 100.0
            initialized = true
            lastTimestampNs = timestampNs
            return
        }
        predict(timestampNs)
        val innovation = baroAltitudeMeters - (x[0] + x[2])
        scalarUpdate(h0 = 1.0, h1 = 0.0, h2 = 1.0, innovation = innovation, r = 0.25)
    }

    fun updateGps(gpsAmslMeters: Double, verticalAccuracyMeters: Float, timestampNs: Long) {
        if (!initialized) {
            x[0] = gpsAmslMeters
            x[1] = 0.0
            x[2] = 0.0
            p[0] = 25.0; p[4] = 4.0; p[8] = 100.0
            initialized = true
            lastTimestampNs = timestampNs
            return
        }
        predict(timestampNs)
        val sigma = max(verticalAccuracyMeters.toDouble(), 5.0)
        val innovation = gpsAmslMeters - x[0]
        scalarUpdate(h0 = 1.0, h1 = 0.0, h2 = 0.0, innovation = innovation, r = sigma * sigma)
    }

    private fun scalarUpdate(h0: Double, h1: Double, h2: Double, innovation: Double, r: Double) {
        val pht0 = p[0] * h0 + p[1] * h1 + p[2] * h2
        val pht1 = p[3] * h0 + p[4] * h1 + p[5] * h2
        val pht2 = p[6] * h0 + p[7] * h1 + p[8] * h2

        val s = h0 * pht0 + h1 * pht1 + h2 * pht2 + r
        if (s <= 1e-9) return

        val k0 = pht0 / s
        val k1 = pht1 / s
        val k2 = pht2 / s

        x[0] += k0 * innovation
        x[1] += k1 * innovation
        x[2] += k2 * innovation

        val a00 = 1.0 - k0 * h0; val a01 = -k0 * h1;       val a02 = -k0 * h2
        val a10 = -k1 * h0;       val a11 = 1.0 - k1 * h1; val a12 = -k1 * h2
        val a20 = -k2 * h0;       val a21 = -k2 * h1;       val a22 = 1.0 - k2 * h2

        val ap = DoubleArray(9)
        val a = doubleArrayOf(a00, a01, a02, a10, a11, a12, a20, a21, a22)
        for (row in 0..2) {
            for (col in 0..2) {
                ap[row * 3 + col] =
                    a[row * 3] * p[col] +
                    a[row * 3 + 1] * p[3 + col] +
                    a[row * 3 + 2] * p[6 + col]
            }
        }
        val k = doubleArrayOf(k0, k1, k2)
        for (row in 0..2) {
            for (col in 0..2) {
                p[row * 3 + col] =
                    ap[row * 3] * a[col * 3] +
                    ap[row * 3 + 1] * a[col * 3 + 1] +
                    ap[row * 3 + 2] * a[col * 3 + 2] +
                    k[row] * r * k[col]
            }
        }
    }

    val altitudeFeet: Int get() = (x[0] * 3.28084).toInt()
    val verticalSpeedFpm: Int get() = (x[1] * 196.8504).toInt()
    val isReady: Boolean get() = initialized
}
