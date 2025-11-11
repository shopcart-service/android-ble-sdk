package shopcart.service

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import shopcart.service.dto.LockDeviceRequest
import shopcart.service.dto.LockState
import shopcart.service.http.HttpClient
import shopcart.service.http.HttpResult

class MainActivity : ComponentActivity() {

    private lateinit var bleLockClient: BleLockClient

    private val requiredPerms: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> }

    private val enableBtLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        bleLockClient = BleLockClient(
            androidContext = applicationContext,
            aesEncryptionKeyHexadecimalString = "providedAesKey"
        )

        permLauncher.launch(requiredPerms)

        setContent {
            var status by remember { mutableStateOf("Idle") }
            val scope = rememberCoroutineScope()
            val mac = "F1:03:32:02:00:78"

            Column(Modifier.padding(24.dp)) {
                Text("BLE SDK Tester")
                Spacer(Modifier.height(12.dp))

                Button(onClick = {
                    scope.launch {
                        ensureBluetoothReadyAndPermitted() ?: run {
                            status = "Missing permission or Bluetooth is off"
                            return@launch
                        }
                        status = "Unlocking..."
                        try {
                            @SuppressLint("MissingPermission")
                            val statusResult = bleLockClient.unlockDevice(deviceMacAddress = mac)
                            if (statusResult?.state == LockState.UNLOCKED) {
                                httpUnlock("1", mac, statusResult.batteryPercent ?: 0)
                            }
                            status = "Unlock result: $statusResult"
                        } catch (t: Throwable) {
                            status = "Unlock error: ${t.message}"
                        }
                    }
                }) { Text("UNLOCK") }

                Spacer(Modifier.height(12.dp))

                Button(onClick = {
                    scope.launch {
                        ensureBluetoothReadyAndPermitted() ?: run {
                            status = "Missing permission or Bluetooth is off"
                            return@launch
                        }
                        status = "Locking..."
                        try {
                            @SuppressLint("MissingPermission")
                            val statusResult = bleLockClient.lockDevice(deviceMacAddress = mac)
                            if (statusResult?.state == LockState.LOCKED) {
                                httpLock("1", mac, statusResult.batteryPercent ?: 0)
                            }
                            status = "Lock result: $statusResult"
                        } catch (t: Throwable) {
                            status = "Lock error: ${t.message}"
                        }
                    }
                }) { Text("LOCK") }

                Spacer(Modifier.height(16.dp))
                Text(status)
            }
        }
    }

    private fun ensureBluetoothReadyAndPermitted(): Boolean? {
        val missing = requiredPerms.any {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing) {
            permLauncher.launch(requiredPerms)
            return null
        }

        val adapter = currentAdapter() ?: return false
        if (!adapter.isEnabled) {
            val intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            enableBtLauncher.launch(intent)
            return null
        }
        return true
    }

    private fun currentAdapter(): BluetoothAdapter? {
        val mgr = getSystemService(BluetoothManager::class.java)
        return mgr?.adapter
    }

    private suspend fun httpUnlock(userId: String, mac: String, battery: Int): HttpResult =
        apiCall { HttpClient.api.unlock(LockDeviceRequest(userId, mac, battery)) }

    private suspend fun httpLock(userId: String, mac: String, battery: Int): HttpResult =
        apiCall { HttpClient.api.lock(LockDeviceRequest(userId, mac, battery)) }

    private fun <T> errorBodyToString(resp: retrofit2.Response<T>): String? =
        try { resp.errorBody()?.string() } catch (_: Throwable) { null }

    private inline fun apiCall(block: () -> retrofit2.Response<Unit>): HttpResult {
        return try {
            val resp = block()
            if (resp.isSuccessful) HttpResult.Ok
            else HttpResult.Error(resp.code(), errorBodyToString(resp))
        } catch (t: Throwable) {
            HttpResult.Error(-1, t.message)
        }
    }

}
