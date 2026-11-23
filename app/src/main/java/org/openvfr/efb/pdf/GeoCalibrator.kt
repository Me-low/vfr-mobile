package org.openvfr.efb.pdf

import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngQuad
import kotlin.math.*

data class ControlPoint(
    val pdfX: Double, // Coordonnée normalisée [0.0 .. 1.0] sur la page PDF
    val pdfY: Double,
    val lat: Double,
    val lon: Double
)

data class CalibrationResult(
    val quad: LatLngQuad,
    val rmseMeters: Double,
    val modelName: String
)

/**
 * Solveur géodésique multi-modèles pour la calibration de cartes PDF (VAC / OACI) :
 * - 2 points : Similitude (translation, rotation, échelle uniforme)
 * - 3 points : Transformation affine complète par moindres carrés
 * - >= 4 points : Régression polynomiale bilinéaire pour absorber la projection Lambert
 */
object GeoCalibrator {

    fun calibrate(points: List<ControlPoint>): CalibrationResult? {
        if (points.size < 2) return null

        val refLat = points.map { it.lat }.average()
        val cosLat = cos(Math.toRadians(refLat)).coerceAtLeast(0.1)

        fun toLocalX(lon: Double) = lon * cosLat
        fun toLocalY(lat: Double) = lat
        fun toLon(lx: Double) = lx / cosLat
        fun toLat(ly: Double) = ly

        val modelName: String
        val transform: (Double, Double) -> LatLng = when {
            points.size == 2 -> {
                modelName = "Similitude (2 pts)"
                val p1 = points[0]; val p2 = points[1]
                val dx = p2.pdfX - p1.pdfX
                val dy = p2.pdfY - p1.pdfY
                val dX = toLocalX(p2.lon) - toLocalX(p1.lon)
                val dY = toLocalY(p2.lat) - toLocalY(p1.lat)

                val denom = dx * dx + dy * dy
                if (denom < 1e-12) return null

                val a = (dx * dX + dy * dY) / denom
                val b = (dx * dY - dy * dX) / denom
                val tx = toLocalX(p1.lon) - (a * p1.pdfX - b * p1.pdfY)
                val ty = toLocalY(p1.lat) - (b * p1.pdfX + a * p1.pdfY)

                { u, v ->
                    val lx = a * u - b * v + tx
                    val ly = b * u + a * v + ty
                    LatLng(toLat(ly), toLon(lx))
                }
            }
            points.size == 3 -> {
                modelName = "Affine (3 pts)"
                val coeffsLon = solveLinearLeastSquares(
                    points.map { doubleArrayOf(it.pdfX, it.pdfY, 1.0) },
                    points.map { toLocalX(it.lon) },
                    3
                ) ?: return null

                val coeffsLat = solveLinearLeastSquares(
                    points.map { doubleArrayOf(it.pdfX, it.pdfY, 1.0) },
                    points.map { toLocalY(it.lat) },
                    3
                ) ?: return null

                { u, v ->
                    val lx = coeffsLon[0] * u + coeffsLon[1] * v + coeffsLon[2]
                    val ly = coeffsLat[0] * u + coeffsLat[1] * v + coeffsLat[2]
                    LatLng(toLat(ly), toLon(lx))
                }
            }
            else -> {
                modelName = "Bilinéaire Lambert (${points.size} pts)"
                val coeffsLon = solveLinearLeastSquares(
                    points.map { doubleArrayOf(it.pdfX, it.pdfY, it.pdfX * it.pdfY, 1.0) },
                    points.map { toLocalX(it.lon) },
                    4
                ) ?: return null

                val coeffsLat = solveLinearLeastSquares(
                    points.map { doubleArrayOf(it.pdfX, it.pdfY, it.pdfX * it.pdfY, 1.0) },
                    points.map { toLocalY(it.lat) },
                    4
                ) ?: return null

                { u, v ->
                    val lx = coeffsLon[0] * u + coeffsLon[1] * v + coeffsLon[2] * (u * v) + coeffsLon[3]
                    val ly = coeffsLat[0] * u + coeffsLat[1] * v + coeffsLat[2] * (u * v) + coeffsLat[3]
                    LatLng(toLat(ly), toLon(lx))
                }
            }
        }

        val sqErrors = points.map { pt ->
            val predicted = transform(pt.pdfX, pt.pdfY)
            val d = haversineMeters(pt.lat, pt.lon, predicted.latitude, predicted.longitude)
            d * d
        }
        val rmse = sqrt(sqErrors.average())

        val quad = LatLngQuad(
            transform(0.0, 0.0),
            transform(1.0, 0.0),
            transform(1.0, 1.0),
            transform(0.0, 1.0)
        )
        return CalibrationResult(quad = quad, rmseMeters = rmse, modelName = modelName)
    }

    private fun solveLinearLeastSquares(rows: List<DoubleArray>, target: List<Double>, dim: Int): DoubleArray? {
        val ata = Array(dim) { DoubleArray(dim) }
        val atb = DoubleArray(dim)
        for (i in rows.indices) {
            val r = rows[i]
            val y = target[i]
            for (row in 0 until dim) {
                atb[row] += r[row] * y
                for (col in 0 until dim) {
                    ata[row][col] += r[row] * r[col]
                }
            }
        }
        // Régularisation légère Tikhonov pour stabilité numérique
        for (d in 0 until dim) ata[d][d] += 1e-10

        for (i in 0 until dim) {
            var maxRow = i
            for (k in i + 1 until dim) if (abs(ata[k][i]) > abs(ata[maxRow][i])) maxRow = k
            val tmp = ata[i]; ata[i] = ata[maxRow]; ata[maxRow] = tmp
            val tmpB = atb[i]; atb[i] = atb[maxRow]; atb[maxRow] = tmpB

            if (abs(ata[i][i]) < 1e-12) return null
            for (k in i + 1 until dim) {
                val factor = ata[k][i] / ata[i][i]
                atb[k] -= factor * atb[i]
                for (j in i until dim) ata[k][j] -= factor * ata[i][j]
            }
        }
        val res = DoubleArray(dim)
        for (i in dim - 1 downTo 0) {
            var sum = atb[i]
            for (j in i + 1 until dim) sum -= ata[i][j] * res[j]
            res[i] = sum / ata[i][i]
        }
        return res
    }

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(a))
    }
}
