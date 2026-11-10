package com.healthanalyzer.app.ai

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.LocalDate
import kotlin.math.roundToInt

/** A place resolved from the city typed in Settings. */
data class Place(val name: String, val lat: Double, val lon: Double)

/** One day's forecast; temperatures °C, wind km/h, precipitation mm. */
data class DayWeather(
    val place: String,
    val date: LocalDate,
    val summary: String,
    val tMin: Double?,
    val tMax: Double?,
    val precipMm: Double?,
    val precipProbMax: Int?,
    val windMax: Double?,
    val uvMax: Double?,
    val sunrise: String?,
    val sunset: String?,
    val hours: List<Hour>,
) {
    data class Hour(
        val time: String,
        val temp: Double?,
        val feels: Double?,
        val precipProb: Int?,
        val wind: Double?,
        val humidity: Int?,
        val summary: String,
    )

    /** One line for the Plan tab, e.g. "Partly cloudy · 4–11 °C · rain 30% · wind 18 km/h". */
    fun shortLine(): String = listOfNotNull(
        summary,
        if (tMin != null && tMax != null) "${tMin.roundToInt()}–${tMax.roundToInt()} °C" else null,
        precipProbMax?.let { "rain $it%" },
        windMax?.let { "wind ${it.roundToInt()} km/h" },
    ).joinToString(" · ")

    /** Forecast as prompt text, with hourly rows from 05:00 to 22:00. */
    fun promptText(): String = buildString {
        appendLine("Weather forecast for $place on $date (source: Open-Meteo):")
        appendLine(
            listOfNotNull(
                summary,
                tMin?.let { "min ${fmt(it)} °C" },
                tMax?.let { "max ${fmt(it)} °C" },
                precipMm?.let { "precipitation ${fmt(it)} mm" },
                precipProbMax?.let { "max rain chance $it%" },
                windMax?.let { "max wind ${it.roundToInt()} km/h" },
                uvMax?.let { "UV index max ${fmt(it)}" },
                sunrise?.let { "sunrise $it" },
                sunset?.let { "sunset $it" },
            ).joinToString(", "),
        )
        val rows = hours.filter { it.time >= "05:00" && it.time <= "22:00" }
        if (rows.isNotEmpty()) {
            appendLine("time | °C | feels like | rain % | wind km/h | humidity % | sky")
            rows.forEach { h ->
                appendLine(
                    listOf(
                        h.time, h.temp.fmtOr(), h.feels.fmtOr(), h.precipProb?.toString() ?: "–",
                        h.wind?.roundToInt()?.toString() ?: "–", h.humidity?.toString() ?: "–", h.summary,
                    ).joinToString(" | "),
                )
            }
        }
    }.trimEnd()

    private fun fmt(v: Double) = "%.1f".format(v).removeSuffix(".0")
    private fun Double?.fmtOr() = this?.let { fmt(it) } ?: "–"
}

/** Free Open-Meteo APIs (no key): city search and today's forecast. */
object Weather {

    suspend fun geocode(http: OkHttpClient, query: String): Place? {
        val url = "https://geocoding-api.open-meteo.com/v1/search".toHttpUrl().newBuilder()
            .addQueryParameter("name", query.trim())
            .addQueryParameter("count", "1")
            .addQueryParameter("format", "json")
            .build()
        return parseGeocode(get(http, url.toString()))
    }

    suspend fun today(http: OkHttpClient, place: Place): DayWeather {
        val url = "https://api.open-meteo.com/v1/forecast".toHttpUrl().newBuilder()
            .addQueryParameter("latitude", place.lat.toString())
            .addQueryParameter("longitude", place.lon.toString())
            .addQueryParameter(
                "hourly",
                "temperature_2m,apparent_temperature,precipitation_probability,wind_speed_10m,relative_humidity_2m,weather_code",
            )
            .addQueryParameter(
                "daily",
                "weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum,precipitation_probability_max," +
                    "wind_speed_10m_max,uv_index_max,sunrise,sunset",
            )
            .addQueryParameter("timezone", "auto")
            .addQueryParameter("forecast_days", "1")
            .build()
        return parseForecast(get(http, url.toString()), place.name)
    }

    private suspend fun get(http: OkHttpClient, url: String): String = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IOException("Weather service error ${r.code}")
            body
        }
    }

    fun parseGeocode(body: String): Place? {
        val first = JsonParser.parseString(body).asJsonObject.getAsJsonArray("results")?.firstOrNull()?.asJsonObject
            ?: return null
        val name = listOfNotNull(first.str("name"), first.str("admin1"), first.str("country"))
            .distinct().joinToString(", ")
        return Place(name, first.get("latitude").asDouble, first.get("longitude").asDouble)
    }

    fun parseForecast(body: String, place: String): DayWeather {
        val root = JsonParser.parseString(body).asJsonObject
        val daily = root.getAsJsonObject("daily") ?: throw IOException("Weather reply had no daily forecast")
        fun d(name: String) = daily.getAsJsonArray(name)?.firstOrNull()?.takeUnless { it.isJsonNull }
        val hourly = root.getAsJsonObject("hourly")
        val times = hourly?.getAsJsonArray("time")?.map { it.asString }.orEmpty()
        fun h(name: String, i: Int) = hourly?.getAsJsonArray(name)?.get(i)?.takeUnless { it.isJsonNull }
        val hours = times.indices.map { i ->
            DayWeather.Hour(
                time = times[i].substringAfter('T'),
                temp = h("temperature_2m", i)?.asDouble,
                feels = h("apparent_temperature", i)?.asDouble,
                precipProb = h("precipitation_probability", i)?.asInt,
                wind = h("wind_speed_10m", i)?.asDouble,
                humidity = h("relative_humidity_2m", i)?.asInt,
                summary = describe(h("weather_code", i)?.asInt),
            )
        }
        return DayWeather(
            place = place,
            date = d("time")?.asString?.let { LocalDate.parse(it) } ?: LocalDate.now(),
            summary = describe(d("weather_code")?.asInt),
            tMin = d("temperature_2m_min")?.asDouble,
            tMax = d("temperature_2m_max")?.asDouble,
            precipMm = d("precipitation_sum")?.asDouble,
            precipProbMax = d("precipitation_probability_max")?.asInt,
            windMax = d("wind_speed_10m_max")?.asDouble,
            uvMax = d("uv_index_max")?.asDouble,
            sunrise = d("sunrise")?.asString?.substringAfter('T'),
            sunset = d("sunset")?.asString?.substringAfter('T'),
            hours = hours,
        )
    }

    /** WMO weather code → words. */
    fun describe(code: Int?): String = when (code) {
        null -> "–"
        0 -> "Clear"
        1 -> "Mostly clear"
        2 -> "Partly cloudy"
        3 -> "Overcast"
        45, 48 -> "Fog"
        51, 53, 55 -> "Drizzle"
        56, 57 -> "Freezing drizzle"
        61 -> "Light rain"
        63 -> "Rain"
        65 -> "Heavy rain"
        66, 67 -> "Freezing rain"
        71 -> "Light snow"
        73 -> "Snow"
        75 -> "Heavy snow"
        77 -> "Snow grains"
        80, 81 -> "Rain showers"
        82 -> "Violent rain showers"
        85, 86 -> "Snow showers"
        95 -> "Thunderstorm"
        96, 99 -> "Thunderstorm with hail"
        else -> "Weather code $code"
    }

    private fun JsonObject.str(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
}
