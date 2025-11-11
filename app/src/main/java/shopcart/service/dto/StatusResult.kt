package shopcart.service.dto

data class StatusResult(
    val state: LockState,
    val batteryPercent: Int?,
)
