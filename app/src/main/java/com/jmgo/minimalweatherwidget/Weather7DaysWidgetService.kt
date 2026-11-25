package com.jmgo.minimalweatherwidget

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService

class Weather7DaysWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        return Weather7DaysRemoteViewsFactory(this.applicationContext, intent)
    }
}

class Weather7DaysRemoteViewsFactory(
    private val context: Context,
    private val intent: Intent
) : RemoteViewsService.RemoteViewsFactory {

    private var daysDataList: List<WeatherWidgetProvider.DayForecast> = emptyList()
    private var isDarkTheme: Boolean = true

    override fun onCreate() {}

    override fun onDataSetChanged() {
        val prefs = context.getSharedPreferences("minimal_widget_prefs", Context.MODE_PRIVATE)
        val locInfo = WeatherWidgetProvider.resolveLocationAndMunicipio(context)

        val openMeteoData = WeatherWidgetProvider.fetchOpenMeteoFallbackAndCurrent(locInfo.lat, locInfo.lon, days = 7, context = context)
        daysDataList = openMeteoData.second
        isDarkTheme = intent.getBooleanExtra("is_dark_theme", true)
    }

    override fun onDestroy() {
        daysDataList = emptyList()
    }

    override fun getCount(): Int {
        // 2 filas: Fila 0 = Días 1..4, Fila 1 = Días 5..7
        return if (daysDataList.isEmpty()) 0 else 2
    }

    override fun getViewAt(position: Int): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_row_4cols)
        val prefs = context.getSharedPreferences("minimal_widget_prefs", Context.MODE_PRIVATE)

        val showMoon = prefs.getBoolean("show_widget_moon", true)
        val showWind = prefs.getBoolean("show_widget_wind", true)
        val showRain = prefs.getBoolean("show_widget_rain", true)
        val showUv = prefs.getBoolean("show_widget_uv", true)
        val showSunlight = prefs.getBoolean("show_widget_sunlight", true)
        val useApparent = prefs.getBoolean("use_apparent_temp", false)

        val cardIds = listOf(R.id.card0, R.id.card1, R.id.card2, R.id.card3)
        val iconIds = listOf(R.id.icon0, R.id.icon1, R.id.icon2, R.id.icon3)
        val dayIds = listOf(R.id.day0, R.id.day1, R.id.day2, R.id.day3)
        val camIds = listOf(R.id.cam0, R.id.cam1, R.id.cam2, R.id.cam3)
        val windIds = listOf(R.id.wind0, R.id.wind1, R.id.wind2, R.id.wind3)
        val tempIds = listOf(R.id.temp0, R.id.temp1, R.id.temp2, R.id.temp3)
        val rainIds = listOf(R.id.rain0, R.id.rain1, R.id.rain2, R.id.rain3)
        val uvStartIds = listOf(R.id.uvStart0, R.id.uvStart1, R.id.uvStart2, R.id.uvStart3)
        val uvIconIds = listOf(R.id.uvIcon0, R.id.uvIcon1, R.id.uvIcon2, R.id.uvIcon3)
        val outdoorIds = listOf(R.id.outdoor0, R.id.outdoor1, R.id.outdoor2, R.id.outdoor3)

        val startDayIdx = position * 4

        for (col in 0 until 4) {
            val dayIdx = startDayIdx + col

            if (dayIdx < daysDataList.size) {
                val d = daysDataList[dayIdx]
                views.setViewVisibility(cardIds[col], View.VISIBLE)

                views.setTextViewText(iconIds[col], d.icon)
                views.setTextViewText(dayIds[col], d.dayName)

                // Color día / fin de semana
                val isWeekend = (d.dayName == "S" || d.dayName == "D")
                if (isWeekend) {
                    val bgRes = if (isDarkTheme) R.drawable.bg_weekend_dark else R.drawable.bg_weekend_light
                    views.setInt(cardIds[col], "setBackgroundResource", bgRes)
                    val dayColor = if (isDarkTheme) Color.parseColor("#ED8936") else Color.parseColor("#DD6B20")
                    views.setTextColor(dayIds[col], dayColor)
                } else {
                    views.setInt(cardIds[col], "setBackgroundResource", 0)
                    val dayColor = if (isDarkTheme) Color.parseColor("#FFD700") else Color.parseColor("#D69E2E")
                    views.setTextColor(dayIds[col], dayColor)
                }

                // Cámara lunar
                if (showMoon && d.hasGoodMoonPhoto) {
                    views.setViewVisibility(camIds[col], View.VISIBLE)
                    views.setTextViewText(camIds[col], "📷")
                } else {
                    views.setViewVisibility(camIds[col], View.INVISIBLE)
                }

                // Viento
                if (showWind) {
                    views.setViewVisibility(windIds[col], View.VISIBLE)
                    val windSpeed = d.windSpeedVal
                    val isWindHigh = windSpeed >= 30
                    val windColor = if (isWindHigh) {
                        if (isDarkTheme) Color.parseColor("#FF453A") else Color.parseColor("#E53E3E")
                    } else {
                        if (isDarkTheme) Color.parseColor("#B0C4DE") else Color.parseColor("#4A5568")
                    }
                    views.setTextViewText(windIds[col], WeatherWidgetProvider.sanitizeWidgetWind(d.windText))
                    views.setTextColor(windIds[col], windColor)
                } else {
                    views.setViewVisibility(windIds[col], View.GONE)
                }

                // Temperaturas
                if (d.maxTemp.isNotEmpty()) {
                    val maxDisplay = if (useApparent && d.apparentMaxTemp.isNotEmpty()) d.apparentMaxTemp else d.maxTemp
                    val minDisplay = if (useApparent && d.apparentMinTemp.isNotEmpty()) d.apparentMinTemp else d.minTemp

                    val maxVal = maxDisplay.toIntOrNull() ?: 0
                    val minVal = minDisplay.toIntOrNull() ?: 99

                    val tempColor = when {
                        maxVal >= 30 -> if (isDarkTheme) Color.parseColor("#FF453A") else Color.parseColor("#E53E3E")
                        minVal <= 10 -> if (isDarkTheme) Color.parseColor("#63B3ED") else Color.parseColor("#3182CE")
                        else -> if (isDarkTheme) Color.parseColor("#FFFFFF") else Color.parseColor("#1A202C")
                    }

                    views.setTextViewText(tempIds[col], "$maxDisplay / $minDisplay")
                    views.setTextColor(tempIds[col], tempColor)
                }

                // Lluvia y UV
                val uvFormatted = if (showUv) WeatherWidgetProvider.formatUvRangeForWidget(d.uvStart, d.uvEnd) else ""
                val rainDisplay = if (showRain) d.rainStr else ""
                val uvIconColor = if (isDarkTheme) Color.parseColor("#ED8936") else Color.parseColor("#DD6B20")

                views.setTextViewText(rainIds[col], rainDisplay)
                views.setTextViewText(uvStartIds[col], uvFormatted)
                views.setTextViewText(uvIconIds[col], if (uvFormatted.isNotEmpty()) "UV" else "")
                views.setTextColor(uvIconIds[col], uvIconColor)

                // Deporte verde
                if (showSunlight) {
                    views.setViewVisibility(outdoorIds[col], View.VISIBLE)
                    views.setTextViewText(outdoorIds[col], d.outdoorBadge)

                    val count = d.outdoorBadge.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 0
                    val sportsColor = if (count > 0) {
                        if (isDarkTheme) Color.parseColor("#48BB78") else Color.parseColor("#2E7D32")
                    } else {
                        if (isDarkTheme) Color.parseColor("#A0AEC0") else Color.parseColor("#718096")
                    }
                    views.setTextColor(outdoorIds[col], sportsColor)
                } else {
                    views.setViewVisibility(outdoorIds[col], View.GONE)
                }

                // Fill-in Intent para abrir la app al tocar
                val fillInIntent = Intent()
                views.setOnClickFillInIntent(cardIds[col], fillInIntent)

            } else {
                // Columna vacía de relleno para la 2.ª fila -> INVISIBLE
                views.setViewVisibility(cardIds[col], View.INVISIBLE)
            }
        }

        return views
    }

    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount(): Int = 1
    override fun getItemId(position: Int): Long = position.toLong()
    override fun hasStableIds(): Boolean = true
}
