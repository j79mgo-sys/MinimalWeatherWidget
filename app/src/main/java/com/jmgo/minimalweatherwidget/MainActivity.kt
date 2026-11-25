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
import androidx.activity.enableEdgeToEdge
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
        enableEdgeToEdge()
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
            setPadding(dp(16), dp(195), dp(16), dp(32))
        }

        scrollView.addView(contentLayout)
        rootLayout.addView(scrollView)

        fun createHeaderIconButton(
            emojiIcon: String,
            onClick: () -> Unit
        ): TextView {
            return TextView(this).apply {
                text = emojiIcon
                textSize = 23f
                setTextColor(Color.parseColor("#1A202C"))
                gravity = Gravity.CENTER
                minWidth = dp(38)
                minHeight = dp(38)
                setPadding(dp(5), dp(2), dp(5), dp(2))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#EBF8FF"))
                    cornerRadius = dp(10).toFloat()
                    setStroke(dp(1), Color.parseColor("#BEE3F8"))
                }
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, dp(4), 0) }
                layoutParams = lp
            }
        }

        stickyContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#FFFFFF"))
            setPadding(dp(16), dp(36), dp(16), dp(8))
            elevation = dp(4).toFloat()
        }

        // Fila 1 (Superior): Nombre de ubicación + Estrella favorita
        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(4))
        }

        stickyTitle = TextView(this).apply {
            id = android.R.id.text1
            text = "Cargando..."
            textSize = 19f
            setTextColor(Color.parseColor("#1A202C"))
            typeface = Typeface.DEFAULT_BOLD
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
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

        row1.addView(stickyTitle)
        row1.addView(btnStarSticky)

        // Fila 2 (Intermedia): Act.: <hora> a la izquierda + Iconos de Configuración a la derecha
        val row2 = LinearLayout(this).apply {
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

        val row2LeftSpacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 0, 1.0f)
        }
        val row2RightSpacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 0, 1.0f)
        }

        val btnNotif = createHeaderIconButton("🔔") {
            WeatherNotificationHelper.showNotificationSettingsDialog(this@MainActivity) {
                checkAndRequestNotificationPermission()
            }
        }
        val btnTempMode = createHeaderIconButton("🌡️") { showTempTypeDialog() }
        val btnWidgetConfig = createHeaderIconButton("⚙️") { showWidgetConfigDialog() }
        val btnSports = createHeaderIconButton("🏃") { showOutdoorSportsSettingsDialog() }

        row2.addView(stickyTimeView)
        row2.addView(row2LeftSpacer)
        row2.addView(btnNotif)
        row2.addView(btnTempMode)
        row2.addView(btnWidgetConfig)
        row2.addView(btnSports)
        row2.addView(row2RightSpacer)

        // Fila 3 (Inferior): Ubicación, Mapas, Web, Embalses, Manual/Guía (Centrada)
        val row3 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(2))
        }

        val btnChangeCity = createHeaderIconButton("📍") { showCitySelectorDialog() }
        val btnMaps = createHeaderIconButton("🗺️") { showAemetMapsDialog() }
        val btnWeb = createHeaderIconButton("🌐") { openWebVersion() }
        val btnEmbalses = createHeaderIconButton("🏞️") { showReservoirsCommunitiesDialog() }
        val btnHelp = createHeaderIconButton("📖") { showHelpManualDialog() }

        row3.addView(btnChangeCity)
        row3.addView(btnMaps)
        row3.addView(btnWeb)
        row3.addView(btnEmbalses)
        row3.addView(btnHelp)

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

    private fun showAemetMapsDialog() {
        if (isFinishing || isDestroyed) return
        try {
            val dialog = android.app.Dialog(this, android.R.style.Theme_DeviceDefault_Light_NoActionBar_Fullscreen)

            val rootLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#F7FAFC"))
            }

            // Barra superior del mapa
            val headerBar = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(12))
                setBackgroundColor(Color.parseColor("#2B6CB0"))
            }

            val tvTitle = TextView(this).apply {
                text = "🗺️ Mapas de la AEMET"
                textSize = 17f
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val webView = android.webkit.WebView(this).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                settings.setGeolocationEnabled(true)
                webChromeClient = object : android.webkit.WebChromeClient() {
                    override fun onGeolocationPermissionsShowPrompt(
                        origin: String,
                        callback: android.webkit.GeolocationPermissions.Callback
                    ) {
                        callback.invoke(origin, true, false)
                    }
                }
                webViewClient = android.webkit.WebViewClient()
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            }

            val btnClose = TextView(this).apply {
                text = "✕ Cerrar"
                textSize = 14f
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(12), dp(6), dp(12), dp(6))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#2C5282"))
                    cornerRadius = dp(8).toFloat()
                }
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    try {
                        webView.destroy()
                    } catch (_: Exception) {}
                    try {
                        dialog.dismiss()
                    } catch (_: Exception) {}
                }
            }

            headerBar.addView(tvTitle)
            headerBar.addView(btnClose)
            rootLayout.addView(headerBar)

            // Barra de Pestañas de Mapas con scroll horizontal
            val tabsScroll = HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                setBackgroundColor(Color.parseColor("#EDF2F7"))
                setPadding(dp(8), dp(8), dp(8), dp(8))
            }

            val tabsLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            data class MapTab(val label: String, val url: String)
            val mapTabs = listOf(
                MapTab("⚠️ Avisos", "https://www.aemet.es/es/eltiempo/prediccion/avisos"),
                MapTab("🌧️ Radar Lluvia", "https://www.aemet.es/es/eltiempo/observacion/radar"),
                MapTab("🏖️ Playas", "https://www.aemet.es/es/eltiempo/prediccion/playas"),
                MapTab("🛰️ Satélite Meteosat", "https://www.aemet.es/es/eltiempo/observacion/satelite"),
                MapTab("🌡️ Temperaturas y Municipios", "https://www.aemet.es/es/eltiempo/prediccion/municipios"),
                MapTab("🔥 Incendios", "https://www.aemet.es/es/eltiempo/prediccion/incendios"),
                MapTab("⚡ Rayos y Tormentas", "https://www.aemet.es/es/eltiempo/observacion/rayos")
            )

            var selectedTabBtn: TextView? = null

            mapTabs.forEachIndexed { index, tab ->
                val btnTab = TextView(this).apply {
                    text = tab.label
                    textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    minHeight = dp(36)
                    setPadding(dp(12), dp(6), dp(12), dp(6))

                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(0, 0, dp(6), 0) }
                    layoutParams = lp

                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        selectedTabBtn?.apply {
                            setTextColor(Color.parseColor("#2D3748"))
                            background = GradientDrawable().apply {
                                setColor(Color.parseColor("#E2E8F0"))
                                cornerRadius = dp(8).toFloat()
                            }
                        }
                        selectedTabBtn = this
                        setTextColor(Color.WHITE)
                        background = GradientDrawable().apply {
                            setColor(Color.parseColor("#3182CE"))
                            cornerRadius = dp(8).toFloat()
                        }

                        try {
                            webView.loadUrl(tab.url)
                        } catch (_: Exception) {}
                    }
                }

                if (index == 0) {
                    selectedTabBtn = btnTab
                    btnTab.setTextColor(Color.WHITE)
                    btnTab.background = GradientDrawable().apply {
                        setColor(Color.parseColor("#3182CE"))
                        cornerRadius = dp(8).toFloat()
                    }
                    try {
                        webView.loadUrl(tab.url)
                    } catch (_: Exception) {}
                } else {
                    btnTab.setTextColor(Color.parseColor("#2D3748"))
                    btnTab.background = GradientDrawable().apply {
                        setColor(Color.parseColor("#E2E8F0"))
                        cornerRadius = dp(8).toFloat()
                    }
                }

                tabsLayout.addView(btnTab)
            }

            tabsScroll.addView(tabsLayout)
            rootLayout.addView(tabsScroll)
            rootLayout.addView(webView)

            dialog.setContentView(rootLayout)
            dialog.show()
        } catch (e: Exception) {
            e.printStackTrace()
        }
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
              <li><b>Ubicación por GPS (📍 Ubicación)</b>: Detecta tu posición exacta automáticamente utilizando los servicios de geolocalización de tu dispositivo.</li>
              <li><b>Búsqueda Manual</b>: Puedes escribir el nombre de cualquier municipio de España o ciudad del mundo.</li>
              <li><b>⭐ Favoritos Rápidos</b>: Guarda tus ciudades habituales para cambiar entre ellas al instante con un solo toque desde la barra superior.</li>
            </ul>

            <h4 style="color:#2B6CB0;">2. 📱 WIDGETS 4x2 CON SCROLL VERTICAL</h4>
            <ul>
              <li><b>7 Días Desplazables</b>: Widget 4x2 claro u oscuro organizado en 2 filas de 4 columnas. Desliza hacia arriba dentro del widget para ver los días 5, 6 y 7.</li>
              <li><b>Toque en la Hora (HH:mm)</b>: Fuerza una descarga en red inmediata de datos actualizados.</li>
              <li><b>Toque en el Cuerpo</b>: Abre la aplicación principal.</li>
            </ul>

            <h4 style="color:#2B6CB0;">3. 🏃 SEMÁFORO DEPORTE AL AIRE LIBRE</h4>
            <ul>
              <li><b>Horas en Verde (🟢)</b>: Contabiliza las horas diurnas que cumplen tus criterios ideales de temperatura, lluvia, viento y UV.</li>
              <li><b>Configuración 🏃</b>: Ajusta tus umbrales personales de temperatura, viento y lluvia desde el botón del corredor.</li>
            </ul>

            <h4 style="color:#2B6CB0;">4. 📷 FOTOGRAFÍA LUNAR Y ASTRONOMÍA</h4>
            <ul>
              <li><b>Cámara Lunar 📷</b>: Indica noches con ventana fotográfica lunar óptima.</li>
              <li><b>Detalle Astronómico</b>: Pulsa sobre cualquier hora lunar o icono de cámara para abrir el desglose de acimut, elevación y crepúsculos civil y náutico.</li>
            </ul>

            <h4 style="color:#2B6CB0;">5. ⚙️ AJUSTES Y PERSONALIZACIÓN</h4>
            <ul>
              <li><b>Termómetro 🌡️</b>: Alterna al instante entre Temperatura Real y Sensación Térmica.</li>
              <li><b>Engranaje ⚙️</b>: Activa o desactiva la visibilidad de elementos en el widget.</li>
              <li><b>Favoritos ⭐</b>: Guarda tus municipios habituales para cambio rápido.</li>
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

            // Actualizar widget AEMET exclusivo 4x1
            val aemetIds = manager.getAppWidgetIds(ComponentName(this, AemetOnly4x1WidgetProvider::class.java))
            if (aemetIds.isNotEmpty()) {
                val aemetIntent = Intent(this, AemetOnly4x1WidgetProvider::class.java).apply {
                    action = AemetOnly4x1WidgetProvider.ACTION_REFRESH_AEMET_WIDGET
                }
                sendBroadcast(aemetIntent)
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

                if (!isSameDay) {
                    // Si ha cambiado el día, purgar la caché del día anterior
                    prefs.edit()
                        .remove("cached_hourly_json")
                        .remove("cached_hourly_date")
                        .remove("last_hourly_fetch_time")
                        .apply()
                } else if (isHourlyCacheValid) {
                    itemsList = loadHourlyCache(prefs)
                }

                if (itemsList.isEmpty()) {
                    itemsList = fetchOpenMeteoHourlyWithSunMoonAndTides(lat, lon)
                    sourceUsed = "Fuente: Open-Meteo"

                    if (itemsList.isNotEmpty()) {
                        updateTimeStr = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
                        saveHourlyCache(prefs, itemsList, sourceUsed, updateTimeStr, now, currentHourlyVersion)
                    } else {
                        withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(this@MainActivity, "⚠️ Error al conectar con Open-Meteo. Mostrando datos previos.", android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                }

                val filteredItemsList = filterFutureTimelineItems(itemsList)
                val dailySummaries = build7DaySummaries(code, lat, lon)
                lastLoadedItems = filteredItemsList

                withContext(Dispatchers.Main) {
                    stickyTimeView.text = "Act.: $updateTimeStr"
                    renderTimeline(city, filteredItemsList, sourceUsed, dailySummaries)
                }
            } finally {
                isLoading = false
            }
        }
    }

    private fun filterFutureTimelineItems(list: List<TimelineItem>): List<TimelineItem> {
        if (list.isEmpty()) return emptyList()
        val nowDate = LocalDate.now()
        val nowHour = LocalTime.now().hour
        val currentMinuteOfDay = nowHour * 60

        return list.filter { item ->
            val itemDate = try {
                if (item.itemDate.isNotBlank()) LocalDate.parse(item.itemDate) else null
            } catch (_: Exception) { null }

            if (itemDate != null && itemDate < nowDate) {
                return@filter false
            }

            if (item.dayLabel == "Hoy" || (itemDate != null && itemDate == nowDate)) {
                if (item.isAstroEvent) {
                    item.sortMinute >= currentMinuteOfDay
                } else {
                    val h = item.hour.toIntOrNull() ?: 0
                    h >= nowHour
                }
            } else {
                true
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
                put("itemDate", item.itemDate)
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
                        uv = obj.optInt("uv", 0),
                        itemDate = obj.optString("itemDate", "")
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
        val openMeteoData = WeatherWidgetProvider.fetchOpenMeteoFallbackAndCurrent(lat, lon, days = 7)
        val fetchedDays = openMeteoData.second

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

    private fun createSevenDayCard(d: DailySummary, warningLevel: String? = null): View {
        val isWeekend = d.date.dayOfWeek.value >= 6 || d.dayName == "S" || d.dayName == "D"

        val (bgColor, strokeColor, strokeWidthDp) = when (warningLevel?.uppercase()) {
            "ROJO" -> Triple(Color.parseColor("#FEE2E2"), Color.parseColor("#EF4444"), 2)
            "NARANJA" -> Triple(Color.parseColor("#FFEDD5"), Color.parseColor("#F97316"), 2)
            "AMARILLO" -> Triple(Color.parseColor("#FEF9C3"), Color.parseColor("#EAB308"), 2)
            else -> Triple(
                if (isWeekend) Color.parseColor("#EBF8FF") else Color.parseColor("#FFFFFF"),
                if (isWeekend) Color.parseColor("#BEE3F8") else Color.parseColor("#E2E8F0"),
                1
            )
        }

        val cardBg = GradientDrawable().apply {
            setColor(bgColor)
            cornerRadius = dp(12).toFloat()
            setStroke(dp(strokeWidthDp), strokeColor)
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

    private fun showLunarPhotoDetailSheet(date: LocalDate, city: String, lat: Double, lon: Double) {
        val moon = MainActivityHelper.calculateAccurateMoonData(date, lat, lon)
        val sun = MainActivityHelper.calculateSunTimesStatic(date, lat, lon)
        val zone = ZoneId.of("Europe/Madrid")
        val startOfDay = date.atStartOfDay(zone)

        val formatDay = date.format(DateTimeFormatter.ofPattern("EEEE, d 'de' MMMM", Locale("es", "ES")))

        val riseDetailsStr = if (moon.riseTotalMins >= 0) {
            val zdtRise = startOfDay.plusMinutes(moon.riseTotalMins.toLong())
            val (alt, az) = MainActivityHelper.getMoonAltAz(zdtRise, lat, lon)
            val altSign = if (alt >= 0) "+${alt.roundToInt()}°" else "${alt.roundToInt()}°"
            val azDeg = if (az.isNaN()) moon.riseAzimuth else az.roundToInt()
            "${moon.riseTimeStr}  •  ${azimuthToCardinal(azDeg)}  •  Elevación: $altSign"
        } else {
            "No hay salida de la luna este día"
        }

        val setDetailsStr = if (moon.setTotalMins >= 0) {
            val zdtSet = startOfDay.plusMinutes(moon.setTotalMins.toLong())
            val (alt, az) = MainActivityHelper.getMoonAltAz(zdtSet, lat, lon)
            val altSign = if (alt >= 0) "+${alt.roundToInt()}°" else "${alt.roundToInt()}°"
            val azDeg = if (az.isNaN()) 270 else az.roundToInt()
            "${moon.setTimeStr}  •  ${azimuthToCardinal(azDeg)}  •  Elevación: $altSign"
        } else {
            "No hay puesta de la luna este día"
        }

        val sunsetMins = sun.sunsetTotalMins
        val sunriseMins = sun.sunriseTotalMins

        val civilDuskMins = (sunsetMins + 30) % 1440
        val nauticalDuskMins = (sunsetMins + 60) % 1440
        val civilDawnMins = (sunriseMins - 30 + 1440) % 1440
        val nauticalDawnMins = (sunriseMins - 60 + 1440) % 1440

        fun fmtMins(m: Int) = String.format(Locale.US, "%02d:%02d", m / 60 % 24, m % 60)

        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(24))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#FFFFFF"))
                cornerRadii = floatArrayOf(
                    dp(20).toFloat(), dp(20).toFloat(),
                    dp(20).toFloat(), dp(20).toFloat(),
                    0f, 0f, 0f, 0f
                )
            }
        }

        val handle = View(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#CBD5E0"))
                cornerRadius = dp(3).toFloat()
            }
            val lp = LinearLayout.LayoutParams(dp(40), dp(5)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setMargins(0, 0, 0, dp(14))
            }
            layoutParams = lp
        }
        container.addView(handle)

        val tvHeaderTitle = TextView(this).apply {
            text = "📷 Detalle Fotografía Lunar — $city"
            textSize = 17f
            setTextColor(Color.parseColor("#2B6CB0"))
            typeface = Typeface.DEFAULT_BOLD
        }
        val tvHeaderSub = TextView(this).apply {
            text = formatDay.replaceFirstChar { it.uppercase() }
            textSize = 13f
            setTextColor(Color.parseColor("#718096"))
            setPadding(0, dp(2), 0, dp(12))
        }
        container.addView(tvHeaderTitle)
        container.addView(tvHeaderSub)

        val phaseCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F0FFF4"))
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), Color.parseColor("#A3E635"))
            }
            setPadding(dp(14), dp(10), dp(14), dp(10))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, dp(10))
            }
            layoutParams = lp
        }
        val tvPhaseEmoji = TextView(this).apply {
            text = moon.phaseEmoji
            textSize = 32f
            setPadding(0, 0, dp(12), 0)
        }
        val tvPhaseText = TextView(this).apply {
            text = "${moon.phaseName} — ${moon.illumination}% Iluminada"
            textSize = 15f
            setTextColor(Color.parseColor("#276749"))
            typeface = Typeface.DEFAULT_BOLD
        }
        phaseCard.addView(tvPhaseEmoji)
        phaseCard.addView(tvPhaseText)
        container.addView(phaseCard)

        fun addDetailRow(icon: String, title: String, value: String) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            val tvTitle = TextView(this).apply {
                text = "$icon $title"
                textSize = 13.5f
                setTextColor(Color.parseColor("#1A202C"))
                typeface = Typeface.DEFAULT_BOLD
            }
            val tvVal = TextView(this).apply {
                text = value
                textSize = 13f
                setTextColor(Color.parseColor("#4A5568"))
                setPadding(dp(22), dp(2), 0, 0)
            }
            row.addView(tvTitle)
            row.addView(tvVal)
            container.addView(row)
        }

        addDetailRow("🌅", "Salida de la Luna (Moonrise)", riseDetailsStr)
        addDetailRow("🌇", "Puesta de la Luna (Moonset)", setDetailsStr)

        val tvTwilightHeader = TextView(this).apply {
            text = "✨ Ventanas Óptimas de Luz Ambiente (Crepúsculos)"
            textSize = 14f
            setTextColor(Color.parseColor("#2B6CB0"))
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(10), 0, dp(4))
        }
        container.addView(tvTwilightHeader)

        addDetailRow("🌆", "Atardecer — Crepúsculo Civil / Náutico", "${fmtMins(civilDuskMins)} (Civil)  •  ${fmtMins(nauticalDuskMins)} (Náutico)")
        addDetailRow("🌄", "Amanecer — Crepúsculo Civil / Náutico", "${fmtMins(civilDawnMins)} (Civil)  •  ${fmtMins(nauticalDawnMins)} (Náutico)")

        dialog.setContentView(container)
        dialog.window?.let { w ->
            w.setLayout(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            w.setGravity(Gravity.BOTTOM)
            w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        }
        dialog.show()
    }

    private fun azimuthToCardinal(az: Int): String {
        val directions = arrayOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSO", "SO", "OSO", "O", "ONO", "NO", "NNO")
        val index = (Math.round((az % 360) / 22.5)).toInt() % 16
        val cleanIdx = if (index < 0) index + 16 else index
        return "$az° ${directions[cleanIdx]}"
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
        }

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

    private data class OutdoorRating(
        val label: String,
        val color: String,
        val bg: String,
        val border: String
    )

    private fun parseTempInt(tempStr: String): Int {
        val digits = tempStr.replace("°C", "").replace("°", "").trim()
        return digits.toIntOrNull() ?: 20
    }

    private fun parseWindInt(windStr: String): Int {
        val match = Regex("""(\d+)""").find(windStr)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: 10
    }

    private fun parseRainDouble(rainStr: String): Double {
        val clean = rainStr.replace("mm", "").replace("l/m²", "").replace("l", "").trim()
        return clean.toDoubleOrNull() ?: 0.0
    }

    private fun calculateOutdoorItemScore(item: TimelineItem): Double {
        val prefs = getSharedPreferences("minimal_widget_prefs", Context.MODE_PRIVATE)

        val optMinT = prefs.getInt("outdoor_temp_opt_min", 10)
        val optMaxT = prefs.getInt("outdoor_temp_opt_max", 24)
        val redMinT = prefs.getInt("outdoor_temp_red_min", 4)
        val redMaxT = prefs.getInt("outdoor_temp_red_max", 31)

        val rainOptMax = prefs.getFloat("outdoor_rain_opt_max", 0.0f).toDouble()
        val rainRedMax = prefs.getFloat("outdoor_rain_red_max", 1.5f).toDouble()

        val windOptMax = prefs.getInt("outdoor_wind_opt_max", 25)
        val windRedMax = prefs.getInt("outdoor_wind_red_max", 40)

        val uvOptMax = prefs.getInt("outdoor_uv_opt_max", 5)
        val uvRedMax = prefs.getInt("outdoor_uv_red_max", 8)

        val t = parseTempInt(item.temp)
        val w = parseWindInt(item.wind)
        val r = parseRainDouble(item.rainLiters)
        val uv = item.uv

        val tempLevel = when {
            t in optMinT..optMaxT -> 2
            t in redMinT..<optMinT || t in (optMaxT + 1)..redMaxT -> 1
            else -> 0
        }

        val rainLevel = when {
            r <= rainOptMax -> 2
            r <= rainRedMax -> 1
            else -> 0
        }

        val windLevel = when {
            w <= windOptMax -> 2
            w <= windRedMax -> 1
            else -> 0
        }

        val uvLevel = when {
            uv <= uvOptMax -> 2
            uv < uvRedMax -> 1
            else -> 0
        }

        val levels = listOf(tempLevel, rainLevel, windLevel, uvLevel)

        return when {
            levels.contains(0) -> {
                val redCount = levels.count { it == 0 }
                (35.0 - (redCount - 1) * 10.0).coerceAtLeast(10.0)
            }
            levels.contains(1) -> {
                val yellowCount = levels.count { it == 1 }
                (55.0 - (yellowCount - 1) * 5.0).coerceAtLeast(40.0)
            }
            else -> 95.0
        }
    }

    private fun createOutdoorSportsCard(list: List<TimelineItem>): View {
        val paddingPx = dp(14)

        val validItems = list.filter { !it.isAstroEvent }
        if (validItems.isEmpty()) {
            return View(this)
        }

        val dayGroupsMap = mutableMapOf<String, MutableList<TimelineItem>>()
        for (item in validItems) {
            val groupKey = item.dayLabel.ifEmpty { "Previsión" }
            dayGroupsMap.getOrPut(groupKey) { mutableListOf() }.add(item)
        }

        val fourDaysList = dayGroupsMap.entries.take(4)

        val firstDayItems = fourDaysList.firstOrNull()?.value ?: emptyList()
        val firstDayTimeItems = firstDayItems.filter { item ->
            val h = item.hour.split(":").firstOrNull()?.toIntOrNull() ?: 12
            h in 7..21
        }.ifEmpty { firstDayItems }

        val firstScores = firstDayTimeItems.map { calculateOutdoorItemScore(it) }
        val overallScore = if (firstScores.isNotEmpty()) firstScores.average().toInt() else 75

        val overallRating = when {
            overallScore >= 80 -> OutdoorRating("Excelente 🟢", "#2E7D32", "#E8F5E9", "#A5D6A7")
            overallScore >= 40 -> OutdoorRating("Moderado 🟡", "#F57F17", "#FFFDE7", "#FFF59D")
            else -> OutdoorRating("Poco Favorable 🔴", "#C62828", "#FFEBEE", "#EF9A9A")
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        for ((dayName, items) in fourDaysList) {
            val daytime = items.filter { item ->
                val hourInt = item.hour.split(":").firstOrNull()?.toIntOrNull() ?: 12
                hourInt in 7..21
            }.ifEmpty { items }

            val totalCount = daytime.size
            var redHours = 0
            var yellowHours = 0
            var greenHours = 0

            for (item in daytime) {
                val itemScore = calculateOutdoorItemScore(item)
                when {
                    itemScore >= 80.0 -> greenHours++
                    itemScore >= 40.0 -> yellowHours++
                    else -> redHours++
                }
            }

            val dayRating = when {
                redHours >= 3 -> OutdoorRating("Poco Favorable 🔴", "#C62828", "#FFEBEE", "#EF9A9A")
                yellowHours >= 3 -> OutdoorRating("Moderado 🟡", "#F57F17", "#FFFDE7", "#FFF59D")
                greenHours == totalCount && totalCount > 0 -> OutdoorRating("Excelente 🟢", "#2E7D32", "#E8F5E9", "#A5D6A7")
                redHours > 0 || yellowHours > 0 -> OutdoorRating("Moderado 🟡", "#F57F17", "#FFFDE7", "#FFF59D")
                else -> OutdoorRating("Excelente 🟢", "#2E7D32", "#E8F5E9", "#A5D6A7")
            }

            val dayAvgScore = if (daytime.isNotEmpty()) daytime.map { calculateOutdoorItemScore(it) }.average().toInt() else 75

            val dayRowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, dp(4))
            }

            val tvDayLabel = TextView(this).apply {
                text = "• $dayName"
                textSize = 14f
                setTextColor(Color.parseColor("#2D3748"))
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
            }

            val tvDayBadge = TextView(this).apply {
                text = "$dayAvgScore% • ${dayRating.label}"
                textSize = 13f
                setTextColor(Color.parseColor(dayRating.color))
                typeface = Typeface.DEFAULT_BOLD
                background = GradientDrawable().apply {
                    setColor(Color.WHITE)
                    cornerRadius = dp(10).toFloat()
                    setStroke(dp(1), Color.parseColor(dayRating.border))
                }
                setPadding(dp(10), dp(5), dp(10), dp(5))
            }

            dayRowLayout.addView(tvDayLabel)
            dayRowLayout.addView(tvDayBadge)
            container.addView(dayRowLayout)
        }

        val tips = mutableListOf<String>()
        val maxUv = firstDayTimeItems.maxOfOrNull { it.uv } ?: 0
        val maxWind = firstDayTimeItems.maxOfOrNull { parseWindInt(it.wind) } ?: 0
        val totalRain = firstDayTimeItems.sumOf { parseRainDouble(it.rainLiters) }
        val minTemp = firstDayTimeItems.minOfOrNull { parseTempInt(it.temp) } ?: 20
        val maxTemp = firstDayTimeItems.maxOfOrNull { parseTempInt(it.temp) } ?: 20

        if (maxUv >= 6) tips.add("🧴 Protección solar (UV $maxUv)")
        if (maxWind >= 25) tips.add("💨 Cortavientos ($maxWind km/h)")
        if (totalRain > 0) tips.add("🌧️ Ropa impermeable")
        if (minTemp < 12) tips.add("🧥 Ropa térmica / capas")
        if (maxTemp > 28) tips.add("💧 Hidratación alta")

        if (tips.isNotEmpty()) {
            val tvTips = TextView(this).apply {
                text = "💡 Consejos: ${tips.joinToString(" • ")}"
                textSize = 12.5f
                setTextColor(Color.parseColor("#4A5568"))
                setPadding(0, dp(8), 0, dp(2))
            }
            container.addView(tvTips)
        }

        return container
    }

    private fun showMasterSettingsDialog() {
        val options = arrayOf(
            "🔔 Configurar Avisos y Notificaciones",
            "🏃 Configuración Deportes al Aire Libre",
            "📖 Manual y Guía de Uso (Ayuda)"
        )
        AlertDialog.Builder(this)
            .setTitle("⚙️ Panel de Configuración")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> WeatherNotificationHelper.showNotificationSettingsDialog(this@MainActivity) {
                        checkAndRequestNotificationPermission()
                    }
                    1 -> showOutdoorSportsSettingsDialog()
                    2 -> showHelpManualDialog()
                }
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun openWebVersion() {
        try {
            val prefs = getSharedPreferences("minimal_widget_prefs", Context.MODE_PRIVATE)
            val publicUrl = prefs.getString("cfg_public_url", null) ?: "https://j79mgo-sys.github.io/MinimalWeatherWidget/"
            val webView = android.webkit.WebView(this).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = true
                settings.setGeolocationEnabled(true)
                webChromeClient = object : android.webkit.WebChromeClient() {
                    override fun onGeolocationPermissionsShowPrompt(
                        origin: String,
                        callback: android.webkit.GeolocationPermissions.Callback
                    ) {
                        callback.invoke(origin, true, false)
                    }
                }
                webViewClient = android.webkit.WebViewClient()
                loadUrl(publicUrl)
            }

            val topBar = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(Color.parseColor("#2B6CB0"))
                setPadding(dp(10), dp(4), dp(10), dp(4))
                gravity = Gravity.CENTER_VERTICAL
            }

            val titleTv = TextView(this).apply {
                text = "🌐 Web"
                setTextColor(Color.WHITE)
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
            }

            val btnShare = TextView(this).apply {
                text = "🔗 Compartir"
                setTextColor(Color.WHITE)
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(6), dp(2), dp(6), dp(2))
            }

            val btnClose = TextView(this).apply {
                text = "❌ Cerrar"
                setTextColor(Color.WHITE)
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(6), dp(2), dp(6), dp(2))
            }

            topBar.addView(titleTv)
            topBar.addView(btnShare)
            topBar.addView(btnClose)

            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(topBar)
                addView(webView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT))
            }

            val dialog = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
            dialog.setContentView(container)

            btnShare.setOnClickListener {
                val currentUrl = prefs.getString("cfg_public_url", null) ?: publicUrl
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Minimal Weather Widget Web")
                    putExtra(Intent.EXTRA_TEXT, "Consulta el tiempo en directo en nuestra versión web: $currentUrl")
                }
                startActivity(Intent.createChooser(shareIntent, "Compartir Versión Web"))
            }

            btnClose.setOnClickListener {
                dialog.dismiss()
            }

            dialog.show()
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "Error al abrir la versión web", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun showTempTypeDialog() {
        val prefs = getSharedPreferences("minimal_widget_prefs", Context.MODE_PRIVATE)
        val currentUseApparent = prefs.getBoolean("use_apparent_temp", false)

        val options = arrayOf(
            "🌡️ Temperatura Real (Máx / Mín)" + if (!currentUseApparent) "  ✓" else "",
            "🌡️ Sensación Térmica (Máx / Mín)" + if (currentUseApparent) "  ✓" else ""
        )

        AlertDialog.Builder(this)
            .setTitle("🌡️ Tipo de Temperatura en Widget")
            .setItems(options) { _, which ->
                val useApparent = (which == 1)
                prefs.edit().putBoolean("use_apparent_temp", useApparent).commit()
                val modeName = if (useApparent) "Sensación Térmica" else "Temperatura Real"
                android.widget.Toast.makeText(this, "Widget actualizado: $modeName", android.widget.Toast.LENGTH_SHORT).show()
                WeatherWidgetProvider.requestRefresh(this)
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun showWidgetConfigDialog() {
        val prefs = getSharedPreferences("minimal_widget_prefs", Context.MODE_PRIVATE)

        val showMoon = prefs.getBoolean("show_widget_moon", true)
        val showWind = prefs.getBoolean("show_widget_wind", true)
        val showRain = prefs.getBoolean("show_widget_rain", true)
        val showUv = prefs.getBoolean("show_widget_uv", true)
        val showSunlight = prefs.getBoolean("show_widget_sunlight", true)

        val scrollView = android.widget.ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        scrollView.addView(layout)

        fun createSectionTitle(title: String) {
            val tv = TextView(this@MainActivity).apply {
                text = title
                textSize = 14.5f
                setTextColor(Color.parseColor("#2B6CB0"))
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, dp(6), 0, dp(4))
            }
            layout.addView(tv)
        }

        createSectionTitle("👁️ Elementos Visibles en el Widget")

        fun createSwitchRow(label: String, initialChecked: Boolean): android.widget.CheckBox {
            val cb = android.widget.CheckBox(this@MainActivity).apply {
                text = label
                isChecked = initialChecked
                textSize = 13.5f
                setPadding(dp(4), dp(4), 0, dp(4))
            }
            layout.addView(cb)
            return cb
        }

        val cbMoon = createSwitchRow("📷 Cámara lunar (Noches óptimas)", showMoon)
        val cbWind = createSwitchRow("💨 Viento (Velocidad y racha)", showWind)
        val cbRain = createSwitchRow("💧 Lluvia (Previsión precipitación)", showRain)
        val cbUv = createSwitchRow("UV Índice UV (Franja UV ≥ 3)", showUv)
        val cbSunlight = createSwitchRow("🏃 Horas deporte (verde)", showSunlight)

        AlertDialog.Builder(this)
            .setTitle("⚙️ Ajustes del Widget")
            .setView(scrollView)
            .setPositiveButton("Guardar") { _, _ ->
                val newMoon = cbMoon.isChecked
                val newWind = cbWind.isChecked
                val newRain = cbRain.isChecked
                val newUv = cbUv.isChecked
                val newSunlight = cbSunlight.isChecked

                prefs.edit()
                    .putBoolean("show_widget_moon", newMoon)
                    .putBoolean("show_widget_wind", newWind)
                    .putBoolean("show_widget_rain", newRain)
                    .putBoolean("show_widget_uv", newUv)
                    .putBoolean("show_widget_sunlight", newSunlight)
                    .commit()

                android.widget.Toast.makeText(this, "Ajustes del widget guardados", android.widget.Toast.LENGTH_SHORT).show()
                WeatherWidgetProvider.requestRefresh(this)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showOutdoorSportsSettingsDialog() {
        val prefs = getSharedPreferences("minimal_widget_prefs", Context.MODE_PRIVATE)

        val currentOptMinT = prefs.getInt("outdoor_temp_opt_min", 10)
        val currentOptMaxT = prefs.getInt("outdoor_temp_opt_max", 24)
        val currentRedMinT = prefs.getInt("outdoor_temp_red_min", 4)
        val currentRedMaxT = prefs.getInt("outdoor_temp_red_max", 31)

        val currentRainOptMax = prefs.getFloat("outdoor_rain_opt_max", 0.0f)
        val currentRainRedMax = prefs.getFloat("outdoor_rain_red_max", 1.5f)

        val currentWindOptMax = prefs.getInt("outdoor_wind_opt_max", 25)
        val currentWindRedMax = prefs.getInt("outdoor_wind_red_max", 40)

        val currentUvOptMax = prefs.getInt("outdoor_uv_opt_max", 5)
        val currentUvRedMax = prefs.getInt("outdoor_uv_red_max", 7)

        val scrollView = android.widget.ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        scrollView.addView(layout)

        fun createSectionTitle(title: String) {
            val tv = TextView(this@MainActivity).apply {
                text = title
                textSize = 15f
                setTextColor(Color.parseColor("#2B6CB0"))
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, dp(12), 0, dp(4))
            }
            layout.addView(tv)
        }

        fun createSingleColorRow(iconEmoji: String, labelText: String, initialVal: String): EditText {
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(3), 0, dp(3))
            }

            val tvIcon = TextView(this@MainActivity).apply {
                text = iconEmoji
                textSize = 15f
                setPadding(0, 0, dp(6), 0)
            }

            val tvLabel = TextView(this@MainActivity).apply {
                text = labelText
                textSize = 13.5f
                setTextColor(Color.parseColor("#2D3748"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
            }

            val et = EditText(this@MainActivity).apply {
                setText(initialVal)
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
                textSize = 13.5f
                gravity = Gravity.CENTER
                minWidth = dp(55)
            }

            row.addView(tvIcon)
            row.addView(tvLabel)
            row.addView(et)
            layout.addView(row)
            return et
        }

        fun createInfoRow(iconEmoji: String, infoText: String) {
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(3), 0, dp(3))
            }

            val tvIcon = TextView(this@MainActivity).apply {
                text = iconEmoji
                textSize = 15f
                setPadding(0, 0, dp(6), 0)
            }

            val tvLabel = TextView(this@MainActivity).apply {
                text = infoText
                textSize = 13f
                setTextColor(Color.parseColor("#C62828"))
            }

            row.addView(tvIcon)
            row.addView(tvLabel)
            layout.addView(row)
        }

        fun createRangeColorRow(iconEmoji: String, labelText: String, initialMin: String, initialMax: String): Pair<EditText, EditText> {
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(3), 0, dp(3))
            }

            val tvIcon = TextView(this@MainActivity).apply {
                text = iconEmoji
                textSize = 15f
                setPadding(0, 0, dp(6), 0)
            }

            val tvLabel = TextView(this@MainActivity).apply {
                text = labelText
                textSize = 13f
                setTextColor(Color.parseColor("#2D3748"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
            }

            val etMin = EditText(this@MainActivity).apply {
                setText(initialMin)
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
                textSize = 13f
                gravity = Gravity.CENTER
                minWidth = dp(45)
            }

            val tvTo = TextView(this@MainActivity).apply {
                text = "a"
                textSize = 13f
                setTextColor(Color.parseColor("#718096"))
                setPadding(dp(4), 0, dp(4), 0)
            }

            val etMax = EditText(this@MainActivity).apply {
                setText(initialMax)
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
                textSize = 13f
                gravity = Gravity.CENTER
                minWidth = dp(45)
            }

            row.addView(tvIcon)
            row.addView(tvLabel)
            row.addView(etMin)
            row.addView(tvTo)
            row.addView(etMax)
            layout.addView(row)
            return Pair(etMin, etMax)
        }

        // Section 1: Temperature
        createSectionTitle("🌡️ Sensación Térmica / Temperatura (°C)")
        val (etOptMinT, etOptMaxT) = createRangeColorRow("🟢", "Excelente (Rango °C):", currentOptMinT.toString(), currentOptMaxT.toString())
        val (etRedMinT, etRedMaxT) = createRangeColorRow("🟠", "Moderado (Rango °C):", currentRedMinT.toString(), currentRedMaxT.toString())
        createInfoRow("🔴", "Poco Favorable (< Moderado Mín o > Moderado Máx)")

        // Section 2: Wind
        createSectionTitle("💨 Viento (km/h)")
        val etWindOptMax = createSingleColorRow("🟢", "Excelente (Hasta km/h):", currentWindOptMax.toString())
        val etWindRedMax = createSingleColorRow("🟠", "Moderado (Hasta km/h):", currentWindRedMax.toString())
        createInfoRow("🔴", "Poco Favorable (> Moderado)")

        // Section 3: UV
        createSectionTitle("☀️ Índice Radiación UV")
        val etUvOptMax = createSingleColorRow("🟢", "Excelente (Hasta):", currentUvOptMax.toString())
        val etUvRedMax = createSingleColorRow("🟠", "Moderado (Hasta):", currentUvRedMax.toString())
        createInfoRow("🔴", "Poco Favorable (> Moderado)")

        // Section 4: Rain
        createSectionTitle("🌧️ Lluvia / Precipitación (mm/h)")
        val etRainOptMax = createSingleColorRow("🟢", "Excelente (Hasta mm/h):", currentRainOptMax.toString())
        val etRainRedMax = createSingleColorRow("🟠", "Moderado (Hasta mm/h):", currentRainRedMax.toString())
        createInfoRow("🔴", "Poco Favorable (> Moderado)")

        AlertDialog.Builder(this)
            .setTitle("🏃 Matriz Deportes al Aire Libre")
            .setView(scrollView)
            .setPositiveButton("Guardar") { _, _ ->
                val newOptMinT = etOptMinT.text.toString().toIntOrNull() ?: 10
                val newOptMaxT = etOptMaxT.text.toString().toIntOrNull() ?: 24
                val newRedMinT = etRedMinT.text.toString().toIntOrNull() ?: 4
                val newRedMaxT = etRedMaxT.text.toString().toIntOrNull() ?: 31

                val newRainOptMax = etRainOptMax.text.toString().toFloatOrNull() ?: 0.0f
                val newRainRedMax = etRainRedMax.text.toString().toFloatOrNull() ?: 1.5f

                val newWindOptMax = etWindOptMax.text.toString().toIntOrNull() ?: 25
                val newWindRedMax = etWindRedMax.text.toString().toIntOrNull() ?: 40

                val newUvOptMax = etUvOptMax.text.toString().toIntOrNull() ?: 5
                val newUvRedMax = etUvRedMax.text.toString().toIntOrNull() ?: 7

                prefs.edit()
                    .putInt("outdoor_temp_opt_min", newOptMinT)
                    .putInt("outdoor_temp_opt_max", newOptMaxT)
                    .putInt("outdoor_temp_red_min", newRedMinT)
                    .putInt("outdoor_temp_red_max", newRedMaxT)
                    .putFloat("outdoor_rain_opt_max", newRainOptMax)
                    .putFloat("outdoor_rain_red_max", newRainRedMax)
                    .putInt("outdoor_wind_opt_max", newWindOptMax)
                    .putInt("outdoor_wind_red_max", newWindRedMax)
                    .putInt("outdoor_uv_opt_max", newUvOptMax)
                    .putInt("outdoor_uv_red_max", newUvRedMax)
                    .apply()

                android.widget.Toast.makeText(this, "Matriz de configuración guardada", android.widget.Toast.LENGTH_SHORT).show()
                checkCacheAndLoadData(forceRefresh = true)
                WeatherWidgetProvider.requestRefresh(this)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    data class Reservoir(val name: String, val capacityHm3: Double, val storedHm3: Double) {
        val percentage: Int get() = if (capacityHm3 > 0) ((storedHm3 / capacityHm3) * 100).roundToInt().coerceIn(0, 100) else 0
        val status: String get() = when {
            percentage > 70 -> "Normal / Abundante"
            percentage > 40 -> "Normal / Estable"
            percentage > 20 -> "Reserva Baja"
            else -> "Pre Alerta / Sequía"
        }
    }

    data class ProvinceData(val provinceName: String, val reservoirs: List<Reservoir>)
    data class CommunityData(val communityName: String, val provinces: List<ProvinceData>)

    private val spainReservoirsData = listOf(
        CommunityData("Andalucía", listOf(
            ProvinceData("Cádiz", listOf(
                Reservoir("Guadalcacín II", 800.3, 312.0),
                Reservoir("Zahara - El Gastor", 223.0, 78.5),
                Reservoir("Bornos", 200.0, 72.0),
                Reservoir("Celemín", 45.0, 18.2),
                Reservoir("Barbate", 228.0, 64.0)
            )),
            ProvinceData("Sevilla", listOf(
                Reservoir("José Torán", 113.0, 48.0),
                Reservoir("El Pintado", 213.0, 92.5),
                Reservoir("Gergal", 35.0, 24.0)
            )),
            ProvinceData("Málaga", listOf(
                Reservoir("La Viñuela", 164.0, 35.2),
                Reservoir("Guadalhorce", 126.0, 42.0),
                Reservoir("Conde de Guadalhorce", 67.0, 28.5)
            )),
            ProvinceData("Granada", listOf(
                Reservoir("Negratín", 567.0, 195.0),
                Reservoir("Bermejales", 102.0, 44.0),
                Reservoir("Rules", 117.0, 65.0)
            )),
            ProvinceData("Córdoba", listOf(
                Reservoir("Iznájar", 981.0, 320.0),
                Reservoir("Bembézar", 342.0, 145.0),
                Reservoir("San Rafael de Navallana", 157.0, 82.0)
            )),
            ProvinceData("Jaén", listOf(
                Reservoir("Tranco de Beas", 498.0, 210.0),
                Reservoir("Guadalmena", 400.0, 168.0),
                Reservoir("Jándula", 56.0, 24.0)
            )),
            ProvinceData("Huelva", listOf(
                Reservoir("Aracena", 128.0, 85.0),
                Reservoir("Chanza", 341.0, 210.0)
            )),
            ProvinceData("Almería", listOf(
                Reservoir("Cuevas del Almanzora", 159.0, 28.0),
                Reservoir("Benínar", 69.0, 12.5)
            ))
        )),
        CommunityData("Madrid", listOf(
            ProvinceData("Madrid", listOf(
                Reservoir("El Atazar", 425.0, 290.0),
                Reservoir("Valmayor", 124.0, 88.0),
                Reservoir("Santillana", 91.0, 68.0),
                Reservoir("El Pardo", 47.0, 38.0)
            ))
        )),
        CommunityData("Castilla-La Mancha", listOf(
            ProvinceData("Guadalajara", listOf(
                Reservoir("Entrepeñas", 835.0, 310.0),
                Reservoir("Buendía", 1639.0, 480.0),
                Reservoir("Alcorlo", 180.0, 115.0)
            )),
            ProvinceData("Toledo", listOf(
                Reservoir("Cazalegas", 10.0, 6.5)
            )),
            ProvinceData("Ciudad Real", listOf(
                Reservoir("Gasset", 39.0, 22.0),
                Reservoir("Torre de Abraham", 183.0, 45.0)
            ))
        )),
        CommunityData("Cataluña", listOf(
            ProvinceData("Barcelona", listOf(
                Reservoir("Sau", 151.0, 42.0),
                Reservoir("Susqueda", 233.0, 78.0)
            )),
            ProvinceData("Gerona", listOf(
                Reservoir("Boadella", 61.0, 28.0)
            )),
            ProvinceData("Lérida", listOf(
                Reservoir("Canelles", 679.0, 245.0),
                Reservoir("Santa Ana", 237.0, 110.0)
            ))
        )),
        CommunityData("Aragón", listOf(
            ProvinceData("Zaragoza", listOf(
                Reservoir("Mequinenza", 1533.0, 980.0),
                Reservoir("Yesa", 447.0, 210.0)
            )),
            ProvinceData("Huesca", listOf(
                Reservoir("Mediano", 436.0, 260.0),
                Reservoir("El Grado", 400.0, 230.0)
            ))
        )),
        CommunityData("Comunidad Valenciana", listOf(
            ProvinceData("Valencia", listOf(
                Reservoir("Tous", 379.0, 145.0),
                Reservoir("Loriguilla", 73.0, 31.0)
            )),
            ProvinceData("Alicante", listOf(
                Reservoir("Amadorio", 16.0, 8.5),
                Reservoir("Tibi", 4.0, 2.0)
            ))
        )),
        CommunityData("Castilla y León", listOf(
            ProvinceData("León", listOf(
                Reservoir("Riaño", 651.0, 410.0),
                Reservoir("Barrios de Luna", 308.0, 195.0)
            )),
            ProvinceData("Burgos", listOf(
                Reservoir("Ebro", 541.0, 320.0)
            )),
            ProvinceData("Salamanca", listOf(
                Reservoir("Almendra", 2648.0, 1420.0)
            ))
        ))
    )

    private fun showReservoirsCommunitiesDialog() {
        val names = spainReservoirsData.map { it.communityName }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("💧 Selecciona Comunidad Autónoma")
            .setItems(names) { _, which ->
                val community = spainReservoirsData[which]
                showReservoirsProvincesDialog(community)
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun showReservoirsProvincesDialog(community: CommunityData) {
        val names = community.provinces.map { it.provinceName }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("📍 ${community.communityName} - Provincia")
            .setItems(names) { _, which ->
                val province = community.provinces[which]
                showReservoirsListDialog(province, community)
            }
            .setNegativeButton("Volver") { _, _ -> showReservoirsCommunitiesDialog() }
            .show()
    }

    private fun showReservoirsListDialog(province: ProvinceData, community: CommunityData) {
        val scrollView = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        scrollView.addView(container)

        val title = TextView(this).apply {
            text = "Embalses en ${province.provinceName}"
            textSize = 16f
            setTextColor(Color.parseColor("#2B6CB0"))
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(12))
        }
        container.addView(title)

        for (res in province.reservoirs) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#F7FAFC"))
                    cornerRadius = dp(10).toFloat()
                    setStroke(dp(1), Color.parseColor("#E2E8F0"))
                }
                setPadding(dp(12), dp(10), dp(12), dp(10))
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, dp(8)) }
                layoutParams = lp
            }

            val tvName = TextView(this).apply {
                text = "🌊 ${res.name}"
                textSize = 14.5f
                setTextColor(Color.parseColor("#1A202C"))
                typeface = Typeface.DEFAULT_BOLD
            }
            card.addView(tvName)

            val tvDetails = TextView(this).apply {
                text = "Agua: ${res.storedHm3} hm³ / ${res.capacityHm3} hm³ (${res.percentage}%) • ${res.status}"
                textSize = 13f
                setTextColor(Color.parseColor("#4A5568"))
                setPadding(0, dp(4), 0, dp(6))
            }
            card.addView(tvDetails)

            // Barra horizontal azul de progreso
            val barBackground = GradientDrawable().apply {
                setColor(Color.parseColor("#E2E8F0"))
                cornerRadius = dp(4).toFloat()
            }
            val barContainer = FrameLayout(this).apply {
                background = barBackground
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(10)
                )
            }

            val progressWidthPx = maxOf(dp(4), (dp(260) * (res.percentage / 100.0)).toInt())
            val barProgress = View(this).apply {
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#3182CE"))
                    cornerRadius = dp(4).toFloat()
                }
                layoutParams = FrameLayout.LayoutParams(
                    progressWidthPx,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            barContainer.addView(barProgress)
            card.addView(barContainer)

            container.addView(card)
        }

        AlertDialog.Builder(this)
            .setView(scrollView)
            .setPositiveButton("Cerrar", null)
            .setNegativeButton("Volver") { _, _ -> 
                showReservoirsProvincesDialog(community)
            }
            .show()
    }

    private fun normalizeCity(input: String): String {
        return java.text.Normalizer.normalize(input.lowercase().trim(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
    }

    private fun findProvinceDataForCity(city: String): ProvinceData? {
        val cleanCity = normalizeCity(city)
        for (comm in spainReservoirsData) {
            for (prov in comm.provinces) {
                val cleanProv = normalizeCity(prov.provinceName)
                if (cleanCity.contains(cleanProv) || cleanProv.contains(cleanCity)) {
                    return prov
                }
            }
        }
        if (cleanCity.contains("medina") || cleanCity.contains("sidonia") || cleanCity.contains("cadiz") ||
            cleanCity.contains("jerez") || cleanCity.contains("chiclana") || cleanCity.contains("san fernando") ||
            cleanCity.contains("tarifa") || cleanCity.contains("algeciras") || cleanCity.contains("puerto") ||
            cleanCity.contains("rota") || cleanCity.contains("sanlucar") || cleanCity.contains("ubrique") || cleanCity.contains("conil")) {
            return spainReservoirsData.firstOrNull()?.provinces?.firstOrNull { it.provinceName == "Cádiz" }
        }
        return spainReservoirsData.firstOrNull()?.provinces?.firstOrNull { it.provinceName == "Cádiz" }
    }

    private fun createProvinceReservoirsCard(city: String): View {
        val provData = findProvinceDataForCity(city) ?: return View(this)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        for (res in provData.reservoirs) {
            val itemLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(4), 0, dp(6))
            }

            val tvResTitle = TextView(this).apply {
                text = "• ${res.name}: ${res.storedHm3} hm³ (${res.percentage}%)"
                textSize = 13.5f
                setTextColor(Color.parseColor("#2D3748"))
                typeface = Typeface.DEFAULT_BOLD
            }

            val tvResStatus = TextView(this).apply {
                text = "${res.status} (Capacidad: ${res.capacityHm3} hm³)"
                textSize = 12.5f
                setTextColor(Color.parseColor("#4A5568"))
                setPadding(dp(10), 0, 0, dp(4))
            }

            val barBackground = GradientDrawable().apply {
                setColor(Color.parseColor("#CBD5E0"))
                cornerRadius = dp(4).toFloat()
            }
            val barContainer = FrameLayout(this).apply {
                background = barBackground
                val lpBar = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(8)
                ).apply {
                    setMargins(dp(10), 0, 0, 0)
                }
                layoutParams = lpBar
            }

            val progressWidthPx = maxOf(dp(4), (dp(240) * (res.percentage / 100.0)).toInt())
            val barProgress = View(this).apply {
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#3182CE"))
                    cornerRadius = dp(4).toFloat()
                }
                layoutParams = FrameLayout.LayoutParams(
                    progressWidthPx,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            barContainer.addView(barProgress)

            itemLayout.addView(tvResTitle)
            itemLayout.addView(tvResStatus)
            itemLayout.addView(barContainer)
            container.addView(itemLayout)
        }

        container.setOnClickListener {
            showReservoirsCommunitiesDialog()
        }

        return container
    }

    private fun makeCardCollapsible(
        prefKey: String,
        headerTitle: String,
        contentView: View,
        bgColorHex: String = "#FFFFFF",
        strokeColorHex: String = "#E2E8F0"
    ): View {
        val prefs = getSharedPreferences("minimal_widget_prefs", Context.MODE_PRIVATE)
        var isOpen = prefs.getBoolean(prefKey, false) // Closed by default

        val cardContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor(bgColorHex))
                cornerRadius = dp(14).toFloat()
                if (strokeColorHex.isNotEmpty()) {
                    setStroke(dp(1), Color.parseColor(strokeColorHex))
                }
            }
            setPadding(dp(14), dp(12), dp(14), dp(12))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, dp(6), 0, dp(6))
            }
            layoutParams = lp
        }

        val headerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, if (isOpen) dp(8) else 0)
        }

        val tvTitle = TextView(this).apply {
            text = headerTitle
            textSize = 15.5f
            setTextColor(Color.parseColor("#1A202C"))
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        }

        val tvArrow = TextView(this).apply {
            text = if (isOpen) "▼" else "▶"
            textSize = 14f
            setTextColor(Color.parseColor("#2B6CB0"))
            typeface = Typeface.DEFAULT_BOLD
        }

        headerLayout.addView(tvTitle)
        headerLayout.addView(tvArrow)
        cardContainer.addView(headerLayout)

        contentView.visibility = if (isOpen) View.VISIBLE else View.GONE
        cardContainer.addView(contentView)

        headerLayout.setOnClickListener {
            isOpen = !isOpen
            prefs.edit().putBoolean(prefKey, isOpen).apply()
            contentView.visibility = if (isOpen) View.VISIBLE else View.GONE
            tvArrow.text = if (isOpen) "▼" else "▶"
            headerLayout.setPadding(0, 0, 0, if (isOpen) dp(8) else 0)
        }

        return cardContainer
    }

    private fun renderTimeline(city: String, list: List<TimelineItem>, sourceUsed: String, dailySummaries: List<DailySummary> = emptyList()) {
        contentLayout.removeAllViews()

        stickyTitle.text = city
        updateStarUI()

        // Tarjeta de Avisos AEMET (Fenómenos Adversos)
        val prefs = getSharedPreferences("minimal_widget_prefs", Context.MODE_PRIVATE)
        val code = prefs.getString("code", "11023") ?: "11023"
        val lat = prefs.getFloat("lat", 36.4578f).toDouble()
        val lon = prefs.getFloat("lon", -5.9272f).toDouble()

        val aemetWarnings = WeatherNotificationHelper.fetchAemetMultiDayWarnings(this, code, lat, lon, dailySummaries)

        val innerWarnings = createAemetWarningsCard(aemetWarnings)
        val hasWarnings = aemetWarnings.isNotEmpty()
        val warningsCard = makeCardCollapsible(
            "card_open_warnings", "⚠️ Avisos AEMET", innerWarnings,
            if (hasWarnings) "#FFF5F5" else "#F0FFF4",
            if (hasWarnings) "#FEB2B2" else "#C6F6D5"
        )
        contentLayout.addView(warningsCard)

        if (list.isNotEmpty()) {
            val provData = findProvinceDataForCity(city)
            val provName = provData?.provinceName ?: "Cádiz"
            val innerReservoirs = createProvinceReservoirsCard(city)
            val reservoirsCard = makeCardCollapsible(
                "card_open_reservoirs", "💧 Embalses ($provName)", innerReservoirs,
                "#EBF8FF", "#BEE3F8"
            )
            contentLayout.addView(reservoirsCard)
        }

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

                if (isMoon) {
                    row.isClickable = true
                    row.isFocusable = true
                    row.setOnClickListener {
                        val parsedDate = try {
                            if (item.itemDate.isNotBlank()) LocalDate.parse(item.itemDate) else LocalDate.now()
                        } catch (_: Exception) { LocalDate.now() }
                        showLunarPhotoDetailSheet(parsedDate, city, lat, lon)
                    }
                }

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
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.1f)
                }

                val tvIcon = TextView(this).apply {
                    text = item.icon
                    textSize = 26f
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.6f)
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
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.7f)
                }

                val windColor = if (item.wind.contains("|||")) Color.parseColor("#E53E3E") else Color.parseColor("#718096")
                val tvWind = TextView(this).apply {
                    text = item.wind
                    textSize = 13f
                    setTextColor(windColor)
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f)
                }

                // UV al lado de la lluvia: solo se muestra si es 3 o más (si no, el hueco queda vacío)
                val tvUv = TextView(this).apply {
                    text = if (item.uv >= UV_MIN_TO_SHOW) "UV${item.uv}" else ""
                    textSize = 13f
                    setTextColor(Color.parseColor("#E53E3E"))
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.7f)
                }

                val tvRain = TextView(this).apply {
                    text = item.rainLiters
                    textSize = 13f
                    setTextColor(Color.parseColor("#3182CE"))
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.END
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.8f)
                }

                val hInt = item.hour.toIntOrNull() ?: 12
                val parsedDate = try {
                    if (item.itemDate.isNotBlank()) java.time.LocalDate.parse(item.itemDate) else java.time.LocalDate.now()
                } catch (_: Exception) {
                    java.time.LocalDate.now()
                }
                val sunData = MainActivityHelper.calculateSunTimesStatic(parsedDate, lat, lon)
                val isDaylight = hInt in sunData.sunriseHour..sunData.sunsetHour

                val outdoorBadgeStr = if (isDaylight) {
                    val itemOutdoorScore = calculateOutdoorItemScore(item)
                    when {
                        itemOutdoorScore >= 80.0 -> "🏃🟢"
                        itemOutdoorScore >= 40.0 -> "🏃🟡"
                        else -> "🏃🔴"
                    }
                } else {
                    ""
                }

                val tvOutdoor = TextView(this).apply {
                    text = outdoorBadgeStr
                    textSize = 13f
                    gravity = Gravity.END
                    isSingleLine = true
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.9f)
                }

                row.addView(tvHour)
                row.addView(tvIcon)
                row.addView(tvTemp)
                row.addView(tvWind)
                row.addView(tvUv)
                row.addView(tvRain)
                row.addView(tvOutdoor)

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

    private fun fetchOpenMeteoHourlyWithSunMoonAndTides(lat: Double, lon: Double): List<TimelineItem> {
        return try {
            val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                    "&hourly=temperature_2m,precipitation,weather_code,wind_speed_10m,wind_direction_10m,uv_index" +
                    "&daily=sunrise,sunset&timezone=Europe%2FMadrid&forecast_days=7"
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

                if (date < nowDate) continue
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
                        uv = uvVal, itemDate = date.toString()
                    )
                )
            }

            for ((date, dayItems) in mapByDate) {
                if (date < nowDate) continue
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
                            icon = "", temp = "", wind = "", rainLiters = "",
                            itemDate = date.toString()
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
            val isMoonRiseGood = diffRise <= 90
            list.add(
                AstroEvent(
                    hourInt = moonInfo.riseHour.coerceIn(0, 23),
                    exactMinute = moonInfo.riseTotalMins,
                    timeStr = moonInfo.riseTimeStr,
                    title = if (isMoonRiseGood) "Salida Luna (📷 ¡Óptima!)" else "Salida de la Luna",
                    details = "Azimut: ${moonInfo.riseAzimuth}° • ${moonInfo.phaseName} (${moonInfo.illumination}%)",
                    iconEmoji = moonInfo.phaseEmoji,
                    isGoodPhoto = isMoonRiseGood
                )
            )
        }

        if (moonInfo.setTotalMins >= 0) {
            val diffSet = abs(moonInfo.setTotalMins - sun.sunriseTotalMins)
            val isMoonSetGood = diffSet <= 90
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
        val uv: Int = 0,
        val itemDate: String = ""
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

    fun getMoonAltAz(zdt: ZonedDateTime, lat: Double, lon: Double): Pair<Double, Double> {
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