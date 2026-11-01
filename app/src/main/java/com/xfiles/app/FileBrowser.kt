package com.xfiles.app

import android.Manifest
import android.app.Activity
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
import androidx.compose.foundation.shape.RoundedCornerShape
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

private val BrowserWhite = Color.White
private val BrowserGrey = Color(0xFF888888)
private val BrowserCard = Color(0xFF191919)

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
                    context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
        } else {
            permissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    LaunchedEffect(refresh) {
        hasAccess = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    val folder = remember(currentPath, refresh, showHidden, sortBy, hasAccess) {
        if (!hasAccess) emptyList()
        else runCatching {
            File(currentPath).listFiles()
                ?.filter { showHidden || !it.name.startsWith(".") }
                ?.sortedWith(
                    if (sortBy == "Name") compareBy<File> { !it.isDirectory }
                        .thenBy { it.name.lowercase(Locale.ROOT) }
                    else compareBy<File> { !it.isDirectory }
                        .thenByDescending { it.length() }
                ) ?: emptyList()
        }.getOrElse { emptyList() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            "File Manager",
            color = BrowserWhite,
            fontSize = 25.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(12.dp))

        if (!hasAccess) {
            Surface(
                color = BrowserCard,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(18.dp)) {
                    Text("Storage access needed", color = BrowserWhite, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Allow storage access to browse files and folders on your device.",
                        color = BrowserGrey,
                        fontSize = 13.sp
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { requestAccess() }) {
                        Text("Grant Access")
                    }
                }
            }
            return@Column
        }

        Surface(
            color = BrowserCard,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(12.dp)) {
                Text("Storage Path", color = BrowserGrey, fontSize = 11.sp)
                Text(
                    currentPath,
                    color = BrowserWhite,
                    fontSize = 13.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Name",
                        color = BrowserGrey,
                        fontSize = 12.sp
                    )
                    TextButton(onClick = { sortBy = "Name" }) {
                        Text(if (sortBy == "Name") "Name A–Z ✓" else "Name A–Z")
                    }
                    TextButton(onClick = { sortBy = "Size" }) {
                        Text(if (sortBy == "Size") "Size ✓" else "Size")
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { gridView = !gridView }) {
                        Text(if (gridView) "List" else "Grid")
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Show hidden files", color = BrowserGrey, fontSize = 12.sp)
                    Spacer(Modifier.weight(1f))
                    Switch(checked = showHidden, onCheckedChange = { showHidden = it })
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "${folder.count { it.isDirectory }} folders  •  ${folder.count { it.isFile }} files",
                color = BrowserGrey,
                fontSize = 12.sp
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { refresh++ }) {
                Text("Refresh")
            }
            TextButton(
                onClick = {
                    val parent = File(currentPath).parentFile
                    if (parent != null && parent.canRead()) currentPath = parent.absolutePath
                }
            ) {
                Text("Back")
            }
        }

        if (folder.isEmpty()) {
            Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("This folder is empty", color = BrowserGrey)
            }
        } else if (!gridView) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(folder, key = { it.absolutePath }) { file ->
                    FileRow(file) {
                        if (file.isDirectory && file.canRead()) {
                            currentPath = file.absolutePath
                        }
                    }
                }
            }
        } else {
            androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(2),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(folder.size) { index ->
                    val file = folder[index]
                    Surface(
                        color = BrowserCard,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (file.isDirectory && file.canRead()) currentPath = file.absolutePath
                            }
                    ) {
                        Column(
                            Modifier.padding(14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(if (file.isDirectory) "📁" else "📄", fontSize = 30.sp)
                            Text(
                                file.name,
                                color = BrowserWhite,
                                fontSize = 12.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                if (file.isDirectory) "Folder" else browserSize(file.length()),
                                color = BrowserGrey,
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FileRow(file: File, onClick: () -> Unit) {
    val date = remember(file.lastModified()) {
        SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(file.lastModified()))
    }
    Surface(
        color = BrowserCard,
        shape = RoundedCornerShape(11.dp),
        modifier = Modifier.fillMaxWidth().clickable { onClick() }
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(if (file.isDirectory) "📁" else "📄", fontSize = 24.sp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    file.name,
                    color = BrowserWhite,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    if (file.isDirectory) "Folder" else "${browserSize(file.length())}  •  $date",
                    color = BrowserGrey,
                    fontSize = 11.sp
                )
            }
            if (file.isDirectory) Text("›", color = BrowserGrey, fontSize = 24.sp)
        }
    }
}

private fun browserSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    if (bytes < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
    return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
}
