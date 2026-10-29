package top.maler.motogps

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.edit
import top.maler.motogps.ui.MotoApp
import top.maler.motogps.ui.MotoTheme
import top.maler.motogps.ui.PlatformActions

class MainActivity : ComponentActivity() {
    private val model get() = (application as MotoApplication).model

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        model.onPermissionsChanged()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MotoTheme {
                MotoApp(
                    model,
                    PlatformActions(
                        requestLocation = { request(locationPermissions) },
                        requestBluetooth = { request(bluetoothPermissions) },
                        openAppSettings = ::openAppSettings,
                        openBluetoothSettings = { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
                        openNotificationAccess = { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                        moveToBackground = { moveTaskToBack(true) },
                    ),
                )
            }
        }
        // UI tests run offline: no permission prompts, radio or location work.
        if (isUiTest()) return
        model.start()
        if (savedInstanceState == null) {
            // First launch: location ranks search results, nearby devices find
            // the round screen, notifications show the ride while locked.
            val missing = (locationPermissions + bluetoothPermissions + notificationPermissions)
                .distinct()
                .filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
            if (missing.isNotEmpty()) {
                rememberAsked(missing)
                permissionLauncher.launch(missing.toTypedArray())
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Returning from system settings may have granted or revoked access.
        model.onPermissionsChanged()
    }

    private fun request(permissions: List<String>) {
        val missing = permissions.filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            model.onPermissionsChanged()
            return
        }
        // After two denials Android stops showing the dialog; use settings.
        if (missing.none { shouldShowRequestPermissionRationale(it) } && askedBefore(missing)) {
            openAppSettings()
        } else {
            rememberAsked(missing)
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun askedBefore(permissions: List<String>): Boolean {
        val preferences = getSharedPreferences("moto_permissions", MODE_PRIVATE)
        return permissions.all { preferences.getBoolean(it, false) }
    }

    private fun rememberAsked(permissions: List<String>) {
        getSharedPreferences("moto_permissions", MODE_PRIVATE).edit {
            permissions.forEach { putBoolean(it, true) }
        }
    }

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }

    private fun isUiTest(): Boolean = intent?.getBooleanExtra(EXTRA_UI_TEST, false) == true

    companion object {
        const val EXTRA_UI_TEST = "top.maler.motogps.extra.UI_TEST"

        val locationPermissions = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

        val bluetoothPermissions: List<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        val notificationPermissions: List<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            emptyList()
        }
    }
}
