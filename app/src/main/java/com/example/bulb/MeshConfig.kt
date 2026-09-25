package com.example.bulb

/** Static defaults; per-network values (UUIDs, addresses) come from KeyStore at runtime. */
object MeshConfig {
    const val MESH_UUID = "00000000-1111-2222-3333-444444444444"   // overridden by user via Keys dialog
    const val PROVISIONER_UUID = "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE"
    const val PROVISIONER_NAME = "phone-provisioner"
    const val PHONE_UNICAST = 0x0003          // address reserved for the phone
    const val BULB_UNICAST = 0x0002           // default, overridden per user
    const val NET_KEY_INDEX = 0x0000
    const val APP_KEY_INDEX = 0x0000

    // Some LEDVANCE bulbs clamp the lightness model to 1..50
    const val LIGHTNESS_MAX = 50
    const val LIGHTNESS_MIN = 1
}
