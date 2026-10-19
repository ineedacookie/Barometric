package com.barometric.app

import com.barometric.app.data.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ProviderTest {
    @Test fun `USU imperial feed preserves tenths of inHg and fixed MST in summer`() {
        val rows = Json.parseToJsonElement("""[{"date_time":"2026-07-01 12:00:00","site_pressure":"25.7","sealevel_pressure":"30.4"},{"date_time":"bad","sealevel_pressure":"30.4"},{"date_time":"2026-07-01 13:00:00","sealevel_pressure":null,"site_pressure":"-9999"}]""").jsonArray
        val result = WeatherApi.parseUsu(rows, "hyrum", "usu-17", 10)
        assertEquals(2, result.size)
        assertEquals(Instant.parse("2026-07-01T19:00:00Z").epochSecond, result.first().timestamp)
        assertEquals(1029.46215466768, result.first { it.kind == PressureKind.SEA_LEVEL }.hpa, 0.000001)
        assertEquals(870.30188733419, result.first { it.kind == PressureKind.STATION }.hpa, 0.000001)
    }

    @Test fun `NOAA slp and altim remain separate and null does not become zero`() {
        val rows = Json.parseToJsonElement("""[{"obsTime":1791571860,"slp":1009.2,"altim":1013},{"obsTime":1791575460,"slp":null,"altim":1014}]""").jsonArray
        val result = WeatherApi.parseNoaa(rows, "hyrum", "noaa-KLGU")
        assertEquals(3, result.size)
        assertEquals(1, result.count { it.kind == PressureKind.SEA_LEVEL })
        assertEquals(1009.2, result.first().hpa, 0.0)
    }

    @Test fun `modeled Unix readings omit null or missing values`() {
        val response = Json.parseToJsonElement("""{"hourly":{"time":[100,200,300,400],"pressure_msl":[1000,null,1005]}}""").jsonObject
        val result = WeatherApi.parseOpenMeteo(response, "hyrum")
        assertEquals(listOf(100L, 300L), result.map { it.timestamp })
        assertEquals("open-meteo", result.first().sourceId)
    }
}
