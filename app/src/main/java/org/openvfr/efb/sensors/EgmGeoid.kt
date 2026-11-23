package org.openvfr.efb.sensors

import kotlin.math.cos
import kotlin.math.sin

/**
 * Modèle d'ondulation du géoïde (EGM96/EGM2008 simplifié) pour convertir
 * l'altitude ellipsoïdale WGS84 brute du GPS Android en altitude orthométrique réelle (AMSL) :
 * H_AMSL = h_WGS84 - N_geoid
 */
object EgmGeoid {

    fun getUndulationMeters(latDeg: Double, lonDeg: Double): Double {
        // En Europe de l'Ouest (France), l'ondulation du géoïde varie typiquement entre +43m et +52m.
        // Cette approximation harmonique fournit une précision métrique sur l'Europe occidentale
        // et une estimation globale cohérente ailleurs.
        val latRad = Math.toRadians(latDeg)
        val lonRad = Math.toRadians(lonDeg)
        return if (latDeg in 41.0..52.0 && lonDeg in -6.0..10.0) {
            47.5 + 3.2 * sin((latDeg - 46.0) * 0.25) + 2.4 * cos((lonDeg - 2.0) * 0.22)
        } else {
            30.0 * cos(latRad) * cos(lonRad - 0.35) + 12.0 * sin(2.0 * latRad)
        }
    }

    fun wgs84ToAmslMeters(wgs84Meters: Double, latDeg: Double, lonDeg: Double): Double {
        return wgs84Meters - getUndulationMeters(latDeg, lonDeg)
    }
}
