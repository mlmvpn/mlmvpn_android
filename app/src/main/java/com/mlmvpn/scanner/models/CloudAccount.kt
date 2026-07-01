package com.mlmvpn.scanner.models

data class CloudAccount(
    val id: String,
    val token: String,
    val email: String,
    val name: String,
    val accountId: String,
    var status: String, // 'active', 'deployed'
    val addedAt: String,
    // BPB Engine fields
    var workerUrl: String? = null,
    var uuid: String? = null,
    var trPass: String? = null,
    var subPath: String? = null,
    // EDG (Edgetunnel) Engine fields
    var edgWorkerUrl: String? = null,
    var edgUuid: String? = null,
    var edgAdminPass: String? = null,
    var edgKvNamespaceId: String? = null,
    var edgStatus: String = "idle", // 'idle', 'deployed'
    
    // Nahan Engine fields
    var nahanWorkerUrl: String? = null,
    var nahanDbId: String? = null,
    var nahanApiRoute: String = "sync",
    var nahanMasterKey: String? = null,
    var nahanStatus: String = "idle", // 'idle', 'deployed'

    // MLM Engine fields
    var mlmWorkerUrl: String? = null,
    var mlmDbId: String? = null,
    var mlmAdminPassword: String? = null,
    var mlmStatus: String = "idle", // 'idle', 'deployed'

    // Smart Verification fields
    var isEmailVerified: Boolean = true,
    var hasSubdomain: Boolean = false
)
