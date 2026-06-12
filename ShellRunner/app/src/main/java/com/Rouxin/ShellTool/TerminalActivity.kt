package com.Rouxin.ShellTool

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.terminal.TextStyle
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import java.io.File

class TerminalActivity : AppCompatActivity() {

    // ========== UI ==========
    private lateinit var terminalContainer: FrameLayout
    private lateinit var terminalInput: EditText
    private lateinit var terminalTitle: TextView
    private lateinit var terminalStatus: TextView
    private lateinit var backButton: ImageButton
    private lateinit var sendButton: MaterialButton
    private lateinit var stopButton: MaterialButton
    private lateinit var linuxButton: MaterialButton
    private lateinit var addTabButton: ImageButton
    private lateinit var settingsButton: ImageButton
    private lateinit var tabBar: LinearLayout
    private lateinit var tabScrollView: HorizontalScrollView
    private lateinit var tabDivider: View
    private lateinit var inputArea: View

    // ========== 会话管理 ==========
    private data class TabSession(
        val session: TerminalSession,
        val terminalView: TerminalView,
        val label: String,
        val isRunning: Boolean = true
    )

    private val tabs = mutableListOf<TabSession>()
    private var activeTab: TabSession? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastScale = 1.0f
    private val isTerminalMode: Boolean get() = intent.getBooleanExtra("terminal_mode", false)
    private val workDir: String get() = intent.getStringExtra("work_dir") ?: "/data/RouXin"
    private var globalLayoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null

    // ========== 终端个性化设置 ==========
    private var mFontSize = 14
    private var mFgColor = 0xFFFFFFFF.toInt()
    private var mBgColor = 0xFF1E1E1E.toInt()
    private var mColorSchemeName = "dark"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_terminal)
        initViews()
        loadTerminalPrefs()

        globalLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
            activeTab?.let { tab ->
                val tv = tab.terminalView
                val w = tv.width; val h = tv.height
                if (w > 0 && h > 0) {
                    tab.session.updateSize(calculateColumns(w), calculateRows(h), w, h)
                }
            }
        }
        globalLayoutListener?.let { terminalContainer.viewTreeObserver.addOnGlobalLayoutListener(it) }

        // 恢复已有 session（跨 Activity 销毁保活）
        restoreSessions()

        if (tabs.isEmpty()) {
            val scriptPath = intent.getStringExtra("script_path")
            val scriptName = intent.getStringExtra("script_name") ?: "脚本"
            if (!scriptPath.isNullOrEmpty()) {
                val dir = File(scriptPath).canonicalFile.parent ?: "/data/RouXin"
                createNewTerminal(dir, scriptName, scriptPath)
            } else if (isTerminalMode) {
                createNewTerminal(workDir, "终端")
            } else {
                terminalTitle.text = "\uD83D\uDCAB RouXin \u613F \u6B23"
                terminalStatus.text = "就绪"
                inputArea.visibility = View.GONE
                stopButton.visibility = View.GONE
                clearContainerEmptyViews()
                showOnEmpty("请选择脚本执行")
            }
        } else {
            // 已有 session，检查是否带新脚本
            val scriptPath = intent.getStringExtra("script_path")
            val scriptName = intent.getStringExtra("script_name")
            if (!scriptPath.isNullOrEmpty()) {
                val dir = File(scriptPath).canonicalFile.parent ?: "/data/RouXin"
                createNewTerminal(dir, scriptName ?: "脚本", scriptPath)
            }
        }

        linuxButton.setOnClickListener {
            runOnUiThread {
                val label = "终端#" + (tabs.size + 1)
                createNewTerminal(workDir, label)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val scriptPath = intent.getStringExtra("script_path")
        val scriptName = intent.getStringExtra("script_name")
        if (!scriptPath.isNullOrEmpty()) {
            val dir = File(scriptPath).canonicalFile.parent ?: "/data/RouXin"
            createNewTerminal(dir, scriptName ?: "脚本", scriptPath)
        }
    }

    private fun restoreSessions() {
        val iterator = liveSessions.iterator()
        while (iterator.hasNext()) {
            val (session, label) = iterator.next()
            if (try { session.isRunning() } catch (_: Exception) { false }) {
                val tv = buildTerminalView(session)
                val tab = TabSession(session, tv, label)
                tabs.add(tab)
                tv.visibility = View.GONE
                terminalContainer.addView(tv)
                addTabToBar(tab)
            } else {
                iterator.remove()
            }
        }
        if (tabs.isNotEmpty()) {
            switchTo(tabs.last())
        }
    }

    private fun buildTerminalView(session: TerminalSession): TerminalView {
        val tv = TerminalView(this, null)
        tv.setTextSize(mFontSize)
        tv.setBackgroundColor(mBgColor)
        val emu = session.getEmulator()
        if (emu != null) {
            emu.mColors.mCurrentColors[TextStyle.COLOR_INDEX_FOREGROUND] = mFgColor
            emu.mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND] = mBgColor
        }
        tv.isFocusableInTouchMode = true
        tv.isFocusable = true
        tv.setTerminalViewClient(terminalViewClientImpl)
        tv.attachSession(session)
        tv.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        return tv
    }

    private fun clearContainerEmptyViews() {
        if (tabs.isNotEmpty()) return // 有会话时不清理
        val toRemove = mutableListOf<View>()
        for (i in 0 until terminalContainer.childCount) {
            val v = terminalContainer.getChildAt(i)
            if (v !is TerminalView) toRemove.add(v)
        }
        toRemove.forEach { terminalContainer.removeView(it) }
    }

    private fun showOnEmpty(msg: String) {
        val tv = TextView(this).apply {
            text = msg
            setTextColor(0xFF6F6F6F.toInt())
            textSize = 16f
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        terminalContainer.addView(tv)
    }

    private fun initViews() {
        terminalContainer  = findViewById(R.id.terminalContainer)
        terminalInput      = findViewById(R.id.terminalInput)
        terminalTitle      = findViewById(R.id.terminalTitle)
        terminalStatus     = findViewById(R.id.terminalStatus)
        backButton         = findViewById(R.id.backButton)
        sendButton         = findViewById(R.id.sendButton)
        stopButton         = findViewById(R.id.stopButton)
        linuxButton        = findViewById(R.id.linuxButton)
        addTabButton       = findViewById(R.id.addTabButton)
        settingsButton     = findViewById(R.id.settingsButton)
        tabBar             = findViewById(R.id.tabBar)
        tabScrollView      = findViewById(R.id.tabScrollView)
        tabDivider         = findViewById(R.id.tabDivider)
        inputArea          = findViewById(R.id.inputArea)

        backButton.setOnClickListener { finish() }
        sendButton.setOnClickListener { sendInput() }
        stopButton.setOnClickListener { killActiveSession() }
        addTabButton.setOnClickListener {
            // 不 finish，回到文件页选脚本（保持 TerminalActivity 存活）
            startActivity(Intent(this, MainActivity::class.java))
        }
        settingsButton.setOnClickListener { showSettingsDialog() }
        terminalInput.setOnEditorActionListener { _, _, _ -> sendInput(); true }
    }

    // ========== Tab 管理 ==========

    private fun createNewTerminal(dir: String, label: String, scriptPath: String? = null) {
        val session = TerminalSession(
            "su", dir, emptyArray(), emptyArray(), null, sessionClientImpl
        )
        session.mSessionName = label
        session.updateSize(80, 24, 480, 720)

        val terminalView = buildTerminalView(session)
        terminalView.visibility = View.GONE
        terminalContainer.addView(terminalView)

        liveSessions.add(session to label)
        val tab = TabSession(session, terminalView, label)
        tabs.add(tab)
        addTabToBar(tab)
        switchTo(tab)

        terminalView.postDelayed({
            val w = terminalView.width; val h = terminalView.height
            if (w > 0 && h > 0) {
                session.updateSize(calculateColumns(w), calculateRows(h), w, h)
            }
            terminalView.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            imm?.showSoftInput(terminalView, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)

            if (scriptPath != null) {
                val scriptFile = java.io.File(scriptPath)
                val dir = scriptFile.parent?.replace("'", "'\\''") ?: "/"
                val name = scriptFile.name.replace("'", "'\\''")
                session.write("cd '$dir' && stty -echo && (sh './$name' || './$name') ; stty echo\n")
            }
        }, 300)
    }

    private fun calculateColumns(width: Int): Int {
        val tv = activeTab?.terminalView
        val fw = if (tv != null) tv.mRenderer.getFontWidth() else dpf(7)
        return (width / fw).toInt().coerceAtLeast(40)
    }
    private fun calculateRows(height: Int): Int {
        val tv = activeTab?.terminalView
        val fls = if (tv != null) tv.mRenderer.getFontLineSpacing().toFloat() else dpf(14)
        return (height / fls).toInt().coerceAtLeast(8)
    }

    private fun addTabToBar(tab: TabSession) {
        val tabView = createTabView(tab)
        tabBar.addView(tabView)
        updateTabBarVisibility()
        tabScrollView.post { tabScrollView.fullScroll(HorizontalScrollView.FOCUS_RIGHT) }
    }

    private fun createTabView(tab: TabSession): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(8), dp(6))
            background = createTabBackground(false)
            tag = tab.session.hashCode()

            val dot = View(this@TerminalActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(6) }
                setBackgroundColor(0xFF4CAF50.toInt())
            }

            val name = TextView(this@TerminalActivity).apply {
                text = tab.label
                setTextColor(0xFFCCCCCC.toInt())
                textSize = 12f
                maxLines = 1
            }

            val close = TextView(this@TerminalActivity).apply {
                text = "\u00D7"
                setTextColor(0xFF666666.toInt())
                textSize = 16f
                setPadding(dp(4), 0, 0, 0)
                setOnClickListener { closeSession(tab) }
            }

            addView(dot); addView(name); addView(close)

            setOnClickListener {
                if (activeTab !== tab) switchTo(tab)
            }
        }
    }

    private fun createTabBackground(isActive: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(if (isActive) 0xFF30363D.toInt() else Color.TRANSPARENT)
            if (isActive) setStroke(1, 0xFF484F58.toInt())
        }
    }

    private fun updateTabBarVisibility() {
        tabScrollView.visibility = if (tabs.isNotEmpty()) View.VISIBLE else View.GONE
        tabDivider.visibility = if (tabs.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun closeSession(tab: TabSession) {
        tab.session.finishIfRunning()
        liveSessions.removeAll { it.first === tab.session }
        val tb = tabBar.findViewWithTag<View>(tab.session.hashCode())
        tb?.let { tabBar.removeView(it) }
        tabs.remove(tab)
        updateTabBarVisibility()

        if (activeTab === tab) {
            if (tabs.isNotEmpty()) switchTo(tabs.last())
            else { activeTab = null; finish() }
        }
    }

    private fun switchTo(tab: TabSession) {
        activeTab?.let { it.terminalView.visibility = View.GONE }
        activeTab = tab
        tab.terminalView.visibility = View.VISIBLE

        terminalTitle.text = tab.label
        terminalStatus.text = if (try { tab.session.isRunning() } catch (_: Exception) { false }) "运行中" else "已结束"
        stopButton.visibility = if (try { tab.session.isRunning() } catch (_: Exception) { false }) View.VISIBLE else View.GONE

        if (isTerminalMode && try { tab.session.isRunning() } catch (_: Exception) { false }) {
            inputArea.visibility = View.VISIBLE
        } else {
            inputArea.visibility = View.GONE
        }

        tab.terminalView.requestFocus()
        tabs.forEach { updateTabUI(it) }
    }

    private fun updateTabUI(tab: TabSession) {
        val tb = tabBar.findViewWithTag<View>(tab.session.hashCode())
        if (tb !is LinearLayout) return
        tb.background = createTabBackground(tab === activeTab)
        val name = tb.getChildAt(1) as? TextView ?: return
        name.setTextColor(if (tab === activeTab) 0xFFFFFFFF.toInt() else 0xFFCCCCCC.toInt())
    }

    // ========== 输入/控制 ==========

    private fun sendInput() {
        val input = terminalInput.text.toString()
        val session = activeTab?.session ?: return
        if (input.isNotEmpty()) {
            session.write("$input\n")
            terminalInput.text.clear()
        }
    }

    private fun killActiveSession() {
        activeTab?.session?.finishIfRunning()
        if (!isTerminalMode) inputArea.visibility = View.GONE
        stopButton.visibility = View.GONE
        terminalStatus.text = "已结束"
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ========== 生命周期 ==========

    override fun onResume() {
        super.onResume()
        updateTerminalSize()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        handler.postDelayed({ updateTerminalSize() }, 200)
    }

    private fun updateTerminalSize() {
        val tab = activeTab ?: return
        val tv = tab.terminalView; val w = tv.width; val h = tv.height
        if (w > 0 && h > 0) {
            tab.session.updateSize(calculateColumns(w), calculateRows(h), w, h)
        }
        tv.scrollToBottom()
    }

    override fun onDestroy() {
        // 不杀 session：它们在 companion 的 liveSessions 里保活
        globalLayoutListener?.let { terminalContainer.viewTreeObserver.removeOnGlobalLayoutListener(it) }
        tabs.clear()
        super.onDestroy()
    }

    // ========== 设置 ==========

    private fun showSettingsDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_terminal_settings, null)

        val seekBar = view.findViewById<SeekBar>(R.id.fontSizeSeek)
        val sizeLabel = view.findViewById<TextView>(R.id.fontSizeLabel)

        seekBar.progress = mFontSize - 10
        sizeLabel.text = "${mFontSize}sp"
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                mFontSize = p + 10; sizeLabel.text = "${mFontSize}sp"; applyFontSize()
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) { saveTerminalPrefs() }
        })

        val schemeRow1 = view.findViewById<LinearLayout>(R.id.schemeRow1)
        val schemeRow2 = view.findViewById<LinearLayout>(R.id.schemeRow2)

        if (schemeRow1 != null && schemeRow2 != null) {
            val names = COLOR_SCHEME_NAMES
            schemeRow1.removeAllViews(); schemeRow2.removeAllViews()
            names.forEachIndexed { idx, name ->
                val label = COLOR_SCHEME_LABELS[name] ?: name
                val colors = COLOR_SCHEMES[name] ?: return@forEachIndexed
                val btn = Button(this).apply {
                    text = label; textSize = 11f
                    setTextColor(colors[0]); setBackgroundColor(colors[1])
                    layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(6) }
                    setOnClickListener {
                        applyColorScheme(name); saveTerminalPrefs()
                    }
                }
                if (idx < 3) schemeRow1.addView(btn) else schemeRow2.addView(btn)
            }
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("终端设置")
            .setView(view)
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun applyFontSize() { tabs.forEach { it.terminalView.setTextSize(mFontSize) } }

    private fun applyColorScheme(name: String) {
        val scheme = COLOR_SCHEMES[name] ?: COLOR_SCHEMES["dark"]!!
        mFgColor = scheme[0]; mBgColor = scheme[1]; mColorSchemeName = name
        tabs.forEach { tab ->
            val tv = tab.terminalView
            tv.setBackgroundColor(mBgColor)
            val emu = tab.session.getEmulator()
            if (emu != null) {
                emu.mColors.mCurrentColors[TextStyle.COLOR_INDEX_FOREGROUND] = mFgColor
                emu.mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND] = mBgColor
            }
            tv.invalidate()
        }
    }

    private fun loadTerminalPrefs() {
        val p = getSharedPreferences("terminal_prefs", MODE_PRIVATE)
        mFontSize = p.getInt("font_size", 14).coerceIn(10, 28)
        mColorSchemeName = p.getString("color_scheme", "dark") ?: "dark"
        val scheme = COLOR_SCHEMES[mColorSchemeName] ?: COLOR_SCHEMES["dark"]!!
        mFgColor = scheme[0]; mBgColor = scheme[1]
    }

    private fun saveTerminalPrefs() {
        getSharedPreferences("terminal_prefs", MODE_PRIVATE).edit()
            .putInt("font_size", mFontSize)
            .putString("color_scheme", mColorSchemeName)
            .apply()
    }

    private fun dpf(v: Int): Float = v * resources.displayMetrics.density

    // ========== TerminalViewClient ==========

    private val terminalViewClientImpl = object : TerminalViewClient {
        override fun onSingleTapUp(e: MotionEvent) {
            activeTab?.terminalView?.let { tv ->
                tv.requestFocus()
                val imm = getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                imm?.showSoftInput(tv, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
        }
        override fun shouldBackButtonBeMappedToEscape(): Boolean = true
        override fun shouldEnforceCharBasedInput(): Boolean = false
        override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
        override fun isTerminalViewSelected(): Boolean = true
        override fun copyModeChanged(copyMode: Boolean) {}
        override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false
        override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false
        override fun onLongPress(event: MotionEvent): Boolean {
            // 返回 false 让 TerminalView 进入文本选择模式
            return false
        }
        override fun readControlKey(): Boolean = false
        override fun readAltKey(): Boolean = false
        override fun readShiftKey(): Boolean = false
        override fun readFnKey(): Boolean = false
        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false
        override fun onEmulatorSet() {}
        override fun onScale(scale: Float): Float {
            if (lastScale == 1.0f || scale.isNaN() || scale.isInfinite()) {
                lastScale = scale; return scale
            }
            val delta = scale / lastScale
            if (kotlin.math.abs(delta - 1.0f) > 0.02f) {
                mFontSize = (mFontSize * delta).toInt().coerceIn(10, 28)
                applyFontSize(); saveTerminalPrefs()
            }
            lastScale = scale; return scale
        }
        override fun logError(tag: String, message: String) {}
        override fun logWarn(tag: String, message: String) {}
        override fun logInfo(tag: String, message: String) {}
        override fun logDebug(tag: String, message: String) {}
        override fun logVerbose(tag: String, message: String) {}
        override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {}
        override fun logStackTrace(tag: String, e: Exception) {}
    }

    // ========== TerminalSessionClient ==========

    private val sessionClientImpl = object : TerminalSessionClient {
        override fun onSessionFinished(session: TerminalSession) {
            handler.post {
                val tab = tabs.find { it.session === session }
                if (tab != null) {
                    terminalStatus.text = "已结束"
                    stopButton.visibility = View.GONE
                    if (!isTerminalMode) inputArea.visibility = View.GONE
                    updateTabUI(tab)
                }
            }
        }

        override fun onTitleChanged(session: TerminalSession) {
            handler.post {
                if (activeTab?.session === session) {
                    terminalTitle.text = session.title ?: activeTab?.label ?: "Terminal"
                }
            }
        }

        override fun onTextChanged(session: TerminalSession) {
            for (tab in tabs) {
                if (tab.session === session) {
                    handler.post { tab.terminalView.invalidate() }
                    break
                }
            }
        }

        override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("terminal", text))
        }

        override fun onPasteTextFromClipboard(session: TerminalSession) {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = clipboard.primaryClip
            if (clip != null && clip.itemCount > 0) {
                clip.getItemAt(0).text?.toString()?.let { session.write(it) }
            }
        }

        override fun onBell(session: TerminalSession) {}
        override fun onColorsChanged(session: TerminalSession) {}
        override fun onTerminalCursorStateChange(state: Boolean) {}
        override fun getTerminalCursorStyle(): Int? = null
        override fun logError(tag: String, message: String) {}
        override fun logWarn(tag: String, message: String) {}
        override fun logInfo(tag: String, message: String) {}
        override fun logDebug(tag: String, message: String) {}
        override fun logVerbose(tag: String, message: String) {}
        override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {}
        override fun logStackTrace(tag: String, e: Exception) {}
    }

    // ========== 配色方案 & 持久化 session ==========

    companion object {
        /** 跨 Activity 销毁保活的 PTY session（session → label） */
        val liveSessions = mutableListOf<Pair<TerminalSession, String>>()

        val COLOR_SCHEMES = mapOf(
            "dark"    to intArrayOf(0xFFCCCCCC.toInt(), 0xFF1E1E1E.toInt()),
            "light"   to intArrayOf(0xFF1E1E1E.toInt(), 0xFFFFFFFF.toInt()),
            "green"   to intArrayOf(0xFF00FF00.toInt(), 0xFF000000.toInt()),
            "amber"   to intArrayOf(0xFFFFB000.toInt(), 0xFF000000.toInt()),
            "solarized" to intArrayOf(0xFF839496.toInt(), 0xFF002B36.toInt()),
            "monokai" to intArrayOf(0xFFF8F8F2.toInt(), 0xFF272822.toInt())
        )
        val COLOR_SCHEME_NAMES = listOf("dark", "light", "green", "amber", "solarized", "monokai")
        val COLOR_SCHEME_LABELS = mapOf(
            "dark" to "暗色", "light" to "亮色", "green" to "绿莹",
            "amber" to "琥珀", "solarized" to "日光", "monokai" to "Monokai"
        )
    }
}
