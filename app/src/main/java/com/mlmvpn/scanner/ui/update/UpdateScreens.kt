package com.mlmvpn.scanner.ui.update

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.mlmvpn.scanner.update.UpdateChecker
import kotlinx.coroutines.launch
import java.util.Locale

private val bgColor = Color(0xFF121212)
private val surfaceColor = Color(0xFF202124)
private val primaryColor = Color(0xFF8AB4F8)
private val textColor = Color(0xFFE8EAED)
private val mutedColor = Color(0xFF9AA0A6)
private val borderColor = Color(0xFF3C4043)

private fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "?"
    val mb = bytes / 1024.0 / 1024.0
    return String.format(Locale.US, "%.1f مگابایت", mb)
}

@Composable
fun UpdateAvailableDialog(
    info: UpdateChecker.UpdateInfo,
    onDismiss: () -> Unit,
    onDownloadClick: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = surfaceColor,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.SystemUpdate, contentDescription = null, tint = primaryColor)
                    Spacer(Modifier.width(8.dp))
                    Text("نسخه جدید منتشر شد", color = textColor, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "بستن", tint = mutedColor)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row {
                    Text("نسخه: ", color = mutedColor, fontSize = 14.sp)
                    Text(info.versionName, color = primaryColor, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(Modifier.width(16.dp))
                    Text("حجم: ", color = mutedColor, fontSize = 14.sp)
                    Text(formatSize(info.apkSizeBytes), color = textColor, fontSize = 14.sp)
                }
                Spacer(Modifier.height(14.dp))
                Text("موارد اضافه‌شده:", color = mutedColor, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                        .verticalScroll(rememberScrollState())
                        .background(bgColor, RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    if (info.changelog.isEmpty()) {
                        Text("—", color = mutedColor, fontSize = 13.sp)
                    } else {
                        info.changelog.forEach { line ->
                            Row(modifier = Modifier.padding(vertical = 3.dp)) {
                                Text("•  ", color = primaryColor, fontSize = 13.sp)
                                Text(line, color = textColor, fontSize = 13.sp, lineHeight = 19.sp)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = onDownloadClick,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = primaryColor)
                ) {
                    Text("دانلود نسخه جدید", color = Color(0xFF202124), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun UpdateDownloadScreen(
    info: UpdateChecker.UpdateInfo,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val progress by UpdateChecker.downloadProgressFlow.collectAsState()
    var errorText by remember { mutableStateOf<String?>(null) }
    val isDownloading = progress != null && progress!! < 100

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, enabled = !isDownloading) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "برگشت", tint = if (isDownloading) mutedColor else textColor)
                }
                Spacer(Modifier.width(4.dp))
                Text("بروزرسانی اپلیکیشن", color = textColor, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }

            Spacer(Modifier.weight(1f))

            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Icon(
                    Icons.Default.SystemUpdate,
                    contentDescription = null,
                    tint = primaryColor,
                    modifier = Modifier.size(64.dp)
                )
                Spacer(Modifier.height(16.dp))
                Text("نسخه ${info.versionName}", color = textColor, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(formatSize(info.apkSizeBytes), color = mutedColor, fontSize = 14.sp)

                Spacer(Modifier.height(32.dp))

                val pct = progress ?: 0
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .background(surfaceColor, RoundedCornerShape(6.dp))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction = (pct / 100f).coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .background(primaryColor, RoundedCornerShape(6.dp))
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    if (progress == null) "" else "$pct٪",
                    color = primaryColor,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )

                errorText?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, color = Color(0xFFF28B82), fontSize = 13.sp)
                }
            }

            Spacer(Modifier.weight(1f))

            Button(
                onClick = {
                    errorText = null
                    scope.launch {
                        UpdateChecker.downloadAndInstall(context, info) { err ->
                            errorText = err
                        }
                    }
                },
                enabled = !isDownloading,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = primaryColor,
                    disabledContainerColor = borderColor
                )
            ) {
                Text(
                    if (isDownloading) "در حال دانلود..." else "دانلود",
                    color = Color(0xFF202124),
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }
        }
    }
}
