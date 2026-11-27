package com.jmgo.minimalweatherwidget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent

class LightWeatherWidgetProvider : WeatherWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        // Ejecuta la lógica de actualización heredada de WeatherWidgetProvider,
        // la cual actualiza exclusivamente el widget blanco (R.layout.widget_weather_light).
        super.onUpdate(context, appWidgetManager, appWidgetIds)
    }
}
