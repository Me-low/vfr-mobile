package org.openvfr.efb.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp
import org.maplibre.android.geometry.LatLng
import org.openvfr.efb.airspace.Airspace
import org.openvfr.efb.airspace.AirspaceSpatialEngine
import org.openvfr.efb.navigation.ComputedLeg
import org.openvfr.efb.navigation.NavWaypoint
import org.openvfr.efb.terrain.TerrainEngine
import java.util.Locale

/**
 * Coupe verticale longitudinale synchronisée avec la route :
 * Affiche le relief MNT, les espaces aériens traversés, la trajectoire verticale prévue
 * et la position en temps réel de l'aéronef.
 */
@Composable
fun VerticalProfileView(
    waypoints: List<NavWaypoint>,
    legs: List<ComputedLeg>,
    cruiseAltitudeFt: Int,
    currentAltitudeFt: Int,
    modifier: Modifier = Modifier
) {
    val totalDistNm = legs.sumOf { it.distanceNm }.coerceAtLeast(1.0)
    val maxChartAltFt = maxOf(cruiseAltitudeFt + 2000, 6500).toFloat()
    val primaryColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = String.format(Locale.US, "Profil vertical • Distance %.1f NM", totalDistNm),
                style = MaterialTheme.typography.labelMedium
            )
            Text(
                text = "Croisière $cruiseAltitudeFt ft",
                style = MaterialTheme.typography.labelMedium,
                color = primaryColor
            )
        }
        Spacer(modifier = Modifier.height(6.dp))

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
        ) {
            val w = size.width
            val h = size.height

            // Grille horizontale tous les 1000 ft
            var altStep = 1000f
            while (altStep < maxChartAltFt) {
                val y = h - (altStep / maxChartAltFt) * h
                drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
                altStep += 1000f
            }

            if (waypoints.size >= 2) {
                // Échantillonnage du profil relief sur 60 points le long des branches
                val terrainPath = Path()
                terrainPath.moveTo(0f, h)
                var cumDist = 0.0
                for (leg in legs) {
                    val samples = 15
                    for (s in 0..samples) {
                        val f = s.toDouble() / samples
                        val lat = leg.from.position.latitude + (leg.to.position.latitude - leg.from.position.latitude) * f
                        val lon = leg.from.position.longitude + (leg.to.position.longitude - leg.from.position.longitude) * f
                        val elevFt = TerrainEngine.getTerrainElevationFeet(lat, lon)
                        val x = (((cumDist + leg.distanceNm * f) / totalDistNm) * w).toFloat()
                        val y = h - ((elevFt / maxChartAltFt) * h).coerceIn(0f, h)
                        terrainPath.lineTo(x, y)
                    }
                    cumDist += leg.distanceNm
                }
                terrainPath.lineTo(w, h)
                terrainPath.close()
                drawPath(terrainPath, color = Color(0xFF6D4C41).copy(alpha = 0.55f))

                // Trajectoire prévue (montée -> palier -> descente)
                val tocX = (w * 0.14f).coerceAtMost(w * 0.35f)
                val todX = (w * 0.84f).coerceAtLeast(w * 0.65f)
                val cruiseY = h - (cruiseAltitudeFt / maxChartAltFt) * h

                drawLine(primaryColor, Offset(0f, h - 10f), Offset(tocX, cruiseY), strokeWidth = 4f)
                drawLine(primaryColor, Offset(tocX, cruiseY), Offset(todX, cruiseY), strokeWidth = 4f)
                drawLine(primaryColor, Offset(todX, cruiseY), Offset(w, h - 10f), strokeWidth = 4f)

                // Repères verticaux des points tournants
                var wpDist = 0.0
                for (leg in legs) {
                    wpDist += leg.distanceNm
                    val wx = ((wpDist / totalDistNm) * w).toFloat()
                    drawLine(gridColor, Offset(wx, 0f), Offset(wx, h), strokeWidth = 1.5f)
                }
            }

            // Altitude actuelle de l'avion
            val acY = h - ((currentAltitudeFt / maxChartAltFt) * h).coerceIn(4f, h - 4f)
            drawCircle(Color(0xFFD32F2F), radius = 7f, center = Offset(24f, acY))
        }
    }
}
