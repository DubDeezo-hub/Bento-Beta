package com.example.pssender

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

// ---------------------------------------------------------------------------------------------
// Android 17 adds a "local network" permission (in the Nearby devices group). Without it an app can't reach anything on
// the home network, which is everything Crimson Multitool does. Apps built for older Android versions get it
// automatically, but asking for it now means the app keeps working when it is built for Android 17, and on phones that
// enforce it early. When it is refused, connections to the console just time out, so the app says why instead.
// ---------------------------------------------------------------------------------------------

object LocalNetwork {
    /** The permission's name (spelled out: the Android version the app is built with doesn't know it yet). */
    const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

    /** The first Android version (17) with the permission. */
    const val FIRST_SDK = 37

    /** Whether this phone has the permission at all. */
    fun applies(sdk: Int = Build.VERSION.SDK_INT): Boolean = sdk >= FIRST_SDK

    /** Whether the app may reach the console: always on older phones, otherwise when the person allowed it. */
    fun allowed(ctx: Context): Boolean =
        !applies() || ctx.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED
}
