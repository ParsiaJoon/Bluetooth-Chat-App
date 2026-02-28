package com.example.blegossip

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.ParcelUuid
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.nio.charset.Charset
import java.util.UUID

import android.annotation.SuppressLint

class MainActivity : AppCompatActivity() {

    // Pick a random UUID for your app's "channel"
    private val SERVICE_UUID: UUID = UUID.fromString("c0a8012e-3f2b-4b5b-9b8f-2f3a2c9f1d11")
    private val SERVICE_PARCEL = ParcelUuid(SERVICE_UUID)

    private lateinit var etMsg: EditText
    private lateinit var btnBroadcast: Button
    private lateinit var toggleScan: ToggleButton
    private lateinit var tvLog: TextView

    private var btAdapter: BluetoothAdapter? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null

    private var advertising = false

    // Dedup: keep last N message fingerprints
    private val seen = ArrayDeque<String>(200)

    private lateinit var tvMyId: TextView
    private lateinit var etTo: EditText

    private lateinit var myId: String

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
            val denied = perms.filterValues { !it }.keys
            if (denied.isNotEmpty()) log("Permissions denied: $denied")
            else log("Permissions granted.")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvMyId = findViewById(R.id.tvMyId)
        etTo = findViewById(R.id.etTo)
        tvLog = findViewById(R.id.tvLog)
        myId = getOrCreateMyId()
        // Force-visible debug:
        Toast.makeText(this, "My ID: $myId", Toast.LENGTH_LONG).show()
        tvMyId.text = "My ID: $myId"


        etMsg = findViewById(R.id.etMsg)
        btnBroadcast = findViewById(R.id.btnBroadcast)
        toggleScan = findViewById(R.id.toggleScan)


        requestBlePermissions()

        val bm = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        btAdapter = bm.adapter

        if (btAdapter == null) {
            toast("No Bluetooth on this device.")
            finish()
            return
        }

        if (!btAdapter!!.isEnabled) {
            toast("Enable Bluetooth first.")
        }

        advertiser = btAdapter!!.bluetoothLeAdvertiser
        scanner = btAdapter!!.bluetoothLeScanner

        btnBroadcast.setOnClickListener {
            requestBlePermissions()

            val msg = etMsg.text.toString().trim()
            if (msg.isBlank()) return@setOnClickListener

            val toInput = etTo.text.toString()
            val toId = normalizeToId(toInput)  // null means broadcast

            val safeMsg = msg.take(12)

            if (toId == null) {
                // Broadcast
                startOrUpdateAdvertising(makePayload(type = 'B', toId = "FFFFFFFF", message = safeMsg))
            } else {
                // Direct message
                startOrUpdateAdvertising(makePayload(type = 'D', toId = toId, message = safeMsg))
            }
        }

        toggleScan.setOnCheckedChangeListener { _, isChecked ->
            requestBlePermissions()
            if (isChecked) startScanning() else stopScanning()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopScanning()
        stopAdvertising()
    }

    private fun requestBlePermissions() {
        val needed = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!hasPerm(Manifest.permission.BLUETOOTH_SCAN)) needed += Manifest.permission.BLUETOOTH_SCAN
            if (!hasPerm(Manifest.permission.BLUETOOTH_ADVERTISE)) needed += Manifest.permission.BLUETOOTH_ADVERTISE
            if (!hasPerm(Manifest.permission.BLUETOOTH_CONNECT)) needed += Manifest.permission.BLUETOOTH_CONNECT

            // Fallback for some Android 15/16 + OEM builds that still gate scan results
            if (!hasPerm(Manifest.permission.ACCESS_FINE_LOCATION)) needed += Manifest.permission.ACCESS_FINE_LOCATION
        } else {
            if (!hasPerm(Manifest.permission.ACCESS_FINE_LOCATION)) needed += Manifest.permission.ACCESS_FINE_LOCATION
        }

        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
        else log("All required permissions already granted.")
    }

    private fun hasPerm(p: String): Boolean =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    // --- Advertising ---

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            advertising = true
            log("Advertising ON (service=$SERVICE_UUID)")
        }

        override fun onStartFailure(errorCode: Int) {
            advertising = false
            log("Advertising FAILED (code=$errorCode). Try: toggle Bluetooth, close other BLE apps.")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startOrUpdateAdvertising(payload: ByteArray) {
        if (!canAdvertise()) {
            log("Cannot advertise: BLUETOOTH_ADVERTISE permission missing")
            return
        }

        val adv = advertiser ?: run {
            log("No BLE advertiser available on this device.")
            return
        }

        stopAdvertising()

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .build()

        val manufacturerId = 0xFFFF

        val advertiseData = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addManufacturerData(manufacturerId, payload) // <-- use the parameter
            .build()

        adv.startAdvertising(settings, advertiseData, advertiseCallback)
        log("Broadcasting packet (${payload.size} bytes)")
    }
    private fun canScan(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || hasPerm(Manifest.permission.BLUETOOTH_SCAN)

    private fun canAdvertise(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || hasPerm(Manifest.permission.BLUETOOTH_ADVERTISE)

    private fun canConnect(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || hasPerm(Manifest.permission.BLUETOOTH_CONNECT)

    @SuppressLint("MissingPermission")
    private fun stopAdvertising() {
        if (!canAdvertise()) return

        if (advertising) {
            advertiser?.stopAdvertising(advertiseCallback)
            advertising = false
            log("Advertising OFF")
        }
    }

    // --- Scanning ---

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val record = result.scanRecord ?: return
            val manufacturerId = 0xFFFF
            val bytes = record.getManufacturerSpecificData(manufacturerId) ?: return
            if (bytes.size < 13) return

            if (bytes[0] != 'B'.code.toByte() || bytes[1] != 'G'.code.toByte() ||
                bytes[2] != 'O'.code.toByte() || bytes[3] != 'S'.code.toByte()
            ) return

            val type = bytes[4].toInt().toChar()
            val toId = String(bytes, 5, 8, Charsets.US_ASCII)
            val msg = try { String(bytes, 13, bytes.size - 13, Charsets.UTF_8) } catch (_: Exception) { return }

            val shouldShow = (type == 'B') || (type == 'D' && toId == myId)
            if (!shouldShow) return

            val addr = if (canConnect()) result.device?.address else "unknown"
            val fp = "$type|$toId|$msg|$addr"
            if (seen.contains(fp)) return
            seen.addLast(fp)
            if (seen.size > 200) seen.removeFirst()

            log(if (type == 'D') "DM: \"$msg\"" else "RECV: \"$msg\"")
        }

        override fun onScanFailed(errorCode: Int) {
            log("Scan FAILED (code=$errorCode).")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScanning() {
        if (!canScan()) {
            log("Cannot scan: BLUETOOTH_SCAN permission missing")
            return
        }

        val sc = scanner
        if (sc == null) {
            log("No BLE scanner available.")
            return
        }

        val filter = ScanFilter.Builder()
            .setServiceUuid(SERVICE_PARCEL)
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
            .build()
        log("Starting scan... canScan=${canScan()}")
        sc.startScan(null, settings, scanCallback)
        log("Scanning ON (service=$SERVICE_UUID)")
    }

    @SuppressLint("MissingPermission")
    private fun stopScanning() {
        if (!canScan()) return

        scanner?.stopScan(scanCallback)
        log("Scanning OFF")
    }

    // --- UI helpers ---

    private fun log(s: String) {
        runOnUiThread { tvLog.append(s + "\n") }
    }

    private fun toast(s: String) {
        runOnUiThread { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }
    }

    private fun getOrCreateMyId(): String {
        val prefs = getSharedPreferences("blegossip", MODE_PRIVATE)
        val existing = prefs.getString("my_id", null)
        if (existing != null && existing.length == 8) return existing

        val newId = (1..8)
            .map { "0123456789ABCDEF".random() }
            .joinToString("")

        prefs.edit().putString("my_id", newId).apply()
        return newId
    }

    private fun normalizeToId(input: String): String? {
        val s = input.trim().uppercase()
        if (s.isBlank()) return null
        if (s.length != 8) return null
        if (!s.all { it in "0123456789ABCDEF" }) return null
        return s
    }

    private fun makePayload(type: Char, toId: String, message: String): ByteArray {
        val magic = byteArrayOf('B'.code.toByte(), 'G'.code.toByte(), 'O'.code.toByte(), 'S'.code.toByte())
        val t = type.code.toByte()
        val toBytes = toId.toByteArray(Charsets.US_ASCII) // 8 bytes
        val msgBytes = message.toByteArray(Charsets.UTF_8)
        return magic + byteArrayOf(t) + toBytes + msgBytes
    }
}