package org.openvfr.efb.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.layers.*
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import org.openvfr.efb.airspace.*
import org.openvfr.efb.avionics.TrafficTarget
import org.openvfr.efb.navigation.*
import org.openvfr.efb.pdf.*
import org.openvfr.efb.sensors.FlightTelemetry
import org.openvfr.efb.sensors.TrackPoint
import org.openvfr.efb.terrain.TerrainEngine
import org.openvfr.efb.ui.components.*
import org.openvfr.efb.ui.theme.CockpitDisplayMode
import org.openvfr.efb.weather.DecodedWeather
import org.openvfr.efb.weather.WeatherService
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NavigationScreen(
    telemetry: FlightTelemetry,
    recordedTrack: List<TrackPoint>,
    trafficTargets: Map<Int, TrafficTarget>,
    isGdl90Listening: Boolean,
    displayMode: CockpitDisplayMode,
    onCycleDisplayMode: () -> Unit,
    onUpdateQnh: (Float) -> Unit,
    onToggleSimulation: (Int) -> Unit,
    onToggleGdl90: () -> Unit,
    onClearTrack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scaffoldState = rememberBottomSheetScaffoldState()

    var mapInstance by remember { mutableStateOf<MapLibreMap?>(null) }
    var followAircraft by remember { mutableStateOf(true) }
    var screenLocked by remember { mutableStateOf(false) }
    var showQnhDialog by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableIntStateOf(0) }

    // États Route & Navigation
    var waypoints by remember { mutableStateOf(NavigationCalculator.DEFAULT_DEMO_ROUTE) }
    var isAddingWaypointsOnMap by remember { mutableStateOf(false) }
    var cruiseAltitudeFt by remember { mutableIntStateOf(2500) }
    var aircraft by remember { mutableStateOf(AircraftProfile()) }
    var loadSheet by remember { mutableStateOf(LoadSheet()) }
    var showGlideRing by remember { mutableStateOf(true) }

    // États Espaces Aériens & NOTAM
    var airspaces by remember {
        mutableStateOf(
            OpenAirParser.parse(OpenAirParser.DEFAULT_FRANCE_SW_OPENAIR) +
                    NotamGeometryParser.parseBulletinToAirspaces(NotamGeometryParser.SAMPLE_NOTAM_TEXT)
        )
    }
    var ceilingFilterFt by remember { mutableIntStateOf(11500) }
    var inspectedPointStack by remember { mutableStateOf<Pair<LatLng, List<Airspace>>?>(null) }
    var rawNotamInput by remember { mutableStateOf(NotamGeometryParser.SAMPLE_NOTAM_TEXT) }

    // États Météo
    var weatherList by remember { mutableStateOf<List<DecodedWeather>>(emptyList()) }
    var isLoadingWeather by remember { mutableStateOf(false) }

    // États Calibration & Calques PDF multi-couches
    var pdfLayers by remember { mutableStateOf<List<CalibratedPdfLayer>>(emptyList()) }
    var pendingPdfUri by remember { mutableStateOf<Uri?>(null) }
    var pendingPdfName by remember { mutableStateOf("Carte VAC") }
    var pendingPdfBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var pdfCropMargin by remember { mutableFloatStateOf(0f) }
    var isCalibrating by remember { mutableStateOf(false) }
    var pendingPdfNormPoint by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var controlPoints by remember { mutableStateOf<List<ControlPoint>>(emptyList()) }
    var calibrationPreview by remember { mutableStateOf<CalibrationResult?>(null) }

    // Calculs temps réel
    val terrainBelowFt = remember(telemetry.latitude, telemetry.longitude) {
        TerrainEngine.getTerrainElevationFeet(telemetry.latitude, telemetry.longitude)
    }
    val heightAglFt = (telemetry.altitudeAmslFt - terrainBelowFt).coerceAtLeast(0)

    val computedLegs = remember(
        waypoints, aircraft, cruiseAltitudeFt,
        telemetry.estimatedWindDirDeg, telemetry.estimatedWindSpeedKt
    ) {
        NavigationCalculator.computeFlightPlan(
            waypoints = waypoints,
            aircraft = aircraft,
            cruiseAltitudeFt = cruiseAltitudeFt,
            windDirDeg = telemetry.estimatedWindDirDeg,
            windSpeedKt = telemetry.estimatedWindSpeedKt
        )
    }

    val fuelBreakdown = remember(computedLegs, aircraft, loadSheet) {
        NavigationCalculator.computeFuelBreakdown(computedLegs, aircraft, loadSheet)
    }

    val weightBalance = remember(aircraft, loadSheet, fuelBreakdown) {
        NavigationCalculator.computeWeightAndBalance(aircraft, loadSheet, fuelBreakdown.tripLiters)
    }

    val activeAlerts = remember(
        telemetry.latitude, telemetry.longitude, telemetry.altitudeAmslFt,
        telemetry.groundSpeedKt, telemetry.gpsTrackDeg, airspaces
    ) {
        AirspaceSpatialEngine.checkProactiveAlerts(
            aircraftPos = LatLng(telemetry.latitude, telemetry.longitude),
            altitudeAmslFt = telemetry.altitudeAmslFt,
            terrainElevationFt = terrainBelowFt,
            groundSpeedKt = telemetry.groundSpeedKt,
            trackDeg = telemetry.gpsTrackDeg,
            airspaces = airspaces
        )
    }

    val glideRingPolygon = remember(
        showGlideRing, telemetry.latitude, telemetry.longitude, telemetry.altitudeAmslFt,
        aircraft, telemetry.estimatedWindDirDeg, telemetry.estimatedWindSpeedKt
    ) {
        if (!showGlideRing) emptyList()
        else TerrainEngine.computeTerrainAwareGlideRing(
            aircraftPos = LatLng(telemetry.latitude, telemetry.longitude),
            altitudeAmslFt = telemetry.altitudeAmslFt,
            bestGlideRatio = aircraft.bestGlideRatio,
            bestGlideSpeedKt = aircraft.bestGlideSpeedKt.toDouble(),
            windDirDeg = telemetry.estimatedWindDirDeg,
            windSpeedKt = telemetry.estimatedWindSpeedKt
        )
    }

    // Chargement météo initial
    LaunchedEffect(Unit) {
        isLoadingWeather = true
        weatherList = WeatherService.fetchMetarAndTaf(listOf("LFBH", "LFBG", "LFBI", "LFBD"))
        isLoadingWeather = false
    }

    // Sélecteurs de fichiers (PDF & OpenAir)
    val pdfPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            pendingPdfUri = uri
            pendingPdfName = uri.lastPathSegment?.substringAfterLast('/') ?: "Carte PDF"
            pdfCropMargin = 0f
            val bmp = PdfOverlayManager.renderPdfPageToBitmap(context, uri, 0, 2200, 0f)
            if (bmp != null) {
                pendingPdfBitmap = bmp
                controlPoints = emptyList()
                pendingPdfNormPoint = null
                calibrationPreview = null
                isCalibrating = true
            }
        }
    }

    val openAirPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                val parsed = OpenAirParser.parse(reader.readText())
                if (parsed.isNotEmpty()) {
                    airspaces = airspaces + parsed
                    Toast.makeText(context, "${parsed.size} espaces importés", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Synchronisation continue des couches GeoJSON MapLibre
    LaunchedEffect(
        mapInstance, airspaces, ceilingFilterFt, waypoints, telemetry,
        glideRingPolygon, trafficTargets, pdfLayers
    ) {
        val map = mapInstance ?: return@LaunchedEffect
        map.getStyle { style ->
            syncMapLayers(
                style = style,
                airspaces = airspaces.filter { it.lowerLimit.valueFeet <= ceilingFilterFt },
                waypoints = waypoints,
                telemetry = telemetry,
                glideRing = glideRingPolygon,
                traffic = trafficTargets.values.toList(),
                pdfLayers = pdfLayers
            )
        }
    }

    // Suivi caméra GPS
    LaunchedEffect(telemetry.latitude, telemetry.longitude, telemetry.gpsTrackDeg, followAircraft) {
        if (followAircraft && !isCalibrating) {
            mapInstance?.cameraPosition = CameraPosition.Builder()
                .target(LatLng(telemetry.latitude, telemetry.longitude))
                .bearing(if (telemetry.groundSpeedKt > 8) telemetry.gpsTrackDeg.toDouble() else 0.0)
                .build()
        }
    }

    BottomSheetScaffold(
        scaffoldState = scaffoldState,
        sheetPeekHeight = if (isCalibrating) 0.dp else 86.dp,
        sheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        sheetContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        sheetContent = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 340.dp, max = 520.dp)
                    .padding(horizontal = 16.dp)
            ) {
                // Résumé compact visible dans la poignée du tiroir
                val totalDist = computedLegs.sumOf { it.distanceNm }
                val totalMin = computedLegs.sumOf { it.eteMinutes }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (waypoints.size >= 2)
                            "${waypoints.first().code} -> ${waypoints.last().code} • ${String.format(Locale.US, "%.1f", totalDist)} NM • $totalMin min"
                        else "Navigation libre",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "AGL $heightAglFt ft",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                ScrollableTabRow(
                    selectedTabIndex = selectedTab,
                    edgePadding = 0.dp,
                    divider = {}
                ) {
                    listOf("Route / Log", "Profil", "Météo & NOTAM", "Avion & Devis", "Calques & Capteurs")
                        .forEachIndexed { idx, title ->
                            Tab(
                                selected = selectedTab == idx,
                                onClick = { selectedTab = idx },
                                text = { Text(title, maxLines = 1) }
                            )
                        }
                }

                Spacer(modifier = Modifier.height(10.dp))

                when (selectedTab) {
                    0 -> RouteLogTab(
                        waypoints = waypoints,
                        legs = computedLegs,
                        isAddingOnMap = isAddingWaypointsOnMap,
                        onToggleAddOnMap = { isAddingWaypointsOnMap = !isAddingWaypointsOnMap },
                        onStampActualTime = { wpIndex ->
                            val nowUtc = LocalTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("HH:mm"))
                            waypoints = waypoints.mapIndexed { i, wp ->
                                if (i == wpIndex) wp.copy(actualOverflightTimeUtc = nowUtc) else wp
                            }
                        },
                        onRemoveWaypoint = { wpIndex ->
                            if (waypoints.size > 1) {
                                waypoints = waypoints.filterIndexed { i, _ -> i != wpIndex }
                            }
                        },
                        onExportGpx = {
                            val gpx = FlightExporter.exportRouteToGpx(waypoints)
                            copyToClipboard(context, "Route GPX", gpx)
                        },
                        onExportFpl = {
                            val fpl = FlightExporter.exportGarminFpl(waypoints)
                            copyToClipboard(context, "Garmin FPL", fpl)
                        },
                        onExportBriefing = {
                            val doc = FlightExporter.generateBriefingSummary(computedLegs, fuelBreakdown, weightBalance)
                            copyToClipboard(context, "Dossier de vol", doc)
                        }
                    )

                    1 -> VerticalProfileTab(
                        waypoints = waypoints,
                        legs = computedLegs,
                        cruiseAltitudeFt = cruiseAltitudeFt,
                        currentAltitudeFt = telemetry.altitudeAmslFt,
                        ceilingFilterFt = ceilingFilterFt,
                        onChangeCruiseAlt = { cruiseAltitudeFt = (cruiseAltitudeFt + it).coerceIn(1000, 19500) },
                        onChangeCeilingFilter = { ceilingFilterFt = it }
                    )

                    2 -> WeatherAndNotamTab(
                        weatherList = weatherList,
                        isLoadingWeather = isLoadingWeather,
                        onRefreshWeather = {
                            scope.launch {
                                isLoadingWeather = true
                                weatherList = WeatherService.fetchMetarAndTaf(listOf("LFBH", "LFBG", "LFBI", "LFBD"))
                                isLoadingWeather = false
                            }
                        },
                        rawNotamText = rawNotamInput,
                        onNotamTextChange = { rawNotamInput = it },
                        onParseNotams = {
                            val nonNotams = airspaces.filterNot { it.category == AirspaceCategory.NOTAM }
                            val parsed = NotamGeometryParser.parseBulletinToAirspaces(rawNotamInput)
                            airspaces = nonNotams + parsed
                            Toast.makeText(context, "${parsed.size} zones NOTAM tracées", Toast.LENGTH_SHORT).show()
                        }
                    )

                    3 -> AircraftAndFuelTab(
                        aircraft = aircraft,
                        loadSheet = loadSheet,
                        fuel = fuelBreakdown,
                        wb = weightBalance,
                        showGlideRing = showGlideRing,
                        onToggleGlideRing = { showGlideRing = it },
                        onUpdateTas = { aircraft = aircraft.copy(cruiseTasKt = it) },
                        onUpdateFuel = { loadSheet = loadSheet.copy(blockFuelLiters = it) },
                        onToggleNightVfr = { loadSheet = loadSheet.copy(isNightVfr = it) }
                    )

                    4 -> LayersAndSensorsTab(
                        telemetry = telemetry,
                        pdfLayers = pdfLayers,
                        isGdl90Listening = isGdl90Listening,
                        trafficCount = trafficTargets.size,
                        trackPointsCount = recordedTrack.size,
                        onImportPdf = { pdfPicker.launch(arrayOf("application/pdf")) },
                        onUpdatePdfOpacity = { id, op ->
                            pdfLayers = pdfLayers.map { if (it.id == id) it.copy(opacity = op) else it }
                        },
                        onRemovePdf = { id ->
                            pdfLayers = pdfLayers.filterNot { it.id == id }
                        },
                        onImportOpenAir = { openAirPicker.launch(arrayOf("text/plain", "*/*")) },
                        onToggleSim = { onToggleSimulation(aircraft.cruiseTasKt) },
                        onToggleGdl90 = onToggleGdl90,
                        onExportTrackGpx = {
                            val gpx = FlightExporter.exportTrackToGpx(recordedTrack)
                            copyToClipboard(context, "Trace GPX", gpx)
                        },
                        onClearTrack = onClearTrack
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // 1. Fond cartographique MapLibre plein écran
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    MapView(ctx).apply {
                        getMapAsync { map ->
                            mapInstance = map
                            map.setStyle("https://tiles.openfreemap.org/styles/liberty") { style ->
                                initMapLayers(style)
                            }
                            map.cameraPosition = CameraPosition.Builder()
                                .target(LatLng(telemetry.latitude, telemetry.longitude))
                                .zoom(10.0)
                                .build()

                            map.addOnMapClickListener { latLng ->
                                val pending = pendingPdfNormPoint
                                when {
                                    isCalibrating && pending != null -> {
                                        val updated = controlPoints + ControlPoint(
                                            pdfX = pending.first,
                                            pdfY = pending.second,
                                            lat = latLng.latitude,
                                            lon = latLng.longitude
                                        )
                                        controlPoints = updated
                                        pendingPdfNormPoint = null
                                        val res = GeoCalibrator.calibrate(updated)
                                        calibrationPreview = res
                                        val bmp = pendingPdfBitmap
                                        if (res != null && bmp != null) {
                                            map.getStyle { st ->
                                                PdfOverlayManager.syncLayersToMap(
                                                    st,
                                                    pdfLayers + CalibratedPdfLayer(
                                                        id = "preview",
                                                        name = pendingPdfName,
                                                        bitmap = bmp,
                                                        quad = res.quad,
                                                        opacity = 0.85f
                                                    )
                                                )
                                            }
                                        }
                                        true
                                    }

                                    isAddingWaypointsOnMap -> {
                                        val idx = waypoints.size + 1
                                        waypoints = waypoints + NavWaypoint(
                                            id = "wpt_${System.currentTimeMillis()}",
                                            code = "WPT$idx",
                                            name = String.format(Locale.US, "%.3f, %.3f", latLng.latitude, latLng.longitude),
                                            position = latLng,
                                            type = WaypointType.CUSTOM,
                                            elevationFt = TerrainEngine.getTerrainElevationFeet(latLng.latitude, latLng.longitude)
                                        )
                                        true
                                    }

                                    else -> {
                                        val stack = AirspaceSpatialEngine.inspectVerticalStack(
                                            latLng, airspaces, ceilingFilterFt
                                        )
                                        inspectedPointStack = latLng to stack
                                        true
                                    }
                                }
                            }
                        }
                    }
                }
            )

            // 2. HUD Supérieur minimaliste (Altitude/Vario, QNH, Vent, Mode affichage)
            if (!isCalibrating) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val varioSign = if (telemetry.verticalSpeedFpm >= 0) "+" else ""
                        TelemetryPill(
                            label = "ALTITUDE AMSL",
                            value = "${telemetry.altitudeAmslFt}",
                            unit = "ft",
                            subValue = "$varioSign${telemetry.verticalSpeedFpm} fpm • AGL $heightAglFt"
                        )

                        TelemetryPill(
                            label = "QNH",
                            value = String.format(Locale.US, "%.0f", telemetry.qnhHpa),
                            unit = "hPa",
                            onClick = if (!screenLocked) ({ showQnhDialog = true }) else null
                        )

                        WindIndicatorPill(
                            windDirDeg = telemetry.estimatedWindDirDeg,
                            windSpeedKt = telemetry.estimatedWindSpeedKt,
                            aircraftTrackDeg = telemetry.gpsTrackDeg
                        )

                        FilledTonalIconButton(
                            onClick = onCycleDisplayMode,
                            enabled = !screenLocked,
                            modifier = Modifier.size(56.dp),
                            shape = CircleShape
                        ) {
                            Icon(
                                imageVector = when (displayMode) {
                                    CockpitDisplayMode.MATERIAL_YOU -> Icons.Rounded.Palette
                                    CockpitDisplayMode.SUNLIGHT -> Icons.Rounded.WbSunny
                                    CockpitDisplayMode.NIGHT_RED -> Icons.Rounded.NightsStay
                                },
                                contentDescription = "Mode affichage"
                            )
                        }
                    }

                    // Alerte proactive espace aérien
                    activeAlerts.firstOrNull()?.let { alert ->
                        AirspaceAlertBanner(alert = alert)
                    }
                }
            }

            // 3. Studio de Calibration PDF Split-Screen
            if (isCalibrating && pendingPdfBitmap != null) {
                val bmp = pendingPdfBitmap!!
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.48f)
                        .statusBarsPadding()
                        .padding(12.dp),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
                    shadowElevation = 8.dp
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (pendingPdfNormPoint == null)
                                        "1. Touchez un repère PDF (${controlPoints.size} pts)"
                                    else
                                        "2. Touchez le même point sur la carte",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                calibrationPreview?.let { res ->
                                    Text(
                                        text = "${res.modelName} • RMSE : ${res.rmseMeters.toInt()} m",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        isCalibrating = false
                                        pendingPdfBitmap = null
                                    },
                                    modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                                ) { Text("Annuler") }

                                Button(
                                    onClick = {
                                        val res = calibrationPreview
                                        if (res != null) {
                                            pdfLayers = pdfLayers + CalibratedPdfLayer(
                                                id = "pdf_${System.currentTimeMillis()}",
                                                name = pendingPdfName,
                                                bitmap = bmp,
                                                quad = res.quad,
                                                opacity = 0.85f,
                                                rmseMeters = res.rmseMeters,
                                                modelName = res.modelName
                                            )
                                        }
                                        isCalibrating = false
                                        pendingPdfBitmap = null
                                    },
                                    enabled = controlPoints.size >= 2,
                                    modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                                ) { Text("Valider") }
                            }
                        }

                        // Curseur détourage marges VAC
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Détourage marges", style = MaterialTheme.typography.labelSmall)
                            Slider(
                                value = pdfCropMargin,
                                onValueChange = { frac ->
                                    pdfCropMargin = frac
                                    pendingPdfUri?.let { uri ->
                                        PdfOverlayManager.renderPdfPageToBitmap(context, uri, 0, 2200, frac)?.let {
                                            pendingPdfBitmap = it
                                        }
                                    }
                                },
                                valueRange = 0f..0.30f,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 8.dp)
                            )
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(Unit) {
                                    detectTapGestures { offset ->
                                        val nx = (offset.x / size.width).toDouble().coerceIn(0.0, 1.0)
                                        val ny = (offset.y / size.height).toDouble().coerceIn(0.0, 1.0)
                                        pendingPdfNormPoint = nx to ny
                                    }
                                }
                        ) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = "PDF à calibrer",
                                contentScale = ContentScale.FillBounds,
                                modifier = Modifier.fillMaxSize()
                            )
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                controlPoints.forEach { pt ->
                                    drawCircle(
                                        color = Color(0xFF00C853),
                                        radius = 12f,
                                        center = Offset(
                                            (pt.pdfX * size.width).toFloat(),
                                            (pt.pdfY * size.height).toFloat()
                                        )
                                    )
                                }
                                pendingPdfNormPoint?.let { (nx, ny) ->
                                    drawCircle(
                                        color = Color(0xFFFF3D00),
                                        radius = 16f,
                                        center = Offset((nx * size.width).toFloat(), (ny * size.height).toFloat())
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 4. Bandeau inférieur flottant (Vitesse/Route + Boutons tactiles >= 56dp)
            if (!isCalibrating) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TelemetryPill(
                        label = if (telemetry.isSimulated) "SIMU GS / TRK" else "GS / TRK",
                        value = "${telemetry.groundSpeedKt}",
                        unit = "kt",
                        subValue = String.format(
                            Locale.US,
                            "TRK %03d° • HDG %03d°",
                            telemetry.gpsTrackDeg,
                            telemetry.magneticHeadingDeg
                        )
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalIconButton(
                            onClick = { pdfPicker.launch(arrayOf("application/pdf")) },
                            enabled = !screenLocked,
                            modifier = Modifier.size(56.dp),
                            shape = CircleShape
                        ) {
                            Icon(Icons.Rounded.PictureAsPdf, contentDescription = "Importer PDF")
                        }

                        FilledTonalIconButton(
                            onClick = { followAircraft = !followAircraft },
                            enabled = !screenLocked,
                            modifier = Modifier.size(56.dp),
                            shape = CircleShape
                        ) {
                            Icon(
                                if (followAircraft) Icons.Rounded.GpsFixed else Icons.Rounded.GpsNotFixed,
                                contentDescription = "Suivi GPS"
                            )
                        }

                        FilledIconButton(
                            onClick = { screenLocked = !screenLocked },
                            modifier = Modifier.size(56.dp),
                            shape = CircleShape
                        ) {
                            Icon(
                                if (screenLocked) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                                contentDescription = "Verrouiller écran"
                            )
                        }
                    }
                }
            }
        }
    }

    // Dialogue Calage QNH
    if (showQnhDialog) {
        QnhPickerDialog(
            currentQnh = telemetry.qnhHpa,
            onDismiss = { showQnhDialog = false },
            onConfirm = {
                onUpdateQnh(it)
                showQnhDialog = false
            }
        )
    }

    // Dialogue Inspection Pile Verticale d'Espaces Aériens au toucher
    inspectedPointStack?.let { (pt, stack) ->
        val localElev = TerrainEngine.getTerrainElevationFeet(pt.latitude, pt.longitude)
        AlertDialog(
            onDismissRequest = { inspectedPointStack = null },
            title = {
                Column {
                    Text("Inspection verticale")
                    Text(
                        text = String.format(Locale.US, "%.4f°N, %.4f°E • Sol %d ft", pt.latitude, pt.longitude, localElev),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            },
            text = {
                if (stack.isEmpty()) {
                    Text("Espace aérien non contrôlé (Classe G) jusqu'au plafond filtré.")
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (space in stack) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer
                            ) {
                                Column(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
                                    Text(
                                        text = "[${space.airspaceClass}] ${space.name}",
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.labelLarge
                                    )
                                    Text(
                                        text = "Limites : ${space.lowerLimit.rawText} -> ${space.upperLimit.rawText}" +
                                                (space.frequencyMhz?.let { " • Fréq : $it MHz" } ?: ""),
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        // Direct-To immédiat vers le point inspecté
                        val here = NavWaypoint(
                            id = "pos_now",
                            code = "POS",
                            name = "Position actuelle",
                            position = LatLng(telemetry.latitude, telemetry.longitude),
                            type = WaypointType.CUSTOM
                        )
                        val dest = NavWaypoint(
                            id = "dct_${System.currentTimeMillis()}",
                            code = "DCT",
                            name = "Direct-To",
                            position = pt,
                            type = WaypointType.CUSTOM,
                            elevationFt = localElev
                        )
                        waypoints = listOf(here, dest)
                        inspectedPointStack = null
                    },
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                ) { Text("Direct-To") }
            },
            dismissButton = {
                TextButton(
                    onClick = { inspectedPointStack = null },
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                ) { Text("Fermer") }
            }
        )
    }
}

// ==============================================================================
// ONGLETS DU TIROIR INFÉRIEUR (BOTTOM SHEET)
// ==============================================================================

@Composable
private fun RouteLogTab(
    waypoints: List<NavWaypoint>,
    legs: List<ComputedLeg>,
    isAddingOnMap: Boolean,
    onToggleAddOnMap: () -> Unit,
    onStampActualTime: (Int) -> Unit,
    onRemoveWaypoint: (Int) -> Unit,
    onExportGpx: () -> Unit,
    onExportFpl: () -> Unit,
    onExportBriefing: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = isAddingOnMap,
                onClick = onToggleAddOnMap,
                label = { Text(if (isAddingOnMap) "Mode ajout actif (touchez carte)" else "+ Point sur carte") }
            )
            AssistChip(onClick = onExportGpx, label = { Text("GPX") })
            AssistChip(onClick = onExportFpl, label = { Text("FPL") })
            AssistChip(onClick = onExportBriefing, label = { Text("Briefing") })
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(legs) { index, leg ->
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1.3f)) {
                            Text(
                                text = "${leg.from.code} -> ${leg.to.code}",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = String.format(Locale.US, "%.1f NM • MSA %d ft", leg.distanceNm, leg.minSafeAltitudeFt),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = String.format(Locale.US, "Cm %03d°", leg.magneticHeadingDeg),
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "Gs ${leg.groundSpeedKt} kt (${leg.eteMinutes} min)",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FilledTonalButton(
                                onClick = { onStampActualTime(index + 1) },
                                contentPadding = PaddingValues(horizontal = 10.dp)
                            ) {
                                Text(
                                    text = leg.to.actualOverflightTimeUtc ?: "HE ${leg.estimatedOverflightTimeUtc}",
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                            IconButton(onClick = { onRemoveWaypoint(index + 1) }) {
                                Icon(Icons.Rounded.DeleteOutline, contentDescription = "Supprimer")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VerticalProfileTab(
    waypoints: List<NavWaypoint>,
    legs: List<ComputedLeg>,
    cruiseAltitudeFt: Int,
    currentAltitudeFt: Int,
    ceilingFilterFt: Int,
    onChangeCruiseAlt: (Int) -> Unit,
    onChangeCeilingFilter: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        VerticalProfileView(
            waypoints = waypoints,
            legs = legs,
            cruiseAltitudeFt = cruiseAltitudeFt,
            currentAltitudeFt = currentAltitudeFt
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Altitude croisière : $cruiseAltitudeFt ft", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { onChangeCruiseAlt(-500) }) { Text("-500 ft") }
                FilledTonalButton(onClick = { onChangeCruiseAlt(500) }) { Text("+500 ft") }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Filtre plafond espaces : ${ceilingFilterFt} ft", style = MaterialTheme.typography.labelMedium)
            Slider(
                value = ceilingFilterFt.toFloat(),
                onValueChange = { onChangeCeilingFilter(it.toInt()) },
                valueRange = 2500f..19500f,
                modifier = Modifier.weight(1f).padding(start = 12.dp)
            )
        }
    }
}

@Composable
private fun WeatherAndNotamTab(
    weatherList: List<DecodedWeather>,
    isLoadingWeather: Boolean,
    onRefreshWeather: () -> Unit,
    rawNotamText: String,
    onNotamTextChange: (String) -> Unit,
    onParseNotams: () -> Unit
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("METAR / TAF décodés", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                FilledTonalButton(onClick = onRefreshWeather) {
                    Text(if (isLoadingWeather) "Chargement..." else "Actualiser")
                }
            }
        }
        items(weatherList) { wx ->
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(wx.station, fontWeight = FontWeight.Bold)
                        Box(
                            modifier = Modifier
                                .background(Color(wx.category.colorHex), RoundedCornerShape(8.dp))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(wx.category.label, color = Color.White, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Text(wx.plainFrenchSummary, style = MaterialTheme.typography.labelMedium)
                    Text(wx.rawMetar, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                }
            }
        }
        item {
            HorizontalDivider()
            Spacer(modifier = Modifier.height(6.dp))
            Text("Parseur géométrique NOTAM / SUP AIP", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = rawNotamText,
                onValueChange = onNotamTextChange,
                modifier = Modifier.fillMaxWidth().height(110.dp),
                textStyle = MaterialTheme.typography.labelSmall
            )
            Spacer(modifier = Modifier.height(6.dp))
            Button(onClick = onParseNotams, modifier = Modifier.fillMaxWidth()) {
                Text("Extraire coordonnées & tracer sur la carte")
            }
        }
    }
}

@Composable
private fun AircraftAndFuelTab(
    aircraft: AircraftProfile,
    loadSheet: LoadSheet,
    fuel: FuelBreakdown,
    wb: WeightBalanceResult,
    showGlideRing: Boolean,
    onToggleGlideRing: (Boolean) -> Unit,
    onUpdateTas: (Int) -> Unit,
    onUpdateFuel: (Double) -> Unit,
    onToggleNightVfr: (Boolean) -> Unit
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Anneau de finesse 3D (MNT + Vent)", style = MaterialTheme.typography.labelLarge)
                Switch(checked = showGlideRing, onCheckedChange = onToggleGlideRing)
            }
        }
        item {
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface) {
                Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${aircraft.typeName} (${aircraft.registration}) • TAS ${aircraft.cruiseTasKt} kt", fontWeight = FontWeight.Bold)
                    Text(
                        String.format(
                            Locale.US,
                            "Roulage %.1fL • Étape %.1fL • Marge %.1fL • Dégag. %.1fL • Réserve %.1fL",
                            fuel.taxiLiters, fuel.tripLiters, fuel.contingencyLiters, fuel.alternateLiters, fuel.finalReserveLiters
                        ),
                        style = MaterialTheme.typography.labelSmall
                    )
                    Text(
                        String.format(
                            Locale.US,
                            "Total requis : %.1f L | Embarqué : %.1f L | Autonomie : %d min",
                            fuel.totalRequiredLiters, fuel.blockFuelLiters, fuel.enduranceMinutes
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (fuel.extraFuelLiters >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Carburant bloc", style = MaterialTheme.typography.labelSmall)
                        Slider(
                            value = loadSheet.blockFuelLiters.toFloat(),
                            onValueChange = { onUpdateFuel(it.toDouble()) },
                            valueRange = 20f..aircraft.usableFuelLiters.toFloat(),
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Réserve VFR Nuit (45 min)", style = MaterialTheme.typography.labelSmall)
                        Spacer(modifier = Modifier.weight(1f))
                        Switch(checked = loadSheet.isNightVfr, onCheckedChange = onToggleNightVfr)
                    }
                }
            }
        }
        item {
            CgEnvelopeView(aircraft = aircraft, wb = wb)
        }
    }
}

@Composable
private fun LayersAndSensorsTab(
    telemetry: FlightTelemetry,
    pdfLayers: List<CalibratedPdfLayer>,
    isGdl90Listening: Boolean,
    trafficCount: Int,
    trackPointsCount: Int,
    onImportPdf: () -> Unit,
    onUpdatePdfOpacity: (String, Float) -> Unit,
    onRemovePdf: (String) -> Unit,
    onImportOpenAir: () -> Unit,
    onToggleSim: () -> Unit,
    onToggleGdl90: () -> Unit,
    onExportTrackGpx: () -> Unit,
    onClearTrack: () -> Unit
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Capteurs & Avionique", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                text = "Baromètre : ${if (telemetry.hasBarometer) "Actif (Kalman)" else "Absent (GPS EGM)"} • " +
                        "Déclinaison WMM : ${String.format(Locale.US, "%.1f°", telemetry.magneticDeclinationDeg)}",
                style = MaterialTheme.typography.labelSmall
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = telemetry.isSimulated,
                    onClick = onToggleSim,
                    label = { Text(if (telemetry.isSimulated) "Simu Vol : ON" else "Simu Vol : OFF") }
                )
                FilterChip(
                    selected = isGdl90Listening,
                    onClick = onToggleGdl90,
                    label = { Text("GDL90 UDP ($trafficCount)") }
                )
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onImportPdf) { Text("+ Calibrer PDF") }
                FilledTonalButton(onClick = onImportOpenAir) { Text("+ OpenAir .txt") }
            }
        }

        items(pdfLayers) { layer ->
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
                Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${layer.name} (RMSE ${layer.rmseMeters.toInt()}m)", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                        IconButton(onClick = { onRemovePdf(layer.id) }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Retirer calque")
                        }
                    }
                    Slider(
                        value = layer.opacity,
                        onValueChange = { onUpdatePdfOpacity(layer.id, it) }
                    )
                }
            }
        }

        item {
            HorizontalDivider()
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Boîte noire GPS ($trackPointsCount pts)", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = onExportTrackGpx, label = { Text("Export GPX") })
                    AssistChip(onClick = onClearTrack, label = { Text("Effacer") })
                }
            }
        }
    }
}

@Composable
private fun QnhPickerDialog(
    currentQnh: Float,
    onDismiss: () -> Unit,
    onConfirm: (Float) -> Unit
) {
    var qnh by remember { mutableFloatStateOf(currentQnh) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Calage altimétrique QNH") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "${qnh.toInt()} hPa",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(onClick = { qnh -= 1f }, modifier = Modifier.size(56.dp)) { Text("-1") }
                    OutlinedButton(onClick = { qnh = 1013.25f }, modifier = Modifier.height(56.dp)) { Text("1013") }
                    FilledTonalButton(onClick = { qnh += 1f }, modifier = Modifier.size(56.dp)) { Text("+1") }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(qnh) }, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) {
                Text("Appliquer")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) {
                Text("Annuler")
            }
        }
    )
}

// ==============================================================================
// PIPELINE MAPLIBRE (COUCHES VECTORIELLES & RASTER)
// ==============================================================================

private fun initMapLayers(style: Style) {
    style.addSource(GeoJsonSource("airspaces-src"))
    style.addLayer(
        FillLayer("airspaces-fill", "airspaces-src").withProperties(
            fillColor(get("fill")),
            fillOpacity(0.28f)
        )
    )
    style.addLayer(
        LineLayer("airspaces-line", "airspaces-src").withProperties(
            lineColor(get("stroke")),
            lineWidth(2.0f)
        )
    )

    style.addSource(GeoJsonSource("glide-src"))
    style.addLayer(
        FillLayer("glide-fill", "glide-src").withProperties(
            fillColor("#00C853"),
            fillOpacity(0.14f)
        )
    )
    style.addLayer(
        LineLayer("glide-line", "glide-src").withProperties(
            lineColor("#00E676"),
            lineWidth(2.2f),
            lineDasharray(arrayOf(3f, 2f))
        )
    )

    style.addSource(GeoJsonSource("route-src"))
    style.addLayer(
        LineLayer("route-line", "route-src").withProperties(
            lineColor("#D500F9"),
            lineWidth(3.5f)
        )
    )
    style.addLayer(
        CircleLayer("route-pts", "route-src").withProperties(
            circleRadius(6.5f),
            circleColor("#D500F9"),
            circleStrokeWidth(2f),
            circleStrokeColor("#FFFFFF")
        )
    )

    style.addSource(GeoJsonSource("vector-src"))
    style.addLayer(
        LineLayer("vector-line", "vector-src").withProperties(
            lineColor("#FF6D00"),
            lineWidth(2.5f)
        )
    )

    style.addSource(GeoJsonSource("traffic-src"))
    style.addLayer(
        CircleLayer("traffic-pts", "traffic-src").withProperties(
            circleRadius(7f),
            circleColor("#FFAB00"),
            circleStrokeWidth(2f),
            circleStrokeColor("#000000")
        )
    )

    style.addSource(GeoJsonSource("aircraft-src"))
    style.addLayer(
        CircleLayer("aircraft-pt", "aircraft-src").withProperties(
            circleRadius(9f),
            circleColor("#00B0FF"),
            circleStrokeWidth(3f),
            circleStrokeColor("#FFFFFF")
        )
    )
}

private fun syncMapLayers(
    style: Style,
    airspaces: List<Airspace>,
    waypoints: List<NavWaypoint>,
    telemetry: FlightTelemetry,
    glideRing: List<LatLng>,
    traffic: List<TrafficTarget>,
    pdfLayers: List<CalibratedPdfLayer>
) {
    PdfOverlayManager.syncLayersToMap(style, pdfLayers)

    // 1. Espaces aériens
    val airspaceFeatures = airspaces.map { space ->
        val pts = space.polygon.map { Point.fromLngLat(it.longitude, it.latitude) }
        val feat = Feature.fromGeometry(Polygon.fromLngLats(listOf(pts)))
        feat.addStringProperty("fill", space.category.strokeHex)
        feat.addStringProperty("stroke", space.category.strokeHex)
        feat
    }
    style.getSourceAs<GeoJsonSource>("airspaces-src")?.setGeoJson(FeatureCollection.fromFeatures(airspaceFeatures))

    // 2. Cône de finesse 3D
    val glideFeatures = if (glideRing.size >= 3) {
        val pts = glideRing.map { Point.fromLngLat(it.longitude, it.latitude) }
        listOf(Feature.fromGeometry(Polygon.fromLngLats(listOf(pts))))
    } else emptyList()
    style.getSourceAs<GeoJsonSource>("glide-src")?.setGeoJson(FeatureCollection.fromFeatures(glideFeatures))

    // 3. Route active
    val routeFeatures = mutableListOf<Feature>()
    if (waypoints.size >= 2) {
        val linePts = waypoints.map { Point.fromLngLat(it.position.longitude, it.position.latitude) }
        routeFeatures.add(Feature.fromGeometry(LineString.fromLngLats(linePts)))
    }
    waypoints.forEach { wp ->
        routeFeatures.add(Feature.fromGeometry(Point.fromLngLat(wp.position.longitude, wp.position.latitude)))
    }
    style.getSourceAs<GeoJsonSource>("route-src")?.setGeoJson(FeatureCollection.fromFeatures(routeFeatures))

    // 4. Vecteur vitesse prédictif 5 minutes
    val acLatLng = LatLng(telemetry.latitude, telemetry.longitude)
    val vecFeatures = if (telemetry.groundSpeedKt > 10) {
        val dist5MinNm = (telemetry.groundSpeedKt / 60.0) * 5.0
        val endPt = OpenAirParser.destinationPoint(acLatLng, telemetry.gpsTrackDeg.toDouble(), dist5MinNm)
        val line = LineString.fromLngLats(
            listOf(
                Point.fromLngLat(acLatLng.longitude, acLatLng.latitude),
                Point.fromLngLat(endPt.longitude, endPt.latitude)
            )
        )
        listOf(Feature.fromGeometry(line))
    } else emptyList()
    style.getSourceAs<GeoJsonSource>("vector-src")?.setGeoJson(FeatureCollection.fromFeatures(vecFeatures))

    // 5. Trafic GDL90
    val trafficFeatures = traffic.map { t ->
        Feature.fromGeometry(Point.fromLngLat(t.longitude, t.latitude))
    }
    style.getSourceAs<GeoJsonSource>("traffic-src")?.setGeoJson(FeatureCollection.fromFeatures(trafficFeatures))

    // 6. Position aéronef
    val acFeature = Feature.fromGeometry(Point.fromLngLat(telemetry.longitude, telemetry.latitude))
    style.getSourceAs<GeoJsonSource>("aircraft-src")?.setGeoJson(FeatureCollection.fromFeatures(listOf(acFeature)))
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cb.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, "$label copié dans le presse-papiers", Toast.LENGTH_SHORT).show()
}
