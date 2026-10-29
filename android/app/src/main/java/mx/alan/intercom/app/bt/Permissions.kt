package mx.alan.intercom.app.bt

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

object Permissions {
    fun required(): Array<String> = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 31) {
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    fun missing(ctx: Context): List<String> =
        required().filter { ctx.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

    fun hasConnect(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    fun hasScan(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 31) ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        else ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
}

/** Conserva el OUI (fabricante del chip/módulo) y oculta el resto de la MAC para exportes. */
fun maskMac(mac: String?): String? {
    if (mac == null) return null
    val parts = mac.split(":")
    return if (parts.size == 6) parts.take(3).joinToString(":") + ":XX:XX:XX" else mac
}
