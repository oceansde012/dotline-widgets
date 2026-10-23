package io.github.oceansde012.dotline

import android.Manifest
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.ViewTreeObserver
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RemoteViews
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.text.format.DateUtils
import java.util.Locale

/**
 * The single app screen (DESIGN_SPEC.md section 4): setup checklist, live widget gallery, settings, footer.
 * Everything is framework views built through [Ui]; widget previews are the providers' real RemoteViews.
 */
class MainActivity : Activity() {

    companion object {
        const val SECTION_WEATHER = WeatherWidgetProvider.SECTION_WEATHER
        const val SECTION_BLUETOOTH = "bluetooth"
        const val EXTRA_INSTALL = "install_update"
        const val EXTRA_CONFIRM = "confirm_intent"
        private const val REQ_NOTIF = 9
        private const val TAG = "NdMain"
        private const val REQ_BT = 7
        private const val REQ_LOC = 8
        private const val SEARCH_DEBOUNCE_MS = 400L
        private const val LOCATION_MAX_AGE_MS = 30 * 60 * 1000L
        private const val LOCATION_TIMEOUT_MS = 12_000L
        private const val OPEN_METEO = "https://open-meteo.com/"
    }

    private lateinit var ui: Ui
    private lateinit var scroll: ScrollView
    private lateinit var content: LinearLayout
    private val main = Handler(Looper.getMainLooper())

    // setup cards
    private lateinit var notifCard: Card
    private lateinit var wxCard: Card
    private lateinit var btCard: Card
    private lateinit var btButton: TextView
    private lateinit var locButton: TextView
    private lateinit var cityInput: EditText
    private lateinit var wxStatus: TextView
    private lateinit var wxResults: LinearLayout
    private var btBlocked = false
    private var locBlocked = false
    private var settingText = false

    // search + location state
    private var searchToken = 0
    private val searchRun = Runnable { runSearch() }
    private var locating: CancellationSignal? = null

    // widget gallery
    private class Preview(
        val frame: FrameLayout, val w: Float, val h: Float, val build: (Context, Float, Float) -> RemoteViews,
    ) {
        var shown: RemoteViews? = null
    }

    private val previews = ArrayList<Preview>()
    private val previewTick = object : Runnable {
        override fun run() {
            if (MediaTracker.track?.playing == true) {
                playerPreview?.let { show(it) }
                main.postDelayed(this, 1000)
            }
        }
    }
    private var playerPreview: Preview? = null

    private var pendingSection: String? = null
    private var pendingInstall = false
    private var pendingConfirm: Intent? = null

    // updates (release builds only)
    private lateinit var installCard: Card
    private lateinit var updVersion: TextView
    private lateinit var updLast: TextView
    private lateinit var updStatus: TextView
    private lateinit var updInstall: TextView
    private lateinit var updCheck: TextView

    // --- lifecycle ---------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        ui = Ui(this)
        scroll = requireViewById(R.id.scroll)
        content = requireViewById(R.id.content)
        buildHeader()
        buildSetup()
        buildGallery()
        buildSettings()
        if (BuildConfig.UPDATES_ENABLED) buildUpdates()
        buildFooter()
        // keep the first focus off the city field (it would scroll the page and pop the keyboard)
        content.isFocusable = true
        content.isFocusableInTouchMode = true
        content.requestFocus()
        pendingSection = intent?.getStringExtra(WeatherWidgetProvider.EXTRA_SECTION)
        intent?.removeExtra(WeatherWidgetProvider.EXTRA_SECTION)
        takeUpdateExtras(intent)
        // system bars + keyboard: explicit padding, so the focused city field stays visible on API 35 edge-to-edge
        scroll.setOnApplyWindowInsetsListener { v, ins ->
            val bars = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = ins.getInsets(WindowInsets.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            ins
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingSection = intent.getStringExtra(WeatherWidgetProvider.EXTRA_SECTION)
        intent.removeExtra(WeatherWidgetProvider.EXTRA_SECTION)
        takeUpdateExtras(intent)
    }

    override fun onResume() {
        super.onResume()
        val granted = MediaTracker.hasAccess(this)
        MediaTracker.ensureStarted(this)   // also starts the power / weather / buds receivers
        if (granted) MediaTracker.render(this, forceFull = true)
        WeatherRepo.refreshAsync(this, minAgeMs = 10 * 60 * 1000L) { runOnUiThread { if (!isDestroyed) refreshPreviews() } }
        BudsRepo.refreshAsync(this)
        PowerWidgetProvider.refresh(this)
        updateCards()
        refreshPreviews()
        main.removeCallbacks(previewTick)
        main.post(previewTick)
        if (BuildConfig.UPDATES_ENABLED) onResumeUpdates()
        pendingSection?.let { section ->
            pendingSection = null
            when (section) {
                SECTION_WEATHER -> focusCard(wxCard)
                SECTION_BLUETOOTH -> focusCard(btCard)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        Updater.foreground = false
        Updater.onChange = null
        main.removeCallbacks(previewTick)
        main.removeCallbacks(searchRun)
        locating?.cancel()
        locating = null
    }

    // --- header ------------------------------------------------------------------------------

    private fun buildHeader() {
        val title = ui.text(getString(R.string.header_title), 30f, Nd.FG, ui.heroFont).apply {
            maxLines = 1
            setAutoSizeTextTypeUniformWithConfiguration(18, 30, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        }
        content.addView(title, ui.lp(top = 8))
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
        }
        content.addView(ui.label(getString(R.string.header_version, version)), ui.lp(top = 6, bottom = 4))
    }

    private fun section(titleRes: Int) {
        content.addView(ui.label(getString(titleRes)), ui.lp(top = 28, bottom = 10, start = 2))
    }

    // --- setup checklist ---------------------------------------------------------------------

    /**
     * Tile-styled card: status dot (fg = done, accent = needs action), title, body with one action.
     * A done card collapses to one `READY` row; tapping that row opens it again (e.g. to change the city).
     */
    private inner class Card(titleRes: Int) {
        val root = ui.vertical().apply { setBackgroundResource(R.drawable.nd_tile_r22) }
        val body = ui.vertical()
        private val dot = View(this@MainActivity)
        private val title = ui.label(getString(titleRes), Nd.FG, 12f)
        private val status = ui.label(getString(R.string.status_ready), Nd.DIM)
        private val header = ui.horizontal()
        var done = false
            private set
        var expanded = true
        private var init = false

        init {
            header.minimumHeight = ui.dp(48)
            header.addView(dot, ui.lp(w = ui.dp(10), h = ui.dp(10), end = 12))
            header.addView(title, ui.lp(w = 0, weight = 1f))
            header.addView(status, ui.lp(w = Ui.WRAP, start = 8))
            header.setOnClickListener { if (done) open(!expanded) }
            root.addView(header, ui.lp())
            root.addView(body, ui.lp(top = 4))
            apply()
        }

        fun setTitle(s: CharSequence) {
            title.text = s
        }

        fun open(on: Boolean) {
            expanded = on
            apply()
        }

        /** a state change collapses (granted) or opens (lost) the card; no change keeps what the user chose */
        fun update(isDone: Boolean) {
            if (!init || isDone != done) expanded = !isDone
            init = true
            done = isDone
            apply()
        }

        private fun apply() {
            dot.setBackgroundResource(if (done) R.drawable.nd_dot_fg else R.drawable.nd_dot)
            status.visibility = if (done) View.VISIBLE else View.GONE
            header.setBackgroundResource(if (done) R.drawable.nd_row else 0)
            header.isClickable = done
            val open = !done || expanded
            body.visibility = if (open) View.VISIBLE else View.GONE
            val v = ui.dp(4)
            root.setPadding(ui.dp(16), v, ui.dp(16), if (open) ui.dp(16) else v)
        }
    }

    private fun buildSetup() {
        section(R.string.sec_setup)
        notifCard = Card(R.string.notif_title)
        wxCard = Card(R.string.wx_title)
        btCard = Card(R.string.bt_title)
        buildNotificationCard()
        buildWeatherCard()
        buildBluetoothCard()
        content.addView(notifCard.root, ui.lp(bottom = 12))
        content.addView(wxCard.root, ui.lp(bottom = 12))
        content.addView(btCard.root, ui.lp())
    }

    private fun buildNotificationCard() {
        val b = notifCard.body
        b.addView(ui.body(getString(R.string.notif_body)), ui.lp())
        b.addView(ui.button(getString(R.string.btn_open_settings), primary = true).apply {
            setOnClickListener { openAccessSettings() }
        }, ui.lp(top = 12))
        b.addView(ui.link(getString(R.string.btn_restricted)).apply {
            setOnClickListener { openAppInfo() }
        }, ui.lp(top = 4))
        b.addView(ui.body(getString(R.string.restricted_steps), Nd.DIM, 12f), ui.lp(start = 4, end = 4))
    }

    private fun buildWeatherCard() {
        val b = wxCard.body
        b.addView(ui.body(getString(R.string.wx_body)), ui.lp())
        cityInput = EditText(this).apply {
            setBackgroundResource(R.drawable.nd_input)
            hint = getString(R.string.wx_search_hint)
            setHintTextColor(Nd.DIM)
            setTextColor(Nd.FG)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            minimumHeight = ui.dp(52)
            setPadding(ui.dp(16), 0, ui.dp(16), 0)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) = Unit
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    if (settingText) return
                    onQueryChanged(s?.toString().orEmpty())
                }
            })
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_SEARCH) {
                    main.removeCallbacks(searchRun)
                    runSearch()
                    true
                } else {
                    false
                }
            }
        }
        b.addView(cityInput, ui.lp(top = 12))
        wxStatus = ui.body("", Nd.DIM, 13f).apply { visibility = View.GONE }
        b.addView(wxStatus, ui.lp(top = 8, start = 4))
        wxResults = ui.vertical()
        b.addView(wxResults, ui.lp(top = 4))
        locButton = ui.button(getString(R.string.btn_use_location), primary = true).apply {
            setOnClickListener { useMyLocation() }
        }
        b.addView(locButton, ui.lp(top = 12))
    }

    private fun buildBluetoothCard() {
        val b = btCard.body
        b.addView(ui.body(getString(R.string.bt_body)), ui.lp())
        btButton = ui.button(getString(R.string.btn_allow), primary = true).apply {
            setOnClickListener {
                if (btBlocked) openAppInfo() else requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQ_BT)
            }
        }
        b.addView(btButton, ui.lp(top = 12))
    }

    /** re-checks every requirement; called from onResume so coming back from system settings updates the list */
    private fun updateCards() {
        notifCard.update(MediaTracker.hasAccess(this))
        val hasCity = WeatherRepo.hasLocation(this)
        wxCard.update(hasCity)
        wxCard.setTitle(
            if (hasCity) getString(R.string.wx_title_city, WeatherRepo.locationName(this).uppercase())
            else getString(R.string.wx_title)
        )
        val bt = BudsRepo.hasPermission(this)
        btCard.update(bt)
        btButton.text = getString(if (btBlocked && !bt) R.string.btn_open_app_settings else R.string.btn_allow)
        locButton.text = getString(if (locBlocked) R.string.btn_open_app_settings else R.string.btn_use_location)
    }

    private fun focusCard(c: Card) {
        c.open(true)
        afterLayout {
            scroll.smoothScrollTo(0, (c.root.top - ui.dp(16)).coerceAtLeast(0))
        }
    }

    private fun afterLayout(block: () -> Unit) {
        if (scroll.isLaidOut && !scroll.isLayoutRequested) {
            scroll.post(block)
            return
        }
        scroll.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                scroll.viewTreeObserver.removeOnGlobalLayoutListener(this)
                block()
            }
        })
    }

    // --- notification access -----------------------------------------------------------------

    private fun openAccessSettings() {
        val cn = MediaTracker.listenerComponent(this)
        val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, cn.flattenToString())
        try {
            startActivity(detail)
        } catch (e: ActivityNotFoundException) {
            try {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            } catch (e2: ActivityNotFoundException) {
                Log.w(TAG, "no notification access settings", e2)
            }
        }
    }

    /** App info: its menu holds "Allow restricted settings" (sideloaded apps, e.g. Samsung One UI) */
    private fun openAppInfo() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "no app details settings", e)
        }
    }

    // --- weather city: search ----------------------------------------------------------------

    private fun onQueryChanged(raw: String) {
        main.removeCallbacks(searchRun)
        searchToken++   // anything in flight is obsolete now
        val q = raw.trim()
        if (q.length < 2) {
            wxResults.removeAllViews()
            showStatus(null)
            return
        }
        main.postDelayed(searchRun, SEARCH_DEBOUNCE_MS)
    }

    private fun runSearch() {
        val q = cityInput.text.toString().trim()
        if (q.length < 2) return
        val token = ++searchToken
        showStatus(getString(R.string.wx_searching))
        WeatherRepo.searchAsync(q) { hits ->
            runOnUiThread {
                if (isDestroyed || token != searchToken) return@runOnUiThread
                showResults(hits.take(5))
            }
        }
    }

    private fun showResults(hits: List<City>) {
        wxResults.removeAllViews()
        if (hits.isEmpty()) {
            showStatus(getString(R.string.wx_not_found))
            return
        }
        showStatus(null)
        for (c in hits) {
            val row = ui.vertical().apply {
                setBackgroundResource(R.drawable.nd_row)
                minimumHeight = ui.dp(56)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8))
                isClickable = true
                isFocusable = true
                setOnClickListener { chooseCity(c.lat, c.lon, c.name) }
            }
            row.addView(ui.text(c.name, 16f, Nd.FG).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            val sub = listOf(c.admin, c.country).filter { it.isNotBlank() && it != c.name }.joinToString(", ")
            if (sub.isNotEmpty()) {
                row.addView(ui.text(sub, 13f, Nd.DIM).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            }
            wxResults.addView(row, ui.lp())
        }
    }

    private fun showStatus(s: String?) {
        wxStatus.text = s.orEmpty()
        wxStatus.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    /** stores the city (which also refreshes the forecast and re-renders every weather widget) and tidies the card */
    private fun chooseCity(lat: Double, lon: Double, name: String) {
        WeatherRepo.setLocation(this, lat, lon, name) { runOnUiThread { if (!isDestroyed) refreshPreviews() } }
        searchToken++
        main.removeCallbacks(searchRun)
        wxResults.removeAllViews()
        showStatus(null)
        settingText = true
        cityInput.setText("")
        settingText = false
        hideKeyboard()
        updateCards()
        wxCard.open(false)
        refreshPreviews()
    }

    private fun hideKeyboard() {
        cityInput.clearFocus()
        content.requestFocus()
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(cityInput.windowToken, 0)
    }

    // --- weather city: my location -----------------------------------------------------------

    private fun useMyLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            if (locBlocked) openAppInfo() else requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), REQ_LOC)
            return
        }
        locate()
    }

    /** framework location only (no Play Services): a recent last-known fix, else one network fix */
    private fun locate() {
        val lm = getSystemService(LocationManager::class.java)
        if (lm == null) {
            showStatus(getString(R.string.wx_location_failed))
            return
        }
        showStatus(getString(R.string.wx_locating))
        var best: Location? = null
        for (p in listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)) {
            try {
                val l = lm.getLastKnownLocation(p)
                if (l != null && (best == null || l.time > best.time)) best = l
            } catch (e: SecurityException) {
                Log.w(TAG, "last known $p", e)
            } catch (e: IllegalArgumentException) {
                // provider not present
            }
        }
        val last = best
        if (last != null && System.currentTimeMillis() - last.time < LOCATION_MAX_AGE_MS) {
            onLocation(last)
            return
        }
        val network = try {
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (e: Exception) {
            false
        }
        if (!network) {
            if (last != null) onLocation(last) else showStatus(getString(R.string.wx_location_failed))
            return
        }
        val cancel = CancellationSignal()
        locating?.cancel()
        locating = cancel
        main.postDelayed({ cancel.cancel() }, LOCATION_TIMEOUT_MS)
        try {
            lm.getCurrentLocation(LocationManager.NETWORK_PROVIDER, cancel, mainExecutor) { fix: Location? ->
                if (isDestroyed || locating !== cancel) return@getCurrentLocation
                locating = null
                val l = fix ?: last
                if (l != null) onLocation(l) else showStatus(getString(R.string.wx_location_failed))
            }
        } catch (e: SecurityException) {
            showStatus(getString(R.string.wx_location_failed))
        }
    }

    /** names the place (Geocoder, may be absent on the device) off the main thread, else shows the coordinates */
    private fun onLocation(l: Location) {
        val app = applicationContext
        Thread {
            val name = placeName(app, l.latitude, l.longitude)
                ?: String.format(Locale.US, "%.2f, %.2f", l.latitude, l.longitude)
            runOnUiThread { if (!isDestroyed) chooseCity(l.latitude, l.longitude, name) }
        }.start()
    }

    @Suppress("DEPRECATION")
    private fun placeName(ctx: Context, lat: Double, lon: Double): String? = try {
        if (!Geocoder.isPresent()) {
            null
        } else {
            val a = Geocoder(ctx, Locale.getDefault()).getFromLocation(lat, lon, 1)?.firstOrNull()
            (a?.locality ?: a?.subAdminArea ?: a?.adminArea)?.takeIf { it.isNotBlank() }
        }
    } catch (e: Exception) {
        Log.w(TAG, "reverse geocoding failed", e)
        null
    }

    // --- permissions -------------------------------------------------------------------------

    @Deprecated("framework permission callback, no AndroidX here")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val ok = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        when (requestCode) {
            REQ_BT -> {
                btBlocked = !ok && !shouldShowRequestPermissionRationale(Manifest.permission.BLUETOOTH_CONNECT)
                if (ok) {
                    BudsWidgetProvider.updateAll(this)
                    BudsRepo.refreshAsync(this)
                }
            }
            REQ_LOC -> {
                locBlocked = !ok && !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION)
                if (ok) locate()
            }
        }
        updateCards()
        refreshPreviews()
    }

    // --- widget gallery ----------------------------------------------------------------------

    private fun buildGallery() {
        section(R.string.sec_widgets)
        val avail = (resources.configuration.screenWidthDp - 40).toFloat().coerceAtLeast(200f)
        val wide = minOf(avail, 380f)
        addWidgetCard(R.string.gallery_player, R.string.player_widget_desc, PlayerWidgetProvider::class.java, wide, 160f) { c, w, h ->
            PlayerRenderer.full(c, w, h, MediaTracker.track ?: demoTrack(c))
        }.also { playerPreview = it }
        addWidgetCard(R.string.gallery_head, R.string.head_widget_desc, HeadWidgetProvider::class.java, wide, 56f, { c, w, h -> HeadWidgetProvider.buildPreview(c, w, h) })
        addWidgetCard(R.string.gallery_clock, R.string.clock_widget_desc, ClockWidgetProvider::class.java, minOf(avail, 220f), 150f, { c, w, h -> ClockWidgetProvider.buildPreview(c, w, h) })
        addWidgetCard(R.string.gallery_weather, R.string.weather_widget_desc, WeatherWidgetProvider::class.java, 150f, 150f, { c, w, h -> WeatherWidgetProvider.buildPreview(c, w, h) })
        addWidgetCard(R.string.gallery_power, R.string.power_widget_desc, PowerWidgetProvider::class.java, minOf(avail, 230f), 62f, { c, w, h -> PowerWidgetProvider.buildPreview(c, w, h) })
        addWidgetCard(R.string.gallery_buds, R.string.buds_widget_desc, BudsWidgetProvider::class.java, 150f, 62f, { c, w, h -> BudsWidgetProvider.buildPreview(c, w, h) })
    }

    /** a paused sample track for the player preview when no media is playing (a stale real track is never faked) */
    private fun demoTrack(c: Context) = Track(
        pkg = c.packageName,
        title = c.getString(R.string.demo_title),
        artist = c.getString(R.string.demo_artist),
        art = null,
        artKey = 0L,
        durationMs = 243_000L,
        playing = false,
        position = 84_000L,
        updateTime = 0L,
        speed = 1f,
        sessionActivity = null,
    )

    private fun addWidgetCard(
        titleRes: Int, descRes: Int, provider: Class<*>, w: Float, h: Float,
        build: (Context, Float, Float) -> RemoteViews,
    ): Preview {
        val head = ui.horizontal()
        val texts = ui.vertical()
        texts.addView(ui.label(getString(titleRes), Nd.FG, 12f))
        texts.addView(ui.body(getString(descRes), Nd.DIM, 12f), ui.lp(top = 2))
        head.addView(texts, ui.lp(w = 0, weight = 1f, end = 12))
        head.addView(ui.button(getString(R.string.btn_add_home), primary = false).apply {
            setOnClickListener { pin(provider) }
        }, ui.lp(w = Ui.WRAP))
        content.addView(head, ui.lp(top = 8))

        val frame = FrameLayout(this).apply {
            isClickable = true
            setOnClickListener { pin(provider) }
        }
        content.addView(frame, ui.lp(w = ui.dp(w.toInt()), h = ui.dp(h.toInt()), top = 10, bottom = 20))
        val p = Preview(frame, w, h, build)
        previews.add(p)
        return p
    }

    private fun refreshPreviews() {
        for (p in previews) show(p)
    }

    /** applies the provider's RemoteViews into the frame (reapply when it is the same layout, so a tick does not flicker) */
    private fun show(p: Preview) {
        val rv = try {
            p.build(this, p.w, p.h)
        } catch (e: Exception) {
            Log.w(TAG, "preview build failed", e)
            return
        }
        try {
            val existing = if (p.frame.childCount > 0 && p.shown?.layoutId == rv.layoutId) p.frame.getChildAt(0) else null
            if (existing != null) {
                rv.reapply(this, existing)
                neutralize(existing)
            } else {
                p.frame.removeAllViews()
                val v = rv.apply(this, p.frame)
                neutralize(v)
                p.frame.addView(v, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
            }
            p.shown = rv
        } catch (e: Exception) {
            Log.w(TAG, "preview apply failed", e)
            p.frame.removeAllViews()
            p.shown = null
        }
    }

    private fun pin(provider: Class<*>) {
        val mgr = getSystemService(AppWidgetManager::class.java)
        if (mgr == null || !mgr.isRequestPinAppWidgetSupported) {
            Toast.makeText(this, R.string.pin_unsupported, Toast.LENGTH_LONG).show()
            return
        }
        mgr.requestPinAppWidget(ComponentName(this, provider), null, null)
    }

    // --- settings ----------------------------------------------------------------------------

    private fun buildSettings() {
        section(R.string.sec_settings)
        val card = ui.vertical().apply {
            setBackgroundResource(R.drawable.nd_tile_r22)
            setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(16))
        }
        card.addView(ui.label(getString(R.string.set_temp), Nd.FG, 12f))
        card.addView(
            ui.segmented(
                listOf(getString(R.string.opt_celsius), getString(R.string.opt_fahrenheit)),
                if (Prefs.fahrenheit(this)) 1 else 0,
            ) { i ->
                Prefs.setFahrenheit(this, i == 1)
                WeatherWidgetProvider.updateAll(this)   // stored in Celsius: re-render is enough
                refreshPreviews()
            },
            ui.lp(top = 10),
        )
        card.addView(ui.label(getString(R.string.set_player_time), Nd.FG, 12f), ui.lp(top = 20))
        card.addView(
            ui.segmented(
                listOf(getString(R.string.opt_remaining), getString(R.string.opt_total)),
                if (Prefs.timeTotal(this)) 1 else 0,
            ) { i ->
                Prefs.setTimeTotal(this, i == 1)
                MediaTracker.render(this, forceFull = true)
                refreshPreviews()
            },
            ui.lp(top = 10),
        )
        card.addView(ui.body(getString(R.string.set_clock_note), Nd.DIM, 12f), ui.lp(top = 16))
        content.addView(card, ui.lp())
    }

    // --- updates -----------------------------------------------------------------------------

    private fun takeUpdateExtras(i: Intent?) {
        if (i == null) return
        if (i.getBooleanExtra(EXTRA_INSTALL, false)) pendingInstall = true
        i.getParcelableExtra(EXTRA_CONFIRM, Intent::class.java)?.let { pendingConfirm = it }
        i.removeExtra(EXTRA_INSTALL)
        i.removeExtra(EXTRA_CONFIRM)
    }

    private fun buildUpdates() {
        // setup card: the install permission (the system install dialog still confirms each update)
        installCard = Card(R.string.upd_setup_title)
        installCard.body.addView(ui.body(getString(R.string.upd_setup_body)), ui.lp())
        installCard.body.addView(ui.button(getString(R.string.btn_allow_installs), primary = true).apply {
            setOnClickListener {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.fromParts("package", packageName, null)))
                } catch (e: ActivityNotFoundException) {
                    Log.w(TAG, "no unknown sources settings", e)
                }
            }
        }, ui.lp(top = 12))
        // after the last setup card
        val idx = content.indexOfChild(btCard.root)
        content.addView(installCard.root, idx + 1, ui.lp(top = 12))

        section(R.string.sec_updates)
        val card = ui.vertical().apply {
            setBackgroundResource(R.drawable.nd_tile_r22)
            setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(16))
        }
        updVersion = ui.label("", Nd.FG, 12f)
        updLast = ui.label("")
        updStatus = ui.label("", Nd.FG, 12f)
        card.addView(updVersion)
        card.addView(updLast, ui.lp(top = 6))
        card.addView(updStatus, ui.lp(top = 14))
        updInstall = ui.button(getString(R.string.btn_install), primary = true).apply {
            setOnClickListener { Updater.install(this@MainActivity) }
        }
        updCheck = ui.button(getString(R.string.btn_check_now), primary = false).apply {
            setOnClickListener { Updater.checkAsync(this@MainActivity) }
        }
        card.addView(updInstall, ui.lp(top = 14))
        card.addView(updCheck, ui.lp(top = 10))
        content.addView(card, ui.lp())
    }

    private fun onResumeUpdates() {
        Updater.foreground = true
        Updater.cancelNotification(this)
        Updater.syncState(this)
        UpdateJob.schedule(this)
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !getSharedPreferences("nd_update", MODE_PRIVATE).getBoolean("notif_asked", false)
        ) {
            getSharedPreferences("nd_update", MODE_PRIVATE).edit().putBoolean("notif_asked", true).apply()
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }
        val canInstall = Updater.canInstall(this)
        installCard.update(canInstall)
        Updater.onChange = { runOnUiThread { if (!isDestroyed) renderUpdates() } }
        renderUpdates()
        if (Updater.checkDue(this)) Updater.checkAsync(this)
        pendingConfirm?.let { c ->
            pendingConfirm = null
            try {
                startActivity(c.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                Log.w(TAG, "stale install confirmation", e)
            }
        }
        if (pendingInstall) {
            pendingInstall = false
            if (!canInstall) focusCard(installCard) else Updater.install(this)
        }
    }

    private fun renderUpdates() {
        val s = Updater.state
        val ready = Updater.readyUpdate(this)
        updVersion.text = getString(R.string.upd_version, BuildConfig.VERSION_NAME)
        val last = Updater.lastCheck(this)
        updLast.text = if (last == 0L) getString(R.string.upd_never) else getString(
            R.string.upd_last_check,
            DateUtils.formatDateTime(this, last, DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH),
        )
        updStatus.text = when (s) {
            Updater.State.CHECKING -> getString(R.string.upd_checking)
            Updater.State.DOWNLOADING -> getString(R.string.upd_downloading, Updater.progress)
            Updater.State.INSTALLING -> getString(R.string.upd_installing)
            Updater.State.ERROR -> getString(R.string.upd_error, Updater.lastError(this))
            Updater.State.READY ->
                if (Updater.lastResult(this) == "cancelled") getString(R.string.upd_cancelled) + " · " + getString(R.string.upd_ready, Updater.versionOf(ready))
                else getString(R.string.upd_ready, Updater.versionOf(ready))
            Updater.State.UP_TO_DATE -> getString(R.string.upd_up_to_date)
            Updater.State.IDLE -> ""
        }
        updStatus.setTextColor(if (s == Updater.State.ERROR) Nd.ACCENT else Nd.FG)
        updInstall.visibility = if (s == Updater.State.READY && ready > 0) View.VISIBLE else View.GONE
        val working = s == Updater.State.CHECKING || s == Updater.State.DOWNLOADING || s == Updater.State.INSTALLING
        updCheck.isEnabled = !working
        updCheck.alpha = if (working) 0.4f else 1f
    }

    // --- footer ------------------------------------------------------------------------------

    private fun buildFooter() {
        content.addView(ui.link(getString(R.string.footer_weather), Nd.DIM).apply {
            setOnClickListener {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(OPEN_METEO)))
                } catch (e: ActivityNotFoundException) {
                    Log.w(TAG, "no browser", e)
                }
            }
        }, ui.lp(top = 24))
        content.addView(ui.body(getString(R.string.footer_fonts), Nd.DIM, 11f), ui.lp(top = 4, start = 4))
    }
}
