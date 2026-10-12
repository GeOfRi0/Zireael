package com.example.data.engine

import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Singleton OkHttpClient configuration for the Turbo Download Engine.
 * Configured with a dedicated ConnectionPool and increased concurrency limits
 * (Dispatcher.maxRequests = 64, maxRequestsPerHost = 32) so that all download
 * coroutines, threads, range segments, and sniffing requests share the same pool
 * instead of allocating new client instances.
 */
object OkHttpProvider {

    private val connectionPool = ConnectionPool(
        maxIdleConnections = 32,
        keepAliveDuration = 5L,
        timeUnit = TimeUnit.MINUTES
    )

    private val dispatcher = Dispatcher().apply {
        maxRequests = 64
        maxRequestsPerHost = 32
    }

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectionPool(connectionPool)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }
}
