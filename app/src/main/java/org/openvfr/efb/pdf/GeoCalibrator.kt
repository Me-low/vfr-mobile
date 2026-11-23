package com.tonpackage.vfrmobile.geo

import kotlin.math.*

data class CalibPoint(
    val x: Double,      // Coordonnée X en pixels/points PDF
    val y: Double,      // Coordonnée Y en pixels/points PDF
    val lat: Double,    // Latitude WGS84 (degrés décimaux)
    val lon: Double,    // Longitude WGS84 (degrés décimaux)
    val errMeters: Double = 0.0
)

data class MapModel(
    val aRe: Double,
    val aIm: Double,
    val bRe: Double,
    val bIm: Double,
    val points: List<CalibPoint>,
    val rmsMeters: Double,
    val maxMeters: Double,
    val ptPerKm: Double,
    val rotationDeg: Double
) {
    companion object {
        const val WGS_A = 6378137.0
        const val WGS_E = 0.0818191908426
        const val LCC_PHI_REF = 46.5
        const val LCC_N = 0.7256

        private fun lccT(phiRad: Double): Double {
            val s = sin(phiRad)
            return tan(PI / 4.0 - phiRad / 2.0) /
                ((1.0 - WGS_E * s) / (1.0 + WGS_E * s)).pow(WGS_E / 2.0)
        }

        private fun lccRho0(): Double {
            val p0 = Math.toRadians(LCC_PHI_REF)
            val m0 = cos(p0) / sqrt(1.0 - (WGS_E * sin(p0)).pow(2))
            return WGS_A * m0 / LCC_N
        }

        fun lccXY(lat: Double, lon: Double): Pair<Double, Double> {
            val p0 = Math.toRadians(LCC_PHI_REF)
            val rho0 = lccRho0()
            val rho = rho0 * (lccT(Math.toRadians(lat)) / lccT(p0)).pow(LCC_N)
            val th = LCC_N * Math.toRadians(lon)
            return Pair(rho * sin(th), rho0 - rho * cos(th))
        }

        /** Résout w = a*z + b aux moindres carrés (exactement comme build_map_model dans maptiles.py) */
        fun solve(rawPoints: List<CalibPoint>): MapModel {
            require(rawPoints.size >= 2) { "Il faut au moins 2 points de calage." }

            val zs = rawPoints.map { p ->
                val (xLcc, yLcc) = lccXY(p.lat, p.lon)
                Pair(xLcc, -yLcc) // z = X - i*Y
            }
            val ws = rawPoints.map { Pair(it.x, it.y) }

            val zmRe = zs.map { it.first }.average()
            val zmIm = zs.map { it.second }.average()
            val wmRe = ws.map { it.first }.average()
            val wmIm = ws.map { it.second }.average()

            var den = 0.0
            var numRe = 0.0
            var numIm = 0.0

            for (i in zs.indices) {
                val dzRe = zs[i].first - zmRe
                val dzIm = zs[i].second - zmIm
                val dwRe = ws[i].first - wmRe
                val dwIm = ws[i].second - wmIm

                den += dzRe * dzRe + dzIm * dzIm
                // conj(dz) * dw = (dzRe - i*dzIm) * (dwRe + i*dwIm)
                numRe += dzRe * dwRe + dzIm * dwIm
                numIm += dzRe * dwIm - dzIm * dwRe
            }

            require(den >= 1e6) { "Les points de calage sont trop proches ou confondus." }

            val aRe = numRe / den
            val aIm = numIm / den
            val bRe = wmRe - (aRe * zmRe - aIm * zmIm)
            val bIm = wmIm - (aRe * zmIm + aIm * zmRe)
            val absA = hypot(aRe, aIm)

            val scoredPoints = rawPoints.mapIndexed { i, p ->
                val predX = (aRe * zs[i].first - aIm * zs[i].second) + bRe
                val predY = (aRe * zs[i].second + aIm * zs[i].first) + bIm
                val errM = hypot(ws[i].first - predX, ws[i].second - predY) / absA
                p.copy(errMeters = (errM * 10.0).roundToInt() / 10.0)
            }

            val rms = sqrt(scoredPoints.map { it.errMeters * it.errMeters }.average())
            val maxErr = scoredPoints.maxOf { it.errMeters }

            return MapModel(
                aRe = aRe,
                aIm = aIm,
                bRe = bRe,
                bIm = bIm,
                points = scoredPoints,
                rmsMeters = (rms * 10.0).roundToInt() / 10.0,
                maxMeters = (maxErr * 10.0).roundToInt() / 10.0,
                ptPerKm = absA * 1000.0,
                rotationDeg = Math.toDegrees(atan2(aIm, aRe))
            )
        }
    }

    /** Géographique (WGS84) -> Pixels PDF */
    fun geoToPdf(lat: Double, lon: Double): Pair<Double, Double> {
        val (u, negV) = lccXY(lat, lon)
        val x = aRe * u - aIm * negV + bRe
        val y = aRe * negV + aIm * u + bIm
        return Pair(x, y)
    }

    /** Pixels PDF -> Géographique (WGS84) */
    fun pdfToGeo(x: Double, y: Double): Pair<Double, Double> {
        val rho0 = lccRho0()
        val d = aRe * aRe + aIm * aIm
        val wr = x - bRe
        val wi = y - bIm
        val bigX = (wr * aRe + wi * aIm) / d
        val bigY = -((wi * aRe - wr * aIm) / d)
        val rho = hypot(bigX, rho0 - bigY)
        val th = atan2(bigX, rho0 - bigY)
        val t = lccT(Math.toRadians(LCC_PHI_REF)) * (rho / rho0).pow(1.0 / LCC_N)

        var phi = PI / 2.0 - 2.0 * atan(t)
        repeat(8) {
            val s = sin(phi)
            phi = PI / 2.0 - 2.0 * atan(t * ((1.0 - WGS_E * s) / (1.0 + WGS_E * s)).pow(WGS_E / 2.0))
        }
        return Pair(Math.toDegrees(phi), Math.toDegrees(th / LCC_N))
    }
}
