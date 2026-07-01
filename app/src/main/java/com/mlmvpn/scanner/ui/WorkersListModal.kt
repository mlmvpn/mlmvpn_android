package com.mlmvpn.scanner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.data.CloudManager
import com.mlmvpn.scanner.models.CloudAccount
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkersListModal(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val cloudManager = remember { CloudManager(context) }
    val accounts by cloudManager.accountsFlow.collectAsState()
    
    val bgColor = Color(0xFF202124)
    val primaryColor = Color(0xFF8AB4F8)
    
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = bgColor
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top Bar
            Surface(
                color = Color(0xFF2D2E31),
                modifier = Modifier.fillMaxWidth(),
                shadowElevation = 4.dp
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Dns, contentDescription = null, tint = primaryColor)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            stringResource(R.string.workers_list_title),
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.Gray)
                    }
                }
            }

            // List
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(bottom = 100.dp)
            ) {
                items(accounts) { account ->
                    AccountWorkersCard(account = account, cloudManager = cloudManager)
                }
                if (accounts.isEmpty()) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            Text(stringResource(R.string.cloud_no_account_connected), color = Color.Gray)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AccountWorkersCard(account: CloudAccount, cloudManager: CloudManager) {
    var workers by remember { mutableStateOf<List<String>?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var showDeleteConfirm by remember { mutableStateOf<String?>(null) }
    var showDeleteAllConfirm by remember { mutableStateOf(false) }

    fun fetchWorkers() {
        isLoading = true
        error = null
        scope.launch {
            val result = cloudManager.getAllWorkers(account)
            if (result.first) {
                workers = result.second
            } else {
                error = result.second.firstOrNull() ?: context.getString(R.string.workers_fetch_failed)
            }
            isLoading = false
        }
    }

    LaunchedEffect(account) {
        fetchWorkers()
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2D2E31)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(account.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(account.email.ifEmpty { "Token Auth" }, color = Color.Gray, fontSize = 12.sp)
                }
                
                if (!workers.isNullOrEmpty()) {
                    TextButton(onClick = { showDeleteAllConfirm = true }) {
                        Icon(Icons.Default.Delete, contentDescription = null, tint = Color(0xFFE57373), modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.workers_delete_all), color = Color(0xFFE57373), fontSize = 12.sp)
                    }
                }
            }

            Divider(modifier = Modifier.padding(vertical = 12.dp), color = Color(0xFF3C4043))

            // Body
            if (isLoading) {
                Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color(0xFF8AB4F8), modifier = Modifier.size(24.dp))
                }
            } else if (error != null) {
                Text(error ?: "", color = Color(0xFFE57373), fontSize = 14.sp)
            } else if (workers.isNullOrEmpty()) {
                Text(stringResource(R.string.workers_list_empty), color = Color.Gray, fontSize = 14.sp)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    workers?.forEach { workerName ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF1E1E1E), RoundedCornerShape(8.dp))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(workerName, color = Color(0xFFE8EAED), fontSize = 14.sp)
                            IconButton(
                                onClick = { showDeleteConfirm = workerName },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.Gray, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDeleteConfirm != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            containerColor = Color(0xFF2D2E31),
            title = { Text(stringResource(R.string.workers_delete_confirm_title), color = Color.White) },
            text = { Text(stringResource(R.string.workers_delete_confirm_desc) + "\n\n$showDeleteConfirm", color = Color.LightGray) },
            confirmButton = {
                TextButton(onClick = {
                    val target = showDeleteConfirm!!
                    showDeleteConfirm = null
                    scope.launch {
                        val res = cloudManager.deleteWorker(account, target)
                        if (res.first) {
                            android.widget.Toast.makeText(context, context.getString(R.string.workers_delete_success), android.widget.Toast.LENGTH_SHORT).show()
                            fetchWorkers()
                        } else {
                            android.widget.Toast.makeText(context, res.second, android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                }) {
                    Text(stringResource(R.string.node_delete), color = Color(0xFFE57373))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = null }) {
                    Text(stringResource(R.string.common_cancel), color = Color(0xFF8AB4F8))
                }
            }
        )
    }

    if (showDeleteAllConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteAllConfirm = false },
            containerColor = Color(0xFF2D2E31),
            title = { Text(stringResource(R.string.workers_delete_confirm_title), color = Color.White) },
            text = { Text(stringResource(R.string.workers_delete_all_confirm_desc), color = Color.LightGray) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteAllConfirm = false
                    val targets = workers?.toList() ?: emptyList()
                    scope.launch {
                        var hasError = false
                        for (w in targets) {
                            val res = cloudManager.deleteWorker(account, w)
                            if (!res.first) hasError = true
                        }
                        if (hasError) {
                            android.widget.Toast.makeText(context, "Some deletions failed", android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            android.widget.Toast.makeText(context, context.getString(R.string.workers_delete_success), android.widget.Toast.LENGTH_SHORT).show()
                        }
                        fetchWorkers()
                    }
                }) {
                    Text(stringResource(R.string.node_delete), color = Color(0xFFE57373))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAllConfirm = false }) {
                    Text(stringResource(R.string.common_cancel), color = Color(0xFF8AB4F8))
                }
            }
        )
    }
}
