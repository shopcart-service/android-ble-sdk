package shopcart.service.dto

data class LockDeviceRequest(
    val userId: String,
    val lockDeviceMac: String,
    val battery: Int
)