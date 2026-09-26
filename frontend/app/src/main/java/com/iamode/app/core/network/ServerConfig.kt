package com.iamode.app.core.network

import com.iamode.app.BuildConfig
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The endpoint and optional debug credential are fixed in BuildConfig at APK build time.
 * They are deliberately not user-editable at runtime.
 */
@Singleton
class ServerConfig @Inject constructor() {
    val defaultUrl: String = BuildConfig.BACKEND_URL

    val baseUrl: HttpUrl = defaultUrl.toHttpUrl()
    val devToken: String = BuildConfig.DEV_API_TOKEN
}

/** Points every backend request at the current [ServerConfig.baseUrl]. */
class BaseUrlInterceptor @Inject constructor(private val config: ServerConfig) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val target = config.baseUrl
        val original = chain.request().url
        val url = original.newBuilder()
            .scheme(target.scheme)
            .host(target.host)
            .port(target.port)
            .encodedPath(target.encodedPath.trimEnd('/') + original.encodedPath)
            .build()
        return chain.proceed(chain.request().newBuilder().url(url).build())
    }
}
