package com.davidlang.torque.fuelsmoothing

import java.io.File

/**
 * The vehicle decision the user still has to make, shared by the service (writer) and the setup page
 * (reader). Present only while a pending identity has an open prompt; deleted when it resolves.
 */
internal object IdentityPending {
    const val FILE = "identity-pending.json"

    class Choice(val target: String, val label: String)
    class Pending(val t: Long, val title: String, val text: String, val choices: List<Choice>)

    fun write(root: File, p: Pending) {
        val arr = org.json.JSONArray()
        for (c in p.choices) arr.put(org.json.JSONObject().put("target", c.target).put("label", c.label))
        AtomicFile.write(File(root, FILE), org.json.JSONObject().put("t", p.t).put("title", p.title)
            .put("text", p.text).put("choices", arr).toString())
    }

    fun read(root: File): Pending? = runCatching {
        val f = File(root, FILE)
        if (!f.isFile) return null
        val o = org.json.JSONObject(f.readText())
        val arr = o.getJSONArray("choices")
        val choices = (0 until arr.length()).map { arr.getJSONObject(it).let { c -> Choice(c.getString("target"), c.getString("label")) } }
        if (choices.isEmpty()) null else Pending(o.getLong("t"), o.getString("title"), o.getString("text"), choices)
    }.getOrNull()

    fun clear(root: File) {
        File(root, FILE).delete()
    }
}
