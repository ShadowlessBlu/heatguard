package gh.knust.echowear

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import gh.knust.echowear.alert.SettingsStore
import gh.knust.echowear.service.MonitorService
import gh.knust.echowear.ui.EchoWearApp
import gh.knust.echowear.ui.theme.EchoWearTheme

class MainActivity : ComponentActivity() {

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            // Deliberately not "all of them": see startIfPossible().
            startIfPossible()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val missing = allPermissions().filterNot { isGranted(it) }
        if (missing.isEmpty()) startIfPossible()
        else permissionLauncher.launch(missing.toTypedArray())

        setContent {
            // The accessibility settings have to be read here rather than
            // inside the app tree: they decide the typography and colour
            // scheme, which the whole tree is composed under. Reading them
            // from a store built directly on the activity also means the
            // theme is right before the monitoring service has bound.
            val settings = remember { SettingsStore(applicationContext) }
            val largeText by settings.largeText.collectAsStateWithLifecycle(false)
            val highContrast by settings.highContrast.collectAsStateWithLifecycle(false)
            val reduceMotion by settings.reduceMotion.collectAsStateWithLifecycle(false)
            val textScale by settings.textScale.collectAsStateWithLifecycle(1f)

            EchoWearTheme(
                highContrast = highContrast,
                largeText = largeText,
                textScale = textScale,
                reduceMotion = reduceMotion,
            ) {
                Scaffold { inner ->
                    EchoWearApp(
                        modifier = Modifier.padding(inner),
                        onRequestPermissions = {
                            val still = allPermissions().filterNot { isGranted(it) }
                            if (still.isNotEmpty()) permissionLauncher.launch(still.toTypedArray())
                            else startIfPossible()
                        },
                    )
                }
            }
        }
    }

    private fun isGranted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED

    /**
     * The permissions without which the app cannot do its job at all.
     *
     * Only Bluetooth is essential. SMS and notifications matter enormously
     * when an alert fires, but gating the service on them means that
     * declining the SMS prompt - which plenty of people do, and which Android
     * makes look alarming - leaves the app stuck on its starting screen with
     * no wearable ever discovered and no explanation. Degrade instead: run,
     * and say on the Settings screen what is missing.
     */
    private fun essentialPermissions(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            // Below Android 12 a BLE scan needs a location permission, which
            // surprises people; the settings screen explains why.
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun optionalPermissions(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
        add(Manifest.permission.SEND_SMS)
    }

    private fun allPermissions() = essentialPermissions() + optionalPermissions()

    private fun startIfPossible() {
        if (essentialPermissions().all { isGranted(it) }) startMonitoring()
    }

    private fun startMonitoring() {
        val intent = Intent(this, MonitorService::class.java)
            .setAction(MonitorService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}
