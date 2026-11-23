package org.openvfr.efb.navigation

import org.maplibre.android.geometry.LatLng

enum class WaypointType(val badge: String) {
    AIRFIELD("AD"),
    ULM("ULM"),
    VFR_POINT("VRP"),
    VOR_DME("NAV"),
    CUSTOM("WPT")
}

data class NavWaypoint(
    val id: String,
    val code: String,
    val name: String,
    val position: LatLng,
    val type: WaypointType,
    val frequencyMhz: String? = null,
    val elevationFt: Int = 0,
    val actualOverflightTimeUtc: String? = null // Heure réelle (HR) saisie en vol
)

data class AircraftProfile(
    val registration: String = "F-GXYZ",
    val typeName: String = "Robin DR400-120",
    val cruiseTasKt: Int = 105,
    val climbSpeedKt: Int = 75,
    val climbRateFpm: Int = 600,
    val cruiseFuelFlowLph: Double = 24.0,
    val climbFuelFlowLph: Double = 30.0,
    val usableFuelLiters: Double = 110.0,
    val bestGlideRatio: Double = 10.5,
    val bestGlideSpeedKt: Int = 73,
    val emptyWeightKg: Double = 555.0,
    val emptyArmM: Double = 0.36,
    val maxTakeoffWeightKg: Double = 900.0,
    val frontSeatsArmM: Double = 0.41,
    val rearSeatsArmM: Double = 1.19,
    val baggageArmM: Double = 1.85,
    val fuelArmM: Double = 1.12
)

data class LoadSheet(
    val frontSeatsKg: Double = 145.0,
    val rearSeatsKg: Double = 0.0,
    val baggageKg: Double = 12.0,
    val blockFuelLiters: Double = 85.0,
    val taxiFuelLiters: Double = 3.0,
    val alternateMinutes: Int = 20,
    val isNightVfr: Boolean = false
)

data class ComputedLeg(
    val from: NavWaypoint,
    val to: NavWaypoint,
    val distanceNm: Double,
    val trueCourseDeg: Int,
    val magneticCourseDeg: Int,
    val driftAngleDeg: Int,
    val magneticHeadingDeg: Int,
    val effectiveWindKt: Int,
    val groundSpeedKt: Int,
    val eteMinutes: Int,
    val fuelLiters: Double,
    val minSafeAltitudeFt: Int,
    val estimatedOverflightTimeUtc: String
)

data class FuelBreakdown(
    val taxiLiters: Double,
    val tripLiters: Double,
    val contingencyLiters: Double,
    val alternateLiters: Double,
    val finalReserveLiters: Double,
    val totalRequiredLiters: Double,
    val blockFuelLiters: Double,
    val extraFuelLiters: Double,
    val enduranceMinutes: Int
)

data class WeightBalanceResult(
    val zeroFuelWeightKg: Double,
    val zeroFuelCgM: Double,
    val takeoffWeightKg: Double,
    val takeoffCgM: Double,
    val landingWeightKg: Double,
    val landingCgM: Double,
    val isWithinEnvelope: Boolean
)
