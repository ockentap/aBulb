package com.example.bulb

import org.json.JSONArray
import org.json.JSONObject

/** Builds the Nordic/mesh-cdb network JSON the MeshManagerApi can import,
 *  given the secrets exported from the Raspberry Pi. */
object CdbBuilder {

    fun build(keys: MeshKeys, phoneAddress: Int): String {
        val netKey = keys.net; val appKey = keys.app; val devKey = keys.dev
        val bulbUnicast = String.format("%04X", keys.bulbUnicast)
        val o = JSONObject()
        o.put("\$schema", "http://json-schema.org/draft-04/schema#")
        o.put("id", "https://www.bluetooth.com/specifications/specs/mesh-cdb-1-0-1-schema.json#")
        o.put("version", "1.0.1")
        o.put("meshUUID", MeshConfig.MESH_UUID)
        o.put("meshName", "LEDVANCE Bulb")
        o.put("timestamp", "2026-01-01T00:00:00Z")
        o.put("partial", false)

        o.put("netKeys", JSONArray().put(JSONObject()
            .put("index", MeshConfig.NET_KEY_INDEX)
            .put("key", netKey.uppercase())
            .put("name", "net-key-0")
            .put("phase", 0)
            .put("minSecurity", "secure")
            .put("timestamp", "2026-01-01T00:00:00Z")))

        o.put("appKeys", JSONArray().put(JSONObject()
            .put("index", MeshConfig.APP_KEY_INDEX)
            .put("key", appKey.uppercase())
            .put("name", "app-key-0")
            .put("boundNetKey", MeshConfig.NET_KEY_INDEX)))

        o.put("provisioners", JSONArray().put(JSONObject()
            .put("provisionerName", MeshConfig.PROVISIONER_NAME)
            .put("UUID", MeshConfig.PROVISIONER_UUID)
            .put("allocatedUnicastRange", JSONArray().put(JSONObject()
                .put("lowAddress", "0003")
                .put("highAddress", "7FFF")))
            .put("allocatedGroupRange", JSONArray())
            .put("allocatedSceneRange", JSONArray())))

        val bulbUuid = MeshConfig.BULB_UUID.replaceFirst(Regex("^(.{8})(.{4})(.{4})(.{4})(.{12})$"), "$1-$2-$3-$4-$5")
        val netKeysArr = JSONArray().put(JSONObject().put("index", MeshConfig.NET_KEY_INDEX).put("updated", false))
        val appKeysArr = JSONArray().put(JSONObject().put("index", MeshConfig.APP_KEY_INDEX).put("updated", false))
        val elements = JSONArray().put(JSONObject()
            .put("name", "Element: $bulbUnicast")
            .put("location", "0001")
            .put("models", JSONArray().put(JSONObject().put("modelId", "00001300"))))

        val phoneNode = JSONObject()
            .put("UUID", MeshConfig.PROVISIONER_UUID)
            .put("name", MeshConfig.PROVISIONER_NAME)
            .put("deviceKey", MeshConfig.PROVISIONER_UUID.replace("-", "").uppercase())
            .put("unicastAddress", String.format("%04X", phoneAddress))
            .put("security", "secure")
            .put("configComplete", true)
            .put("cid", "052E")
            .put("crpl", "00")
            .put("features", JSONObject()
                .put("friend", 0).put("lowPower", 0)
                .put("relay", 0).put("proxy", 0))
            .put("defaultTTL", 5)
            .put("netKeys", JSONArray().put(JSONObject().put("index", MeshConfig.NET_KEY_INDEX).put("updated", false)))
            .put("appKeys", JSONArray().put(JSONObject().put("index", MeshConfig.APP_KEY_INDEX).put("updated", false)))
            .put("elements", JSONArray().put(JSONObject()
                .put("name", "Element: " + String.format("%04X", phoneAddress))
                .put("location", "0001")
                .put("models", JSONArray())))

        val bulbNode = JSONObject()
            .put("UUID", bulbUuid.uppercase())
            .put("name", "LEDVANCE bulb")
            .put("deviceKey", devKey.uppercase())
            .put("unicastAddress", bulbUnicast)
            .put("security", "secure")
            .put("configComplete", true)
            .put("cid", "052E")
            .put("crpl", "00")
            .put("features", JSONObject()
                .put("friend", 0).put("lowPower", 0)
                .put("relay", 0).put("proxy", 1))
            .put("defaultTTL", 5)
            .put("netKeys", netKeysArr)
            .put("appKeys", appKeysArr)
            .put("elements", elements)

        val nodesArr = JSONArray()
        nodesArr.put(bulbNode as Any).put(phoneNode as Any)
        o.put("nodes", nodesArr)

        o.put("groups", JSONArray())
        o.put("scenes", JSONArray())
        o.put("networkExclusions", JSONArray())
        return o.toString(2)
    }
}
