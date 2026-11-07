package com.jmgo.minimalweatherwidget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

class LightWeatherWidgetProvider : WeatherWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, LightWeatherWidgetProvider::class.java))
            onUpdate(context, manager, ids)
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        // Ejecuta la lógica completa de actualización heredada de WeatherWidgetProvider,
        // la cual actualiza tanto el widget oscuro como el widget claro (R.layout.widget_weather_light).
        super.onUpdate(context, appWidgetManager, appWidgetIds)
    }
}
