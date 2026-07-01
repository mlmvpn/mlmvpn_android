package com.mlmvpn.core.warp

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object WarpHandshakeTester {
    
    /**
     * Tests a specific endpoint by sending a Native UDP WireGuard Initiation packet.
     * This is used for the manual "Ping" button in the UI.
     */
    suspend fun testEndpoint(
        context: Context,
        endpointIp: String,
        endpointPort: Int,
        account: WarpAccountManager.WarpAccountData,
        reserved: List<Int>,
        localPort: Int // Not used anymore
    ): Int? = withContext(Dispatchers.IO) {
        return@withContext WarpBatchTester.testWarpEndpoint(endpointIp, endpointPort, reserved)
    }
}
