package dev.atoll.app.app

import okhttp3.OkHttpClient

/** Every session feature uses this client, including Files' own OBS instance. */
internal fun sessionHttp(client: OkHttpClient, onExpired: () -> Unit): OkHttpClient =
    client.newBuilder().addInterceptor { chain ->
        chain.proceed(chain.request()).also { if (it.code == 401) onExpired() }
    }.build()
