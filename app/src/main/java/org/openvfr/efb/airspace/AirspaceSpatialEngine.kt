package org.openvfr.efb.airspace

import org.maplibre.android.geometry.LatLng

/**
 * Moteur géométrique spatial :
 * - Inspection de la pile verticale d'espaces aériens au toucher
 * - Filtrage par plafond
 * - Détection proactive d'intrusion à +2 minutes selon le vecteur vitesse
 */
object AirspaceSpatialEngine {

    fun isPointInPolygon(point: LatLng, polygon: List<LatLng>): Boolean {
        if (polygon.size < 3) return false
        var inside = false
        var j = polygon.size - 1
        for (i in polygon.indices) {
            val xi = polygon[i].longitude
            val yi = polygon[i].latitude
            val xj = polygon[j].longitude
            val yj = polygon[j].latitude

            val intersect = ((yi > point.latitude) != (yj > point.latitude)) &&
                    (point.longitude < (xj - xi) * (point.latitude - yi) / ((yj - yi) + 1e-12) + xi)
            if (intersect) inside = !inside
            j = i
        }
        return inside
    }

    fun inspectVerticalStack(
        point: LatLng,
        airspaces: List<Airspace>,
        ceilingFilterFt: Int = 19500
    ): List<Airspace> {
        return airspaces
            .filter { it.lowerLimit.valueFeet <= ceilingFilterFt }
            .filter { isPointInPolygon(point, it.polygon) }
            .sortedByDescending { it.lowerLimit.valueFeet }
    }

    fun checkProactiveAlerts(
        aircraftPos: LatLng,
        altitudeAmslFt: Int,
        terrainElevationFt: Int,
        groundSpeedKt: Int,
        trackDeg: Int,
        airspaces: List<Airspace>
    ): List<AirspaceAlert> {
        val alerts = mutableListOf<AirspaceAlert>()
        val lookaheadNm = (groundSpeedKt / 60.0) * 2.0
        val futurePos = if (groundSpeedKt > 20) {
            OpenAirParser.destinationPoint(aircraftPos, trackDeg.toDouble(), lookaheadNm)
        } else aircraftPos

        for (space in airspaces) {
            if (space.category == AirspaceCategory.SIV) continue
            val low = space.lowerLimit.toEffectiveAmslFeet(terrainElevationFt) - 200
            val high = space.upperLimit.toEffectiveAmslFeet(terrainElevationFt) + 200
            if (altitudeAmslFt !in low..high) continue

            val insideNow = isPointInPolygon(aircraftPos, space.polygon)
            if (insideNow) {
                alerts.add(AirspaceAlert(space, isInside = true, distanceNm = 0.0, etaSeconds = 0))
            } else if (groundSpeedKt > 20 && isPointInPolygon(futurePos, space.polygon)) {
                alerts.add(
                    AirspaceAlert(
                        airspace = space,
                        isInside = false,
                        distanceNm = lookaheadNm,
                        etaSeconds = 90
                    )
                )
            }
        }
        return alerts.sortedByDescending { it.isInside }
    }
}
