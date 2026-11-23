package org.openvfr.efb.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import org.openvfr.efb.navigation.AircraftProfile
import org.openvfr.efb.navigation.WeightBalanceResult
import java.util.Locale

/**
 * Graphique interactif de l'enveloppe de Masse et Centrage (ZFW, TOW, LW).
 */
@Composable
fun CgEnvelopeView(
    aircraft: AircraftProfile,
    wb: WeightBalanceResult,
    modifier: Modifier = Modifier
) {
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
    val envColor = if (wb.isWithinEnvelope) Color(0xFF2E7D32) else Color(0xFFC62828)

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = String.format(Locale.US, "TOW : %.0f kg (CG %.2f m)", wb.takeoffWeightKg, wb.takeoffCgM),
                style = MaterialTheme.typography.labelMedium
            )
            Text(
                text = if (wb.isWithinEnvelope) "CENTRAGE VALIDE" else "HORS ENVELOPPE",
                style = MaterialTheme.typography.labelMedium,
                color = envColor
            )
        }
        Spacer(modifier = Modifier.height(6.dp))

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
        ) {
            val w = size.width
            val h = size.height
            val minArm = 0.25f
            val maxArm = 0.70f
            val minMass = 500f
            val maxMass = 950f

            fun mapX(arm: Double): Float = (((arm.toFloat() - minArm) / (maxArm - minArm)) * w).coerceIn(0f, w)
            fun mapY(mass: Double): Float = (h - ((mass.toFloat() - minMass) / (maxMass - minMass)) * h).coerceIn(0f, h)

            drawRect(gridColor, style = Stroke(width = 2f))

            // Polygone enveloppe certifiée DR400
            val envPath = Path().apply {
                moveTo(mapX(0.30), mapY(540.0))
                lineTo(mapX(0.30), mapY(760.0))
                lineTo(mapX(0.38), mapY(aircraft.maxTakeoffWeightKg))
                lineTo(mapX(0.62), mapY(aircraft.maxTakeoffWeightKg))
                lineTo(mapX(0.62), mapY(540.0))
                close()
            }
            drawPath(envPath, color = envColor.copy(alpha = 0.18f))
            drawPath(envPath, color = envColor, style = Stroke(width = 3f))

            // Segment ZFW -> LW -> TOW
            val pZfw = Offset(mapX(wb.zeroFuelCgM), mapY(wb.zeroFuelWeightKg))
            val pLw = Offset(mapX(wb.landingCgM), mapY(wb.landingWeightKg))
            val pTow = Offset(mapX(wb.takeoffCgM), mapY(wb.takeoffWeightKg))

            drawLine(Color(0xFF1976D2), pZfw, pLw, strokeWidth = 3f)
            drawLine(Color(0xFF1976D2), pLw, pTow, strokeWidth = 3f)

            drawCircle(Color(0xFF7B1FA2), radius = 6f, center = pZfw)
            drawCircle(Color(0xFF00897B), radius = 6f, center = pLw)
            drawCircle(envColor, radius = 8f, center = pTow)
        }
    }
}
