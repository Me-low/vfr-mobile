package org.openvfr.efb.navigation

import android.hardware.GeomagneticField
import org.maplibre.android.geometry.LatLng
import org.openvfr.efb.airspace.OpenAirParser
import org.openvfr.efb.terrain.TerrainEngine
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.*

object NavigationCalculator {

    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")

    /**
     * Résout l'intégralité du log de navigation (triangle des vitesses, déclinaison WMM,
     * altitude minimale de sécurité MSA, ETE, délestage et heures estimées HE).
     */
    fun computeFlightPlan(
        waypoints: List<NavWaypoint>,
        aircraft: AircraftProfile,
        cruiseAltitudeFt: Int,
        windDirDeg: Int,
        windSpeedKt: Int,
        departureTimeUtc: LocalTime = LocalTime.now(ZoneOffset.UTC)
    ): List<ComputedLeg> {
        if (waypoints.size < 2) return emptyList()

        val legs = mutableListOf<ComputedLeg>()
        var runningTime = departureTimeUtc

        for (i in 0 until waypoints.size - 1) {
            val from = waypoints[i]
            val to = waypoints[i + 1]

            // Si le pilote a saisi une heure réelle (HR) au point précédent, recalage automatique des HE suivantes
            from.actualOverflightTimeUtc?.let { hrText ->
                runCatching { LocalTime.parse(hrText, timeFmt) }.getOrNull()?.let { runningTime = it }
            }

            val distNm = OpenAirParser.haversineNm(from.position, to.position)
            val rv = OpenAirParser.initialBearingDeg(from.position, to.position)

            val midLat = (from.position.latitude + to.position.latitude) / 2.0
            val midLon = (from.position.longitude + to.position.longitude) / 2.0
            val decl = GeomagneticField(
                midLat.toFloat(),
                midLon.toFloat(),
                (cruiseAltitudeFt * 0.3048f),
                System.currentTimeMillis()
            ).declination.toDouble()

            val rm = (rv - decl + 360.0) % 360.0

            // Triangle des vitesses exact :
            // Angle au vent alpha = direction d'où vient le vent (magnétique) - route magnétique
            val windDirMag = (windDirDeg - decl + 360.0) % 360.0
            val alphaRad = Math.toRadians(windDirMag - rm)
            val tas = aircraft.cruiseTasKt.toDouble().coerceAtLeast(30.0)
            val vw = windSpeedKt.toDouble()

            val sinDrift = ((vw * sin(alphaRad)) / tas).coerceIn(-0.95, 0.95)
            val driftRad = asin(sinDrift)
            val driftDeg = Math.toDegrees(driftRad).roundToInt()
            val cm = ((rm + driftDeg) + 360.0).roundToInt() % 360

            val gs = (tas * cos(driftRad) - vw * cos(alphaRad)).roundToInt().coerceAtLeast(25)
            val effWind = (gs - tas).roundToInt()
            val eteMin = ceil((distNm / gs) * 60.0).toInt().coerceAtLeast(1)
            val legFuel = (eteMin / 60.0) * aircraft.cruiseFuelFlowLph

            // Altitude minimale de sécurité sur le tronçon (relief max + 1000 ft, arrondi à la centaine supérieure)
            val maxTerrain = sampleMaxTerrainAlongLeg(from.position, to.position)
            val msa = (((maxTerrain + 1000) + 99) / 100) * 100

            runningTime = runningTime.plusMinutes(eteMin.toLong())

            legs.add(
                ComputedLeg(
                    from = from,
                    to = to,
                    distanceNm = distNm,
                    trueCourseDeg = rv.roundToInt() % 360,
                    magneticCourseDeg = rm.roundToInt() % 360,
                    driftAngleDeg = driftDeg,
                    magneticHeadingDeg = cm,
                    effectiveWindKt = effWind,
                    groundSpeedKt = gs,
                    eteMinutes = eteMin,
                    fuelLiters = legFuel,
                    minSafeAltitudeFt = msa,
                    estimatedOverflightTimeUtc = runningTime.format(timeFmt)
                )
            )
        }
        return legs
    }

    private fun sampleMaxTerrainAlongLeg(p1: LatLng, p2: LatLng, steps: Int = 12): Int {
        var maxElev = 0
        for (s in 0..steps) {
            val f = s.toDouble() / steps
            val lat = p1.latitude + (p2.latitude - p1.latitude) * f
            val lon = p1.longitude + (p2.longitude - p1.longitude) * f
            val elev = TerrainEngine.getTerrainElevationFeet(lat, lon)
            if (elev > maxElev) maxElev = elev
        }
        return maxElev
    }

    fun computeFuelBreakdown(
        legs: List<ComputedLeg>,
        aircraft: AircraftProfile,
        loadSheet: LoadSheet
    ): FuelBreakdown {
        val tripFuel = legs.sumOf { it.fuelLiters } + 2.5 // Majoration montée initiale
        val contingency = tripFuel * 0.05
        val alternate = (loadSheet.alternateMinutes / 60.0) * aircraft.cruiseFuelFlowLph
        val reserveMin = if (loadSheet.isNightVfr) 45 else 30
        val finalReserve = (reserveMin / 60.0) * aircraft.cruiseFuelFlowLph
        val totalReq = loadSheet.taxiFuelLiters + tripFuel + contingency + alternate + finalReserve
        val extra = loadSheet.blockFuelLiters - totalReq
        val usableAirborne = (loadSheet.blockFuelLiters - loadSheet.taxiFuelLiters).coerceAtLeast(0.0)
        val enduranceMin = ((usableAirborne / aircraft.cruiseFuelFlowLph) * 60.0).roundToInt()

        return FuelBreakdown(
            taxiLiters = loadSheet.taxiFuelLiters,
            tripLiters = tripFuel,
            contingencyLiters = contingency,
            alternateLiters = alternate,
            finalReserveLiters = finalReserve,
            totalRequiredLiters = totalReq,
            blockFuelLiters = loadSheet.blockFuelLiters,
            extraFuelLiters = extra,
            enduranceMinutes = enduranceMin
        )
    }

    fun computeWeightAndBalance(
        aircraft: AircraftProfile,
        loadSheet: LoadSheet,
        tripFuelLiters: Double
    ): WeightBalanceResult {
        val densityKgPerL = 0.72 // AVGAS 100LL
        val zfw = aircraft.emptyWeightKg + loadSheet.frontSeatsKg + loadSheet.rearSeatsKg + loadSheet.baggageKg
        val zfwMoment = aircraft.emptyWeightKg * aircraft.emptyArmM +
                loadSheet.frontSeatsKg * aircraft.frontSeatsArmM +
                loadSheet.rearSeatsKg * aircraft.rearSeatsArmM +
                loadSheet.baggageKg * aircraft.baggageArmM
        val zfwCg = zfwMoment / zfw

        val takeoffFuelKg = (loadSheet.blockFuelLiters - loadSheet.taxiFuelLiters).coerceAtLeast(0.0) * densityKgPerL
        val tow = zfw + takeoffFuelKg
        val towCg = (zfwMoment + takeoffFuelKg * aircraft.fuelArmM) / tow

        val landingFuelKg = (takeoffFuelKg - tripFuelLiters * densityKgPerL).coerceAtLeast(0.0)
        val lw = zfw + landingFuelKg
        val lwCg = (zfwMoment + landingFuelKg * aircraft.fuelArmM) / lw

        val validMass = tow <= aircraft.maxTakeoffWeightKg
        val validCg = listOf(zfwCg, towCg, lwCg).all { it in 0.30..0.62 }

        return WeightBalanceResult(
            zeroFuelWeightKg = zfw,
            zeroFuelCgM = zfwCg,
            takeoffWeightKg = tow,
            takeoffCgM = towCg,
            landingWeightKg = lw,
            landingCgM = lwCg,
            isWithinEnvelope = validMass && validCg
        )
    }

    val DEFAULT_DEMO_ROUTE = listOf(
        NavWaypoint("wp1", "LFJI", "Marennes", LatLng(45.8295, -1.1167), WaypointType.AIRFIELD, "123.500", 30),
        NavWaypoint("wp2", "W-OLERON", "Viaduc Oléron", LatLng(45.8850, -1.1950), WaypointType.VFR_POINT, null, 0),
        NavWaypoint("wp3", "S-LFBH", "Point Sierra La Rochelle", LatLng(46.0650, -1.1800), WaypointType.VFR_POINT, "124.200", 0),
        NavWaypoint("wp4", "LFBH", "La Rochelle Île de Ré", LatLng(46.1792, -1.1953), WaypointType.AIRFIELD, "118.000", 74)
    )
}
