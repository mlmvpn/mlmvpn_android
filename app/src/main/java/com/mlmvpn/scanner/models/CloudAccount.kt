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

    // Dedicated DNS (per-user ECS-steering resolver worker) fields
    var dnsWorkerUrl: String? = null,
    var dnsKvNamespaceId: String? = null,
    var dnsStatus: String = "idle", // 'idle', 'deployed'

    // GST relay accelerator (Cloudflare Worker that speeds up / stabilizes the
    // Google Apps Script tunnel by relaying the same protocol on the CF edge)
    var gstRelayWorkerUrl: String? = null,
    var gstRelayStatus: String = "idle", // 'idle', 'deployed'

    // Smart Verification fields
    var isEmailVerified: Boolean = true,
    var hasSubdomain: Boolean = false
)
