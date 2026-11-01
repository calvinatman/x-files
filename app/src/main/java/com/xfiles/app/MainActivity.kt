package com.xfiles.app

import android.os.Bundle
import java.io.FileOutputStream
import android.net.Uri
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import android.os.Environment
import android.os.StatFs
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

private val Black = Color(0xFF000000)
private val White = Color(0xFFFFFFFF)
private val Grey = Color(0xFF929292)
private val Card = Color(0xFF191919)
private val Line = Color(0xFF292929)

class MainActivity : ComponentActivity() {
    private fun cleanXFilesTemp() {
        val temp = File(cacheDir, "zip_password_extract")
        runCatching { temp.deleteRecursively() }
        cacheDir.listFiles()?.filter {
            it.isDirectory && (it.name.startsWith("xfiles_zip_") || it.name.startsWith("xfiles_"))
        }?.forEach { runCatching { it.deleteRecursively() } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cleanXFilesTemp()
        setContent { XFilesApp() }
    }
}

private data class Category(
    val name: String,
    val symbol: String,
    val size: Long = 0L,
    val count: Int = 0,
    val brandColor: Color = White
)

private data class CategoryResult(
    val size: Long,
    val count: Int
)

private data class ScanReport(
    val totalFiles: Int,
    val totalBytes: Long,
    val categories: Map<String, Pair<Int, Long>>,
    val largeFiles: List<File>,
    val emptyFolders: List<String>,
    val duplicates: List<List<File>>
)

private suspend fun performXScan(root: File, enabledCategories: Set<String>, batteryFriendly: Boolean = true): ScanReport = withContext(Dispatchers.IO) {
    val categories = linkedMapOf<String, Pair<Int, Long>>()
    val largeFiles = mutableListOf<File>()
    val emptyFolders = mutableListOf<String>()
    val hashes = mutableMapOf<String, MutableList<File>>()
    var totalFiles = 0
    var totalBytes = 0L
    var visited = 0
    val visitedDirectories = HashSet<String>()

    fun category(file: File): String = when (file.extension.lowercase(Locale.ROOT)) {
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic" -> "Images"
        "mp4", "mkv", "mov", "avi", "webm", "3gp" -> "Videos"
        "mp3", "wav", "aac", "ogg", "flac", "m4a" -> "Audio"
        "pdf", "doc", "docx", "txt", "xls", "xlsx", "ppt", "pptx" -> "Documents"
        "apk", "xapk", "apks" -> "APKs"
        "zip", "7z", "rar", "tar", "gz" -> "Archives"
        else -> "Other"
    }

    fun walk(dir: File, depth: Int) {
        if (depth > if (batteryFriendly) 14 else 20 || visited++ > if (batteryFriendly) 40000 else 150000) return
        val canonical = runCatching { dir.canonicalPath }.getOrNull() ?: return
        if (!visitedDirectories.add(canonical)) return
        val children = runCatching { dir.listFiles() }.getOrNull() ?: return
        if (children.isEmpty()) emptyFolders.add(dir.absolutePath)

        children.forEach { file ->
            if (file.name == "Android" && dir.absolutePath == root.absolutePath) {
                val media = File(file, "media")
                if (media.isDirectory) walk(media, depth + 2)
                return@forEach
            }
            if (file.isDirectory) {
                if (!file.name.startsWith(".") || file.name == ".Statuses") walk(file, depth + 1)
            } else if (file.isFile) {
                val size = file.length()
                val type = category(file)
                if (type != "Other" && type !in enabledCategories) return@forEach
                val old = categories[type] ?: (0 to 0L)
                categories[type] = (old.first + 1) to (old.second + size)
                totalFiles++
                totalBytes += size
                if (size >= 100L * 1024 * 1024) largeFiles.add(file)
                if (size > 0) {
                    val key = runCatching {
                        val digest = java.security.MessageDigest.getInstance("SHA-256")
                        file.inputStream().use { input ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                val n = input.read(buffer)
                                if (n <= 0) break
                                digest.update(buffer, 0, n)
                            }
                        }
                        digest.digest().joinToString("") { "%02x".format(it) }
                    }.getOrNull()
                    if (key != null) hashes.getOrPut(key) { mutableListOf() }.add(file)
                }
            }
        }
    }

    walk(root, 0)
    ScanReport(
        totalFiles, totalBytes, categories,
        largeFiles.sortedByDescending { it.length() }.take(30),
        emptyFolders.take(100),
        hashes.values.filter { it.size > 1 }.take(100)
    )
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    if (bytes < 1024L * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    if (bytes < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
    if (bytes < 1024L * 1024 * 1024 * 1024) return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
    return String.format(Locale.US, "%.2f TB", bytes / (1024.0 * 1024 * 1024 * 1024))
}


internal fun recordRecentFile(context: android.content.Context, file: File) {
    val prefs = context.getSharedPreferences("x_files_recent", android.content.Context.MODE_PRIVATE)
    val old = prefs.getString("files", "")?.split("\\n")?.filter { it.isNotBlank() } ?: emptyList()
    val updated = (listOf(file.absolutePath) + old.filter { it != file.absolutePath }).take(10)
    prefs.edit().putString("files", updated.joinToString("\\n")).apply()
}

private fun scanCategory(root: File, folders: List<String>, extensions: Set<String>): CategoryResult {
    var total = 0L
    var count = 0
    val visited = HashSet<String>()

    fun scan(file: File, depth: Int) {
        if (depth > 12 || count > 100000) return
        if (!visited.add(file.absolutePath)) return

        val children = runCatching { file.listFiles() }.getOrNull() ?: return
        for (child in children) {
            if (child.name.startsWith(".") && file.absolutePath != root.absolutePath) {
                if (child.isDirectory && child.name != ".Statuses") continue
            }
            if (child.isDirectory) {
                scan(child, depth + 1)
            } else if (child.isFile) {
                val ext = child.extension.lowercase(Locale.ROOT)
                if (extensions.isEmpty() || ext in extensions) {
                    total += child.length()
                    count++
                }
            }
        }
    }

    for (folder in folders) {
        val directory = File(root, folder)
        if (directory.isDirectory) scan(directory, 0)
    }

    return CategoryResult(total, count)
}

@Composable
fun XFilesApp() {
    var selectedTab by remember { mutableStateOf("Home") }
    var search by remember { mutableStateOf("") }
    var homeSearchResults by remember { mutableStateOf<List<File>>(emptyList()) }
    var homeSearchLoading by remember { mutableStateOf(false) }

    LaunchedEffect(search) {
        val query = search.trim()
        if (query.isBlank()) {
            homeSearchResults = emptyList()
            homeSearchLoading = false
        } else {
            homeSearchLoading = true
            homeSearchResults = withContext(Dispatchers.IO) {
                val found = mutableListOf<File>()
                val stack = ArrayDeque<File>()
                stack.add(root)
                while (stack.isNotEmpty() && found.size < 100) {
                    val dir = stack.removeLast()
                    val children = runCatching { dir.listFiles()?.toList() }.getOrNull() ?: continue
                    for (child in children) {
                        if (child.name.equals("Android", true) && child.isDirectory &&
                            child.parentFile == root) continue
                        if (child.name.startsWith(".")) continue
                        if (child.name.contains(query, ignoreCase = true)) found.add(child)
                        if (child.isDirectory && found.size < 100) stack.add(child)
                        if (found.size >= 100) break
                    }
                }
                found
            }
            homeSearchLoading = false
        }
    }


    val context = LocalContext.current
    val root = remember { Environment.getExternalStorageDirectory() }

    var categoryResults by remember { mutableStateOf<Map<String, CategoryResult>>(emptyMap()) }
    var scanning by remember { mutableStateOf(false) }

    val storage = remember {
        runCatching {
            val stat = StatFs(root.absolutePath)
            val total = stat.totalBytes
            val free = stat.availableBytes
            Triple(total, total - free, free)
        }.getOrElse { Triple(0L, 0L, 0L) }
    }

    LaunchedEffect(root.absolutePath) {
        scanning = true
        categoryResults = withContext(Dispatchers.IO) {
            val definitions = listOf(
                Triple("Images", listOf("Pictures", "DCIM"), setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "avif")),
                Triple("Videos", listOf("Movies", "DCIM"), setOf("mp4", "mkv", "mov", "avi", "webm", "3gp")),
                Triple("Audio", listOf("Music", "Podcasts", "Recordings"), setOf("mp3", "m4a", "aac", "wav", "flac", "ogg", "opus")),
                Triple("Documents", listOf("Documents", "Download"), setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "rtf", "csv")),
                Triple("APKs", listOf("Download", "Documents"), setOf("apk", "apks", "xapk")),
                Triple("Archives", listOf("Download", "Documents"), setOf("zip", "rar", "7z", "tar", "gz", "bz2")),
                Triple("Downloads", listOf("Download"), emptySet()),
                Triple("WhatsApp Status", listOf("Android/media/com.whatsapp/WhatsApp/Media/.Statuses", "WhatsApp/Media/.Statuses"), setOf("jpg", "jpeg", "png", "mp4", "webp")),
                Triple("Instagram", listOf("Pictures/Instagram", "Movies/Instagram", "Android/media/com.instagram.android"), setOf("jpg", "jpeg", "png", "mp4", "webp"))
            )

            val scanPrefs = context.getSharedPreferences(
                "x_files_settings", android.content.Context.MODE_PRIVATE
            )
            definitions
                .filter { (name, _, _) ->
                    when (name) {
                        "Images" -> scanPrefs.getBoolean("images", true)
                        "Videos" -> scanPrefs.getBoolean("videos", true)
                        "Audio" -> scanPrefs.getBoolean("audio", true)
                        "Documents" -> scanPrefs.getBoolean("documents", true)
                        "APKs" -> scanPrefs.getBoolean("apks", true)
                        else -> true
                    }
                }
                .associate { (name, folders, extensions) ->
                    name to scanCategory(root, folders, extensions)
                }
        }
        scanning = false
    }

    val categoryDefinitions = listOf(
        Category("Images", "▧"),
        Category("Videos", "▶"),
        Category("Audio", "♫"),
        Category("Documents", "▤"),
        Category("APKs", "◇"),
        Category("Archives", "▣"),
        Category("Downloads", "↓"),
        Category("WhatsApp Status", "◉", brandColor = Color(0xFF25D366)),
        Category("Instagram", "▣", brandColor = Color(0xFFE1306C))
    )

    var accentName by remember {
        mutableStateOf(
            context.getSharedPreferences("x_files_settings", android.content.Context.MODE_PRIVATE)
                .getString("accent", "White") ?: "White"
        )
    }
    val accentColor = when (accentName) {
        "Blue" -> Color(0xFF2196F3)
        "Green" -> Color(0xFF4CAF50)
        "Purple" -> Color(0xFF9C27B0)
        "Orange" -> Color(0xFFFF9800)
        else -> White
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Black,
            surface = Card,
            primary = accentColor,
            secondary = accentColor,
            onBackground = White,
            onSurface = White
        )
    ) {
        Scaffold(
            containerColor = Black,
            bottomBar = {
                if (selectedTab != "Files") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Black)
                            .navigationBarsPadding()
                            .padding(top = 9.dp, bottom = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BottomItem("Home", selectedTab == "Home", "⌂") { selectedTab = "Home" }
                        BottomItem("Files", false, "▤") { selectedTab = "Files" }
                        BottomItem("Tools", selectedTab == "Tools", "⚒") { selectedTab = "Tools" }
                        BottomItem("Settings", selectedTab == "Settings", "⚙") { selectedTab = "Settings" }
                    }
                }
            }
        ) { padding ->
            when (selectedTab) {
                "Files" -> Box(
                    Modifier.fillMaxSize().background(Black).padding(padding)
                ) {
                    FileBrowserScreen()
                }

                "Tools" -> ToolsDashboard(onOpenFiles = { selectedTab = "Files" })
                "Settings" -> SettingsDashboard(onAccentChanged = { accentName = it })

                else -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Black)
                            .padding(padding)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 17.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Image(
                                painter = painterResource(R.drawable.x_files_logo),
                                contentDescription = "X Files logo",
                                modifier = Modifier.size(42.dp),
                                contentScale = ContentScale.Fit
                            )
                            Column {
                                Text("X Files", color = White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                                Text("Your storage, organized.", color = Grey, fontSize = 11.sp)
                            }
                        }

                        Surface(
                            color = Card,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 15.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("⌕", color = Grey, fontSize = 24.sp)
                                Spacer(Modifier.width(10.dp))
                                BasicTextField(
                                    value = search,
                                    onValueChange = { search = it },
                                    singleLine = true,
                                    textStyle = androidx.compose.ui.text.TextStyle(color = White, fontSize = 14.sp),
                                    decorationBox = { inner ->
                                        if (search.isEmpty()) Text("Search files and folders", color = Grey, fontSize = 14.sp)
                                        inner()
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }


                        if (search.isNotBlank()) {
                            Surface(
                                color = Card,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        if (homeSearchLoading) "Searching..."
                                        else "Search Results (${homeSearchResults.size})",
                                        color = White,
                                        fontWeight = FontWeight.Bold
                                    )
                                    if (!homeSearchLoading && homeSearchResults.isEmpty()) {
                                        Text("No matching files found.", color = Grey)
                                    }
                                    homeSearchResults.take(20).forEach { file ->
                                        Column(Modifier.fillMaxWidth().clickable {
                                            if (file.isDirectory) {
                                                Toast.makeText(context, "Folder: ${file.absolutePath}", Toast.LENGTH_LONG).show()
                                            } else {
                                                runCatching {
                                                    val uri = androidx.core.content.FileProvider.getUriForFile(
                                                        context, "${context.packageName}.fileprovider", file
                                                    )
                                                    val mime = android.webkit.MimeTypeMap.getSingleton()
                                                        .getMimeTypeFromExtension(file.extension.lowercase())
                                                        ?: "*/*"
                                                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                                        setDataAndType(uri, mime)
                                                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                    }
                                                    recordRecentFile(context, file)
                                                    context.startActivity(intent)
                                                }.onFailure {
                                                    Toast.makeText(context, "Cannot open file: ${it.message}", Toast.LENGTH_LONG).show()
                                                }
                                            }
                                        }.padding(vertical = 5.dp)) {
                                            Text(file.name, color = White, maxLines = 1)
                                            Text(file.absolutePath, color = Grey, fontSize = 10.sp, maxLines = 1)
                                        }
                                    }
                                    if (homeSearchResults.size > 20) {
                                        Text("Showing first 20 of ${homeSearchResults.size} results.", color = Grey)
                                    }
                                }
                            }
                        }

                        SectionTitle("Storage Overview")
                        Surface(
                            color = Card,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(13.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Internal Storage", color = White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                                        Text("Device shared storage", color = Grey, fontSize = 11.sp)
                                    }
                                    Text(formatBytes(storage.second) + " used", color = White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                }

                                val progress = if (storage.first > 0L) storage.second.toFloat() / storage.first.toFloat() else 0f
                                Box(
                                    Modifier.fillMaxWidth().height(8.dp)
                                        .clip(RoundedCornerShape(50)).background(Color(0xFF444444))
                                ) {
                                    Box(
                                        Modifier.fillMaxWidth(progress.coerceIn(0f, 1f))
                                            .fillMaxHeight().background(White)
                                    )
                                }

                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    StorageValue("Total", formatBytes(storage.first))
                                    StorageValue("Used", formatBytes(storage.second))
                                    StorageValue("Free", formatBytes(storage.third))
                                }
                            }
                        }

                        Surface(
                            color = Card,
                            shape = RoundedCornerShape(15.dp),
                            modifier = Modifier.fillMaxWidth().clickable {
                                selectedTab = "Tools"
                            }
                        ) {
                            Row(
                                Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Image(
                                    painter = painterResource(R.drawable.x_files_logo),
                                    contentDescription = "X Scan",
                                    modifier = Modifier.size(43.dp)
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("X Scan", color = White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                    Text("Find and review accessible files", color = Grey, fontSize = 11.sp)
                                }
                                Text("›", color = White, fontSize = 26.sp)
                            }
                        }

                        SectionTitle("Quick Access")
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            QuickCard("Recent", "◷", Modifier.weight(1f)) {
                                Toast.makeText(context, "Recent files will be connected during Files implementation.", Toast.LENGTH_SHORT).show()
                            }
                            QuickCard("Downloads", "↓", Modifier.weight(1f)) {
                                selectedTab = "Files"
                            }
                            QuickCard("Favourites", "☆", Modifier.weight(1f)) {
                                Toast.makeText(context, "Favourites will be added in Files implementation.", Toast.LENGTH_SHORT).show()
                            }
                        }

                        SectionTitle("Categories")
                        Text(
                            if (scanning) "Calculating accessible folder sizes…" else "Accessible shared-storage files",
                            color = Grey,
                            fontSize = 11.sp
                        )

                        categoryDefinitions.chunked(3).forEach { rowItems ->
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(9.dp)
                            ) {
                                rowItems.forEach { category ->
                                    val result = categoryResults[category.name]
                                    Surface(
                                        color = Card,
                                        shape = RoundedCornerShape(13.dp),
                                        modifier = Modifier.weight(1f).height(105.dp)
                                    ) {
                                        Column(
                                            Modifier.padding(horizontal = 4.dp, vertical = 9.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.Center
                                        ) {
                                            Text(category.symbol, color = category.brandColor, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                                            Spacer(Modifier.height(4.dp))
                                            Text(
                                                category.name,
                                                color = White,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                softWrap = false,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                result?.let { formatBytes(it.size) } ?: "—",
                                                color = Grey,
                                                fontSize = 10.sp
                                            )
                                        }
                                    }
                                }
                                repeat(3 - rowItems.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }

                        SectionTitle("Recent Files")
                        Surface(
                            color = Card,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            var recent by remember { mutableStateOf(emptyList<String>()) }
                            val recentPrefs = remember {
                                context.getSharedPreferences("x_files_recent", android.content.Context.MODE_PRIVATE)
                            }
                            DisposableEffect(recentPrefs) {
                                val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                                    if (key == "files") {
                                        recent = recentPrefs.getString("files", "")?.split("\\n")
                                            ?.filter { it.isNotBlank() && File(it).isFile }?.take(10) ?: emptyList()
                                    }
                                }
                                recentPrefs.registerOnSharedPreferenceChangeListener(listener)
                                onDispose { recentPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
                            }
                            LaunchedEffect(selectedTab) {
                                recent = recentPrefs.getString("files", "")?.split("\\n")
                                    ?.filter { it.isNotBlank() && File(it).isFile }?.take(10) ?: emptyList()
                            }
                            if (recent.isEmpty()) {
                                Text("No recently opened files.", color = Grey, fontSize = 12.sp,
                                    modifier = Modifier.padding(16.dp))
                            } else {
                                Column(Modifier.padding(12.dp)) {
                                    recent.forEach { path ->
                                        val file = File(path)
                                        Row(
                                            Modifier.fillMaxWidth().clickable {
                                                runCatching {
                                                    val uri = androidx.core.content.FileProvider.getUriForFile(
                                                        context, "${context.packageName}.fileprovider", file
                                                    )
                                                    val mime = android.webkit.MimeTypeMap.getSingleton()
                                                        .getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
                                                    context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                                        setDataAndType(uri, mime)
                                                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                    })
                                                }.onFailure {
                                                    Toast.makeText(context, "Cannot open file", Toast.LENGTH_SHORT).show()
                                                }
                                            }.padding(vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(Modifier.weight(1f)) {
                                                Text(file.name, color = White, maxLines = 1)
                                                Text(file.parent ?: "", color = Grey, fontSize = 10.sp, maxLines = 1)
                                            }
                                            Text("›", color = White, fontSize = 20.sp)
                                        }
                                        HorizontalDivider(color = Line)
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun SimpleSection(title: String, description: String) {
    Column(
        Modifier.fillMaxSize().background(Black).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(title, color = White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text(description, color = Grey, fontSize = 13.sp)
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, color = White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun StorageValue(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = Grey, fontSize = 10.sp)
        Text(value, color = White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun QuickCard(title: String, symbol: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        color = Card,
        shape = RoundedCornerShape(13.dp),
        modifier = modifier.height(78.dp).clickable(onClick = onClick)
    ) {
        Column(
            Modifier.padding(9.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(symbol, color = White, fontSize = 22.sp)
            Text(title, color = White, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}

@Composable
private fun BottomItem(label: String, selected: Boolean, symbol: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(13.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(symbol, color = if (selected) White else Grey, fontSize = 22.sp)
        Text(
            label,
            color = if (selected) White else Grey,
            fontSize = 10.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
    }
}


@Composable
private fun ToolsDashboard(onOpenFiles: () -> Unit) {
    val tools = listOf(
        "X Scanner" to "Scan accessible files and folders",
        "Storage Analyzer" to "Understand storage usage",
        "Archive Manager" to "Create and extract archives",
        "Duplicate Finder" to "Find exact duplicate files",
        "Large Files" to "Locate space-consuming files",
        "Empty Folders" to "Find empty folders",
        "File Splitter & Joiner" to "Split and join files",
        "Checksum Calculator" to "Calculate file hashes",
        "Storage Information" to "View storage details",
        "APK Installer" to "Install APK files",
        "Text Editor" to "Open and edit text files",
        "Image Converter" to "Convert images to JPG"
    )
    var selectedTool by remember { mutableStateOf<String?>(null) }
    var report by remember { mutableStateOf<ScanReport?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var splitSource by remember { mutableStateOf<Uri?>(null) }
    var splitDestination by remember { mutableStateOf<Uri?>(null) }
    var joinSources by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var joinDestination by remember { mutableStateOf<Uri?>(null) }
    var partSizeMb by remember { mutableStateOf("100") }
    var fileOperationMessage by remember { mutableStateOf("") }
    var fileOperationBusy by remember { mutableStateOf(false) }
    var checksumUri by remember { mutableStateOf<Uri?>(null) }
    var checksumResult by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var checksumBusy by remember { mutableStateOf(false) }

    var apkUri by remember { mutableStateOf<Uri?>(null) }
    val apkPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        apkUri = uri
        if (uri != null) {
            runCatching {
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(intent)
            }.onFailure {
                error = "Cannot open APK: ${it.message}"
            }
        }
    }

    var editorText by remember { mutableStateOf("") }
    var editorName by remember { mutableStateOf("notes.txt") }
    var editorMessage by remember { mutableStateOf("") }

    val textPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                            ?: error("Cannot read this file")
                    }
                }.onSuccess {
                    editorText = it
                    editorName = uri.lastPathSegment?.substringAfterLast('/') ?: "notes.txt"
                    editorMessage = "File opened"
                }.onFailure {
                    editorMessage = "Open failed: ${it.message}"
                }
            }
        }
    }

    val textSavePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(editorText) }
                    ?: error("Cannot save this file")
            }.onSuccess {
                editorMessage = "Saved successfully"
            }.onFailure {
                editorMessage = "Save failed: ${it.message}"
            }
        }
    }

    var imageUri by remember { mutableStateOf<Uri?>(null) }
    var imageMessage by remember { mutableStateOf("") }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        imageUri = uri
        imageMessage = if (uri != null) "Image selected" else ""
    }

    val imageOutputPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/jpeg")
    ) { uri ->
        val source = imageUri
        if (uri != null && source != null) {
            scope.launch {
                imageMessage = try {
                    withContext(Dispatchers.IO) {
                        val bitmap = context.contentResolver.openInputStream(source)?.use {
                            android.graphics.BitmapFactory.decodeStream(it)
                        } ?: error("Cannot decode image")
                        context.contentResolver.openOutputStream(uri)?.use {
                            if (!bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, it)) {
                                error("JPG conversion failed")
                            }
                        } ?: error("Cannot save output")
                        bitmap.recycle()
                    }
                    "JPG saved successfully"
                } catch (e: Exception) {
                    "Conversion failed: ${e.message}"
                }
            }
        }
    }

    val checksumPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        checksumUri = it
        checksumResult = emptyMap()
    }

    val splitPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        splitSource = it
    }
    val splitFolderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
        splitDestination = it
    }
    val joinPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
        joinSources = it
    }
    val joinOutputPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        joinDestination = uri
        if (uri != null && joinSources.isNotEmpty()) {
            scope.launch {
                fileOperationBusy = true
                fileOperationMessage = try {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { output ->
                            joinSources.forEach { part ->
                                context.contentResolver.openInputStream(part)?.use { input ->
                                    input.copyTo(output)
                                } ?: error("Cannot read a selected part")
                            }
                        } ?: error("Cannot create output file")
                    }
                    "Files joined successfully."
                } catch (e: Exception) {
                    "Join failed: ${e.message}"
                }
                fileOperationBusy = false
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(Color.Black)
            .verticalScroll(rememberScrollState()).padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (selectedTool != null) {
                TextButton(onClick = { selectedTool = null }) { Text("‹ Back", color = Color.White) }
            }
            Text(selectedTool ?: "Tools", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
        }

        if (selectedTool == "X Scanner") {
            Text("Offline scan • Accessible shared storage", color = Grey, fontSize = 12.sp)
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    scanning = true
                    error = null
                    scope.launch {
                        try {
                            val scanPrefs = context.getSharedPreferences("x_files_settings", android.content.Context.MODE_PRIVATE)
                            val enabled = buildSet {
                                if (scanPrefs.getBoolean("images", true)) add("Images")
                                if (scanPrefs.getBoolean("videos", true)) add("Videos")
                                if (scanPrefs.getBoolean("audio", true)) add("Audio")
                                if (scanPrefs.getBoolean("documents", true)) add("Documents")
                                if (scanPrefs.getBoolean("apks", true)) add("APKs")
                                add("Archives")
                            }
                            val batteryMode = context.getSharedPreferences("x_files_settings", android.content.Context.MODE_PRIVATE).getBoolean("battery", true)
                    report = performXScan(Environment.getExternalStorageDirectory(), enabled, batteryMode)
                        } catch (e: Exception) {
                            error = e.message ?: "Scan failed"
                        } finally {
                            scanning = false
                        }
                    }
                },
                enabled = !scanning,
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)
            ) {
                Text(if (scanning) "Scanning..." else "Start X Scan")
            }
            if (scanning) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Color.White)
                Text("Scanning accessible files. Large storage may take time.", color = Grey, fontSize = 12.sp)
            }
            error?.let { Text(it, color = Color(0xFFFF7777)) }

            report?.let { result ->
                Spacer(Modifier.height(18.dp))
                Card(colors = CardDefaults.cardColors(containerColor = Card)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Scan Overview", color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text("${result.totalFiles} files • ${formatBytes(result.totalBytes)}", color = White)
                        Text("${result.duplicates.size} duplicate groups • ${result.largeFiles.size} large files", color = Grey, fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text("Categories", color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                result.categories.forEach { (name, values) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(name, color = White)
                        Text("${values.first} • ${formatBytes(values.second)}", color = Grey)
                    }
                    HorizontalDivider(color = Line)
                }
                Spacer(Modifier.height(14.dp))
                Text("Large Files (100 MB+)", color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                result.largeFiles.forEach { file ->
                    Column(Modifier.padding(vertical = 6.dp)) {
                        Text(file.name, color = White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${formatBytes(file.length())} • ${file.parent}", color = Grey, fontSize = 11.sp)
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text("Duplicate Groups", color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                result.duplicates.forEachIndexed { index, group ->
                    Text("Group ${index + 1} • ${group.size} copies", color = White)
                    group.forEach { file ->
                        Text("${file.name} • ${file.parent}", color = Grey, fontSize = 11.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                }
                Spacer(Modifier.height(14.dp))
                Text("Empty Folders", color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                result.emptyFolders.forEach { path ->
                    Text(path, color = Grey, fontSize = 11.sp)
                }
                Text("Scan covers accessible shared storage only. Android private/restricted folders are excluded. No files were deleted.", color = Grey, fontSize = 11.sp)
            }
        } else if (selectedTool == "Storage Information") {
            val internal = Environment.getDataDirectory()
            val internalStats = runCatching { StatFs(internal.path) }.getOrNull()
            val internalTotal = internalStats?.totalBytes ?: 0L
            val internalFree = internalStats?.availableBytes ?: 0L
            val internalUsed = (internalTotal - internalFree).coerceAtLeast(0L)
            val externalDirs = context.getExternalFilesDirs(null).filterNotNull()
            val externalRoots = externalDirs.mapNotNull { dir ->
                runCatching {
                    val path = dir.absolutePath.substringBefore("/Android/")
                    File(path).takeIf { it.exists() }
                }.getOrNull()
            }.distinctBy { it.absolutePath }

            Text("Device storage overview", color = Grey, fontSize = 12.sp)
            Spacer(Modifier.height(14.dp))
            Card(colors = CardDefaults.cardColors(containerColor = Card)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Internal Storage", color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text("${formatBytes(internalUsed)} used", color = White, fontSize = 22.sp)
                    Text("${formatBytes(internalFree)} available of ${formatBytes(internalTotal)}", color = Grey, fontSize = 12.sp)
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { if (internalTotal > 0) (internalUsed.toFloat() / internalTotal).coerceIn(0f, 1f) else 0f },
                        modifier = Modifier.fillMaxWidth().height(8.dp),
                        color = Color.White,
                        trackColor = Line
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("External Storage / SD Card", color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            if (externalRoots.isEmpty()) {
                Text("No additional shared storage detected.", color = Grey, fontSize = 12.sp)
            } else {
                externalRoots.forEach { root ->
                    val stats = runCatching { StatFs(root.path) }.getOrNull()
                    val total = stats?.totalBytes ?: 0L
                    val free = stats?.availableBytes ?: 0L
                    Column(Modifier.padding(vertical = 10.dp)) {
                        Text(root.absolutePath, color = White, fontSize = 12.sp)
                        Text("Total: ${formatBytes(total)}", color = Grey, fontSize = 12.sp)
                        Text("Available: ${formatBytes(free)}", color = Grey, fontSize = 12.sp)
                    }
                    HorizontalDivider(color = Line)
                }
            }
            Text("Android may restrict access to some app-specific and system storage areas.", color = Grey, fontSize = 11.sp)

        } else if (selectedTool == "Checksum Calculator") {
            Text("Calculate file hashes offline", color = Grey, fontSize = 12.sp)
            Spacer(Modifier.height(12.dp))
            Button(onClick = { checksumPicker.launch(arrayOf("*/*")) }) {
                Text("Choose file")
            }
            checksumUri?.let { uri ->
                Text(uri.lastPathSegment ?: "Selected file", color = White, fontSize = 12.sp)
                Button(
                    enabled = !checksumBusy,
                    onClick = {
                        scope.launch {
                            checksumBusy = true
                            checksumResult = try {
                                withContext(Dispatchers.IO) {
                                    val algorithms = listOf("SHA-256", "SHA-1", "MD5")
                                    algorithms.associateWith { algorithm ->
                                        val digest = java.security.MessageDigest.getInstance(algorithm)
                                        context.contentResolver.openInputStream(uri)?.use { input ->
                                            val buffer = ByteArray(64 * 1024)
                                            while (true) {
                                                val count = input.read(buffer)
                                                if (count < 0) break
                                                digest.update(buffer, 0, count)
                                            }
                                        } ?: error("Cannot read selected file")
                                        digest.digest().joinToString("") { "%02x".format(it) }
                                    }
                                }
                            } catch (e: Exception) {
                                mapOf("Error" to (e.message ?: "Hash calculation failed"))
                            }
                            checksumBusy = false
                        }
                    }
                ) { Text(if (checksumBusy) "Calculating..." else "Calculate hashes") }
            }
            if (checksumBusy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            checksumResult.forEach { (algorithm, value) ->
                Text(algorithm, color = White, fontWeight = FontWeight.Bold)
                SelectionContainer {
                    Text(value, color = Grey, fontSize = 12.sp)
                }
                Spacer(Modifier.height(10.dp))
            }
        } else if (selectedTool == "File Splitter & Joiner") {
            Text("Split large files into numbered parts or join selected parts.", color = Grey, fontSize = 12.sp)
            Spacer(Modifier.height(12.dp))
            Text("SPLIT FILE", color = White, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = partSizeMb,
                onValueChange = { partSizeMb = it.filter(Char::isDigit).take(6) },
                label = { Text("Part size (MB)") },
                singleLine = true
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = { splitPicker.launch(arrayOf("*/*")) }) { Text("Choose file to split") }
            splitSource?.let { Text("Selected: ${it.lastPathSegment ?: "file"}", color = Grey, fontSize = 12.sp) }
            Button(
                enabled = splitSource != null && !fileOperationBusy,
                onClick = { splitFolderPicker.launch(null) }
            ) { Text("Choose destination and split") }

            Spacer(Modifier.height(20.dp))
            HorizontalDivider(color = Line)
            Spacer(Modifier.height(14.dp))
            Text("JOIN PARTS", color = White, fontWeight = FontWeight.Bold)
            Button(onClick = { joinPicker.launch(arrayOf("*/*")) }) { Text("Choose split parts") }
            Text("${joinSources.size} part(s) selected", color = Grey, fontSize = 12.sp)
            Button(
                enabled = joinSources.isNotEmpty() && !fileOperationBusy,
                onClick = { joinOutputPicker.launch("joined_file") }
            ) { Text("Join into one file") }

            if (splitDestination != null && splitSource != null && !fileOperationBusy) {
                Button(onClick = {
                    val source = splitSource!!
                    val folderUri = splitDestination!!
                    val partMb = partSizeMb.toLongOrNull()
                    if (partMb == null || partMb <= 0) {
                        fileOperationMessage = "Enter a valid part size."
                    } else {
                        scope.launch {
                            fileOperationBusy = true
                            fileOperationMessage = try {
                                withContext(Dispatchers.IO) {
                                    val root = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, folderUri)
                                        ?: error("Cannot access destination folder")
                                    val name = source.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':') ?: "file"
                                    val buffer = ByteArray(1024 * 1024)
                                    var part = 1
                                    var total = 0L
                                    context.contentResolver.openInputStream(source)?.use { input ->
                                        while (true) {
                                            val doc = root.createFile("application/octet-stream", "$name.part${part.toString().padStart(3, '0')}")
                                                ?: error("Cannot create part $part")
                                            var partBytes = 0L
                                            context.contentResolver.openOutputStream(doc.uri)?.use { output ->
                                                while (partBytes < partMb * 1024L * 1024L) {
                                                    val wanted = minOf(buffer.size.toLong(), partMb * 1024L * 1024L - partBytes).toInt()
                                                    val read = input.read(buffer, 0, wanted)
                                                    if (read < 0) break
                                                    output.write(buffer, 0, read)
                                                    partBytes += read
                                                    total += read
                                                }
                                            } ?: error("Cannot write part $part")
                                            if (partBytes == 0L) {
                                                doc.delete()
                                                break
                                            }
                                            part++
                                            if (partBytes < partMb * 1024L * 1024L) break
                                        }
                                    } ?: error("Cannot read source file")
                                    "Split complete: ${part - 1} part(s), ${formatBytes(total)}."
                                }
                            } catch (e: Exception) {
                                "Split failed: ${e.message}"
                            }
                            fileOperationBusy = false
                            splitDestination = null
                        }
                    }
                }) { Text("Start splitting") }
            }
            if (fileOperationBusy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            if (fileOperationMessage.isNotBlank()) Text(fileOperationMessage, color = White, fontSize = 12.sp)
            Text("Join parts in their original numbered order. Select only parts from the same split file.", color = Grey, fontSize = 11.sp)

        } else if (selectedTool == "Storage Analyzer") {
            Text("Storage usage overview", color = Grey, fontSize = 12.sp)
            Spacer(Modifier.height(14.dp))
            if (report == null) {
                Text("Run X Scanner first to generate storage analysis.", color = White)
                Spacer(Modifier.height(8.dp))
                Button(onClick = { selectedTool = "X Scanner" }) { Text("Open X Scanner") }
            } else {
                val data = report!!
                Card(colors = CardDefaults.cardColors(containerColor = Card)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Accessible Storage", color = Grey)
                        Text(formatBytes(data.totalBytes), color = White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                        Text("${data.totalFiles} files scanned", color = Grey)
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text("Space by Category", color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                data.categories.entries.sortedByDescending { it.value.second }.forEach { entry ->
                    Column(Modifier.padding(vertical = 8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(entry.key, color = White)
                            Text(formatBytes(entry.value.second), color = Grey)
                        }
                        val fraction = if (data.totalBytes > 0) entry.value.second.toFloat() / data.totalBytes else 0f
                        LinearProgressIndicator(
                            progress = { fraction.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(6.dp),
                            color = Color.White,
                            trackColor = Line
                        )
                        Text("${entry.value.first} files", color = Grey, fontSize = 11.sp)
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text("Storage Review", color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("${data.largeFiles.size} files above 100 MB", color = Grey)
                Text("${data.duplicates.size} exact duplicate groups", color = Grey)
                Text("${data.emptyFolders.size} empty folders found", color = Grey)
                Text("Analysis covers accessible shared storage only. No files are deleted.", color = Grey, fontSize = 11.sp)
            }
        } else if (selectedTool == "Duplicate Finder" || selectedTool == "Large Files" || selectedTool == "Empty Folders") {
            val data = report
            if (data == null) {
                Text("Run X Scanner first to load results.", color = Grey)
                Button(onClick = { selectedTool = "X Scanner" }) { Text("Open X Scanner") }
            } else {
                when (selectedTool) {
                    "Duplicate Finder" -> {
                        Text("Exact duplicates • SHA-256 matched", color = Grey, fontSize = 12.sp)
                        Spacer(Modifier.height(12.dp))
                        if (data.duplicates.isEmpty()) {
                            Text("No duplicate groups found in the scanned files.", color = White)
                        } else {
                            data.duplicates.forEachIndexed { index, group ->
                                Card(
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                                    colors = CardDefaults.cardColors(containerColor = Card)
                                ) {
                                    Column(Modifier.padding(14.dp)) {
                                        Text("Duplicate group ${index + 1}", color = White, fontWeight = FontWeight.Bold)
                                        Text("${group.size} matching copies • ${formatBytes(group.first().length())} each", color = Grey, fontSize = 12.sp)
                                        group.forEach { file ->
                                            Text(file.absolutePath, color = Grey, fontSize = 11.sp)
                                        }
                                        Text("No files selected or deleted.", color = Grey, fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }
                    "Large Files" -> {
                        Text("Files larger than 100 MB", color = Grey, fontSize = 12.sp)
                        Spacer(Modifier.height(12.dp))
                        if (data.largeFiles.isEmpty()) {
                            Text("No files above 100 MB found.", color = White)
                        } else {
                            data.largeFiles.forEach { file ->
                                Card(
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                    colors = CardDefaults.cardColors(containerColor = Card)
                                ) {
                                    Column(Modifier.padding(12.dp)) {
                                        Text(file.name, color = White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text(formatBytes(file.length()), color = White, fontWeight = FontWeight.Bold)
                                        Text(file.parent ?: "", color = Grey, fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }
                    "Empty Folders" -> {
                        Text("${data.emptyFolders.size} empty folders found", color = Grey)
                        data.emptyFolders.forEach { folder ->
                            Text(folder, color = White, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))
                            HorizontalDivider(color = Line)
                        }
                    }
                }
            }
        } else if (selectedTool == "APK Installer") {
            Text("Choose an APK file to open Android's installer.", color = Grey)
            Spacer(Modifier.height(14.dp))
            Button(onClick = {
                apkPicker.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream"))
            }) {
                Text("Choose APK")
            }
            Text("Installation is handled by Android. System permission or confirmation may be required.", color = Grey, fontSize = 12.sp)
        } else if (selectedTool == "Archive Manager") {
            Text("Create and extract supported archives from the file browser.", color = Grey)
            Spacer(Modifier.height(12.dp))
            Button(onClick = onOpenFiles) { Text("Open Files") }
            Text("Supported formats depend on the archive type and available access permissions.", color = Grey, fontSize = 12.sp)
        } else if (selectedTool == "Text Editor") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { textPicker.launch(arrayOf("text/*")) }) { Text("Open") }
                Button(onClick = { textSavePicker.launch(editorName) }) { Text("Save As") }
            }
            Spacer(Modifier.height(8.dp))
            Text(editorMessage, color = Grey, fontSize = 12.sp)
            OutlinedTextField(
                value = editorText,
                onValueChange = { editorText = it },
                modifier = Modifier.fillMaxWidth().height(420.dp),
                placeholder = { Text("Write or edit text…") },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = White,
                    unfocusedTextColor = White,
                    focusedBorderColor = White,
                    unfocusedBorderColor = Grey,
                    cursorColor = White
                )
            )
        } else if (selectedTool == "Image Converter") {
            Text("Select an image and save a JPG copy.", color = Grey)
            Spacer(Modifier.height(12.dp))
            Button(onClick = { imagePicker.launch(arrayOf("image/*")) }) {
                Text("Choose Image")
            }
            if (imageUri != null) {
                Text("Image selected", color = White, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    imageOutputPicker.launch("converted_image.jpg")
                }) { Text("Convert to JPG") }
            }
            Text(imageMessage, color = Grey, fontSize = 12.sp)
        } else if (selectedTool != null) {
            Text("$selectedTool implementation is pending.", color = Grey)
        } else {
            Text("Offline utilities", color = Grey, fontSize = 13.sp)
            Spacer(Modifier.height(18.dp))
            tools.forEach { (name, description) ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp).clickable { selectedTool = name },
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF171717)),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(name, color = White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(4.dp))
                            Text(description, color = Grey, fontSize = 12.sp)
                        }
                        Text("›", color = White, fontSize = 25.sp)
                    }
                }
            }
        }
    }
}



@Composable
private fun SettingsDashboard(onAccentChanged: (String) -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("x_files_settings", android.content.Context.MODE_PRIVATE) }
    var accent by remember { mutableStateOf(prefs.getString("accent", "White") ?: "White") }
    var layout by remember { mutableStateOf(prefs.getString("layout", "List") ?: "List") }
    var sorting by remember { mutableStateOf(prefs.getString("sorting", "Name") ?: "Name") }

    var hidden by remember { mutableStateOf(prefs.getBoolean("hidden", true)) }
    var extensions by remember { mutableStateOf(prefs.getBoolean("extensions", true)) }
    var confirmDelete by remember { mutableStateOf(prefs.getBoolean("confirm_delete", true)) }
    var folderSizes by remember { mutableStateOf(prefs.getBoolean("folder_sizes", false)) }
    var images by remember { mutableStateOf(prefs.getBoolean("images", true)) }
    var videos by remember { mutableStateOf(prefs.getBoolean("videos", true)) }
    var audio by remember { mutableStateOf(prefs.getBoolean("audio", true)) }
    var documents by remember { mutableStateOf(prefs.getBoolean("documents", true)) }
    var apks by remember { mutableStateOf(prefs.getBoolean("apks", true)) }
    var battery by remember { mutableStateOf(prefs.getBoolean("battery", true)) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Settings", color = White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
        Text("Customize your X Files experience", color = Grey, fontSize = 12.sp)
        Spacer(Modifier.height(20.dp))

        @Composable
        fun heading(value: String) {
            Text(value, color = Grey, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
        }

        @Composable
        fun toggle(title: String, value: Boolean, key: String, update: (Boolean) -> Unit) {
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Switch(value, onCheckedChange = {
                    update(it)
                    prefs.edit().putBoolean(key, it).apply()
                })
            }
        }

        @Composable
        fun choice(title: String, values: List<String>, selected: String, key: String, update: (String) -> Unit) {
            Text(title, color = White, fontSize = 14.sp)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                values.forEach { item ->
                    FilterChip(selected == item, onClick = {
                        update(item)
                        prefs.edit().putString(key, item).apply()
                    }, label = { Text(item) })
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        heading("APPEARANCE")
        choice("Accent color", listOf("White", "Blue", "Green", "Purple", "Orange"), accent, "accent") {
            accent = it
            onAccentChanged(it)
        }
        HorizontalDivider(color = Line)

        heading("FILE DISPLAY")
        choice("Default layout", listOf("List", "Grid"), layout, "layout") { layout = it }
        toggle("Show hidden files", hidden, "hidden") { hidden = it }
        toggle("Show file extensions", extensions, "extensions") { extensions = it }
        HorizontalDivider(color = Line)

        heading("FILE MANAGEMENT")
        choice("Default sorting", listOf("Name", "Size", "Date"), sorting, "sorting") { sorting = it }
        toggle("Confirm before deleting", confirmDelete, "confirm_delete") { confirmDelete = it }
        toggle("Calculate folder sizes", folderSizes, "folder_sizes") { folderSizes = it }
        HorizontalDivider(color = Line)

        heading("SCAN SETTINGS")
        toggle("Scan images", images, "images") { images = it }
        toggle("Scan videos", videos, "videos") { videos = it }
        toggle("Scan audio", audio, "audio") { audio = it }
        toggle("Scan documents", documents, "documents") { documents = it }
        toggle("Scan APK files", apks, "apks") { apks = it }
        HorizontalDivider(color = Line)

        heading("PERFORMANCE")
        toggle("Limit scan depth (battery-friendly)", battery, "battery") { battery = it }
        HorizontalDivider(color = Line)

        heading("STORAGE & PERMISSIONS")
        Text("Use Android storage permission settings and the Files folder picker for SD card or USB access.", color = Grey, fontSize = 12.sp)
        Spacer(Modifier.height(16.dp))

        heading("GENERAL")
        Text("Language: Device default", color = White, fontSize = 14.sp)
        Text("Notifications: Not configured", color = Grey, fontSize = 12.sp)
        Spacer(Modifier.height(16.dp))

        heading("ABOUT X FILES")
        Text("X Files • Offline file manager", color = White, fontSize = 14.sp)
        Text("No ads • No tracking • No root required", color = Grey, fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))
        Text("CONTACT DEVELOPER", color = Grey, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))

        OutlinedButton(
            onClick = {
                runCatching {
                    context.startActivity(android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://t.me/blvcknetwork")
                    ))
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Telegram  •  @blvcknetwork")
        }

        OutlinedButton(
            onClick = {
                runCatching {
                    context.startActivity(android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://instagram.com/tonmoyhazar")
                    ))
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Instagram  •  @tonmoyhazar")
        }
        Spacer(Modifier.height(16.dp))

        OutlinedButton(onClick = {
            prefs.edit().clear().apply()
            accent = "White"
            onAccentChanged("White")
            layout = "List"
            sorting = "Name"
            hidden = true
            extensions = true
            confirmDelete = true
            folderSizes = false
            images = true
            videos = true
            audio = true
            documents = true
            apks = true
            battery = true
        }) {
            Text("Reset settings")
        }
    }

    StoragePermissionCard()
    PrivacyInfoCard()
    AboutAppCard()
    RestrictedFoldersGuide()
}


@Composable
private fun StoragePermissionCard() {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Storage Permission", color = Color.White, fontWeight = FontWeight.Bold)
        Text(
            "Manage X Files storage access from Android settings.",
            color = Color.LightGray
        )
        Button(onClick = {
            val intent = if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    android.net.Uri.parse("package:" + context.packageName)
                )
            } else {
                android.content.Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:" + context.packageName)
                )
            }
            runCatching { context.startActivity(intent) }
        }) {
            Text("Open Permission Settings")
        }
    }
}


@Composable
private fun PrivacyInfoCard() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Privacy & Security", color = Color.White, fontWeight = FontWeight.Bold)
        Text(
            "X Files is designed for offline file management. File scanning and file operations are performed on your device. No app-owned cloud storage is used.",
            color = Color.LightGray
        )
        Text(
            "Only grant storage access when you want X Files to manage your files. Android may restrict access to protected folders.",
            color = Color.LightGray
        )
    }
}


@Composable
private fun AboutAppCard() {
    val context = LocalContext.current
    val version = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "Unknown"
    }
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("About X Files", color = Color.White, fontWeight = FontWeight.Bold)
        Text("Version: $version", color = Color.LightGray)
        Text("Android: ${android.os.Build.VERSION.RELEASE}", color = Color.LightGray)
        Text("Package: ${context.packageName}", color = Color.LightGray)
    }
}


@Composable
private fun RestrictedFoldersGuide() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Restricted Android Folders", color = Color.White, fontWeight = FontWeight.Bold)
        Text(
            "Android 11 and newer restrict access to Android/data and Android/obb. " +
                "These folders may remain inaccessible even when broad storage permission is granted.",
            color = Color.LightGray
        )
        Text(
            "For folders Android allows you to select, open Files and use Choose folder. " +
                "X Files will show only content the system grants access to.",
            color = Color.LightGray
        )
    }
}
