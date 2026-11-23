package org.openvfr.efb.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.openvfr.efb.airspace.AirspaceAlert
import java.util.Locale

@Composable
fun TelemetryPill(
    label: String,
    value: String,
    unit: String,
    subValue: String? = null,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .defaultMinSize(minWidth = 82.dp, minHeight = 58.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.93f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)),
        shadowElevation = 4.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        fontFeatureSettings = "tnum",
                        letterSpacing = 0.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                    text = unit,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 2.dp)
                )
            }
            if (subValue != null) {
                Text(
                    text = subValue,
                    style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f)
                )
            }
        }
    }
}

/**
 * Widget compact indiquant le vecteur vent instantané calculé en vol
 * (flèche orientée dans le sens du vent + direction/force en kt).
 */
@Composable
fun WindIndicatorPill(
    windDirDeg: Int,
    windSpeedKt: Int,
    aircraftTrackDeg: Int,
    modifier: Modifier = Modifier
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    Surface(
        modifier = modifier.defaultMinSize(minWidth = 82.dp, minHeight = 58.dp),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.93f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)),
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Canvas(modifier = Modifier.size(26.dp)) {
                val relativeWindTo = (windDirDeg - aircraftTrackDeg + 180f) % 360f
                rotate(relativeWindTo) {
                    val cx = size.width / 2f
                    drawLine(primaryColor, Offset(cx, 3f), Offset(cx, size.height - 3f), strokeWidth = 3f)
                    drawLine(primaryColor, Offset(cx, 3f), Offset(cx - 6f, 11f), strokeWidth = 3f)
                    drawLine(primaryColor, Offset(cx, 3f), Offset(cx + 6f, 11f), strokeWidth = 3f)
                }
            }
            Column {
                Text("VENT", style = MaterialTheme.typography.labelSmall)
                Text(
                    text = String.format(Locale.US, "%03d°/%02dkt", windDirDeg, windSpeedKt),
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.Bold,
                        fontFeatureSettings = "tnum"
                    )
                )
            }
        }
    }
}

@Composable
fun AirspaceAlertBanner(
    alert: AirspaceAlert,
    modifier: Modifier = Modifier
) {
    val bgColor = if (alert.isInside) Color(0xFFB71C1C) else Color(0xFFE65100)
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = bgColor,
        shadowElevation = 6.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(Icons.Rounded.Warning, contentDescription = null, tint = Color.White)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (alert.isInside) "PÉNÉTRATION ESPACE : ${alert.airspace.name}"
                    else "APPROCHE ESPACE (${alert.etaSeconds}s) : ${alert.airspace.name}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = "${alert.airspace.lowerLimit.rawText} -> ${alert.airspace.upperLimit.rawText}" +
                            (alert.airspace.frequencyMhz?.let { " • Fréq $it MHz" } ?: ""),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.92f)
                )
            }
        }
    }
}
