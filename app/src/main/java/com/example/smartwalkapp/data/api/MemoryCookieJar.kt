package com.example.smartwalkapp.data.api

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/** Guarda las cookies en memoria mientras la app está abierta. */
class MemoryCookieJar : CookieJar {
    private val store = mutableMapOf<String, MutableMap<String, Cookie>>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val byName = store.getOrPut(url.host) { mutableMapOf() }
        cookies.forEach { byName[it.name] = it }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> =
        store[url.host]?.values?.filter { it.expiresAt > System.currentTimeMillis() } ?: emptyList()
}
