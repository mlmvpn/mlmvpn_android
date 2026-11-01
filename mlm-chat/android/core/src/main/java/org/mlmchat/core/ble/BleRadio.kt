package org.mlmchat.core.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelUuid
import android.util.Log
import org.mlmchat.core.BleCommand
import org.mlmchat.core.ChatNode
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The Bluetooth LE radio for the core's BLE transport (SPEC 16): advertising our rotating tag,
 * scanning for other Gereh devices, a GATT server for those who connect to us, GATT client links
 * to those the core asks for, and the bytes of each link, split to the MTU, in order.
 *
 * The radio only carries bytes. Who a device is, and everything on a link, is the core's: the
 * SPEC 14.7 session, Noise keyed on the responder's `loc`. So this class needs no secret, and logs
 * kinds and counts only, never an address or a tag.
 *
 * The host app declares the permissions and asks for them (Android 12+: BLUETOOTH_SCAN,
 * BLUETOOTH_ADVERTISE, BLUETOOTH_CONNECT; Android 6 to 11: BLUETOOTH, BLUETOOTH_ADMIN and
 * ACCESS_FINE_LOCATION, which Android itself demands for scanning). [start] returns false when
 * Bluetooth is off or a permission is missing.
 */
@SuppressLint("MissingPermission") // every entry point checks [hasPermissions] first
class BleRadio(context: Context, private val node: ChatNode) {
    companion object {
        private const val TAG = "mlmchat-ble"
        val SERVICE: UUID = UUID.fromString("bbfbe4da-bad5-4b6d-986a-3d77a5519a62")
        val RX: UUID = UUID.fromString("bbfbe4da-bad5-4b6d-986a-3d77a5519a63")
        val TX: UUID = UUID.fromString("bbfbe4da-bad5-4b6d-986a-3d77a5519a64")
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val WANT_MTU = 517
        /** The tag changes every 10 minutes; advertise it fresh at half that. */
        private const val READVERTISE_MS = 5 * 60 * 1000L
        /** Inbound links get ids from here; the core numbers the ones it asks for from 1. */
        private const val INBOUND_BASE = 1_000_000

        fun permissions(): Array<String> = if (Build.VERSION.SDK_INT >= 31) {
            arrayOf("android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_ADVERTISE", "android.permission.BLUETOOTH_CONNECT")
        } else {
            arrayOf("android.permission.ACCESS_FINE_LOCATION")
        }

        fun hasPermissions(context: Context): Boolean =
            permissions().all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }

    private val ctx = context.applicationContext
    private val manager = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?
    private val running = AtomicBoolean(false)
    private val scanning = AtomicBoolean(false)
    private val thread = HandlerThread("gereh-ble").apply { start() }
    private val handler = Handler(thread.looper)
    private var server: BluetoothGattServer? = null
    private var commandThread: Thread? = null
    private val nextInbound = AtomicInteger(INBOUND_BASE)

    /** One link: a GATT client connection we opened, or a device connected to our server. */
    private class Link(val id: Int, val device: BluetoothDevice, val inbound: Boolean) {
        var gatt: BluetoothGatt? = null
        var rx: BluetoothGattCharacteristic? = null
        var mtu = 23
        val out = ArrayDeque<ByteArray>()
        var busy = false
        var open = false
    }

    private val links = ConcurrentHashMap<Int, Link>()
    /** Inbound links by device address. */
    private val inboundByAddr = ConcurrentHashMap<String, Link>()

    // ---- start and stop ----------------------------------------------------------------------

    fun start(): Boolean {
        val adapter = manager?.adapter
        if (adapter == null || !adapter.isEnabled || !hasPermissions(ctx) || adapter.bluetoothLeAdvertiser == null) {
            Log.i(TAG, "not started: bluetooth off, missing, or no permission")
            return false
        }
        if (!running.compareAndSet(false, true)) return true
        node.bleStart()
        handler.post {
            openServer()
            advertise()
            scanning.set(true)
            scan()
        }
        commandThread = Thread({ commands() }, "gereh-ble-cmd").apply { isDaemon = true; start() }
        Log.i(TAG, "started")
        return true
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        handler.post {
            try {
                manager?.adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
                manager?.adapter?.bluetoothLeScanner?.stopScan(scanCallback)
            } catch (_: Throwable) {
            }
            scanning.set(false)
            for (l in links.values) closeLink(l)
            server?.close()
            server = null
        }
        try {
            node.bleStop()
        } catch (_: Throwable) {
        }
        Log.i(TAG, "stopped")
    }

    /** Contacts beside us right now. */
    fun nearby(): Int = try { node.bleNearby() } catch (_: Throwable) { 0 }

    // ---- advertising and scanning ----------------------------------------------------------

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            Log.w(TAG, "advertising failed ($errorCode)")
        }
    }

    private val readvertise = Runnable { advertise() }

    private fun advertise() {
        handler.removeCallbacks(readvertise)
        if (!running.get()) return
        val advertiser = manager?.adapter?.bluetoothLeAdvertiser ?: return
        try {
            advertiser.stopAdvertising(advertiseCallback)
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .setConnectable(true)
                .setTimeout(0)
                .build()
            // the service UUID in the advertisement, the tag in the scan response (31 bytes each)
            val data = AdvertiseData.Builder().setIncludeDeviceName(false).addServiceUuid(ParcelUuid(SERVICE)).build()
            val response = AdvertiseData.Builder().setIncludeDeviceName(false)
                .addServiceData(ParcelUuid(SERVICE), node.bleAdvertTag()).build()
            advertiser.startAdvertising(settings, data, response, advertiseCallback)
        } catch (e: Throwable) {
            Log.w(TAG, "advertising: ${e.javaClass.simpleName}")
        }
        handler.postDelayed(readvertise, READVERTISE_MS)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = seen(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach { seen(it) }
        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "scanning failed ($errorCode)")
        }
    }

    private fun seen(r: ScanResult) {
        val tag = r.scanRecord?.getServiceData(ParcelUuid(SERVICE)) ?: return
        try {
            node.bleSeen(r.device.address, tag)
        } catch (_: Throwable) {
        }
    }

    /**
     * Scan only while it is wanted (the app on screen, or «add nearby» open): a scan all day long
     * kept phones awake (30 Oct 2026). Advertising and the server stay either way, so a phone that
     * scans still finds this one. An app that never calls this keeps scanning, as before.
     */
    fun setScanning(on: Boolean) {
        handler.post {
            if (!running.get()) return@post
            if (on && scanning.compareAndSet(false, true)) {
                scan()
            } else if (!on && scanning.compareAndSet(true, false)) {
                try {
                    manager?.adapter?.bluetoothLeScanner?.stopScan(scanCallback)
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun scan() {
        val scanner = manager?.adapter?.bluetoothLeScanner ?: return
        try {
            val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build()
            scanner.startScan(listOf(filter), settings, scanCallback)
        } catch (e: Throwable) {
            Log.w(TAG, "scan: ${e.javaClass.simpleName}")
        }
    }

    // ---- the core's commands -----------------------------------------------------------------

    private fun commands() {
        while (running.get()) {
            val cmd = try {
                node.bleNext(1000)
            } catch (_: Throwable) {
                null
            } ?: continue
            handler.post {
                when (cmd) {
                    is BleCommand.Connect -> connect(cmd.link, cmd.addr)
                    is BleCommand.Send -> links[cmd.link]?.let { send(it, cmd.data) }
                    is BleCommand.Close -> links[cmd.link]?.let { closeLink(it) }
                    is BleCommand.Advertise -> advertise()
                }
            }
        }
    }

    private fun closed(l: Link) {
        if (links.remove(l.id) != null) {
            if (l.inbound) inboundByAddr.remove(l.device.address)
            try {
                node.bleClosed(l.id)
            } catch (_: Throwable) {
            }
        }
    }

    private fun closeLink(l: Link) {
        try {
            if (l.inbound) server?.cancelConnection(l.device) else l.gatt?.disconnect()
        } catch (_: Throwable) {
        }
        if (!l.inbound) {
            try {
                l.gatt?.close()
            } catch (_: Throwable) {
            }
        }
        closed(l)
    }

    // ---- sending: chunks of MTU - 3, one in flight per link ---------------------------------

    private fun send(l: Link, data: ByteArray) {
        val size = (l.mtu - 3).coerceAtLeast(20)
        var i = 0
        while (i < data.size) {
            val end = minOf(data.size, i + size)
            l.out.add(data.copyOfRange(i, end))
            i = end
        }
        pump(l)
    }

    @Suppress("DEPRECATION")
    private fun pump(l: Link) {
        if (l.busy || !l.open) return
        val chunk = l.out.pollFirst() ?: return
        l.busy = true
        val ok = try {
            if (l.inbound) {
                val tx = server?.getService(SERVICE)?.getCharacteristic(TX)
                if (tx == null) false
                else if (Build.VERSION.SDK_INT >= 33) server?.notifyCharacteristicChanged(l.device, tx, false, chunk) == BluetoothGatt.GATT_SUCCESS
                else {
                    tx.value = chunk
                    server?.notifyCharacteristicChanged(l.device, tx, false) == true
                }
            } else {
                val g = l.gatt
                val rx = l.rx
                if (g == null || rx == null) false
                else if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(rx, chunk, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS
                else {
                    rx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    rx.value = chunk
                    g.writeCharacteristic(rx)
                }
            }
        } catch (_: Throwable) {
            false
        }
        if (!ok) {
            l.busy = false
            closeLink(l)
        }
    }

    private fun sent(l: Link) {
        l.busy = false
        pump(l)
    }

    // ---- client links (we connect) --------------------------------------------------------------

    private fun connect(link: Int, addr: String) {
        val adapter = manager?.adapter
        if (adapter == null || !running.get()) {
            node.bleClosed(link)
            return
        }
        val device = try {
            adapter.getRemoteDevice(addr)
        } catch (_: Throwable) {
            node.bleClosed(link)
            return
        }
        val l = Link(link, device, false)
        links[link] = l
        l.gatt = if (Build.VERSION.SDK_INT >= 23) device.connectGatt(ctx, false, clientCallback(l), BluetoothDevice.TRANSPORT_LE)
        else device.connectGatt(ctx, false, clientCallback(l))
        if (l.gatt == null) closed(l)
    }

    private fun clientCallback(l: Link) = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            handler.post {
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    if (!gatt.requestMtu(WANT_MTU)) gatt.discoverServices()
                } else {
                    try {
                        gatt.close()
                    } catch (_: Throwable) {
                    }
                    closed(l)
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            handler.post {
                if (status == BluetoothGatt.GATT_SUCCESS) l.mtu = mtu
                gatt.discoverServices()
            }
        }

        @Suppress("DEPRECATION")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            handler.post {
                val svc = gatt.getService(SERVICE)
                val rx = svc?.getCharacteristic(RX)
                val tx = svc?.getCharacteristic(TX)
                val cccd = tx?.getDescriptor(CCCD)
                if (rx == null || tx == null || cccd == null || !gatt.setCharacteristicNotification(tx, true)) {
                    closeLink(l)
                    return@post
                }
                l.rx = rx
                val ok = if (Build.VERSION.SDK_INT >= 33) gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothGatt.GATT_SUCCESS
                else {
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(cccd)
                }
                if (!ok) closeLink(l)
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            handler.post {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    closeLink(l)
                    return@post
                }
                l.open = true
                node.bleConnected(l.id, false)
                pump(l)
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            handler.post {
                if (status == BluetoothGatt.GATT_SUCCESS) sent(l) else closeLink(l)
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid == TX) node.bleData(l.id, value)
        }

        @Deprecated("Android 12 and older")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33 && characteristic.uuid == TX) characteristic.value?.let { node.bleData(l.id, it) }
        }
    }

    // ---- the server (others connect to us) -----------------------------------------------------

    private fun openServer() {
        val m = manager ?: return
        val s = m.openGattServer(ctx, serverCallback) ?: return
        val svc = BluetoothGattService(SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val rx = BluetoothGattCharacteristic(RX,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE)
        val tx = BluetoothGattCharacteristic(TX, BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0)
        tx.addDescriptor(BluetoothGattDescriptor(CCCD, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
        svc.addCharacteristic(rx)
        svc.addCharacteristic(tx)
        s.addService(svc)
        server = s
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                handler.post { inboundByAddr[device.address]?.let { closed(it) } }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            handler.post { inboundByAddr[device.address]?.mtu = mtu; pendingMtu[device.address] = mtu }
        }

        override fun onDescriptorWriteRequest(device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?) {
            // the client turned notifications on: the link is open. Made before the answer, so
            // it exists when the client's first bytes (which wait for this answer) come in.
            if (descriptor.uuid == CCCD && inboundByAddr[device.address] == null && running.get()) {
                val l = Link(nextInbound.incrementAndGet(), device, true)
                l.mtu = pendingMtu.remove(device.address) ?: 23
                l.open = true
                links[l.id] = l
                inboundByAddr[device.address] = l
                node.bleConnected(l.id, true)
            }
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        }

        override fun onCharacteristicWriteRequest(device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?) {
            val l = inboundByAddr[device.address]
            if (characteristic.uuid == RX && l != null && value != null) node.bleData(l.id, value)
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            handler.post {
                val l = inboundByAddr[device.address] ?: return@post
                if (status == BluetoothGatt.GATT_SUCCESS) sent(l) else closeLink(l)
            }
        }
    }

    /** MTUs agreed before the client subscribed (the link does not exist yet). */
    private val pendingMtu = ConcurrentHashMap<String, Int>()
}
