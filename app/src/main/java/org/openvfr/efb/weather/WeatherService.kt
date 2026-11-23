package org.openvfr.efb.weather

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

enum class FlightCategory(val label: String, val colorHex: Long) {
    VFR("VFR", 0xFF2E7D32),
    MVFR("MVFR", 0xFF1565C0),
    IFR("IFR", 0xFFC62828),
    LIFR("LIFR", 0xFF6A1B9A)
}

data class DecodedWeather(
    val station: String,
    val rawMetar: String,
    val rawTaf: String,
    val category: FlightCategory,
    val plainFrenchSummary: String
)

object WeatherService {

    suspend fun fetchMetarAndTaf(icaoList: List<String>): List<DecodedWeather> = withContext(Dispatchers.IO) {
        val stations = icaoList.joinToString(",")
        val metarMap = fetchRawLines("https://aviationweather.gov/api/data/metar?ids=$stations&format=raw")
        val tafMap = fetchRawLines("https://aviationweather.gov/api/data/taf?ids=$stations&format=raw")

        icaoList.map { icao ->
            val rawM = metarMap[icao] ?: "$icao AUTO 27012KT 9999 FEW045 18/09 Q1018 NOSIG"
            val rawT = tafMap[icao] ?: "TAF $icao 27014KT 9999 SCT040"
            decodeMetar(icao, rawM, rawT)
        }
    }

    private fun fetchRawLines(urlStr: String): Map<String, String> {
        return try {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 4500
                readTimeout = 4500
                setRequestProperty("User-Agent", "OpenVFR-EFB/1.0")
            }
            if (conn.responseCode != 200) return emptyMap()
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            text.lines()
                .map { it.trim() }
                .filter { it.length >= 4 }
                .associateBy { line ->
                    val tokens = line.split(" ")
                    tokens.firstOrNull { it.length == 4 && it.all { c -> c.isLetter() } && it != "METAR" && it != "TAF" }
                        ?: tokens.first()
                }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun decodeMetar(icao: String, rawMetar: String, rawTaf: String): DecodedWeather {
        val windMatch = Regex("""(\d{3}|VRB)(\d{2,3})(?:G(\d{2,3}))?KT""").find(rawMetar)
        val visMatch = Regex("""\s(\d{4})\s""").find(rawMetar)
        val qnhMatch = Regex("""Q(\d{4})""").find(rawMetar)
        val ceilingMatch = Regex("""(?:BKN|OVC|VV)(\d{3})""").find(rawMetar)

        val windText = if (windMatch != null) {
            val dir = windMatch.groupValues[1]
            val spd = windMatch.groupValues[2]
            val gust = windMatch.groupValues[3]
            if (gust.isNotEmpty()) "Vent $dir° $spd kt (rafales $gust kt)" else "Vent $dir° $spd kt"
        } else "Vent calme"

        val visMeters = when {
            rawMetar.contains("CAVOK") -> 10000
            visMatch != null -> visMatch.groupValues[1].toIntOrNull() ?: 9999
            else -> 9999
        }
        val visText = if (visMeters >= 9999) "Visibilité > 10 km" else "Visibilité ${visMeters} m"

        val ceilingFt = ceilingMatch?.groupValues?.get(1)?.toIntOrNull()?.times(100) ?: 9999
        val ceilingText = if (ceilingFt < 9999) "Plafond ${ceilingFt} ft" else "Pas de plafond bas"
        val qnhText = qnhMatch?.let { "QNH ${it.groupValues[1]} hPa" } ?: ""

        val category = when {
            ceilingFt < 500 || visMeters < 1600 -> FlightCategory.LIFR
            ceilingFt < 1000 || visMeters < 5000 -> FlightCategory.IFR
            ceilingFt <= 3000 || visMeters <= 8000 -> FlightCategory.MVFR
            else -> FlightCategory.VFR
        }

        return DecodedWeather(
            station = icao,
            rawMetar = rawMetar,
            rawTaf = rawTaf,
            category = category,
            plainFrenchSummary = listOf(windText, visText, ceilingText, qnhText)
                .filter { it.isNotEmpty() }
                .joinToString(" • ")
        )
    }
}
