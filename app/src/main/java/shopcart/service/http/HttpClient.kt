package shopcart.service.http

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object HttpClient {
    private const val BASE_URL = "https://shopcart-service.eu/"
    private const val API_KEY_HEADER = "X-API-Key"
    private const val API_KEY_VALUE  = "sk_live_rQxxxxxxxx" // your company api key

    private val auth = Interceptor { chain ->
        chain.proceed(
            chain.request().newBuilder()
                .addHeader(API_KEY_HEADER, API_KEY_VALUE)
                .build()
        )
    }

    private val logging = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BASIC
    }

    private val ok = OkHttpClient.Builder()
        .addInterceptor(auth)
        .addInterceptor(logging)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    val api: LockApi = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(ok)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(LockApi::class.java)
}
