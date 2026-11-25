package com.jmgo.minimalweatherwidget

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class WeatherNotificationWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val CHANNEL_ID = "weather_alerts_channel"
        const val CHANNEL_NAME = "Alertas del Tiempo (Mañana)"
        const val NOTIFICATION_ID = 2001

        fun createNotificationChannel(context: Context) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notificaciones meteorológicas para el día de mañana según las condiciones configuradas"
            }
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    override suspend fun doWork(): Result {
        val prefs = appContext.getSharedPreferences("minimal_widget_prefs", Context.MODE_PRIVATE)
        val notifEnabled = prefs.getBoolean("notif_enabled", false)
        if (!notifEnabled) {
            return Result.success()
        }

        val windEnabled = prefs.getBoolean("notif_wind_enabled", false)
        val windThreshold = prefs.getFloat("notif_wind_threshold", 35.0f)

        val tempMaxEnabled = prefs.getBoolean("notif_temp_max_enabled", false)
        val tempMaxThreshold = prefs.getFloat("notif_temp_max_threshold", 35.0f)

        val tempMinEnabled = prefs.getBoolean("notif_temp_min_enabled", false)
        val tempMinThreshold = prefs.getFloat("notif_temp_min_threshold", 10.0f)

        val rainEnabled = prefs.getBoolean("notif_rain_enabled", false)
        val rainThreshold = prefs.getFloat("notif_rain_threshold", 1.0f)

        val uvEnabled = prefs.getBoolean("notif_uv_enabled", false)
        val uvThreshold = prefs.getFloat("notif_uv_threshold", 3.0f)

        val moonEnabled = prefs.getBoolean("notif_moon_enabled", false)

        val tempChangeEnabled = prefs.getBoolean("notif_temp_change_enabled", false)
        val tempChangeThreshold = prefs.getFloat("notif_temp_change_threshold", 6.0f)

        val stormEnabled = prefs.getBoolean("notif_storm_enabled", false)
        val fogEnabled = prefs.getBoolean("notif_fog_enabled", false)
        val tidesEnabled = prefs.getBoolean("notif_tides_enabled", false)
        val aemetWarningsEnabled = prefs.getBoolean("notif_aemet_warnings_enabled", false)

        // Si ninguna condición individual está activa, no hacemos nada
        if (!windEnabled && !tempMaxEnabled && !tempMinEnabled && !rainEnabled &&
            !uvEnabled && !moonEnabled && !tempChangeEnabled && !stormEnabled &&
            !fogEnabled && !tidesEnabled && !aemetWarningsEnabled
        ) {
            return Result.success()
        }

        // Obtener ubicación
        val loc = WeatherWidgetProvider.resolveLocationAndMunicipio(appContext)

        // Consultar previsión de Open-Meteo
        val forecastPair = fetchForecastData(loc.lat, loc.lon) ?: return Result.retry()
        val todayData = forecastPair.first
        val tomorrowData = forecastPair.second ?: return Result.retry()

        val alerts = mutableListOf<String>()

        // 1. Viento
        if (windEnabled && tomorrowData.windSpeed >= windThreshold) {
            alerts.add("🌬️ Viento: ${tomorrowData.windSpeed} km/h (límite > ${windThreshold.toInt()} km/h)")
        }

        // 2. Temperatura Máxima
        if (tempMaxEnabled && tomorrowData.maxTemp >= tempMaxThreshold) {
            alerts.add("🌡️ Temp. Máxima: ${tomorrowData.maxTemp.roundToInt()}°C (límite > ${tempMaxThreshold.toInt()}°C)")
        }

        // 3. Temperatura Mínima
        if (tempMinEnabled && tomorrowData.minTemp <= tempMinThreshold) {
            alerts.add("❄️ Temp. Mínima: ${tomorrowData.minTemp.roundToInt()}°C (límite < ${tempMinThreshold.toInt()}°C)")
        }

        // 4. Lluvia
        if (rainEnabled && tomorrowData.rainMm >= rainThreshold) {
            val formattedRain = String.format(Locale.US, "%.1f", tomorrowData.rainMm)
            alerts.add("🌧️ Lluvia: $formattedRain l (límite ≥ ${rainThreshold} l)")
        }

        // 5. Índice UV
        if (uvEnabled && tomorrowData.uvMax >= uvThreshold) {
            alerts.add("☀️ Índice UV: ${tomorrowData.uvMax.roundToInt()} (límite ≥ ${uvThreshold.toInt()})")
        }

        // 6. Fotografiar la Luna
        if (moonEnabled) {
            val dateTomorrow = LocalDate.now().plusDays(1)
            val sunData = MainActivityHelper.calculateSunTimesStatic(dateTomorrow, loc.lat, loc.lon)
            val moonData = MainActivityHelper.calculateAccurateMoonData(dateTomorrow, loc.lat, loc.lon)
            val diffRise = if (moonData.riseTotalMins >= 0) abs(sunData.sunsetTotalMins - moonData.riseTotalMins) else 999
            val diffSet = if (moonData.setTotalMins >= 0) abs(moonData.setTotalMins - sunData.sunriseTotalMins) else 999
            val isGoodMoon = diffRise <= 90 || diffSet <= 90
            if (isGoodMoon) {
                alerts.add("📷 🌕 Ventana óptima para fotografiar la Luna (coincidencia Sol/Luna)")
            }
        }

        // 7. Cambio brusco de temperatura
        if (tempChangeEnabled && todayData != null) {
            val diffMax = abs(tomorrowData.maxTemp - todayData.maxTemp)
            if (diffMax >= tempChangeThreshold) {
                val direction = if (tomorrowData.maxTemp < todayData.maxTemp) "baja" else "sube"
                alerts.add("📉 Cambio brusco temp: $direction ${diffMax.roundToInt()}°C respecto a hoy (máx ${tomorrowData.maxTemp.roundToInt()}°C)")
            }
        }

        // 8. Tormentas o Granizo
        if (stormEnabled && (tomorrowData.weatherCode in setOf(95, 96, 99))) {
            alerts.add("⚡ Riesgo de Tormenta o Granizo previsto para mañana")
        }

        // 9. Niebla o visibilidad reducida
        if (fogEnabled && (tomorrowData.weatherCode in setOf(45, 48))) {
            alerts.add("🌫️ Previsión de Niebla intensa o visibilidad reducida")
        }

        // 10. Marea alta / Pleamar en costa
        if (tidesEnabled) {
            val nearestCoast = MainActivityHelper.getNearestCoastPoint(loc.lat, loc.lon)
            if (nearestCoast != null) {
                alerts.add("🌊 Aviso de Pleamar / Marea alta en zona costera (${nearestCoast.name})")
            }
        }

        // 11. Avisos oficiales AEMET
        if (aemetWarningsEnabled) {
            val exactDetails = WeatherNotificationHelper.fetchAemetExactWarnings(appContext, loc.municipioCode)
            if (exactDetails.isNotEmpty()) {
                alerts.add("📢 Avisos Oficiales AEMET para Mañana:\n" + exactDetails.joinToString("\n"))
            } else {
                if (tomorrowData.windSpeed >= 40 || tomorrowData.rainMm >= 5.0 || tomorrowData.weatherCode in setOf(95, 96, 99)) {
                    alerts.add("📢 Previsión AEMET: Viento de ${tomorrowData.windSpeed} km/h y precipitaciones en la previsión oficial")
                }
            }
        }

        // Si se cumple alguna condición activa, emitir notificación
        if (alerts.isNotEmpty()) {
            sendNotification(loc.cityName, alerts)
        }

        return Result.success()
    }

    private data class DayForecastData(
        val weatherCode: Int,
        val windSpeed: Int,
        val maxTemp: Double,
        val minTemp: Double,
        val rainMm: Double,
        val uvMax: Double
    )

    private fun fetchForecastData(lat: Double, lon: Double): Pair<DayForecastData?, DayForecastData?>? {
        return try {
            val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                    "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum,wind_speed_10m_max,uv_index_max" +
                    "&timezone=Europe%2FMadrid&forecast_days=3"
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 10000
            }
            if (conn.responseCode != 200) return null
            val resp = conn.inputStream.bufferedReader(Charset.forName("UTF-8")).use { it.readText() }
            val json = JSONObject(resp)
            val daily = json.optJSONObject("daily") ?: return null

            val times = daily.getJSONArray("time")
            val codes = daily.getJSONArray("weather_code")
            val maxs = daily.getJSONArray("temperature_2m_max")
            val mins = daily.getJSONArray("temperature_2m_min")
            val rains = daily.getJSONArray("precipitation_sum")
            val winds = daily.getJSONArray("wind_speed_10m_max")
            val uvs = daily.optJSONArray("uv_index_max")

            val todayStr = LocalDate.now().toString()
            val tomorrowStr = LocalDate.now().plusDays(1).toString()

            var todayIdx = -1
            var tomorrowIdx = -1

            for (i in 0 until times.length()) {
                val t = times.getString(i)
                if (t == todayStr) todayIdx = i
                if (t == tomorrowStr) tomorrowIdx = i
            }

            if (todayIdx == -1 && times.length() > 0) todayIdx = 0
            if (tomorrowIdx == -1 && times.length() > 1) tomorrowIdx = 1

            fun parseDay(idx: Int): DayForecastData? {
                if (idx < 0 || idx >= times.length()) return null
                return DayForecastData(
                    weatherCode = codes.optInt(idx, 0),
                    windSpeed = winds.optDouble(idx, 0.0).roundToInt(),
                    maxTemp = maxs.optDouble(idx, 0.0),
                    minTemp = mins.optDouble(idx, 0.0),
                    rainMm = rains.optDouble(idx, 0.0),
                    uvMax = uvs?.optDouble(idx, 0.0) ?: 0.0
                )
            }

            Pair(parseDay(todayIdx), parseDay(tomorrowIdx))
        } catch (e: Exception) {
            null
        }
    }

    private fun sendNotification(cityName: String, alerts: List<String>) {
        createNotificationChannel(appContext)

        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra(WeatherNotificationHelper.EXTRA_SHOW_CURRENT_ALERTS, true)
        }
        val pendingIntent = PendingIntent.getActivity(
            appContext, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = "⚠️ Avisos del Tiempo para Mañana ($cityName)"
        val bodyText = alerts.joinToString("\n")

        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(alerts.firstOrNull() ?: "")
            .setStyle(NotificationCompat.BigTextStyle().bigText(bodyText))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val notificationManager =
            appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}
