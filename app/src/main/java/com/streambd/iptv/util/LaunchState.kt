package com.streambd.iptv.util

import javax.inject.Inject
import javax.inject.Singleton

/** এই চালুতে অ্যাপ বুট থেকে খুলেছে কিনা। Splash একবার পড়ে মুছে দেয়। */
@Singleton
class LaunchState @Inject constructor() {
    @Volatile var bootLaunch: Boolean = false

    fun consumeBoot(): Boolean {
        val value = bootLaunch
        bootLaunch = false
        return value
    }
}
