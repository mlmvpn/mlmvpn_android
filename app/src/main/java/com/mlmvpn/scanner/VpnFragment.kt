package com.mlmvpn.scanner

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mlmvpn.scanner.data.NodeManager

import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import androidx.lifecycle.lifecycleScope
import java.net.InetSocketAddress
import java.net.Socket

class VpnFragment : Fragment() {
    
    private lateinit var nodeManager: NodeManager
    private lateinit var adapter: VpnNodesAdapter
    private lateinit var recyclerNodes: RecyclerView

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_vpn, container, false)
        nodeManager = NodeManager(requireContext())
        
        recyclerNodes = view.findViewById(R.id.recycler_nodes)
        val btnPingAll = view.findViewById<Button>(R.id.btn_ping_all)
        val btnDeleteAll = view.findViewById<Button>(R.id.btn_delete_all)

        setupRecyclerView(view)

        btnPingAll.setOnClickListener {
            Toast.makeText(context, "Pinging nodes...", Toast.LENGTH_SHORT).show()
            startPingTest()
        }

        btnDeleteAll.setOnClickListener {
            nodeManager.clearAll()
            adapter.updateNodes(nodeManager.nodes)
        }

        return view
    }

    private suspend fun tcpPing(host: String, port: Int): Int = withContext(Dispatchers.IO) {
        try {
            val socket = Socket()
            val start = System.currentTimeMillis()
            socket.connect(InetSocketAddress(host, port), 3000) // 3 seconds timeout
            val end = System.currentTimeMillis()
            socket.close()
            (end - start).toInt()
        } catch (e: Exception) {
            -1
        }
    }

    private fun startPingTest() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val jobs = nodeManager.nodes.mapIndexed { index, node ->
                async {
                    try {
                        val uriStr = node.uri
                        // Simple parse since java.net.URI fails on vless:// sometimes
                        val hostStart = uriStr.indexOf("@") + 1
                        val pathStart = uriStr.indexOf("?", hostStart)
                        val authHostPort = if (pathStart > 0) uriStr.substring(hostStart, pathStart) else uriStr.substring(hostStart)
                        val parts = authHostPort.split(":")
                        
                        var host = parts[0]
                        var port = 443
                        if (parts.size > 1) {
                            // IPv6 could have multiple colons, so we handle it basically
                            val lastColon = authHostPort.lastIndexOf(":")
                            host = authHostPort.substring(0, lastColon).replace("[", "").replace("]", "")
                            port = authHostPort.substring(lastColon + 1).substringBefore("/").toIntOrNull() ?: 443
                        }

                        if (host.isNotEmpty()) {
                            val latency = tcpPing(host, port)
                            node.ping = if (latency >= 0) "${latency}ms" else "Timeout"
                        } else {
                            node.ping = "Error"
                        }
                    } catch (e: Exception) {
                        node.ping = "Error"
                    }
                    
                    withContext(Dispatchers.Main) {
                        adapter.notifyItemChanged(index)
                    }
                }
            }
            jobs.awaitAll()
            nodeManager.saveNodes()
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Ping test complete!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupRecyclerView(view: View) {
        adapter = VpnNodesAdapter(nodeManager.nodes,
            onNodeSelect = { node ->
                Toast.makeText(context, "Selected: ${node.name}", Toast.LENGTH_SHORT).show()
                // The FAB will use the selected node
            },
            onNodeDelete = { node ->
                nodeManager.nodes.remove(node)
                nodeManager.saveNodes()
                adapter.updateNodes(nodeManager.nodes)
            },
            onNodeShare = { node ->
                showShareDialog(node.uri)
            },
            onNodeEdit = { node ->
                showEditNodeDialog(node)
            }
        )
        
        val fabConnect = view.findViewById<com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton>(R.id.fab_connect)
        
        fun updateFabState() {
            if (MyVpnService.isRunning) {
                fabConnect.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#CF6679"))
                fabConnect.setIconResource(android.R.drawable.ic_media_pause)
                fabConnect.text = "Disconnect"
            } else {
                fabConnect.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#8AB4F8"))
                fabConnect.setIconResource(R.drawable.ic_vpn)
                fabConnect.text = "Connect"
            }
        }
        updateFabState()

        val vpnLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                startVpnService()
                updateFabState()
                adapter.notifyDataSetChanged()
            } else {
                Toast.makeText(context, "VPN Permission Denied", Toast.LENGTH_SHORT).show()
            }
        }

        fabConnect.setOnClickListener {
            val selectedId = adapter.selectedNodeId
            
            if (MyVpnService.isRunning) {
                if (selectedId == MyVpnService.connectedNodeId) {
                    // Clicked on the same connected node -> Disconnect
                    val stopIntent = android.content.Intent(requireContext(), MyVpnService::class.java).apply {
                        action = "STOP"
                    }
                    requireContext().startService(stopIntent)
                    MyVpnService.isRunning = false
                    updateFabState()
                    adapter.notifyDataSetChanged()
                    Toast.makeText(context, "VPN Disconnected", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                } else {
                    // Selected a DIFFERENT node while running -> Auto switch
                    val stopIntent = android.content.Intent(requireContext(), MyVpnService::class.java).apply {
                        action = "STOP"
                    }
                    requireContext().startService(stopIntent)
                    MyVpnService.isRunning = false
                    updateFabState()
                    Toast.makeText(context, "Switching node...", Toast.LENGTH_SHORT).show()
                    
                    // Wait a bit and connect to new node
                    fabConnect.postDelayed({
                        val intent = android.net.VpnService.prepare(requireContext())
                        if (intent != null) {
                            vpnLauncher.launch(intent)
                        } else {
                            startVpnService()
                            updateFabState()
                            adapter.notifyDataSetChanged()
                        }
                    }, 800)
                    return@setOnClickListener
                }
            }

            if (selectedId != null) {
                val intent = android.net.VpnService.prepare(requireContext())
                if (intent != null) {
                    vpnLauncher.launch(intent)
                } else {
                    startVpnService()
                    updateFabState()
                    adapter.notifyDataSetChanged()
                }
            } else {
                Toast.makeText(context, "Please select a node first", Toast.LENGTH_SHORT).show()
            }
        }
        
        recyclerNodes.layoutManager = LinearLayoutManager(context)
        recyclerNodes.adapter = adapter
        
        val btnSort = view.findViewById<Button>(R.id.btn_sort)
        btnSort.setOnClickListener {
            // Sort by ping latency. Timeout or "Test" or "Error" will go to bottom.
            val sortedList = nodeManager.nodes.sortedBy { node ->
                val pingVal = node.ping.replace("ms", "").toIntOrNull()
                pingVal ?: 999999
            }
            nodeManager.nodes.clear()
            nodeManager.nodes.addAll(sortedList)
            nodeManager.saveNodes()
            adapter.updateNodes(nodeManager.nodes)
            Toast.makeText(context, "Sorted by Ping", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startVpnService() {
        val selectedId = adapter.selectedNodeId
        val node = nodeManager.nodes.find { it.id == selectedId } ?: return
        
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext())
        val isProxyMode = prefs.getBoolean("proxy_mode", false)
        val localPort = prefs.getString("local_port", "10808")

        val intent = android.content.Intent(requireContext(), MyVpnService::class.java).apply {
            putExtra("NODE_URI", node.uri)
            putExtra("NODE_ID", node.id)
            putExtra("PROXY_MODE", isProxyMode)
            putExtra("LOCAL_PORT", localPort)
        }
        
        requireContext().startService(intent)
        MyVpnService.isRunning = true
        MyVpnService.connectedNodeId = node.id
        adapter.notifyDataSetChanged()
        Toast.makeText(context, "VPN Tunnel Initialized!", Toast.LENGTH_SHORT).show()
    }

    private fun showShareDialog(uri: String) {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_share_node, null)
        val btnCopy = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_copy)
        val btnClose = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_close)
        val ivQr = dialogView.findViewById<android.widget.ImageView>(R.id.iv_qr_code)
        
        btnCopy.setOnClickListener {
            val clipboard = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("VPN Node", uri)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        try {
            val hints = java.util.EnumMap<com.google.zxing.EncodeHintType, Any>(com.google.zxing.EncodeHintType::class.java)
            hints[com.google.zxing.EncodeHintType.MARGIN] = 1
            val writer = com.google.zxing.qrcode.QRCodeWriter()
            val bitMatrix = writer.encode(uri, com.google.zxing.BarcodeFormat.QR_CODE, 512, 512, hints)
            val width = bitMatrix.width
            val height = bitMatrix.height
            val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.RGB_565)
            for (x in 0 until width) {
                for (y in 0 until height) {
                    bitmap.setPixel(x, y, if (bitMatrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                }
            }
            ivQr.setImageBitmap(bitmap)
        } catch (e: Exception) {
            Toast.makeText(context, "QR Code failed", Toast.LENGTH_SHORT).show()
        }

        val dialog = androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .create()
            
        btnClose.setOnClickListener {
            dialog.dismiss()
        }
            
        dialog.show()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
    }

    private fun showEditNodeDialog(node: com.mlmvpn.scanner.models.VpnNode) {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_edit_node, null)
        val config = com.mlmvpn.scanner.utils.VpnConfig.parseUri(node.uri) ?: com.mlmvpn.scanner.utils.VpnConfig(name = node.name)
        
        val etName = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.et_name)
        val etAddress = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.et_address)
        val etPort = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.et_port)
        val etUuid = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.et_uuid)
        val etNetwork = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.et_network)
        val etWsHost = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.et_wshost)
        val etWsPath = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.et_wspath)
        val etTls = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.et_tls)
        val etSni = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.et_sni)
        val etAlpn = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.et_alpn)
        
        etName.setText(config.name)
        etAddress.setText(config.address)
        etPort.setText(config.port.toString())
        etUuid.setText(config.uuid)
        etNetwork.setText(config.network)
        etWsHost.setText(config.wsHost)
        etWsPath.setText(config.wsPath)
        etTls.setText(config.tls)
        etSni.setText(config.sni)
        etAlpn.setText(config.alpn)

        val dialog = androidx.appcompat.app.AlertDialog.Builder(requireContext(), android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setView(dialogView)
            .create()

        dialogView.findViewById<android.widget.ImageView>(R.id.btn_close).setOnClickListener {
            dialog.dismiss()
        }
        
        dialogView.findViewById<android.widget.Button>(R.id.btn_save).setOnClickListener {
            config.name = etName.text.toString().trim()
            config.address = etAddress.text.toString().trim()
            config.port = etPort.text.toString().toIntOrNull() ?: 443
            config.uuid = etUuid.text.toString().trim()
            config.network = etNetwork.text.toString().trim()
            config.wsHost = etWsHost.text.toString().trim()
            config.wsPath = etWsPath.text.toString().trim()
            config.tls = etTls.text.toString().trim()
            config.sni = etSni.text.toString().trim()
            config.alpn = etAlpn.text.toString().trim()
            
            val newUri = config.toUriString()
            
            val index = nodeManager.nodes.indexOf(node)
            if (index != -1) {
                val updatedNode = node.copy(name = config.name, uri = newUri)
                updatedNode.ping = node.ping // preserve ping
                nodeManager.nodes[index] = updatedNode
                nodeManager.saveNodes()
                adapter.updateNodes(nodeManager.nodes)
            }
            dialog.dismiss()
        }
        
        dialog.show()
    }

    override fun onResume() {
        super.onResume()
        // Refresh nodes in case we just added some from the Cloud Tab
        nodeManager = NodeManager(requireContext())
        adapter.updateNodes(nodeManager.nodes)
    }
}
