package com.mlmvpn.scanner

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.mlmvpn.scanner.models.VpnNode

import android.widget.ImageView
import android.widget.LinearLayout
import com.google.android.material.card.MaterialCardView

class VpnNodesAdapter(
    private var nodes: List<VpnNode>,
    private val onNodeSelect: (VpnNode) -> Unit,
    private val onNodeDelete: (VpnNode) -> Unit,
    private val onNodeShare: (VpnNode) -> Unit,
    private val onNodeEdit: (VpnNode) -> Unit
) : RecyclerView.Adapter<VpnNodesAdapter.ViewHolder>() {

    var selectedNodeId: String? = null

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val llNodeContainer: LinearLayout = view.findViewById(R.id.ll_node_container)
        val indicatorActive: View = view.findViewById(R.id.indicator_active)
        val tvName: TextView = view.findViewById(R.id.tv_node_name)
        val tvNodeInitial: TextView = view.findViewById(R.id.tv_node_initial)
        val tvAddress: TextView = view.findViewById(R.id.tv_node_address)
        val tvType: TextView = view.findViewById(R.id.tv_node_type)
        val tvPing: TextView = view.findViewById(R.id.tv_ping)
        val btnShare: ImageView = view.findViewById(R.id.btn_share)
        val btnEdit: ImageView = view.findViewById(R.id.btn_edit)
        val btnDelete: ImageView = view.findViewById(R.id.btn_delete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_vpn_node, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val node = nodes[position]
        
        val isDefaultConfig = node.id.startsWith("default_mlmvpn")
        
        if (isDefaultConfig) {
            holder.tvName.text = "${node.name} \uD83C\uDDEE\uD83C\uDDF7"
            holder.tvType.text = "\uD83C\uDDEE\uD83C\uDDF7 MLMVPN"
        } else {
            holder.tvName.text = node.name
            var protocol = node.type.uppercase()
            var network = "ws" // BPB usually uses ws
            if (node.uri.contains("type=grpc")) network = "grpc"
            holder.tvType.text = "$protocol / $network"
        }
        
        // Extract IP/Port
        val uriSummary = try {
            val uriStr = node.uri
            val hostStart = uriStr.indexOf("@") + 1
            val pathStart = uriStr.indexOf("?", hostStart)
            val authHostPort = if (pathStart > 0) uriStr.substring(hostStart, pathStart) else uriStr.substring(hostStart)
            val parts = authHostPort.split(":")
            
            var host = parts[0]
            var port = 443
            if (parts.size > 1) {
                val lastColon = authHostPort.lastIndexOf(":")
                host = authHostPort.substring(0, lastColon).replace("[", "").replace("]", "")
                port = authHostPort.substring(lastColon + 1).substringBefore("/").toIntOrNull() ?: 443
            }
            "$port : $host"
        } catch (e: Exception) { "Unknown Address" }
        holder.tvAddress.text = uriSummary
        
        holder.tvPing.text = node.ping
        
        if (isDefaultConfig) {
            holder.tvNodeInitial.text = ""
            holder.tvNodeInitial.setBackgroundResource(R.mipmap.ic_launcher)
        } else {
            holder.tvNodeInitial.text = node.name.take(1).uppercase()
            holder.tvNodeInitial.background = null
        }
        
        // Coloring ping text based on value (already done via chip, but let's override text color if needed)
        val pingVal = node.ping.replace("ms", "").toIntOrNull()
        if (pingVal != null) {
            if (pingVal < 150) holder.tvPing.setTextColor(android.graphics.Color.parseColor("#1A3525")) // Darker green for chip
            else if (pingVal < 300) holder.tvPing.setTextColor(android.graphics.Color.parseColor("#3B1020")) // warning
            else holder.tvPing.setTextColor(android.graphics.Color.parseColor("#CF6679"))
        } else {
            holder.tvPing.setTextColor(android.graphics.Color.parseColor("#1A3525"))
        }

        val isSelected = node.id == selectedNodeId
        val isConnected = node.id == MyVpnService.connectedNodeId && MyVpnService.isRunning
        
        holder.indicatorActive.visibility = if (isSelected || isConnected) View.VISIBLE else View.INVISIBLE
        if (isConnected) {
            holder.indicatorActive.setBackgroundColor(android.graphics.Color.parseColor("#81C995")) // connected color = Green
            holder.llNodeContainer.setBackgroundColor(android.graphics.Color.parseColor("#1A3525")) // Green bg
        } else {
            holder.indicatorActive.setBackgroundColor(android.graphics.Color.parseColor("#8AB4F8")) // default active = Blue
            holder.llNodeContainer.setBackgroundColor(if (isSelected) android.graphics.Color.parseColor("#1A3050") else android.graphics.Color.TRANSPARENT)
        }

        holder.llNodeContainer.setOnClickListener {
            val prevSelected = selectedNodeId
            selectedNodeId = node.id
            notifyItemChanged(nodes.indexOfFirst { it.id == prevSelected })
            notifyItemChanged(position)
            onNodeSelect(node)
        }
        
        if (isDefaultConfig) {
            holder.btnShare.visibility = View.GONE
            holder.btnDelete.visibility = View.GONE
            holder.btnEdit.visibility = View.GONE
        } else {
            holder.btnShare.visibility = View.VISIBLE
            holder.btnEdit.visibility = View.VISIBLE
            holder.btnDelete.visibility = View.VISIBLE
            holder.btnEdit.setImageResource(android.R.drawable.ic_menu_edit)
            holder.btnShare.setOnClickListener { onNodeShare(node) }
            holder.btnDelete.setOnClickListener { onNodeDelete(node) }
            holder.btnEdit.setOnClickListener { onNodeEdit(node) }
        }
    }

    override fun getItemCount() = nodes.size

    fun updateNodes(newNodes: List<VpnNode>) {
        this.nodes = newNodes
        notifyDataSetChanged()
    }
}
