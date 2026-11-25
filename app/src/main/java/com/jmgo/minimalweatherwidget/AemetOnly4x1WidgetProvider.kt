package com.jmgo.minimalweatherwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.View
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter

class AemetOnly4x1WidgetProvider : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val action = intent.action ?: return
        if (action == ACTION_REFRESH_AEMET_WIDGET ||
            action == AppWidgetManager.ACTION_APPWIDGET_UPDATE ||
            action == Intent.ACTION_DATE_CHANGED ||
            action == Intent.ACTION_TIME_CHANGED ||
            action == Intent.ACTION_TIMEZONE_CHANGED
        ) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, AemetOnly4x1WidgetProvider::class.java))
            if (ids.isNotEmpty()) {
                onUpdate(context, manager, ids)
            }
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        if (appWidgetIds.isEmpty()) return
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val locInfo = WeatherWidgetProvider.resolveLocationAndMunicipio(context)
                val updateTimeStr = java.time.LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))

                // ALWAYS query AEMET forecast directly
                val fetchedDays = WeatherWidgetProvider.fetchAemetForecast(context, locInfo.municipioCode, locInfo.lat, locInfo.lon)
                val currentTemp = fetchedDays.firstOrNull()?.maxTemp ?: "--"
                val headerTimeStr = "AEMET $updateTimeStr"

                applyToAemetWidgets(
                    context = context,
                    appWidgetManager = appWidgetManager,
                    ids = appWidgetIds,
                    cityName = locInfo.cityName,
                    currentTemp = currentTemp,
                    headerTimeStr = headerTimeStr,
                    daysData = fetchedDays
                )
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun applyToAemetWidgets(
        context: Context,
        appWidgetManager: AppWidgetManager,
        ids: IntArray,
        cityName: String,
        currentTemp: String,
        headerTimeStr: String,
        daysData: List<WeatherWidgetProvider.DayForecast>
    ) {
        if (ids.isEmpty()) return
        val prefs = context.getSharedPreferences("minimal_widget_prefs", Context.MODE_PRIVATE)

        val showMoon = prefs.getBoolean("show_widget_moon", true)
        val showWind = prefs.getBoolean("show_widget_wind", true)
        val showRain = prefs.getBoolean("show_widget_rain", true)
        val showUv = prefs.getBoolean("show_widget_uv", true)
        val showSunlight = prefs.getBoolean("show_widget_sunlight", true)
        val useApparent = prefs.getBoolean("use_apparent_temp", false)

        val iconIds = listOf(R.id.aemetIcon0, R.id.aemetIcon1, R.id.aemetIcon2, R.id.aemetIcon3)
        val dayIds = listOf(R.id.aemetDay0, R.id.aemetDay1, R.id.aemetDay2, R.id.aemetDay3)
        val camIds = listOf(R.id.aemetCam0, R.id.aemetCam1, R.id.aemetCam2, R.id.aemetCam3)
        val windIds = listOf(R.id.aemetWind0, R.id.aemetWind1, R.id.aemetWind2, R.id.aemetWind3)
        val tempIds = listOf(R.id.aemetTemp0, R.id.aemetTemp1, R.id.aemetTemp2, R.id.aemetTemp3)
        val rainIds = listOf(R.id.aemetRain0, R.id.aemetRain1, R.id.aemetRain2, R.id.aemetRain3)
        val uvStartIds = listOf(R.id.aemetUvStart0, R.id.aemetUvStart1, R.id.aemetUvStart2, R.id.aemetUvStart3)
        val uvIconIds = listOf(R.id.aemetUvIcon0, R.id.aemetUvIcon1, R.id.aemetUvIcon2, R.id.aemetUvIcon3)
        val outdoorIds = listOf(R.id.aemetOutdoor0, R.id.aemetOutdoor1, R.id.aemetOutdoor2, R.id.aemetOutdoor3)

        ids.forEach { appWidgetId ->
            val views = RemoteViews(context.packageName, R.layout.widget_aemet_4x1)

            // Tocar el cuerpo del widget abre la app
            val openAppIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val openAppPendingIntent = PendingIntent.getActivity(
                context, 0, openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.aemetWidgetRoot, openAppPendingIntent)

            // Tocar la hora de actualización descarga AEMET inmediatamente
            val refreshIntent = Intent(context, AemetOnly4x1WidgetProvider::class.java).apply {
                action = ACTION_REFRESH_AEMET_WIDGET
            }
            val refreshPendingIntent = PendingIntent.getBroadcast(
                context, 0, refreshIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.tvAemetUpdateTime, refreshPendingIntent)

            // Cabecera superior
            views.setTextViewText(R.id.tvAemetHeaderCityTemp, "📍 $cityName $currentTemp°")
            views.setTextViewText(R.id.tvAemetUpdateTime, headerTimeStr)

            if (daysData.isNotEmpty()) {
                for (i in 0 until minOf(4, daysData.size)) {
                    val d = daysData[i]
                    views.setTextViewText(iconIds[i], d.icon)
                    views.setTextViewText(dayIds[i], d.dayName)

                    // Cámara lunar (INVISIBLE cuando está inactiva para mantener fija la alineación de la cabecera)
                    if (showMoon && d.hasGoodMoonPhoto) {
                        views.setViewVisibility(camIds[i], View.VISIBLE)
                        views.setTextViewText(camIds[i], "📷")
                    } else {
                        views.setViewVisibility(camIds[i], View.INVISIBLE)
                    }

                    // Viento
                    if (showWind) {
                        views.setViewVisibility(windIds[i], View.VISIBLE)
                        val speed = d.windSpeedVal
                        val isHigh = speed >= 30
                        val windColor = if (isHigh) Color.parseColor("#E53E3E") else Color.parseColor("#4A5568")
                        views.setTextViewText(windIds[i], WeatherWidgetProvider.sanitizeWidgetWind(d.windText))
                        views.setTextColor(windIds[i], windColor)
                    } else {
                        views.setViewVisibility(windIds[i], View.GONE)
                    }

                    // Temperatura Real vs Sensación Térmica
                    if (d.maxTemp.isNotEmpty()) {
                        val maxDisplay = if (useApparent && d.apparentMaxTemp.isNotEmpty()) d.apparentMaxTemp else d.maxTemp
                        val minDisplay = if (useApparent && d.apparentMinTemp.isNotEmpty()) d.apparentMinTemp else d.minTemp

                        val maxVal = maxDisplay.toIntOrNull() ?: 0
                        val minVal = minDisplay.toIntOrNull() ?: 99

                        val tempColor = when {
                            maxVal >= 30 -> Color.parseColor("#E53E3E")
                            minVal <= 10 -> Color.parseColor("#3182CE")
                            else -> Color.parseColor("#1A202C")
                        }

                        views.setTextViewText(tempIds[i], "$maxDisplay / $minDisplay")
                        views.setTextColor(tempIds[i], tempColor)
                    }

                    // Lluvia en % e Índice UV
                    val uvFormatted = if (showUv) WeatherWidgetProvider.formatUvRangeForWidget(d.uvStart, d.uvEnd) else ""
                    val rainDisplay = if (showRain) d.rainStr else ""

                    views.setTextViewText(rainIds[i], rainDisplay)
                    views.setTextViewText(uvStartIds[i], uvFormatted)
                    views.setTextViewText(uvIconIds[i], if (uvFormatted.isNotEmpty()) "UV" else "")
                    views.setTextColor(uvIconIds[i], Color.parseColor("#DD6B20"))

                    // Luz Útil
                    if (showSunlight) {
                        views.setViewVisibility(outdoorIds[i], View.VISIBLE)
                        views.setTextViewText(outdoorIds[i], d.outdoorBadge)
                    } else {
                        views.setViewVisibility(outdoorIds[i], View.GONE)
                    }
                }
            }

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }

    companion object {
        const val ACTION_REFRESH_AEMET_WIDGET = "com.jmgo.minimalweatherwidget.ACTION_REFRESH_AEMET_WIDGET"

        fun requestRefresh(context: Context) {
            val intent = Intent(context, AemetOnly4x1WidgetProvider::class.java).apply {
                action = ACTION_REFRESH_AEMET_WIDGET
            }
            context.sendBroadcast(intent)
        }
    }
}
