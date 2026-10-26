package com.hifilauncher.audio

import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class Track(
    val id: Long,
    val path: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val trackNo: Int,
    val folder: String,
) {
    /** Derived from the id, so a Track can be built (and unit-tested) without Android. */
    val uri: Uri get() = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)

    companion object {
        const val EXTRA_DURATION = "durationMs"
        const val EXTRA_FOLDER = "folder"
    }
}

/**
 * The URI goes in twice (setUri and requestMetadata.mediaUri) and the service resolves it
 * in onAddMediaItems, so playback works whichever of the two survives the controller to
 * session hop. Duration travels in extras so the service can compute an average bitrate
 * without waiting for the player to be ready.
 */
fun Track.toMediaItem(): MediaItem =
    MediaItem.Builder()
        .setMediaId(id.toString())
        .setUri(uri)
        .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setTrackNumber(trackNo.takeIf { it > 0 })
                .setExtras(
                    Bundle().apply {
                        putLong(Track.EXTRA_DURATION, durationMs)
                        putString(Track.EXTRA_FOLDER, folder)
                    },
                )
                .build(),
        )
        .build()

/**
 * Reads the system media index instead of walking the file tree: one query, no tag
 * parsing at scan time (cheap on an old phone). Ordered by path so folders stay grouped
 * and files stay in filename order, like a folder-based player.
 *
 * MediaStore.Audio.Media.DATA is deprecated from API 29 but is the simplest correct
 * choice on the target (Android 8.0).
 */
class LibraryRepository(private val resolver: ContentResolver) {

    suspend fun scan(): List<Track> = withContext(Dispatchers.IO) {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.TRACK,
        )
        val selection =
            "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} > 0"
        val order = "${MediaStore.Audio.Media.DATA} COLLATE NOCASE ASC"

        val out = ArrayList<Track>()
        resolver.query(collection, projection, selection, null, order)?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val iPath = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val iTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val iArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val iAlbum = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val iDuration = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val iTrack = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
            while (c.moveToNext()) {
                val path = c.getString(iPath) ?: continue
                val file = File(path)
                val id = c.getLong(iId)
                out += Track(
                    id = id,
                    path = path,
                    title = c.getString(iTitle).clean() ?: file.nameWithoutExtension,
                    artist = c.getString(iArtist).clean() ?: "Unknown artist",
                    album = c.getString(iAlbum).clean() ?: file.parentFile?.name.orEmpty(),
                    durationMs = c.getLong(iDuration),
                    // MediaStore encodes disc and track as DTTT (1003 = disc 1, track 3).
                    trackNo = c.getInt(iTrack) % 1000,
                    folder = file.parent.orEmpty(),
                )
            }
        }
        out
    }

    private fun String?.clean(): String? =
        this?.takeIf { it.isNotBlank() && it != MediaStore.UNKNOWN_STRING }
}
