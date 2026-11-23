package org.openvfr.efb

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.maplibre.android.MapLibre
import org.openvfr.efb.avionics.Gdl90Receiver
import org.openvfr.efb.sensors.FlightSensorManager
import org.openvfr.efb.terrain.TerrainEngine
import org.openvfr.efb.ui.screens.NavigationScreen
import org.openvfr.efb.ui.theme.CockpitDisplayMode
import org.openvfr.efb.ui.theme.CockpitTheme
import java.io.File

class MainActivity : ComponentActivity() {

    private lateinit var sensorManager: FlightSensorManager
    private lateinit var gdl90Receiver: Gdl90Receiver

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        MapLibre.getInstance(this)

        // Dossier local pour dalles DEM SRTM (.hgt)
        TerrainEngine.srtmDirectory = File(getExternalFilesDir(null), "srtm").apply { mkdirs() }

        sensorManager = FlightSensorManager(this)
        gdl90Receiver = Gdl90Receiver()

        setContent {
            var displayMode by remember { mutableStateOf(CockpitDisplayMode.MATERIAL_YOU) }
            val telemetry by sensorManager.telemetry.collectAsStateWithLifecycle()
            val recordedTrack by sensorManager.recordedTrack.collectAsStateWithLifecycle()
            val trafficTargets by gdl90Receiver.targets.collectAsStateWithLifecycle()
            val isGdl90Listening by gdl90Receiver.isListening.collectAsStateWithLifecycle()

            val permissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestMultiplePermissions()
            ) { granted ->
                if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
                    sensorManager.start()
                }
            }

            LaunchedEffect(Unit) {
                permissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            }

            CockpitTheme(mode = displayMode) {
                NavigationScreen(
                    telemetry = telemetry,
                    recordedTrack = recordedTrack,
                    trafficTargets = trafficTargets,
                    isGdl90Listening = isGdl90Listening,
                    displayMode = displayMode,
                    onCycleDisplayMode = {
                        displayMode = when (displayMode) {
                            CockpitDisplayMode.MATERIAL_YOU -> CockpitDisplayMode.SUNLIGHT
                            CockpitDisplayMode.SUNLIGHT -> CockpitDisplayMode.NIGHT_RED
                            CockpitDisplayMode.NIGHT_RED -> CockpitDisplayMode.MATERIAL_YOU
                        }
                    },
                    onUpdateQnh = { qnh -> sensorManager.setQnh(qnh) },
                    onToggleSimulation = { tas -> sensorManager.toggleSimulation(tas) },
                    onToggleGdl90 = { gdl90Receiver.toggle(4000) },
                    onClearTrack = { sensorManager.clearTrack() }
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorManager.stop()
        gdl90Receiver.stop()
    }
}
