package com.justdataplease.spoon.data.remote

/** Readiness checks may run on every action; account metadata reloads may not. */
internal class AccountRefreshGate(private val intervalMillis: Long = 15_000L) {
    private var lastOwner: String? = null
    private var lastRefreshMillis: Long? = null

    fun shouldRefresh(owner: String, nowMillis: Long): Boolean {
        val last = lastRefreshMillis
        if (lastOwner == owner && last != null && nowMillis >= last && nowMillis - last < intervalMillis) return false
        lastOwner = owner
        lastRefreshMillis = nowMillis
        return true
    }
}
