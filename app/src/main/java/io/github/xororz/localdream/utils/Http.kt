package io.github.xororz.localdream.utils

import okhttp3.OkHttpClient

// Single OkHttp instance for the whole app. Per-use-case clients must be
// derived via newBuilder() so they share this instance's dispatcher and
// connection pool instead of each spawning their own thread pools.
object Http {
    // BackendAuth adds the access code to requests for the local backend and
    // for paired hosts; derived clients inherit the interceptor.
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(BackendAuth.interceptor)
            .build()
    }
}
