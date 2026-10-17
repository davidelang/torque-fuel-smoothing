package com.davidlang.torque.fuelsmoothing

import org.json.JSONArray
import org.json.JSONObject

/**
 * Field codec for backup-manifest.json. Reads and writes the fields.
 * Does not accept or reject a backup based on those fields.
 */
internal object BackupManifest {
    const val NAME = "backup-manifest.json"
    const val FORMAT = "davidlang-android-app-backup"
    const val FORMAT_VERSION = 1
    const val APP_KEY = "fuelsmoothing"
    val EXCLUDED = listOf("*.tmp", "*.part")

    data class Info(
        val applicationId: String,
        val versionName: String,
        val versionCode: Int,
        val buildType: String,
        val androidSdkInt: Int,
    )

    data class Entry(val path: String, val bytes: Long, val sha256: String)

    data class Fields(
        val format: String,
        val formatVersion: Int,
        val appKey: String,
        val applicationId: String,
        val versionName: String,
        val versionCode: Int,
        val buildType: String,
        val dbSchemaVersion: Int?,
        val createdUtc: String,
        val androidSdkInt: Int,
        val entries: List<Entry>,
        val excluded: List<String>,
    )

    fun encode(fields: Fields): String {
        val entries = JSONArray()
        for (e in fields.entries) {
            entries.put(
                JSONObject()
                    .put("path", e.path)
                    .put("bytes", e.bytes)
                    .put("sha256", e.sha256),
            )
        }
        val excluded = JSONArray()
        for (x in fields.excluded) excluded.put(x)
        val db = if (fields.dbSchemaVersion == null) JSONObject.NULL else fields.dbSchemaVersion
        return JSONObject()
            .put("format", fields.format)
            .put("formatVersion", fields.formatVersion)
            .put("appKey", fields.appKey)
            .put("applicationId", fields.applicationId)
            .put("versionName", fields.versionName)
            .put("versionCode", fields.versionCode)
            .put("buildType", fields.buildType)
            .put("dbSchemaVersion", db)
            .put("createdUtc", fields.createdUtc)
            .put("androidSdkInt", fields.androidSdkInt)
            .put("entries", entries)
            .put("excluded", excluded)
            .toString(2)
    }

    fun decode(text: String): Fields {
        val o = JSONObject(text)
        val arr = o.getJSONArray("entries")
        val entries = ArrayList<Entry>(arr.length())
        for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
            entries.add(Entry(e.getString("path"), e.getLong("bytes"), e.getString("sha256")))
        }
        val ex = o.getJSONArray("excluded")
        val excluded = ArrayList<String>(ex.length())
        for (i in 0 until ex.length()) excluded.add(ex.getString(i))
        if (!o.has("dbSchemaVersion")) throw IllegalArgumentException("datatransfer: manifest missing dbSchemaVersion")
        val db = if (o.isNull("dbSchemaVersion")) null else o.getInt("dbSchemaVersion")
        return Fields(
            format = o.getString("format"),
            formatVersion = o.getInt("formatVersion"),
            appKey = o.getString("appKey"),
            applicationId = o.getString("applicationId"),
            versionName = o.getString("versionName"),
            versionCode = o.getInt("versionCode"),
            buildType = o.getString("buildType"),
            dbSchemaVersion = db,
            createdUtc = o.getString("createdUtc"),
            androidSdkInt = o.getInt("androidSdkInt"),
            entries = entries,
            excluded = excluded,
        )
    }
}
