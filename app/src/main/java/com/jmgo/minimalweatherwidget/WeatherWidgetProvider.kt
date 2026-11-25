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
import android.net.Uri
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
        val uvEnd: String = "",
        val outdoorBadge: String = "🏃🟢",
        val apparentMaxTemp: String = "",
        val apparentMinTemp: String = ""
    )

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        scheduleAutoRefresh(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        if (!hasAnyWidgets(context)) {
            cancelAutoRefresh(context)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH || intent.action == ACTION_AUTO_REFRESH) {
            context.getSharedPreferences("minimal_widget_prefs", Context.MODE_PRIVATE)
                .edit()
                .putLong("last_fetch_time", 0L)
                .apply()

            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, WeatherWidgetProvider::class.java))
            onUpdate(context, manager, ids)
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        scheduleAutoRefresh(context)

        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                synchronized(updateLock) {
                    val prefs = context.getSharedPreferences("minimal_widget_prefs", Context.MODE_PRIVATE)
                    val locInfo = resolveLocationAndMunicipio(context)
                    val savedCity = prefs.getString("cached_widget_city", "") ?: ""
                    val isSameLocation = (savedCity == locInfo.cityName)

                    val savedVersion = prefs.getInt("cache_version", 1)
                    val savedDate = prefs.getString("cached_date", "")
                    val currentDateStr = java.time.LocalDate.now().toString()
                    val nowDate = java.time.LocalDate.now()
                    val diasSemana = listOf("D", "L", "M", "X", "J", "V", "S")
                    val todayExpectedDayName = diasSemana[nowDate.dayOfWeek.value % 7]
                    val cachedDay0Name = prefs.getString("c_day_0", "") ?: ""

                    val isSameDay = isSameLocation && (savedDate == currentDateStr) && (cachedDay0Name == todayExpectedDayName)
                    val lastFetch = if (savedVersion < CACHE_VERSION) 0L else prefs.getLong("last_fetch_time", 0L)

                    val currentTime = System.currentTimeMillis()
                    val isCacheValid = isSameDay && (currentTime - lastFetch) < CACHE_MILLIS

                    val daysData = mutableListOf<DayForecast>()
                    var currentTemp = prefs.getString("cached_temp", "--") ?: "--"
                    var currentUv = prefs.getInt("cached_current_uv", 0)
                    var updateTimeStr = prefs.getString("cached_widget_update_time", "--:--") ?: "--:--"
                    var anyGoodMoonPhoto = false

                    if (!isCacheValid) {
                        updateTimeStr = java.time.LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))

                        val openMeteoData = fetchOpenMeteoFallbackAndCurrent(locInfo.lat, locInfo.lon, days = 7, context = context)
                        val fetchedDays = openMeteoData.second
                        val fetchedCurrentTemp = openMeteoData.first
                        currentUv = openMeteoData.third

                        if (fetchedDays.isNotEmpty()) {
                            daysData.addAll(fetchedDays)
                            currentTemp = fetchedCurrentTemp ?: fetchedDays.firstOrNull()?.maxTemp ?: "--"
                            anyGoodMoonPhoto = daysData.any { it.hasGoodMoonPhoto }

                            val editor = prefs.edit()
                            editor.putInt("cache_version", CACHE_VERSION)
                            editor.putLong("last_fetch_time", currentTime)
                            editor.putString("cached_date", currentDateStr)
                            editor.putString("cached_widget_city", locInfo.cityName)
                            editor.putString("cached_temp", currentTemp)
                            editor.putInt("cached_current_uv", currentUv)
                            editor.putString("cached_widget_update_time", updateTimeStr)
                            editor.putBoolean("cached_moon_photo", anyGoodMoonPhoto)
                            editor.putBoolean("is_cache_fallback", false)

                            for (i in 0 until minOf(7, fetchedDays.size)) {
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
                                editor.putString("c_outdoor_$i", d.outdoorBadge)
                                editor.putString("c_app_max_$i", d.apparentMaxTemp)
                                editor.putString("c_app_min_$i", d.apparentMinTemp)
                                editor.putBoolean("c_moon_$i", d.hasGoodMoonPhoto)
                            }
                            editor.commit()
                        }
                    }

                    if (daysData.isEmpty()) {
                        prefs.edit().putBoolean("is_cache_fallback", true).commit()
                        anyGoodMoonPhoto = prefs.getBoolean("cached_moon_photo", false)
                        currentUv = prefs.getInt("cached_current_uv", 0)
                        updateTimeStr = prefs.getString("cached_widget_update_time", "--:--") ?: "--:--"

                        for (i in 0 until 7) {
                            val targetDate = nowDate.plusDays(i.toLong())
                            val expectedDayName = diasSemana[targetDate.dayOfWeek.value % 7]
                            val savedTemp = prefs.getString("c_temp_$i", "") ?: ""
                            val parts = savedTemp.split("/")
                            val maxT = parts.getOrNull(0)?.trim() ?: ""
                            val minT = parts.getOrNull(1)?.trim() ?: ""
                            val rawWind = prefs.getString("c_wind_$i", "") ?: ""
                            val savedWindSpeed = prefs.getInt("c_wind_speed_$i", 0)
                            val savedMoonPhoto = prefs.getBoolean("c_moon_$i", false)
                            val savedRain = prefs.getString("c_rain_$i", "") ?: ""
                            val savedUv = prefs.getInt("c_uv_$i", 0)
                            val savedOutdoor = prefs.getString("c_outdoor_$i", null)
                                ?: calculateDayOutdoorBadge(prefs, maxT, minT, savedWindSpeed, savedRain, savedUv)

                            daysData.add(
                                DayForecast(
                                    dayName = expectedDayName,
                                    icon = prefs.getString("c_icon_$i", "⛅") ?: "⛅",
                                    windText = sanitizeWidgetWind(rawWind),
                                    windSpeedVal = savedWindSpeed,
                                    maxTemp = maxT,
                                    minTemp = minT,
                                    rainStr = savedRain,
                                    hasGoodMoonPhoto = savedMoonPhoto,
                                    uvMax = savedUv,
                                    uvStart = prefs.getString("c_uv_start_$i", "") ?: "",
                                    uvEnd = prefs.getString("c_uv_end_$i", "") ?: "",
                                    outdoorBadge = savedOutdoor
                                )
                            )
                        }
                    }

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
                    }

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

    companion object {
        // Fuerza una descarga nueva de datos (ignora la caché) y actualiza los widgets
        const val ACTION_REFRESH = "com.jmgo.minimalweatherwidget.ACTION_REFRESH"
        // La envía la alarma automática cada 30 minutos
        const val ACTION_AUTO_REFRESH = "com.jmgo.minimalweatherwidget.ACTION_AUTO_REFRESH"

        // Extra que se añade al abrir la app desde el widget
        const val EXTRA_REFRESH_FROM_WIDGET = "refresh_from_widget"

        private const val CACHE_VERSION = 25
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

            AemetOnly4x1WidgetProvider.requestRefresh(context)
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

        @JvmStatic
        fun calculateDayOutdoorBadge(prefs: android.content.SharedPreferences?, maxTempStr: String, minTempStr: String, windSpeed: Int, rainStr: String, uvMax: Int): String {
            val optMinT = prefs?.getInt("outdoor_temp_opt_min", 10) ?: 10
            val optMaxT = prefs?.getInt("outdoor_temp_opt_max", 24) ?: 24
            val redMinT = prefs?.getInt("outdoor_temp_red_min", 4) ?: 4
            val redMaxT = prefs?.getInt("outdoor_temp_red_max", 31) ?: 31

            val rainOptMax = (prefs?.getFloat("outdoor_rain_opt_max", 0.0f) ?: 0.0f).toDouble()
            val rainRedMax = (prefs?.getFloat("outdoor_rain_red_max", 1.5f) ?: 1.5f).toDouble()

            val windOptMax = prefs?.getInt("outdoor_wind_opt_max", 25) ?: 25
            val windRedMax = prefs?.getInt("outdoor_wind_red_max", 40) ?: 40

            val uvOptMax = prefs?.getInt("outdoor_uv_opt_max", 5) ?: 5
            val uvRedMax = prefs?.getInt("outdoor_uv_red_max", 8) ?: 8

            val maxT = maxTempStr.toIntOrNull() ?: 20
            val minT = minTempStr.toIntOrNull() ?: 15
            val avgT = (maxT + minT) / 2

            val cleanRain = rainStr.replace("mm", "").replace("l/m²", "").replace("💧", "").trim()
            val r = cleanRain.toDoubleOrNull() ?: 0.0

            val tempLevel = when {
                avgT in optMinT..optMaxT -> 2
                avgT in redMinT..<optMinT || avgT in (optMaxT + 1)..redMaxT -> 1
                else -> 0
            }

            val rainLevel = when {
                r <= rainOptMax -> 2
                r <= rainRedMax -> 1
                else -> 0
            }

            val windLevel = when {
                windSpeed <= windOptMax -> 2
                windSpeed <= windRedMax -> 1
                else -> 0
            }

            val uvLevel = when {
                uvMax <= uvOptMax -> 2
                uvMax < uvRedMax -> 1
                else -> 0
            }

            val levels = listOf(tempLevel, rainLevel, windLevel, uvLevel)
            return when {
                levels.contains(0) -> "🏃🔴"
                levels.contains(1) -> "🏃🟡"
                else -> "🏃🟢 8h"
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

    fun formatUvRangeForWidget(startRaw: String, endRaw: String): String {
        if (startRaw.isBlank()) return ""
        if (startRaw.contains("-")) return startRaw

        if (endRaw.isBlank()) {
            return startRaw
        }

        val startInt = startRaw.toIntOrNull() ?: return startRaw
        val endInt = endRaw.toIntOrNull() ?: return startRaw

        if (startInt == endInt) return "$startInt"

        val sDisplay = if (startInt > 12) startInt - 12 else if (startInt == 0) 12 else startInt
        val eDisplay = if (endInt > 12) endInt - 12 else if (endInt == 0) 12 else endInt
        return "$sDisplay-$eDisplay"
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

        ids.forEach { appWidgetId ->
            val views = RemoteViews(context.packageName, layoutRes)
            views.setInt(R.id.widgetRoot, "setBackgroundResource", rootBg)

            val serviceIntent = Intent(context, Weather7DaysWidgetService::class.java).apply {
                putExtra("is_dark_theme", isDark)
                data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }
            views.setRemoteAdapter(R.id.lvWidgetDays, serviceIntent)

            val openAppIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_REFRESH_FROM_WIDGET, true)
            }
            val openAppPendingIntent = PendingIntent.getActivity(
                context, 0, openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setPendingIntentTemplate(R.id.lvWidgetDays, openAppPendingIntent)
            views.setOnClickPendingIntent(R.id.widgetRoot, openAppPendingIntent)

            // Al pulsar la hora del widget (tvUpdateTime) o la vista vacía se fuerza una descarga inmediata
            val refreshIntent = Intent(context, WeatherWidgetProvider::class.java).apply {
                action = ACTION_REFRESH
            }
            val refreshPendingIntent = PendingIntent.getBroadcast(
                context, 0, refreshIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.tvUpdateTime, refreshPendingIntent)
            views.setEmptyView(R.id.lvWidgetDays, R.id.empty_view)
            views.setOnClickPendingIntent(R.id.empty_view, refreshPendingIntent)

            // Ubicación actual + Temp actual + UV actual (si UV >= 3)
            val uvHeaderStr = if (currentUv >= 3) "  UV$currentUv" else ""
            views.setTextViewText(R.id.tvHeaderCityTemp, "📍 $cityName $currentTemp°$uvHeaderStr")

            // Hora de actualización del widget
            val timeColor = if (isDark) Color.parseColor("#A0AEC0") else Color.parseColor("#718096")
            val displayTime = if (updateTimeStr == "--:--" || updateTimeStr.isEmpty()) "" else updateTimeStr

            views.setTextViewText(R.id.tvUpdateTime, displayTime)
            views.setTextColor(R.id.tvUpdateTime, timeColor)

            appWidgetManager.updateAppWidget(appWidgetId, views)
            appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.lvWidgetDays)
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

    @JvmStatic
    fun sanitizeWidgetWind(raw: String): String {
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

    @JvmStatic
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
        val resolvedCode = findAemetMunicipioCode(context, detectedCity)

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

    @JvmStatic
    fun fetchAemetForecast(context: Context, municipioCode: String, lat: Double, lon: Double, days: Int = 4): List<DayForecast> {
        return emptyList()
    }

    @JvmStatic
    fun fetchOpenMeteoFallbackAndCurrent(lat: Double, lon: Double, days: Int = 7, context: Context? = null): Triple<String?, List<DayForecast>, Int> {
        val prefs = context?.getSharedPreferences("minimal_widget_prefs", Context.MODE_PRIVATE)
        return try {
            val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                    "&current=temperature_2m,uv_index" +
                    "&daily=weather_code,temperature_2m_max,temperature_2m_min,apparent_temperature_max,apparent_temperature_min,precipitation_sum,wind_speed_10m_max,wind_gusts_10m_max,wind_direction_10m_dominant,sunrise,sunset,uv_index_max" +
                    "&hourly=temperature_2m,apparent_temperature,precipitation,weather_code,wind_speed_10m,wind_gusts_10m,uv_index" +
                    "&timezone=Europe%2FMadrid&forecast_days=${days + 2}"
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
            val windGusts = daily.optJSONArray("wind_gusts_10m_max")
            val windDirs = daily.getJSONArray("wind_direction_10m_dominant")
            val sunsets = daily.optJSONArray("sunset")
            val uvs = daily.optJSONArray("uv_index_max")

            val omByDate = mutableMapOf<LocalDate, Int>()
            for (k in 0 until times.length()) {
                val fechaStr = times.getString(k).substring(0, 10)
                try {
                    val d = LocalDate.parse(fechaStr, DateTimeFormatter.ofPattern("yyyy-MM-dd"))
                    omByDate[d] = k
                } catch (_: Exception) {}
            }

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
            val nowDate = LocalDate.now()

            val nowHour = java.time.LocalTime.now().hour

            for (idx in 0 until days) {
                val targetDate = nowDate.plusDays(idx.toLong())
                val i = omByDate[targetDate] ?: continue
                val fechaStr = targetDate.toString()
                val dayName = diasSemana[targetDate.dayOfWeek.value % 7]

                val moonData = MainActivityHelper.calculateAccurateMoonData(targetDate, lat, lon)
                var icon = wmoCodeToEmoji(codes.optInt(i, 0))
                var maxT = maxs.optDouble(i, 0.0).roundToInt().toString()
                var minT = mins.optDouble(i, 0.0).roundToInt().toString()

                var rainMm = rains.optDouble(i, 0.0)
                var wSpeed = windSpeeds.optDouble(i, 0.0).roundToInt()
                var wGust = if (windGusts != null && !windGusts.optDouble(i, 0.0).isNaN()) windGusts.optDouble(i, 0.0).roundToInt() else wSpeed
                val wDeg = windDirs.optInt(i, 0)
                var uvMax = if (uvs != null && !uvs.optDouble(i, 0.0).isNaN()) uvs.optDouble(i, 0.0).roundToInt() else 0

                // Para Hoy (idx == 0): calcular la previsión solo para lo que queda de día (desde la hora actual)
                if (idx == 0 && hourlyObj != null) {
                    val hourlyTemps = hourlyObj.optJSONArray("temperature_2m")
                    val hourlyRains = hourlyObj.optJSONArray("precipitation")
                    val hourlyWinds = hourlyObj.optJSONArray("wind_speed_10m")
                    val hourlyGusts = hourlyObj.optJSONArray("wind_gusts_10m")
                    val hourlyCodes = hourlyObj.optJSONArray("weather_code")

                    val remTemps = mutableListOf<Double>()
                    var remRainSum = 0.0
                    var remWindMax = 0.0
                    var remGustMax = 0.0
                    val remCodes = mutableListOf<Int>()
                    val remUvs = mutableListOf<Double>()

                    if (hourlyTime != null) {
                        for (k in 0 until hourlyTime.length()) {
                            val tStr = hourlyTime.optString(k, "")
                            if (tStr.startsWith(fechaStr)) {
                                val h = if (tStr.length >= 13) tStr.substring(11, 13).toIntOrNull() ?: 0 else 0
                                if (h >= nowHour) {
                                    if (hourlyTemps != null && !hourlyTemps.optDouble(k, Double.NaN).isNaN()) {
                                        remTemps.add(hourlyTemps.optDouble(k))
                                    }
                                    if (hourlyRains != null && !hourlyRains.optDouble(k, Double.NaN).isNaN()) {
                                        remRainSum += hourlyRains.optDouble(k)
                                    }
                                    if (hourlyWinds != null && !hourlyWinds.optDouble(k, Double.NaN).isNaN()) {
                                        remWindMax = maxOf(remWindMax, hourlyWinds.optDouble(k))
                                    }
                                    if (hourlyGusts != null && !hourlyGusts.optDouble(k, Double.NaN).isNaN()) {
                                        remGustMax = maxOf(remGustMax, hourlyGusts.optDouble(k))
                                    }
                                    if (hourlyCodes != null) {
                                        remCodes.add(hourlyCodes.optInt(k))
                                    }
                                    if (hourlyUv != null && !hourlyUv.optDouble(k, Double.NaN).isNaN()) {
                                        remUvs.add(hourlyUv.optDouble(k))
                                    }
                                }
                            }
                        }
                    }

                    if (remTemps.isNotEmpty()) {
                        maxT = remTemps.maxOrNull()?.roundToInt()?.toString() ?: maxT
                        minT = remTemps.minOrNull()?.roundToInt()?.toString() ?: minT
                    }
                    rainMm = remRainSum
                    if (remWindMax > 0) wSpeed = remWindMax.roundToInt()
                    if (remGustMax > 0) wGust = remGustMax.roundToInt()
                    if (remUvs.isNotEmpty()) uvMax = maxOf(0, remUvs.maxOrNull()?.roundToInt() ?: 0)
                    if (remCodes.isNotEmpty()) icon = Companion.wmoCodeToEmoji(remCodes.first())
                }

                val rainStr = formatWidgetLitersWithoutUnit(rainMm)
                val windText = if (wGust > wSpeed && wGust > 0) {
                    "${degreesToArrow(wDeg)} $wSpeed ($wGust)"
                } else {
                    "${degreesToArrow(wDeg)} $wSpeed"
                }

                var uvStart = ""
                var uvEnd = ""
                if (hourlyTime != null && hourlyUv != null) {
                    var firstHourWithUv3 = -1
                    var lastHourWithUv3 = -1

                    val minHourToConsider = if (idx == 0) nowHour else 0

                    for (k in 0 until minOf(hourlyTime.length(), hourlyUv.length())) {
                        val tStr = hourlyTime.optString(k, "")
                        if (tStr.startsWith(fechaStr)) {
                            val hour = if (tStr.length >= 13) {
                                tStr.substring(11, 13).toIntOrNull() ?: 0
                            } else 0

                            if (hour >= minHourToConsider) {
                                val uvVal = hourlyUv.optDouble(k, 0.0)
                                if (uvVal >= 2.95) {
                                    if (firstHourWithUv3 == -1) {
                                        firstHourWithUv3 = hour
                                    }
                                    lastHourWithUv3 = hour
                                }
                            }
                        }
                    }

                    if (firstHourWithUv3 != -1 && lastHourWithUv3 != -1) {
                        uvStart = "$firstHourWithUv3"
                        uvEnd = "${lastHourWithUv3 + 1}"
                    }
                }

                val sunData = MainActivityHelper.calculateSunTimesStatic(targetDate, lat, lon)
                val sunriseH = sunData.sunriseHour
                val sunsetH = sunData.sunsetHour

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
                val rawGoodMoon = diffRise <= 90 || diffSet <= 90

                val isGoodMoon = if (idx == 0 && rawGoodMoon) {
                    val nowMins = nowHour * 60 + java.time.LocalTime.now().minute
                    val riseFuture = moonData.riseTotalMins >= nowMins
                    val setFuture = moonData.setTotalMins >= nowMins
                    (diffRise <= 90 && riseFuture) || (diffSet <= 90 && setFuture)
                } else {
                    rawGoodMoon
                }

                var greenCount = 0
                if (hourlyObj != null && hourlyTime != null) {
                    val hourlyTemps = hourlyObj.optJSONArray("temperature_2m")
                    val hourlyRains = hourlyObj.optJSONArray("precipitation")
                    val hourlyWinds = hourlyObj.optJSONArray("wind_speed_10m")
                    val hourlyUvs = hourlyObj.optJSONArray("uv_index")

                    val optMinT = prefs?.getInt("outdoor_temp_opt_min", 10) ?: 10
                    val optMaxT = prefs?.getInt("outdoor_temp_opt_max", 24) ?: 24

                    val rainOptMax = (prefs?.getFloat("outdoor_rain_opt_max", 0.0f) ?: 0.0f).toDouble()
                    val windOptMax = prefs?.getInt("outdoor_wind_opt_max", 25) ?: 25
                    val uvOptMax = prefs?.getInt("outdoor_uv_opt_max", 5) ?: 5

                    val startH = if (idx == 0) maxOf(sunriseH, nowHour) else sunriseH

                    for (k in 0 until minOf(hourlyTime.length(), hourlyTemps?.length() ?: 0)) {
                        val tStr = hourlyTime.optString(k, "")
                        if (tStr.startsWith(fechaStr)) {
                            val h = if (tStr.length >= 13) tStr.substring(11, 13).toIntOrNull() ?: 0 else 0
                            if (h in startH..sunsetH) {
                                val t = hourlyTemps?.optDouble(k, 20.0) ?: 20.0
                                val r = hourlyRains?.optDouble(k, 0.0) ?: 0.0
                                val w = hourlyWinds?.optDouble(k, 10.0) ?: 10.0
                                val uv = hourlyUvs?.optDouble(k, 0.0) ?: 0.0

                                val tOk = (t >= optMinT && t <= optMaxT)
                                val rOk = (r <= rainOptMax)
                                val wOk = (w <= windOptMax)
                                val uvOk = (uv <= uvOptMax)

                                if (tOk && rOk && wOk && uvOk) {
                                    greenCount++
                                }
                            }
                        }
                    }
                }

                val badge = "🏃 $greenCount h"

                list.add(DayForecast(dayName, icon, windText, wSpeed, maxT, minT, rainStr, isGoodMoon, uvMax, uvStart, uvEnd, outdoorBadge = badge))
            }
            Triple(currentTemp, list, currentUv)
        } catch (e: Exception) {
            Triple(null, emptyList(), 0)
        }
    }

    private fun formatWidgetLitersWithoutUnit(mm: Double): String {
        val rounded = mm.roundToInt()
        if (rounded <= 0) return ""
        return "💧$rounded"
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

    @JvmStatic
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

    fun extractMaxIntFromPeriodArray(arr: JSONArray?): Int {
        if (arr == null) return 0
        var maxVal = 0
        for (i in 0 until arr.length()) {
            val vStr = arr.getJSONObject(i).optString("value", "")
            val v = vStr.toIntOrNull() ?: 0
            if (v > maxVal) maxVal = v
        }
        return maxVal
    }

    @JvmStatic
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
}