package com.example.bulb

import android.content.Context
import kotlin.random.Random

/**
 * This install's identity on the mesh: the unicast address its messages are sent from.
 *
 * The mesh has no "one controller at a time" rule for *writes* — any device holding the network
 * and app keys may address the bulb, and the bulb acts on whatever it receives. What it does have
 * is a replay-protection entry per SOURCE address (source, highest sequence number seen), and a
 * sender's sequence counter must only move forward. So:
 *
 *  - two installs sharing a source address fight over one replay entry: the one with the lower
 *    counter is silently dropped while still showing "connected";
 *  - an install whose sequence restarted at zero (fresh app data, restored keys) is dropped for
 *    the same reason, silently;
 *  - neither is visible at the BLE level, which is why the app needs a new address on demand.
 *
 * Hence: one stable, distinct address per install, and a one-tap roll to a fresh one.
 */
object LocalNode {
    private const val PREF = "bulb_node"
    private const val KEY_ADDR = "phoneAddress"

    /** Stable address for this install, rolled on first use. */
    fun address(ctx: Context): Int {
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val stored = p.getInt(KEY_ADDR, 0)
        return if (stored in MeshConfig.ADDR_LOW..MeshConfig.ADDR_HIGH) stored else roll(ctx)
    }

    /** A fresh address — first run, or to get out from under a replay entry the bulb now ignores. */
    fun roll(ctx: Context): Int {
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        var a: Int
        do {
            a = Random.nextInt(MeshConfig.ADDR_LOW, MeshConfig.ADDR_HIGH + 1)
        } while (a == MeshConfig.BULB_UNICAST)
        p.edit().putInt(KEY_ADDR, a).apply()
        return a
    }

    fun describe(addr: Int): String = "0x%04X".format(addr)
}
