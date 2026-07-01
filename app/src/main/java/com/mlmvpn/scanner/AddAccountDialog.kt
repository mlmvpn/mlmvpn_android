package com.mlmvpn.scanner

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import com.mlmvpn.scanner.data.CloudManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AddAccountDialog(
    private val cloudManager: CloudManager,
    private val onAccountAdded: () -> Unit
) : DialogFragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.dialog_add_account, container, false)
        
        val inputToken = view.findViewById<EditText>(R.id.input_token)
        val inputEmail = view.findViewById<EditText>(R.id.input_email)
        val btnAdd = view.findViewById<Button>(R.id.btn_add)
        val btnCancel = view.findViewById<Button>(R.id.btn_cancel)

        btnCancel.setOnClickListener {
            dismiss()
        }

        btnAdd.setOnClickListener {
            val token = inputToken.text.toString()
            val email = inputEmail.text.toString()

            if (token.isEmpty()) {
                Toast.makeText(context, "API Token is required", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            btnAdd.isEnabled = false
            btnAdd.text = "Checking..."

            CoroutineScope(Dispatchers.IO).launch {
                val result = cloudManager.addAccount(token, email)
                withContext(Dispatchers.Main) {
                    if (result.first) {
                        Toast.makeText(context, result.second, Toast.LENGTH_LONG).show()
                        onAccountAdded()
                        dismiss()
                    } else {
                        Toast.makeText(context, result.second, Toast.LENGTH_LONG).show()
                        btnAdd.isEnabled = true
                        btnAdd.text = "Add Account"
                    }
                }
            }
        }
        
        return view
    }
}
