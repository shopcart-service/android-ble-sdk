package shopcart.service

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.time.withTimeout
import kotlinx.coroutines.time.withTimeoutOrNull
import shopcart.service.dto.LockState
import shopcart.service.dto.StatusResult
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.*
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class BleLockClient(
    private val androidContext: Context,
    aesEncryptionKeyHexadecimalString: String,
) {
    private val aesKeyBytes = hexadecimalStringToByteArray(aesEncryptionKeyHexadecimalString)
    private val responseTimeoutSeconds = 5L

    private val serviceUuid = UUID.fromString("0000fee7-0000-1000-8000-00805f9b34fb")
    private val writeCharacteristicUuid = UUID.fromString("000036f5-0000-1000-8000-00805f9b34fb")
    private val responseCharacteristicUuid = UUID.fromString("000036f6-0000-1000-8000-00805f9b34fb")
    private val cccdUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private class ResponseWaiter {
        val channel = Channel<ByteArray>(1)
        val cccdReady = Channel<Boolean>(1)
        fun onNotification(value: ByteArray) {
            if (value.size == 16) channel.trySend(value)
        }
    }

    // ===== Public operations =====
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun unlockDevice(
        deviceMacAddress: String,
        sixDigitPassword: String = "000000"
    ): StatusResult? =
        withContext(Dispatchers.IO) {
            performGattSession(deviceMacAddress) { gatt, writeChr, responseChr, responseWaiter ->
                enableNotifications(gatt, responseChr, responseWaiter)
                try {
                    val tokenBytes = obtainToken(gatt, writeChr, responseWaiter)
                    val battery = queryBattery(gatt, writeChr, responseWaiter, tokenBytes)
                    val isUnlocked =
                        performUnlock(gatt, writeChr, responseWaiter, tokenBytes, sixDigitPassword)
                    val state = if (isUnlocked) LockState.UNLOCKED else LockState.LOCKED
                    StatusResult(state = state, batteryPercent = battery)
                } finally {
                    disableNotifications(gatt, responseChr, responseWaiter)
                    gatt.disconnect()
                    gatt.close()
                }
            }
        }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun lockDevice(deviceMacAddress: String): StatusResult? =
        withContext(Dispatchers.IO) {
            performGattSession(deviceMacAddress) { gatt, writeChr, responseChr, responseWaiter ->
                enableNotifications(gatt, responseChr, responseWaiter)
                try {
                    val tokenBytes = obtainToken(gatt, writeChr, responseWaiter)
                    val battery = queryBattery(gatt, writeChr, responseWaiter, tokenBytes)
                    val isLocked = performLock(gatt, writeChr, responseWaiter, tokenBytes)
                    val state = if (isLocked) LockState.LOCKED else LockState.UNLOCKED
                    val checkLockState = queryLockState(gatt, writeChr, responseWaiter, tokenBytes)
                    if (checkLockState == LockState.UNLOCKED) {
                        // the device is not locked
                        return@performGattSession null
                    }
                    StatusResult(state = state, batteryPercent = battery)
                } finally {
                    disableNotifications(gatt, responseChr, responseWaiter)
                    gatt.disconnect()
                    gatt.close()
                }
            }
        }

    // ===== Core GATT session with notification routing (no GlobalScope) =====
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private suspend fun <R> performGattSession(
        deviceMacAddress: String,
        block: suspend (
            gatt: BluetoothGatt,
            writeCharacteristic: BluetoothGattCharacteristic,
            responseCharacteristic: BluetoothGattCharacteristic,
            responseWaiter: ResponseWaiter
        ) -> R
    ): R? = suspendCancellableCoroutine { continuation ->
        val bluetoothManager =
            androidContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothDevice = bluetoothManager.adapter?.getRemoteDevice(deviceMacAddress)
        if (bluetoothDevice == null) {
            continuation.resume(null); return@suspendCancellableCoroutine
        }

        val responseWaiter = ResponseWaiter()

        val callback = object : BluetoothGattCallback() {
            @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS || newState != BluetoothProfile.STATE_CONNECTED) {
                    if (continuation.isActive) continuation.resume(null)
                    gatt.close()
                } else {
                    gatt.discoverServices()
                }
            }

            @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    continuation.resume(null); gatt.close(); return
                }
                val service = gatt.getService(serviceUuid)
                    ?: run { continuation.resume(null); gatt.close(); return }
                val writeChr = service.getCharacteristic(writeCharacteristicUuid)
                    ?: run { continuation.resume(null); gatt.close(); return }
                val responseChr = service.getCharacteristic(responseCharacteristicUuid)
                    ?: run { continuation.resume(null); gatt.close(); return }

                // launch using the continuation's context; no delicate API
                val scope = CoroutineScope(continuation.context + Dispatchers.IO)
                scope.launch {
                    try {
                        val result = block(gatt, writeChr, responseChr, responseWaiter)
                        if (continuation.isActive) continuation.resume(result)
                    } catch (t: Throwable) {
                        if (continuation.isActive) continuation.resumeWithException(t)
                    }
                }
            }

            // Modern callback (API 33+). On older platforms this overload is still called by the shims.
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray
            ) {
                if (characteristic.uuid == responseCharacteristicUuid) {
                    responseWaiter.onNotification(value)
                }
            }

            // Keep legacy overload for older devices; suppress its deprecation locally.
            @Deprecated("Deprecated in Java")
            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic
            ) {
                val value = characteristic.value ?: return
                if (characteristic.uuid == responseCharacteristicUuid) {
                    responseWaiter.onNotification(value)
                }
            }

            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int
            ) {
                if (descriptor.uuid == cccdUuid) {
                    responseWaiter.cccdReady.trySend(status == BluetoothGatt.GATT_SUCCESS)
                }
            }
        }

        bluetoothDevice.connectGatt(androidContext, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    // ===== Enable/disable notifications (use non-deprecated descriptor writes on API 33+) =====
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private suspend fun enableNotifications(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        waiter: ResponseWaiter
    ) {
        gatt.setCharacteristicNotification(characteristic, true)
        val cccd = characteristic.getDescriptor(cccdUuid) ?: return
        val queued = if (Build.VERSION.SDK_INT >= 33) {
            gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                    BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(cccd)
            }
        }
        require(queued) { "CCCD write could not be queued" }
        val ok = withTimeout(Duration.ofSeconds(3)) { waiter.cccdReady.receive() }
        require(ok) { "Enabling notifications failed" }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private suspend fun disableNotifications(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        waiter: ResponseWaiter
    ) {
        gatt.setCharacteristicNotification(characteristic, false)
        val cccd = characteristic.getDescriptor(cccdUuid) ?: return
        val queued = if (Build.VERSION.SDK_INT >= 33) {
            gatt.writeDescriptor(cccd, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE) ==
                    BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                cccd.value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(cccd)
            }
        }
        if (queued) {
            withTimeoutOrNull(Duration.ofSeconds(2)) { waiter.cccdReady.receive() }
        }
    }

    // ===== Protocol helpers (16-byte frames, AES-ECB) =====
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private suspend fun writeEncryptedAndWaitForPlainResponse(
        gatt: BluetoothGatt,
        writeCharacteristic: BluetoothGattCharacteristic,
        responseWaiter: ResponseWaiter,
        plainFrame16Bytes: ByteArray
    ): ByteArray = withTimeout(Duration.ofSeconds(responseTimeoutSeconds)) {
        val cipherFrame16Bytes = encryptEcbBlock(aesKeyBytes, plainFrame16Bytes)

        val queued: Boolean = if (Build.VERSION.SDK_INT >= 33) {
            val code = gatt.writeCharacteristic(
                writeCharacteristic,
                cipherFrame16Bytes,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            )
            code == BluetoothStatusCodes.SUCCESS
        } else {
            // Legacy path (pre-33)
            @Suppress("DEPRECATION")
            run {
                writeCharacteristic.value = cipherFrame16Bytes
                writeCharacteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                gatt.writeCharacteristic(writeCharacteristic)
            }
        }

        require(queued) { "writeCharacteristic() could not be queued" }

        val responseCipher16Bytes = responseWaiter.channel.receive()
        decryptEcbBlock(aesKeyBytes, responseCipher16Bytes)
    }

    private fun buildProtocolFrame(vararg parts: ByteArray): ByteArray {
        val combined = parts.fold(ByteArray(0)) { acc, p -> acc + p }
        require(combined.size == 16) { "Protocol frame must be exactly 16 bytes, got ${combined.size}" }
        return combined
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private suspend fun obtainToken(
        gatt: BluetoothGatt,
        writeCharacteristic: BluetoothGattCharacteristic,
        responseWaiter: ResponseWaiter
    ): ByteArray {
        val response = writeEncryptedAndWaitForPlainResponse(
            gatt, writeCharacteristic, responseWaiter,
            buildProtocolFrame(byteArrayOf(0x06, 0x01, 0x01, 0x01), ByteArray(12))
        )
        return response.copyOfRange(3, 7)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private suspend fun performLock(
        gatt: BluetoothGatt,
        writeCharacteristic: BluetoothGattCharacteristic,
        responseWaiter: ResponseWaiter,
        tokenBytes: ByteArray
    ): Boolean {
        val response = writeEncryptedAndWaitForPlainResponse(
            gatt, writeCharacteristic, responseWaiter,
            buildProtocolFrame(byteArrayOf(0x05, 0x0C, 0x01, 0x01), tokenBytes, ByteArray(8))
        )
        val okHeader = response[0] == 0x05.toByte() &&
                (response[1] == 0x0D.toByte() || response[1] == 0x08.toByte())
        return okHeader && response[3] == 0x00.toByte()
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private suspend fun performUnlock(
        gatt: BluetoothGatt,
        writeCharacteristic: BluetoothGattCharacteristic,
        responseWaiter: ResponseWaiter,
        tokenBytes: ByteArray,
        sixDigitPassword: String
    ): Boolean {
        require(sixDigitPassword.length == 6) { "Password must be 6 digits" }
        val pwd = sixDigitPassword.toByteArray(StandardCharsets.US_ASCII)
        val response = writeEncryptedAndWaitForPlainResponse(
            gatt, writeCharacteristic, responseWaiter,
            buildProtocolFrame(byteArrayOf(0x05, 0x01, 0x06), pwd, tokenBytes, ByteArray(3))
        )
        return response[0] == 0x05.toByte() &&
                response[1] == 0x02.toByte() &&
                response[3] == 0x00.toByte()
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private suspend fun queryBattery(
        gatt: BluetoothGatt,
        writeCharacteristic: BluetoothGattCharacteristic,
        waiter: ResponseWaiter,
        tokenBytes: ByteArray
    ): Int? {
        val resp = writeEncryptedAndWaitForPlainResponse(
            gatt, writeCharacteristic, waiter,
            buildProtocolFrame(byteArrayOf(0x02, 0x01, 0x01, 0x01), tokenBytes, ByteArray(8))
        )
        return if (resp[0] == 0x02.toByte() && resp[1] == 0x02.toByte()) {
            (resp[3].toInt() and 0xFF).coerceIn(0, 100)
        } else null
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private suspend fun queryLockState(
        gatt: BluetoothGatt,
        writeCharacteristic: BluetoothGattCharacteristic,
        waiter: ResponseWaiter,
        tokenBytes: ByteArray
    ): LockState {
        val resp = writeEncryptedAndWaitForPlainResponse(
            gatt, writeCharacteristic, waiter,
            buildProtocolFrame(byteArrayOf(0x05, 0x0E, 0x01, 0x01), tokenBytes, ByteArray(8))
        )
        return if (resp[0] == 0x05.toByte() && resp[1] == 0x0F.toByte()) {
            if (resp[3] == 0x01.toByte()) LockState.LOCKED else LockState.UNLOCKED
        } else {
            LockState.UNKNOWN
        }
    }

    // ===== helpers =====
    private fun hexadecimalStringToByteArray(hexadecimalString: String): ByteArray {
        require(hexadecimalString.length == 32) { "AES key must be 16 bytes (32 hexadecimal characters)" }
        return ByteArray(hexadecimalString.length / 2) { index ->
            hexadecimalString.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    @SuppressLint("GetInstance")
    private fun encryptEcbBlock(aesKeyBytes: ByteArray, plainBlock16Bytes: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKeyBytes, "AES"))
        return cipher.doFinal(plainBlock16Bytes)
    }

    @SuppressLint("GetInstance")
    private fun decryptEcbBlock(aesKeyBytes: ByteArray, cipherBlock16Bytes: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKeyBytes, "AES"))
        return cipher.doFinal(cipherBlock16Bytes)
    }
}
