package org.openvfr.efb.sensors

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.*

data class TrackPoint(
    val timestampMs: Long,
    val latitude: Double,
    val longitude: Double,
    val altitudeAmslFt: Int,
    val groundSpeedKt: Int,
    val trackDeg: Int
)

data class FlightTelemetry(
    val latitude: Double = 45.8295,          // LFJI Marennes par défaut
    val longitude: Double = -1.1167,
    val hasGpsFix: Boolean = false,
    val isSimulated: Boolean = false,
    val altitudeAmslFt: Int = 100,
    val verticalSpeedFpm: Int = 0,
    val groundSpeedKt: Int = 0,
    val magneticHeadingDeg: Int = 340,
    val trueHeadingDeg: Int = 340,
    val gpsTrackDeg: Int = 340,
    val magneticDeclinationDeg: Float = 0.5f,
    val qnhHpa: Float = 1018f,
    val rawPressureHpa: Float? = null,
    val pitchDeg: Float = 0f,
    val rollDeg: Float = 0f,
    val estimatedWindDirDeg: Int = 270,
    val estimatedWindSpeedKt: Int = 12,
    val hasBarometer: Boolean = false
)

class FlightSensorManager(context: Context) : SensorEventListener, LocationListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val kalmanFilter = AltitudeKalmanFilter()

    private val _telemetry = MutableStateFlow(FlightTelemetry())
    val telemetry: StateFlow<FlightTelemetry> = _telemetry.asStateFlow()

    private val _recordedTrack = MutableStateFlow<List<TrackPoint>>(emptyList())
    val recordedTrack: StateFlow<List<TrackPoint>> = _recordedTrack.asStateFlow()

    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    private var lastBaroStdMeters: Double? = null
    private var simJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @SuppressLint("MissingPermission")
    fun start() {
        val baro = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
        if (baro != null) {
            _telemetry.update { it.copy(hasBarometer = true) }
            sensorManager.registerListener(this, baro, SensorManager.SENSOR_DELAY_GAME)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let { rot ->
            sensorManager.registerListener(this, rot, SensorManager.SENSOR_DELAY_GAME)
        }
        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                250L,
                0f,
                this,
                Looper.getMainLooper()
            )
        } catch (_: SecurityException) {
            // Permission gérée au niveau UI
        }
    }

    fun stop() {
        simJob?.cancel()
        sensorManager.unregisterListener(this)
        locationManager.removeUpdates(this)
    }

    fun setQnh(newQnhHpa: Float) {
        val rawP = _telemetry.value.rawPressureHpa
        val stdAlt = lastBaroStdMeters
        if (rawP != null && stdAlt != null) {
            val qnhAltMeters = SensorManager.getAltitude(newQnhHpa, rawP).toDouble()
            kalmanFilter.applyManualQnhOffset(qnhAltMeters, stdAlt)
        }
        _telemetry.update { it.copy(qnhHpa = newQnhHpa) }
    }

    fun clearTrack() {
        _recordedTrack.value = emptyList()
    }

    /**
     * Mode simulateur de vol embarqué pour tester toute l'avionique au sol
     * (départ LFJI Marennes -> survol Île d'Oléron -> La Rochelle LFBH).
     */
    fun toggleSimulation(aircraftTasKt: Int = 105) {
        if (_telemetry.value.isSimulated) {
            simJob?.cancel()
            _telemetry.update { it.copy(isSimulated = false) }
            return
        }
        _telemetry.update {
            it.copy(
                isSimulated = true,
                hasGpsFix = true,
                latitude = 45.8295,
                longitude = -1.1167,
                altitudeAmslFt = 2500,
                groundSpeedKt = aircraftTasKt - 6,
                gpsTrackDeg = 338,
                magneticHeadingDeg = 332,
                trueHeadingDeg = 332,
                estimatedWindDirDeg = 260,
                estimatedWindSpeedKt = 14
            )
        }
        simJob = scope.launch {
            var t = 0.0
            while (isActive) {
                delay(500L)
                t += 0.5
                val current = _telemetry.value
                val trackRad = Math.toRadians(current.gpsTrackDeg.toDouble())
                val speedMs = current.groundSpeedKt * 0.514444
                val dNorth = cos(trackRad) * speedMs * 0.5
                val dEast = sin(trackRad) * speedMs * 0.5

                val newLat = current.latitude + (dNorth / 111320.0)
                val newLon = current.longitude + (dEast / (111320.0 * cos(Math.toRadians(current.latitude))))
                val targetAlt = 2500 + (sin(t * 0.05) * 180).toInt()
                val vs = ((targetAlt - current.altitudeAmslFt) * 120).coerceIn(-600, 600)

                val updated = current.copy(
                    latitude = newLat,
                    longitude = newLon,
                    altitudeAmslFt = targetAlt,
                    verticalSpeedFpm = vs,
                    rollDeg = (sin(t * 0.1) * 4.0).toFloat(),
                    pitchDeg = 1.5f
                )
                _telemetry.value = updated
                appendTrackPoint(updated)
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (_telemetry.value.isSimulated) return

        when (event.sensor.type) {
            Sensor.TYPE_PRESSURE -> {
                val pressureHpa = event.values[0]
                val stdAltMeters = SensorManager.getAltitude(
                    SensorManager.PRESSURE_STANDARD_ATMOSPHERE,
                    pressureHpa
                ).toDouble()
                lastBaroStdMeters = stdAltMeters
                kalmanFilter.updateBarometer(stdAltMeters, event.timestamp)

                _telemetry.update {
                    it.copy(
                        rawPressureHpa = pressureHpa,
                        altitudeAmslFt = kalmanFilter.altitudeFeet,
                        verticalSpeedFpm = kalmanFilter.verticalSpeedFpm
                    )
                }
            }

            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.getOrientation(rotationMatrix, orientationAngles)

                val trueAzimuth = ((Math.toDegrees(orientationAngles[0].toDouble()) + 360.0) % 360.0).toFloat()
                val pitch = Math.toDegrees(orientationAngles[1].toDouble()).toFloat()
                val roll = Math.toDegrees(orientationAngles[2].toDouble()).toFloat()

                val decl = _telemetry.value.magneticDeclinationDeg
                val magHeading = ((trueAzimuth - decl + 360f) % 360f).roundToInt() % 360

                _telemetry.update {
                    it.copy(
                        trueHeadingDeg = trueAzimuth.roundToInt() % 360,
                        magneticHeadingDeg = magHeading,
                        pitchDeg = pitch,
                        rollDeg = roll
                    )
                }
            }
        }
    }

    override fun onLocationChanged(location: Location) {
        if (_telemetry.value.isSimulated) return

        val amslMeters = if (location.hasAltitude()) {
            EgmGeoid.wgs84ToAmslMeters(location.altitude, location.latitude, location.longitude)
        } else 0.0

        if (location.hasAltitude()) {
            val vAcc = if (location.hasVerticalAccuracy()) location.verticalAccuracyMeters else 12f
            kalmanFilter.updateGps(amslMeters, vAcc, location.elapsedRealtimeNanos)
        }

        val geoField = GeomagneticField(
            location.latitude.toFloat(),
            location.longitude.toFloat(),
            amslMeters.toFloat(),
            System.currentTimeMillis()
        )
        val gsKt = if (location.hasSpeed()) (location.speed * 1.94384f).roundToInt() else 0
        val track = if (location.hasBearing() && gsKt > 5) {
            location.bearing.roundToInt() % 360
        } else {
            _telemetry.value.magneticHeadingDeg
        }

        // Estimation temps réel du vecteur vent en vol (si GS > 35 kt) : V_wind = V_tas - V_ground
        var windDir = _telemetry.value.estimatedWindDirDeg
        var windSpd = _telemetry.value.estimatedWindSpeedKt
        if (gsKt > 35) {
            val tasAssumed = gsKt.toDouble()
            val hdgRad = Math.toRadians(_telemetry.value.trueHeadingDeg.toDouble())
            val trkRad = Math.toRadians(track.toDouble())
            val wx = tasAssumed * sin(hdgRad) - gsKt * sin(trkRad)
            val wy = tasAssumed * cos(hdgRad) - gsKt * cos(trkRad)
            windSpd = hypot(wx, wy).roundToInt().coerceIn(0, 99)
            windDir = ((Math.toDegrees(atan2(wx, wy)) + 360.0) % 360.0).roundToInt() % 360
        }

        val updated = _telemetry.value.copy(
            latitude = location.latitude,
            longitude = location.longitude,
            hasGpsFix = true,
            altitudeAmslFt = if (kalmanFilter.isReady) kalmanFilter.altitudeFeet else (amslMeters * 3.28084).roundToInt(),
            verticalSpeedFpm = kalmanFilter.verticalSpeedFpm,
            groundSpeedKt = gsKt,
            gpsTrackDeg = track,
            magneticDeclinationDeg = geoField.declination,
            estimatedWindDirDeg = windDir,
            estimatedWindSpeedKt = windSpd
        )
        _telemetry.value = updated
        if (gsKt > 15) {
            appendTrackPoint(updated)
        }
    }

    private fun appendTrackPoint(t: FlightTelemetry) {
        val currentList = _recordedTrack.value
        val now = System.currentTimeMillis()
        if (currentList.isEmpty() || now - currentList.last().timestampMs >= 2000L) {
            _recordedTrack.value = currentList + TrackPoint(
                timestampMs = now,
                latitude = t.latitude,
                longitude = t.longitude,
                altitudeAmslFt = t.altitudeAmslFt,
                groundSpeedKt = t.groundSpeedKt,
                trackDeg = t.gpsTrackDeg
            )
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
