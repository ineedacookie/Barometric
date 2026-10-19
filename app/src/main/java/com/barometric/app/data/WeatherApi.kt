package com.barometric.app.data

import com.barometric.app.domain.Pressure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.math.*

class WeatherApi {
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun get(url: String, params: Map<String, String> = emptyMap()): JsonElement = withContext(Dispatchers.IO) {
        val uri = url.toHttpUrl().newBuilder().apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        client.newCall(Request.Builder().url(uri).header("User-Agent", "Barometric/0.1 (noncommercial Android tracker)").build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Weather service returned HTTP ${response.code}")
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) JsonArray(emptyList()) else json.parseToJsonElement(body)
        }
    }

    suspend fun search(query: String): List<LocationEntity> {
        val root = get("https://geocoding-api.open-meteo.com/v1/search", mapOf("name" to query.trim(), "count" to "10", "language" to "en", "format" to "json")).jsonObject
        return root["results"]?.jsonArray.orEmpty().mapNotNull { row ->
            val o = row.jsonObject
            val timezone = o.text("timezone") ?: return@mapNotNull null
            runCatching { ZoneId.of(timezone) }.getOrNull() ?: return@mapNotNull null
            LocationEntity(
                "geo-${o.text("id")}", o.text("name") ?: return@mapNotNull null,
                listOfNotNull(o.text("admin1"), o.text("country")).distinct().joinToString(", "),
                o.number("latitude") ?: return@mapNotNull null,
                o.number("longitude") ?: return@mapNotNull null, timezone,
            )
        }
    }

    fun discoverUsu(location: LocationEntity): SourceEntity? = knownUsu(location)

    fun knownUsu(location: LocationEntity): SourceEntity? {
        val distance = Pressure.distanceKm(location.latitude, location.longitude, 41.7423, -111.81223)
        return if (distance <= 50) SourceEntity(location.id, "usu-campus", "USU", "USU · Campus Observatory", "1279257", 41.7423, -111.81223, distance, false, 0) else null
    }

    suspend fun discoverNoaa(location: LocationEntity): SourceEntity? {
        val loganDistance = Pressure.distanceKm(location.latitude, location.longitude, 41.787, -111.853)
        if (loganDistance <= 50) return SourceEntity(location.id, "noaa-KLGU", "NOAA", "NOAA · Logan-Cache Airport", "KLGU", 41.787, -111.853, loganDistance, false, 1)
        val latDelta = .46
        val lonDelta = (.46 / cos(Math.toRadians(location.latitude)).absoluteValue.coerceAtLeast(.01)).coerceAtMost(180.0)
        // NOAA bbox order is latitude, longitude, latitude, longitude.
        val south = (location.latitude - latDelta).coerceAtLeast(-90.0)
        val north = (location.latitude + latDelta).coerceAtMost(90.0)
        val west = location.longitude - lonDelta
        val east = location.longitude + lonDelta
        val boxes = when {
            west < -180 -> listOf("$south,${west + 360},$north,180", "$south,-180,$north,$east")
            east > 180 -> listOf("$south,$west,$north,180", "$south,-180,$north,${east - 360}")
            else -> listOf("$south,$west,$north,$east")
        }
        val rows = boxes.flatMap { bbox ->
            get("https://aviationweather.gov/api/data/metar", mapOf("bbox" to bbox, "format" to "json", "hours" to "3")).jsonArray
        }
        return rows.mapNotNull { row ->
            val o = row.jsonObject
            // Select reporting stations with sea-level pressure, not altimeter-only reports.
            o.number("slp") ?: return@mapNotNull null
            val lat = o.number("lat") ?: return@mapNotNull null
            val lon = o.number("lon") ?: return@mapNotNull null
            val id = o.text("icaoId") ?: return@mapNotNull null
            val distance = Pressure.distanceKm(location.latitude, location.longitude, lat, lon)
            if (distance > 50) return@mapNotNull null
            SourceEntity(location.id, "noaa-$id", "NOAA", "NOAA · ${o.text("name") ?: id}", id, lat, lon, distance, false, 1)
        }.minByOrNull { it.distanceKm }
    }

    fun modeled(location: LocationEntity) = SourceEntity(location.id, "open-meteo", "OPEN_METEO", "Open-Meteo · modeled", null,
        location.latitude, location.longitude, 0.0, true, 2)

    suspend fun fetch(source: SourceEntity, location: LocationEntity, start: Instant, end: Instant, fullResolution: Boolean = false): List<PressureReading> = when (source.provider) {
        "USU" -> campus(source, location, start, end)
        "NOAA" -> noaa(source, location, start, end)
        else -> openMeteo(location, start, end)
    }.filter { it.instant >= start && it.instant < end && it.instant <= Instant.now() }
        .distinctBy { Triple(it.sourceId, it.timestamp, it.kind) }

    private suspend fun campus(source: SourceEntity, location: LocationEntity, start: Instant, end: Instant): List<PressureReading> {
        if (end < Instant.now().minus(Duration.ofDays(7))) return emptyList()
        val root = get("https://campusweather1.uwrl.usu.edu/Charts/Get-HC-main.php",
            mapOf("chart" to "59", "units" to "M")).jsonObject
        // Fetch the complete public history; never substitute daily averages for observations.
        return parseCampus(root, location.id, source.id)
    }

    private suspend fun noaa(source: SourceEntity, location: LocationEntity, start: Instant, end: Instant): List<PressureReading> {
        val now = Instant.now()
        val earliest = maxOf(start, now.minus(Duration.ofDays(30)))
        var cursor = Instant.ofEpochSecond(minOf(end.epochSecond, now.epochSecond))
        val result = mutableListOf<PressureReading>()
        while (cursor > earliest) {
            // NOAA caps each query at 400 records. Bound the window and continue before
            // its oldest observation if a busy station still reaches that cap.
            val hours = ceil(Duration.between(earliest, cursor).seconds / 3600.0).toInt().coerceIn(1, 168)
            val windowStart = cursor.minus(Duration.ofHours(hours.toLong()))
            val rows = get("https://aviationweather.gov/api/data/metar", mapOf(
                "ids" to source.stationId.orEmpty(), "format" to "json", "hours" to hours.toString(), "date" to cursor.toString(),
            )).jsonArray
            result += parseNoaa(rows, location.id, source.id)
            val oldest = if (rows.size >= 400) rows.mapNotNull { it.jsonObject["obsTime"]?.jsonPrimitive?.longOrNull }.minOrNull() else null
            val next = oldest?.let { Instant.ofEpochSecond(it).plusSeconds(1) } ?: windowStart
            cursor = if (next < cursor && next > windowStart) next else windowStart
        }
        return result
    }

    private suspend fun openMeteo(location: LocationEntity, start: Instant, end: Instant): List<PressureReading> {
        val recentStart = LocalDate.now(ZoneOffset.UTC).minusDays(6)
        val first = start.atOffset(ZoneOffset.UTC).toLocalDate()
        val last = end.atOffset(ZoneOffset.UTC).toLocalDate()
        val params = mapOf("latitude" to location.latitude.toString(), "longitude" to location.longitude.toString(),
            "hourly" to "pressure_msl", "timezone" to "GMT", "timeformat" to "unixtime")
        val result = mutableListOf<PressureReading>()
        if (first < recentStart) {
            val archive = get("https://archive-api.open-meteo.com/v1/archive", params + mapOf(
                "start_date" to first.toString(), "end_date" to minOf(last, recentStart.minusDays(1)).toString()))
            result += parseOpenMeteo(archive.jsonObject, location.id)
        }
        if (last >= recentStart) {
            val recent = get("https://api.open-meteo.com/v1/forecast", params + mapOf("past_days" to "7", "forecast_days" to "1"))
            result += parseOpenMeteo(recent.jsonObject, location.id)
        }
        return result
    }

    companion object {
        private val usuTimestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        fun parseCampus(root: JsonObject, locationId: String, sourceId: String = "usu-campus"): List<PressureReading> =
            root["series"]?.jsonArray?.firstOrNull()?.jsonObject?.get("data")?.jsonArray.orEmpty().mapNotNull { row ->
                val pair = row as? JsonArray ?: return@mapNotNull null
                val millis = pair.getOrNull(0)?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
                val hpa = pair.getOrNull(1)?.jsonPrimitive?.doubleOrNull?.times(10) ?: return@mapNotNull null
                if (!Pressure.valid(hpa)) null else PressureReading(locationId, sourceId, millis / 1000, hpa, PressureKind.STATION, 3)
            }

        fun parseUsu(rows: List<JsonElement>, locationId: String, sourceId: String, minutes: Int): List<PressureReading> = rows.flatMap { row ->
            val o = row.jsonObject
            val timestamp = runCatching { LocalDateTime.parse(o.text("date_time"), usuTimestamp).toEpochSecond(ZoneOffset.ofHours(-7)) }.getOrNull()
                ?: return@flatMap emptyList()
            listOf("sealevel_pressure" to PressureKind.SEA_LEVEL, "site_pressure" to PressureKind.STATION).mapNotNull { (key, kind) ->
                val value = o.number(key)?.times(Pressure.HPA_PER_INHG) ?: return@mapNotNull null // inHg -> hPa
                if (!Pressure.valid(value)) null else PressureReading(locationId, sourceId, timestamp, value, kind, minutes)
            }
        }

        fun parseNoaa(rows: List<JsonElement>, locationId: String, sourceId: String): List<PressureReading> = rows.flatMap { row ->
            val o = row.jsonObject
            val timestamp = o["obsTime"]?.jsonPrimitive?.longOrNull ?: return@flatMap emptyList()
            listOf("slp" to PressureKind.SEA_LEVEL, "altim" to PressureKind.ALTIMETER).mapNotNull { (key, kind) ->
                val value = o.number(key) ?: return@mapNotNull null
                if (!Pressure.valid(value)) null else PressureReading(locationId, sourceId, timestamp, value, kind, 60)
            }
        }

        fun parseOpenMeteo(root: JsonObject, locationId: String): List<PressureReading> {
            val hourly = root["hourly"]?.jsonObject ?: return emptyList()
            val times = hourly["time"]?.jsonArray.orEmpty()
            val values = hourly["pressure_msl"]?.jsonArray.orEmpty()
            return times.mapIndexedNotNull { i, time ->
                val timestamp = time.jsonPrimitive.longOrNull ?: return@mapIndexedNotNull null
                val value = values.getOrNull(i)?.jsonPrimitive?.doubleOrNull ?: return@mapIndexedNotNull null
                if (!Pressure.valid(value)) null else PressureReading(locationId, "open-meteo", timestamp, value, intervalMinutes = 60)
            }
        }
    }
}

private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
private fun JsonObject.number(key: String): Double? = this[key]?.jsonPrimitive?.doubleOrNull?.takeIf { it.isFinite() }
