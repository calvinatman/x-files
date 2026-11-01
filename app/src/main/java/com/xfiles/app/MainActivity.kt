package com.xfiles.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Black = Color(0xFF000000)
private val White = Color(0xFFFFFFFF)
private val Grey = Color(0xFF888888)
private val SurfaceDark = Color(0xFF171717)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            XFilesApp()
        }
    }
}

@Composable
fun XFilesApp() {
    var selectedTab by remember { mutableStateOf("Home") }
    val tabs = listOf("Home", "Files", "Tools", "Settings")

    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Black,
            surface = Black,
            primary = White,
            onBackground = White,
            onSurface = White
        )
    ) {
        Scaffold(
            containerColor = Black,
            bottomBar = {
                NavigationBar(
                    containerColor = Black,
                    contentColor = White
                ) {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = selectedTab == tab,
                            onClick = { selectedTab = tab },
                            icon = {
                                Text(
                                    text = when (tab) {
                                        "Home" -> "⌂"
                                        "Files" -> "▤"
                                        "Tools" -> "⚒"
                                        else -> "⚙"
                                    },
                                    fontSize = 20.sp
                                )
                            },
                            label = { Text(tab, fontSize = 11.sp) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = White,
                                selectedTextColor = White,
                                unselectedIconColor = Grey,
                                unselectedTextColor = Grey,
                                indicatorColor = SurfaceDark
                            )
                        )
                    }
                }
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Black)
                    .padding(padding)
                    .padding(horizontal = 20.dp, vertical = 18.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("𝕏", fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(10.dp))
                    Text("Files", fontSize = 23.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Text("⌕", fontSize = 28.sp)
                }

                Spacer(Modifier.height(28.dp))

                when (selectedTab) {
                    "Home" -> HomeContent()
                    else -> {
                        Text(
                            selectedTab,
                            fontSize = 25.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "This section is under development.",
                            color = Grey,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeContent() {
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Surface(
            color = SurfaceDark,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                "⌕   Search files and folders",
                color = Grey,
                modifier = Modifier.padding(17.dp),
                fontSize = 15.sp
            )
        }

        Surface(
            color = White,
            contentColor = Black,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                "✦   X Scan",
                modifier = Modifier.padding(15.dp),
                fontWeight = FontWeight.SemiBold
            )
        }

        Surface(
            color = SurfaceDark,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(18.dp)) {
                Text("Internal Storage", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(16.dp))
                Text("Total       Used       Free", color = Grey, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                Text("Storage details will appear after scanning.", fontSize = 13.sp)
            }
        }

        Text("Categories", fontSize = 19.sp, fontWeight = FontWeight.Bold)
        Text(
            "Images  •  Videos  •  Audio  •  Documents\nAPKs  •  Downloads  •  Archives  •  WhatsApp Status",
            color = Grey,
            lineHeight = 25.sp,
            fontSize = 13.sp
        )

        Text("Recent Files", fontSize = 19.sp, fontWeight = FontWeight.Bold)
        Text("Your recent files will appear here.", color = Grey, fontSize = 13.sp)
    }
}
