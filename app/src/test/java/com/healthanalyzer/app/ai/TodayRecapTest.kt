package com.healthanalyzer.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class TodayRecapTest {

    @Test
    fun parsesOpenMeteoForecast() {
        val body = """
            {"daily": {"time": ["2026-11-09"], "weather_code": [61], "temperature_2m_max": [11.4],
              "temperature_2m_min": [3.6], "precipitation_sum": [2.2], "precipitation_probability_max": [70],
              "wind_speed_10m_max": [18.3], "uv_index_max": [1.5], "sunrise": ["2026-11-09T07:02"], "sunset": ["2026-11-09T16:55"]},
             "hourly": {"time": ["2026-11-09T04:00", "2026-11-09T07:00", "2026-11-09T18:00"],
              "temperature_2m": [3.6, 4.1, 9.0], "apparent_temperature": [0.5, 1.0, 7.2],
              "precipitation_probability": [10, 20, null], "wind_speed_10m": [5.0, 7.9, 12.0],
              "relative_humidity_2m": [90, 88, 70], "weather_code": [3, 2, 63]}}
        """.trimIndent()
        val w = Weather.parseForecast(body, "Toronto, Ontario, Canada")

        assertEquals(LocalDate.of(2026, 11, 9), w.date)
        assertEquals("Light rain · 4–11 °C · rain 70% · wind 18 km/h", w.shortLine())
        assertEquals("16:55", w.sunset)
        assertNull(w.hours[2].precipProb)
        val text = w.promptText()
        assertTrue("min 3.6 °C, max 11.4 °C" in text)
        assertTrue("07:00 | 4.1 | 1 | 20 | 8 | 88 | Partly cloudy" in text)
        assertTrue("18:00 | 9 | 7.2 | – | 12 | 70 | Rain" in text)
        assertTrue("04:00" !in text) // before 05:00 is left out
    }

    @Test
    fun parsesGeocodeAndMissingResults() {
        val p = Weather.parseGeocode(
            """{"results": [{"name": "Toronto", "latitude": 43.70, "longitude": -79.42, "admin1": "Ontario", "country": "Canada"}]}""",
        )!!
        assertEquals("Toronto, Ontario, Canada", p.name)
        assertEquals(-79.42, p.lon, 1e-9)
        assertNull(Weather.parseGeocode("""{"generationtime_ms": 0.1}"""))
    }

    @Test
    fun keyChangesWithDayPlanAndPlace() {
        val day = LocalDate.of(2026, 11, 9)
        val w = listOf(PlanWorkoutEntity(id = 3, date = "2026-11-09", type = "EASY", title = "Easy 6", distanceKm = 6f))
        val place = Place("Toronto", 43.7, -79.4)
        val k = TodayRecap.key(day, w, place)
        assertTrue(k.startsWith("2026-11-09"))
        assertEquals(k, TodayRecap.key(day, w.map { it.copy() }, place))
        assertNotEquals(k, TodayRecap.key(day.plusDays(1), w, place))
        assertNotEquals(k, TodayRecap.key(day, w.map { it.copy(distanceKm = 8f) }, place))
        assertNotEquals(k, TodayRecap.key(day, w, null))
    }

    @Test
    fun promptCoversRestDayAndMissingWeather() {
        val p = TodayRecap.userPrompt(LocalDate.of(2026, 11, 9), emptyList(), "", 20.0, 3.73, null)
        assertTrue("Today is 2026-11-09 (Monday)." in p)
        assertTrue("rest day" in p)
        assertTrue("planned 20.0 km, done 3.7 km" in p)
        assertTrue("No weather forecast available." in p)
    }
}
