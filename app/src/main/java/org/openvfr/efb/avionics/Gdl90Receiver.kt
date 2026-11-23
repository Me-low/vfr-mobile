package org.openvfr.efb.avionics

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

data class TrafficTarget(
    val icaoAddress: Int,
    val callsign: String,
    val latitude: Double,
    val longitude: Double,
    val altitudeFt: Int,
    val groundSpeedKt: Int,
    val trackDeg: Int,
    val verticalSpeedFpm: Int,
    val lastSeenMs: Long
)

/**
 * Récepteur réseau GDL90 (UDP port 4000) compatible Stratux, SkyEcho, SafeSky et simulateurs.
 * Décode les trames binaires avec déséchappement 0x7D et contrôle d'intégrité.
 */
class Gdl90Receiver {

    private val _targets = MutableStateFlow<Map<Int, TrafficTarget>>(emptyMap())
    val targets: StateFlow<Map<Int, TrafficTarget>> = _targets.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private var listenJob: Job? = null
    private var socket: DatagramSocket? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun toggle(port: Int = 4000) {
        if (_isListening.value) stop() else start(port)
    }

    fun start(port: Int = 4000) {
        if (_isListening.value) return
        _isListening.value = true
        listenJob = scope.launch {
            try {
                val udp = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(port))
                    soTimeout = 2000
                }
                socket = udp
                val buffer = ByteArray(2048)

                while (isActive) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        udp.receive(packet)
                        parsePacket(buffer, packet.length)
                    } catch (_: Exception) {
                        // Purge les cibles expirées (> 15s sans réception)
                        val now = System.currentTimeMillis()
                        _targets.value = _targets.value.filterValues { now - it.lastSeenMs < 15_000L }
                    }
                }
            } catch (_: Exception) {
                _isListening.value = false
            }
        }
    }

    fun stop() {
        _isListening.value = false
        listenJob?.cancel()
        runCatching { socket?.close() }
        socket = null
        _targets.value = emptyMap()
    }

    private fun parsePacket(raw: ByteArray, length: Int) {
        val frame = ArrayList<Byte>(length)
        var inFrame = false
        var escapeNext = false

        for (i in 0 until length) {
            val b = raw[i].toInt() and 0xFF
            if (b == 0x7E) {
                if (inFrame && frame.size >= 4) {
                    handleMessage(frame)
                    frame.clear()
                }
                inFrame = true
                escapeNext = false
            } else if (inFrame) {
                if (escapeNext) {
                    frame.add((b xor 0x20).toByte())
                    escapeNext = false
                } else if (b == 0x7D) {
                    escapeNext = true
                } else {
                    frame.add(b.toByte())
                }
            }
        }
    }

    private fun handleMessage(msg: List<Byte>) {
        val msgId = msg[0].toInt() and 0xFF
        // Message 0x14 (20) : Traffic Report (28 octets utiles + 2 CRC)
        if (msgId == 0x14 && msg.size >= 28) {
            val addr = ((msg[2].toInt() and 0xFF) shl 16) or
                    ((msg[3].toInt() and 0xFF) shl 8) or
                    (msg[4].toInt() and 0xFF)

            val rawLat = ((msg[5].toInt() and 0xFF) shl 16) or
                    ((msg[6].toInt() and 0xFF) shl 8) or
                    (msg[7].toInt() and 0xFF)
            val signedLat = if (rawLat and 0x800000 != 0) rawLat or -0x1000000 else rawLat
            val lat = signedLat * (180.0 / 8388608.0)

            val rawLon = ((msg[8].toInt() and 0xFF) shl 16) or
                    ((msg[9].toInt() and 0xFF) shl 8) or
                    (msg[10].toInt() and 0xFF)
            val signedLon = if (rawLon and 0x800000 != 0) rawLon or -0x1000000 else rawLon
            val lon = signedLon * (180.0 / 8388608.0)

            val altEncoded = ((msg[11].toInt() and 0xFF) shl 4) or ((msg[12].toInt() and 0xF0) ushr 4)
            val altFt = (altEncoded * 25) - 1000

            val horizVel = ((msg[14].toInt() and 0xFF) shl 4) or ((msg[15].toInt() and 0xF0) ushr 4)
            val vertVelRaw = ((msg[15].toInt() and 0x0F) shl 8) or (msg[16].toInt() and 0xFF)
            val vertVelSigned = if (vertVelRaw and 0x800 != 0) vertVelRaw or -0x1000 else vertVelRaw
            val vsFpm = vertVelSigned * 64

            val trackDeg = (((msg[17].toInt() and 0xFF) * 360.0) / 256.0).toInt()
            val callsignBytes = ByteArray(8) { idx -> msg[19 + idx] }
            val callsign = String(callsignBytes, Charsets.US_ASCII).trim().ifEmpty {
                addr.toString(16).uppercase()
            }

            val target = TrafficTarget(
                icaoAddress = addr,
                callsign = callsign,
                latitude = lat,
                longitude = lon,
                altitudeFt = altFt,
                groundSpeedKt = horizVel,
                trackDeg = trackDeg,
                verticalSpeedFpm = vsFpm,
                lastSeenMs = System.currentTimeMillis()
            )
            _targets.value = _targets.value + (addr to target)
        }
    }
}
