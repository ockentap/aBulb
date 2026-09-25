package com.example.bulb

import android.content.Context

/** User-supplied mesh credentials + addressing. Nothing is baked in. */
data class MeshKeys(
    val net: String,
    val app: String,
    val dev: String,
    val mac: String = "",
    val bulbUnicast: Int = 0x0002,
)

object KeyStore {
    private const val PREF = "bulb_keys"

    /** Returns null when the user hasn't entered keys yet. */
    fun load(ctx: Context): MeshKeys? {
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val n = p.getString("net", null)
        val a = p.getString("app", null)
        val d = p.getString("dev", null)
        if (n == null || a == null || d == null ||
            !Regex("^[0-9A-Fa-f]{32}$").matches(n) ||
            !Regex("^[0-9A-Fa-f]{32}$").matches(a) ||
            !Regex("^[0-9A-Fa-f]{32}$").matches(d)) return null
        return MeshKeys(
            net = n, app = a, dev = d,
            mac = p.getString("mac", "") ?: "",
            bulbUnicast = p.getInt("unicast", 0x0002),
        )
    }

    fun save(ctx: Context, k: MeshKeys) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString("net", k.net.trim().uppercase())
            .putString("app", k.app.trim().uppercase())
            .putString("dev", k.dev.trim().uppercase())
            .putString("mac", k.mac.trim().uppercase())
            .putInt("unicast", k.bulbUnicast)
            .apply()
    }
}
