package com.jmgo.minimalweatherwidget

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.*

open class WeatherWidgetProvider : AppWidgetProvider() {

    companion object {
        // Fuerza una descarga nueva de datos (ignora la caché) y actualiza los widgets
        const val ACTION_REFRESH = "com.jmgo.minimalweatherwidget.ACTION_REFRESH"
        // La envía la alarma automática cada 30 minutos
        const val ACTION_AUTO_REFRESH = "com.jmgo.minimalweatherwidget.ACTION_AUTO_REFRESH"

        // Extra que se añade al abrir la app desde el widget
        const val EXTRA_REFRESH_FROM_WIDGET = "refresh_from_widget"

        private const val CACHE_VERSION = 17
        // 29 minutos para que la alarma de 30 minutos siempre encuentre la caché caducada
        private const val CACHE_MILLIS = 29 * 60 * 1000L
        // Evita que el widget oscuro y el claro descarguen datos a la vez
        private val updateLock = Any()

        fun getAemetApiKey(context: Context): String {
            val prefs = context.getSharedPreferences("minimal_widget_prefs", Context.MODE_PRIVATE)
            return prefs.getString("user_aemet_api_key", "")?.trim() ?: ""
        }

        val DEFAULT_RECENT_TOWNS = listOf(
            KnownTown("Medina Sidonia", "11023", 36.4578, -5.9272),
            KnownTown("San Fernando", "11031", 36.4646, -6.1969),
            KnownTown("Cádiz", "11012", 36.5271, -6.2886),
            KnownTown("Chiclana de la Frontera", "11015", 36.4194, -6.1494),
            KnownTown("Puerto Real", "11027", 36.5282, -6.1901)
        )

        /**
         * Pide a los widgets (oscuro y claro) que descarguen datos nuevos y se redibujen.
         * Se puede llamar desde la app (cambio de ubicación, al abrirla, etc.).
         */
        fun requestRefresh(context: Context) {
            val intent = Intent(context, WeatherWidgetProvider::class.java).apply {
                action = ACTION_REFRESH
            }
            context.sendBroadcast(intent)
        }

        fun getRecentTowns(context: Context): MutableList<KnownTown> {
            val prefs = context.getSharedPreferences("minimal_widget_prefs", Context.MODE_PRIVATE)
            val jsonStr = prefs.getString("recent_towns_json", null)
            if (jsonStr.isNullOrEmpty()) {
                return DEFAULT_RECENT_TOWNS.toMutableList()
            }
            return try {
                val arr = JSONArray(jsonStr)
                val list = mutableListOf<KnownTown>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    list.add(
                        KnownTown(
                            name = o.getString("name"),
                            code = o.optString("code", ""),
                            lat = o.getDouble("lat"),
                            lon = o.getDouble("lon")
                        )
                    )
                }
                if (list.isEmpty()) DEFAULT_RECENT_TOWNS.toMutableList() else list
            } catch (e: Exception) {
                DEFAULT_RECENT_TOWNS.toMutableList()
            }
        }

        fun saveRecentTown(context: Context, town: KnownTown) {
            val list = getRecentTowns(context)
            list.removeAll { it.name.equals(town.name, ignoreCase = true) }
            list.add(0, town)
            val trimmed = list.take(10)
            val arr = JSONArray()
            for (t in trimmed) {
                val o = JSONObject().apply {
                    put("name", t.name)
                    put("code", t.code)
                    put("lat", t.lat)
                    put("lon", t.lon)
                }
                arr.put(o)
            }
            context.getSharedPreferences("minimal_widget_prefs", Context.MODE_PRIVATE)
                .edit()
                .putString("recent_towns_json", arr.toString())
                .apply()
        }

        fun findAemetMunicipioCode(context: Context, townName: String): String {
            val cleanTarget = normalizeText(townName)
            for (t in DEFAULT_RECENT_TOWNS) {
                if (normalizeText(t.name) == cleanTarget) return t.code
            }

            val prefs = context.getSharedPreferences("minimal_master_cache", Context.MODE_PRIVATE)
            var masterJsonStr = prefs.getString("municipios_json", null)
            val apiKey = getAemetApiKey(context)

            if (masterJsonStr == null && apiKey.isNotBlank()) {
                try {
                    val url = "https://opendata.aemet.es/opendata/api/maestro/municipios/?api_key=${apiKey.trim()}"
                    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 8000
                        readTimeout = 8000
                    }
                    if (conn.responseCode == 200) {
                        val resp = conn.inputStream.bufferedReader(Charset.forName("ISO-8859-15")).use { it.readText() }
                        val trimmedResp = resp.trim()
                        if (trimmedResp.startsWith("[")) {
                            val fullArr = JSONArray(trimmedResp)
                            val compactArr = JSONArray()
                            for (i in 0 until fullArr.length()) {
                                val item = fullArr.getJSONObject(i)
                                val id = item.optString("id", "").replace("id", "")
                                val nombre = item.optString("nombre", "")
                                if (id.length == 5 && nombre.isNotEmpty()) {
                                    compactArr.put(JSONObject().apply {
                                        put("id", id)
                                        put("n", nombre)
                                    })
                                }
                            }
                            masterJsonStr = compactArr.toString()
                            prefs.edit().putString("municipios_json", masterJsonStr).apply()
                        } else if (trimmedResp.startsWith("{")) {
                            val step1 = JSONObject(trimmedResp)
                            val datosUrl = step1.optString("datos", "")
                            if (datosUrl.isNotEmpty()) {
                                val conn2 = (URL(datosUrl).openConnection() as HttpURLConnection).apply {
                                    requestMethod = "GET"
                                    connectTimeout = 8000
                                    readTimeout = 8000
                                }
                                if (conn2.responseCode == 200) {
                                    val resp2 = conn2.inputStream.bufferedReader(Charset.forName("ISO-8859-15")).use { it.readText() }
                                    val fullArr = JSONArray(resp2)
                                    val compactArr = JSONArray()
                                    for (i in 0 until fullArr.length()) {
                                        val item = fullArr.getJSONObject(i)
                                        val id = item.optString("id", "").replace("id", "")
                                        val nombre = item.optString("nombre", "")
                                        if (id.length == 5 && nombre.isNotEmpty()) {
                                            compactArr.put(JSONObject().apply {
                                                put("id", id)
                                                put("n", nombre)
                                            })
                                        }
                                    }
                                    masterJsonStr = compactArr.toString()
                                    prefs.edit().putString("municipios_json", masterJsonStr).apply()
                                }
                            }
                        }
                    }
                } catch (_: Exception) {}
            }

            if (!masterJsonStr.isNullOrEmpty()) {
                try {
                    val arr = JSONArray(masterJsonStr)
                    var partialMatch = ""
                    for (i in 0 until arr.length()) {
                        val item = arr.getJSONObject(i)
                        val n = normalizeText(item.getString("n"))
                        val id = item.getString("id")
                        if (n == cleanTarget) return id
                        if (partialMatch.isEmpty() && (n.contains(cleanTarget) || cleanTarget.contains(n))) {
                            partialMatch = id
                        }
                    }
                    if (partialMatch.isNotEmpty()) return partialMatch
                } catch (_: Exception) {}
            }
            return ""
        }

        private fun normalizeText(input: String): String {
            return java.text.Normalizer.normalize(input.lowercase(Locale("es", "ES")).trim(), java.text.Normalizer.Form.NFD)
                .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        }
    }

    data class KnownTown(val name: String, val code: String, val lat: Double, val lon: Double)
    data class LocationData(val lat: Double, val lon: Double, val cityName: String, val municipioCode: String)
    data class DayForecast(
        val dayName: String,
        val icon: String,
        val windText: String,
        val windSpeedVal: Int,
        val maxTemp: String,
        val minTemp: String,
        val rainStr: String,
        val hasGoodMoonPhoto: Boolean,
        val uvMax: Int = 0,
        val uvStart: String = "",
        val uvEnd: String = ""
    )

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        scheduleAutoRefresh(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        // Solo cancelamos la alarma si ya no queda ningún widget (ni oscuro ni claro)
        if (!hasAnyWidgets(context)) {
            cancelAutoRefresh(context)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH || intent.action == ACTION_AUTO_REFRESH) {
            // Invalida la caché para obligar a descargar datos nuevos
            context.getSharedPreferences("minimal_widget_prefs", Context.MODE_PRIVATE)
                .edit()
                .putLong("last_fetch_time", 0L)
                .apply()

            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, WeatherWidgetProvider::class.java))
            onUpdate(context, manager, ids)
        }
    }

    // Este método pinta SIEMPRE los dos widgets (oscuro y claro), sea cual sea el que lo llame
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        // Asegura que la alarma de cada 30 minutos sigue programada (por ejemplo, tras reiniciar el móvil)
        scheduleAutoRefresh(context)

        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                synchronized(updateLock) {
                    val prefs = context.getSharedPreferences("minimal_widget_prefs", Context.MODE_PRIVATE)
                    val savedVersion = prefs.getInt("cache_version", 1)
                    val savedDate = prefs.getString("cached_date", "")
                    val currentDateStr = java.time.LocalDate.now().toString()
                    val isSameDay = (savedDate == currentDateStr)
                    val lastFetch = if (savedVersion < CACHE_VERSION) 0L else prefs.getLong("last_fetch_time", 0L)

                    val currentTime = System.currentTimeMillis()
                    val isCacheValid = isSameDay && (currentTime - lastFetch) < CACHE_MILLIS

                    val locInfo = resolveLocationAndMunicipio(context)
                    val daysData = mutableListOf<DayForecast>()
                    var currentTemp = prefs.getString("cached_temp", "--") ?: "--"
                    var currentUv = prefs.getInt("cached_current_uv", 0)
                    var updateTimeStr = prefs.getString("cached_widget_update_time", "--:--") ?: "--:--"
                    var anyGoodMoonPhoto = false

                    if (!isCacheValid) {
                        val openMeteoData = fetchOpenMeteoFallbackAndCurrent(locInfo.lat, locInfo.lon)
                        val openMeteoDays = openMeteoData.second
                        currentUv = openMeteoData.third
                        updateTimeStr = java.time.LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))

                        var fetchedDays = fetchAemetForecast(context, locInfo.municipioCode, locInfo.lat, locInfo.lon)

                        if (fetchedDays.isEmpty() && openMeteoDays.isNotEmpty()) {
                            fetchedDays = openMeteoDays
                        } else if (fetchedDays.isNotEmpty() && openMeteoDays.isNotEmpty()) {
                            // AEMET no trae el UV (y la predicción diaria de AEMET suele usar probabilidad % en lugar de mm): los combinamos con Open-Meteo
                            fetchedDays = fetchedDays.mapIndexed { index, d ->
                                val omDay = openMeteoDays.firstOrNull { it.dayName == d.dayName }
                                    ?: openMeteoDays.getOrNull(index)
                                d.copy(
                                    rainStr = if (d.rainStr.isEmpty()) (omDay?.rainStr ?: "") else d.rainStr,
                                    uvMax = omDay?.uvMax ?: 0,
                                    uvStart = omDay?.uvStart ?: "",
                                    uvEnd = omDay?.uvEnd ?: ""
                                )
                            }
                        }

                        if (fetchedDays.isNotEmpty()) {
                            daysData.addAll(fetchedDays)
                            currentTemp = openMeteoData.first ?: fetchedDays.firstOrNull()?.maxTemp ?: "--"
                            anyGoodMoonPhoto = daysData.any { it.hasGoodMoonPhoto }

                            val editor = prefs.edit()
                            editor.putInt("cache_version", CACHE_VERSION)
                            editor.putLong("last_fetch_time", currentTime)
                            editor.putString("cached_date", currentDateStr)
                            editor.putString("cached_temp", currentTemp)
                            editor.putInt("cached_current_uv", currentUv)
                            editor.putString("cached_widget_update_time", updateTimeStr)
                            editor.putBoolean("cached_moon_photo", anyGoodMoonPhoto)

                            for (i in 0 until minOf(4, fetchedDays.size)) {
                                val d = fetchedDays[i]
                                editor.putString("c_day_$i", d.dayName)
                                editor.putString("c_icon_$i", d.icon)
                                editor.putString("c_wind_$i", d.windText)
                                editor.putInt("c_wind_speed_$i", d.windSpeedVal)
                                editor.putString("c_temp_$i", "${d.maxTemp} / ${d.minTemp}")
                                editor.putString("c_rain_$i", d.rainStr)
                                editor.putInt("c_uv_$i", d.uvMax)
                                editor.putString("c_uv_start_$i", d.uvStart)
                                editor.putString("c_uv_end_$i", d.uvEnd)
                            }
                            editor.apply()
                        }
                    }

                    if (daysData.isEmpty()) {
                        anyGoodMoonPhoto = prefs.getBoolean("cached_moon_photo", false)
                        currentUv = prefs.getInt("cached_current_uv", 0)
                        updateTimeStr = prefs.getString("cached_widget_update_time", "--:--") ?: "--:--"

                        for (i in 0 until 4) {
                            val dName = prefs.getString("c_day_$i", "") ?: ""
                            val savedTemp = prefs.getString("c_temp_$i", "") ?: ""
                            val parts = savedTemp.split("/")
                            val maxT = parts.getOrNull(0)?.trim() ?: ""
                            val minT = parts.getOrNull(1)?.trim() ?: ""
                            val rawWind = prefs.getString("c_wind_$i", "") ?: ""
                            val savedWindSpeed = prefs.getInt("c_wind_speed_$i", 0)

                            if (dName.isNotEmpty()) {
                                daysData.add(
                                    DayForecast(
                                        dayName = dName,
                                        icon = prefs.getString("c_icon_$i", "⛅") ?: "⛅",
                                        windText = sanitizeWidgetWind(rawWind),
                                        windSpeedVal = savedWindSpeed,
                                        maxTemp = maxT,
                                        minTemp = minT,
                                        rainStr = prefs.getString("c_rain_$i", "") ?: "",
                                        hasGoodMoonPhoto = false,
                                        uvMax = prefs.getInt("c_uv_$i", 0),
                                        uvStart = prefs.getString("c_uv_start_$i", "") ?: "",
                                        uvEnd = prefs.getString("c_uv_end_$i", "") ?: ""
                                    )
                                )
                            }
                        }
                    }

                    // Obtenemos avisos AEMET / meteorológicos para resaltar la línea por día individual
                    var multiDayWarnings: List<WeatherNotificationHelper.AemetDayWarning> = emptyList()
                    try {
                        val widgetDailySummaries = daysData.mapIndexed { index, d ->
                            val date = java.time.LocalDate.now().plusDays(index.toLong())
                            val fullLabel = when (index) {
                                0 -> "Hoy"
                                1 -> "Mañana"
                                else -> "${d.dayName} (${date.dayOfMonth}/${date.monthValue})"
                            }
                            MainActivity.DailySummary(
                                date = date,
                                dayName = d.dayName,
                                fullDayLabel = fullLabel,
                                icon = d.icon,
                                windText = d.windText,
                                windSpeedVal = d.windSpeedVal,
                                maxTemp = d.maxTemp,
                                minTemp = d.minTemp,
                                rainStr = d.rainStr,
                                uvStart = d.uvStart,
                                uvEnd = d.uvEnd
                            )
                        }

                        multiDayWarnings = WeatherNotificationHelper.fetchAemetMultiDayWarnings(
                            context, locInfo.municipioCode, locInfo.lat, locInfo.lon, widgetDailySummaries
                        )
                    } catch (e: Exception) {
                        // Fallback silencioso
                    }

                    // Pintamos los dos widgets con los mismos datos
                    val darkIds = appWidgetManager.getAppWidgetIds(
                        ComponentName(context, WeatherWidgetProvider::class.java)
                    )
                    val lightIds = appWidgetManager.getAppWidgetIds(
                        ComponentName(context, LightWeatherWidgetProvider::class.java)
                    )

                    applyToWidgets(
                        context, appWidgetManager, darkIds, R.layout.widget_weather,
                        locInfo.cityName, currentTemp, currentUv, anyGoodMoonPhoto, updateTimeStr, daysData, multiDayWarnings
                    )
                    applyToWidgets(
                        context, appWidgetManager, lightIds, R.layout.widget_weather_light,
                        locInfo.cityName, currentTemp, currentUv, anyGoodMoonPhoto, updateTimeStr, daysData, multiDayWarnings
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun getWarningLevelForDay(
        dayIndex: Int,
        dayForecast: DayForecast,
        multiDayWarnings: List<WeatherNotificationHelper.AemetDayWarning>
    ): String? {
        var highestLevel: String? = null

        fun updateIfHigher(level: String) {
            fun levelScore(l: String) = when (l.uppercase()) {
                "ROJO" -> 3
                "NARANJA" -> 2
                "AMARILLO" -> 1
                else -> 0
            }
            if (levelScore(level) > levelScore(highestLevel ?: "")) {
                highestLevel = level.uppercase()
            }
        }

        val targetDate = java.time.LocalDate.now().plusDays(dayIndex.toLong())

        for (w in multiDayWarnings) {
            if (w.levelColor == "VERDE") continue

            val matchesDay = if (w.date != null) {
                w.date == targetDate
            } else {
                when (dayIndex) {
                    0 -> w.dayLabel.startsWith("Hoy", ignoreCase = true)
                    1 -> w.dayLabel.startsWith("Mañana", ignoreCase = true)
                    else -> w.dayLabel.contains("(${targetDate.dayOfMonth}/${targetDate.monthValue})")
                }
            }

            if (matchesDay) {
                updateIfHigher(w.levelColor)
            }
        }

        return highestLevel
    }

    private fun applyToWidgets(
        context: Context,
        appWidgetManager: AppWidgetManager,
        ids: IntArray,
        layoutRes: Int,
        cityName: String,
        currentTemp: String,
        currentUv: Int,
        anyGoodMoonPhoto: Boolean,
        updateTimeStr: String,
        daysData: List<DayForecast>,
        multiDayWarnings: List<WeatherNotificationHelper.AemetDayWarning> = emptyList()
    ) {
        if (ids.isEmpty()) return

        val isDark = (layoutRes == R.layout.widget_weather)
        val rootBg = if (isDark) R.drawable.bg_widget else R.drawable.bg_widget_light

        val cardIds = listOf(R.id.card0, R.id.card1, R.id.card2, R.id.card3)
        val alertBellIds = listOf(R.id.alertBell0, R.id.alertBell1, R.id.alertBell2, R.id.alertBell3)
        val iconIds = listOf(R.id.icon0, R.id.icon1, R.id.icon2, R.id.icon3)
        val dayIds = listOf(R.id.day0, R.id.day1, R.id.day2, R.id.day3)
        val windIds = listOf(R.id.wind0, R.id.wind1, R.id.wind2, R.id.wind3)
        val tempIds = listOf(R.id.temp0, R.id.temp1, R.id.temp2, R.id.temp3)
        val rainIds = listOf(R.id.rain0, R.id.rain1, R.id.rain2, R.id.rain3)
        val uvStartIds = listOf(R.id.uvStart0, R.id.uvStart1, R.id.uvStart2, R.id.uvStart3)
        val uvEndIds = listOf(R.id.uvEnd0, R.id.uvEnd1, R.id.uvEnd2, R.id.uvEnd3)
        val uvIconIds = listOf(R.id.uvIcon0, R.id.uvIcon1, R.id.uvIcon2, R.id.uvIcon3)

        ids.forEach { appWidgetId ->
            val views = RemoteViews(context.packageName, layoutRes)
            views.setInt(R.id.widgetRoot, "setBackgroundResource", rootBg)

            // Al pulsar el widget se abre la app y se fuerza la actualización de todo
            val openAppIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_REFRESH_FROM_WIDGET, true)
            }
            val openAppPendingIntent = PendingIntent.getActivity(
                context, 0, openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widgetRoot, openAppPendingIntent)

            // Ubicación actual + Temp actual + UV actual (si UV >= 3)
            val uvHeaderStr = if (currentUv >= 3) "  UV$currentUv" else ""
            views.setTextViewText(R.id.tvHeaderCityTemp, "📍 $cityName $currentTemp°$uvHeaderStr")

            // Indicador Foto Óptima Luna + Hora de actualización del widget
            val moonStr = if (anyGoodMoonPhoto) "📷 🌕  " else ""
            val displayTime = if (updateTimeStr == "--:--") "" else updateTimeStr
            views.setTextViewText(R.id.tvUpdateTime, "$moonStr$displayTime".trim())

            if (daysData.isNotEmpty()) {
                val isDark = (layoutRes == R.layout.widget_weather)
                for (i in 0 until minOf(4, daysData.size)) {
                    val d = daysData[i]
                    views.setTextViewText(iconIds[i], d.icon)
                    views.setTextViewText(dayIds[i], d.dayName)

                    val warningLevel = getWarningLevelForDay(i, d, multiDayWarnings)

                    if (warningLevel != null) {
                        val bellColor = when (warningLevel) {
                            "ROJO" -> if (isDark) Color.parseColor("#FF453A") else Color.parseColor("#DC2626")
                            "NARANJA" -> if (isDark) Color.parseColor("#FF9F0A") else Color.parseColor("#DD6B20")
                            "AMARILLO" -> if (isDark) Color.parseColor("#FFD60A") else Color.parseColor("#D69E2E")
                            else -> 0
                        }
                        if (bellColor != 0) {
                            views.setViewVisibility(alertBellIds[i], android.view.View.VISIBLE)
                            views.setTextViewText(alertBellIds[i], "🔔")
                            views.setTextColor(alertBellIds[i], bellColor)
                        } else {
                            views.setViewVisibility(alertBellIds[i], android.view.View.GONE)
                        }
                    } else {
                        views.setViewVisibility(alertBellIds[i], android.view.View.GONE)
                    }

                    val isWeekend = (d.dayName == "S" || d.dayName == "D")
                    if (isWeekend) {
                        val bgRes = if (isDark) R.drawable.bg_weekend_dark else R.drawable.bg_weekend_light
                        views.setInt(cardIds[i], "setBackgroundResource", bgRes)
                        val dayColor = if (isDark) Color.parseColor("#ED8936") else Color.parseColor("#DD6B20")
                        views.setTextColor(dayIds[i], dayColor)
                    } else {
                        views.setInt(cardIds[i], "setBackgroundResource", 0)
                        val dayColor = if (isDark) Color.parseColor("#FFD700") else Color.parseColor("#D69E2E")
                        views.setTextColor(dayIds[i], dayColor)
                    }

                    val windSpeed = if (d.windSpeedVal > 0) d.windSpeedVal else {
                        d.windText.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 0
                    }
                    val isWindHigh = windSpeed >= 30
                    val windColor = if (isWindHigh) {
                        if (isDark) Color.parseColor("#FF453A") else Color.parseColor("#E53E3E")
                    } else {
                        if (isDark) Color.parseColor("#B0C4DE") else Color.parseColor("#4A5568")
                    }

                    views.setTextViewText(windIds[i], sanitizeWidgetWind(d.windText))
                    views.setTextColor(windIds[i], windColor)
                    if (d.maxTemp.isNotEmpty()) {
                        val maxVal = d.maxTemp.toIntOrNull() ?: 0
                        val minVal = d.minTemp.toIntOrNull() ?: 99

                        val tempColor = when {
                            maxVal >= 30 -> if (isDark) Color.parseColor("#FF453A") else Color.parseColor("#E53E3E")
                            minVal <= 10 -> if (isDark) Color.parseColor("#63B3ED") else Color.parseColor("#3182CE")
                            else -> if (isDark) Color.parseColor("#FFFFFF") else Color.parseColor("#1A202C")
                        }

                        views.setTextViewText(tempIds[i], "${d.maxTemp} / ${d.minTemp}")
                        views.setTextColor(tempIds[i], tempColor)
                    }
                    views.setTextViewText(rainIds[i], d.rainStr)
                    views.setTextViewText(uvStartIds[i], d.uvStart)
                    views.setTextViewText(uvEndIds[i], d.uvEnd)
                    views.setTextViewText(uvIconIds[i], if (d.uvStart.isNotEmpty()) "☀️" else "")
                }
            }

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }

    private fun hasAnyWidgets(context: Context): Boolean {
        val manager = AppWidgetManager.getInstance(context)
        val darkIds = manager.getAppWidgetIds(ComponentName(context, WeatherWidgetProvider::class.java))
        val lightIds = manager.getAppWidgetIds(ComponentName(context, LightWeatherWidgetProvider::class.java))
        return darkIds.isNotEmpty() || lightIds.isNotEmpty()
    }

    private fun autoRefreshPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, WeatherWidgetProvider::class.java).apply {
            action = ACTION_AUTO_REFRESH
        }
        return PendingIntent.getBroadcast(
            context, 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun scheduleAutoRefresh(context: Context) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + AlarmManager.INTERVAL_HALF_HOUR,
                AlarmManager.INTERVAL_HALF_HOUR,
                autoRefreshPendingIntent(context)
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun cancelAutoRefresh(context: Context) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.cancel(autoRefreshPendingIntent(context))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun sanitizeWidgetWind(raw: String): String {
        return raw.replace("\n", " ")
            .replace("km/h", "", ignoreCase = true)
            .replace("|", "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun safeGetDouble(prefs: android.content.SharedPreferences, key: String, defaultVal: Double): Double {
        return try {
            prefs.getFloat(key, defaultVal.toFloat()).toDouble()
        } catch (_: Exception) {
            try {
                prefs.getString(key, null)?.toDoubleOrNull() ?: defaultVal
            } catch (_: Exception) {
                defaultVal
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun resolveLocationAndMunicipio(context: Context): LocationData {
        val prefs = context.getSharedPreferences("minimal_widget_prefs", Context.MODE_PRIVATE)
        val isManual = try { prefs.getBoolean("manual_location", false) } catch (_: Exception) { false }
        val savedCode = try { prefs.getString("code", "11023") ?: "11023" } catch (_: Exception) { "11023" }
        val savedCity = try { prefs.getString("city", "Medina Sidonia") ?: "Medina Sidonia" } catch (_: Exception) { "Medina Sidonia" }
        val savedLat = safeGetDouble(prefs, "lat", 36.4578)
        val savedLon = safeGetDouble(prefs, "lon", -5.9272)

        if (isManual) {
            return LocationData(savedLat, savedLon, savedCity, savedCode)
        }

        val hasPerm = try {
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                    context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }

        if (!hasPerm) return LocationData(savedLat, savedLon, savedCity, savedCode)

        var bestLoc: Location? = null
        try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            if (lm != null) {
                for (provider in lm.getProviders(true)) {
                    val l = try { lm.getLastKnownLocation(provider) } catch (_: Exception) { null } ?: continue
                    if (bestLoc == null || l.time > bestLoc.time) {
                        bestLoc = l
                    }
                }
            }
        } catch (_: Exception) {}

        if (bestLoc == null) return LocationData(savedLat, savedLon, savedCity, savedCode)

        val lat = bestLoc.latitude
        val lon = bestLoc.longitude

        val detectedCity = getCityFromGps(context, lat, lon) ?: savedCity
        val resolvedCode = findAemetMunicipioCode(context, detectedCity).ifEmpty { savedCode }

        prefs.edit()
            .putString("city", detectedCity)
            .putString("code", resolvedCode)
            .putFloat("lat", lat.toFloat())
            .putFloat("lon", lon.toFloat())
            .apply()

        return LocationData(lat, lon, detectedCity, resolvedCode)
    }

    private fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(a))
    }

    @Suppress("DEPRECATION")
    private fun getCityFromGps(context: Context, lat: Double, lon: Double): String? {
        // 1. Android Geocoder principal
        try {
            val geocoder = Geocoder(context, Locale("es", "ES"))
            val addr = geocoder.getFromLocation(lat, lon, 1)?.firstOrNull()
            val name = addr?.locality ?: addr?.subLocality ?: addr?.subAdminArea
            if (!name.isNullOrBlank()) return name.trim()
        } catch (_: Exception) {}

        // 2. Respaldo directo con la API de Geocodificación Inversa de Open-Meteo
        try {
            val url = "https://geocoding-api.open-meteo.com/v1/reverse?latitude=$lat&longitude=$lon&count=1&language=es"
            val resp = httpGet(url, Charset.forName("UTF-8"))
            if (resp != null) {
                val json = JSONObject(resp)
                val results = json.optJSONArray("results")
                if (results != null && results.length() > 0) {
                    val r = results.getJSONObject(0)
                    val name = r.optString("name", "")
                    if (name.isNotBlank()) return name.trim()
                }
            }
        } catch (_: Exception) {}

        return null
    }

    fun fetchAemetForecast(context: Context, municipioCode: String, lat: Double, lon: Double, days: Int = 4): List<DayForecast> {
        val apiKey = getAemetApiKey(context)
        if (municipioCode.isBlank() || apiKey.isBlank()) return emptyList()
        return try {
            val step1Url = "https://opendata.aemet.es/opendata/api/prediccion/especifica/municipio/diaria/$municipioCode/?api_key=${apiKey.trim()}"
            val step1Response = httpGet(step1Url, Charset.forName("UTF-8")) ?: return emptyList()
            val step1Json = JSONObject(step1Response)
            if (step1Json.optInt("estado") != 200) return emptyList()

            val datosUrl = step1Json.getString("datos")
            val step2Response = httpGet(datosUrl, Charset.forName("ISO-8859-15")) ?: return emptyList()
            val rootArray = JSONArray(step2Response)
            val diasArray = rootArray.getJSONObject(0)
                .getJSONObject("prediccion")
                .getJSONArray("dia")

            val diasSemana = listOf("D", "L", "M", "X", "J", "V", "S")
            val result = mutableListOf<DayForecast>()

            for (i in 0 until minOf(days, diasArray.length())) {
                val diaObj = diasArray.getJSONObject(i)
                val fechaStr = diaObj.getString("fecha").substring(0, 10)
                val date = LocalDate.parse(fechaStr, DateTimeFormatter.ofPattern("yyyy-MM-dd"))
                val dayName = diasSemana[date.dayOfWeek.value % 7]

                val tempObj = diaObj.getJSONObject("temperatura")
                val maxTemp = tempObj.optString("maxima", "--")
                val minTemp = tempObj.optString("minima", "--")

                val rainMm = extractMaxDoubleFromPeriodArray(diaObj.optJSONArray("precipitacion"))
                val rainStr = formatWidgetLitersWithoutUnit(rainMm)

                val skyCode = extractFirstNonEmptyString(diaObj.optJSONArray("estadoCielo"), "value")
                val icon = aemetSkyToEmoji(skyCode)

                val (dir, speed) = extractMainWind(diaObj.optJSONArray("viento"))
                val windText = "${windDirToArrow(dir)} ${windSpeedToBars(speed)}$speed"

                val moonData = MainActivityHelper.calculateAccurateMoonData(date, lat, lon)
                val sunData = MainActivityHelper.calculateSunTimesStatic(date, lat, lon)
                val diffRise = if (moonData.riseTotalMins >= 0) abs(sunData.sunsetTotalMins - moonData.riseTotalMins) else 999
                val diffSet = if (moonData.setTotalMins >= 0) abs(moonData.setTotalMins - sunData.sunriseTotalMins) else 999
                val isGoodMoon = diffRise <= 60 || diffSet <= 60

                result.add(DayForecast(dayName, icon, windText, speed, maxTemp, minTemp, rainStr, isGoodMoon))
            }
            result
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun fetchOpenMeteoFallbackAndCurrent(lat: Double, lon: Double, days: Int = 4): Triple<String?, List<DayForecast>, Int> {
        return try {
            val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                    "&current=temperature_2m,uv_index" +
                    "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum,wind_speed_10m_max,wind_direction_10m_dominant,sunrise,sunset,uv_index_max" +
                    "&hourly=uv_index" +
                    "&timezone=Europe%2FMadrid&forecast_days=$days"
            val resp = httpGet(url, Charset.forName("UTF-8")) ?: return Triple(null, emptyList(), 0)
            val json = JSONObject(resp)

            val currentObj = json.optJSONObject("current")
            val currentTemp = currentObj?.optDouble("temperature_2m")?.roundToInt()?.toString()
            val currentUvRaw = currentObj?.optDouble("uv_index", -1.0) ?: -1.0
            var currentUv = if (currentUvRaw >= 0.0 && !currentUvRaw.isNaN()) currentUvRaw.roundToInt() else -1

            val daily = json.optJSONObject("daily") ?: return Triple(currentTemp, emptyList(), 0)
            val times = daily.getJSONArray("time")
            val codes = daily.getJSONArray("weather_code")
            val maxs = daily.getJSONArray("temperature_2m_max")
            val mins = daily.getJSONArray("temperature_2m_min")
            val rains = daily.getJSONArray("precipitation_sum")
            val windSpeeds = daily.getJSONArray("wind_speed_10m_max")
            val windDirs = daily.getJSONArray("wind_direction_10m_dominant")
            val sunsets = daily.optJSONArray("sunset")
            val uvs = daily.optJSONArray("uv_index_max")

            val hourlyObj = json.optJSONObject("hourly")
            val hourlyTime = hourlyObj?.optJSONArray("time")
            val hourlyUv = hourlyObj?.optJSONArray("uv_index")

            if (currentUv < 0 && hourlyTime != null && hourlyUv != null) {
                val nowIso = LocalDate.now().toString() + String.format(Locale.US, "T%02d:00", java.time.LocalTime.now().hour)
                for (k in 0 until minOf(hourlyTime.length(), hourlyUv.length())) {
                    if (hourlyTime.optString(k, "").startsWith(nowIso)) {
                        currentUv = hourlyUv.optDouble(k, 0.0).roundToInt()
                        break
                    }
                }
            }
            if (currentUv < 0) currentUv = 0

            val diasSemana = listOf("D", "L", "M", "X", "J", "V", "S")
            val list = mutableListOf<DayForecast>()

            for (i in 0 until minOf(days, times.length())) {
                val fechaStr = times.getString(i)
                val date = LocalDate.parse(fechaStr, DateTimeFormatter.ofPattern("yyyy-MM-dd"))
                val dayName = diasSemana[date.dayOfWeek.value % 7]

                val icon = wmoCodeToEmoji(codes.optInt(i, 0))
                val maxT = maxs.optDouble(i, 0.0).roundToInt().toString()
                val minT = mins.optDouble(i, 0.0).roundToInt().toString()

                val rainMm = rains.optDouble(i, 0.0)
                val rainStr = formatWidgetLitersWithoutUnit(rainMm)

                val wSpeed = windSpeeds.optDouble(i, 0.0).roundToInt()
                val wDeg = windDirs.optInt(i, 0)
                val windText = "${degreesToArrow(wDeg)} ${windSpeedToBars(wSpeed)}$wSpeed"

                val uvRaw = uvs?.optDouble(i, 0.0) ?: 0.0
                val uvMax = if (uvRaw.isNaN()) 0 else uvRaw.roundToInt()

                var uvStart = ""
                var uvEnd = ""
                if (hourlyTime != null && hourlyUv != null) {
                    var firstHourWithUv3 = -1
                    var lastHourWithUv3 = -1

                    for (k in 0 until minOf(hourlyTime.length(), hourlyUv.length())) {
                        val tStr = hourlyTime.optString(k, "")
                        if (tStr.startsWith(fechaStr)) {
                            val uvVal = hourlyUv.optDouble(k, 0.0)
                            if (uvVal >= 2.95) {
                                val hour = if (tStr.length >= 13) {
                                    tStr.substring(11, 13).toIntOrNull() ?: 0
                                } else 0
                                if (firstHourWithUv3 == -1) {
                                    firstHourWithUv3 = hour
                                }
                                lastHourWithUv3 = hour
                            }
                        }
                    }

                    if (firstHourWithUv3 != -1 && lastHourWithUv3 != -1) {
                        uvStart = "$firstHourWithUv3"
                        uvEnd = "${lastHourWithUv3 + 1}"
                    }
                }

                val moonData = MainActivityHelper.calculateAccurateMoonData(date, lat, lon)
                val sunData = MainActivityHelper.calculateSunTimesStatic(date, lat, lon)
                val sunsetIso = sunsets?.optString(i, "") ?: ""
                val sunsetMins = if (sunsetIso.length >= 16) {
                    val h = sunsetIso.substring(11, 13).toIntOrNull() ?: 18
                    val m = sunsetIso.substring(14, 16).toIntOrNull() ?: 30
                    h * 60 + m
                } else {
                    sunData.sunsetTotalMins
                }
                val sunriseMins = sunData.sunriseTotalMins

                val diffRise = if (moonData.riseTotalMins >= 0) abs(sunsetMins - moonData.riseTotalMins) else 999
                val diffSet = if (moonData.setTotalMins >= 0) abs(moonData.setTotalMins - sunriseMins) else 999
                val isGoodMoon = diffRise <= 60 || diffSet <= 60

                list.add(DayForecast(dayName, icon, windText, wSpeed, maxT, minT, rainStr, isGoodMoon, uvMax, uvStart, uvEnd))
            }
            Triple(currentTemp, list, currentUv)
        } catch (e: Exception) {
            Triple(null, emptyList(), 0)
        }
    }

    private fun formatWidgetLitersWithoutUnit(mm: Double): String {
        if (mm <= 0.0) return ""
        if (mm < 0.5) return "💧0"
        val rounded = mm.roundToInt()
        val valStr = if (rounded <= 0) "0" else "$rounded"
        return "💧$valStr"
    }

    private fun httpGet(urlStr: String, charset: Charset): String? {
        return try {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 7000
                readTimeout = 7000
                setRequestProperty("Accept", "application/json")
            }
            if (conn.responseCode != 200) null
            else conn.inputStream.bufferedReader(charset).use { it.readText() }
        } catch (e: Exception) {
            null
        }
    }

    fun extractMaxDoubleFromPeriodArray(arr: JSONArray?): Double {
        if (arr == null) return 0.0
        var maxVal = 0.0
        for (i in 0 until arr.length()) {
            val vStr = arr.getJSONObject(i).optString("value", "").replace(",", ".")
            if (vStr.equals("Ip", ignoreCase = true)) {
                if (maxVal < 0.1) maxVal = 0.1
            } else {
                val v = vStr.toDoubleOrNull() ?: 0.0
                if (v > maxVal) maxVal = v
            }
        }
        return maxVal
    }

    fun extractFirstNonEmptyString(arr: JSONArray?, key: String): String {
        if (arr == null) return ""
        for (i in 0 until arr.length()) {
            val v = arr.getJSONObject(i).optString(key, "").trim()
            if (v.isNotEmpty()) return v
        }
        return ""
    }

    private fun extractMainWind(arr: JSONArray?): Pair<String, Int> {
        if (arr == null) return Pair("C", 0)
        var bestDir = "C"
        var maxSpeed = -1
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val dir = obj.optString("direccion", "").trim()
            val vel = obj.optString("velocidad", "").toIntOrNull() ?: -1
            if (dir.isNotEmpty() && vel > maxSpeed) {
                maxSpeed = vel
                bestDir = dir
            }
        }
        return Pair(bestDir, if (maxSpeed < 0) 0 else maxSpeed)
    }

    private fun windDirToArrow(dir: String): String = when (dir.uppercase()) {
        "N" -> "↓"
        "S" -> "↑"
        "E" -> "←"
        "O", "W" -> "→"
        "NE" -> "↙"
        "NO", "NW" -> "↘"
        "SE" -> "↖"
        "SO", "SW" -> "↗"
        else -> "•"
    }

    private fun degreesToArrow(deg: Int): String = when (((deg + 22) % 360) / 45) {
        0 -> "↓"
        1 -> "↙"
        2 -> "←"
        3 -> "↖"
        4 -> "↑"
        5 -> "↗"
        6 -> "→"
        7 -> "↘"
        else -> "•"
    }

    private fun windSpeedToBars(speedKmh: Int): String = when {
        speedKmh < 15 -> "|"
        speedKmh < 30 -> "||"
        else -> "|||"
    }

    private fun aemetSkyToEmoji(code: String): String {
        val clean = code.replace("n", "").trim()
        return when {
            clean == "11" -> "☀️"
            clean in listOf("12", "13", "14", "17", "23", "24", "43", "44", "45") -> "⛅"
            clean in listOf("15", "16", "71", "72", "73", "74", "75") || clean.startsWith("8") -> "☁️"
            clean.startsWith("2") || clean.startsWith("4") || clean.startsWith("5") || clean.startsWith("6") -> "🌧️"
            clean.startsWith("3") || clean.startsWith("7") -> "❄️"
            else -> "⛅"
        }
    }

    private fun wmoCodeToEmoji(code: Int): String = when (code) {
        0 -> "☀️"
        1, 2 -> "⛅"
        3, 45, 48 -> "☁️"
        in 51..67, in 80..82 -> "🌧️"
        in 71..77 -> "❄️"
        in 95..99 -> "⛈️"
        else -> "⛅"
    }
}