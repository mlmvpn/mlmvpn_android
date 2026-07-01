package com.mlmvpn.scanner

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.mlmvpn.scanner.databinding.FragmentCloudBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CloudFragment : Fragment() {

    private var _binding: FragmentCloudBinding? = null
    private val binding get() = _binding!!

    private lateinit var cloudManager: com.mlmvpn.scanner.data.CloudManager
    private lateinit var adapter: CloudAccountsAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCloudBinding.inflate(inflater, container, false)
        cloudManager = com.mlmvpn.scanner.data.CloudManager(requireContext())
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        setupRecyclerView()

        binding.fabAddAccount.setOnClickListener {
            val dialog = AddAccountDialog(cloudManager) {
                // This callback runs when an account is successfully added
                adapter.notifyDataSetChanged()
            }
            dialog.show(parentFragmentManager, "AddAccountDialog")
        }
    }

    private fun setupRecyclerView() {
        adapter = CloudAccountsAdapter(
            cloudManager.accounts,
            onDeployClick = { account, holder ->
                holder.setButtonsEnabled(false)
                holder.progressLayout.visibility = View.VISIBLE
                holder.progressBar.progress = 0
                holder.tvProgressLabel.text = "Initializing deploy..."

                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    val res = cloudManager.deployWorker(account) { percent, msg ->
                        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                            holder.progressBar.progress = percent
                            holder.tvProgressLabel.text = msg
                        }
                    }
                    withContext(Dispatchers.Main) {
                        holder.setButtonsEnabled(true)
                        if (res.first) {
                            holder.tvProgressLabel.text = "Deployed Successfully!"
                            holder.tvProgressLabel.setTextColor(android.graphics.Color.parseColor("#0DFF9E"))
                            Toast.makeText(context, "Deploy Complete!", Toast.LENGTH_LONG).show()
                        } else {
                            holder.tvProgressLabel.text = "Failed!"
                            holder.tvProgressLabel.setTextColor(android.graphics.Color.parseColor("#FF5555"))
                            // Show full error
                            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                                .setTitle("Deployment Failed")
                                .setMessage(res.second)
                                .setPositiveButton("OK", null)
                                .show()
                        }
                    }
                }
            },
            onUsageClick = { account, holder ->
                if (holder.usageLayout.visibility == View.VISIBLE) {
                    holder.usageLayout.visibility = View.GONE
                } else {
                    holder.usageLayout.visibility = View.VISIBLE
                    holder.tvUsageCount.text = "Loading..."
                    holder.usageProgress.isIndeterminate = true
                    
                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                        val res = cloudManager.getUsage(account)
                        withContext(Dispatchers.Main) {
                            holder.usageProgress.isIndeterminate = false
                            if (res.first) {
                                val requests = res.second.toLongOrNull() ?: 0L
                                val formatted = java.text.NumberFormat.getNumberInstance(java.util.Locale.US).format(requests)
                                holder.tvUsageCount.text = "$formatted / 100,000"
                                holder.usageProgress.progress = minOf(requests.toInt(), 100000)
                            } else {
                                holder.tvUsageCount.text = res.second // Show the exact error
                                holder.usageProgress.progress = 0
                            }
                        }
                    }
                }
            },
            onNodesClick = { account ->
                Toast.makeText(context, "Fetching nodes...", Toast.LENGTH_SHORT).show()
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    val res = cloudManager.fetchCloudConfigs(account)
                    withContext(Dispatchers.Main) {
                        if (res.first && res.second.isNotEmpty()) {
                            val nodeManager = com.mlmvpn.scanner.data.NodeManager(requireContext())
                            nodeManager.addConfigs(res.second)
                            Toast.makeText(context, "Found ${res.second.size} nodes! Saved to VPN tab.", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(context, "Failed to fetch nodes. Make sure to Deploy first.", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        )
        binding.accountsRecycler.layoutManager = LinearLayoutManager(context)
        binding.accountsRecycler.adapter = adapter
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
