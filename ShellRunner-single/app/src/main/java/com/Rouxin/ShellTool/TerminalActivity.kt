package com.Rouxin.ShellTool

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.termux.terminal.TerminalColors
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
        val isRunning: Boolean = true,
        val scriptPath: String? = null,
        /** 执行阶段：0=交互模式, 1=sh执行, 2=cd执行 */
        val executionPhase: Int = 0
    )

    private val tabs = mutableListOf<TabSession>()
    private var activeTab: TabSession? = null
    private val handler = Handler(Looper.getMainLooper())

    private val isTerminalMode: Boolean get() = intent.getBooleanExtra("terminal_mode", false)
    private val useRoot: Boolean get() = intent.getBooleanExtra("use_root", false)
    private val workDir: String get() = intent.getStringExtra("work_dir") ?: "/data/RouXin"

    override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
        if (event?.action == KeyEvent.ACTION_DOWN && event.keyCode != KeyEvent.KEYCODE_BACK) {
            val tab = activeTab
            if (tab != null && tab.scriptPath != null && !tab.session.isRunning()) {
                finish()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_terminal)
        initViews()

        loadTerminalPrefs()

        // 监听布局变化（输入法弹起/收起时自动更新终端尺寸）
        terminalContainer.viewTreeObserver.addOnGlobalLayoutListener {
            activeTab?.let { tab ->
                val tv = tab.terminalView
                val w = tv.width
                val h = tv.height
                if (w > 0 && h > 0) {
                    tab.session.updateSize(
                        calculateColumns(w), calculateRows(h), w, h
                    )
                }
            }
        }

        if (isTerminalMode) {
            createNewTab(workDir, "终端")
        } else {
            val scriptPath = intent.getStringExtra("script_path")
            val scriptName = intent.getStringExtra("script_name") ?: "脚本"
            if (!scriptPath.isNullOrEmpty()) {
                val dir = File(scriptPath).canonicalFile.parent ?: "/data/RouXin"
                createNewTab(dir, scriptName, scriptPath)
            } else {
                // 无脚本
                terminalTitle.text = "💫 RouXin 愿 欣"
                terminalStatus.text = "就绪"
                inputArea.visibility = View.GONE
                stopButton.visibility = View.GONE
                showOnEmpty("请选择脚本执行")
            }
        }
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
        tabBar             = findViewById(R.id.tabBar)
        tabScrollView      = findViewById(R.id.tabScrollView)
        tabDivider         = findViewById(R.id.tabDivider)
        addTabButton       = findViewById(R.id.addTabButton)
        settingsButton     = findViewById(R.id.settingsButton)
        inputArea          = findViewById(R.id.inputArea)

        backButton.setOnClickListener { finish() }
        sendButton.setOnClickListener { sendInput() }
        stopButton.setOnClickListener { killActiveSession() }
        addTabButton.setOnClickListener { finish() }  // 回到文件页
        settingsButton.setOnClickListener { showSettingsDialog() }
        terminalInput.setOnEditorActionListener { _, _, _ -> sendInput(); true }
    }

    // ========== Tab 管理 ==========

    private fun createNewTab(dir: String, label: String, scriptPath: String? = null) {
        // 清理空状态占位 View（如果有）
        for (i in terminalContainer.childCount - 1 downTo 0) {
            val child = terminalContainer.getChildAt(i)
            if (child !is com.termux.view.TerminalView) terminalContainer.removeView(child)
        }

        // 创建 Termux TerminalSession（使用我们的 root shell + script PTY 实现）
        val session = TerminalSession(
            if (useRoot) "su" else "sh", // shellPath
            dir,              // cwd
            emptyArray(),     // args
            emptyArray(),     // env
            null,             // transcriptRows
            sessionClientImpl // TerminalSessionClient callback
        )
        session.mSessionName = label
        // 不再在 startProcess 中执行脚本，脚本由 postDelayed 写入
        // 立即设置初始尺寸，确保 emulator buffer 有效
        // updateSize 会触发 startProcess()，启动交互式 shell
        session.updateSize(80, 24, 480, 720)

        // 创建 TerminalView
        val terminalView = TerminalView(this, null)
        terminalView.setTextSize(mFontSize)
        terminalView.isFocusableInTouchMode = true
        terminalView.isFocusable = true
        terminalView.setTerminalViewClient(terminalViewClientImpl)
        terminalView.attachSession(session)
        terminalView.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        // 初始隐藏，切换到它时再显示
        terminalView.visibility = View.GONE
        terminalContainer.addView(terminalView)

        val tab = TabSession(
            session = session,
            terminalView = terminalView,
            label = label,
            scriptPath = scriptPath,
            executionPhase = if (scriptPath != null) 1 else 0  // 非脚本: 0, 脚本: 先1(sh模式)
        )
        tabs.add(tab)
        addTabToBar(tab)
        switchTo(tab)

        // 延迟执行 + 更新真实尺寸
        // postDelayed 确保 PTY shell 完全就绪后再写命令，避免首帧空白
        terminalView.postDelayed({
            val w = terminalView.width
            val h = terminalView.height
            if (w > 0 && h > 0) {
                session.updateSize(calculateColumns(w), calculateRows(h), w, h)
            }
            terminalView.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            imm?.showSoftInput(terminalView, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)

            // 脚本模式：在交互式 shell 就绪后写入执行命令，然后退出
            // 所有命令一行内执行，不使用 stty -echo，避免回显状态污染
            if (scriptPath != null) {
                val scriptFile = java.io.File(scriptPath)
                val scriptDir = scriptFile.canonicalFile.parent?.replace("'", "'\\''") ?: "/"
                val scriptName = scriptFile.name.replace("'", "'\\''")
                session.mScriptPath = scriptPath  // 用于 onSessionFinished fallback
                session.mScriptMode = 1  // 标记当前为 sh 执行
                val cmd = "cd '" + scriptDir + "' && sh '" + scriptName + "' ; exit\n"
                session.write(cmd)
            }
        }, 300)
    }

    /**
     * Phase 1 (sh) 执行失败后重启终端，切换为 Phase 2 (cd) 执行
     */
    private fun restartTabWithCd(tabIdx: Int, oldTab: TabSession) {
        val scriptPath = oldTab.scriptPath ?: return
        val scriptFile = java.io.File(scriptPath)
        val dir = scriptFile.canonicalFile.parent ?: "/data/RouXin"
        val label = oldTab.label

        // 创建新 session（Phase 2: cd 模式）
        val newSession = TerminalSession(
            if (useRoot) "su" else "sh", dir, emptyArray(), emptyArray(), null, sessionClientImpl
        )
        newSession.mSessionName = label
        newSession.updateSize(80, 24, 480, 720)

        // 创建新 TerminalView
        val newView = TerminalView(this, null)
        newView.setTextSize(mFontSize)
        newView.isFocusableInTouchMode = true
        newView.isFocusable = true
        newView.setTerminalViewClient(terminalViewClientImpl)
        newView.attachSession(newSession)
        newView.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        newView.visibility = View.GONE
        terminalContainer.addView(newView)

        // 替换 tab
        val newTab = TabSession(
            session = newSession,
            terminalView = newView,
            label = label,
            isRunning = true,
            scriptPath = scriptPath,
            executionPhase = 2  // cd模式
        )
        tabs[tabIdx] = newTab

        // 清理旧 session / view
        oldTab.session.finishIfRunning()
        terminalContainer.removeView(oldTab.terminalView)

        // 重建 tab bar + 切换到新 tab
        rebuildTabBar()
        switchTo(newTab)

        // 延迟更新真实尺寸 + 弹出键盘 + 写入 cd 执行命令
        newView.postDelayed({
            val w = newView.width
            val h = newView.height
            if (w > 0 && h > 0) {
                newSession.updateSize(
                    calculateColumns(w), calculateRows(h), w, h
                )
            }
            newView.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            imm?.showSoftInput(newView, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)

            // Phase 2: cd 执行模式
            newSession.mScriptPath = scriptPath
            newSession.mScriptMode = 2
            val scriptFile2 = java.io.File(scriptPath)
            val scriptDir2 = scriptFile2.canonicalFile.parent?.replace("'", "'\\''") ?: "/"
            val scriptName2 = scriptFile2.name.replace("'", "'\\''")
            val cmd = "cd '" + scriptDir2 + "' && './" + scriptName2 + "' ; exit\n"
            newSession.write(cmd)
        }, 300)
    }

    private fun calculateColumns(width: Int): Int = (width / dpf(7)).toInt().coerceAtLeast(40)
    private fun calculateRows(height: Int): Int = (height / dpf(14)).toInt().coerceAtLeast(8)

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
                setBackgroundColor(if (tab.session.isRunning()) 0xFF4CAF50.toInt() else 0xFF6F6F6F.toInt())
            }

            val name = TextView(this@TerminalActivity).apply {
                text = tab.label
                setTextColor(0xFFCCCCCC.toInt())
                textSize = 12f
                maxLines = 1
            }

            val close = TextView(this@TerminalActivity).apply {
                text = "×"
                setTextColor(0xFF666666.toInt())
                textSize = 16f
                setPadding(dp(4), 0, 0, 0)
                setOnClickListener { closeTab(tab) }
            }

            addView(dot); addView(name); addView(close)

            setOnClickListener {
                if (activeTab != tab) switchTo(tab)
            }
        }
    }

    private fun switchTo(tab: TabSession) {
        // 隐藏当前
        activeTab?.let {
            it.terminalView.visibility = View.GONE
            // 查找并更新 tab 背景
            updateTabBarItem(it, false)
        }

        activeTab = tab
        tab.terminalView.visibility = View.VISIBLE

        terminalTitle.text = tab.label
        terminalStatus.text = if (tab.session.isRunning()) "运行中" else "已结束"
        stopButton.visibility = if (tab.session.isRunning()) View.VISIBLE else View.GONE

        // 终端模式隐藏 EditText 输入栏，让键盘直连 TerminalView
        // 脚本模式保留输入栏用于发送指令
        if (isTerminalMode) {
            inputArea.visibility = View.GONE
        } else {
            inputArea.visibility = if (tab.session.isRunning()) View.VISIBLE else View.GONE
        }

        updateTabBarItem(tab, true)

        // 切换 tab 后申请焦点+弹出输入法
        tab.terminalView.post {
            tab.terminalView.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            imm?.showSoftInput(tab.terminalView, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun updateTabBarItem(tab: TabSession, isActive: Boolean) {
        val tag = tab.session.hashCode()
        for (i in 0 until tabBar.childCount) {
            val tb = tabBar.getChildAt(i) as? LinearLayout ?: continue
            if (tb.tag != tag) continue
            tb.background = createTabBackground(isActive)
            (tb.getChildAt(1) as? TextView)?.setTextColor(
                if (isActive) 0xFFFFFFFF.toInt() else 0xFFCCCCCC.toInt()
            )
            break
        }
    }

    private fun createTabBackground(isActive: Boolean): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(if (isActive) 0xFF30363D.toInt() else android.graphics.Color.TRANSPARENT)
            if (isActive) setStroke(1, 0xFF484F58.toInt())
        }
    }

    private fun updateTabBarVisibility() {
        tabScrollView.visibility = if (tabs.isNotEmpty()) View.VISIBLE else View.GONE
        tabDivider.visibility = if (tabs.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun closeTab(tab: TabSession) {
        tab.session.finishIfRunning()
        terminalContainer.removeView(tab.terminalView)
        tabs.remove(tab)
        rebuildTabBar()

        if (activeTab == tab) {
            if (tabs.isNotEmpty()) switchTo(tabs.last())
            else { activeTab = null; finish() }
        }
    }

    private fun rebuildTabBar() {
        tabBar.removeAllViews()
        tabs.forEach { addTabToBar(it) }
    }

    // ========== 输入 ==========

    private fun sendInput() {
        val text = terminalInput.text.toString()
        val session = activeTab?.session ?: return

        // 脚本执行完毕后，按回车自动退出终端页
        val tab = activeTab
        if (tab != null && tab.scriptPath != null && !session.isRunning()) {
            finish()
            return
        }

        if (text.isNotEmpty() && session.isRunning()) {
            session.write("$text\n")
            terminalInput.text.clear()
        }
    }

    private fun killActiveSession() {
        activeTab?.let { tab ->
            tab.session.finishIfRunning()
            terminalContainer.removeView(tab.terminalView)
            tabs.remove(tab)
            rebuildTabBar()
            if (tabs.isNotEmpty()) switchTo(tabs.last())
            else finish()
        }
    }

    // ========== 触控优化 ==========
    private var lastScale = 1.0f

    // ========== TerminalViewClient ==========

    private val terminalViewClientImpl = object : TerminalViewClient {
        override fun onSingleTapUp(e: MotionEvent) {
            // 用户点击终端时弹出输入法
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
            // return false to let TerminalView enter text selection mode
            return false
        }
        override fun readControlKey(): Boolean = false
        override fun readAltKey(): Boolean = false
        override fun readShiftKey(): Boolean = false
        override fun readFnKey(): Boolean = false
        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean {
            // 脚本执行完毕后，按任意键自动退出终端页
            val tab = activeTab
            if (tab != null && tab.scriptPath != null && !session.isRunning()) {
                finish()
                return true
            }
            return false
        }
        override fun onEmulatorSet() {}
        override fun onScale(scale: Float): Float {
            if (lastScale == 1.0f || scale.isNaN() || scale.isInfinite()) {
                lastScale = scale
                return scale
            }
            val delta = scale / lastScale
            if (kotlin.math.abs(delta - 1.0f) > 0.02f) {
                mFontSize = (mFontSize * delta).toInt().coerceIn(10, 28)
                applyFontSize()
                saveTerminalPrefs()
            }
            lastScale = scale
            return scale
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
                val tabIdx = tabs.indexOfFirst { it.session === session }
                if (tabIdx < 0) return@post

                val tab = tabs[tabIdx]
                val exitCode = session.getExitStatus()

                // 检查是否需要 fallback: Phase 1 (sh) 执行失败且退出码非0
                if (tab.scriptPath != null && tab.executionPhase == 1 && exitCode != 0) {
                    restartTabWithCd(tabIdx, tab)
                    return@post
                }

                // 正常结束
                if (activeTab?.session === session) {
                    terminalStatus.text = "已结束"
                    stopButton.visibility = View.GONE
                    if (!isTerminalMode) {
                        inputArea.visibility = View.GONE
                    }
                    // 在终端输出中写入退出码提示
                    val exitMsg = "\n—— 进程已退出（退出码: $exitCode）——\n"
                    session.feedText(exitMsg)
                    tab.terminalView.invalidate()
                }
            }
        }

        override fun onTitleChanged(session: TerminalSession) {
            handler.post {
                val title = session.title ?: tabTitle(session)
                terminalTitle.text = title
            }
        }

        override fun onTextChanged(session: TerminalSession) {
            // notify TerminalView to redraw and keep at latest output
            for (tab in tabs) {
                if (tab.session === session) {
                    handler.post {
                        val tv = tab.terminalView
                        tv.scrollToBottom()
                        tv.invalidate()
                    }
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
                val text = clip.getItemAt(0).text?.toString() ?: return
                session.write(text)
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

    private fun tabTitle(session: TerminalSession): String {
        return session.mSessionName ?: "终端"
    }

    // ========== Size 处理 ==========

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
        val tv = tab.terminalView
        val w = tv.width
        val h = tv.height
        if (w > 0 && h > 0) {
            tab.session.updateSize(calculateColumns(w), calculateRows(h), w, h)
            tv.scrollToBottom()
        }
    }

    override fun onDestroy() {
        tabs.forEach { it.session.finishIfRunning() }
        super.onDestroy()
    }

    // ========== 终端 设置 ==========

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

    private fun applyColorScheme() {
        val scheme = COLOR_SCHEMES[mColorSchemeName] ?: COLOR_SCHEMES["dark"]!!
        mFgColor = scheme[0]; mBgColor = scheme[1]
        TerminalColors.COLOR_SCHEME.setDefaultColor(TextStyle.COLOR_INDEX_FOREGROUND, mFgColor)
        TerminalColors.COLOR_SCHEME.setDefaultColor(TextStyle.COLOR_INDEX_BACKGROUND, mBgColor)
        TerminalColors.COLOR_SCHEME.setCursorColorForBackground()
        for (tab in tabs) {
            tab.terminalView.mEmulator?.mColors?.reset()
            tab.terminalView.invalidate()
        }
        terminalContainer.setBackgroundColor(mBgColor)
    }

    private fun applyFontSize() {
        for (tab in tabs) {
            tab.terminalView.setTextSize(mFontSize)
        }
    }

    // ---------- 终端 设置对话框 ----------
    private fun showSettingsDialog() {
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("终端设置")
            .setPositiveButton("关闭", null)
            .create()
        val view = layoutInflater.inflate(R.layout.dialog_terminal_settings, null)

        val seekBar = view.findViewById<SeekBar>(R.id.fontSizeSeek)
        val sizeLabel = view.findViewById<TextView>(R.id.fontSizeLabel)

        seekBar.progress = mFontSize - 10
        sizeLabel.text = "${mFontSize}sp"
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                mFontSize = p + 10
                sizeLabel.text = "${mFontSize}sp"
                applyFontSize()
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {
                saveTerminalPrefs()
            }
        })

        val row1 = view.findViewById<LinearLayout>(R.id.schemeRow1)
        val row2 = view.findViewById<LinearLayout>(R.id.schemeRow2)

        COLOR_SCHEME_NAMES.forEachIndexed { i, name ->
            val label = COLOR_SCHEME_LABELS[name] ?: name
            val scheme = COLOR_SCHEMES[name] ?: return@forEachIndexed
            val bgColor = scheme[1]
            val fgColor = scheme[0]
            val isSelected = name == mColorSchemeName

            val btn = Button(this).apply {
                text = label
                setTextColor(fgColor)
                textSize = 13f
                setPadding(dp(4), dp(8), dp(4), dp(8))

                val bgShape = GradientDrawable().apply {
                    setShape(GradientDrawable.RECTANGLE)
                    setColor(bgColor)
                    if (isSelected) setStroke(dp(2), 0xFFFFFFFF.toInt())
                    cornerRadius = dp(6).toFloat()
                }
                background = bgShape

                val lp = LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                    setMargins(dp(3), dp(3), dp(3), dp(3))
                }
                layoutParams = lp

                setOnClickListener {
                    mColorSchemeName = name
                    applyColorScheme()
                    saveTerminalPrefs()
                    dialog.dismiss()
                }
            }

            if (i < 3 && row1 != null) row1.addView(btn)
            else if (row2 != null) row2.addView(btn)
        }

        dialog.setView(view)
        dialog.show()
    }


    // ========== 终端个性化设置 ==========
    private var mFontSize = 14
    private var mFgColor = 0xFFFFFFFF.toInt()
    private var mBgColor = 0xFF1E1E1E.toInt()
    private var mColorSchemeName = "dark"

    companion object {
        private val COLOR_SCHEMES = mapOf(
            "dark" to intArrayOf(0xFFFFFFFF.toInt(), 0xFF1E1E1E.toInt()),
            "light" to intArrayOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt()),
            "green" to intArrayOf(0xFF00FF00.toInt(), 0xFF000000.toInt()),
            "amber" to intArrayOf(0xFFFFB000.toInt(), 0xFF000000.toInt()),
            "solarized" to intArrayOf(0xFF657B83.toInt(), 0xFF002B36.toInt()),
            "monokai" to intArrayOf(0xFFF8F8F2.toInt(), 0xFF272822.toInt())
        )
        private val COLOR_SCHEME_NAMES = listOf("dark", "light", "green", "amber", "solarized", "monokai")
        private val COLOR_SCHEME_LABELS = mapOf(
            "dark" to "暗色", "light" to "亮色", "green" to "绿莹",
            "amber" to "琥珀", "solarized" to "日光", "monokai" to "Monokai"
        )
    }

    // ========== Utils ==========
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun dpf(v: Int) = v * resources.displayMetrics.density
}
