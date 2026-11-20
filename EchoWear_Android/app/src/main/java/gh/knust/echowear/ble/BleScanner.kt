package gh.knust.echowear.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One wearable seen in a scan, before we know anything beyond its
 * advertisement.
 *
 * The firmware advertises as "EchoWear-XXXX" where XXXX is the serial
 * suffix, so a carer can tell three wearables apart in the list without the
 * app having to connect to each one in turn just to read its identity.
 */
data class DiscoveredDevice(
    val address: String,
    val advertisedName: String,
    val rssi: Int,
    /** True when the EchoWear service UUID was actually in the advertisement. */
    val serviceMatched: Boolean = false,
    /**
     * True when this one did not come from a scan at all, but from the
     * phone's list of bonded devices - see BleScanner.bondedCandidates().
     */
    val bonded: Boolean = false,
    val lastSeen: Long = System.currentTimeMillis(),
) {
    /** "EchoWear-7K4M" -> "7K4M" */
    val serialSuffix: String
        get() = advertisedName.substringAfter("EchoWear-", "").ifBlank { "?" }

    /** Rough proximity, which is what actually helps someone pick the right
     *  device out of three on a ward trolley. */
    val nearness: String
        get() = when {
            bonded -> "Paired in Android settings"
            rssi > -55 -> "Very close"
            rssi > -70 -> "Nearby"
            rssi > -85 -> "In range"
            else -> "Far"
        }
}

/**
 * Discovery, kept separate from the per-device connection so that scanning
 * for a new wearable does not disturb the ones already being monitored.
 *
 * Matching is done in software rather than with a ScanFilter. A hardware
 * filter is tidier and is the usual advice, but it is offloaded to the
 * Bluetooth controller, and on several chipsets - Samsung's included - the
 * offloaded filter is applied to the primary advertisement only. Anything a
 * device puts in its scan response is then invisible to the filter, and the
 * device simply never appears. A foreground scan over a handful of nearby
 * devices costs nothing to filter here, and it means a wearable that is
 * advertising at all will be seen.
 */
@SuppressLint("MissingPermission")
class BleScanner(private val context: Context) {

    companion object {
        private const val TAG = "BleScanner"
        private const val STALE_MS = 12_000L
        private const val NAME_PREFIX = "EchoWear"
    }

    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private val handler = Handler(Looper.getMainLooper())

    private val _results = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val results = _results.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning = _scanning.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    /**
     * How many distinct BLE devices of any kind the scan has seen.
     *
     * This exists purely so the pairing screen can tell the difference
     * between "the scan is not working" and "the scan is working and your
     * wearable is not advertising" - which are the same empty list to the
     * user and completely different problems to fix.
     */
    private val _totalSeen = MutableStateFlow(0)
    val totalSeen = _totalSeen.asStateFlow()

    private val seen = mutableMapOf<String, DiscoveredDevice>()
    private val allAddresses = mutableSetOf<String>()

    /** Timestamps of recent scan starts, for the throttle check below. */
    private val recentStarts = ArrayDeque<Long>()

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device: BluetoothDevice = result.device
            allAddresses.add(device.address)
            _totalSeen.value = allAddresses.size

            val record = result.scanRecord
            // getDeviceName() merges the advertisement and the scan response,
            // so the name is found whichever packet carried it.
            val name = record?.deviceName ?: runCatching { device.name }.getOrNull()
            val serviceMatched =
                record?.serviceUuids?.any { it.uuid == EchoWearUuids.SERVICE } == true
            val nameMatched = name?.startsWith(NAME_PREFIX, ignoreCase = true) == true

            if (!serviceMatched && !nameMatched) return

            seen[device.address] = DiscoveredDevice(
                address = device.address,
                advertisedName = name ?: "EchoWear",
                rssi = result.rssi,
                serviceMatched = serviceMatched,
            )
            publish()
        }

        override fun onBatchScanResults(results: List<ScanResult>) {
            results.forEach { onScanResult(0, it) }
        }

        override fun onScanFailed(errorCode: Int) {
            _scanning.value = false
            _error.value = when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> "A scan is already running."
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED ->
                    "Bluetooth needs restarting on this phone. Turn it off and " +
                        "on again, then try once more."
                SCAN_FAILED_FEATURE_UNSUPPORTED ->
                    "This phone does not support the scan mode required."
                SCAN_FAILED_INTERNAL_ERROR ->
                    "The Bluetooth stack returned an internal error. Turning " +
                        "Bluetooth off and on usually clears it."
                else -> "Scan failed (code $errorCode)."
            }
            Log.e(TAG, "scan failed: $errorCode")
        }
    }

    /**
     * @return null if the scan started, or the reason it could not.
     */
    fun start(durationMs: Long = 20_000L): String? {
        preflight()?.let { _error.value = it; return it }

        val scanner = adapter?.bluetoothLeScanner ?: run {
            val reason = "Bluetooth is off. Turn it on to find wearables."
            _error.value = reason
            return reason
        }
        if (_scanning.value) return null

        seen.clear()
        allAddresses.clear()
        _totalSeen.value = 0

        // A wearable that something else on this phone is already connected
        // to is not advertising, so no scan will ever find it. Seed the list
        // from the bond table so it can still be selected.
        bondedCandidates().forEach { seen[it.address] = it }

        publish()
        _error.value = null
        _scanning.value = true

        noteStart()

        // No ScanFilter - see the class comment. SCAN_MODE_LOW_LATENCY with a
        // zero report delay keeps results coming one at a time rather than in
        // batches, which matters for a user staring at a list.
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0L)
            .build()

        val failure = runCatching { scanner.startScan(null, settings, callback) }
            .exceptionOrNull()
        if (failure != null) {
            _scanning.value = false
            val reason = if (failure is SecurityException) {
                "Bluetooth permission has not been granted. Allow \"Nearby " +
                    "devices\" for HeatGuard and try again."
            } else {
                "Could not start the scan: ${failure.message}"
            }
            _error.value = reason
            Log.e(TAG, "startScan threw", failure)
            return reason
        }

        Log.i(TAG, "scanning for EchoWear devices")
        handler.postDelayed({ stop() }, durationMs)

        // Android silently blocks an app that starts more than 5 scans in 30
        // seconds: startScan returns normally, no callback ever fires, and no
        // error is reported anywhere. Anyone debugging a pairing problem taps
        // "Scan again" far more than five times, so this is worth saying out
        // loud rather than leaving as an unexplained dead scan.
        if (recentStarts.size >= 5) {
            _error.value = "Android has temporarily blocked this app from " +
                "scanning, because it has started more than five scans in " +
                "the last 30 seconds. Wait half a minute and try once more."
        }
        return null
    }

    private fun noteStart() {
        val now = System.currentTimeMillis()
        recentStarts.addLast(now)
        while (recentStarts.isNotEmpty() && now - recentStarts.first() > 30_000L) {
            recentStarts.removeFirst()
        }
    }

    /**
     * EchoWear wearables in the phone's bond table.
     *
     * These are devices Android has already paired with - most often because
     * someone tapped the wearable in the system Bluetooth screen to see what
     * it was. Android then holds the connection, the wearable stops
     * advertising, and it disappears from every scan on the phone while
     * looking perfectly healthy on the bench. Listing them here means that
     * dead end at least has a way out of it.
     */
    private fun bondedCandidates(): List<DiscoveredDevice> = runCatching {
        adapter?.bondedDevices.orEmpty()
            .filter { it.name?.startsWith(NAME_PREFIX, ignoreCase = true) == true }
            .map {
                DiscoveredDevice(
                    address = it.address,
                    advertisedName = it.name ?: "EchoWear",
                    rssi = -127,
                    bonded = true,
                )
            }
    }.getOrElse {
        Log.w(TAG, "could not read bonded devices: ${it.message}")
        emptyList()
    }

    fun stop() {
        if (!_scanning.value) return
        _scanning.value = false
        runCatching { adapter?.bluetoothLeScanner?.stopScan(callback) }
            .onFailure { Log.w(TAG, "stopScan failed: ${it.message}") }
    }

    /**
     * The three things that silently produce an empty scan, checked up front
     * so the screen can say which one it is instead of spinning.
     */
    private fun preflight(): String? {
        if (adapter == null) return "This phone has no Bluetooth adapter."
        if (!adapter.isEnabled()) return "Bluetooth is off. Turn it on to find wearables."

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!granted(Manifest.permission.BLUETOOTH_SCAN)) {
                return "HeatGuard needs the \"Nearby devices\" permission to " +
                    "find a wearable. Grant it in Settings and try again."
            }
        } else {
            if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
                return "On this version of Android a Bluetooth scan needs the " +
                    "location permission. HeatGuard does not use your location; " +
                    "Android requires it for scanning."
            }
            // Below Android 12 the permission is not enough: the Location
            // master switch has to be on as well, and with it off the scan
            // succeeds and returns nothing at all. This is the single most
            // common reason a BLE device "cannot be found".
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            val locationOn = lm != null && (
                lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
                )
            if (!locationOn) {
                return "Turn Location on in your phone's settings. Android " +
                    "below version 12 returns no Bluetooth scan results while " +
                    "Location is off, even with every permission granted."
            }
        }
        return null
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED

    private fun publish() {
        val now = System.currentTimeMillis()
        _results.value = seen.values
            // Bonded entries carry no RSSI and never go stale; a scanned
            // entry does both.
            .filter { it.bonded || now - it.lastSeen < STALE_MS }
            .sortedWith(compareByDescending<DiscoveredDevice> { !it.bonded }
                .thenByDescending { it.rssi })
    }
}
