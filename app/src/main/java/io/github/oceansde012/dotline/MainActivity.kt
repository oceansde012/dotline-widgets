package io.github.oceansde012.dotline

import android.Manifest
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.os.Build
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
        const val SECTION_NOTIFICATION = "notification"
        private const val REQ_PICK_IMAGE = 21
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
    private lateinit var setupBox: LinearLayout
    private lateinit var setupDot: View
    private lateinit var setupSummary: TextView
    private lateinit var setupArrow: TextView
    private var setupOpen = true
    private var setupInit = false
    private var setupDone = false
    private lateinit var btButton: TextView
    private lateinit var locButton: TextView
    private lateinit var notifStatus: TextView
    private lateinit var budsName: TextView
    private lateinit var budsResetBtn: TextView
    private lateinit var budsColorBtn: TextView
    private lateinit var nxGrid: LinearLayout

    // diagnostics card
    private lateinit var diagBody: TextView
    private lateinit var diagCard: LinearLayout
    private var diagOpen = false
    private val diagTick = object : Runnable {
        override fun run() {
            if (diagOpen) diagBody.text = Diagnostics.report(this@MainActivity)
            main.postDelayed(this, 1000)
        }
    }
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

    /** true while the crash report screen replaces the normal UI (nothing else is initialised then) */
    private var crashShown = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Lang.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val crash = DotlineApp.readCrash(this)
        if (crash != null) {
            crashShown = true
            showCrashScreen(crash)
            return
        }
        setContentView(R.layout.activity_main)
        ui = Ui(this)
        scroll = requireViewById(R.id.scroll)
        content = requireViewById(R.id.content)
        buildHeader()
        buildGallery()
        buildSetup()
        buildSettings()
        if (BuildConfig.UPDATES_ENABLED) buildUpdates()
        buildDiagnostics()
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

    private fun showCrashScreen(report: String) {
        val u = Ui(this)
        val root = u.vertical().apply {
            setBackgroundColor(Nd.BG)
            setPadding(u.dp(20), u.dp(20), u.dp(20), u.dp(20))
        }
        root.addView(u.label(getString(R.string.crash_title), Nd.ACCENT, 18f), u.lp())
        root.addView(u.body(getString(R.string.crash_text), Nd.DIM, 14f), u.lp(top = 8, bottom = 12))
        val sv = ScrollView(this).apply { setBackgroundColor(Nd.CHIP) }
        sv.addView(u.text(report, 10.5f, Nd.FG, u.mono).apply {
            setTextIsSelectable(true)
            setPadding(u.dp(12), u.dp(12), u.dp(12), u.dp(12))
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        root.addView(sv, u.lp(h = 0, weight = 1f))
        root.addView(u.button(getString(R.string.crash_copy), primary = true).apply {
            setOnClickListener {
                val cm = getSystemService(android.content.ClipboardManager::class.java)
                cm?.setPrimaryClip(android.content.ClipData.newPlainText("Dotline crash", report))
                Toast.makeText(this@MainActivity, R.string.crash_copied, Toast.LENGTH_SHORT).show()
            }
        }, u.lp(top = 12))
        root.addView(u.button(getString(R.string.crash_close), primary = false).apply {
            setOnClickListener {
                DotlineApp.clearCrash(this@MainActivity)
                recreate()
            }
        }, u.lp(top = 8))
        root.setOnApplyWindowInsetsListener { v, ins ->
            val bars = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(bars.left + u.dp(20), bars.top + u.dp(20), bars.right + u.dp(20), bars.bottom + u.dp(20))
            ins
        }
        setContentView(root)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (crashShown) return
        pendingSection = intent.getStringExtra(WeatherWidgetProvider.EXTRA_SECTION)
        intent.removeExtra(WeatherWidgetProvider.EXTRA_SECTION)
        takeUpdateExtras(intent)
    }

    override fun onResume() {
        super.onResume()
        if (crashShown) return
        val granted = MediaTracker.hasAccess(this)
        MediaTracker.ensureStarted(this)   // also starts the power / weather / buds receivers
        if (granted) MediaTracker.render(this, forceFull = true)
        WeatherRepo.refreshAsync(this, minAgeMs = 10 * 60 * 1000L) { runOnUiThread { if (!isDestroyed) refreshPreviews() } }
        BudsRepo.forgetTried()
        BudsRepo.refreshAsync(this, force = true)
        PowerWidgetProvider.refresh(this)
        ClockWidgetProvider.updateAll(this)
        HeadWidgetProvider.updateAll(this)
        WeatherWidgetProvider.updateAll(this)
        updateCards()
        refreshPreviews()
        publishPickerPreviews()
        main.removeCallbacks(previewTick)
        main.post(previewTick)
        main.removeCallbacks(diagTick)
        main.post(diagTick)
        if (BuildConfig.UPDATES_ENABLED) onResumeUpdates()
        pendingSection?.let { section ->
            pendingSection = null
            when (section) {
                SECTION_WEATHER -> focusCard(wxCard)
                SECTION_BLUETOOTH -> focusCard(btCard)
                SECTION_NOTIFICATION -> focusCard(notifCard)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (crashShown) return
        Updater.foreground = false
        Updater.onChange = null
        main.removeCallbacks(previewTick)
        main.removeCallbacks(diagTick)
        main.removeCallbacks(searchRun)
        locating?.cancel()
        locating = null
    }

    // --- header ------------------------------------------------------------------------------

    private fun buildHeader() {
        // the wordmark is dot-matrix drawn by DotText (no font involved)
        val avail = (resources.configuration.screenWidthDp - 40).toFloat()
        val title = ui.dots(getString(R.string.header_title), minOf(5f, avail / DotText.cols(getString(R.string.header_title))))
        content.addView(title, ui.lp(w = title.layoutParams.width, h = title.layoutParams.height, top = 8))
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
        }
        content.addView(ui.label(getString(R.string.header_version, version)), ui.lp(top = 6, bottom = 4))
    }

    // --- diagnostics -------------------------------------------------------------------------

    /** live facts (access, sessions, Bluetooth sources, weather, battery, updater), refreshed every second while shown */
    private fun buildDiagnostics() {
        section(R.string.sec_diag)
        diagCard = ui.vertical().apply {
            setBackgroundResource(R.drawable.nd_tile_r22)
            setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(if (diagOpen) 16 else 8))
        }
        val head = ui.horizontal().apply { minimumHeight = ui.dp(48) }
        val arrow = ui.label(if (diagOpen) "−" else "+", Nd.FG, 14f)
        head.addView(ui.label(getString(R.string.diag_title), Nd.FG, 12f), ui.lp(w = 0, weight = 1f))
        head.addView(arrow)
        diagCard.addView(head, ui.lp())
        diagBody = ui.text("", 10.5f, Nd.DIM, ui.mono).apply {
            setTextIsSelectable(true)
            setLineSpacing(0f, 1.15f)
        }
        val body = ui.vertical().apply { visibility = if (diagOpen) View.VISIBLE else View.GONE }
        body.addView(diagBody, ui.lp())
        body.addView(ui.button(getString(R.string.btn_open_settings_notif), primary = true).apply {
            setOnClickListener { openAccessSettings() }
        }, ui.lp(top = 12))
        body.addView(ui.button(getString(R.string.btn_copy_report), primary = false).apply {
            setOnClickListener {
                val cm = getSystemService(android.content.ClipboardManager::class.java)
                cm?.setPrimaryClip(android.content.ClipData.newPlainText("Dotline report", Diagnostics.report(this@MainActivity)))
                Toast.makeText(this@MainActivity, R.string.report_copied, Toast.LENGTH_SHORT).show()
            }
        }, ui.lp(top = 8))
        diagCard.addView(body, ui.lp())
        head.setOnClickListener {
            diagOpen = !diagOpen
            head.stateDescription = getString(if (diagOpen) R.string.state_expanded else R.string.state_collapsed)
            diagCard.setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(if (diagOpen) 16 else 8))
            body.visibility = if (diagOpen) View.VISIBLE else View.GONE
            arrow.text = if (diagOpen) "−" else "+"
            if (diagOpen) diagBody.text = Diagnostics.report(this)
        }
        diagBody.text = Diagnostics.report(this)
        content.addView(diagCard, ui.lp())
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
        fun update(isDone: Boolean, isCurrent: Boolean = true) {
            if (!init || isDone != done) expanded = !isDone
            init = true
            done = isDone
            current = isCurrent
            apply()
        }

        /** the first step that is not done yet: red dot; later steps: unlit dot */
        private var current = true

        private fun apply() {
            dot.setBackgroundResource(if (done) R.drawable.nd_dot_fg else if (current) R.drawable.nd_dot else R.drawable.nd_dot_off)
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
        // one summary row: all done = collapsed `ALL SET`, otherwise open with the missing steps
        val summary = ui.horizontal().apply {
            minimumHeight = ui.dp(52)
            setBackgroundResource(R.drawable.nd_tile_r22)
            setPadding(ui.dp(16), 0, ui.dp(16), 0)
            isClickable = true
            setOnClickListener { setSetupOpen(!setupOpen) }
        }
        setupDot = View(this)
        summary.addView(setupDot, ui.lp(w = ui.dp(10), h = ui.dp(10), end = 12))
        setupSummary = ui.label("", Nd.FG, 12f)
        summary.addView(setupSummary, ui.lp(w = 0, weight = 1f))
        setupArrow = ui.label("+", Nd.FG, 14f)
        summary.addView(setupArrow)
        content.addView(summary, ui.lp(bottom = 12))
        // the order of the first-run walk: notification access, Bluetooth, city (auto-updates join from buildUpdates)
        setupBox = ui.vertical()
        setupBox.addView(notifCard.root, ui.lp(bottom = 12))
        setupBox.addView(btCard.root, ui.lp(bottom = 12))
        setupBox.addView(wxCard.root, ui.lp(bottom = 12))
        content.addView(setupBox, ui.lp())
    }

    private fun setSetupOpen(on: Boolean) {
        setupOpen = on
        (setupArrow.parent as? View)?.stateDescription = getString(if (on) R.string.state_expanded else R.string.state_collapsed)
        setupBox.visibility = if (on) View.VISIBLE else View.GONE
        setupArrow.text = if (on) "−" else "+"
    }

    private fun buildNotificationCard() {
        val b = notifCard.body
        notifStatus = ui.label("", Nd.FG, 12f)
        b.addView(notifStatus, ui.lp(start = 2))
        b.addView(ui.body(getString(R.string.notif_body)), ui.lp(top = 6))
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
            typeface = ui.mono
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
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
        locButton = ui.button(getString(R.string.btn_use_location), primary = false).apply {
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

    // --- earbuds picture (the widget's left side) -------------------------------------------------

    private fun buildBudsPicture(b: LinearLayout) {
        budsName = ui.label("", Nd.FG, 12f)
        b.addView(budsName, ui.lp(top = 16, start = 2))
        val row = ui.horizontal()
        row.addView(ui.button(getString(R.string.btn_choose_picture), primary = false).apply {
            setOnClickListener { pickPicture() }
        }, ui.lp(w = 0, weight = 1f, end = 8))
        budsResetBtn = ui.button(getString(R.string.btn_reset), primary = false).apply {
            setOnClickListener { BudsRepo.current(this@MainActivity).let { BudsRepo.resetUserPicture(this@MainActivity, it) }; refreshAfterBuds() }
        }
        row.addView(budsResetBtn, ui.lp(w = Ui.WRAP))
        b.addView(row, ui.lp(top = 8))
        val row2 = ui.horizontal()
        row2.addView(ui.button(getString(R.string.btn_from_nothing_x), primary = false).apply {
            setOnClickListener { loadNothingXGrid() }
        }, ui.lp(w = 0, weight = 1f, end = 8))
        budsColorBtn = ui.button("", primary = false).apply {
            setOnClickListener {
                BudsRepo.setNxColor(this@MainActivity, if (BudsRepo.nxColor(this@MainActivity) == "black") "white" else "black")
                refreshAfterBuds()
            }
        }
        row2.addView(budsColorBtn, ui.lp(w = 0, weight = 1f))
        b.addView(row2, ui.lp(top = 8))
        b.addView(ui.body(getString(R.string.buds_picture_note), Nd.DIM, 12f), ui.lp(top = 6, start = 2))
        nxGrid = ui.vertical()
        b.addView(nxGrid, ui.lp(top = 8))
    }

    private fun pickPicture() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_PICK_IMAGE)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "no document picker", e)
            Toast.makeText(this, R.string.no_picker, Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("framework result callback, no AndroidX here")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (crashShown) return
        if (requestCode != REQ_PICK_IMAGE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val app = applicationContext
        Thread {
            val bmp = try { ArtLoader.decode(app, uri.toString()) } catch (e: Exception) { Log.w(TAG, "picked image", e); null }
            if (bmp != null) BudsRepo.setUserPicture(app, BudsRepo.current(app), bmp)
            runOnUiThread { if (!isDestroyed) refreshAfterBuds() }
        }.start()
    }

    private fun refreshAfterBuds() {
        updateCards()
        refreshPreviews()
    }

    /** thumbnails of every product-image-looking bitmap in the installed Nothing X app; tap one to use it */
    private fun loadNothingXGrid() {
        nxGrid.removeAllViews()
        nxGrid.addView(ui.label(getString(R.string.wx_searching), Nd.DIM))
        val app = applicationContext
        Thread {
            val scan = BudsRepo.scanNothingX(app, BudsRepo.current(app).model)
            val cands = BudsRepo.pickerCandidates(app)
            val thumbs = cands.mapNotNull { c -> BudsRepo.decodeCand(app, c, 160)?.let { c to it } }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                nxGrid.removeAllViews()
                if (thumbs.isEmpty()) {
                    nxGrid.addView(ui.body(
                        if (scan.packages.isEmpty()) getString(R.string.nx_not_installed) else getString(R.string.nx_none_found, scan.all.size),
                        Nd.DIM, 12f))
                    return@runOnUiThread
                }
                var row: LinearLayout? = null
                thumbs.forEachIndexed { i, (c, bmp) ->
                    if (i % 4 == 0) {
                        row = ui.horizontal().also { nxGrid.addView(it, ui.lp(top = 6)) }
                    }
                    val iv = android.widget.ImageView(this).apply {
                        setImageBitmap(bmp)
                        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                        setBackgroundResource(R.drawable.nd_row)
                        setOnClickListener {
                            Thread {
                                BudsRepo.decodeCand(app, c, 256)?.let { BudsRepo.setUserPicture(app, BudsRepo.current(app), it) }
                                runOnUiThread { if (!isDestroyed) { nxGrid.removeAllViews(); refreshAfterBuds() } }
                            }.start()
                        }
                        contentDescription = c.name
                    }
                    row!!.addView(iv, ui.lp(w = 0, h = ui.dp(72), weight = 1f, end = 6))
                }
            }
        }.start()
    }

    /** re-checks every requirement; called from onResume so coming back from system settings updates the list */
    private fun updateCards() {
        val access = MediaTracker.hasAccess(this)
        val bt = BudsRepo.hasPermission(this)
        val hasCity = WeatherRepo.hasLocation(this)
        // first-run walk: the first step that is not done is the current one (red dot)
        notifCard.update(access, isCurrent = true)
        btCard.update(bt, isCurrent = access)
        wxCard.update(hasCity, isCurrent = access && bt)
        notifCard.setTitle(getString(R.string.notif_title))
        btCard.setTitle(getString(R.string.bt_title))
        wxCard.setTitle(
            if (hasCity) getString(R.string.wx_title_city, WeatherRepo.locationName(this).uppercase())
            else getString(R.string.wx_title)
        )
        val installOk = BuildConfig.UPDATES_ENABLED && Updater.canInstall(this)
        val total = if (BuildConfig.UPDATES_ENABLED) 4 else 3
        val ready = listOf(access, bt, hasCity, installOk).count { it }
        val all = ready == total
        if (!setupInit || all != setupDone) setSetupOpen(!all)
        setupInit = true
        setupDone = all
        setupSummary.text = if (all) getString(R.string.setup_all_done) else getString(R.string.setup_progress, ready, total)
        setupDot.setBackgroundResource(if (all) R.drawable.nd_dot_fg else R.drawable.nd_dot)
        notifStatus.text = getString(if (access) R.string.notif_status_yes else R.string.notif_status_no)
        notifStatus.setTextColor(if (access) Nd.FG else Nd.ACCENT)
        val b = BudsRepo.current(this)
        budsName.text = if (b.address.isEmpty()) getString(R.string.buds_picture_none)
        else getString(R.string.buds_picture_for, b.title.ifBlank { b.name }, BudsRepo.picture(this, b).source)
        budsResetBtn.alpha = if (BudsRepo.hasUserPicture(this, b)) 1f else 0.4f
        budsColorBtn.text = getString(R.string.btn_nx_color,
            getString(if (BudsRepo.nxColor(this) == "black") R.string.nx_color_black else R.string.nx_color_white))
        btButton.text = getString(if (btBlocked && !bt) R.string.btn_open_app_settings else R.string.btn_allow)
        locButton.text = getString(if (locBlocked) R.string.btn_open_app_settings else R.string.btn_use_location)
    }

    private fun focusCard(c: Card) {
        setSetupOpen(true)
        c.open(true)
        afterLayout {
            scroll.smoothScrollTo(0, (setupBox.top + c.root.top - ui.dp(16)).coerceAtLeast(0))
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
        WeatherRepo.searchAsync(this, q) { hits ->
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
            val a = Geocoder(ctx, Lang.locale(ctx)).getFromLocation(lat, lon, 1)?.firstOrNull()
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
        if (crashShown) return
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
            PlayerRenderer.full(c, w, h, MediaTracker.track)   // the real state, never a sample
        }.also { playerPreview = it }
        addWidgetCard(R.string.gallery_head, R.string.head_widget_desc, HeadWidgetProvider::class.java, wide, 56f, { c, w, h -> HeadWidgetProvider.buildPreview(c, w, h) })
        addWidgetCard(R.string.gallery_clock, R.string.clock_widget_desc, ClockWidgetProvider::class.java, minOf(avail, 220f), 150f, { c, w, h -> ClockWidgetProvider.buildPreview(c, w, h) })
        addWidgetCard(R.string.gallery_weather, R.string.weather_widget_desc, WeatherWidgetProvider::class.java, 150f, 150f, { c, w, h -> WeatherWidgetProvider.buildPreview(c, w, h) })
        addWidgetCard(R.string.gallery_power, R.string.power_widget_desc, PowerWidgetProvider::class.java, minOf(avail, 230f), 62f, { c, w, h -> PowerWidgetProvider.buildPreview(c, w, h) })
        addWidgetCard(R.string.gallery_buds, R.string.buds_widget_desc, BudsWidgetProvider::class.java, 150f, 62f, { c, w, h -> BudsWidgetProvider.buildPreview(c, w, h) })
    }

    /**
     * Android 15+: generated widget previews for the picker, rendered by the same code as the home screen
     * (the layout previews are only the fallback for older versions). Rate limited by the system, so failures are ignored.
     */
    private fun publishPickerPreviews() {
        if (Build.VERSION.SDK_INT < 35) return
        val mgr = getSystemService(AppWidgetManager::class.java) ?: return
        val dm = resources.displayMetrics
        fun dp(px: Int) = px / dm.density
        val entries = listOf(
            Triple(PlayerWidgetProvider::class.java, 380f, 165f) to { c: Context, w: Float, h: Float -> PlayerRenderer.full(c, w, h, MediaTracker.track) },
            Triple(HeadWidgetProvider::class.java, 380f, 58f) to { c: Context, w: Float, h: Float -> HeadWidgetProvider.buildPreview(c, w, h) },
            Triple(ClockWidgetProvider::class.java, 230f, 190f) to { c: Context, w: Float, h: Float -> ClockWidgetProvider.buildPreview(c, w, h) },
            Triple(WeatherWidgetProvider::class.java, 150f, 150f) to { c: Context, w: Float, h: Float -> WeatherWidgetProvider.buildPreview(c, w, h) },
            Triple(PowerWidgetProvider::class.java, 230f, 62f) to { c: Context, w: Float, h: Float -> PowerWidgetProvider.buildPreview(c, w, h) },
            Triple(BudsWidgetProvider::class.java, 160f, 62f) to { c: Context, w: Float, h: Float -> BudsWidgetProvider.buildPreview(c, w, h) },
        )
        for ((spec, build) in entries) {
            try {
                mgr.setWidgetPreview(ComponentName(this, spec.first), AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN, build(this, spec.second, spec.third))
            } catch (e: Throwable) {
                Log.w(TAG, "setWidgetPreview ${spec.first.simpleName}", e)
            }
        }
    }

    private fun addWidgetCard(
        titleRes: Int, descRes: Int, provider: Class<*>, w: Float, h: Float,
        build: (Context, Float, Float) -> RemoteViews,
    ): Preview {
        val head = ui.horizontal()
        val texts = ui.vertical()
        texts.addView(ui.label(getString(titleRes), Nd.FG, 12f))
        texts.addView(ui.body(getString(descRes), Nd.DIM, 12f), ui.lp(top = 2))
        head.addView(texts, ui.lp(w = 0, weight = 1f, end = 12))
        head.addView(ui.smallButton(getString(R.string.btn_add_home)).apply {
            setOnClickListener { pin(provider) }
        }, ui.lp(w = Ui.WRAP))
        content.addView(head, ui.lp(top = 14))

        val frame = FrameLayout(this).apply {
            isClickable = true
            setOnClickListener { pin(provider) }
        }
        content.addView(frame, ui.lp(w = ui.dp(w.toInt()), h = ui.dp(h.toInt()), top = 10, bottom = 12).apply { gravity = Gravity.CENTER_HORIZONTAL })
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
        card.addView(ui.label(getString(R.string.set_language), Nd.FG, 12f))
        card.addView(
            ui.segmented(
                listOf(getString(R.string.lang_auto), "EN", "УКР", "РУС"),
                Lang.CODES.indexOf(Lang.pref(this)).coerceAtLeast(0),
            ) { i ->
                Lang.setPref(this, Lang.CODES[i])
                MediaTracker.render(this, forceFull = true)
                BudsWidgetProvider.updateAll(this)
                recreate()   // onResume re-renders the rest
            },
            ui.lp(top = 10),
        )
        card.addView(ui.label(getString(R.string.set_temp), Nd.FG, 12f), ui.lp(top = 20))
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
        val budsCard = ui.vertical().apply {
            setBackgroundResource(R.drawable.nd_tile_r22)
            setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(16))
        }
        budsCard.addView(ui.label(getString(R.string.buds_picture_title), Nd.FG, 12f))
        buildBudsPicture(budsCard)
        content.addView(budsCard, ui.lp(top = 12))
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
        setupBox.addView(installCard.root, setupBox.indexOfChild(btCard.root) + 1, ui.lp(bottom = 12))

        section(R.string.sec_updates)
        val card = ui.vertical().apply {
            setBackgroundResource(R.drawable.nd_tile_r22)
            setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(16))
        }
        updVersion = ui.label("", Nd.FG, 12f)
        updLast = ui.label("", Nd.DIM, 11f)
        updStatus = ui.label("", Nd.FG, 12f)
        card.addView(updVersion)
        card.addView(updStatus, ui.lp(top = 8))
        card.addView(updLast, ui.lp(top = 6))
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
        updStatus.visibility = if (updStatus.text.isEmpty()) View.GONE else View.VISIBLE
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

