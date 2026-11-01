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

@Composable
fun FileBrowserScreen() {
    val context = LocalContext.current
    var currentPath by remember {
        mutableStateOf(Environment.getExternalStorageDirectory().absolutePath)
    }
    var refresh by remember { mutableIntStateOf(0) }
    var gridView by remember { mutableStateOf(false) }
    var sortBy by remember { mutableStateOf("Name") }
    var showHidden by remember { mutableStateOf(true) }
    var searchOpen by remember { mutableStateOf(false) }
    var searchText by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var showNewFolder by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }

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
                            .thenByDescending { it.length() }
                    }
                ) ?: emptyList()
        }.getOrElse { emptyList() }
    }

    val listingFailed = hasAccess && !restricted &&
        File(currentPath).exists() && File(currentPath).listFiles() == null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(XBlack)
    ) {
        // Compact toolbar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "‹",
                color = XWhite,
                fontSize = 34.sp,
                modifier = Modifier
                    .clickable {
                        val parent = File(currentPath).parentFile
                        if (parent != null) {
                            currentPath = parent.absolutePath
                            searchText = ""
                        }
                    }
                    .padding(horizontal = 10.dp)
            )

            Column(Modifier.weight(1f)) {
                Text(
                    File(currentPath).name.ifBlank { "Internal Storage" },
                    color = XWhite,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    currentPath,
                    color = XGrey,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Text(
                "⌕",
                color = XWhite,
                fontSize = 27.sp,
                modifier = Modifier
                    .clickable {
                        searchOpen = !searchOpen
                        if (!searchOpen) searchText = ""
                    }
                    .padding(horizontal = 9.dp)
            )

            Text(
                if (gridView) "☷" else "▦",
                color = XWhite,
                fontSize = 22.sp,
                modifier = Modifier
                    .clickable { gridView = !gridView }
                    .padding(horizontal = 9.dp)
            )

            Box {
                Text(
                    "⋮",
                    color = XWhite,
                    fontSize = 25.sp,
                    modifier = Modifier
                        .clickable { menuOpen = true }
                        .padding(horizontal = 8.dp)
                )
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    containerColor = Color(0xFF202020)
                ) {
                    DropdownMenuItem(
                        text = { Text("Sort by name", color = XWhite) },
                        onClick = {
                            sortBy = "Name"
                            menuOpen = false
                            refresh++
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Sort by size", color = XWhite) },
                        onClick = {
                            sortBy = "Size"
                            menuOpen = false
                            refresh++
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (showHidden) "Hide hidden files" else "Show hidden files",
                                color = XWhite
                            )
                        },
                        onClick = {
                            showHidden = !showHidden
                            menuOpen = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Refresh", color = XWhite) },
                        onClick = {
                            refresh++
                            menuOpen = false
                        }
                    )
                }
            }
        }

        HorizontalDivider(color = XLine)

        if (searchOpen) {
            OutlinedTextField(
                value = searchText,
                onValueChange = { searchText = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                placeholder = { Text("Search in this folder", color = XGrey) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = XWhite,
                    unfocusedTextColor = XWhite,
                    focusedBorderColor = XWhite,
                    unfocusedBorderColor = XLine,
                    cursorColor = XWhite
                )
            )
        }

        if (!hasAccess) {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Storage access needed", color = XWhite, fontSize = 18.sp)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Allow storage access to browse your files.",
                    color = XGrey
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = { requestAccess() }) {
                    Text("Grant Access")
                }
            }
        } else if (restricted || listingFailed) {
            Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Access Restricted", color = XWhite, fontSize = 18.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Android restricts access to this folder on this device.",
                        color = XGrey,
                        fontSize = 13.sp
                    )
                }
            }
        } else {
            // Folder summary and parent directory
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${folder.count { it.isDirectory }} folders  •  ${folder.count { it.isFile }} files",
                    color = XGrey,
                    fontSize = 12.sp
                )
                Spacer(Modifier.weight(1f))
                Text(
                    if (sortBy == "Name") "Name A–Z" else "Size ↓",
                    color = XGrey,
                    fontSize = 11.sp
                )
            }

            LazyColumn(modifier = Modifier.weight(1f)) {
                val parent = File(currentPath).parentFile
                if (parent != null) {
                    item {
                        ParentFolderRow {
                            currentPath = parent.absolutePath
                            searchText = ""
                        }
                    }
                }

                if (folder.isEmpty()) {
                    item {
                        Box(
                            Modifier.fillMaxWidth().padding(top = 70.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("This folder is empty", color = XGrey)
                        }
                    }
                } else if (!gridView) {
                    items(folder, key = { it.absolutePath }) { file ->
                        BrowserListRow(file) {
                            if (file.isDirectory) {
                                if (file.canRead()) {
                                    currentPath = file.absolutePath
                                    searchText = ""
                                } else {
                                    currentPath = file.absolutePath
                                }
                            }
                        }
                    }
                } else {
                    // Grid mode
                    item {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 10000.dp)
                                .padding(horizontal = 8.dp),
                            userScrollEnabled = false
                        ) {
                            gridItems(folder, key = { it.absolutePath }) { file ->
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            if (file.isDirectory) {
                                                currentPath = file.absolutePath
                                                searchText = ""
                                            }
                                        }
                                        .padding(vertical = 16.dp, horizontal = 5.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        if (file.isDirectory) "▰" else "▤",
                                        color = XWhite,
                                        fontSize = 34.sp
                                    )
                                    Text(
                                        file.name,
                                        color = XWhite,
                                        fontSize = 11.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // New folder floating action button
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(end = 18.dp, bottom = 16.dp),
                contentAlignment = Alignment.BottomEnd
            ) {
                FloatingActionButton(
                    onClick = {
                        newFolderName = ""
                        showNewFolder = true
                    },
                    containerColor = XWhite,
                    contentColor = XBlack,
                    shape = CircleShape
                ) {
                    Text("+", fontSize = 28.sp)
                }
            }
        }
    }

    if (showNewFolder) {
        AlertDialog(
            onDismissRequest = { showNewFolder = false },
            title = { Text("New Folder", color = XWhite) },
            text = {
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    singleLine = true,
                    placeholder = { Text("Folder name") }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = newFolderName.trim()
                        if (name.isNotEmpty() && !name.contains("/")) {
                            runCatching {
                                File(currentPath, name).mkdir()
                            }
                            refresh++
                        }
                        showNewFolder = false
                    }
                ) {
                    Text("Create", color = XWhite)
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewFolder = false }) {
                    Text("Cancel", color = XGrey)
                }
            },
            containerColor = Color(0xFF202020)
        )
    }
}

@Composable
private fun ParentFolderRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
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
private fun BrowserListRow(file: File, onClick: () -> Unit) {
    val date = remember(file.lastModified()) {
        SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
            .format(Date(file.lastModified()))
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            if (file.isDirectory) "▰" else "▤",
            color = XWhite,
            fontSize = 27.sp
        )
        Spacer(Modifier.width(15.dp))

        Column(Modifier.weight(1f)) {
            Text(
                file.name,
                color = XWhite,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                if (file.isDirectory) "Folder" else browserSize(file.length()),
                color = XGrey,
                fontSize = 11.sp
            )
        }

        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                date,
                color = XGrey,
                fontSize = 10.sp
            )
            if (file.isDirectory) {
                Text("Folder", color = XGrey, fontSize = 10.sp)
            }
        }
    }
    HorizontalDivider(
        modifier = Modifier.padding(start = 58.dp),
        color = XLine,
        thickness = 0.5.dp
    )
}

private fun browserSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    if (bytes < 1024 * 1024) {
        return String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    }
    if (bytes < 1024L * 1024 * 1024) {
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
    }
    return String.format(
        Locale.US,
        "%.2f GB",
        bytes / (1024.0 * 1024 * 1024)
    )
}
