package com.xfiles.manager

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val blue = Color.rgb(35, 111, 245)
    private val ink = Color.rgb(25, 31, 43)
    private val muted = Color.rgb(119, 130, 148)
    private val page = Color.rgb(245, 247, 251)
    private val prefs by lazy { getSharedPreferences("xfiles_local", MODE_PRIVATE) }
    private lateinit var root: LinearLayout
    private lateinit var body: LinearLayout
    private var currentTab = "Home"
    private var categoryFilter: String? = null
    private var selectedRoot: DocumentFile? = null
    private var currentFolder: DocumentFile? = null
    private val path = mutableListOf<DocumentFile>()
    private var items = listOf<DocumentFile>()
    private var imageCount = 0
    private var videoCount = 0
    private var audioCount = 0
    private var docCount = 0
    private var scanRunning = false
    private var scanStatusFolder: Uri? = null

    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        scanMedia()
    }
    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (_: Exception) {}
            selectedRoot = DocumentFile.fromTreeUri(this, uri)
            currentFolder = selectedRoot
            path.clear(); selectedRoot?.let { path.add(it) }
            prefs.edit().putString("root_uri", uri.toString()).apply()
            render()
        }
    }
    private val statusPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            scanStatusFolder = uri
            prefs.edit().putString("wa_status_uri", uri.toString()).apply()
            Toast.makeText(this, "WhatsApp Status folder linked", Toast.LENGTH_SHORT).show()
            render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = page
        window.navigationBarColor = Color.WHITE
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        prefs.getString("root_uri", null)?.let { raw ->
            try { selectedRoot = DocumentFile.fromTreeUri(this, Uri.parse(raw)); currentFolder = selectedRoot; selectedRoot?.let { path.add(it) } } catch (_: Exception) {}
        }
        prefs.getString("wa_status_uri", null)?.let { try { scanStatusFolder = Uri.parse(it) } catch (_: Exception) {} }
        imageCount = prefs.getInt("images", 0); videoCount = prefs.getInt("videos", 0); audioCount = prefs.getInt("audio", 0); docCount = prefs.getInt("docs", 0)
        buildShell()
        render()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun buildShell() {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(page) }
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(18), dp(20), dp(12)) }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(body) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(dp(8), dp(8), dp(8), dp(8)); background = rounded(Color.WHITE, dp(22)); elevation = dp(8).toFloat() }
        listOf("Home", "Files", "Favorites", "Settings").forEach { tab ->
            val b = TextView(this).apply { text = tab; gravity = Gravity.CENTER; textSize = 12f; typeface = Typeface.DEFAULT_BOLD; setTextColor(if (tab == currentTab) blue else muted); setPadding(dp(8), dp(12), dp(8), dp(12)); setOnClickListener { currentTab = tab; render() } }
            nav.addView(b, LinearLayout.LayoutParams(0, -2, 1f))
        }
        val navWrap = LinearLayout(this).apply { setPadding(dp(16), dp(4), dp(16), dp(12)); addView(nav) }
        root.addView(navWrap)
        setContentView(root)
    }

    private fun render() {
        body.removeAllViews()
        when (currentTab) { "Home" -> renderHome(); "Files" -> renderFiles(); "Favorites" -> renderFavorites(); else -> renderSettings() }
        // Rebuild bottom navigation so the active tab stays highlighted.
        val old = root.getChildAt(1) as? LinearLayout
        if (old != null) {
            val nav = old.getChildAt(0) as? LinearLayout
            if (nav != null) for (i in 0 until nav.childCount) (nav.getChildAt(i) as TextView).setTextColor(if ((nav.getChildAt(i) as TextView).text.toString() == currentTab) blue else muted)
        }
    }

    private fun renderHome() {
        rowHeader("X Files", "Your space. Your files.")
        val scan = card(Color.rgb(19, 31, 56))
        scan.addView(text("✦  SMART MEDIA SCANNER", 11f, Color.rgb(160, 190, 255), true))
        scan.addView(text("Find and organize your media", 19f, Color.WHITE, true))
        scan.addView(text("Offline scan · No root required", 12f, Color.rgb(196, 207, 226)))
        val scanRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; orientation = LinearLayout.HORIZONTAL }
        val status = text(if (scanRunning) "Scanning…" else "${imageCount + videoCount + audioCount + docCount} items indexed", 12f, Color.WHITE, true)
        scanRow.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
        scanRow.addView(actionButton(if (scanRunning) "Scanning" else "Scan now", blue) { requestScan() })
        scan.addView(scanRow)
        scan.alpha = 0.98f
        scan.setOnClickListener { if (!scanRunning) requestScan() }
        body.addView(scan)

        sectionTitle("CATEGORIES", "Browse all device media")
        val categories = listOf(Triple("▧", "Photos", imageCount), Triple("▶", "Videos", videoCount), Triple("♫", "Audio", audioCount), Triple("▤", "Documents", docCount))
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (pair in categories.chunked(2)) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEach { (ico, name, count) ->
                val c = card(Color.WHITE)
                c.addView(text(ico, 24f, blue, true)); c.addView(text(name, 15f, ink, true)); c.addView(text("$count items", 12f, muted))
                c.setOnClickListener { currentTab = "Files"; categoryFilter = name; render() }
                r.addView(c, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(8) })
            }
            if (pair.size == 1) r.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            grid.addView(r)
        }
        body.addView(grid)

        sectionTitle("WHATSAPP", "Status media on this device")
        val wa = card(Color.WHITE)
        wa.addView(text("◉  WhatsApp Status", 17f, ink, true))
        wa.addView(text(if (scanStatusFolder == null) "Link the WhatsApp .Statuses folder to view accessible status photos and videos." else "Status folder linked. Open it to browse available status media.", 12f, muted))
        wa.addView(actionButton(if (scanStatusFolder == null) "Choose status folder" else "Open status folder", Color.rgb(36, 164, 100)) {
            if (scanStatusFolder == null) statusPicker.launch(null) else openStatusFolder()
        })
        body.addView(wa)
        sectionTitle("QUICK ACCESS", "Your frequently used spaces")
        val quick = card(Color.WHITE)
        quick.addView(text("★  Favorites", 15f, ink, true))
        quick.addView(text("Keep important files close at hand.", 12f, muted))
        quick.addView(actionButton("View favorites", blue) { currentTab = "Favorites"; render() })
        body.addView(quick)
    }

    private fun renderFiles() {
        val category = categoryFilter
        rowHeader("Files", "Browse folders and manage files")
        val choose = card(Color.WHITE)
        choose.addView(text(currentFolder?.name ?: "No storage folder selected", 14f, ink, true))
        choose.addView(actionButton("Choose storage folder", blue) { folderPicker.launch(null) })
        body.addView(choose)
        val search = EditText(this).apply { hint = "Search current folder"; textSize = 14f; singleLine = true; setPadding(dp(14), dp(10), dp(14), dp(10)); background = rounded(Color.WHITE, dp(14)) }
        body.addView(search, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(10) })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(list)
        fun load(filter: String = "") {
            list.removeAllViews()
            val folder = currentFolder
            if (folder == null) { list.addView(text("Choose a folder to start browsing.", 13f, muted)); return }
            if (path.size > 1) list.addView(actionButton("‹  Back to parent", blue) { path.removeAt(path.lastIndex); currentFolder = path.last(); render() })
            items = try { folder.listFiles().sortedWith(compareBy<DocumentFile> { !it.isDirectory }.thenBy { it.name?.lowercase(Locale.ROOT) ?: "" }) } catch (_: Exception) { emptyList() }
            val found = items.filter { it.name.orEmpty().contains(filter, true) && matchesCategory(it, category) }
            if (found.isEmpty()) list.addView(text("No matching files or folders.", 13f, muted))
            found.forEach { f ->
                val r = card(Color.WHITE); r.orientation = LinearLayout.HORIZONTAL; r.gravity = Gravity.CENTER_VERTICAL
                r.addView(text(if (f.isDirectory) "▰" else "▧", 22f, blue, true), LinearLayout.LayoutParams(dp(36), -2))
                val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                info.addView(text(f.name ?: "Untitled", 14f, ink, true)); info.addView(text(if (f.isDirectory) "Folder" else "${sizeLabel(f.length())} · ${f.type ?: "File"}", 11f, muted))
                r.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
                r.addView(actionButton("•••", muted) { itemMenu(f) })
                r.setOnClickListener { if (f.isDirectory) { currentFolder = f; path.add(f); render() } else openFile(f) }
                r.setOnLongClickListener { itemMenu(f); true }
                list.addView(r)
            }
        }
        search.addTextChangedListener(SimpleWatcher { load(it) })
        load()
    }

    private fun renderFavorites() {
        rowHeader("Favorites", "Your saved locations")
        val saved = prefs.getStringSet("favorites", emptySet()) ?: emptySet()
        if (saved.isEmpty()) body.addView(card(Color.WHITE).apply { addView(text("No favorites yet", 16f, ink, true)); addView(text("Open Files, long-press an item and add it to favorites.", 12f, muted)) })
        saved.forEach { raw ->
            val f = try { DocumentFile.fromSingleUri(this, Uri.parse(raw)) } catch (_: Exception) { null }
            body.addView(card(Color.WHITE).apply { addView(text(f?.name ?: raw.substringAfterLast('/'), 14f, ink, true)); addView(actionButton("Open", blue) { f?.let { openFile(it) } }) })
        }
    }

    private fun renderSettings() {
        rowHeader("Settings", "Personalize your X Files")
        val dark = prefs.getBoolean("dark_mode", false)
        body.addView(card(Color.WHITE).apply {
            addView(text("Appearance", 16f, ink, true)); addView(text(if (dark) "Dark mode preference saved" else "Light mode preference saved", 12f, muted))
            addView(actionButton(if (dark) "Set Light Mode preference" else "Set Dark Mode preference", blue) { prefs.edit().putBoolean("dark_mode", !dark).apply(); Toast.makeText(this@MainActivity, "Theme preference saved locally", Toast.LENGTH_SHORT).show(); render() })
        })
        body.addView(card(Color.WHITE).apply {
            addView(text("Contact", 16f, ink, true))
            addView(actionButton("Telegram  @blvcknetwork", blue) { openUrl("https://t.me/blvcknetwork") })
            addView(actionButton("Instagram  @tonmoykaze", Color.rgb(190, 70, 130)) { openUrl("https://instagram.com/tonmoykaze") })
        })
        body.addView(card(Color.WHITE).apply { addView(text("Offline-first", 14f, ink, true)); addView(text("File browsing and media scanning run on-device. Contact links require an internet connection.", 12f, muted)) })
    }

    private fun requestScan() {
        val needed = if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO) else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        val missing = needed.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) permissionRequest.launch(missing.toTypedArray()) else scanMedia()
    }

    private fun scanMedia() {
        if (scanRunning) return
        scanRunning = true; render()
        Thread {
            var images = 0; var videos = 0; var audio = 0; var docs = 0
            fun count(uri: Uri, selection: String? = null, args: Array<String>? = null): Int {
                var c: Cursor? = null
                return try { c = contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), selection, args, null); c?.count ?: 0 } catch (_: Exception) { 0 } finally { c?.close() }
            }
            images = count(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            videos = count(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            audio = count(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
            val filesUri = MediaStore.Files.getContentUri("external")
            docs = count(filesUri, "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ?", arrayOf("0"))
            runOnUiThread {
                imageCount = images; videoCount = videos; audioCount = audio; docCount = docs
                scanRunning = false
                prefs.edit().putLong("last_scan", System.currentTimeMillis()).putInt("images", images).putInt("videos", videos).putInt("audio", audio).putInt("docs", docs).apply()
                Toast.makeText(this, "Scan complete", Toast.LENGTH_SHORT).show(); render()
            }
        }.start()
    }

    private fun openStatusFolder() {
        val f = scanStatusFolder?.let { DocumentFile.fromTreeUri(this, it) }
        if (f == null) { statusPicker.launch(null); return }
        val entries = try { f.listFiles().filter { it.isFile } } catch (_: Exception) { emptyList() }
        val names = entries.map { it.name ?: "Status" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("WhatsApp Status media").setItems(names) { _, which -> openFile(entries[which]) }.setNegativeButton("Close", null).show()
    }

    private fun itemMenu(file: DocumentFile) {
        val opts = arrayOf("Open", "Add to Favorites", "Rename", "Delete", "Share")
        AlertDialog.Builder(this).setTitle(file.name ?: "Item").setItems(opts) { _, i -> when (opts[i]) {
            "Open" -> if (file.isDirectory) { currentFolder = file; path.add(file); render() } else openFile(file)
            "Add to Favorites" -> { val set = (prefs.getStringSet("favorites", emptySet()) ?: emptySet()).toMutableSet(); set.add(file.uri.toString()); prefs.edit().putStringSet("favorites", set).apply(); Toast.makeText(this, "Added to favorites", Toast.LENGTH_SHORT).show() }
            "Rename" -> rename(file)
            "Delete" -> AlertDialog.Builder(this).setMessage("Delete ${file.name}?").setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ -> val ok = file.delete(); Toast.makeText(this, if (ok) "Deleted" else "Delete failed", Toast.LENGTH_SHORT).show(); render() }.show()
            "Share" -> share(file)
        } }.show()
    }
    private fun rename(file: DocumentFile) { val input = EditText(this).apply { setText(file.name ?: "") }; AlertDialog.Builder(this).setTitle("Rename").setView(input).setNegativeButton("Cancel", null).setPositiveButton("Save") { _, _ -> val n = input.text.toString().trim(); if (n.isNotEmpty()) { Toast.makeText(this, if (file.renameTo(n)) "Renamed" else "Rename failed", Toast.LENGTH_SHORT).show(); render() } }.show() }
    private fun openFile(file: DocumentFile) { try { startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(file.uri, file.type ?: "*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Open with")) } catch (_: Exception) { Toast.makeText(this, "No compatible app found", Toast.LENGTH_SHORT).show() } }
    private fun share(file: DocumentFile) { try { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = file.type ?: "*/*"; putExtra(Intent.EXTRA_STREAM, file.uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }, "Share file")) } catch (_: Exception) { Toast.makeText(this, "Could not share file", Toast.LENGTH_SHORT).show() } }
    private fun matchesCategory(f: DocumentFile, category: String?): Boolean { if (category == null || f.isDirectory) return true; val n = f.name.orEmpty().lowercase(); return when (category) { "Photos" -> n.matches(Regex(".*\\.(jpg|jpeg|png|gif|webp|heic)$")); "Videos" -> n.matches(Regex(".*\\.(mp4|mkv|mov|3gp|webm)$")); "Audio" -> n.matches(Regex(".*\\.(mp3|wav|m4a|ogg|flac)$")); "Documents" -> n.matches(Regex(".*\\.(pdf|doc|docx|txt|ppt|pptx|xls|xlsx)$")); else -> true } }
    private fun rowHeader(title: String, subtitle: String) { body.addView(text(title, 28f, ink, true)); body.addView(text(subtitle, 13f, muted).apply { setPadding(0, dp(3), 0, dp(18)) }) }
    private fun sectionTitle(title: String, subtitle: String) { body.addView(text(title, 11f, muted, true).apply { letterSpacing = .08f; setPadding(dp(2), dp(14), 0, dp(3)) }); body.addView(text(subtitle, 13f, muted).apply { setPadding(dp(2), 0, 0, dp(8)) }) }
    private fun card(color: Int): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(15), dp(14), dp(15), dp(14)); background = rounded(color, dp(18)); elevation = dp(1).toFloat(); layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) } }
    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply { text = s; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(2), 0, dp(4)) }
    private fun actionButton(s: String, color: Int, action: () -> Unit) = Button(this).apply { text = s; textSize = 12f; isAllCaps = false; setTextColor(Color.WHITE); background = rounded(color, dp(12)); setPadding(dp(12), 0, dp(12), 0); setOnClickListener { action() } }
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = radius.toFloat() }
    private fun sizeLabel(bytes: Long) = when { bytes < 1024 -> "$bytes B"; bytes < 1048576 -> "%.1f KB".format(bytes / 1024.0); else -> "%.1f MB".format(bytes / 1048576.0) }
    private fun openUrl(url: String) { try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) { Toast.makeText(this, "No app available", Toast.LENGTH_SHORT).show() } }
    private class SimpleWatcher(val changed: (String) -> Unit) : android.text.TextWatcher { override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}; override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { changed(s?.toString().orEmpty()) }; override fun afterTextChanged(s: android.text.Editable?) {} }
}
