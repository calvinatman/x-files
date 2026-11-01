package com.xfiles.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import com.github.junrar.Junrar
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import net.lingala.zip4j.ZipFile as Zip4jFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipFile as JavaZipFile

private val XWhite = Color.White
private val XGrey = Color(0xFF929292)
private val XLine = Color(0xFF292929)
private val XBlack = Color.Black
private val XPanel = Color(0xFF202020)

@Composable
fun FileBrowserScreen() {
    val context = LocalContext.current
    val internalRoot = remember { Environment.getExternalStorageDirectory().absolutePath }

    var currentPath by remember {
        val savedPath = context.getSharedPreferences(
            "x_files_settings", android.content.Context.MODE_PRIVATE
        ).getString("last_path", internalRoot) ?: internalRoot
        mutableStateOf(if (File(savedPath).isDirectory) savedPath else internalRoot)
    }
    var currentSafFolder by remember { mutableStateOf<DocumentFile?>(null) }
    val safStack = remember { mutableStateListOf<DocumentFile>() }

    var refresh by remember { mutableIntStateOf(0) }
    val settingsPrefs = remember {
        context.getSharedPreferences("x_files_settings", android.content.Context.MODE_PRIVATE)
    }
    var sortBy by remember {
        mutableStateOf(settingsPrefs.getString("sorting", "Name") ?: "Name")
    }
    var showHidden by remember {
        mutableStateOf(settingsPrefs.getBoolean("hidden", true))
    }
    var gridMode by remember {
        mutableStateOf(settingsPrefs.getString("layout", "List") == "Grid")
    }
    LaunchedEffect(currentPath) {
        settingsPrefs.edit().putString("last_path", currentPath).apply()
    }
    var searchText by remember { mutableStateOf("") }
    var searchOpen by remember { mutableStateOf(false) }
    var storageMenu by remember { mutableStateOf(false) }
    var plusMenu by remember { mutableStateOf(false) }
    var toolbarMenu by remember { mutableStateOf(false) }
    var moveMode by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var createType by remember { mutableStateOf("Folder") }
    var newName by remember { mutableStateOf("") }
    var archivePassword by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameValue by remember { mutableStateOf("") }
    var pendingZip by remember { mutableStateOf<File?>(null) }
    var pendingTar by remember { mutableStateOf<File?>(null) }
    var pending7z by remember { mutableStateOf<File?>(null) }
    var pendingRar by remember { mutableStateOf<File?>(null) }
    var zipEntryNames by remember { mutableStateOf<List<String>>(emptyList()) }
    var zipPassword by remember { mutableStateOf("") }
    var showZipPasswordDialog by remember { mutableStateOf(false) }
    var zipPasswordError by remember { mutableStateOf("") }


    val selectedItems = remember { mutableStateListOf<String>() }
    val selectionMode = selectedItems.isNotEmpty()

    var hasAccess by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
            else context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        hasAccess = context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        refresh++
    }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val folder = runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                DocumentFile.fromTreeUri(context, uri)
            }.getOrNull()

            if (folder != null && folder.exists() && folder.isDirectory) {
                currentSafFolder = folder
                safStack.clear()
                selectedItems.clear()
                searchText = ""
                refresh++
            } else {
                message = "Could not access the selected folder."
            }
        }
    }


    val copyDestinationPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { destinationUri ->
        if (destinationUri != null) {
            val result = runCatching {
                context.contentResolver.takePersistableUriPermission(
                    destinationUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                val destination = DocumentFile.fromTreeUri(context, destinationUri)
                    ?: error("Destination unavailable")

                var copied = 0
                var skipped = 0
                var failed = 0

                fun copyFileToSaf(source: File, parent: DocumentFile) {
                    if (source.isDirectory) {
                        require(parent.findFile(source.name) == null) { "Already exists: ${source.name}" }
                        require(parent.findFile(source.name) == null) { "Already exists: ${source.name}" }
                        val folder = parent.createDirectory(source.name)
                            ?: error("Cannot create folder: ${source.name}")
                        source.listFiles()?.forEach { copyFileToSaf(it, folder) }
                    } else {
                        require(parent.findFile(source.name) == null) { "Already exists: ${source.name}" }
                        require(parent.findFile(source.name) == null) { "Already exists: ${source.name}" }
                        val target = parent.createFile("application/octet-stream", source.name)
                            ?: error("Cannot create file: ${source.name}")
                        context.contentResolver.openOutputStream(target.uri)?.use { output ->
                            source.inputStream().use { input -> input.copyTo(output) }
                        } ?: error("Cannot write ${source.name}")
                    }
                }

                fun copyDocumentToSaf(source: DocumentFile, parent: DocumentFile) {
                    if (source.isDirectory) {
                        val folderName = source.name ?: "Folder"
                        require(parent.findFile(folderName) == null) { "Already exists: $folderName" }
                        val folder = parent.createDirectory(folderName)
                            ?: error("Cannot create folder: $folderName")
                        source.listFiles().forEach { copyDocumentToSaf(it, folder) }
                    } else {
                        val name = source.name ?: "Copied file"
                        require(parent.findFile(name) == null) { "Already exists: $name" }
                        require(parent.findFile(name) == null) { "Already exists: $name" }
                        val target = parent.createFile(source.type ?: "application/octet-stream", name)
                            ?: error("Cannot create file: $name")
                        context.contentResolver.openInputStream(source.uri)?.use { input ->
                            context.contentResolver.openOutputStream(target.uri)?.use { output ->
                                input.copyTo(output)
                            } ?: error("Cannot write $name")
                        } ?: error("Cannot read $name")
                    }
                }

                selectedItems.toList().forEach { key ->
                    try {
                        if (key.startsWith("/")) {
                            val source = File(key)
                            require(source.exists()) { "Source not found: ${source.name}" }
                            copyFileToSaf(source, destination)
                            copied++
                            if (moveMode && !source.deleteRecursively()) {
                                failed++
                            }
                        } else {
                            val source = DocumentFile.fromSingleUri(context, Uri.parse(key))
                                ?: error("Source unavailable")
                            require(source.exists()) { "Source no longer exists" }
                            copyDocumentToSaf(source, destination)
                            copied++
                            if (moveMode && !source.delete()) {
                                failed++
                            }
                        }
                    } catch (_: Exception) {
                        failed++
                    }
                }
                Triple(copied, skipped, failed)
            }.getOrNull()

            message = if (result != null) {
                "${if (moveMode) "Moved" else "Copied"}: ${result.first}, skipped/failed: ${result.second + result.third}"
            } else {
                "Could not access the destination folder."
            }
            selectedItems.clear()
            refresh++
        }
    }

    LaunchedEffect(Unit) {
        val savedUri = context.contentResolver.persistedUriPermissions
            .firstOrNull { it.isReadPermission }?.uri
        if (savedUri != null && currentSafFolder == null) {
            val restored = runCatching {
                DocumentFile.fromTreeUri(context, savedUri)
            }.getOrNull()
            if (restored != null && restored.exists() && restored.isDirectory) {
                currentSafFolder = restored
            }
        }
    }

    fun requestAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            runCatching {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    )
                )
            }.onFailure {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    )
                }
            }
        } else {
            permissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    fun openFile(file: File) {
        val archiveType = when {
            file.name.endsWith(".tar.gz", true) || file.name.endsWith(".tgz", true) -> "TAR.GZ"
            file.name.endsWith(".tar", true) -> "TAR"
            file.name.endsWith(".7z", true) -> "7Z"
            file.name.endsWith(".rar", true) -> "RAR"
            file.name.endsWith(".zip", true) -> "ZIP"
            else -> null
        }
        if (archiveType != null) {
            if (archiveType == "TAR" || archiveType == "TAR.GZ") {
                pendingTar = file
                return
            }
            if (archiveType == "7Z") {
                pending7z = file
                return
            }
            if (archiveType == "RAR") {
                pendingRar = file
                return
            }
            if (archiveType != "ZIP") {
                message = "$archiveType support is being integrated."
                return
            }
            pendingZip = file
            zipEntryNames = runCatching {
                buildList {
                    JavaJavaJavaZipFile(file).use { zip ->
                        val entries = zip.entries()
                        while (entries.hasMoreElements()) {
                            val entry = entries.nextElement()
                            val size = if (entry.isDirectory) "" else
                                " (${android.text.format.Formatter.formatShortFileSize(context, entry.size.coerceAtLeast(0))})"
                            add((if (entry.isDirectory) "📁 " else "📄 ") + entry.name + size)
                        }
                    }
                }
            }.getOrElse {
                listOf("Unable to read ZIP: ${it.message ?: "Invalid archive"}")
            }
            val encrypted = runCatching { Zip4jFile(file).isEncrypted }.getOrDefault(false)
            if (encrypted) {
                showZipPasswordDialog = true
            }
            return
        }
        val mime = when (file.extension.lowercase(Locale.ROOT)) {
            "jpg", "jpeg", "png", "gif", "webp", "bmp" -> "image/*"
            "mp4", "mkv", "avi", "mov", "webm" -> "video/*"
            "mp3", "wav", "m4a", "aac", "ogg", "flac" -> "audio/*"
            "pdf" -> "application/pdf"
            "txt", "log", "csv" -> "text/*"
            "html", "htm" -> "text/html"
            "apk" -> "application/vnd.android.package-archive"
            "zip" -> "application/zip"
            else -> "*/*"
        }
        runCatching {
            val uri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            recordRecentFile(context, file)
            context.startActivity(Intent.createChooser(intent, "Open with"))
        }.onFailure {
            message = "No compatible app found, or Android could not open this file."
        }
    }

    val restricted = currentSafFolder == null &&
        (currentPath.contains("/Android/data") || currentPath.contains("/Android/obb"))

    val internalFiles = remember(
        currentPath, refresh, showHidden, sortBy, searchText, hasAccess
    ) {
        if (!hasAccess || restricted) emptyList()
        else runCatching {
            File(currentPath).listFiles()
                ?.filter { showHidden || !it.name.startsWith(".") }
                ?.filter { searchText.isBlank() || it.name.contains(searchText, true) }
                ?.sortedWith(
                    if (sortBy == "Name") {
                        compareBy<File> { !it.isDirectory }
                            .thenBy { it.name.lowercase(Locale.ROOT) }
                    } else if (sortBy == "Size") {
                        compareBy<File> { !it.isDirectory }
                            .thenByDescending { if (it.isDirectory) 0L else it.length() }
                    } else {
                        compareBy<File> { !it.isDirectory }
                            .thenByDescending { it.lastModified() }
                    }
                ) ?: emptyList()
        }.getOrElse { emptyList() }
    }

    val safFiles = remember(
        currentSafFolder, refresh, showHidden, sortBy, searchText
    ) {
        runCatching {
            currentSafFolder?.listFiles()
                ?.filter { showHidden || !(it.name ?: "").startsWith(".") }
                ?.filter { searchText.isBlank() || (it.name ?: "").contains(searchText, true) }
                ?.sortedWith(
                    if (sortBy == "Name") {
                        compareBy<DocumentFile> { !it.isDirectory }
                            .thenBy { (it.name ?: "").lowercase(Locale.ROOT) }
                    } else if (sortBy == "Size") {
                        compareBy<DocumentFile> { !it.isDirectory }
                            .thenByDescending { it.length() }
                    } else {
                        compareBy<DocumentFile> { !it.isDirectory }
                            .thenByDescending { it.lastModified() }
                    }
                ) ?: emptyList()
        }.getOrElse { emptyList() }
    }

    fun goBack() {
        selectedItems.clear()
        if (currentSafFolder != null) {
            if (safStack.isNotEmpty()) {
                currentSafFolder = safStack.removeAt(safStack.lastIndex)
            } else {
                currentSafFolder = null
            }
        } else {
            val parent = File(currentPath).parentFile
            if (parent != null && parent.absolutePath.startsWith(internalRoot)) {
                currentPath = parent.absolutePath
            }
        }
        searchText = ""
        refresh++
    }

    Box(Modifier.fillMaxSize().background(XBlack)) {
        Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "‹",
                color = XWhite,
                fontSize = 34.sp,
                modifier = Modifier.clickable { goBack() }.padding(end = 12.dp)
            )

            Column(Modifier.weight(1f)) {
                Text(
                    currentSafFolder?.name ?: if (currentPath == internalRoot)
                        "Internal Storage" else File(currentPath).name,
                    color = XWhite,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    currentSafFolder?.uri?.lastPathSegment ?: currentPath,
                    color = XGrey,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Box {
                Text(
                    "Storage ▾",
                    color = XWhite,
                    modifier = Modifier.clickable { storageMenu = true }.padding(8.dp)
                )
                DropdownMenu(
                    expanded = storageMenu,
                    onDismissRequest = { storageMenu = false },
                    containerColor = XPanel
                ) {
                    DropdownMenuItem(
                        text = { Text("Internal Storage", color = XWhite) },
                        onClick = {
                            storageMenu = false
                            currentSafFolder = null
                            safStack.clear()
                            currentPath = internalRoot
                            selectedItems.clear()
                            searchText = ""
                            refresh++
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Choose folder…", color = XWhite) },
                        onClick = {
                            storageMenu = false
                            folderPicker.launch(null)
                        }
                    )
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = searchText,
                onValueChange = { searchText = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("Search this folder") },
                textStyle = LocalTextStyle.current.copy(color = XWhite)
            )
            TextButton(onClick = {
                    gridMode = !gridMode
                    settingsPrefs.edit().putString("layout", if (gridMode) "Grid" else "List").apply()
                    settingsPrefs.edit().putString(
                        "layout", if (gridMode) "Grid" else "List"
                    ).apply()
                }) {
                Text(if (gridMode) "List" else "Grid", color = XWhite)
            }
            Box {
                TextButton(onClick = { toolbarMenu = true }) {
                    Text("⋮", color = XWhite, fontSize = 20.sp)
                }
                DropdownMenu(
                    expanded = toolbarMenu,
                    onDismissRequest = { toolbarMenu = false },
                    containerColor = XPanel
                ) {
                    DropdownMenuItem(
                        text = { Text("Sort: $sortBy", color = XWhite) },
                        onClick = {
                            sortBy = when (sortBy) { "Name" -> "Size"; "Size" -> "Date"; else -> "Name" }
                            settingsPrefs.edit().putString("sorting", sortBy).apply()
                            settingsPrefs.edit().putString("sorting", sortBy).apply()
                            toolbarMenu = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(if (showHidden) "Hide hidden files" else "Show hidden files", color = XWhite) },
                        onClick = {
                            showHidden = !showHidden
                            settingsPrefs.edit().putBoolean("hidden", showHidden).apply()
                            settingsPrefs.edit().putBoolean("hidden", showHidden).apply()
                            toolbarMenu = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Refresh", color = XWhite) },
                        onClick = {
                            refresh++
                            toolbarMenu = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Choose folder…", color = XWhite) },
                        onClick = {
                            toolbarMenu = false
                            folderPicker.launch(null)
                        }
                    )
                }
            }
        }

        if (selectionMode) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("${selectedItems.size} selected", color = XWhite)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    selectedItems.clear()
                    if (currentSafFolder != null) {
                        selectedItems.addAll(safFiles.map { it.uri.toString() })
                    } else {
                        selectedItems.addAll(internalFiles.map { it.absolutePath })
                    }
                }) { Text("Select all", color = XWhite) }
                TextButton(onClick = {
                    val uris = selectedItems.mapNotNull { key ->
                        runCatching {
                            if (key.startsWith("/")) {
                                FileProvider.getUriForFile(
                                    context, "${context.packageName}.fileprovider", File(key)
                                )
                            } else Uri.parse(key)
                        }.getOrNull()
                    }
                    if (uris.isNotEmpty()) {
                        runCatching {
                            val shareIntent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                                type = "*/*"
                                putParcelableArrayListExtra(
                                    Intent.EXTRA_STREAM, ArrayList(uris)
                                )
                                clipData = android.content.ClipData.newUri(
                                    context.contentResolver, "Shared files", uris.first()
                                ).apply {
                                    uris.drop(1).forEach { addItem(android.content.ClipData.Item(it)) }
                                }
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(shareIntent, "Share files"))
                        }.onFailure { message = "Could not share selected files." }
                    }
                }) { Text("Share", color = XWhite) }

                TextButton(onClick = {
                    val details = selectedItems.map { key ->
                        if (key.startsWith("/")) {
                            val f = File(key)
                            "${f.name} • ${if (f.isDirectory) "Folder" else browserSize(f.length())}"
                        } else {
                            val d = DocumentFile.fromSingleUri(context, Uri.parse(key))
                            "${d?.name ?: "Selected item"} • ${d?.type ?: "File"}"
                        }
                    }
                    message = details.joinToString("\\n")
                }) { Text("Info", color = XWhite) }

                TextButton(onClick = {
                    moveMode = false
                    copyDestinationPicker.launch(null)
                }) { Text("Copy", color = XWhite) }

                TextButton(onClick = {
                    moveMode = true
                    copyDestinationPicker.launch(null)
                }) { Text("Move", color = XWhite) }

                TextButton(
                    enabled = selectedItems.size == 1,
                    onClick = {
                        val key = selectedItems.firstOrNull()
                        renameValue = if (key != null && key.startsWith("/")) {
                            File(key).name
                        } else {
                            key?.let { DocumentFile.fromSingleUri(context, Uri.parse(it))?.name } ?: ""
                        }
                        showRenameDialog = true
                    }
                ) { Text("Rename", color = if (selectedItems.size == 1) XWhite else XGrey) }

                TextButton(onClick = {
                    if (settingsPrefs.getBoolean("confirm_delete", true)) {
                        showDeleteConfirm = true
                    } else {
                        var deleted = 0
                        selectedItems.toList().forEach { key ->
                            val success = runCatching {
                                if (key.startsWith("/")) {
                                    File(key).deleteRecursively()
                                } else {
                                    DocumentFile.fromSingleUri(context, Uri.parse(key))?.delete() == true
                                }
                            }.getOrDefault(false)
                            if (success) deleted++
                        }
                        selectedItems.clear()
                        refresh++
                        message = "$deleted item(s) deleted."
                    }
                }) {
                    Text("Delete", color = Color(0xFFFF7777))
                }

                TextButton(onClick = { selectedItems.clear() }) {
                    Text("Clear", color = XGrey)
                }
            }
        }

        HorizontalDivider(color = XLine)

        if (currentSafFolder == null && !hasAccess) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Storage access required", color = XWhite)
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = { requestAccess() }) {
                        Text("Grant access")
                    }
                    TextButton(onClick = { folderPicker.launch(null) }) {
                        Text("Browse a selected folder", color = XGrey)
                    }
                }
            }
        } else if (restricted) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Access restricted by Android for this folder.",
                    color = XGrey
                )
            }
        } else if (currentSafFolder != null) {
            if (safFiles.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("This folder is empty", color = XGrey)
                }
            } else if (gridMode) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(100.dp),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 90.dp)
                ) {
                    if (safStack.isNotEmpty()) {
                        item {
                            ParentFolderRow { goBack() }
                        }
                    }
                    gridItems(safFiles, key = { it.uri.toString() }) { doc ->
                        SafBrowserRow(
                            name = doc.name ?: "Unnamed",
                            showExtensions = settingsPrefs.getBoolean("extensions", true),
                            isDirectory = doc.isDirectory,
                            subtitle = if (doc.isDirectory) {
                                if (settingsPrefs.getBoolean("folder_sizes", false))
                                    browserSize(safFolderSize(doc)) else "Folder"
                            } else browserSize(doc.length()),
                            selected = selectedItems.contains(doc.uri.toString()),
                            grid = true,
                            onLongPress = {
                                if (!selectedItems.contains(doc.uri.toString())) {
                                    selectedItems.add(doc.uri.toString())
                                }
                            }
                        ) {
                            val key = doc.uri.toString()
                            if (selectionMode) {
                                if (selectedItems.contains(key)) selectedItems.remove(key)
                                else selectedItems.add(key)
                            } else if (doc.isDirectory) {
                                currentSafFolder?.let { safStack.add(it) }
                                currentSafFolder = doc
                                searchText = ""
                            } else {
                                runCatching {
                                    val intent = Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(
                                            doc.uri,
                                            context.contentResolver.getType(doc.uri) ?: doc.type ?: "*/*"
                                        )
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(intent, "Open with"))
                                }.onFailure {
                                    message = "No compatible app found to open this file."
                                }
                            }
                        }
                    }
                }
            } else {
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 90.dp)) {
                    if (safStack.isNotEmpty()) item { ParentFolderRow { goBack() } }
                    items(safFiles, key = { it.uri.toString() }) { doc ->
                        SafBrowserRow(
                            name = doc.name ?: "Unnamed",
                            showExtensions = settingsPrefs.getBoolean("extensions", true),
                            isDirectory = doc.isDirectory,
                            subtitle = if (doc.isDirectory) {
                                if (settingsPrefs.getBoolean("folder_sizes", false))
                                    browserSize(safFolderSize(doc)) else "Folder"
                            } else browserSize(doc.length()),
                            selected = selectedItems.contains(doc.uri.toString()),
                            grid = false,
                            onLongPress = {
                                if (!selectedItems.contains(doc.uri.toString())) {
                                    selectedItems.add(doc.uri.toString())
                                }
                            }
                        ) {
                            val key = doc.uri.toString()
                            if (selectionMode) {
                                if (selectedItems.contains(key)) selectedItems.remove(key)
                                else selectedItems.add(key)
                            } else if (doc.isDirectory) {
                                currentSafFolder?.let { safStack.add(it) }
                                currentSafFolder = doc
                                searchText = ""
                            } else {
                                runCatching {
                                    val intent = Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(
                                            doc.uri,
                                            context.contentResolver.getType(doc.uri) ?: doc.type ?: "*/*"
                                        )
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(intent, "Open with"))
                                }.onFailure {
                                    message = "No compatible app found to open this file."
                                }
                            }
                        }
                    }
                }
            }
        } else if (internalFiles.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("This folder is empty", color = XGrey)
            }
        } else if (gridMode) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(100.dp),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 90.dp)
            ) {
                if (currentPath != internalRoot) item { ParentFolderRow { goBack() } }
                gridItems(internalFiles, key = { it.absolutePath }) { file ->
                    BrowserEntry(
                        file = file,
                        folderSizeLabel = if (file.isDirectory && settingsPrefs.getBoolean("folder_sizes", false))
                            browserSize(internalFolderSize(file)) else "Folder",
                        showExtensions = settingsPrefs.getBoolean("extensions", true),
                        selected = selectedItems.contains(file.absolutePath),
                        grid = true,
                        onLongPress = {
                            if (!selectedItems.contains(file.absolutePath)) {
                                selectedItems.add(file.absolutePath)
                            }
                        }
                    ) {
                        if (selectionMode) {
                            if (selectedItems.contains(file.absolutePath)) selectedItems.remove(file.absolutePath)
                            else selectedItems.add(file.absolutePath)
                        } else if (file.isDirectory) {
                            currentPath = file.absolutePath
                            searchText = ""
                        } else openFile(file)
                    }
                }
            }
        } else {
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 90.dp)) {
                if (currentPath != internalRoot) item { ParentFolderRow { goBack() } }
                items(internalFiles, key = { it.absolutePath }) { file ->
                    BrowserEntry(
                        file = file,
                        folderSizeLabel = if (file.isDirectory && settingsPrefs.getBoolean("folder_sizes", false))
                            browserSize(internalFolderSize(file)) else "Folder",
                        showExtensions = settingsPrefs.getBoolean("extensions", true),
                        selected = selectedItems.contains(file.absolutePath),
                        grid = false,
                        onLongPress = {
                            if (!selectedItems.contains(file.absolutePath)) {
                                selectedItems.add(file.absolutePath)
                            }
                        }
                    ) {
                        if (selectionMode) {
                            if (selectedItems.contains(file.absolutePath)) selectedItems.remove(file.absolutePath)
                            else selectedItems.add(file.absolutePath)
                        } else if (file.isDirectory) {
                            currentPath = file.absolutePath
                            searchText = ""
                        } else openFile(file)
                    }
                }
            }
        }
        }

        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
        FloatingActionButton(
            onClick = { plusMenu = true },
            modifier = Modifier.padding(18.dp),
            containerColor = XWhite,
            contentColor = XBlack,
            shape = CircleShape
        ) {
            Text("+", fontSize = 28.sp)
        }
        DropdownMenu(
            expanded = plusMenu,
            onDismissRequest = { plusMenu = false },
            containerColor = XPanel
        ) {
            listOf("New Folder", "New File", "New Archive").forEach { action ->
                DropdownMenuItem(
                    text = { Text(action, color = XWhite) },
                    onClick = {
                        plusMenu = false
                        createType = action.removePrefix("New ")
                        newName = ""
                        showCreateDialog = true
                    }
                )
            }
        }
    }
    }

    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename", color = XWhite) },
            text = {
                OutlinedTextField(
                    value = renameValue,
                    onValueChange = { renameValue = it },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val newLabel = renameValue.trim()
                    val key = selectedItems.firstOrNull()
                    if (newLabel.isNotEmpty() && !newLabel.contains("/") &&
                        !newLabel.contains("\\") && key != null
                    ) {
                        val renamed = runCatching {
                            if (key.startsWith("/")) {
                                val source = File(key)
                                require(source.exists()) { "Source no longer exists" }
                                val target = File(source.parentFile ?: error("Parent unavailable"), newLabel)
                                require(!target.exists()) { "An item with this name already exists" }
                                source.renameTo(target).also {
                                    if (!it) error("Storage provider refused rename")
                                }
                            } else {
                                val source = DocumentFile.fromSingleUri(context, Uri.parse(key))
                                    ?: error("Cannot access selected item")
                                require(source.exists()) { "Source no longer exists" }
                                require(source.parentFile?.findFile(newLabel) == null) {
                                    "An item with this name already exists"
                                }
                                source.renameTo(newLabel).also {
                                    if (!it) error("Storage provider refused rename")
                                }
                            }
                        }.getOrElse {
                            message = it.message ?: "Rename failed"
                            false
                        }
                        message = if (renamed) "Renamed successfully." else "Could not rename this item."
                        selectedItems.clear()
                        refresh++
                    }
                    showRenameDialog = false
                }) { Text("Rename", color = XWhite) }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text("Cancel", color = XGrey)
                }
            },
            containerColor = XPanel
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete selected items?", color = XWhite) },
            text = {
                Text(
                    "This will permanently delete ${selectedItems.size} selected item(s).",
                    color = XWhite
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    var deleted = 0
                    var failed = 0
                    selectedItems.toList().forEach { key ->
                        val success = runCatching {
                            if (key.startsWith("/")) {
                                File(key).deleteRecursively()
                            } else {
                                DocumentFile.fromSingleUri(context, Uri.parse(key))?.delete() == true
                            }
                        }.getOrDefault(false)
                        if (success) deleted++ else failed++
                    }
                    selectedItems.clear()
                    refresh++
                    showDeleteConfirm = false
                    message = "Deleted: $deleted • Failed: $failed"
                }) {
                    Text("Delete", color = Color(0xFFFF7777))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel", color = XGrey)
                }
            },
            containerColor = XPanel
        )
    }

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("New $createType", color = XWhite) },
            text = {
                Column {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        singleLine = true,
                        placeholder = { Text("Enter name") }
                    )
                    if (createType == "Archive") {
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = archivePassword,
                            onValueChange = { archivePassword = it },
                            singleLine = true,
                            placeholder = { Text("ZIP password (optional)") },
                            label = { Text("Password") }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = newName.trim()
                    if (name.isNotEmpty() && !name.contains("/") && !name.contains("\\")) {
                        val result = runCatching {
                            val saf = currentSafFolder
                            if (createType == "Archive") {
                                val archiveName = if (name.endsWith(".zip", true)) name else "$name.zip"
                                require(
                                    if (saf == null) !File(currentPath, archiveName).exists()
                                    else saf.findFile(archiveName) == null
                                ) { "An archive with this name already exists." }

                                if (archivePassword.isNotEmpty()) {
                                    val target = if (saf == null) {
                                        File(currentPath, archiveName).also {
                                            if (it.exists()) error("A file with this name already exists")
                                        }
                                    } else {
                                        File(context.cacheDir, "xfiles_${System.nanoTime()}.zip")
                                    }
                                    val params = net.lingala.zip4j.model.ZipParameters().apply {
                                        isEncryptFiles = true
                                        encryptionMethod = net.lingala.zip4j.model.enums.EncryptionMethod.AES
                                        aesKeyStrength = net.lingala.zip4j.model.enums.AesKeyStrength.KEY_STRENGTH_256
                                    }
                                    val zipFile = Zip4jFile(target, archivePassword.toCharArray())
                                    if (selectedItems.isEmpty()) error("Select at least one file or folder")
                                    val staging = File(context.cacheDir, "xfiles_zip_${System.nanoTime()}")
                                    staging.mkdirs()
                                    try {
                                        fun copyDocument(doc: DocumentFile, target: File) {
                                            if (doc.isDirectory) {
                                                target.mkdirs()
                                                doc.listFiles().forEach { child ->
                                                    copyDocument(child, File(target, child.name ?: "item"))
                                                }
                                            } else {
                                                target.parentFile?.mkdirs()
                                                context.contentResolver.openInputStream(doc.uri)?.use { input ->
                                                    target.outputStream().use { output -> input.copyTo(output) }
                                                } ?: error("Cannot read ${doc.name}")
                                            }
                                        }
                                        selectedItems.forEachIndexed { index, key ->
                                            val item = if (key.startsWith("/")) {
                                                File(key).takeIf { it.exists() } ?: error("Selected file not found")
                                            } else {
                                                val doc = DocumentFile.fromSingleUri(context, Uri.parse(key))
                                                    ?: error("Cannot access selected item")
                                                val staged = File(staging, "${index}_${doc.name ?: "item"}")
                                                copyDocument(doc, staged)
                                                staged
                                            }
                                            if (item.isDirectory) zipFile.addFolder(item, params)
                                            else zipFile.addFile(item, params)
                                        }
                                    } finally {
                                        staging.deleteRecursively()
                                    }
                                    if (saf != null) {
                                        try {
                                            val doc = saf.createFile("application/zip", archiveName)
                                                ?: error("Could not create ZIP in selected folder")
                                            context.contentResolver.openOutputStream(doc.uri)?.use { output ->
                                                target.inputStream().use { input -> input.copyTo(output) }
                                            } ?: error("Could not write ZIP to selected folder")
                                        } finally {
                                            target.delete()
                                        }
                                    }
                                    "Password-protected ZIP created (AES-256)"
                                } else {
                                    val output = if (saf != null) {
                                        val doc = saf.createFile("application/zip", archiveName)
                                            ?: error("Could not create ZIP file")
                                        context.contentResolver.openOutputStream(doc.uri)
                                            ?: error("Could not open ZIP output")
                                    } else {
                                        File(currentPath, archiveName).outputStream()
                                    }

                                    ZipOutputStream(output).use { zip ->
                                    fun addFile(file: File, base: File) {
                                        if (file.isDirectory) {
                                            file.listFiles()?.forEach { addFile(it, base) }
                                        } else if (file.isFile) {
                                            val entryName = file.relativeTo(base).invariantSeparatorsPath
                                            zip.putNextEntry(ZipEntry(entryName))
                                            file.inputStream().use { it.copyTo(zip) }
                                            zip.closeEntry()
                                        }
                                    }

                                    fun addDocument(doc: DocumentFile, prefix: String) {
                                        val entryName = if (prefix.isEmpty()) doc.name ?: "item"
                                            else "$prefix/${doc.name ?: "item"}"
                                        if (doc.isDirectory) {
                                            doc.listFiles().forEach { addDocument(it, entryName) }
                                        } else {
                                            zip.putNextEntry(ZipEntry(entryName))
                                            context.contentResolver.openInputStream(doc.uri)?.use {
                                                it.copyTo(zip)
                                            } ?: error("Cannot read ${doc.name}")
                                            zip.closeEntry()
                                        }
                                    }

                                    selectedItems.toList().forEach { key ->
                                        if (key.startsWith("/")) {
                                            val file = File(key)
                                            addFile(file, file.parentFile ?: file)
                                        } else {
                                            val doc = DocumentFile.fromSingleUri(context, Uri.parse(key))
                                                ?: error("Cannot access selected item")
                                            addDocument(doc, "")
                                        }
                                    }
                                    }
                                    "ZIP archive created"
                                }
                            } else if (saf != null) {
                                when (createType) {
                                    "Folder" -> if (saf.createDirectory(name) != null) "Folder created" else error("Could not create folder")
                                    "File" -> if (saf.createFile("application/octet-stream", name) != null) "File created" else error("Could not create file")
                                    else -> error("Unsupported item")
                                }
                            } else {
                                val target = File(currentPath, name)
                                when (createType) {
                                    "Folder" -> if (target.mkdir()) "Folder created" else error("Could not create folder")
                                    "File" -> if (target.createNewFile()) "File created" else error("Could not create file")
                                    else -> error("Unsupported item")
                                }
                            }
                        }

                        message = result.getOrElse {
                            "Could not create $createType: ${it.message ?: "Unknown error"}"
                        }
                        refresh++
                    }
                    showCreateDialog = false
                    archivePassword = ""
                }) {
                    Text("Create", color = XWhite)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) {
                    Text("Cancel", color = XGrey)
                }
            },
            containerColor = XPanel
        )
    }

    pendingRar?.let { archive ->
        AlertDialog(
            onDismissRequest = { pendingRar = null },
            title = { Text("Extract ${archive.name}?", color = XWhite) },
            text = {
                Text(
                    "Extract RAR contents into the current folder. Existing files will not be overwritten.",
                    color = XWhite
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val result = runCatching {
                        val temp = File(context.cacheDir, "rar_extract_${System.currentTimeMillis()}")
                        temp.mkdirs()
                        try {
                            Junrar.extract(archive, temp)
                            val root = currentSafFolder
                            if (root == null) {
                                val destination = File(currentPath).canonicalFile
                                temp.walkTopDown().filter { it.isFile }.forEach { extracted ->
                                    val relative = extracted.relativeTo(temp)
                                    val output = File(destination, relative.path).canonicalFile
                                    require(output.toPath().startsWith(destination.toPath())) {
                                        "Unsafe RAR entry detected"
                                    }
                                    require(!output.exists()) { "File already exists: ${output.name}" }
                                    output.parentFile?.mkdirs()
                                    extracted.copyTo(output, overwrite = false)
                                }
                                temp.walkTopDown().filter { it.isDirectory && it != temp }.forEach { folder ->
                                    val output = File(destination, folder.relativeTo(temp).path).canonicalFile
                                    require(output.toPath().startsWith(destination.toPath())) { "Unsafe RAR folder" }
                                    output.mkdirs()
                                }
                            } else {
                                temp.walkTopDown().filter { it.isDirectory && it != temp }.forEach { folder ->
                                    val parts = folder.relativeTo(temp).invariantSeparatorsPath.split("/")
                                    var parent = root
                                    parts.forEach { part ->
                                        require(part.isNotBlank() && part != "." && part != "..") { "Unsafe RAR folder" }
                                        parent = parent.findFile(part) ?: parent.createDirectory(part)
                                            ?: error("Cannot create folder: $part")
                                    }
                                }
                                temp.walkTopDown().filter { it.isFile }.forEach { extracted ->
                                    val parts = extracted.relativeTo(temp).invariantSeparatorsPath.split("/")
                                    require(parts.isNotEmpty() && parts.none { it.isBlank() || it == "." || it == ".." }) {
                                        "Unsafe RAR entry detected"
                                    }
                                    var parent = root
                                    parts.dropLast(1).forEach { part ->
                                        parent = parent.findFile(part) ?: parent.createDirectory(part)
                                            ?: error("Cannot create folder: $part")
                                    }
                                    val name = parts.last()
                                    require(parent.findFile(name) == null) { "File already exists: $name" }
                                    val doc = parent.createFile("application/octet-stream", name)
                                        ?: error("Cannot create file: $name")
                                    context.contentResolver.openOutputStream(doc.uri)?.use { output ->
                                        extracted.inputStream().use { input -> input.copyTo(output) }
                                    } ?: error("Cannot write file: $name")
                                }
                            }
                        } finally {
                            temp.deleteRecursively()
                        }
                        "RAR archive extracted successfully"
                    }
                    message = result.getOrElse {
                        "RAR extraction failed: ${it.message ?: "Invalid or unsupported archive"}"
                    }
                    pendingRar = null
                    refresh++
                }) { Text("Extract", color = XWhite) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRar = null }) {
                    Text("Cancel", color = XGrey)
                }
            },
            containerColor = XPanel
        )
    }

    pending7z?.let { archive ->
        AlertDialog(
            onDismissRequest = { pending7z = null },
            title = { Text("Extract ${archive.name}?", color = XWhite) },
            text = {
                Text("Extract 7Z contents into the current folder. Existing files will not be overwritten.", color = XWhite)
            },
            confirmButton = {
                TextButton(onClick = {
                    val result = runCatching {
                        SevenZFile(archive).use { seven ->
                            val root = currentSafFolder
                            val destination = File(currentPath).canonicalFile
                            val buffer = ByteArray(8192)
                            var entry = seven.nextEntry
                            while (entry != null) {
                                val parts = entry.name.replace("\\\\", "/")
                                    .split("/").filter { it.isNotEmpty() }
                                require(parts.isNotEmpty() && parts.none { it == "." || it == ".." }) {
                                    "Unsafe 7Z entry"
                                }
                                if (root == null) {
                                    val output = File(destination, parts.joinToString("/")).canonicalFile
                                    require(output.toPath().startsWith(destination.toPath())) {
                                        "Unsafe 7Z entry"
                                    }
                                    if (entry.isDirectory) output.mkdirs()
                                    else {
                                        output.parentFile?.mkdirs()
                                        require(!output.exists()) { "File already exists: ${output.name}" }
                                        output.outputStream().buffered().use { out ->
                                            while (true) {
                                                val count = seven.read(buffer)
                                                if (count <= 0) break
                                                out.write(buffer, 0, count)
                                            }
                                        }
                                    }
                                } else {
                                    var parent = root
                                    for (part in parts.dropLast(1)) {
                                        parent = parent.findFile(part)
                                            ?: parent.createDirectory(part)
                                            ?: error("Cannot create folder: $part")
                                    }
                                    val last = parts.last()
                                    if (entry.isDirectory) {
                                        if (parent.findFile(last) == null)
                                            parent.createDirectory(last) ?: error("Cannot create folder: $last")
                                    } else {
                                        require(parent.findFile(last) == null) { "File already exists: $last" }
                                        val output = parent.createFile("application/octet-stream", last)
                                            ?: error("Cannot create file: $last")
                                        val stream = context.contentResolver.openOutputStream(output.uri)
                                            ?: error("Cannot write file: $last")
                                        stream.buffered().use { out ->
                                            while (true) {
                                                val count = seven.read(buffer)
                                                if (count <= 0) break
                                                out.write(buffer, 0, count)
                                            }
                                        }
                                    }
                                }
                                entry = seven.nextEntry
                            }
                        }
                        "7Z archive extracted successfully"
                    }
                    message = result.getOrElse {
                        "7Z extraction failed: ${it.message ?: "Invalid archive"}"
                    }
                    pending7z = null
                    refresh++
                }) { Text("Extract", color = XWhite) }
            },
            dismissButton = {
                TextButton(onClick = { pending7z = null }) {
                    Text("Cancel", color = XGrey)
                }
            },
            containerColor = XPanel
        )
    }

    pendingTar?.let { archive ->
        AlertDialog(
            onDismissRequest = { pendingTar = null },
            title = { Text("Extract ${archive.name}?", color = XWhite) },
            text = {
                Text(
                    "Extract TAR contents into the current folder. Existing files will not be overwritten.",
                    color = XWhite
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val result = runCatching {
                        val raw = archive.inputStream().buffered()
                        val input = if (archive.name.endsWith(".gz", true) ||
                            archive.name.endsWith(".tgz", true)) {
                            GzipCompressorInputStream(raw)
                        } else raw

                        TarArchiveInputStream(input).use { tar ->
                            val root = currentSafFolder
                            val destination = File(currentPath).canonicalFile
                            var entry = tar.nextTarEntry
                            while (entry != null) {
                                val parts = entry.name.replace("\\\\", "/")
                                    .split("/").filter { it.isNotEmpty() }
                                require(parts.isNotEmpty() && parts.none { it == "." || it == ".." }) {
                                    "Unsafe TAR entry"
                                }

                                if (root == null) {
                                    val output = File(destination, parts.joinToString("/")).canonicalFile
                                    require(output.toPath().startsWith(destination.toPath())) {
                                        "Unsafe TAR entry"
                                    }
                                    if (entry.isDirectory) output.mkdirs()
                                    else {
                                        output.parentFile?.mkdirs()
                                        require(!output.exists()) { "File already exists: ${output.name}" }
                                        output.outputStream().buffered().use { tar.copyTo(it) }
                                    }
                                } else {
                                    var parent = root
                                    for (part in parts.dropLast(1)) {
                                        parent = parent.findFile(part)
                                            ?: parent.createDirectory(part)
                                            ?: error("Cannot create folder: $part")
                                    }
                                    val last = parts.last()
                                    if (entry.isDirectory) {
                                        if (parent.findFile(last) == null)
                                            parent.createDirectory(last) ?: error("Cannot create folder: $last")
                                    } else {
                                        require(parent.findFile(last) == null) {
                                            "File already exists: $last"
                                        }
                                        val output = parent.createFile("application/octet-stream", last)
                                            ?: error("Cannot create file: $last")
                                        val stream = context.contentResolver.openOutputStream(output.uri)
                                            ?: error("Cannot write file: $last")
                                        stream.buffered().use { tar.copyTo(it) }
                                    }
                                }
                                entry = tar.nextTarEntry
                            }
                        }
                        "TAR archive extracted successfully"
                    }
                    message = result.getOrElse {
                        "TAR extraction failed: ${it.message ?: "Invalid archive"}"
                    }
                    pendingTar = null
                    refresh++
                }) { Text("Extract", color = XWhite) }
            },
            dismissButton = {
                TextButton(onClick = { pendingTar = null }) {
                    Text("Cancel", color = XGrey)
                }
            },
            containerColor = XPanel
        )
    }


    if (showZipPasswordDialog) {
        AlertDialog(
            onDismissRequest = {
                showZipPasswordDialog = false
                zipPassword = ""
                zipPasswordError = ""
            },
            title = { Text("ZIP Password", color = XWhite) },
            text = {
                Column {
                    Text("Enter the password for the encrypted archive.", color = XWhite)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = zipPassword,
                        onValueChange = { zipPassword = it; zipPasswordError = "" },
                        label = { Text("Password") },
                        singleLine = true
                    )
                    if (zipPasswordError.isNotEmpty()) {
                        Text(zipPasswordError, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val archive = pendingZip
                    if (archive != null && zipPassword.isNotEmpty()) {
                        val result = runCatching {
                            val temp = File(context.cacheDir, "zip_password_extract")
                            try {
                                temp.deleteRecursively()
                                check(temp.mkdirs()) { "Cannot create temporary folder" }
                                Zip4jFile(archive, zipPassword.toCharArray()).extractAll(temp.absolutePath)
                                val files = temp.walkTopDown().filter { it.isFile }.toList()
                                val safRoot = currentSafFolder
                                if (safRoot == null) {
                                    val destination = File(currentPath).canonicalFile
                                    files.forEach { source ->
                                        val output = File(destination, source.relativeTo(temp).invariantSeparatorsPath).canonicalFile
                                        require(output.toPath().startsWith(destination.toPath())) { "Unsafe ZIP entry" }
                                        require(!output.exists()) { "Already exists: ${output.name}" }
                                        output.parentFile?.mkdirs()
                                        source.copyTo(output, overwrite = false)
                                    }
                                } else {
                                    files.forEach { source ->
                                        val parts = source.relativeTo(temp).invariantSeparatorsPath.split("/").filter { it.isNotBlank() }
                                        require(parts.isNotEmpty() && parts.none { it == "." || it == ".." }) { "Unsafe ZIP entry" }
                                        var parent = safRoot
                                        parts.dropLast(1).forEach { part ->
                                            parent = parent.findFile(part) ?: parent.createDirectory(part)
                                                ?: error("Cannot create folder: $part")
                                        }
                                        val name = parts.last()
                                        require(parent.findFile(name) == null) { "Already exists: $name" }
                                        val output = parent.createFile("application/octet-stream", name)
                                            ?: error("Cannot create file: $name")
                                        context.contentResolver.openOutputStream(output.uri)?.use { out ->
                                            source.inputStream().use { it.copyTo(out) }
                                        } ?: error("Cannot write file: $name")
                                    }
                                }
                            } finally {
                                temp.deleteRecursively()
                            }
                        }
                        if (result.isSuccess) {
                            message = "Encrypted ZIP extracted successfully"
                            showZipPasswordDialog = false
                            pendingZip = null
                            zipPassword = ""
                            refresh++
                        } else {
                            zipPasswordError = "Wrong password or invalid archive."
                        }
                    }
                }) { Text("Extract", color = XWhite) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showZipPasswordDialog = false
                    zipPassword = ""
                    zipPasswordError = ""
                }) { Text("Cancel", color = XGrey) }
            },
            containerColor = XPanel
        )
    }

    pendingZip?.let { archive ->
        AlertDialog(
            onDismissRequest = { pendingZip = null; zipEntryNames = emptyList() },
            title = { Text("Extract ZIP?", color = XWhite) },
            text = {
                Column {
                    Text(
                        "${archive.name} • ${zipEntryNames.size} items",
                        color = XWhite
                    )
                    Spacer(Modifier.height(8.dp))
                    if (zipEntryNames.isEmpty()) {
                        Text("This ZIP is empty.", color = XGrey)
                    } else {
                        Column(Modifier.heightIn(max = 260.dp)) {
                            zipEntryNames.take(100).forEach { item ->
                                Text(item, color = XWhite, fontSize = 13.sp)
                            }
                            if (zipEntryNames.size > 100) {
                                Text("…and ${zipEntryNames.size - 100} more", color = XGrey)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Extract into the current folder. Existing files will not be overwritten.",
                        color = XGrey
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val result = runCatching {
                        ZipInputStream(archive.inputStream().buffered()).use { zip ->
                            val root = currentSafFolder
                            val destination = File(currentPath).canonicalFile
                            var entry = zip.nextEntry
                            while (entry != null) {
                                val parts = entry.name.replace("\\\\", "/")
                                    .split("/")
                                    .filter { it.isNotEmpty() }
                                require(parts.isNotEmpty() && parts.none { it == "." || it == ".." }) {
                                    "Unsafe ZIP entry detected"
                                }

                                if (root == null) {
                                    val output = File(destination, parts.joinToString("/")).canonicalFile
                                    require(output.toPath().startsWith(destination.toPath())) {
                                        "Unsafe ZIP entry detected"
                                    }
                                    if (entry.isDirectory) {
                                        output.mkdirs()
                                    } else {
                                        output.parentFile?.mkdirs()
                                        require(!output.exists()) { "File already exists: ${output.name}" }
                                        output.outputStream().buffered().use { zip.copyTo(it) }
                                    }
                                } else {
                                    var parent = root
                                    for (part in parts.dropLast(1)) {
                                        parent = parent.findFile(part)
                                            ?: parent.createDirectory(part)
                                            ?: error("Cannot create folder: $part")
                                    }
                                    val last = parts.last()
                                    if (entry.isDirectory) {
                                        if (parent.findFile(last) == null) {
                                            parent.createDirectory(last) ?: error("Cannot create folder: $last")
                                        }
                                    } else {
                                        require(parent.findFile(last) == null) {
                                            "File already exists: $last"
                                        }
                                        val output = parent.createFile("application/octet-stream", last)
                                            ?: error("Cannot create file: $last")
                                        val stream = context.contentResolver.openOutputStream(output.uri)
                                            ?: error("Cannot write file: $last")
                                        stream.buffered().use { zip.copyTo(it) }
                                    }
                                }
                                zip.closeEntry()
                                entry = zip.nextEntry
                            }
                        }
                        "ZIP extracted successfully"
                    }
                    message = result.getOrElse {
                        "Extraction failed: ${it.message ?: "Invalid ZIP file"}"
                    }
                    pendingZip = null
                    zipEntryNames = emptyList()
                    refresh++
                }) {
                    Text("Extract", color = XWhite)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingZip = null; zipEntryNames = emptyList() }) {
                    Text("Cancel", color = XGrey)
                }
            },
            containerColor = XPanel
        )
    }

    message?.let { msg ->
        AlertDialog(
            onDismissRequest = { message = null },
            confirmButton = {
                TextButton(onClick = { message = null }) {
                    Text("OK", color = XWhite)
                }
            },
            text = { Text(msg, color = XWhite) },
            containerColor = XPanel
        )
    }
}

@Composable
private fun SafBrowserRow(
    name: String,
    showExtensions: Boolean,
    isDirectory: Boolean,
    subtitle: String,
    selected: Boolean,
    grid: Boolean,
    onLongPress: () -> Unit,
    onClick: () -> Unit
) {
    val displayName = if (!showExtensions && !isDirectory) {
        val dot = name.lastIndexOf('.')
        if (dot > 0) name.substring(0, dot) else name
    } else name
    if (grid) {
        Column(
            Modifier.fillMaxWidth()
                .background(if (selected) Color(0xFF303030) else XBlack)
                .combinedClickable(onClick = onClick, onLongClick = onLongPress)
                .padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(if (isDirectory) "▰" else "▤", color = XWhite, fontSize = 34.sp)
            Text(displayName, color = XWhite, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    } else {
        Row(
            Modifier.fillMaxWidth()
                .background(if (selected) Color(0xFF303030) else XBlack)
                .combinedClickable(onClick = onClick, onLongClick = onLongPress)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(if (isDirectory) "▰" else "▤", color = XWhite, fontSize = 25.sp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(displayName, color = XWhite, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, color = XGrey, fontSize = 11.sp)
            }
        }
        HorizontalDivider(color = XLine, thickness = 0.5.dp)
    }
}

@Composable
private fun BrowserEntry(
    file: File,
    folderSizeLabel: String,
    showExtensions: Boolean,
    selected: Boolean,
    grid: Boolean,
    onLongPress: () -> Unit,
    onClick: () -> Unit
) {
    val date = remember(file.lastModified()) {
        SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(file.lastModified()))
    }
    val displayName = if (!showExtensions && file.isFile) {
        val dot = file.name.lastIndexOf('.')
        if (dot > 0) file.name.substring(0, dot) else file.name
    } else file.name
    if (grid) {
        Column(
            Modifier.fillMaxWidth()
                .background(if (selected) Color(0xFF303030) else XBlack)
                .combinedClickable(onClick = onClick, onLongClick = onLongPress)
                .padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(if (file.isDirectory) "▰" else "▤", color = XWhite, fontSize = 34.sp)
            Text(displayName, color = XWhite, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    } else {
        Row(
            Modifier.fillMaxWidth()
                .background(if (selected) Color(0xFF303030) else XBlack)
                .combinedClickable(onClick = onClick, onLongClick = onLongPress)
                .padding(horizontal = 16.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(if (file.isDirectory) "▰" else "▤", color = XWhite, fontSize = 27.sp)
            Spacer(Modifier.width(15.dp))
            Column(Modifier.weight(1f)) {
                Text(displayName, color = XWhite, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (file.isDirectory) folderSizeLabel else browserSize(file.length()), color = XGrey, fontSize = 11.sp)
            }
            Text(date, color = XGrey, fontSize = 10.sp)
        }
        HorizontalDivider(color = XLine, thickness = 0.5.dp)
    }
}

@Composable
private fun ParentFolderRow(onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("↰", color = XWhite, fontSize = 25.sp)
        Spacer(Modifier.width(16.dp))
        Text("..", color = XWhite, fontSize = 15.sp)
    }
    HorizontalDivider(color = XLine, thickness = 0.5.dp)
}


private fun internalFolderSize(folder: File): Long {
    return runCatching {
        folder.listFiles()?.sumOf { child ->
            if (child.isDirectory) internalFolderSize(child) else child.length()
        } ?: 0L
    }.getOrDefault(0L)
}

private fun safFolderSize(folder: DocumentFile): Long {
    return runCatching {
        folder.listFiles().sumOf { child ->
            if (child.isDirectory) safFolderSize(child) else child.length()
        }
    }.getOrDefault(0L)
}

private fun browserSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    if (bytes < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
    return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
}
