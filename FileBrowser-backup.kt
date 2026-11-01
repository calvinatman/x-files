package com.xfiles.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val XWhite = Color.White
private val XGrey = Color(0xFF929292)
private val XLine = Color(0xFF292929)
private val XBlack = Color.Black
private val XPanel = Color(0xFF202020)

@Composable
fun FileBrowserScreen() {
    val context = LocalContext.current
    val internalRoot = remember {
        Environment.getExternalStorageDirectory().absolutePath
    }

    var currentPath by remember { mutableStateOf(internalRoot) }
    var selectedTreeUri by remember { mutableStateOf<Uri?>(null) }
    var currentSafFolder by remember { mutableStateOf<DocumentFile?>(null) }
    val safFolderStack = remember { mutableStateListOf<DocumentFile>() }
    var refresh by remember { mutableIntStateOf(0) }
    var sortBy by remember { mutableStateOf("Name") }
    var showHidden by remember { mutableStateOf(true) }
    var gridView by remember { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) }
    var searchText by remember { mutableStateOf("") }
    var storageMenu by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var plusMenu by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var createType by remember { mutableStateOf("Folder") }
    var newName by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
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

    LaunchedEffect(Unit) {
        if (currentSafFolder == null) {
            val savedUri = context.contentResolver.persistedUriPermissions
                .firstOrNull { it.isReadPermission }
                ?.uri
            if (savedUri != null) {
                val restored = runCatching {
                    DocumentFile.fromTreeUri(context, savedUri)
                }.getOrNull()
                if (restored != null && restored.exists() && restored.isDirectory) {
                    selectedTreeUri = savedUri
                    currentSafFolder = restored
                }
            }
        }
    }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val pickedFolder = runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                DocumentFile.fromTreeUri(context, uri)
            }.getOrNull()

            if (pickedFolder != null && pickedFolder.exists() && pickedFolder.isDirectory) {
                selectedTreeUri = uri
                currentSafFolder = pickedFolder
                safFolderStack.clear()
                currentPath = internalRoot
                searchText = ""
                refresh++
            } else {
                message = "Could not access this folder. Please choose it again and allow access."
            }
        }
    }

    fun requestAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            val intent = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
            runCatching { context.startActivity(intent) }
                .onFailure {
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
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Open with"))
        }.onFailure {
            message = "No compatible app found, or Android could not open this file."
        }
    }

    LaunchedEffect(refresh) {
        hasAccess = if (Build.VERSION.SDK_INT >= 30) {
            Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    val restricted = currentPath.contains("/Android/data") ||
        currentPath.contains("/Android/obb")

    val folder = remember(currentPath, refresh, showHidden, sortBy, hasAccess, searchText) {
        if (!hasAccess || restricted) emptyList()
        else runCatching {
            File(currentPath).listFiles()
                ?.filter { showHidden || !it.name.startsWith(".") }
                ?.filter { searchText.isBlank() || it.name.contains(searchText, true) }
                ?.sortedWith(
                    if (sortBy == "Name") {
                        compareBy<File> { !it.isDirectory }
                            .thenBy { it.name.lowercase(Locale.ROOT) }
                    } else {
                        compareBy<File> { !it.isDirectory }
                            .thenByDescending { if (it.isDirectory) 0L else it.length() }
                    }
                ) ?: emptyList()
        }.getOrElse { emptyList() }
    }

    val listingFailed = hasAccess && !restricted &&
        File(currentPath).exists() && File(currentPath).listFiles() == null

    val safEntries = remember(currentSafFolder, refresh, showHidden, sortBy, searchText) {
        runCatching {
            currentSafFolder?.listFiles()
                ?.filter { showHidden || !(it.name ?: "").startsWith(".") }
                ?.filter { searchText.isBlank() || (it.name ?: "").contains(searchText, true) }
                ?.sortedWith(
                    if (sortBy == "Name") compareBy<DocumentFile> { !it.isDirectory }
                        .thenBy { (it.name ?: "").lowercase(Locale.ROOT) }
                    else compareBy<DocumentFile> { !it.isDirectory }
                        .thenByDescending { it.length() }
                ) ?: emptyList()
        }.getOrElse { emptyList() }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(XBlack)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "‹",
                color = XWhite,
                fontSize = 34.sp,
                modifier = Modifier.clickable {
                    if (currentSafFolder != null) {
                        if (safFolderStack.isNotEmpty()) {
                            item {
                                ParentFolderRow {
                                    currentSafFolder = safFolderStack.removeAt(safFolderStack.lastIndex)
                                    searchText = ""
                                }
                            }
                        }
                        if (safEntries.isEmpty()) {
                            item {
                                Box(Modifier.fillMaxWidth().padding(top = 70.dp), contentAlignment = Alignment.Center) {
                                    Text("This folder is empty", color = XGrey)
                                }
                            }
                        } else {
                            items(safEntries, key = { it.uri.toString() }) { doc ->
                                SafBrowserRow(doc, gridView, onLongPress = { selectedItems.add(doc.uri.toString()) }) {
                                    if (doc.isDirectory) {
                                        currentSafFolder?.let { safFolderStack.add(it) }
                                        currentSafFolder = doc
                                        searchText = ""
                                    } else {
                                        runCatching {
                                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                                setDataAndType(doc.uri, doc.type ?: "*/*")
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                            context.startActivity(Intent.createChooser(intent, "Open with"))
                                        }.onFailure { message = "No compatible app found to open this file." }
                                    }
                                }
                            }
                        }
                    } else {
                        val parent = File(currentPath).parentFile
                        if (parent != null && parent.absolutePath.startsWith(internalRoot)) {
                            item {
                                ParentFolderRow {
                                    currentPath = parent.absolutePath
                                    searchText = ""
                                }
                            }
                        }
                        if (folder.isEmpty()) {
                            item {
                                Box(Modifier.fillMaxWidth().padding(top = 70.dp), contentAlignment = Alignment.Center) {
                                    Text("This folder is empty", color = XGrey)
                                }
                            }
                        } else {
                            items(folder, key = { it.absolutePath }) { file ->
                                if (gridView) {
                                    BrowserGridRow(file, onLongPress = { selectedItems.add(file.absolutePath) }) {
                                        if (file.isDirectory) {
                                            currentPath = file.absolutePath
                                            searchText = ""
                                        } else openFile(file)
                                    }
                                } else {
                                    BrowserListRow(file, onLongPress = { selectedItems.add(file.absolutePath) }) {
                                        if (file.isDirectory) {
                                            currentPath = file.absolutePath
                                            searchText = ""
                                        } else openFile(file)
                                    }
                                }
                            }
                        }
                    }
                }

                Box(
                    Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = 16.dp)
                ) {
                    FloatingActionButton(
                        onClick = { plusMenu = true },
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
        }
    }

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("New $createType", color = XWhite) },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    placeholder = {
                        Text(
                            when (createType) {
                                "Folder" -> "Folder name"
                                "File" -> "File name, e.g. notes.txt"
                                else -> "Archive name, e.g. backup.zip"
                            }
                        )
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = newName.trim()
                    if (name.isNotEmpty() && !name.contains("/") && !name.contains("\\")) {
                        val result = runCatching {
                            val safFolder = currentSafFolder
                            if (safFolder != null) {
                                when (createType) {
                                    "Folder" -> safFolder.createDirectory(name) != null
                                    "File" -> safFolder.createFile(
                                        "application/octet-stream", name
                                    ) != null
                                    else -> false
                                }
                            } else {
                                val target = File(currentPath, name)
                                when (createType) {
                                    "Folder" -> target.mkdir()
                                    "File" -> target.createNewFile()
                                    else -> false
                                }
                            }
                        }.getOrDefault(false)
                        message = when {
                            result -> "$createType created"
                            createType == "Archive" -> "Archive creation will be added with archive support."
                            else -> "Could not create $createType"
                        }
                        refresh++
                        currentSafFolder?.listFiles()
                    }
                    showCreateDialog = false
                }) { Text("Create", color = XWhite) }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) {
                    Text("Cancel", color = XGrey)
                }
            },
            containerColor = XPanel
        )
    }

    message?.let {
        AlertDialog(
            onDismissRequest = { message = null },
            confirmButton = {
                TextButton(onClick = { message = null }) {
                    Text("OK", color = XWhite)
                }
            },
            text = { Text(it, color = XWhite) },
            containerColor = XPanel
        )
    }
}

@Composable
private fun SafBrowserRow(
    document: DocumentFile,
    gridView: Boolean,
    onLongPress: () -> Unit,
    onClick: () -> Unit
) {
    if (gridView) {
        Column(
            Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongPress)
                .padding(horizontal = 6.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(if (document.isDirectory) "▰" else "▤", color = XWhite, fontSize = 34.sp)
            Text(
                document.name ?: "Unnamed",
                color = XWhite,
                fontSize = 11.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    } else {
        Row(
            Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongPress)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(if (document.isDirectory) "▰" else "▤", color = XWhite, fontSize = 23.sp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(document.name ?: "Unnamed", color = XWhite, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (document.isDirectory) "Folder" else
                        "${android.text.format.Formatter.formatFileSize(
                            androidx.compose.ui.platform.LocalContext.current,
                            document.length()
                        )} • ${document.type ?: "File"}",
                    color = XGrey,
                    fontSize = 11.sp
                )
            }
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

@Composable
private fun BrowserListRow(file: File, onLongPress: () -> Unit, onClick: () -> Unit) {
    val date = remember(file.lastModified()) {
        SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(file.lastModified()))
    }
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongPress)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(if (file.isDirectory) "▰" else "▤", color = XWhite, fontSize = 27.sp)
        Spacer(Modifier.width(15.dp))
        Column(Modifier.weight(1f)) {
            Text(file.name, color = XWhite, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (file.isDirectory) "Folder" else browserSize(file.length()), color = XGrey, fontSize = 11.sp)
        }
        Spacer(Modifier.width(8.dp))
        Text(date, color = XGrey, fontSize = 10.sp)
    }
    HorizontalDivider(Modifier.padding(start = 58.dp), color = XLine, thickness = 0.5.dp)
}

@Composable
private fun BrowserGridRow(file: File, onLongPress: () -> Unit, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongPress).padding(horizontal = 6.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(if (file.isDirectory) "▰" else "▤", color = XWhite, fontSize = 34.sp)
        Text(
            file.name,
            color = XWhite,
            fontSize = 11.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun browserSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    if (bytes < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
    return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
}
