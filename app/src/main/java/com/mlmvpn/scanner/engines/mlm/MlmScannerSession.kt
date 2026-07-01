package com.mlmvpn.scanner.engines.mlm

import com.mlmvpn.scanner.models.CloudAccount

object MlmScannerSession {
    var activeAccount: CloudAccount? = null
    var activeUser: MlmUser? = null

    fun start(account: CloudAccount, user: MlmUser) {
        activeAccount = account
        activeUser = user
    }

    fun clear() {
        activeAccount = null
        activeUser = null
    }
}
