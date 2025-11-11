package shopcart.service.http

sealed interface HttpResult {
    data object Ok : HttpResult
    data class Error(val code: Int, val message: String?) : HttpResult
}