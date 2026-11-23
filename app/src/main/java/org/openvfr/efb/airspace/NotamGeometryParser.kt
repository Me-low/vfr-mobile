package org.openvfr.efb.airspace

import org.maplibre.android.geometry.LatLng

/**
 * Parseur géométrique automatique de NOTAM et SUP AIP :
 * Extrait les coordonnées OACI (DDMMN/S DDDMME/W ou DDMMSSN/S DDDMMSSE/W),
 * les rayons (RADIUS X NM) et les plafonds (SFC / FLxxx / xxxxFT AMSL)
 * pour tracer instantanément les zones temporaires sur la carte.
 */
object NotamGeometryParser {

    private val dmsRegex = Regex("""(\d{2})(\d{2})(\d{2})([NS])\s*(\d{3})(\d{2})(\d{2})([EW])""")
    private val dmRegex = Regex("""(\d{2})(\d{2})([NS])\s*(\d{3})(\d{2})([EW])""")
    private val radiusRegex = Regex("""RADIUS\s+(\d+(?:\.\d+)?)\s*NM""")
    private val upperFlRegex = Regex("""(FL\s*\d{2,3})""")
    private val upperFtRegex = Regex("""(\d{3,5})\s*FT\s*(?:AMSL|AGL)""")

    fun parseBulletinToAirspaces(rawNotamText: String): List<Airspace> {
        val blocks = rawNotamText.split(Regex("""\n\s*\n|(?=Q\))""")).filter { it.isNotBlank() }
        val results = mutableListOf<Airspace>()

        blocks.forEachIndexed { index, block ->
            val upper = block.uppercase()
            val coords = extractAllCoordinates(upper)
            if (coords.isEmpty()) return@forEachIndexed

            val radiusMatch = radiusRegex.find(upper)
            val polygon = if (coords.size == 1) {
                val rNm = radiusMatch?.groupValues?.get(1)?.toDoubleOrNull() ?: 3.0
                OpenAirParser.generateCircle(coords.first(), rNm, 36)
            } else {
                if (coords.first() != coords.last()) coords + coords.first() else coords
            }

            val lower = VerticalLimit("SFC", AltitudeReference.SFC, 0)

            val upperLimit = when {
                upperFlRegex.containsMatchIn(upper) -> {
                    val flStr = upperFlRegex.find(upper)!!.groupValues[1]
                    OpenAirParser.parseVerticalLimit(flStr)
                }
                upperFtRegex.containsMatchIn(upper) -> {
                    val ftStr = upperFtRegex.find(upper)!!.groupValues[0]
                    OpenAirParser.parseVerticalLimit(ftStr)
                }
                else -> VerticalLimit("FL065", AltitudeReference.FL, 6500)
            }

            val titleLine = block.lines().firstOrNull { it.trim().isNotEmpty() }?.take(42) ?: "NOTAM #${index + 1}"
            results.add(
                Airspace(
                    id = "notam_${index}_${titleLine.hashCode()}",
                    name = "NOTAM: $titleLine",
                    airspaceClass = "NOTAM",
                    category = AirspaceCategory.NOTAM,
                    lowerLimit = lower,
                    upperLimit = upperLimit,
                    scheduleInfo = block.trim(),
                    polygon = polygon
                )
            )
        }
        return results
    }

    private fun extractAllCoordinates(text: String): List<LatLng> {
        val dmsMatches = dmsRegex.findAll(text).toList()
        if (dmsMatches.isNotEmpty()) {
            return dmsMatches.map { m ->
                val lat = m.groupValues[1].toDouble() + m.groupValues[2].toDouble() / 60.0 + m.groupValues[3].toDouble() / 3600.0
                val lon = m.groupValues[5].toDouble() + m.groupValues[6].toDouble() / 60.0 + m.groupValues[7].toDouble() / 3600.0
                LatLng(
                    if (m.groupValues[4] == "S") -lat else lat,
                    if (m.groupValues[8] == "W") -lon else lon
                )
            }
        }
        return dmRegex.findAll(text).map { m ->
            val lat = m.groupValues[1].toDouble() + m.groupValues[2].toDouble() / 60.0
            val lon = m.groupValues[4].toDouble() + m.groupValues[5].toDouble() / 60.0
            LatLng(
                if (m.groupValues[3] == "S") -lat else lat,
                if (m.groupValues[6] == "W") -lon else lon
            )
        }.toList()
    }

    val SAMPLE_NOTAM_TEXT = """
        A1428/26 VOLTIGE AERIENNE ST-TROJAN OLERON
        PSN 454830N 0011230W RADIUS 2.5 NM
        ACTIVITE VOLTIGE DE 0800 A 1700 UTC
        SFC - 4500FT AMSL

        B0891/26 PARACHUTAGE ROYAN MEDIS
        PSN 453740N 0005820W RADIUS 3.0 NM
        SFC - FL105
    """.trimIndent()
}
