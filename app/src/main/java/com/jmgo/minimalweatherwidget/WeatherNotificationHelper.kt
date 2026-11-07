package com.jmgo.minimalweatherwidget

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

object WeatherNotificationHelper {

    const val EXTRA_SHOW_CURRENT_ALERTS = "extra_show_current_alerts"
    private const val WORK_TAG = "weather_notification_periodic_work"

    fun scheduleOrCancelWorker(context: Context) {
        val prefs = context.getSharedPreferences("minimal_widget_prefs", Context.MODE_PRIVATE)
        val notifEnabled = prefs.getBoolean("notif_enabled", false)

        val workManager = WorkManager.getInstance(context)

        if (notifEnabled) {
            WeatherNotificationWorker.createNotificationChannel(context)
            val workRequest = PeriodicWorkRequestBuilder<WeatherNotificationWorker>(12, TimeUnit.HOURS)
                .addTag(WORK_TAG)
                .build()

            workManager.enqueueUniquePeriodicWork(
                WORK_TAG,
                ExistingPeriodicWorkPolicy.UPDATE,
                workRequest
            )
        } else {
            workManager.cancelUniqueWork(WORK_TAG)
        }
    }

    fun triggerImmediateCheck(context: Context) {
        WeatherNotificationWorker.createNotificationChannel(context)
        val workRequest = OneTimeWorkRequestBuilder<WeatherNotificationWorker>()
            .build()
        WorkManager.getInstance(context).enqueue(workRequest)
    }

    fun showCurrentAlertsDialog(activity: Activity) {
        val prefs = activity.getSharedPreferences("minimal_widget_prefs", Context.MODE_PRIVATE)
        val notifEnabled = prefs.getBoolean("notif_enabled", false)

        val paddingPx = (16 * activity.resources.displayMetrics.density).toInt()
        val scrollView = ScrollView(activity)
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
        }
        scrollView.addView(container)

        val loadingView = TextView(activity).apply {
            text = "⏳ Consultando avisos para mañana..."
            textSize = 14f
            setTextColor(Color.parseColor("#4A5568"))
            setPadding(0, paddingPx, 0, paddingPx)
        }
        container.addView(loadingView)

        val dialog = AlertDialog.Builder(activity)
            .setTitle("📋 Avisos Meteorológicos para Mañana")
            .setView(scrollView)
            .setPositiveButton("Entendido") { d, _ -> d.dismiss() }
            .setNeutralButton("⚙️ Configuración") { d, _ ->
                d.dismiss()
                showNotificationSettingsDialog(activity)
            }
            .create()

        dialog.show()

        CoroutineScope(Dispatchers.IO).launch {
            val loc = WeatherWidgetProvider().resolveLocationAndMunicipio(activity)
            val alerts = mutableListOf<String>()

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

            val openMeteoData = WeatherWidgetProvider().fetchOpenMeteoFallbackAndCurrent(loc.lat, loc.lon, days = 3)
            val daysList = openMeteoData.second

            val today = daysList.firstOrNull()
            val tomorrow = if (daysList.size > 1) daysList[1] else null

            if (tomorrow != null) {
                if (windEnabled && tomorrow.windSpeedVal >= windThreshold) {
                    alerts.add("🌬️ Viento: ${tomorrow.windSpeedVal} km/h (límite > ${windThreshold.toInt()} km/h)")
                }
                val maxT = tomorrow.maxTemp.toIntOrNull() ?: 0
                if (tempMaxEnabled && maxT >= tempMaxThreshold) {
                    alerts.add("🌡️ Temp. Máxima: $maxT°C (límite > ${tempMaxThreshold.toInt()}°C)")
                }
                val minT = tomorrow.minTemp.toIntOrNull() ?: 0
                if (tempMinEnabled && minT <= tempMinThreshold) {
                    alerts.add("❄️ Temp. Mínima: $minT°C (límite < ${tempMinThreshold.toInt()}°C)")
                }
                val rainVal = tomorrow.rainStr.replace("💧", "").trim().toDoubleOrNull() ?: 0.0
                if (rainEnabled && rainVal >= rainThreshold) {
                    alerts.add("🌧️ Lluvia: ${tomorrow.rainStr} (límite ≥ ${rainThreshold} l)")
                }
                if (uvEnabled && tomorrow.uvMax >= uvThreshold) {
                    alerts.add("☀️ Índice UV: ${tomorrow.uvMax} (límite ≥ ${uvThreshold.toInt()})")
                }
                if (moonEnabled && tomorrow.hasGoodMoonPhoto) {
                    alerts.add("📷 🌕 Ventana óptima para fotografiar la Luna (coincidencia Sol/Luna)")
                }
                if (tempChangeEnabled && today != null) {
                    val maxToday = today.maxTemp.toIntOrNull() ?: 0
                    val diff = kotlin.math.abs(maxT - maxToday)
                    if (diff >= tempChangeThreshold) {
                        val dir = if (maxT < maxToday) "baja" else "sube"
                        alerts.add("📉 Cambio brusco de temp: $dir $diff°C respecto a hoy (máx $maxT°C)")
                    }
                }
                if (stormEnabled && tomorrow.icon == "⛈️") {
                    alerts.add("⚡ Riesgo de Tormenta o Granizo para mañana")
                }
                if (fogEnabled && (tomorrow.icon == "🌫️" || tomorrow.icon.contains("fog", ignoreCase = true))) {
                    alerts.add("🌫️ Previsión de Niebla intensa o visibilidad reducida")
                }
                if (tidesEnabled) {
                    val coast = MainActivityHelper.getNearestCoastPoint(loc.lat, loc.lon)
                    if (coast != null) {
                        alerts.add("🌊 Aviso de Pleamar / Marea alta en zona costera (${coast.name})")
                    }
                }
                if (aemetWarningsEnabled) {
                    val exactDetails = fetchAemetExactWarnings(activity, loc.municipioCode)
                    if (exactDetails.isNotEmpty()) {
                        val headerText = "📢 Avisos Oficiales AEMET para Mañana:\n" + exactDetails.joinToString("\n")
                        alerts.add(headerText)
                    } else {
                        if (tomorrow.windSpeedVal >= 40 || rainVal >= 5.0 || tomorrow.icon == "⛈️") {
                            val apiKey = WeatherWidgetProvider.getAemetApiKey(activity)
                            val note = if (apiKey.isBlank()) "\n(Introduce tu Clave AEMET en el botón 🔑 AEMET para desglose oficial)" else ""
                            alerts.add("📢 Previsión AEMET: Viento de ${tomorrow.windSpeedVal} km/h y precipitaciones en la previsión oficial$note")
                        }
                    }
                }
            }

            withContext(Dispatchers.Main) {
                container.removeAllViews()

                val locHeader = TextView(activity).apply {
                    text = "📍 Ubicación: ${loc.cityName}"
                    textSize = 15f
                    setTextColor(Color.parseColor("#1A202C"))
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(0, 0, 0, paddingPx / 2)
                }
                container.addView(locHeader)

                if (!notifEnabled) {
                    val disabledText = TextView(activity).apply {
                        text = "⚠️ Las notificaciones están desactivadas.\n\nPuedes activarlas abajo en '⚙️ Configuración'."
                        textSize = 13.5f
                        setTextColor(Color.parseColor("#DD6B20"))
                        setPadding(0, 8, 0, 8)
                    }
                    container.addView(disabledText)
                } else if (alerts.isEmpty()) {
                    val noAlertsText = TextView(activity).apply {
                        text = "✅ No hay avisos meteorológicos previstos para mañana según tus 10 condiciones configuradas."
                        textSize = 14f
                        setTextColor(Color.parseColor("#276749"))
                        setPadding(0, 8, 0, 8)
                    }
                    container.addView(noAlertsText)
                } else {
                    val alertsTitle = TextView(activity).apply {
                        text = "Avisos activos para mañana (${alerts.size}):"
                        textSize = 14f
                        setTextColor(Color.parseColor("#E53E3E"))
                        typeface = Typeface.DEFAULT_BOLD
                        setPadding(0, 4, 0, 8)
                    }
                    container.addView(alertsTitle)

                    for (alert in alerts) {
                        val card = TextView(activity).apply {
                            text = alert
                            textSize = 13.5f
                            setTextColor(Color.parseColor("#2D3748"))
                            setPadding(paddingPx, paddingPx * 3 / 4, paddingPx, paddingPx * 3 / 4)
                            background = GradientDrawable().apply {
                                setColor(Color.parseColor("#FFF5F5"))
                                cornerRadius = (8 * activity.resources.displayMetrics.density)
                                setStroke(2, Color.parseColor("#FEB2B2"))
                            }
                        }
                        val params = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { bottomMargin = paddingPx / 2 }
                        container.addView(card, params)
                    }
                }
            }
        }
    }

    fun showNotificationSettingsDialog(activity: Activity, onRequestPermission: (() -> Unit)? = null) {
        val prefs = activity.getSharedPreferences("minimal_widget_prefs", Context.MODE_PRIVATE)

        val paddingPx = (16 * activity.resources.displayMetrics.density).toInt()
        val smallPaddingPx = (8 * activity.resources.displayMetrics.density).toInt()

        val scrollView = ScrollView(activity)
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
        }
        scrollView.addView(container)

        // Botón superior para ver avisos actuales de mañana
        val btnViewCurrentAlerts = TextView(activity).apply {
            text = "📋 VER AVISOS ACTUALES DE MAÑANA"
            textSize = 13.5f
            setTextColor(Color.parseColor("#FFFFFF"))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, paddingPx, 0, paddingPx)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#3182CE"))
                cornerRadius = (8 * activity.resources.displayMetrics.density)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                showCurrentAlertsDialog(activity)
            }
        }
        val btnViewParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = paddingPx
        }
        container.addView(btnViewCurrentAlerts, btnViewParams)

        // Switch General
        val masterSwitch = Switch(activity).apply {
            text = "Activar avisos para el tiempo de mañana"
            textSize = 16f
            isChecked = prefs.getBoolean("notif_enabled", false)
            setPadding(0, 0, 0, smallPaddingPx)
        }
        container.addView(masterSwitch)

        val subtitle = TextView(activity).apply {
            text = "Configura las 11 condiciones independientes. Se notificarán únicamente aquellas que estén activadas y se cumplan para mañana:"
            textSize = 13f
            setTextColor(Color.parseColor("#888888"))
            setPadding(0, 0, 0, paddingPx)
        }
        container.addView(subtitle)

        // Helper para crear fila de condición con valor numérico
        fun createThresholdConditionRow(
            title: String,
            unitLabel: String,
            prefKeyEnabled: String,
            prefKeyThreshold: String,
            defaultEnabled: Boolean,
            defaultThreshold: Float,
            isDecimal: Boolean = false
        ): Triple<LinearLayout, Switch, EditText> {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, smallPaddingPx, 0, smallPaddingPx)
            }

            val switchRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val sw = Switch(activity).apply {
                text = title
                textSize = 14f
                isChecked = prefs.getBoolean(prefKeyEnabled, defaultEnabled)
            }

            switchRow.addView(sw, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(switchRow)

            val inputRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(paddingPx, 4, 0, 0)
                visibility = if (sw.isChecked) View.VISIBLE else View.GONE
            }

            val lblValue = TextView(activity).apply {
                text = "Valor umbral: "
                textSize = 13f
            }
            inputRow.addView(lblValue)

            val etValue = EditText(activity).apply {
                inputType = if (isDecimal) (InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
                else InputType.TYPE_CLASS_NUMBER
                val savedVal = prefs.getFloat(prefKeyThreshold, defaultThreshold)
                setText(if (isDecimal) savedVal.toString() else savedVal.toInt().toString())
                textSize = 14f
                setSelectAllOnFocus(true)
            }
            val etParams = LinearLayout.LayoutParams(180, LinearLayout.LayoutParams.WRAP_CONTENT)
            inputRow.addView(etValue, etParams)

            val lblUnit = TextView(activity).apply {
                text = " $unitLabel"
                textSize = 13f
            }
            inputRow.addView(lblUnit)

            row.addView(inputRow)

            sw.setOnCheckedChangeListener { _, isChecked ->
                inputRow.visibility = if (isChecked) View.VISIBLE else View.GONE
            }

            return Triple(row, sw, etValue)
        }

        // Helper para crear fila de condición simple On/Off
        fun createSimpleSwitchRow(title: String, prefKeyEnabled: String, defaultEnabled: Boolean = false): Switch {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, smallPaddingPx, 0, smallPaddingPx)
            }
            val sw = Switch(activity).apply {
                text = title
                textSize = 14f
                isChecked = prefs.getBoolean(prefKeyEnabled, defaultEnabled)
            }
            row.addView(sw, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            container.addView(row)
            return sw
        }

        // 1. Viento superior a 35 km/h
        val (rowWind, swWind, etWind) = createThresholdConditionRow(
            title = "1. 🌬️ Viento superior a:",
            unitLabel = "km/h",
            prefKeyEnabled = "notif_wind_enabled",
            prefKeyThreshold = "notif_wind_threshold",
            defaultEnabled = false,
            defaultThreshold = 35f,
            isDecimal = false
        )
        container.addView(rowWind)

        // 2. Temp Máxima superior a 35ºC
        val (rowTempMax, swTempMax, etTempMax) = createThresholdConditionRow(
            title = "2. 🌡️ Temp. Máxima superior a:",
            unitLabel = "ºC",
            prefKeyEnabled = "notif_temp_max_enabled",
            prefKeyThreshold = "notif_temp_max_threshold",
            defaultEnabled = false,
            defaultThreshold = 35f,
            isDecimal = false
        )
        container.addView(rowTempMax)

        // 3. Temp Mínima inferior a 10ºC
        val (rowTempMin, swTempMin, etTempMin) = createThresholdConditionRow(
            title = "3. ❄️ Temp. Mínima inferior a:",
            unitLabel = "ºC",
            prefKeyEnabled = "notif_temp_min_enabled",
            prefKeyThreshold = "notif_temp_min_threshold",
            defaultEnabled = false,
            defaultThreshold = 10f,
            isDecimal = false
        )
        container.addView(rowTempMin)

        // 4. Lluvia de 1l o más
        val (rowRain, swRain, etRain) = createThresholdConditionRow(
            title = "4. 🌧️ Previsión de lluvia de:",
            unitLabel = "l (mm) o más",
            prefKeyEnabled = "notif_rain_enabled",
            prefKeyThreshold = "notif_rain_threshold",
            defaultEnabled = false,
            defaultThreshold = 1f,
            isDecimal = true
        )
        container.addView(rowRain)

        // 5. Índice UV de 3 o superior
        val (rowUv, swUv, etUv) = createThresholdConditionRow(
            title = "5. ☀️ Índice UV de:",
            unitLabel = "o superior",
            prefKeyEnabled = "notif_uv_enabled",
            prefKeyThreshold = "notif_uv_threshold",
            defaultEnabled = false,
            defaultThreshold = 3f,
            isDecimal = false
        )
        container.addView(rowUv)

        // 6. Fotografiar la Luna (Ventana óptima)
        val swMoon = createSimpleSwitchRow(
            title = "6. 📷 🌕 Fotografiar la Luna (coincidencia Sol/Luna)",
            prefKeyEnabled = "notif_moon_enabled"
        )

        // 7. Cambio brusco de temperatura respecto a hoy (ej. 6ºC)
        val (rowTempChange, swTempChange, etTempChange) = createThresholdConditionRow(
            title = "7. 📉 Cambio brusco temp. respecto a hoy:",
            unitLabel = "ºC de variación",
            prefKeyEnabled = "notif_temp_change_enabled",
            prefKeyThreshold = "notif_temp_change_threshold",
            defaultEnabled = false,
            defaultThreshold = 6f,
            isDecimal = false
        )
        container.addView(rowTempChange)

        // 8. Tormentas o Granizo
        val swStorm = createSimpleSwitchRow(
            title = "8. ⚡ Riesgo de Tormenta o Granizo",
            prefKeyEnabled = "notif_storm_enabled"
        )

        // 9. Niebla o visibilidad reducida
        val swFog = createSimpleSwitchRow(
            title = "9. 🌫️ Niebla intensa o visibilidad reducida",
            prefKeyEnabled = "notif_fog_enabled"
        )

        // 10. Mareas / Pleamar en costa
        val swTides = createSimpleSwitchRow(
            title = "10. 🌊 Pleamar / Marea alta (en zonas de costa)",
            prefKeyEnabled = "notif_tides_enabled"
        )

        // 11. Avisos oficiales AEMET
        val swAemet = createSimpleSwitchRow(
            title = "11. 📢 Avisos oficiales AEMET (Fenómenos adversos)",
            prefKeyEnabled = "notif_aemet_warnings_enabled"
        )

        val btnTest = Button(activity).apply {
            text = "🔔 PROBAR AVISOS AHORA"
            textSize = 13f
            setOnClickListener {
                onRequestPermission?.invoke()
                triggerImmediateCheck(activity)
                Toast.makeText(activity, "Comprobando avisos de mañana...", Toast.LENGTH_SHORT).show()
            }
        }
        val btnParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = paddingPx
        }
        container.addView(btnTest, btnParams)

        AlertDialog.Builder(activity)
            .setTitle("⚙️ Ajustes de Avisos para Mañana")
            .setView(scrollView)
            .setPositiveButton("Guardar") { dialog, _ ->
                val editor = prefs.edit()
                val mainEnabled = masterSwitch.isChecked
                editor.putBoolean("notif_enabled", mainEnabled)

                // 1. Viento
                editor.putBoolean("notif_wind_enabled", swWind.isChecked)
                editor.putFloat("notif_wind_threshold", etWind.text.toString().toFloatOrNull() ?: 35f)

                // 2. Temp Max
                editor.putBoolean("notif_temp_max_enabled", swTempMax.isChecked)
                editor.putFloat("notif_temp_max_threshold", etTempMax.text.toString().toFloatOrNull() ?: 35f)

                // 3. Temp Min
                editor.putBoolean("notif_temp_min_enabled", swTempMin.isChecked)
                editor.putFloat("notif_temp_min_threshold", etTempMin.text.toString().toFloatOrNull() ?: 10f)

                // 4. Lluvia
                editor.putBoolean("notif_rain_enabled", swRain.isChecked)
                editor.putFloat("notif_rain_threshold", etRain.text.toString().toFloatOrNull() ?: 1f)

                // 5. UV
                editor.putBoolean("notif_uv_enabled", swUv.isChecked)
                editor.putFloat("notif_uv_threshold", etUv.text.toString().toFloatOrNull() ?: 3f)

                // 6. Luna
                editor.putBoolean("notif_moon_enabled", swMoon.isChecked)

                // 7. Cambio de Temp
                editor.putBoolean("notif_temp_change_enabled", swTempChange.isChecked)
                editor.putFloat("notif_temp_change_threshold", etTempChange.text.toString().toFloatOrNull() ?: 6f)

                // 8. Tormenta
                editor.putBoolean("notif_storm_enabled", swStorm.isChecked)

                // 9. Niebla
                editor.putBoolean("notif_fog_enabled", swFog.isChecked)

                // 10. Mareas
                editor.putBoolean("notif_tides_enabled", swTides.isChecked)

                // 11. AEMET
                editor.putBoolean("notif_aemet_warnings_enabled", swAemet.isChecked)

                editor.apply()

                if (mainEnabled) {
                    onRequestPermission?.invoke()
                }

                scheduleOrCancelWorker(activity)

                Toast.makeText(activity, "Ajustes de notificaciones guardados", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("Cancelar") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    fun fetchAemetExactWarnings(context: Context, municipioCode: String): List<String> {
        val apiKey = WeatherWidgetProvider.getAemetApiKey(context)
        if (municipioCode.isBlank() || apiKey.isBlank()) {
            return emptyList()
        }
        return try {
            val step1Url = "https://opendata.aemet.es/opendata/api/prediccion/especifica/municipio/diaria/$municipioCode/?api_key=${apiKey.trim()}"
            val step1Resp = httpGet(step1Url, java.nio.charset.Charset.forName("UTF-8")) ?: return emptyList()
            val step1Json = org.json.JSONObject(step1Resp)
            if (step1Json.optInt("estado") != 200) return emptyList()

            val datosUrl = step1Json.getString("datos")
            val step2Resp = httpGet(datosUrl, java.nio.charset.Charset.forName("ISO-8859-15")) ?: return emptyList()
            val rootArray = org.json.JSONArray(step2Resp)
            val diasArray = rootArray.getJSONObject(0)
                .getJSONObject("prediccion")
                .getJSONArray("dia")

            val tomorrowStr = java.time.LocalDate.now().plusDays(1).toString()
            var tomorrowObj: org.json.JSONObject? = null

            for (i in 0 until diasArray.length()) {
                val dObj = diasArray.getJSONObject(i)
                val fecha = dObj.optString("fecha", "").take(10)
                if (fecha == tomorrowStr) {
                    tomorrowObj = dObj
                    break
                }
            }

            if (tomorrowObj == null && diasArray.length() > 1) {
                tomorrowObj = diasArray.getJSONObject(1)
            }

            if (tomorrowObj == null) return emptyList()

            val details = mutableListOf<String>()

            // 1. Estado del cielo (descripción)
            val cieloArray = tomorrowObj.optJSONArray("estadoCielo")
            var skyDesc = ""
            if (cieloArray != null) {
                for (k in 0 until cieloArray.length()) {
                    val desc = cieloArray.optJSONObject(k)?.optString("descripcion", "")?.trim() ?: ""
                    if (desc.isNotBlank()) {
                        skyDesc = desc
                        break
                    }
                }
            }

            // 2. Probabilidad de precipitación máxima
            val probArray = tomorrowObj.optJSONArray("probPrecipitacion")
            var maxProb = 0
            if (probArray != null) {
                for (k in 0 until probArray.length()) {
                    val pVal = probArray.optJSONObject(k)?.optInt("value", 0) ?: 0
                    if (pVal > maxProb) maxProb = pVal
                }
            }

            // 3. Rachas máximas de viento
            val rachaArray = tomorrowObj.optJSONArray("rachaMax")
            var maxRacha = 0
            if (rachaArray != null) {
                for (k in 0 until rachaArray.length()) {
                    val rVal = rachaArray.optJSONObject(k)?.optInt("value", 0) ?: 0
                    if (rVal > maxRacha) maxRacha = rVal
                }
            }

            // 4. Cota de nieve
            val nieveArray = tomorrowObj.optJSONArray("cotaNieveProv")
            var nieveStr = ""
            if (nieveArray != null) {
                for (k in 0 until nieveArray.length()) {
                    val nVal = nieveArray.optJSONObject(k)?.optString("value", "") ?: ""
                    if (nVal.isNotBlank()) {
                        nieveStr = "$nVal m"
                        break
                    }
                }
            }

            if (skyDesc.isNotBlank()) {
                details.add("• Estado del cielo AEMET: $skyDesc")
            }
            if (maxProb > 0) {
                details.add("• Probabilidad de lluvia AEMET: $maxProb%")
            }
            if (maxRacha > 0) {
                details.add("• Rachas máx. de viento AEMET: $maxRacha km/h")
            }
            if (nieveStr.isNotBlank()) {
                details.add("• Cota de nieve AEMET: $nieveStr")
            }

            details
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun httpGet(urlStr: String, charset: java.nio.charset.Charset): String? {
        return try {
            val conn = (java.net.URL(urlStr).openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 10000
            }
            if (conn.responseCode != 200) return null
            conn.inputStream.bufferedReader(charset).use { it.readText() }
        } catch (e: Exception) {
            null
        }
    }
}
