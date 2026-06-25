package com.Rouxin.ShellTool

import android.Manifest
import android.content.SharedPreferences
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.*
import java.io.File
import android.widget.CheckBox
import android.widget.LinearLayout

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_STORAGE = 100
    }

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: FileListAdapter
    private lateinit var pathText: TextView
    private lateinit var emptyView: TextView
    private val files = mutableListOf<FileItem>()
    private lateinit var prefs: android.content.SharedPreferences

    private var hasRoot = false
    private var workDir = "/data/RouXin"
    private var currentPath: String = workDir

    // 目录缓存
    private val dirCache = object : LinkedHashMap<String, List<FileItem>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<FileItem>>): Boolean {
            return size > 64
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        applyTheme()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 检测 Root 权限，没有也不崩溃
        hasRoot = checkRootAccess()
        initWorkDir()
        initViews()
        RxinSandbox.createSandbox()
    }

    private fun checkRootAccess(): Boolean {
        return try {
            val proc = Runtime.getRuntime().exec("su -c echo ok")
            val output = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor()
            output == "ok" && proc.exitValue() == 0
        } catch (e: Exception) {
            false
        }
    }

    /** 创建工作目录：有 root → /data/RouXin，无 root → 内部存储 */
    private fun initWorkDir() {
        if (hasRoot) {
            try {
                Runtime.getRuntime().exec(arrayOf("su", "-c", "mkdir -p /data/RouXin")).waitFor()
                workDir = "/data/RouXin"
            } catch (_: Exception) {
                hasRoot = false
            }
        }
        if (!hasRoot) {
            workDir = createWorkDirOnStorage()
        }
        currentPath = workDir
    }

    /** 在内部存储创建 RouXin 目录，失败则降级到应用私有目录 */
    private fun createWorkDirOnStorage(): String {
        // 方式一：File.mkdirs() — 有 MANAGE_EXTERNAL_STORAGE 时有效
        val dir = File("/storage/emulated/0/RouXin")
        if (dir.mkdirs() || dir.exists()) return dir.absolutePath

        // 方式二：shell mkdir — 部分设备 shell 权限更高
        try {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", "mkdir -p /storage/emulated/0/RouXin"))
            p.waitFor()
            if (dir.exists()) return dir.absolutePath
        } catch (_: Exception) {}

        // 方式三：用 Environment API 获取基路径
        try {
            val base = Environment.getExternalStorageDirectory()  // /storage/emulated/0
            val alt = File(base, "RouXin")
            if (alt.mkdirs() || alt.exists()) return alt.absolutePath
        } catch (_: Exception) {}

        // 都没权限 → 引导用户授权（Android 11+ 需要 MANAGE_EXTERNAL_STORAGE）
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            requestStoragePermission()
        } else if (Build.VERSION.SDK_INT < 30) {
            requestStoragePermission()
        }

        // 最终降级：应用私有目录
        return File(getExternalFilesDir(null), "RouXin").also { it.mkdirs() }.absolutePath
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = android.net.Uri.parse("package:$packageName")
                })
            } catch (_: Exception) {}
        } else {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQUEST_STORAGE)
        }
    }

    // 权限请求回调
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_STORAGE && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            val dir = File("/storage/emulated/0/RouXin")
            if (dir.mkdirs() || dir.exists()) {
                workDir = dir.absolutePath
                currentPath = workDir
                navigateTo(currentPath, forceRefresh = true)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 从权限设置页返回后，重试创建工作目录
        if (!hasRoot && workDir.startsWith("/storage/emulated/0/Android")) {
            val restored = createWorkDirOnStorage()
            if (restored.startsWith("/storage/emulated/0/RouXin")) {
                workDir = restored
                currentPath = workDir
            }
        }
        navigateTo(currentPath)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (hasRoot) RootShell.destroy()
    }

    private fun applyTheme() {
        val isDark = prefs.getBoolean("dark_theme", true)
        AppCompatDelegate.setDefaultNightMode(
            if (isDark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
    }

    private fun initViews() {
        recyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = FileListAdapter(files,
            onFileClick = { file -> onFileClicked(file) },
            onFileLongClick = { file -> onFileLongClicked(file) }
        )
        recyclerView.adapter = adapter
        pathText = findViewById(R.id.pathText)
        emptyView = findViewById(R.id.emptyView)

        val loadingText = findViewById<TextView>(R.id.loadingText)
        val customText = prefs.getString("loading_text", "RouXin leaves behind endless regrets")
        loadingText.text = customText
        loadingText.isSelected = true
        loadingText.setOnClickListener { showLoadingTextDialog(loadingText) }

        findViewById<ImageButton>(R.id.upBtn).setOnClickListener { navigateUp() }
        findViewById<ImageButton>(R.id.refreshBtn).setOnClickListener { navigateTo(currentPath, forceRefresh = true) }

        findViewById<MaterialButton>(R.id.terminalBtn).setOnClickListener { openTerminal() }

        val themeBtn = findViewById<MaterialButton>(R.id.themeBtn)
        themeBtn.text = if (prefs.getBoolean("dark_theme", true)) "浅色" else "深色"
        themeBtn.setOnClickListener {
            prefs.edit().putBoolean("dark_theme", !prefs.getBoolean("dark_theme", true)).apply()
            recreate()
        }

        pathText.setOnClickListener { showPathDialog() }

        findViewById<MaterialButton>(R.id.navHome).setOnClickListener { navigateTo(workDir) }
        findViewById<MaterialButton>(R.id.navSdcard).setOnClickListener { navigateTo("/storage/emulated/0") }
        findViewById<MaterialButton>(R.id.navRoot).setOnClickListener { navigateTo("/") }
    }

    // ========== 文件浏览 ==========

    private fun navigateTo(path: String, forceRefresh: Boolean = false) {
        currentPath = path
        pathText.text = path
        files.clear()
        adapter.notifyDataSetChanged()
        emptyView.visibility = View.GONE
        recyclerView.visibility = View.VISIBLE

        if (!forceRefresh) {
            dirCache[path]?.let { cached ->
                files.clear(); files.addAll(cached); adapter.notifyDataSetChanged()
                emptyView.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
                recyclerView.visibility = if (files.isEmpty()) View.GONE else View.VISIBLE
                recyclerView.scrollToPosition(0)
                return
            }
        }

        CoroutineScope(Dispatchers.IO).launch {
            val items = if (hasRoot) listDirWithRoot(path) else listDirWithJava(path)
            dirCache[path] = items.toList()
            withContext(Dispatchers.Main) {
                files.clear(); files.addAll(items); adapter.notifyDataSetChanged()
                emptyView.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
                recyclerView.visibility = if (files.isEmpty()) View.GONE else View.VISIBLE
                recyclerView.scrollToPosition(0)
            }
        }
    }

    /** root 模式：ls -la 获取文件列表 */
    private fun listDirWithRoot(path: String): List<FileItem> {
        val escapedPath = path.replace("'", "'\\''")
        val output = RootShell.exec("ls -la '$escapedPath'")
        val items = mutableListOf<FileItem>()
        File(path).parent?.let { items.add(FileItem("..", it, true, false, 0, 0)) }
        for (line in output.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("total ") || !trimmed.matches(Regex("[dl-].*"))) continue
            val tokens = trimmed.split(Regex("\\s+"), 8)
            if (tokens.size < 8) continue
            val perms = tokens[0]
            val name = tokens[tokens.size - 1].substringBefore(" ->")
            if (name == "." || name == "..") continue
            val isDir = perms[0] == 'd' || perms[0] == 'l'
            val size = if (!isDir) tokens[4].toLongOrNull() ?: 0 else 0
            val fullPath = if (path.endsWith("/")) "$path$name" else "$path/$name"
            items.add(FileItem(name, fullPath, isDir, !isDir && isScriptName(name), size, 0))
        }
        items.sortWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        return items
    }

    /** 无 root 模式：Java File API 获取文件列表 */
    private fun listDirWithJava(path: String): List<FileItem> {
        val items = mutableListOf<FileItem>()
        val dir = File(path)
        dir.parent?.let { items.add(FileItem("..", it, true, false, 0, 0)) }
        val list = dir.listFiles() ?: return items
        for (f in list) {
            if (f.name == "." || f.name == "..") continue
            val isDir = f.isDirectory
            val size = if (isDir) 0 else f.length()
            items.add(FileItem(f.name, f.absolutePath, isDir, !isDir && isScriptName(f.name), size, f.lastModified()))
        }
        items.sortWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        return items
    }

    private fun isScriptName(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".sh") || n.endsWith(".bash") || n.endsWith(".py") ||
               n.endsWith(".rb") || n.endsWith(".pl") || n.endsWith(".lua")
    }

    private fun navigateUp() { File(currentPath).parent?.let { navigateTo(it) } }

    private fun onFileClicked(file: FileItem) {
        when {
            file.isDirectory -> navigateTo(file.path, forceRefresh = true)
            file.isScript -> showExecuteDialog(file)
            else -> showFileInfo(file)
        }
    }

    private fun onFileLongClicked(file: FileItem) {
        val options = mutableListOf<String>()
        if (file.isScript) {
            options.add("立即执行")
            options.add("添加到任务队列")
        }
        if (file.isDirectory) options.add("在此打开终端")
        options.add("复制路径")
        options.add("查看属性")

        MaterialAlertDialogBuilder(this)
            .setTitle(file.name)
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    "立即执行" -> showExecuteDialog(file)
                    "在此打开终端" -> openTerminalInDir(file.path)
                    "复制路径" -> copyToClipboard(file.path)
                    "查看属性" -> showFileInfo(file)
                }
            }
            .show()
    }

    private fun showExecuteDialog(file: FileItem) {
        val prefs: SharedPreferences = getSharedPreferences("shell_prefs", 0)
        val savedUseRoot = prefs.getBoolean("use_root", false)

        val msgView = TextView(this).apply {
            text = "${file.name}\n${file.path}"
            textSize = 14f
            setPadding(48, 28, 48, 0)
            setTextColor(0xFF666666.toInt())
        }
        val rootCheck = CheckBox(this).apply {
            text = "使用 root 权限执行"
            isChecked = savedUseRoot
            setPadding(48, 8, 48, 24)
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(msgView)
            addView(rootCheck)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("执行脚本")
            .setView(layout)
            .setPositiveButton("执行") { _, _ ->
                executeScript(file, rootCheck.isChecked)
                prefs.edit().putBoolean("use_root", rootCheck.isChecked).apply()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 执行脚本：直接跳转到 TerminalActivity，由 TerminalActivity 执行 */
    private fun executeScript(file: FileItem, useRoot: Boolean = false) {
        startActivity(Intent(this, TerminalActivity::class.java).apply {
            putExtra("script_path", file.path)
            putExtra("script_name", file.name)
            putExtra("terminal_mode", false)
            putExtra("use_root", useRoot)
        })
    }

    private fun showFileInfo(file: FileItem) {
        val f = File(file.path)
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        val info = buildString {
            append("名称: ${file.name}\n")
            append("路径: ${file.path}\n")
            append("类型: ${if (file.isDirectory) "文件夹" else "文件"}\n")
            if (!file.isDirectory) append("大小: ${formatSize(file.size)}\n")
            if (file.lastModified > 0) append("修改: ${sdf.format(java.util.Date(file.lastModified))}\n")
            append("读: ${if (f.canRead()) "✓" else "✗"}  写: ${if (f.canWrite()) "✓" else "✗"}  执行: ${if (f.canExecute()) "✓" else "✗"}")
        }
        MaterialAlertDialogBuilder(this).setTitle(file.name).setMessage(info).show()
    }

    private fun showPathDialog() {
        val input = EditText(this).apply { setText(currentPath); setSingleLine(); setPadding(48, 32, 48, 32) }
        MaterialAlertDialogBuilder(this)
            .setTitle("跳转到目录")
            .setView(input)
            .setPositiveButton("前往") { _, _ -> input.text.toString().trim().let { if (it.isNotEmpty()) navigateTo(it) } }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showLoadingTextDialog(textView: TextView) {
        val input = EditText(this).apply { setText(textView.text); setSingleLine(); setPadding(48, 32, 48, 32) }
        MaterialAlertDialogBuilder(this)
            .setTitle("自定义加载文字")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val newText = input.text.toString().trim()
                if (newText.isNotEmpty()) {
                    prefs.edit().putString("loading_text", newText).apply()
                    textView.text = newText
                }
            }
            .setNegativeButton("取消", null)
            .setNeutralButton("恢复默认") { _, _ ->
                prefs.edit().remove("loading_text").apply()
                textView.text = "RouXin leaves behind endless regrets"
            }
            .show()
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("path", text))
        Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
    }

    private fun formatSize(size: Long) = when {
        size < 1024 -> "$size B"
        size < 1024 * 1024 -> "${size / 1024} KB"
        else -> String.format("%.1f MB", size / (1024.0 * 1024.0))
    }

    // ========== 终端 ==========

    private fun openTerminal() {
        startActivity(Intent(this, TerminalActivity::class.java).apply {
            putExtra("terminal_mode", true)
            putExtra("work_dir", currentPath)
        })
    }

    private fun openTerminalInDir(path: String) {
        startActivity(Intent(this, TerminalActivity::class.java).apply {
            putExtra("terminal_mode", true)
            putExtra("work_dir", path)
        })
    }
}
