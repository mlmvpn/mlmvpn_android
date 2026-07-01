package com.mlmvpn.scanner

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.mlmvpn.scanner.models.CloudAccount

import android.widget.LinearLayout
import android.widget.ProgressBar

class CloudAccountsAdapter(
    private val accounts: List<CloudAccount>,
    private val onDeployClick: (CloudAccount, ViewHolder) -> Unit,
    private val onUsageClick: (CloudAccount, ViewHolder) -> Unit,
    private val onNodesClick: (CloudAccount) -> Unit
) : RecyclerView.Adapter<CloudAccountsAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tv_account_name)
        val tvEmail: TextView = view.findViewById(R.id.tv_email)
        val tvStatus: TextView = view.findViewById(R.id.tv_status)
        
        val btnDeploy: Button = view.findViewById(R.id.btn_deploy)
        val btnUsage: Button = view.findViewById(R.id.btn_usage)
        val btnNodes: Button = view.findViewById(R.id.btn_nodes)
        
        val progressLayout: LinearLayout = view.findViewById(R.id.progress_layout)
        val tvProgressLabel: TextView = view.findViewById(R.id.tv_progress_label)
        val progressBar: ProgressBar = view.findViewById(R.id.progress_bar)
        
        val usageLayout: LinearLayout = view.findViewById(R.id.usage_layout)
        val tvUsageCount: TextView = view.findViewById(R.id.tv_usage_count)
        val usageProgress: ProgressBar = view.findViewById(R.id.usage_progress)
        
        fun setButtonsEnabled(enabled: Boolean) {
            btnDeploy.isEnabled = enabled
            btnUsage.isEnabled = enabled
            btnNodes.isEnabled = enabled
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_cloud_account, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val account = accounts[position]
        holder.tvName.text = account.name
        holder.tvEmail.text = account.email.ifEmpty { "Token Auth" }
        holder.tvStatus.text = account.status.uppercase()
        
        // Reset state
        holder.progressLayout.visibility = View.GONE
        holder.usageLayout.visibility = View.GONE
        holder.setButtonsEnabled(true)
        
        holder.btnDeploy.setOnClickListener { onDeployClick(account, holder) }
        holder.btnUsage.setOnClickListener { onUsageClick(account, holder) }
        holder.btnNodes.setOnClickListener { onNodesClick(account) }
    }

    override fun getItemCount() = accounts.size
}
