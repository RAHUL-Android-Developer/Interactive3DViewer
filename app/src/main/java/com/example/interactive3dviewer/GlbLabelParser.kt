package com.example.interactive3dviewer

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads ONLY the JSON chunk of a .glb (no geometry/textures touched) and returns the nodes that
 * carry `extras.prop`. Runs on a background thread; cost is a few KB of JSON.
 */
object GlbLabelParser {
    /**
     * @param nodeName    glTF node name (used to find the Filament entity)
     * @param text        label text = extras.prop
     * @param occurrence  index of this node among nodes sharing the same name (disambiguates duplicates)
     * @param sameNameCount how many nodes in the file have this name
     */

    private const val GLB_MAGIC = 0x46546C67
    private const val CHUNK_JSON = 0x4E4F534A

    fun parse(glb: ByteArray): List<LabelDef> {
        if (glb.size < 20) return emptyList()
        val bb = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN)
        if (bb.getInt(0) != GLB_MAGIC) return emptyList()
        val jsonLen = bb.getInt(12)
        if (bb.getInt(16) != CHUNK_JSON || jsonLen <= 0 || 20 + jsonLen > glb.size) return emptyList()

        val nodes = JSONObject(String(glb, 20, jsonLen, Charsets.UTF_8)).optJSONArray("nodes") ?: return emptyList()

        val nameTotals = HashMap<String, Int>()
        for (i in 0 until nodes.length()) {
            val n = nodes.optJSONObject(i)?.optString("name", "").orEmpty()
            nameTotals[n] = (nameTotals[n] ?: 0) + 1
        }

        val seen = HashMap<String, Int>()
        val out = ArrayList<LabelDef>()

        for (i in 0 until nodes.length()) {
            val node = nodes.optJSONObject(i) ?: continue
            val name = node.optString("name", "")
            val occ = seen[name] ?: 0
            seen[name] = occ + 1
            val text = node.optJSONObject("extras")?.optString("prop", "").orEmpty()
            if (name.isNotEmpty() && text.isNotBlank()) out += LabelDef(name, text, occ, nameTotals[name] ?: 1)
        }
        return out
    }
}
