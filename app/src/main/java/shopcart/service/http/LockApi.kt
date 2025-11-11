package shopcart.service.http

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.PUT
import shopcart.service.dto.LockDeviceRequest

interface LockApi {
    @PUT("api/lock-devices/unlock")
    suspend fun unlock(@Body body: LockDeviceRequest): Response<Unit>

    @PUT("api/lock-devices/lock")
    suspend fun lock(@Body body: LockDeviceRequest): Response<Unit>
}
