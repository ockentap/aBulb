package com.example.bulb

/** Static defaults; per-network values (UUIDs, addresses) come from KeyStore at runtime. */
object MeshConfig {
    const val APP_VERSION = "1.4.1"
    const val MESH_UUID = "00000000-1111-2222-3333-444444444444"   // overridden by user via Keys dialog
    const val PROVISIONER_UUID = "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE"
    const val PROVISIONER_NAME = "phone-provisioner"
    const val PHONE_UNICAST = 0x0003          // address reserved for the phone
    const val BULB_UNICAST = 0x0002           // default, overridden per user
    /** How long the app holds the bulb's single BLE link after your last touch.
     *  The link is a shared resource: release it so another phone can take a turn. */
    const val IDLE_RELEASE_MS = 5_000L
    // Per-install source address range. Every aBulb install must send from its OWN address
    // (the bulb's replay protection is per source address), and kept high because a provisioner
    // hands out node addresses upward from 0x0003.
    const val ADDR_LOW = 0x7F00
    const val ADDR_HIGH = 0x7FFE
    const val BULB_UUID = "00000000000000000000000000000000"  // required field only; not used by transport
    const val NET_KEY_INDEX = 0x0000
    const val APP_KEY_INDEX = 0x0000

    // Some LEDVANCE bulbs clamp the lightness model to 1..50
    const val LIGHTNESS_MAX = 50
    const val LIGHTNESS_MIN = 1
    const val LIGHT_LIGHTNESS_MODEL_ID = 0x1300  // SIG model, verified from bulb composition page
}
