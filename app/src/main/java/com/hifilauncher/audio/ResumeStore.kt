package com.hifilauncher.audio

import android.content.Context

/**
 * Last playback position, so the phone comes back to where the music stopped after a
 * reboot, a crash or a low-memory kill. Three small values in SharedPreferences;
 * apply() writes asynchronously, so saving is cheap enough to do every 30 seconds.
 */
class ResumeStore(ctx: Context) {

    data class Snapshot(val folder: String, val mediaId: String, val positionMs: Long)

    private val prefs = ctx.getSharedPreferences("hifi_resume", Context.MODE_PRIVATE)

    fun save(folder: String, mediaId: String, positionMs: Long) {
        prefs.edit()
            .putString(KEY_FOLDER, folder)
            .putString(KEY_ID, mediaId)
            .putLong(KEY_POS, positionMs)
            .apply()
    }

    fun load(): Snapshot? {
        val folder = prefs.getString(KEY_FOLDER, null) ?: return null
        val id = prefs.getString(KEY_ID, null) ?: return null
        return Snapshot(folder, id, prefs.getLong(KEY_POS, 0L))
    }

    private companion object {
        const val KEY_FOLDER = "folder"
        const val KEY_ID = "media_id"
        const val KEY_POS = "position_ms"
    }
}
