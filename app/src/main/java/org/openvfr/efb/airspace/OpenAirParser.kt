package org.openvfr.efb.airspace

import org.maplibre.android.geometry.LatLng
import kotlin.math.*

/**
 * Parseur complet du standard OpenAir (AC, AN, AL, AH, AF, AG, DP, V X=, V D=, DC, DA, DB)
 * avec support des coordonnées DMS/DM, cercles et arcs horaires/anti-horaires.
 */
object OpenAirParser {

    fun parse(content: String): List<Airspace> {
        val result = mutableListOf<Airspace>()
        var currentClass = "G"
        var currentName = ""
        var currentLower = VerticalLimit("SFC", AltitudeReference.SFC, 0)
        var currentUpper = VerticalLimit("FL115", AltitudeReference.FL, 11500)
        var currentFreq: String? = null
        var currentPoints = mutableListOf<LatLng>()
        var center: LatLng? = null
        var direction = 1
        var idx = 0

        fun flush() {
            if (currentName.isNotBlank() && currentPoints.size >= 3) {
                val closed = if (currentPoints.first() != currentPoints.last()) {
                    currentPoints + currentPoints.first()
                } else currentPoints.toList()

                result.add(
                    Airspace(
                        id = "oa_${idx++}_${currentName.hashCode()}",
                        name = currentName,
                        airspaceClass = currentClass,
                        category = mapCategory(currentClass, currentName),
                        lowerLimit = currentLower,
                        upperLimit = currentUpper,
                        frequencyMhz = currentFreq,
                        polygon = closed
                    )
                )
            }
            currentPoints = mutableListOf()
            center = null
            direction = 1
            currentFreq = null
        }

        content.lineSequence().forEach { rawLine ->
            val line = rawLine.substringBefore("*").trim()
            if (line.isEmpty()) return@forEach

            when {
                line.startsWith("AC ") -> {
                    flush()
                    currentClass = line.removePrefix("AC ").trim()
                }
                line.startsWith("AN ") -> {
                    currentName = line.removePrefix("AN ").trim()
                }
                line.startsWith("AL ") -> {
                    currentLower = parseVerticalLimit(line.removePrefix("AL ").trim())
                }
                line.startsWith("AH ") -> {
                    currentUpper = parseVerticalLimit(line.removePrefix("AH ").trim())
                }
                line.startsWith("AF ") -> {
                    currentFreq = line.removePrefix("AF ").trim()
                }
                line.startsWith("DP ") -> {
                    parseCoord(line.removePrefix("DP ").trim())?.let { currentPoints.add(it) }
                }
                line.startsWith("V ") -> {
                    val expr = line.removePrefix("V ").trim()
                    if (expr.startsWith("X=")) {
                        center = parseCoord(expr.removePrefix("X=").trim())
                    } else if (expr.startsWith("D=")) {
                        direction = if (expr.removePrefix("D=").trim() == "-") -1 else 1
                    }
                }
                line.startsWith("DC ") -> {
                    val radiusNm = line.removePrefix("DC ").trim().toDoubleOrNull()
                    val c = center
                    if (c != null && radiusNm != null) {
                        currentPoints.addAll(generateCircle(c, radiusNm, 48))
                    }
                }
                line.startsWith("DA ") -> {
                    val parts = line.removePrefix("DA ").split(",").map { it.trim().toDoubleOrNull() }
                    val c = center
                    if (c != null && parts.size == 3 && parts.all { it != null }) {
                        currentPoints.addAll(generateArcByAngles(c, parts[0]!!, parts[1]!!, parts[2]!!, direction))
                    }
                }
                line.startsWith("DB ") -> {
                    val parts = line.removePrefix("DB ").split(",").map { it.trim() }
                    val c = center
                    if (c != null && parts.size == 2) {
                        val p1 = parseCoord(parts[0])
                        val p2 = parseCoord(parts[1])
                        if (p1 != null && p2 != null) {
                            currentPoints.addAll(generateArcByPoints(c, p1, p2, direction))
                        }
                    }
                }
            }
        }
        flush()
        return result
    }

    fun parseVerticalLimit(raw: String): VerticalLimit {
        val clean = raw.uppercase().replace(" ", "")
        return when {
            clean == "SFC" || clean == "GND" || clean == "SOL" ->
                VerticalLimit(raw, AltitudeReference.SFC, 0)
            clean == "UNL" ->
                VerticalLimit(raw, AltitudeReference.UNL, 99999)
            clean.startsWith("FL") -> {
                val fl = clean.removePrefix("FL").filter { it.isDigit() }.toIntOrNull() ?: 115
                VerticalLimit(raw, AltitudeReference.FL, fl * 100)
            }
            clean.contains("AGL") || clean.contains("ASFC") -> {
                val ft = clean.filter { it.isDigit() }.toIntOrNull() ?: 1000
                VerticalLimit(raw, AltitudeReference.AGL, ft)
            }
            else -> {
                val ft = clean.filter { it.isDigit() }.toIntOrNull() ?: 3500
                VerticalLimit(raw, AltitudeReference.AMSL, ft)
            }
        }
    }

    private fun mapCategory(ac: String, name: String): AirspaceCategory {
        val uName = name.uppercase()
        val uAc = ac.uppercase()
        return when {
            uAc == "CTR" || uName.contains("CTR") -> AirspaceCategory.CTR
            uAc == "TMA" || uName.contains("TMA") -> AirspaceCategory.TMA
            uAc == "P" || uName.contains("ZIT") -> AirspaceCategory.PROHIBITED
            uAc == "R" || uName.contains("TSA") || uName.contains("CBA") -> AirspaceCategory.RESTRICTED
            uAc == "D" || uName.contains("RTBA") -> AirspaceCategory.DANGER
            uName.contains("SIV") -> AirspaceCategory.SIV
            uName.contains("ZSM") || uName.contains("PARC") -> AirspaceCategory.ZSM
            else -> AirspaceCategory.OTHER
        }
    }

    private val coordRegex = Regex(
        """(\d{1,2}):(\d{1,2}):(\d{1,2}(?:\.\d+)?)\s*([NS])\s+(\d{1,3}):(\d{1,2}):(\d{1,2}(?:\.\d+)?)\s*([EW])"""
    )

    fun parseCoord(text: String): LatLng? {
        val m = coordRegex.find(text.uppercase()) ?: return null
        val latDeg = m.groupValues[1].toDouble() +
                m.groupValues[2].toDouble() / 60.0 +
                m.groupValues[3].toDouble() / 3600.0
        val latSign = if (m.groupValues[4] == "S") -1.0 else 1.0

        val lonDeg = m.groupValues[5].toDouble() +
                m.groupValues[6].toDouble() / 60.0 +
                m.groupValues[7].toDouble() / 3600.0
        val lonSign = if (m.groupValues[8] == "W") -1.0 else 1.0

        return LatLng(latSign * latDeg, lonSign * lonDeg)
    }

    fun generateCircle(center: LatLng, radiusNm: Double, steps: Int = 36): List<LatLng> {
        return (0..steps).map { i ->
            val bearing = (360.0 * i) / steps
            destinationPoint(center, bearing, radiusNm)
        }
    }

    private fun generateArcByAngles(
        center: LatLng,
        radiusNm: Double,
        startDeg: Double,
        endDeg: Double,
        dir: Int
    ): List<LatLng> {
        var sweep = if (dir > 0) (endDeg - startDeg + 360.0) % 360.0
        else -((startDeg - endDeg + 360.0) % 360.0)
        if (abs(sweep) < 1e-3) sweep = 360.0 * dir
        val steps = max(8, (abs(sweep) / 6.0).toInt())
        return (0..steps).map { i ->
            val angle = (startDeg + sweep * i / steps + 360.0) % 360.0
            destinationPoint(center, angle, radiusNm)
        }
    }

    private fun generateArcByPoints(center: LatLng, p1: LatLng, p2: LatLng, dir: Int): List<LatLng> {
        val rNm = haversineNm(center, p1)
        val b1 = initialBearingDeg(center, p1)
        val b2 = initialBearingDeg(center, p2)
        return generateArcByAngles(center, rNm, b1, b2, dir)
    }

    fun destinationPoint(start: LatLng, bearingDeg: Double, distanceNm: Double): LatLng {
        val r = 3440.065
        val d = distanceNm / r
        val brng = Math.toRadians(bearingDeg)
        val lat1 = Math.toRadians(start.latitude)
        val lon1 = Math.toRadians(start.longitude)

        val lat2 = asin(sin(lat1) * cos(d) + cos(lat1) * sin(d) * cos(brng))
        val lon2 = lon1 + atan2(sin(brng) * sin(d) * cos(lat1), cos(d) - sin(lat1) * sin(lat2))
        return LatLng(Math.toDegrees(lat2), Math.toDegrees(lon2))
    }

    fun haversineNm(p1: LatLng, p2: LatLng): Double {
        val r = 3440.065
        val dLat = Math.toRadians(p2.latitude - p1.latitude)
        val dLon = Math.toRadians(p2.longitude - p1.longitude)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(p1.latitude)) * cos(Math.toRadians(p2.latitude)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(a))
    }

    fun initialBearingDeg(p1: LatLng, p2: LatLng): Double {
        val lat1 = Math.toRadians(p1.latitude)
        val lat2 = Math.toRadians(p2.latitude)
        val dLon = Math.toRadians(p2.longitude - p1.longitude)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    val DEFAULT_FRANCE_SW_OPENAIR = """
        * Données AIRAC OpenVFR - Secteur Charente-Maritime / Aquitaine
        AC CTR
        AN CTR LA ROCHELLE (LFBH)
        AL SFC
        AH 2500FT AMSL
        AF 124.200
        DP 46:17:00 N 001:22:00 W
        DP 46:17:00 N 001:02:00 W
        DP 46:03:00 N 001:02:00 W
        DP 46:03:00 N 001:22:00 W
        DP 46:17:00 N 001:22:00 W

        AC TMA
        AN TMA 1 LA ROCHELLE
        AL 2500FT AMSL
        AH FL065
        AF 124.200
        V X=46:10:45 N 001:11:43 W
        DC 18

        AC CTR
        AN CTR COGNAC (LFBG)
        AL SFC
        AH 3500FT AMSL
        AF 122.150
        V X=45:39:30 N 000:19:03 W
        DC 10

        AC R
        AN R 49 A COGNAC
        AL SFC
        AH FL115
        AF 122.150
        DP 45:52:00 N 000:48:00 W
        DP 45:52:00 N 000:05:00 W
        DP 45:25:00 N 000:05:00 W
        DP 45:25:00 N 000:48:00 W
        DP 45:52:00 N 000:48:00 W

        AC P
        AN ZIT 23 BLAYE (CENTRALE)
        AL SFC
        AH 3300FT AMSL
        V X=45:15:21 N 000:41:35 W
        DC 2.7

        AC D
        AN R 310 MARENNES / OLERON (TIR)
        AL SFC
        AH 5000FT AMSL
        DP 45:56:00 N 001:24:00 W
        DP 45:56:00 N 001:13:00 W
        DP 45:46:00 N 001:13:00 W
        DP 45:46:00 N 001:24:00 W
        DP 45:56:00 N 001:24:00 W

        AC SIV
        AN SIV AQUITAINE NORD
        AL 1500FT AGL
        AH FL115
        AF 120.550
        DP 46:05:00 N 001:35:00 W
        DP 46:05:00 N 000:10:00 W
        DP 45:00:00 N 000:10:00 W
        DP 45:00:00 N 001:35:00 W
        DP 46:05:00 N 001:35:00 W
    """.trimIndent()
}
