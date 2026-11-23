package org.openvfr.efb.navigation

import org.openvfr.efb.sensors.TrackPoint
import java.time.Instant
import java.util.Locale

/**
 * Export interopérable du log de navigation et de la trace GPS :
 * - GPX 1.1 (trace de vol & route)
 * - Garmin FPL
 * - Dossier de vol texte complet ("Route étroite")
 */
object FlightExporter {

    fun exportRouteToGpx(waypoints: List<NavWaypoint>): String = buildString {
        appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        appendLine("""<gpx version="1.1" creator="OpenVFR EFB" xmlns="http://www.topografix.com/GPX/1/1">""")
        appendLine("  <rte>")
        appendLine("    <name>Navigation OpenVFR</name>")
        for (wp in waypoints) {
            appendLine("""    <rtept lat="${wp.position.latitude}" lon="${wp.position.longitude}">""")
            appendLine("      <name>${wp.code} - ${wp.name}</name>")
            appendLine("    </rtept>")
        }
        appendLine("  </rte>")
        appendLine("</gpx>")
    }

    fun exportTrackToGpx(track: List<TrackPoint>): String = buildString {
        appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        appendLine("""<gpx version="1.1" creator="OpenVFR EFB" xmlns="http://www.topografix.com/GPX/1/1">""")
        appendLine("  <trk>")
        appendLine("    <name>Trace Vol OpenVFR</name>")
        appendLine("    <trkseg>")
        for (pt in track) {
            val eleMeters = pt.altitudeAmslFt * 0.3048
            val isoTime = Instant.ofEpochMilli(pt.timestampMs).toString()
            appendLine("""      <trkpt lat="${pt.latitude}" lon="${pt.longitude}">""")
            appendLine(String.format(Locale.US, "        <ele>%.1f</ele>", eleMeters))
            appendLine("        <time>$isoTime</time>")
            appendLine("      </trkpt>")
        }
        appendLine("    </trkseg>")
        appendLine("  </trk>")
        appendLine("</gpx>")
    }

    fun exportGarminFpl(waypoints: List<NavWaypoint>): String = buildString {
        appendLine("""<?xml version="1.0" encoding="utf-8"?>""")
        appendLine("""<flight-plan xmlns="http://www8.garmin.com/xmlschemas/FlightPlan/v1">""")
        appendLine("  <waypoint-table>")
        for (wp in waypoints) {
            appendLine("    <waypoint>")
            appendLine("      <identifier>${wp.code}</identifier>")
            appendLine("      <type>${if (wp.type == WaypointType.AIRFIELD) "AIRPORT" else "USER WAYPOINT"}</type>")
            appendLine("      <lat>${wp.position.latitude}</lat>")
            appendLine("      <lon>${wp.position.longitude}</lon>")
            appendLine("    </waypoint>")
        }
        appendLine("  </waypoint-table>")
        appendLine("  <route>")
        appendLine("    <route-name>OPENVFR</route-name>")
        for (wp in waypoints) {
            appendLine("    <route-point><waypoint-identifier>${wp.code}</waypoint-identifier></route-point>")
        }
        appendLine("  </route>")
        appendLine("</flight-plan>")
    }

    fun generateBriefingSummary(
        legs: List<ComputedLeg>,
        fuel: FuelBreakdown,
        wb: WeightBalanceResult
    ): String = buildString {
        appendLine("=== DOSSIER DE VOL OPENVFR ===")
        appendLine(String.format(Locale.US, "Distance totale : %.1f NM | Temps total : %d min",
            legs.sumOf { it.distanceNm }, legs.sumOf { it.eteMinutes }))
        appendLine(String.format(Locale.US, "Carburant requis : %.1f L | Bloc : %.1f L (Autonomie %d min)",
            fuel.totalRequiredLiters, fuel.blockFuelLiters, fuel.enduranceMinutes))
        appendLine(String.format(Locale.US, "Masse décollage : %.0f kg (CG %.2f m) - %s",
            wb.takeoffWeightKg, wb.takeoffCgM, if (wb.isWithinEnvelope) "DANS L'ENVELOPPE" else "HORS LIMITES"))
        appendLine("--------------------------------")
        for (leg in legs) {
            appendLine(
                String.format(
                    Locale.US,
                    "%s -> %s | %.1f NM | Cm %03d° | Gs %d kt | %d min | MSA %d ft | HE %s",
                    leg.from.code, leg.to.code, leg.distanceNm, leg.magneticHeadingDeg,
                    leg.groundSpeedKt, leg.eteMinutes, leg.minSafeAltitudeFt, leg.estimatedOverflightTimeUtc
                )
            )
        }
    }
}
