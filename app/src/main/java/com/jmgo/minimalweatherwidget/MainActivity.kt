package com.jmgo.minimalweatherwidget

import android.Manifest
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.*

class MainActivity : ComponentActivity() {

    private companion object {
        // 29 minutos: así el temporizador de 30 minutos siempre encuentra la caché caducada
        const val CACHE_MILLIS = 29 * 60 * 1000L
        // Cada cuánto se actualiza solo mientras la app está abierta
        const val AUTO_REFRESH_INTERVAL_MS = 30 * 60 * 1000L
        const val UV_MIN_TO_SHOW = 3
    }

    private lateinit var rootLayout: FrameLayout
    private lateinit var contentLayout: LinearLayout
    private lateinit var stickyContainer: LinearLayout
    private lateinit var btnStarSticky: TextView
    private lateinit var stickyTitle: TextView
    private lateinit var sourceAemetView: TextView
    private lateinit var sourceBackupView: TextView
    private lateinit var stickyHeaderDay: TextView
    private lateinit var stickyTimeView: TextView
    private lateinit var scrollView: ScrollView
    private val dayHeadersInfo = mutableListOf<DayHeaderInfo>()

    private var lastLoadedItems = listOf<TimelineItem>()
    private var currentCityName = ""

    // Control de actualizaciones
    @Volatile private var isLoading = false
    private var permissionFlowDone = false
    private var pendingForceRefresh = false
    private val refreshHandler = Handler(Looper.getMainLooper())
    private val autoRefreshRunnable = object : Runnable {
        override fun run() {
            checkCacheAndLoadData(forceRefresh = true)
            refreshHandler.postDelayed(this, AUTO_REFRESH_INTERVAL_MS)
        }
    }

    data class DayHeaderInfo(val topY: Int, val dayLabel: String)

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        permissionFlowDone = true
        val force = pendingForceRefresh
        pendingForceRefresh = false
        checkCacheAndLoadData(forceRefresh = force)
    }

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            WeatherNotificationHelper.scheduleOrCancelWorker(this)
        }
    }

    private fun checkAndRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun checkCurrentAlertsIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(WeatherNotificationHelper.EXTRA_SHOW_CURRENT_ALERTS, false) == true) {
            intent.removeExtra(WeatherNotificationHelper.EXTRA_SHOW_CURRENT_ALERTS)
            WeatherNotificationHelper.showCurrentAlertsDialog(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WeatherNotificationHelper.scheduleOrCancelWorker(this)

        if (savedInstanceState == null) {
            checkCurrentAlertsIntent(intent)
        }

        // Si venimos de pulsar el widget, forzamos la actualización de todo
        if (savedInstanceState == null &&
            intent?.getBooleanExtra(WeatherWidgetProvider.EXTRA_REFRESH_FROM_WIDGET, false) == true
        ) {
            pendingForceRefresh = true
            intent?.removeExtra(WeatherWidgetProvider.EXTRA_REFRESH_FROM_WIDGET)
        }

        rootLayout = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#F5F7FA"))
        }

        scrollView = ScrollView(this).apply {
            isFillViewport = true
            setOnScrollChangeListener { _, _, scrollY, _, _ ->
                updateStickyHeader(scrollY)
            }
        }

        contentLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(185), dp(16), dp(32))
        }

        sourceAemetView = TextView(this).apply {
            text = "• AEMET: Verificando..."
            textSize = 12f
            setTextColor(Color.parseColor("#4A5568"))
            setPadding(dp(4), dp(1), dp(4), dp(1))
        }

        sourceBackupView = TextView(this).apply {
            text = "• Fuente de respaldo: Verificando..."
            textSize = 12f
            setTextColor(Color.parseColor("#718096"))
            setPadding(dp(4), dp(1), dp(4), dp(10))
        }

        contentLayout.addView(sourceAemetView)
        contentLayout.addView(sourceBackupView)
        scrollView.addView(contentLayout)
        rootLayout.addView(scrollView)

        stickyContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#FFFFFF"))
            setPadding(dp(16), dp(36), dp(16), dp(8))
            elevation = dp(4).toFloat()
        }

        // Fila 1 (Superior): Hora de actualización a la izquierda | 🔑 AEMET + 🔔 Avisos a la derecha
        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(4))
        }

        stickyTimeView = TextView(this).apply {
            text = ""
            textSize = 12f
            setTextColor(Color.parseColor("#718096"))
            isSingleLine = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, dp(6), 0) }
        }

        val rightRow1Buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        }

        val btnAemetKey = TextView(this).apply {
            text = "🔑 AEMET"
            textSize = 12.5f
            setTextColor(Color.parseColor("#744210"))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            minHeight = dp(38)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#FEFCBF"))
                cornerRadius = dp(10).toFloat()
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { showAemetKeyDialog() }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, dp(6), 0) }
            layoutParams = lp
        }

        val btnNotif = TextView(this).apply {
            text = "🔔 Avisos"
            textSize = 12.5f
            setTextColor(Color.parseColor("#276749"))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            minHeight = dp(38)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F0FFF4"))
                cornerRadius = dp(10).toFloat()
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                WeatherNotificationHelper.showNotificationSettingsDialog(this@MainActivity) {
                    checkAndRequestNotificationPermission()
                }
            }
        }

        rightRow1Buttons.addView(btnAemetKey)
        rightRow1Buttons.addView(btnNotif)

        row1.addView(stickyTimeView)
        row1.addView(rightRow1Buttons)

        // Fila 2 (Intermedia): Cambiar Ubicación a la izquierda | Ayuda a la derecha
        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(4))
        }

        val btnChangeCity = TextView(this).apply {
            text = "📍 Cambiar ubicación"
            textSize = 12.5f
            setTextColor(Color.parseColor("#3182CE"))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            minHeight = dp(38)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EBF8FF"))
                cornerRadius = dp(10).toFloat()
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { showCitySelectorDialog() }
        }

        val row2Spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 0, 1.0f)
        }

        val btnHelpManual = TextView(this).apply {
            text = "❓ Ayuda"
            textSize = 12.5f
            setTextColor(Color.parseColor("#2C5282"))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            minHeight = dp(38)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EBF8FF"))
                cornerRadius = dp(10).toFloat()
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { showHelpManualDialog() }
        }

        row2.addView(btnChangeCity)
        row2.addView(row2Spacer)
        row2.addView(btnHelpManual)

        // Fila 3 (Inferior): Ubicación Actual + Estrella Favorita sola en su propia fila
        val row3 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(2))
        }

        stickyTitle = TextView(this).apply {
            id = android.R.id.text1
            text = "Cargando..."
            textSize = 19f
            setTextColor(Color.parseColor("#1A202C"))
            typeface = Typeface.DEFAULT_BOLD
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        btnStarSticky = TextView(this).apply {
            text = "☆"
            textSize = 24f
            setTextColor(Color.parseColor("#A0AEC0"))
            gravity = Gravity.CENTER
            minHeight = dp(38)
            minWidth = dp(38)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            isClickable = true
            isFocusable = true
            setOnClickListener { toggleCurrentCityFavorite() }
        }

        row3.addView(stickyTitle)
        row3.addView(btnStarSticky)

        stickyHeaderDay = TextView(this).apply {
            text = ""
            textSize = 15f
            setTextColor(Color.parseColor("#2B6CB0"))
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(2), 0, 0)
        }

        stickyContainer.addView(row1)
        stickyContainer.addView(row2)
        stickyContainer.addView(row3)
        stickyContainer.addView(stickyHeaderDay)

        val headerParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP
        }
        rootLayout.addView(stickyContainer, headerParams)

        setContentView(rootLayout)

        requestPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    // La app ya estaba abierta y el usuario pulsa el widget o una notificación
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        checkCurrentAlertsIntent(intent)
        if (intent.getBooleanExtra(WeatherWidgetProvider.EXTRA_REFRESH_FROM_WIDGET, false)) {
            intent.removeExtra(WeatherWidgetProvider.EXTRA_REFRESH_FROM_WIDGET)
            if (permissionFlowDone) {
                checkCacheAndLoadData(forceRefresh = true)
            } else {
                pendingForceRefresh = true
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Al volver a la app, solo recarga si los datos tienen más de 30 minutos
        if (permissionFlowDone) {
            checkCacheAndLoadData(forceRefresh = false)
        }
        refreshHandler.removeCallbacks(autoRefreshRunnable)
        refreshHandler.postDelayed(autoRefreshRunnable, AUTO_REFRESH_INTERVAL_MS)
    }

    override fun onPause() {
        super.onPause()
        refreshHandler.removeCallbacks(autoRefreshRunnable)
    }

    private fun isTownFavorite(townName: String): Boolean {
        val prefs = getSharedPreferences("minimal_widget_prefs", Context.MODE_PRIVATE)
        val favsJson = prefs.getString("favorite_towns_json", null) ?: return false
        return try {
            val arr = JSONArray(favsJson)
            for (i in 0 until arr.length()) {
                val name = arr.getJSONObject(i).optString("name", "")
                if (name.equals(townName, ignoreCase = true)) return true
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun toggleCurrentCityFavorite() {
        if (currentCityName.isBlank() || currentCityName == "Cargando...") return
        val prefs = getSharedPreferences("minimal_widget_prefs", Context.MODE_PRIVATE)
        val favsJson = prefs.getString("favorite_towns_json", null)
        val favsList = mutableListOf<WeatherWidgetProvider.KnownTown>()
        if (!favsJson.isNullOrEmpty()) {
            try {
                val arr = JSONArray(favsJson)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    favsList.add(
                        WeatherWidgetProvider.KnownTown(
                            name = o.getString("name"),
                            code = o.optString("code", ""),
                            lat = o.getDouble("lat"),
                            lon = o.getDouble("lon")
                        )
                    )
                }
            } catch (_: Exception) {}
        }

        val isAlreadyFav = favsList.any { it.name.equals(currentCityName, ignoreCase = true) }

        if (isAlreadyFav) {
            favsList.removeAll { it.name.equals(currentCityName, ignoreCase = true) }
        } else {
            val currentCode = prefs.getString("code", "11023") ?: "11023"
            val currentLat = prefs.getFloat("lat", 36.4578f).toDouble()
            val currentLon = prefs.getFloat("lon", -5.9272f).toDouble()
            favsList.add(
                WeatherWidgetProvider.KnownTown(
                    name = currentCityName,
                    code = currentCode,
                    lat = currentLat,
                    lon = currentLon
                )
            )
        }

        val newArr = JSONArray()
        for (t in favsList) {
            val o = JSONObject().apply {
                put("name", t.name)
                put("code", t.code)
                put("lat", t.lat)
                put("lon", t.lon)
            }
            newArr.put(o)
        }

        prefs.edit().putString("favorite_towns_json", newArr.toString()).apply()
        updateStarUI()
    }

    private fun updateStarUI() {
        val isFav = isTownFavorite(currentCityName)
        val starText = if (isFav) "★" else "☆"
        val starColor = if (isFav) Color.parseColor("#D69E2E") else Color.parseColor("#A0AEC0")

        if (::btnStarSticky.isInitialized) {
            btnStarSticky.text = starText
            btnStarSticky.setTextColor(starColor)
        }
    }

    private fun getFavoriteTowns(): List<WeatherWidgetProvider.KnownTown> {
        val prefs = getSharedPreferences("minimal_widget_prefs", Context.MODE_PRIVATE)
        val favsJson = prefs.getString("favorite_towns_json", null) ?: return emptyList()
        val list = mutableListOf<WeatherWidgetProvider.KnownTown>()
        try {
            val arr = JSONArray(favsJson)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    WeatherWidgetProvider.KnownTown(
                        name = o.getString("name"),
                        code = o.optString("code", ""),
                        lat = o.getDouble("lat"),
                        lon = o.getDouble("lon")
                    )
                )
            }
        } catch (_: Exception) {}
        return list
    }

    private fun showCitySelectorDialog() {
        val favoriteTowns = getFavoriteTowns()
        val options = mutableListOf<String>()
        options.add("📡 Usar ubicación GPS actual")
        options.add("🔍 Buscar municipio en España...")

        if (favoriteTowns.isEmpty()) {
            options.add("★ (Sin favoritas — pulsa ☆ para añadir)")
        } else {
            for (t in favoriteTowns) {
                options.add("★ ${t.name}")
            }
        }

        AlertDialog.Builder(this)
            .setTitle("Seleccionar ubicación")
            .setItems(options.toTypedArray()) { _, which ->
                val prefs = getSharedPreferences("minimal_widget_prefs", Context.MODE_PRIVATE)
                when (which) {
                    0 -> {
                        prefs.edit()
                            .putBoolean("manual_location", false)
                            .putLong("last_fetch_time", 0L)
                            .putLong("last_hourly_fetch_time", 0L)
                            .apply()
                        checkCacheAndLoadData(forceRefresh = true)
                    }
                    1 -> {
                        showSearchMunicipioAutocompleteDialog()
                    }
                    else -> {
                        if (favoriteTowns.isNotEmpty()) {
                            val town = favoriteTowns[which - 2]
                            prefs.edit()
                                .putBoolean("manual_location", true)
                                .putString("city", town.name)
                                .putString("code", town.code)
                                .putFloat("lat", town.lat.toFloat())
                                .putFloat("lon", town.lon.toFloat())
                                .putLong("last_fetch_time", 0L)
                                .putLong("last_hourly_fetch_time", 0L)
                                .apply()
                            checkCacheAndLoadData(forceRefresh = true)
                        }
                    }
                }
            }
            .show()
    }

    private fun showSearchMunicipioAutocompleteDialog() {
        val masterPrefs = getSharedPreferences("minimal_master_cache", Context.MODE_PRIVATE)
        val masterJsonStr = masterPrefs.getString("municipios_json", null)
        val townNamesList = mutableListOf<String>()

        if (!masterJsonStr.isNullOrEmpty()) {
            try {
                val arr = JSONArray(masterJsonStr)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    townNamesList.add(obj.getString("n"))
                }
            } catch (_: Exception) {}
        }

        if (townNamesList.isEmpty()) {
            townNamesList.addAll(listOf(
                "Madrid", "Barcelona", "Valencia", "Sevilla", "Zaragoza", "Málaga", "Murcia",
                "Palma", "Bilbao", "Alicante", "Córdoba", "Valladolid", "Vigo", "Gijón",
                "Granada", "Oviedo", "Cartagena", "Jerez de la Frontera", "Almería", "San Sebastián",
                "Burgos", "Santander", "Albacete", "San Fernando", "Logroño", "Badajoz", "Huelva",
                "Salamanca", "Marbella", "Tarragona", "León", "Cádiz", "Jaén", "Medina Sidonia", "Chiclana de la Frontera"
            ))
        }

        val input = AutoCompleteTextView(this).apply {
            hint = "Escribe para buscar municipio..."
            setPadding(dp(16), dp(16), dp(16), dp(16))
            threshold = 2
            setTextColor(Color.parseColor("#1A202C"))
            setHintTextColor(Color.parseColor("#A0AEC0"))
        }

        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, townNamesList)
        input.setAdapter(adapter)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(10))
            addView(input)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Buscar municipio en España")
            .setView(container)
            .setPositiveButton("Seleccionar", null)
            .setNegativeButton("Cancelar", null)
            .create()

        dialog.setOnShowListener {
            val button = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            button.setOnClickListener {
                val query = input.text.toString().trim()
                if (query.isNotEmpty()) {
                    dialog.dismiss()
                    searchAndSelectTown(query)
                }
            }
        }

        dialog.show()
    }

    private fun showHelpManualDialog() {
        val container = ScrollView(this).apply {
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }

        val htmlText = """
            <h3>📖 MANUAL COMPLETO Y GUÍA DE USO</h3>
            <p><b>Minimal Weather Widget</b> es una aplicación meteorológica avanzada y eficiente diseñada para ofrecer la máxima precisión meteorológica, avisos AEMET en tiempo real, eventos astronómicos y notificaciones personalizadas para tu día a día.</p>
            
            <hr/>
            
            <h4 style="color:#2B6CB0;">1. 📍 BÚSQUEDA DE UBICACIÓN Y FAVORITOS</h4>
            <ul>
              <li><b>Ubicación por GPS (📍 Cambiar ubicación)</b>: Detecta tu posición exacta automáticamente utilizando los servicios de geolocalización de tu dispositivo.</li>
              <li><b>Búsqueda Manual</b>: Puedes escribir el nombre de cualquier municipio de España o ciudad del mundo.</li>
              <li><b>⭐ Favoritos Rápidos</b>: Guarda tus ciudades habituales para cambiar entre ellas al instante con un solo toque desde la barra superior.</li>
            </ul>

            <h4 style="color:#2B6CB0;">2. 📱 WIDGETS DE PANTALLA DE INICIO (MODO CLARO Y OSCURO)</h4>
            <ul>
              <li><b>Widgets de 4 Días Adaptativos</b>: Consulta el tiempo actual y de los próximos 3 días sin necesidad de abrir la aplicación. Disponible en tema Oscuro y Claro.</li>
              <li><b>⚠️ FILO ROJO DE ALERTA METEOROLÓGICA</b>: Si hay algún aviso meteorológico de AEMET o meteorología adversa (<b>AMARILLO 🟡, NARANJA 🟠 o ROJO 🔴</b>) para hoy o los próximos días, <b>el widget mostrará automáticamente un borde rojo destacado</b> para advertirte al instante.</li>
              <li><b>🗓️ Destacado de Fin de Semana</b>: Las columnas de Sábado (<b>S</b>) y Domingo (<b>D</b>) incluyen un fondo redondeado y tono cálido especial para distinguirlos rápidamente.</li>
              <li><b>Simbología del Widget</b>:
                <br/>• <i>Cabecera</i>: Municipio, temperatura actual e índice UV (si UV ≥ 3).
                <br/>• <b>📷 🌕 (Camarita y Luna)</b>: Indica que en los próximos 4 días hay una ventana fotográfica óptima para fotografiar la Luna saliendo durante el atardecer.
                <br/>• <b>💧 Litros de Precipitación</b>: Muestra la precipitación prevista en mm por día (acumulación real en litros/m²).
                <br/>• <b>Horas + ☀️ (ej: 12/16 ☀️)</b>: Rango horario de inicio (arriba) y fin (abajo) de radiación UV alta (≥ 3).
                <br/>• <i>Colores de Temperatura</i>: Rojo (Máx ≥ 30 °C), Azul (Mín ≤ 10 °C).
                <br/>• <i>Colores de Viento</i>: Flechas con barras de intensidad (ej: ↗ ||| 41 km/h) marcadas en rojo si superan 30 km/h.
              </li>
            </ul>

            <h4 style="color:#2B6CB0;">3. ⚠️ TARJETA DE AVISOS Y ALERTAS ADVERSAS AEMET</h4>
            <ul>
              <li>Ubicada en la pantalla principal de la app entre la previsión de 7 días y la gráfica por horas.</li>
              <li>Combina los <b>Avisos Oficiales CAP de AEMET</b> con la evaluación detallada de lluvia, viento y tormentas.</li>
              <li>Muestra tarjetas distintivas:
                <br/>• 🟢 <b>Sin avisos meteorológicos adversos previstos</b>.
                <br/>• 🟡 <b>Aviso Amarillo</b>: Riesgo moderado / precaución.
                <br/>• 🟠 <b>Aviso Naranja</b>: Riesgo importante / estar preparado.
                <br/>• 🔴 <b>Aviso Rojo</b>: Riesgo extremo / tomar precauciones máximas.
              </li>
              <li>Al pulsar sobre la tarjeta o en el botón <b>🔔 Avisos</b> del encabezado, se despliega el resumen completo de fenómenos adversos por día.</li>
            </ul>

            <h4 style="color:#2B6CB0;">4. ⏱️ PREVISIÓN POR HORAS, ASTRONOMÍA Y MAREAS</h4>
            <ul>
              <li><b>Línea Temporal Hora a Hora</b>: Desplazamiento horizontal con el pronóstico de temperatura, estado del cielo, lluvia en mm, velocidad de viento y nivel UV.</li>
              <li><b>🌅 🌇 Sol y Fotoperíodo</b>: Horarios exactos de amanecer, atardecer y horas totales de luz solar.</li>
              <li><b>🌙 Fases Lunares y Ventana Fotográfica 📷</b>: Horarios de salida/puesta de la Luna y porcentaje de iluminación. Se marca en azul como <b>📷 ¡Óptima!</b> cuando la salida/puesta de la Luna coincide con el atardecer/amanecer solar (±60 min).</li>
              <li><b>🌊 Mareas en Zonas Costeras</b>: Muestra las horas y alturas en metros de Pleamar (marea alta) y Bajamar (marea baja).</li>
            </ul>

            <h4 style="color:#2B6CB0;">5. 🔔 NOTIFICACIONES AUTOMÁTICAS Y CONFIGURACIÓN DE ALERTAS</h4>
            <ul>
              <li><b>Supervisión Diaria en Segundo Plano</b>: Un servicio WorkManager analiza automáticamente la previsión para el día de mañana.</li>
              <li><b>11 Disparadores Personalizables</b>:
                <br/>• ☔ <i>Lluvia/Precipitación</i> (umbral en mm).
                <br/>• 💨 <i>Viento Fuerte</i> (umbral en km/h).
                <br/>• 🌡️ <i>Calor Extremo</i> (máxima en °C).
                <br/>• ❄️ <i>Frío Intenso</i> (mínima en °C).
                <br/>• 🌨️ <i>Nieve o Aguanieve</i>.
                <br/>• 🌩️ <i>Tormentas y Aparato Eléctrico</i>.
                <br/>• ☀️ <i>Radiación UV Elevada</i> (UV ≥ 6).
                <br/>• 🧊 <i>Heladas</i> (Mínima ≤ 0 °C).
                <br/>• 📈 <i>Cambio Brusco de Temperatura</i> (diferencia ≥ 6 °C entre hoy y mañana).
                <br/>• ⚠️ <i>Avisos Oficiales AEMET</i>.
                <br/>• 📷 <i>Ventana Fotográfica de Luna</i>.
              </li>
              <li><b>Programación de Hora</b>: Elige a qué hora del atardecer/noche deseas recibir el resumen de alerta (ej: 20:00).</li>
              <li><b>Vista Previa "Ver Alertas Previstas"</b>: Comprueba instantáneamente qué notificaciones se enviarían con el pronóstico actual.</li>
            </ul>

            <h4 style="color:#2B6CB0;">6. 🔑 CLAVE AEMET OPENDATA Y FUENTES DE DATOS</h4>
            <ul>
              <li><b>API Key AEMET (Opcional)</b>: Puedes vincular tu clave gratuita personal obtenida en AEMET OpenData desde el botón <b>🔑 AEMET</b> para obtener los datos oficiales del organismo estatal de meteorología en España.</li>
              <li><b>Open-Meteo (Respaldo Automático)</b>: Si no introduces clave o consultas una ubicación internacional, la app utiliza la API global Open-Meteo de forma transparente y sin coste.</li>
            </ul>
        """.trimIndent()

        val content = TextView(this).apply {
            text = android.text.Html.fromHtml(htmlText, android.text.Html.FROM_HTML_MODE_LEGACY)
            textSize = 13.5f
            setTextColor(Color.parseColor("#2D3748"))
            setLineSpacing(dp(2).toFloat(), 1.05f)
        }

        container.addView(content)

        AlertDialog.Builder(this)
            .setTitle("📖 Manual y Guía Completa de Uso")
            .setView(container)
            .setPositiveButton("Entendido", null)
            .show()
    }

    private fun showAemetKeyDialog() {
        val prefs = getSharedPreferences("minimal_widget_prefs", Context.MODE_PRIVATE)
        val currentKey = prefs.getString("user_aemet_api_key", "") ?: ""

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(10))
        }

        val msgView = TextView(this).apply {
            text = "AEMET OpenData ofrece claves gratuitas para consultar datos meteorológicos oficiales.\n\n" +
                    "• Si no introduces una clave, simplemente se usará Open-Meteo como fuente meteorológica de respaldo sin necesidad de registro.\n\n" +
                    "• Puedes solicitar tu clave gratuita en:\nhttps://opendata.aemet.es/centrodedescargas/altaUsuario"
            textSize = 13f
            setTextColor(Color.parseColor("#4A5568"))
            setPadding(0, 0, 0, dp(12))
        }

        val input = EditText(this).apply {
            hint = "Pega aquí tu API Key de AEMET"
            setText(currentKey)
            textSize = 13f
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F7FAFC"))
                setStroke(dp(1), Color.parseColor("#CBD5E0"))
                cornerRadius = dp(8).toFloat()
            }
        }

        container.addView(msgView)
        container.addView(input)

        AlertDialog.Builder(this)
            .setTitle("Clave API de AEMET")
            .setView(container)
            .setPositiveButton("Guardar") { dialog, _ ->
                val newKey = input.text.toString().trim()
                prefs.edit()
                    .putString("user_aemet_api_key", newKey)
                    .putLong("last_fetch_time", 0L)
                    .putLong("last_hourly_fetch_time", 0L)
                    .apply()

                notifyWidgetsUpdate()
                checkCacheAndLoadData(forceRefresh = true)
                dialog.dismiss()
            }
            .setNeutralButton("Obtener Clave") { _, _ ->
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://opendata.aemet.es/centrodedescargas/altaUsuario"))
                    startActivity(intent)
                } catch (_: Exception) {}
            }
            .setNegativeButton(if (currentKey.isNotEmpty()) "Borrar Clave" else "Cancelar") { dialog, _ ->
                if (currentKey.isNotEmpty()) {
                    prefs.edit()
                        .remove("user_aemet_api_key")
                        .putLong("last_fetch_time", 0L)
                        .putLong("last_hourly_fetch_time", 0L)
                        .apply()

                    notifyWidgetsUpdate()
                    checkCacheAndLoadData(forceRefresh = true)
                }
                dialog.dismiss()
            }
            .show()
    }

    private fun searchAndSelectTown(query: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val code = WeatherWidgetProvider.findAemetMunicipioCode(this@MainActivity, query)
            var lat = 36.4194
            var lon = -6.1494
            var resolvedName = query.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }

            try {
                val geoUrl = "https://geocoding-api.open-meteo.com/v1/search?name=${java.net.URLEncoder.encode(query, "UTF-8")}&count=1&language=es&format=json"
                val resp = httpGet(geoUrl, Charset.forName("UTF-8"))
                if (resp != null) {
                    val json = JSONObject(resp)
                    val results = json.optJSONArray("results")
                    if (results != null && results.length() > 0) {
                        val r = results.getJSONObject(0)
                        lat = r.optDouble("latitude", lat)
                        lon = r.optDouble("longitude", lon)
                        resolvedName = r.optString("name", resolvedName)
                    }
                }
            } catch (_: Exception) {}

            withContext(Dispatchers.Main) {
                if (code.isNotEmpty()) {
                    val newTown = WeatherWidgetProvider.KnownTown(resolvedName, code, lat, lon)
                    WeatherWidgetProvider.saveRecentTown(this@MainActivity, newTown)

                    val prefs = getSharedPreferences("minimal_widget_prefs", Context.MODE_PRIVATE)
                    prefs.edit()
                        .putBoolean("manual_location", true)
                        .putString("city", resolvedName)
                        .putString("code", code)
                        .putFloat("lat", lat.toFloat())
                        .putFloat("lon", lon.toFloat())
                        .putLong("last_fetch_time", 0L)
                        .putLong("last_hourly_fetch_time", 0L)
                        .apply()

                    checkCacheAndLoadData(forceRefresh = true)
                } else {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Municipio no encontrado")
                        .setMessage("No se ha encontrado el código oficial AEMET para '$query'. Pruebe seleccionando una sugerencia de la lista.")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
    }

    private fun notifyWidgetsUpdate() {
        try {
            val manager = AppWidgetManager.getInstance(this)

            // Actualizar widget oscuro con su propio ID
            val darkIds = manager.getAppWidgetIds(ComponentName(this, WeatherWidgetProvider::class.java))
            if (darkIds.isNotEmpty()) {
                val darkIntent = Intent(this, WeatherWidgetProvider::class.java).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, darkIds)
                }
                sendBroadcast(darkIntent)
            }

            // Actualizar widget claro con su propio ID
            val lightIds = manager.getAppWidgetIds(ComponentName(this, LightWeatherWidgetProvider::class.java))
            if (lightIds.isNotEmpty()) {
                val lightIntent = Intent(this, LightWeatherWidgetProvider::class.java).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, lightIds)
                }
                sendBroadcast(lightIntent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun checkCacheAndLoadData(forceRefresh: Boolean) {
        // Una carga normal se ignora si ya hay otra en curso; una forzada siempre se ejecuta
        if (!forceRefresh && isLoading) return
        isLoading = true

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prefs = getSharedPreferences("minimal_widget_prefs", Context.MODE_PRIVATE)
                val now = System.currentTimeMillis()

                val lastHourlyFetch = prefs.getLong("last_hourly_fetch_time", 0L)
                val savedHourlyVersion = prefs.getInt("hourly_cache_version", 1)
                val savedHourlyDate = prefs.getString("cached_hourly_date", "")
                val currentDateStr = LocalDate.now().toString()
                val isSameDay = (savedHourlyDate == currentDateStr)
                val currentHourlyVersion = 12
                val isHourlyCacheValid = !forceRefresh && isSameDay && (savedHourlyVersion == currentHourlyVersion) && (now - lastHourlyFetch) < CACHE_MILLIS

                // Si la previsión por horas se va a descargar de nuevo, el widget también:
                // así se actualizan siempre a la vez
                if (!isHourlyCacheValid) {
                    prefs.edit().putLong("last_fetch_time", 0L).apply()
                }

                // Avisamos a ambos widgets de forma segura
                notifyWidgetsUpdate()

                val city = prefs.getString("city", "Medina Sidonia") ?: "Medina Sidonia"
                currentCityName = city
                val code = prefs.getString("code", "11023") ?: "11023"
                val lat = prefs.getFloat("lat", 36.4578f).toDouble()
                val lon = prefs.getFloat("lon", -5.9272f).toDouble()

                var itemsList = emptyList<TimelineItem>()
                var sourceUsed = prefs.getString("cached_hourly_source", "Fuente: AEMET + Open-Meteo Marine") ?: "Fuente: AEMET + Open-Meteo Marine"
                var updateTimeStr = prefs.getString("cached_update_time_str", "--:--") ?: "--:--"

                if (isHourlyCacheValid) {
                    itemsList = loadHourlyCache(prefs)
                }

                if (itemsList.isEmpty()) {
                    itemsList = fetchAemetHourlyWithSunMoonAndTides(code, lat, lon)
                    if (itemsList.isNotEmpty()) {
                        sourceUsed = "Fuente: AEMET + Open-Meteo (Sol/Mareas)"
                    } else {
                        itemsList = fetchOpenMeteoHourlyWithSunMoonAndTides(lat, lon)
                        sourceUsed = "Fuente: Open-Meteo (Meteo, Sol y Mareas)"
                    }

                    if (itemsList.isNotEmpty()) {
                        updateTimeStr = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
                        saveHourlyCache(prefs, itemsList, sourceUsed, updateTimeStr, now, currentHourlyVersion)
                    }
                }

                val dailySummaries = build7DaySummaries(code, lat, lon)
                lastLoadedItems = itemsList

                withContext(Dispatchers.Main) {
                    stickyTimeView.text = "Act: $updateTimeStr"
                    renderTimeline(city, itemsList, sourceUsed, dailySummaries)
                }
            } finally {
                isLoading = false
            }
        }
    }

    private fun saveHourlyCache(
        prefs: android.content.SharedPreferences,
        list: List<TimelineItem>,
        source: String,
        timeStr: String,
        timestamp: Long,
        version: Int
    ) {
        val arr = JSONArray()
        for (item in list) {
            val obj = JSONObject().apply {
                put("dayLabel", item.dayLabel)
                put("hour", item.hour)
                put("sortMinute", item.sortMinute)
                put("timeStr", item.timeStr)
                put("isAstroEvent", item.isAstroEvent)
                put("astroTitle", item.astroTitle)
                put("astroDetails", item.astroDetails)
                put("iconEmoji", item.iconEmoji)
                put("isGoodPhoto", item.isGoodPhoto)
                put("icon", item.icon)
                put("temp", item.temp)
                put("wind", item.wind)
                put("rainLiters", item.rainLiters)
                put("uv", item.uv)
            }
            arr.put(obj)
        }
        prefs.edit()
            .putInt("hourly_cache_version", version)
            .putString("cached_hourly_date", LocalDate.now().toString())
            .putString("cached_hourly_json", arr.toString())
            .putString("cached_hourly_source", source)
            .putString("cached_update_time_str", timeStr)
            .putLong("last_hourly_fetch_time", timestamp)
            .apply()
    }

    private fun loadHourlyCache(prefs: android.content.SharedPreferences): List<TimelineItem> {
        val jsonStr = prefs.getString("cached_hourly_json", null) ?: return emptyList()
        return try {
            val arr = JSONArray(jsonStr)
            val list = mutableListOf<TimelineItem>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    TimelineItem(
                        dayLabel = obj.optString("dayLabel"),
                        hour = obj.optString("hour"),
                        sortMinute = obj.optInt("sortMinute"),
                        timeStr = obj.optString("timeStr"),
                        isAstroEvent = obj.optBoolean("isAstroEvent"),
                        astroTitle = obj.optString("astroTitle"),
                        astroDetails = obj.optString("astroDetails"),
                        iconEmoji = obj.optString("iconEmoji"),
                        isGoodPhoto = obj.optBoolean("isGoodPhoto"),
                        icon = obj.optString("icon"),
                        temp = obj.optString("temp"),
                        wind = obj.optString("wind"),
                        rainLiters = obj.optString("rainLiters"),
                        uv = obj.optInt("uv", 0)
                    )
                )
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    data class DailySummary(
        val date: LocalDate,
        val dayName: String,
        val fullDayLabel: String,
        val icon: String,
        val windText: String,
        val windSpeedVal: Int,
        val maxTemp: String,
        val minTemp: String,
        val rainStr: String,
        val uvStart: String,
        val uvEnd: String
    )

    private fun build7DaySummaries(code: String, lat: Double, lon: Double): List<DailySummary> {
        val openMeteoData = WeatherWidgetProvider().fetchOpenMeteoFallbackAndCurrent(lat, lon, days = 7)
        val openMeteoDays = openMeteoData.second
        var fetchedDays = WeatherWidgetProvider().fetchAemetForecast(this, code, lat, lon, days = 7)

        if (fetchedDays.isEmpty() && openMeteoDays.isNotEmpty()) {
            fetchedDays = openMeteoDays
        } else if (fetchedDays.isNotEmpty() && openMeteoDays.isNotEmpty()) {
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

        val diasSemana = listOf("D", "L", "M", "X", "J", "V", "S")
        val list = mutableListOf<DailySummary>()
        val nowDate = LocalDate.now()

        for (i in 0 until minOf(7, fetchedDays.size)) {
            val d = fetchedDays[i]
            val date = nowDate.plusDays(i.toLong())
            val dayName = diasSemana[date.dayOfWeek.value % 7]
            val fullLabel = when (i) {
                0 -> "Hoy"
                1 -> "Mañana"
                else -> "${diasSemana[date.dayOfWeek.value % 7]} ${date.dayOfMonth}"
            }
            list.add(
                DailySummary(
                    date = date,
                    dayName = dayName,
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
            )
        }
        return list
    }

    private fun createSevenDayCard(d: DailySummary): View {
        val isWeekend = d.date.dayOfWeek.value >= 6 || d.dayName == "S" || d.dayName == "D"
        val bgColor = if (isWeekend) Color.parseColor("#EBF8FF") else Color.parseColor("#FFFFFF")
        val strokeColor = if (isWeekend) Color.parseColor("#BEE3F8") else Color.parseColor("#E2E8F0")

        val cardBg = GradientDrawable().apply {
            setColor(bgColor)
            cornerRadius = dp(12).toFloat()
            setStroke(dp(1), strokeColor)
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = cardBg
            setPadding(dp(8), dp(10), dp(8), dp(10))
            val lp = LinearLayout.LayoutParams(dp(86), dp(112)).apply {
                setMargins(0, 0, dp(8), 0)
            }
            layoutParams = lp
            isClickable = true
            isFocusable = true
        }

        val tvDayIcon = TextView(this).apply {
            text = "${d.dayName} ${d.icon}"
            textSize = 15f
            setTextColor(Color.parseColor("#D69E2E"))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            includeFontPadding = false
        }

        val isWindHigh = d.windSpeedVal >= 30
        val windColor = if (isWindHigh) Color.parseColor("#E53E3E") else Color.parseColor("#718096")
        val tvWind = TextView(this).apply {
            text = d.windText
            textSize = 11f
            setTextColor(windColor)
            gravity = Gravity.CENTER
            includeFontPadding = false
        }

        val maxVal = d.maxTemp.toIntOrNull() ?: 0
        val minVal = d.minTemp.toIntOrNull() ?: 99
        val tempColor = when {
            maxVal >= 30 -> Color.parseColor("#E53E3E")
            minVal <= 10 -> Color.parseColor("#3182CE")
            else -> Color.parseColor("#1A202C")
        }

        val tvTemp = TextView(this).apply {
            text = "${d.maxTemp} / ${d.minTemp}"
            textSize = 13f
            setTextColor(tempColor)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            includeFontPadding = false
        }

        val rainUvRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(26))
        }

        if (d.rainStr.isNotEmpty()) {
            val tvRain = TextView(this).apply {
                text = d.rainStr
                textSize = 11f
                setTextColor(Color.parseColor("#3182CE"))
                typeface = Typeface.DEFAULT_BOLD
                includeFontPadding = false
            }
            rainUvRow.addView(tvRain)
        }

        if (d.uvStart.isNotEmpty()) {
            val uvLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                if (d.rainStr.isNotEmpty()) setPadding(dp(3), 0, 0, 0)
            }
            val tvStart = TextView(this).apply {
                text = d.uvStart
                textSize = 9f
                setTextColor(Color.parseColor("#E53E3E"))
                typeface = Typeface.DEFAULT_BOLD
                includeFontPadding = false
            }
            val tvEnd = TextView(this).apply {
                text = d.uvEnd
                textSize = 9f
                setTextColor(Color.parseColor("#E53E3E"))
                typeface = Typeface.DEFAULT_BOLD
                includeFontPadding = false
            }
            uvLayout.addView(tvStart)
            uvLayout.addView(tvEnd)

            val tvSun = TextView(this).apply {
                text = "☀️"
                textSize = 9f
                includeFontPadding = false
            }
            val uvSunRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            uvSunRow.addView(uvLayout)
            uvSunRow.addView(tvSun)
            rainUvRow.addView(uvSunRow)
        }

        container.addView(tvDayIcon)
        container.addView(tvWind)
        container.addView(tvTemp)
        container.addView(rainUvRow)

        return container
    }

    private fun scrollToDayHeader(targetDayLabel: String) {
        for (i in 0 until contentLayout.childCount) {
            val child = contentLayout.getChildAt(i)
            val tag = child.tag as? String
            if (tag != null && tag.startsWith("DAY_HEADER_")) {
                val dayName = tag.removePrefix("DAY_HEADER_")
                if (dayName.contains(targetDayLabel, ignoreCase = true) || targetDayLabel.contains(dayName, ignoreCase = true)) {
                    scrollView.post {
                        scrollView.smoothScrollTo(0, maxOf(0, child.top - dp(135)))
                    }
                    break
                }
            }
        }
    }

    private fun createAemetWarningsCard(warnings: List<WeatherNotificationHelper.AemetDayWarning>): View {
        val paddingPx = dp(14)
        val hasWarnings = warnings.isNotEmpty()

        val cardBgColor = if (hasWarnings) "#FFF5F5" else "#F0FFF4"
        val cardStrokeColor = if (hasWarnings) "#FEB2B2" else "#C6F6D5"

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor(cardBgColor))
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), Color.parseColor(cardStrokeColor))
            }
            setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, dp(10), 0, dp(8))
            }
            layoutParams = lp
        }

        val tvHeader = TextView(this).apply {
            text = "⚠️ Avisos AEMET (Fenómenos Adversos)"
            textSize = 15.5f
            setTextColor(Color.parseColor("#1A202C"))
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(6))
        }
        container.addView(tvHeader)

        if (!hasWarnings) {
            val tvSub = TextView(this).apply {
                text = "🟢 Sin avisos meteorológicos adversos"
                textSize = 14f
                setTextColor(Color.parseColor("#276749"))
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, 0, 0, dp(4))
            }
            val tvDesc = TextView(this).apply {
                text = "No hay avisos amarillos, naranjas o rojos activos para esta ubicación en la predicción oficial AEMET."
                textSize = 13f
                setTextColor(Color.parseColor("#4A5568"))
            }
            container.addView(tvSub)
            container.addView(tvDesc)
        } else {
            for (w in warnings) {
                val itemLayout = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, dp(4), 0, dp(4))
                }
                val tvTitle = TextView(this).apply {
                    text = "${w.levelEmoji} ${w.dayLabel} - Aviso ${w.levelColor}"
                    textSize = 13.5f
                    val textColor = when (w.levelColor) {
                        "ROJO" -> "#E53E3E"
                        "NARANJA" -> "#DD6B20"
                        else -> "#D69E2E"
                    }
                    setTextColor(Color.parseColor(textColor))
                    typeface = Typeface.DEFAULT_BOLD
                }
                val tvDesc = TextView(this).apply {
                    text = w.description
                    textSize = 13f
                    setTextColor(Color.parseColor("#2D3748"))
                }
                itemLayout.addView(tvTitle)
                itemLayout.addView(tvDesc)
                container.addView(itemLayout)
            }
        }

        return container
    }

    private fun renderTimeline(city: String, list: List<TimelineItem>, sourceUsed: String, dailySummaries: List<DailySummary> = emptyList()) {
        contentLayout.removeAllViews()

        stickyTitle.text = city
        updateStarUI()

        val isAemetUsed = sourceUsed.contains("AEMET", ignoreCase = true) && !sourceUsed.contains("Open-Meteo (Meteo", ignoreCase = true)

        if (isAemetUsed) {
            sourceAemetView.text = "• AEMET: Tiempo, temperatura, viento y lluvia"
            sourceAemetView.setTextColor(Color.parseColor("#2B6CB0"))
            sourceBackupView.text = "• Open-Meteo (Respaldo): Índice UV, Sol y Mareas"
            sourceBackupView.setTextColor(Color.parseColor("#718096"))
        } else {
            sourceAemetView.text = "• AEMET: No disponible (usando respaldo)"
            sourceAemetView.setTextColor(Color.parseColor("#E53E3E"))
            sourceBackupView.text = "• Open-Meteo (Respaldo): Tiempo, temp., viento, lluvia, UV, Sol y Mareas"
            sourceBackupView.setTextColor(Color.parseColor("#2B6CB0"))
        }

        contentLayout.addView(sourceAemetView)
        contentLayout.addView(sourceBackupView)

        // Resumen 7 Días en la parte superior
        if (dailySummaries.isNotEmpty()) {
            val tvSummaryTitle = TextView(this).apply {
                text = "📅 Previsión 7 Días"
                textSize = 15f
                setTextColor(Color.parseColor("#2B6CB0"))
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(4), dp(10), dp(4), dp(4))
            }
            contentLayout.addView(tvSummaryTitle)

            val summaryScroll = HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                setPadding(0, dp(2), 0, dp(10))
            }
            val summaryRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            for (day in dailySummaries) {
                val card = createSevenDayCard(day)
                card.setOnClickListener {
                    scrollToDayHeader(day.fullDayLabel)
                }
                summaryRow.addView(card)
            }
            summaryScroll.addView(summaryRow)
            contentLayout.addView(summaryScroll)
        }

        // Tarjeta de Avisos AEMET (Fenómenos Adversos)
        val prefs = getSharedPreferences("minimal_widget_prefs", Context.MODE_PRIVATE)
        val code = prefs.getString("code", "11023") ?: "11023"
        val lat = prefs.getFloat("lat", 36.4578f).toDouble()
        val lon = prefs.getFloat("lon", -5.9272f).toDouble()

        val aemetWarnings = WeatherNotificationHelper.fetchAemetMultiDayWarnings(this, code, lat, lon, dailySummaries)
        val aemetWarningsCard = createAemetWarningsCard(aemetWarnings)
        contentLayout.addView(aemetWarningsCard)

        dayHeadersInfo.clear()

        if (list.isEmpty()) {
            val err = TextView(this).apply {
                text = "No se pudieron cargar los datos. Comprueba tu conexión."
                textSize = 16f
                setTextColor(Color.parseColor("#4A5568"))
            }
            contentLayout.addView(err)
            return
        }

        // Título de Previsión por Horas
        val tvHourlyTitle = TextView(this).apply {
            text = "⏱️ Previsión por Horas"
            textSize = 15f
            setTextColor(Color.parseColor("#2B6CB0"))
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(4), dp(14), dp(4), dp(2))
        }
        contentLayout.addView(tvHourlyTitle)

        var currentDayHeader = ""

        for (item in list) {
            if (item.dayLabel != currentDayHeader) {
                currentDayHeader = item.dayLabel
                val dayHeader = TextView(this).apply {
                    text = currentDayHeader
                    tag = "DAY_HEADER_$currentDayHeader"
                    textSize = 17f
                    setTextColor(Color.parseColor("#2B6CB0"))
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(dp(4), dp(22), dp(4), dp(8))
                }
                contentLayout.addView(dayHeader)
            }

            if (item.isAstroEvent) {
                val isMoon = item.astroTitle.contains("Luna")
                val isTide = item.astroTitle.contains("Pleamar") || item.astroTitle.contains("Bajamar")
                val isSpecialGreen = isMoon || isTide

                val cardColor = if (item.isGoodPhoto) "#EBF8FF" else if (isSpecialGreen) "#F0FFF4" else "#EDF2F7"
                val borderColor = if (item.isGoodPhoto) "#3182CE" else if (isSpecialGreen) "#38A169" else "#00000000"

                val astroBg = GradientDrawable().apply {
                    setColor(Color.parseColor(cardColor))
                    cornerRadius = dp(10).toFloat()
                    if (item.isGoodPhoto || isSpecialGreen) {
                        setStroke(dp(2), Color.parseColor(borderColor))
                    }
                }

                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = astroBg
                    setPadding(dp(14), dp(10), dp(14), dp(10))
                    val params = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    params.setMargins(0, 0, 0, dp(4))
                    layoutParams = params
                }

                val tvTime = TextView(this).apply {
                    text = item.timeStr
                    textSize = 14f
                    setTextColor(Color.parseColor("#2D3748"))
                    typeface = Typeface.DEFAULT_BOLD
                    isSingleLine = true
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.3f)
                }

                val tvDesc = TextView(this).apply {
                    text = "${item.iconEmoji} ${item.astroTitle}"
                    textSize = 14f
                    setTextColor(if (item.isGoodPhoto) Color.parseColor("#2B6CB0") else if (isSpecialGreen) Color.parseColor("#276749") else Color.parseColor("#2D3748"))
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2.8f)
                }

                val tvAzimuth = TextView(this).apply {
                    text = item.astroDetails
                    textSize = 12f
                    setTextColor(Color.parseColor("#4A5568"))
                    gravity = Gravity.END
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.9f)
                }

                row.addView(tvTime)
                row.addView(tvDesc)
                row.addView(tvAzimuth)
                contentLayout.addView(row)

            } else {
                val cardBg = GradientDrawable().apply {
                    setColor(Color.parseColor("#FFFFFF"))
                    cornerRadius = dp(14).toFloat()
                }

                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = cardBg
                    setPadding(dp(10), dp(10), dp(10), dp(10))
                    val params = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    params.setMargins(0, 0, 0, dp(6))
                    layoutParams = params
                }

                val tvHour = TextView(this).apply {
                    text = "${item.hour}:00"
                    textSize = 15f
                    setTextColor(Color.parseColor("#1A202C"))
                    typeface = Typeface.DEFAULT_BOLD
                    isSingleLine = true
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.3f)
                }

                val tvIcon = TextView(this).apply {
                    text = item.icon
                    textSize = 26f
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.7f)
                }

                val tempVal = item.temp.toIntOrNull() ?: 0
                val tempColor = when {
                    tempVal >= 35 -> Color.parseColor("#E53E3E")
                    tempVal <= 10 -> Color.parseColor("#3182CE")
                    else -> Color.parseColor("#2D3748")
                }

                val tvTemp = TextView(this).apply {
                    text = "${item.temp}°"
                    textSize = 17f
                    setTextColor(tempColor)
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.8f)
                }

                val windColor = if (item.wind.contains("|||")) Color.parseColor("#E53E3E") else Color.parseColor("#718096")
                val tvWind = TextView(this).apply {
                    text = item.wind
                    textSize = 13f
                    setTextColor(windColor)
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.4f)
                }

                // UV al lado de la lluvia: solo se muestra si es 3 o más (si no, el hueco queda vacío)
                val tvUv = TextView(this).apply {
                    text = if (item.uv >= UV_MIN_TO_SHOW) "UV${item.uv}" else ""
                    textSize = 13f
                    setTextColor(Color.parseColor("#E53E3E"))
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.8f)
                }

                val tvRain = TextView(this).apply {
                    text = item.rainLiters
                    textSize = 13f
                    setTextColor(Color.parseColor("#3182CE"))
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.END
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.8f)
                }

                row.addView(tvHour)
                row.addView(tvIcon)
                row.addView(tvTemp)
                row.addView(tvWind)
                row.addView(tvUv)
                row.addView(tvRain)

                contentLayout.addView(row)
            }
        }

        contentLayout.post {
            dayHeadersInfo.clear()
            for (i in 0 until contentLayout.childCount) {
                val child = contentLayout.getChildAt(i)
                val tag = child.tag as? String
                if (tag != null && tag.startsWith("DAY_HEADER_")) {
                    val dayName = tag.removePrefix("DAY_HEADER_")
                    dayHeadersInfo.add(DayHeaderInfo(child.top, dayName))
                }
            }
            updateStickyHeader(scrollView.scrollY)
        }
    }

    private fun updateStickyHeader(scrollY: Int) {
        if (dayHeadersInfo.isEmpty()) {
            stickyHeaderDay.text = ""
            return
        }
        val currentActiveDay = dayHeadersInfo.lastOrNull { scrollY >= it.topY - dp(125) }?.dayLabel
        if (currentActiveDay != null) {
            stickyHeaderDay.text = "📅 $currentActiveDay"
            stickyHeaderDay.visibility = TextView.VISIBLE
        } else {
            stickyHeaderDay.visibility = TextView.GONE
        }
    }

    data class CoastPoint(val name: String, val lat: Double, val lon: Double, val marineLat: Double, val marineLon: Double)
    private val coastPoints = listOf(
        CoastPoint("Cádiz / San Fernando / Chiclana", 36.4600, -6.1800, 36.4500, -6.2800),
        CoastPoint("Bahía de Algeciras", 36.1400, -5.4500, 36.1200, -5.4000),
        CoastPoint("Costa de la Luz (Tarifa/Zahara)", 36.0100, -5.6000, 36.0000, -5.6500),
        CoastPoint("Costa Noroeste (Sanlúcar/Rota)", 36.7800, -6.3500, 36.7500, -6.4200)
    )

    private fun getNearestCoastPoint(lat: Double, lon: Double): CoastPoint? {
        val r = 6371.0
        for (cp in coastPoints) {
            val dLat = Math.toRadians(cp.lat - lat)
            val dLon = Math.toRadians(cp.lon - lon)
            val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat)) * cos(Math.toRadians(cp.lat)) * sin(dLon / 2).pow(2)
            val dist = 2.0 * r * asin(sqrt(a))

            if (dist <= 15.0) return cp
        }
        return null
    }

    private fun fetchOpenMeteoSunTimes(lat: Double, lon: Double): Map<LocalDate, MainActivityHelper.SunStaticInfo> {
        val map = mutableMapOf<LocalDate, MainActivityHelper.SunStaticInfo>()
        try {
            val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&daily=sunrise,sunset&timezone=Europe%2FMadrid&forecast_days=4"
            val resp = httpGet(url, Charset.forName("UTF-8")) ?: return map
            val daily = JSONObject(resp).optJSONObject("daily") ?: return map
            val times = daily.getJSONArray("time")
            val rises = daily.getJSONArray("sunrise")
            val sets = daily.getJSONArray("sunset")
            for (i in 0 until times.length()) {
                val date = LocalDate.parse(times.getString(i).substring(0, 10))
                val rStr = rises.optString(i, "")
                val sStr = sets.optString(i, "")
                if (rStr.length >= 16 && sStr.length >= 16) {
                    val srH = rStr.substring(11, 13).toIntOrNull() ?: 7
                    val srM = rStr.substring(14, 16).toIntOrNull() ?: 30
                    val ssH = sStr.substring(11, 13).toIntOrNull() ?: 18
                    val ssM = sStr.substring(14, 16).toIntOrNull() ?: 30
                    map[date] = MainActivityHelper.SunStaticInfo(srH, srM, srH * 60 + srM, ssH, ssM, ssH * 60 + ssM)
                }
            }
        } catch (_: Exception) {}
        return map
    }

    // UV por hora de Open-Meteo (AEMET no lo incluye). Clave: "2026-11-05T11"
    private fun fetchOpenMeteoHourlyUv(lat: Double, lon: Double): Map<String, Int> {
        val map = mutableMapOf<String, Int>()
        try {
            val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&hourly=uv_index&timezone=Europe%2FMadrid&forecast_days=4"
            val resp = httpGet(url, Charset.forName("UTF-8")) ?: return map
            val hourly = JSONObject(resp).optJSONObject("hourly") ?: return map
            val times = hourly.getJSONArray("time")
            val uvs = hourly.optJSONArray("uv_index") ?: return map
            for (i in 0 until times.length()) {
                val v = uvs.optDouble(i, 0.0)
                if (v.isNaN()) continue
                map[times.getString(i).substring(0, 13)] = v.roundToInt()
            }
        } catch (_: Exception) {}
        return map
    }

    private fun fetchOpenMeteoTides(lat: Double, lon: Double): Map<LocalDate, List<AstroEvent>> {
        val map = mutableMapOf<LocalDate, MutableList<AstroEvent>>()
        val coast = getNearestCoastPoint(lat, lon) ?: return map
        try {
            val url = "https://marine-api.open-meteo.com/v1/marine?latitude=${coast.marineLat}&longitude=${coast.marineLon}&hourly=sea_level_height_msl&timezone=Europe%2FMadrid&forecast_days=4"
            val resp = httpGet(url, Charset.forName("UTF-8")) ?: return map
            val hourly = JSONObject(resp).optJSONObject("hourly") ?: return map
            val times = hourly.getJSONArray("time")
            val levels = hourly.optJSONArray("sea_level_height_msl") ?: return map

            val n = times.length()
            val heights = DoubleArray(n) { idx -> levels.optDouble(idx, Double.NaN) }

            for (i in 1 until n - 1) {
                val y1 = heights[i - 1]
                val y2 = heights[i]
                val y3 = heights[i + 1]
                if (y1.isNaN() || y2.isNaN() || y3.isNaN()) continue

                val isHigh = (y2 > y1 && y2 >= y3) || (y2 >= y1 && y2 > y3)
                val isLow = (y2 < y1 && y2 <= y3) || (y2 <= y1 && y2 < y3)

                if (isHigh || isLow) {
                    val denom = (y1 - 2.0 * y2 + y3)
                    val deltaHours = if (abs(denom) > 1e-5) (0.5 * (y1 - y3) / denom).coerceIn(-0.5, 0.5) else 0.0
                    val peakVal = y2 - 0.25 * (y1 - y3) * deltaHours

                    val tStr = times.getString(i)
                    val date = LocalDate.parse(tStr.substring(0, 10))
                    val baseHour = tStr.substring(11, 13).toIntOrNull() ?: 0
                    val baseMin = tStr.substring(14, 16).toIntOrNull() ?: 0

                    var exactMins = (baseHour * 60 + baseMin + (deltaHours * 60.0).roundToInt())
                    var eventDate = date
                    if (exactMins < 0) {
                        exactMins += 1440
                        eventDate = date.minusDays(1)
                    } else if (exactMins >= 1440) {
                        exactMins -= 1440
                        eventDate = date.plusDays(1)
                    }

                    val fH = (exactMins / 60).coerceIn(0, 23)
                    val fM = (exactMins % 60).coerceIn(0, 59)
                    val title = if (isHigh) "Pleamar" else "Bajamar"
                    val sign = if (peakVal >= 0) "+" else ""
                    val heightFormatted = String.format(Locale.US, "%s%.2f m", sign, peakVal)

                    val dayList = map.getOrPut(eventDate) { mutableListOf() }
                    if (dayList.none { abs(it.exactMinute - exactMins) < 120 && it.title == title }) {
                        dayList.add(
                            AstroEvent(
                                hourInt = fH,
                                exactMinute = exactMins,
                                timeStr = String.format("%02d:%02d", fH, fM),
                                title = title,
                                details = "Nivel: $heightFormatted",
                                iconEmoji = if (isHigh) "🌊" else "🏖️",
                                isGoodPhoto = false
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {}
        return map
    }

    private fun fetchAemetHourlyWithSunMoonAndTides(municipioCode: String, lat: Double, lon: Double): List<TimelineItem> {
        val apiKey = WeatherWidgetProvider.getAemetApiKey(this)
        if (municipioCode.isBlank() || apiKey.isBlank()) return emptyList()

        return try {
            val step1Url = "https://opendata.aemet.es/opendata/api/prediccion/especifica/municipio/horaria/$municipioCode/?api_key=${apiKey.trim()}"
            val step1Resp = httpGet(step1Url, Charset.forName("UTF-8")) ?: return emptyList()
            val step1Json = JSONObject(step1Resp)
            if (step1Json.optInt("estado") != 200) return emptyList()

            val datosUrl = step1Json.getString("datos")
            val step2Resp = httpGet(datosUrl, Charset.forName("ISO-8859-15")) ?: return emptyList()
            val rootArr = JSONArray(step2Resp)
            val diasArr = rootArr.getJSONObject(0).getJSONObject("prediccion").getJSONArray("dia")

            val result = mutableListOf<TimelineItem>()
            val nowDate = LocalDate.now()
            val nowHour = LocalTime.now().hour
            val diasSemana = listOf("Domingo", "Lunes", "Martes", "Miércoles", "Jueves", "Viernes", "Sábado")
            val nearCoast = getNearestCoastPoint(lat, lon) != null

            val sunMap = fetchOpenMeteoSunTimes(lat, lon)
            val tidesMap = if (nearCoast) fetchOpenMeteoTides(lat, lon) else emptyMap()
            val uvMap = fetchOpenMeteoHourlyUv(lat, lon)

            for (d in 0 until diasArr.length()) {
                val diaObj = diasArr.getJSONObject(d)
                val fechaStr = diaObj.getString("fecha").substring(0, 10)
                val date = LocalDate.parse(fechaStr, DateTimeFormatter.ofPattern("yyyy-MM-dd"))
                val dayLabel = when (date) {
                    nowDate -> "Hoy"
                    nowDate.plusDays(1) -> "Mañana (${diasSemana[date.dayOfWeek.value % 7]})"
                    else -> "${diasSemana[date.dayOfWeek.value % 7]} ${date.dayOfMonth}"
                }

                val astroEvents = buildAstroAndTideEventsForDay(
                    date, lat, lon, nearCoast,
                    sunMap[date],
                    tidesMap[date]
                )

                val tempMap = parseHourlyPeriodArray(diaObj.optJSONArray("temperatura"))
                val skyMap = parseHourlyPeriodArray(diaObj.optJSONArray("estadoCielo"))
                val windMap = parseHourlyWindArray(diaObj.optJSONArray("vientoAndRachaMax"))
                val precipMap = parseHourlyPeriodArray(diaObj.optJSONArray("precipitacion"))

                val dayItems = mutableListOf<TimelineItem>()

                for (h in 0..23) {
                    if (date == nowDate && h < nowHour) continue

                    val hKey = String.format("%02d", h)
                    val temp = tempMap[hKey] ?: continue
                    val sky = skyMap[hKey] ?: ""
                    val (dir, speed) = windMap[hKey] ?: Pair("C", 0)
                    val rawRain = precipMap[hKey] ?: "0"
                    val rainLiters = formatLiters(rawRain)
                    val windStr = "${windDirToArrow(dir)} ${windSpeedToBars(speed)} $speed"
                    val uvVal = uvMap["${date}T$hKey"] ?: 0

                    dayItems.add(
                        TimelineItem(
                            dayLabel = dayLabel,
                            hour = hKey,
                            sortMinute = h * 60,
                            timeStr = "",
                            isAstroEvent = false,
                            astroTitle = "", astroDetails = "", iconEmoji = "", isGoodPhoto = false,
                            icon = aemetSkyToEmoji(sky), temp = temp, wind = windStr, rainLiters = rainLiters,
                            uv = uvVal
                        )
                    )
                }

                for (astro in astroEvents) {
                    if (date == nowDate && astro.hourInt < nowHour) continue
                    dayItems.add(
                        TimelineItem(
                            dayLabel = dayLabel,
                            hour = String.format("%02d", astro.hourInt),
                            sortMinute = astro.exactMinute,
                            timeStr = astro.timeStr,
                            isAstroEvent = true,
                            astroTitle = astro.title,
                            astroDetails = astro.details,
                            iconEmoji = astro.iconEmoji,
                            isGoodPhoto = astro.isGoodPhoto,
                            icon = "", temp = "", wind = "", rainLiters = ""
                        )
                    )
                }

                result.addAll(dayItems.sortedBy { it.sortMinute })
            }
            result
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun fetchOpenMeteoHourlyWithSunMoonAndTides(lat: Double, lon: Double): List<TimelineItem> {
        return try {
            val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                    "&hourly=temperature_2m,precipitation,weather_code,wind_speed_10m,wind_direction_10m,uv_index" +
                    "&daily=sunrise,sunset&timezone=Europe%2FMadrid&forecast_days=4"
            val resp = httpGet(url, Charset.forName("UTF-8")) ?: return emptyList()
            val json = JSONObject(resp)
            val hourly = json.getJSONObject("hourly")
            val daily = json.optJSONObject("daily")

            val sunMap = mutableMapOf<LocalDate, MainActivityHelper.SunStaticInfo>()
            if (daily != null) {
                val dTimes = daily.getJSONArray("time")
                val rises = daily.getJSONArray("sunrise")
                val sets = daily.getJSONArray("sunset")
                for (i in 0 until dTimes.length()) {
                    val d = LocalDate.parse(dTimes.getString(i).substring(0, 10))
                    val rStr = rises.optString(i, "")
                    val sStr = sets.optString(i, "")
                    if (rStr.length >= 16 && sStr.length >= 16) {
                        val srH = rStr.substring(11, 13).toIntOrNull() ?: 7
                        val srM = rStr.substring(14, 16).toIntOrNull() ?: 30
                        val ssH = sStr.substring(11, 13).toIntOrNull() ?: 18
                        val ssM = sStr.substring(14, 16).toIntOrNull() ?: 30
                        sunMap[d] = MainActivityHelper.SunStaticInfo(srH, srM, srH * 60 + srM, ssH, ssM, ssH * 60 + ssM)
                    }
                }
            }

            val times = hourly.getJSONArray("time")
            val temps = hourly.getJSONArray("temperature_2m")
            val rains = hourly.getJSONArray("precipitation")
            val codes = hourly.getJSONArray("weather_code")
            val speeds = hourly.getJSONArray("wind_speed_10m")
            val dirs = hourly.getJSONArray("wind_direction_10m")
            val uvs = hourly.optJSONArray("uv_index")

            val result = mutableListOf<TimelineItem>()
            val nowDate = LocalDate.now()
            val nowHour = LocalTime.now().hour
            val diasSemana = listOf("Domingo", "Lunes", "Martes", "Miércoles", "Jueves", "Viernes", "Sábado")
            val nearCoast = getNearestCoastPoint(lat, lon) != null
            val tidesMap = if (nearCoast) fetchOpenMeteoTides(lat, lon) else emptyMap()

            val mapByDate = mutableMapOf<LocalDate, MutableList<TimelineItem>>()

            for (i in 0 until times.length()) {
                val tStr = times.getString(i)
                val dateStr = tStr.substring(0, 10)
                val hourStr = tStr.substring(11, 13)
                val minStr = tStr.substring(14, 16)
                val hInt = hourStr.toIntOrNull() ?: 0
                val mInt = minStr.toIntOrNull() ?: 0
                val date = LocalDate.parse(dateStr, DateTimeFormatter.ofPattern("yyyy-MM-dd"))

                if (date == nowDate && hInt < nowHour) continue

                val dayLabel = when (date) {
                    nowDate -> "Hoy"
                    nowDate.plusDays(1) -> "Mañana (${diasSemana[date.dayOfWeek.value % 7]})"
                    else -> "${diasSemana[date.dayOfWeek.value % 7]} ${date.dayOfMonth}"
                }

                val temp = temps.optDouble(i, 0.0).roundToInt().toString()
                val rainMm = rains.optDouble(i, 0.0)
                val rainLiters = formatLiters(rainMm.toString())
                val icon = wmoCodeToEmoji(codes.optInt(i, 0))
                val spd = speeds.optDouble(i, 0.0).roundToInt()
                val deg = dirs.optInt(i, 0)
                val windStr = "${degreesToArrow(deg)} ${windSpeedToBars(spd)} $spd"
                val uvRaw = uvs?.optDouble(i, 0.0) ?: 0.0
                val uvVal = if (uvRaw.isNaN()) 0 else uvRaw.roundToInt()

                val listForDay = mapByDate.getOrPut(date) { mutableListOf() }
                listForDay.add(
                    TimelineItem(
                        dayLabel = dayLabel, hour = hourStr, sortMinute = hInt * 60 + mInt, timeStr = "",
                        isAstroEvent = false, astroTitle = "", astroDetails = "", iconEmoji = "", isGoodPhoto = false,
                        icon = icon, temp = temp, wind = windStr, rainLiters = rainLiters,
                        uv = uvVal
                    )
                )
            }

            for ((date, dayItems) in mapByDate) {
                val astroEvents = buildAstroAndTideEventsForDay(
                    date, lat, lon, nearCoast,
                    sunMap[date],
                    tidesMap[date]
                )
                for (astro in astroEvents) {
                    if (date == nowDate && astro.hourInt < nowHour) continue
                    dayItems.add(
                        TimelineItem(
                            dayLabel = dayItems.firstOrNull()?.dayLabel ?: "",
                            hour = String.format("%02d", astro.hourInt),
                            sortMinute = astro.exactMinute,
                            timeStr = astro.timeStr,
                            isAstroEvent = true,
                            astroTitle = astro.title,
                            astroDetails = astro.details,
                            iconEmoji = astro.iconEmoji,
                            isGoodPhoto = astro.isGoodPhoto,
                            icon = "", temp = "", wind = "", rainLiters = ""
                        )
                    )
                }
                result.addAll(dayItems.sortedBy { it.sortMinute })
            }
            result
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun formatLiters(raw: String): String {
        val clean = raw.trim().replace(",", ".")
        if (clean.equals("Ip", ignoreCase = true)) return "0 l"
        val value = clean.toDoubleOrNull() ?: 0.0
        if (value <= 0.0) return ""
        if (value < 0.5) return "0 l"
        val rounded = value.roundToInt()
        if (rounded <= 0) return "0 l"
        return "$rounded l"
    }

    private data class AstroEvent(
        val hourInt: Int,
        val exactMinute: Int,
        val timeStr: String,
        val title: String,
        val details: String,
        val iconEmoji: String,
        val isGoodPhoto: Boolean
    )

    private fun buildAstroAndTideEventsForDay(
        date: LocalDate,
        lat: Double,
        lon: Double,
        nearCoast: Boolean,
        openMeteoSun: MainActivityHelper.SunStaticInfo?,
        openMeteoTides: List<AstroEvent>?
    ): List<AstroEvent> {
        val list = mutableListOf<AstroEvent>()
        val sun = openMeteoSun ?: MainActivityHelper.calculateSunTimesStatic(date, lat, lon)

        list.add(
            AstroEvent(
                hourInt = sun.sunriseHour.coerceIn(0, 23),
                exactMinute = sun.sunriseTotalMins,
                timeStr = String.format("%02d:%02d", sun.sunriseHour, sun.sunriseMin),
                title = "Salida del Sol",
                details = "",
                iconEmoji = "🌅",
                isGoodPhoto = false
            )
        )

        list.add(
            AstroEvent(
                hourInt = sun.sunsetHour.coerceIn(0, 23),
                exactMinute = sun.sunsetTotalMins,
                timeStr = String.format("%02d:%02d", sun.sunsetHour, sun.sunsetMin),
                title = "Puesta del Sol",
                details = "",
                iconEmoji = "🌇",
                isGoodPhoto = false
            )
        )

        val moonInfo = MainActivityHelper.calculateAccurateMoonData(date, lat, lon)

        if (moonInfo.riseTotalMins >= 0) {
            val diffRise = abs(sun.sunsetTotalMins - moonInfo.riseTotalMins)
            val isMoonRiseGood = diffRise <= 60
            list.add(
                AstroEvent(
                    hourInt = moonInfo.riseHour.coerceIn(0, 23),
                    exactMinute = moonInfo.riseTotalMins,
                    timeStr = moonInfo.riseTimeStr,
                    title = if (isMoonRiseGood) "Salida Luna (📷 ¡Óptima!)" else "Salida de la Luna",
                    details = "Azimut: ${moonInfo.riseAzimuth}°",
                    iconEmoji = moonInfo.phaseEmoji,
                    isGoodPhoto = isMoonRiseGood
                )
            )
        }

        if (moonInfo.setTotalMins >= 0) {
            val diffSet = abs(moonInfo.setTotalMins - sun.sunriseTotalMins)
            val isMoonSetGood = diffSet <= 60
            list.add(
                AstroEvent(
                    hourInt = moonInfo.setHour.coerceIn(0, 23),
                    exactMinute = moonInfo.setTotalMins,
                    timeStr = moonInfo.setTimeStr,
                    title = if (isMoonSetGood) "Puesta Luna (📷 ¡Óptima!)" else "Puesta de la Luna",
                    details = "Fase: ${moonInfo.phaseName} (${moonInfo.illumination}%)",
                    iconEmoji = moonInfo.phaseEmoji,
                    isGoodPhoto = isMoonSetGood
                )
            )
        }

        if (nearCoast) {
            if (!openMeteoTides.isNullOrEmpty()) {
                list.addAll(openMeteoTides)
            } else {
                val tideShift = (date.dayOfYear * 50) % 720
                val pleamar1Mins = (330 + tideShift) % 1440
                val bajamar1Mins = (pleamar1Mins + 372) % 1440
                val pleamar2Mins = (bajamar1Mins + 372) % 1440
                val bajamar2Mins = (pleamar2Mins + 372) % 1440

                val tideEvents = listOf(
                    Triple(pleamar1Mins, "Pleamar", String.format("%02d:%02d", pleamar1Mins / 60, pleamar1Mins % 60)),
                    Triple(bajamar1Mins, "Bajamar", String.format("%02d:%02d", bajamar1Mins / 60, bajamar1Mins % 60)),
                    Triple(pleamar2Mins, "Pleamar", String.format("%02d:%02d", pleamar2Mins / 60, pleamar2Mins % 60)),
                    Triple(bajamar2Mins, "Bajamar", String.format("%02d:%02d", bajamar2Mins / 60, bajamar2Mins % 60))
                )

                for (tide in tideEvents) {
                    val h = tide.first / 60
                    val title = tide.second
                    list.add(
                        AstroEvent(
                            hourInt = h.coerceIn(0, 23),
                            exactMinute = tide.first,
                            timeStr = tide.third,
                            title = title,
                            details = "",
                            iconEmoji = if (title == "Pleamar") "🌊" else "🏖️",
                            isGoodPhoto = false
                        )
                    )
                }
            }
        }

        return list.sortedBy { it.exactMinute }
    }

    private fun parseHourlyPeriodArray(arr: JSONArray?): Map<String, String> {
        val map = mutableMapOf<String, String>()
        if (arr == null) return map
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val p = obj.optString("periodo", "")
            val v = obj.optString("value", "")
            if (p.isNotEmpty() && v.isNotEmpty()) map[p] = v
        }
        return map
    }

    private fun parseHourlyWindArray(arr: JSONArray?): Map<String, Pair<String, Int>> {
        val map = mutableMapOf<String, Pair<String, Int>>()
        if (arr == null) return map
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val p = obj.optString("periodo", "")
            val dirArr = obj.optJSONArray("direccion")
            val velArr = obj.optJSONArray("velocidad")
            if (p.isNotEmpty() && dirArr != null && velArr != null && dirArr.length() > 0 && velArr.length() > 0) {
                val dir = dirArr.optString(0, "C")
                val vel = velArr.optString(0, "0").toIntOrNull() ?: 0
                map[p] = Pair(dir, vel)
            }
        }
        return map
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    data class TimelineItem(
        val dayLabel: String,
        val hour: String,
        val sortMinute: Int,
        val timeStr: String,
        val isAstroEvent: Boolean,
        val astroTitle: String,
        val astroDetails: String,
        val iconEmoji: String,
        val isGoodPhoto: Boolean,
        val icon: String,
        val temp: String,
        val wind: String,
        val rainLiters: String,
        val uv: Int = 0
    )
}

object MainActivityHelper {
    data class CoastPoint(val name: String, val lat: Double, val lon: Double, val marineLat: Double, val marineLon: Double)
    val coastPoints = listOf(
        CoastPoint("Cádiz / San Fernando / Chiclana", 36.4600, -6.1800, 36.4500, -6.2800),
        CoastPoint("Bahía de Algeciras", 36.1400, -5.4500, 36.1200, -5.4000),
        CoastPoint("Costa de la Luz (Tarifa/Zahara)", 36.0100, -5.6000, 36.0000, -5.6500),
        CoastPoint("Costa Noroeste (Sanlúcar/Rota)", 36.7800, -6.3500, 36.7500, -6.4200)
    )

    fun getNearestCoastPoint(lat: Double, lon: Double): CoastPoint? {
        val r = 6371.0
        for (cp in coastPoints) {
            val dLat = Math.toRadians(cp.lat - lat)
            val dLon = Math.toRadians(cp.lon - lon)
            val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat)) * cos(Math.toRadians(cp.lat)) * sin(dLon / 2).pow(2)
            val dist = 2.0 * r * asin(sqrt(a))
            if (dist <= 15.0) return cp
        }
        return null
    }

    data class AccurateMoonData(
        val phaseName: String,
        val phaseEmoji: String,
        val illumination: Int,
        val riseHour: Int,
        val riseMin: Int,
        val riseTotalMins: Int,
        val riseTimeStr: String,
        val riseAzimuth: Int,
        val setHour: Int,
        val setMin: Int,
        val setTotalMins: Int,
        val setTimeStr: String
    )

    data class SunStaticInfo(
        val sunriseHour: Int,
        val sunriseMin: Int,
        val sunriseTotalMins: Int,
        val sunsetHour: Int,
        val sunsetMin: Int,
        val sunsetTotalMins: Int
    )

    fun calculateSunTimesStatic(date: LocalDate, lat: Double, lon: Double): SunStaticInfo {
        val latRad = Math.toRadians(lat)
        val dayOfYear = date.dayOfYear
        val declination = 23.44 * sin(Math.toRadians(360.0 * (284 + dayOfYear) / 365.0))
        val declRad = Math.toRadians(declination)
        val cosHourAngle = -tan(latRad) * tan(declRad)
        val hourAngle = Math.toDegrees(acos(cosHourAngle.coerceIn(-1.0, 1.0)))
        val solarNoonUtc = 12.0 - (lon / 15.0)

        val zone = ZoneId.of("Europe/Madrid")
        val zdt = date.atStartOfDay(zone)
        val tzOffset = zdt.offset.totalSeconds / 3600.0

        val sunriseDecimal = solarNoonUtc - (hourAngle / 15.0) + tzOffset
        val sunsetDecimal = solarNoonUtc + (hourAngle / 15.0) + tzOffset

        val srH = sunriseDecimal.toInt().coerceIn(0, 23)
        val srM = ((sunriseDecimal - srH) * 60).toInt().coerceIn(0, 59)
        val ssH = sunsetDecimal.toInt().coerceIn(0, 23)
        val ssM = ((sunsetDecimal - ssH) * 60).toInt().coerceIn(0, 59)

        return SunStaticInfo(srH, srM, srH * 60 + srM, ssH, ssM, ssH * 60 + ssM)
    }

    fun calculateAccurateMoonData(date: LocalDate, lat: Double, lon: Double): AccurateMoonData {
        val zone = ZoneId.of("Europe/Madrid")
        val startOfDay = date.atStartOfDay(zone)

        var riseMins = -1
        var setMins = -1
        var riseAz = 90

        var prevAlt = getMoonAltAz(startOfDay, lat, lon).first
        val stepMins = 5
        for (m in stepMins..(24 * 60) step stepMins) {
            val t = startOfDay.plusMinutes(m.toLong())
            val (alt, az) = getMoonAltAz(t, lat, lon)
            if (prevAlt <= 0.0 && alt > 0.0 && riseMins == -1) {
                val denom = alt - prevAlt
                val frac = if (abs(denom) > 1e-6) (-prevAlt / denom).coerceIn(0.0, 1.0) else 0.0
                riseMins = ((m - stepMins) + frac * stepMins).roundToInt()
                riseAz = if (az.isNaN()) 90 else az.roundToInt()
            }
            if (prevAlt >= 0.0 && alt < 0.0 && setMins == -1) {
                val denom = prevAlt - alt
                val frac = if (abs(denom) > 1e-6) (prevAlt / denom).coerceIn(0.0, 1.0) else 0.0
                setMins = ((m - stepMins) + frac * stepMins).roundToInt()
            }
            prevAlt = alt
        }

        val midDay = startOfDay.plusHours(12)
        val (illum, phaseAngle) = getMoonIlluminationAndPhase(midDay)
        val (name, emoji) = when {
            illum < 3 -> Pair("Nueva", "🌑")
            phaseAngle < 180.0 && illum < 45 -> Pair("Creciente", "🌒")
            phaseAngle < 180.0 && illum in 45..55 -> Pair("Cuarto Creciente", "🌓")
            phaseAngle < 180.0 -> Pair("Gibosa Creciente", "🌔")
            illum > 97 -> Pair("Llena", "🌕")
            phaseAngle >= 180.0 && illum > 55 -> Pair("Gibosa Menguante", "🌖")
            phaseAngle >= 180.0 && illum in 45..55 -> Pair("Cuarto Menguante", "🌗")
            else -> Pair("Menguante", "🌘")
        }

        val rH = if (riseMins >= 0) (riseMins / 60).coerceIn(0, 23) else 0
        val rM = if (riseMins >= 0) (riseMins % 60).coerceIn(0, 59) else 0
        val sH = if (setMins >= 0) (setMins / 60).coerceIn(0, 23) else 0
        val sM = if (setMins >= 0) (setMins % 60).coerceIn(0, 59) else 0

        return AccurateMoonData(
            phaseName = name,
            phaseEmoji = emoji,
            illumination = illum,
            riseHour = rH,
            riseMin = rM,
            riseTotalMins = riseMins,
            riseTimeStr = if (riseMins >= 0) String.format("%02d:%02d", rH, rM) else "--:--",
            riseAzimuth = riseAz,
            setHour = sH,
            setMin = sM,
            setTotalMins = setMins,
            setTimeStr = if (setMins >= 0) String.format("%02d:%02d", sH, sM) else "--:--"
        )
    }

    private fun toDaysJ2000(zdt: ZonedDateTime): Double {
        val epochSec = zdt.toEpochSecond()
        return (epochSec - 946728000.0) / 86400.0
    }

    private fun getMoonAltAz(zdt: ZonedDateTime, lat: Double, lon: Double): Pair<Double, Double> {
        val d = toDaysJ2000(zdt)
        val L = Math.toRadians((218.316 + 13.176396 * d) % 360.0)
        val M = Math.toRadians((134.963 + 13.064993 * d) % 360.0)
        val F = Math.toRadians((93.272 + 13.229350 * d) % 360.0)
        val D = Math.toRadians((297.850 + 12.190749 * d) % 360.0)

        val lonEcl = L + Math.toRadians(
            6.289 * sin(M) +
                    1.274 * sin(2.0 * D - M) +
                    0.658 * sin(2.0 * D) +
                    0.214 * sin(2.0 * M) -
                    0.186 * sin(Math.toRadians((357.529 + 0.98560028 * d) % 360.0))
        )
        val latEcl = Math.toRadians(5.128 * sin(F) + 0.280 * sin(M + F) + 0.277 * sin(M - F))

        val e = Math.toRadians(23.439 - 0.00000036 * d)

        val ra = atan2(sin(lonEcl) * cos(e) - tan(latEcl) * sin(e), cos(lonEcl))
        val dec = asin((sin(latEcl) * cos(e) + cos(latEcl) * sin(e) * sin(lonEcl)).coerceIn(-1.0, 1.0))

        val gmst = (280.46061837 + 360.98564736629 * d) % 360.0
        val lst = Math.toRadians((gmst + lon + 360.0) % 360.0)
        val ha = lst - ra

        val latRad = Math.toRadians(lat)
        val sinAlt = sin(latRad) * sin(dec) + cos(latRad) * cos(dec) * cos(ha)
        val alt = Math.toDegrees(asin(sinAlt.coerceIn(-1.0, 1.0)))

        val y = sin(ha)
        val x = cos(ha) * sin(latRad) - tan(dec) * cos(latRad)
        val az = (Math.toDegrees(atan2(y, x)) + 180.0 + 360.0) % 360.0

        return Pair(alt - 0.125, az)
    }

    private fun getMoonIlluminationAndPhase(zdt: ZonedDateTime): Pair<Int, Double> {
        val d = toDaysJ2000(zdt)
        val D = (297.850 + 12.190749 * d) % 360.0
        val normD = if (D < 0) D + 360.0 else D
        val rawIllum = (1.0 - cos(Math.toRadians(normD))) / 2.0 * 100.0
        val illum = if (rawIllum.isNaN()) 50 else rawIllum.roundToInt().coerceIn(0, 100)
        return Pair(illum, normD)
    }
}