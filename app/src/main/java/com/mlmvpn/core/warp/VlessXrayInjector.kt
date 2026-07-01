package com.mlmvpn.core.warp

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import java.security.SecureRandom
import kotlinx.coroutines.delay

class VlessXrayInjector(private val vpnFd: Int) : IVpnEngine {
    private var coreController: CoreController? = null

    private fun copyAssetsToFilesDir(context: Context) {
        val filesToCopy = listOf("geosite.dat", "geoip.dat")
        for (filename in filesToCopy) {
            val destFile = java.io.File(context.filesDir, filename)
            if (!destFile.exists() || destFile.length() < 1000) {
                try {
                    Log.d("VlessXrayInjector", "Copying $filename to ${destFile.absolutePath}")
                    context.assets.open(filename).use { inputStream ->
                        java.io.FileOutputStream(destFile).use { outputStream ->
                            val buffer = ByteArray(4096)
                            var read: Int
                            while (inputStream.read(buffer).also { read = it } != -1) {
                                outputStream.write(buffer, 0, read)
                            }
                            outputStream.flush()
                        }
                    }
                    Log.d("VlessXrayInjector", "Successfully copied $filename, size: ${destFile.length()}")
                } catch (e: Exception) {
                    Log.e("VlessXrayInjector", "Failed to copy asset $filename", e)
                }
            }
        }
    }

    override suspend fun start(context: Context, config: String, localPort: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            val keyBytes = ByteArray(32)
            SecureRandom().nextBytes(keyBytes)
            val flags = Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
            val xudpBaseKey = Base64.encodeToString(keyBytes, flags)
            
            try {
                copyAssetsToFilesDir(context)
                Libv2ray.initCoreEnv(context.filesDir.absolutePath, xudpBaseKey)
            } catch (e: Exception) {
                // Ignore if already initialized
            }

            val handler = object : CoreCallbackHandler {
                override fun onEmitStatus(status: Long, msg: String): Long {
                    Log.d("VlessXrayInjector", "Status: $status - $msg")
                    return 0
                }
                override fun shutdown(): Long {
                    Log.d("VlessXrayInjector", "Core shutdown")
                    return 0
                }
                override fun startup(): Long {
                    Log.d("VlessXrayInjector", "Core startup")
                    return 0
                }
            }

            coreController = Libv2ray.newCoreController(handler)
            // Log the JSON config for debugging
            Log.d("VlessXrayInjector", "VPN JSON Config:\n$config")
            coreController?.startLoop(config, vpnFd)
            
            delay(1000)
            return@withContext true
        } catch (e: Exception) {
            Log.e("VlessXrayInjector", "Failed to start Xray", e)
            return@withContext false
        }
    }

    override fun stop() {
        try {
            coreController?.stopLoop()
            coreController = null
        } catch (e: Exception) {
            Log.e("VlessXrayInjector", "Error stopping", e)
        }
    }
}
