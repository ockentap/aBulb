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
    fun exportedJson(k: MeshKeys): String = org.json.JSONObject()
        .put("format", "abulb-keys-v1")
        .put("netKey", k.net).put("appKey", k.app).put("deviceKey", k.dev)
        .put("mac", k.mac).put("bulbUnicast", "0x%04x".format(k.bulbUnicast))
        .toString(2)

    fun load(ctx: Context): MeshKeys? {
        prefsLoad(ctx)?.let { return it }
        return bakedLoad()
    }

    private fun bakedLoad(): MeshKeys? = try {
        val c = Class.forName("com.example.bulb.BakedKeys")
        MeshKeys(
            net = c.getField("NET").get(null) as String,
            app = c.getField("APP").get(null) as String,
            dev = c.getField("DEV").get(null) as String,
            mac = c.getField("MAC").get(null) as String,
            bulbUnicast = c.getField("UNICAST").get(null) as Int,
        )
    } catch (_: Throwable) { null }

    private fun prefsLoad(ctx: Context): MeshKeys? {
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
