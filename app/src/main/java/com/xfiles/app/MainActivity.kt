package com.xfiles.app

import android.os.Bundle
import android.os.StatFs
import android.os.Environment
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import kotlinx.coroutines.delay
import java.util.Locale

private val Black = Color(0xFF000000)
private val White = Color(0xFFFFFFFF)
private val Grey = Color(0xFF888888)
private val Card = Color(0xFF191919)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { XFilesApp() }
    }
}

private data class Category(
    val name: String,
    val icon: String
)

private val categories = listOf(
    Category("Images", "▧"),
    Category("Videos", "▶"),
    Category("Audio", "♫"),
    Category("Documents", "▤"),
    Category("APKs", "◇"),
    Category("Downloads", "↓"),
    Category("Archives", "▣"),
    Category("WhatsApp\nStatus", "◉")
)

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f MB", kb / 1024)
    val gb = kb / 1024
    if (gb < 1024) return String.format(Locale.US, "%.2f GB", gb / 1024)
    return String.format(Locale.US, "%.2f TB", gb / 1024)
}

@Composable
fun XFilesApp() {
    var selectedTab by remember { mutableStateOf("Files") }
    var search by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    var dots by remember { mutableIntStateOf(0) }

    val context = LocalContext.current

    LaunchedEffect(scanning) {
        while (scanning) {
            dots = (dots + 1) % 4
            delay(450)
        }
    }

    val storage = remember {
        runCatching {
            val stat = StatFs(Environment.getExternalStorageDirectory().path)
            val total = stat.totalBytes
            val free = stat.availableBytes
            Triple(total, total - free, free)
        }.getOrElse { Triple(0L, 0L, 0L) }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Black,
            surface = Card,
            primary = White,
            onBackground = White,
            onSurface = White
        )
    ) {
        Scaffold(
            containerColor = Black,
            bottomBar = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Black)
                        .navigationBarsPadding()
                        .padding(top = 10.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BottomItem("Files", selectedTab == "Files", true) {
                        selectedTab = "Files"
                    }
                    BottomItem("Browse", selectedTab == "Browse", false) {
                        selectedTab = "Browse"
                    }
                    BottomItem("Tools", selectedTab == "Tools", false) {
                        selectedTab = "Tools"
                    }
                    BottomItem("Settings", selectedTab == "Settings", false) {
                        selectedTab = "Settings"
                    }
                }
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Black)
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Image(
                        painter = painterResource(R.drawable.x_files_logo),
                        contentDescription = "X Files logo",
                        modifier = Modifier.size(43.dp),
                        contentScale = ContentScale.Fit
                    )
                    Text("Files", color = White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                }

                Surface(
                    color = Card,
                    shape = RoundedCornerShape(15.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 17.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("⌕", color = Grey, fontSize = 25.sp)
                        Spacer(Modifier.width(12.dp))
                        BasicTextField(
                            value = search,
                            onValueChange = { search = it },
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(
                                color = White,
                                fontSize = 16.sp
                            ),
                            decorationBox = { inner ->
                                if (search.isEmpty()) {
                                    Text("Search files and folders", color = Grey, fontSize = 16.sp)
                                }
                                inner()
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                Surface(
                    color = Card,
                    shape = RoundedCornerShape(15.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (scanning) "X Scanning" + ".".repeat(dots)
                                else "Total Files",
                                color = White,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                if (scanning) "Please wait"
                                else "Scan to find accessible files",
                                color = Grey,
                                fontSize = 12.sp
                            )
                        }
                        Surface(
                            color = White,
                            shape = RoundedCornerShape(11.dp),
                            modifier = Modifier
                                .width(66.dp)
                                .height(76.dp)
                                .clickable {
                                    if (!scanning) {
                                        Toast.makeText(
                                            context,
                                            "X Scan engine is being added next.",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Image(
                                    painter = painterResource(R.drawable.x_files_logo),
                                    contentDescription = "X Files Scan",
                                    modifier = Modifier.size(32.dp)
                                )
                                Text("Scan", color = Black, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }
                    }
                }

                Surface(
                    color = Card,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(17.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text("Internal Storage", color = White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            StorageValue("Total", formatBytes(storage.first))
                            StorageValue("Used", formatBytes(storage.second))
                            StorageValue("Free", formatBytes(storage.third))
                        }
                        val progress = if (storage.first > 0) storage.second.toFloat() / storage.first else 0f
                        Box(
                            Modifier.fillMaxWidth().height(8.dp)
                                .clip(RoundedCornerShape(50)).background(Color.DarkGray)
                        ) {
                            Box(
                                Modifier.fillMaxWidth(progress.coerceIn(0f, 1f))
                                    .fillMaxHeight().background(White)
                            )
                        }
                    }
                }

                Text("Categories", color = White, fontSize = 21.sp, fontWeight = FontWeight.Bold)

                categories.chunked(3).forEach { rowItems ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        rowItems.forEach { category ->
                            Surface(
                                color = Card,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.weight(1f).height(112.dp)
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Text(category.icon, color = White, fontSize = 29.sp)
                                    Spacer(Modifier.height(5.dp))
                                    Text(
                                        category.name,
                                        color = White,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        lineHeight = 15.sp,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                    )
                                    Text("--", color = Grey, fontSize = 11.sp)
                                }
                            }
                        }
                        if (rowItems.size < 3) {
                            repeat(3 - rowItems.size) {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }

                Text("Recent Files", color = White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Surface(
                    color = Card,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Your recent files will appear here after file access is enabled.",
                        color = Grey,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(16.dp)
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun StorageValue(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = Grey, fontSize = 12.sp)
        Text(value, color = White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun BottomItem(
    label: String,
    selected: Boolean,
    useLogo: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (useLogo) {
            Image(
                painter = painterResource(R.drawable.x_files_logo),
                contentDescription = "Files",
                modifier = Modifier.size(25.dp)
            )
        } else {
            Text(
                when (label) {
                    "Browse" -> "▤"
                    "Tools" -> "⚒"
                    else -> "⚙"
                },
                color = if (selected) White else Grey,
                fontSize = 23.sp
            )
        }
        Text(
            label,
            color = if (selected) White else Grey,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
    }
}
