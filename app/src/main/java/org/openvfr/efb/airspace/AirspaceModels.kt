package org.openvfr.efb.airspace

import org.maplibre.android.geometry.LatLng

enum class AirspaceCategory(val label: String, val fillHex: String, val strokeHex: String) {
    CTR("CTR", "#44D32F2F", "#D32F2F"),
    TMA("TMA", "#331976D2", "#1976D2"),
    RESTRICTED("R / TSA", "#44F57C00", "#F57C00"),
    PROHIBITED("P / ZIT", "#55B71C1C", "#B71C1C"),
    DANGER("D / RTBA", "#38E64A19", "#E64A19"),
    SIV("SIV", "#2200796B", "#00796B"),
    ZSM("ZSM / Parc", "#33388E3C", "#388E3C"),
    NOTAM("NOTAM / SUP AIP", "#488E24AA", "#8E24AA"),
    OTHER("Espace", "#28546E7A", "#546E7A")
}

enum class AltitudeReference {
    SFC, AMSL, AGL, FL, UNL
}

data class VerticalLimit(
    val rawText: String,
    val reference: AltitudeReference,
    val valueFeet: Int
) {
    fun toEffectiveAmslFeet(terrainElevationFt: Int = 0): Int = when (reference) {
        AltitudeReference.SFC -> terrainElevationFt
        AltitudeReference.AMSL -> valueFeet
        AltitudeReference.AGL -> terrainElevationFt + valueFeet
        AltitudeReference.FL -> valueFeet
        AltitudeReference.UNL -> 99999
    }
}

data class Airspace(
    val id: String,
    val name: String,
    val airspaceClass: String,
    val category: AirspaceCategory,
    val lowerLimit: VerticalLimit,
    val upperLimit: VerticalLimit,
    val frequencyMhz: String? = null,
    val scheduleInfo: String? = null,
    val polygon: List<LatLng>
)

data class AirspaceAlert(
    val airspace: Airspace,
    val isInside: Boolean,
    val distanceNm: Double,
    val etaSeconds: Int?
)
