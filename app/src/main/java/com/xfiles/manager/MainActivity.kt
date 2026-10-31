package com.xfiles.manager

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val blue = Color.rgb(0, 122, 255)
    private val ink = Color.rgb(24, 29, 39)
    private val muted = Color.rgb(125, 135, 150)
    private val page = Color.rgb(245, 247, 251)
    private val line = Color.rgb(230, 234, 241)

    private lateinit var root: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var pathLabel: TextView
    private lateinit var search: EditText
    private var selectedRoot: DocumentFile? = null
    private var currentFolder: DocumentFile? = null
    private var currentPath = mutableListOf<DocumentFile>()
    private var allItems = listOf<DocumentFile>()

    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) { }
            selectedRoot = DocumentFile.fromTreeUri(this, uri)
            currentFolder = selectedRoot
            currentPath.clear()
            selectedRoot?.let { currentPath.add(it) }
            renderFiles()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = page
        window.navigationBarColor = Color.WHITE
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        buildScreen()
        if (savedInstanceState == null) showWelcome() else renderFiles()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun buildScreen() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(page)
            setPadding(dp(20), dp(18), dp(20), dp(12))
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val heading = TextView(this).apply {
            text = "X Files"
            textSize = 30f
            setTextColor(ink)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        top.addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
        val addButton = pillButton("+", blue, Color.WHITE) { showCreateFolder() }
        top.addView(addButton, LinearLayout.LayoutParams(dp(46), dp(42)))
        root.addView(top)

        val subtitle = TextView(this).apply {
            text = "Your files, beautifully organized."
            textSize = 14f
            setTextColor(muted)
            setPadding(0, dp(3), 0, dp(16))
        }
        root.addView(subtitle)

        search = EditText(this).apply {
            hint = "Search files and folders"
            textSize = 15f
            isSingleLine = true
            setTextColor(ink)
            setHintTextColor(muted)
            setPadding(dp(15), 0, dp(15), 0)
            background = rounded(Color.WHITE, dp(14))
        }
        root.addView(search, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(12) })
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                renderList(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        val pathCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(Color.WHITE, dp(14))
        }
        pathLabel = TextView(this).apply {
            text = "Storage not selected"
            textSize = 13f
            setTextColor(muted)
        }
        pathCard.addView(pathLabel)
        val choose = TextView(this).apply {
            text = "Choose storage folder  ›"
            textSize = 14f
            setTextColor(blue)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(8), 0, 0)
            setOnClickListener { folderPicker.launch(null) }
        }
        pathCard.addView(choose)
        root.addView(pathCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })

        val section = TextView(this).apply {
            text = "BROWSE"
            textSize = 12f
            letterSpacing = 0.08f
            setTextColor(muted)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(2), dp(2), 0, dp(8))
        }
        root.addView(section)

        val scroll = ScrollView(this)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val footer = TextView(this).apply {
            text = "X FILES  •  PRIVATE ON-DEVICE FILE BROWSER"
            textSize = 10f
            letterSpacing = 0.06f
            gravity = Gravity.CENTER
            setTextColor(muted)
            setPadding(0, dp(10), 0, 0)
        }
        root.addView(footer)
        setContentView(root)
    }

    private fun showWelcome() {
        content.removeAllViews()
        val card = card()
        card.addView(label("Welcome to X Files", 21f, ink, true))
        card.addView(label("Select a folder to browse, organize, rename and delete your files. Android will ask you to grant folder access.", 14f, muted))
        val button = pillButton("Choose folder", blue, Color.WHITE) { folderPicker.launch(null) }
        card.addView(button, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(14) })
        content.addView(card)
    }

    private fun renderFiles() {
        val folder = currentFolder
        if (folder == null || !folder.exists()) {
            showWelcome()
            return
        }
        pathLabel.text = currentPath.joinToString("  /  ") { it.name ?: "Storage" }
        allItems = try {
            folder.listFiles().sortedWith(compareBy<DocumentFile> { !it.isDirectory }.thenBy { it.name?.lowercase(Locale.ROOT) ?: "" })
        } catch (_: Exception) { emptyList() }
        renderList(search.text?.toString().orEmpty())
    }

    private fun renderList(filter: String) {
        if (!::content.isInitialized) return
        content.removeAllViews()
        val folder = currentFolder
        if (folder == null) {
            showWelcome()
            return
        }

        if (currentPath.size > 1) {
            val back = card()
            val backRow = TextView(this).apply {
                text = "‹   Back to parent folder"
                textSize = 15f
                setTextColor(blue)
                setPadding(dp(4), dp(5), dp(4), dp(5))
                setOnClickListener { goUp() }
            }
            back.addView(backRow)
            content.addView(back)
        }

        val filtered = allItems.filter { it.name.orEmpty().contains(filter, ignoreCase = true) }
        if (filtered.isEmpty()) {
            val empty = card()
            empty.addView(label(if (filter.isBlank()) "This folder is empty" else "No matching files", 16f, ink, true))
            empty.addView(label("Use + to create a folder, or choose another storage folder.", 13f, muted))
            content.addView(empty)
            return
        }

        filtered.forEach { file ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(13), dp(12), dp(10), dp(12))
                background = rounded(Color.WHITE, dp(14))
            }
            val icon = TextView(this).apply {
                text = if (file.isDirectory) "📁" else fileIcon(file.name.orEmpty())
                textSize = 23f
                gravity = Gravity.CENTER
            }
            row.addView(icon, LinearLayout.LayoutParams(dp(42), dp(42)))
            val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            info.addView(label(file.name ?: "Untitled", 15f, ink, true))
            val detail = if (file.isDirectory) "Folder" else "${formatSize(file.length())}  •  ${file.typeLabel()}"
            info.addView(label(detail, 12f, muted))
            row.addView(info, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
            val more = TextView(this).apply {
                text = "•••"
                textSize = 17f
                setTextColor(muted)
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(8), dp(5), dp(8))
                setOnClickListener { showItemMenu(file) }
            }
            row.addView(more)
            row.setOnClickListener {
                if (file.isDirectory) {
                    currentFolder = file
                    currentPath.add(file)
                    search.setText("")
                    renderFiles()
                } else openFile(file)
            }
            row.setOnLongClickListener {
                showItemMenu(file)
                true
            }
            content.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
    }

    private fun goUp() {
        if (currentPath.size > 1) {
            currentPath.removeAt(currentPath.lastIndex)
            currentFolder = currentPath.last()
            renderFiles()
        }
    }

    private fun showCreateFolder() {
        if (currentFolder == null) {
            Toast.makeText(this, "Pehle storage folder choose karo", Toast.LENGTH_SHORT).show()
            folderPicker.launch(null)
            return
        }
        val input = EditText(this).apply { hint = "Folder name" }
        AlertDialog.Builder(this)
            .setTitle("New folder")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    val created = currentFolder?.createDirectory(name)
                    Toast.makeText(this, if (created != null) "Folder created" else "Could not create folder", Toast.LENGTH_SHORT).show()
                    renderFiles()
                }
            }.show()
    }

    private fun showItemMenu(file: DocumentFile) {
        val actions = if (file.isDirectory) arrayOf("Open", "Rename", "Delete") else arrayOf("Open", "Rename", "Delete", "Share")
        AlertDialog.Builder(this)
            .setTitle(file.name ?: "File")
            .setItems(actions) { _, which ->
                when (actions[which]) {
                    "Open" -> if (file.isDirectory) {
                        currentFolder = file
                        currentPath.add(file)
                        renderFiles()
                    } else openFile(file)
                    "Rename" -> renameFile(file)
                    "Delete" -> confirmDelete(file)
                    "Share" -> shareFile(file)
                }
            }.show()
    }

    private fun renameFile(file: DocumentFile) {
        val input = EditText(this).apply { setText(file.name ?: "") }
        AlertDialog.Builder(this).setTitle("Rename").setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    val ok = file.renameTo(newName)
                    Toast.makeText(this, if (ok) "Renamed" else "Rename failed", Toast.LENGTH_SHORT).show()
                    renderFiles()
                }
            }.show()
    }

    private fun confirmDelete(file: DocumentFile) {
        AlertDialog.Builder(this).setTitle("Delete ${file.name}?")
            .setMessage("This action cannot be undone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                val ok = file.delete()
                Toast.makeText(this, if (ok) "Deleted" else "Delete failed", Toast.LENGTH_SHORT).show()
                renderFiles()
            }.show()
    }

    private fun openFile(file: DocumentFile) {
        try {
            val uri = file.uri
            val mime = file.type ?: "*/*"
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(intent, "Open with"))
        } catch (_: Exception) {
            Toast.makeText(this, "Is file ko open karne ke liye app nahi mili", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareFile(file: DocumentFile) {
        try {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = file.type ?: "*/*"
                putExtra(Intent.EXTRA_STREAM, file.uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Share file"))
        } catch (_: Exception) {
            Toast.makeText(this, "Share nahi ho paya", Toast.LENGTH_SHORT).show()
        }
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(15), dp(14), dp(15), dp(14))
        background = rounded(Color.WHITE, dp(16))
        elevation = dp(1).toFloat()
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
    }

    private fun label(value: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(2), 0, dp(3))
        }

    private fun pillButton(text: String, bg: Int, fg: Int, action: () -> Unit) =
        Button(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(fg)
            setBackgroundColor(bg)
            isAllCaps = false
            setOnClickListener { action() }
        }

    private fun rounded(color: Int, radius: Int): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
        }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
        else -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
    }

    private fun fileIcon(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg", "png", "gif", "webp" -> "🖼️"
        "mp4", "mkv", "mov", "3gp" -> "🎬"
        "mp3", "wav", "m4a", "ogg" -> "🎵"
        "pdf" -> "📕"
        "zip", "rar", "7z" -> "🗜️"
        "apk" -> "📦"
        "txt", "md", "log" -> "📄"
        else -> "📃"
    }

    private fun DocumentFile.typeLabel(): String =
        name?.substringAfterLast('.', "File")?.uppercase(Locale.ROOT) ?: "FILE"
}
