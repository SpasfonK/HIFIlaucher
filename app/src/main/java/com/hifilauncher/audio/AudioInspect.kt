package com.hifilauncher.audio

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.io.IOException

data class AudioInfo(
    val codec: String,
    val sampleRateHz: Int,
    val bitDepth: Int?,
    val bitrateKbps: Int?,
) {
    /** "24 bit / 96 kHz", "44.1 kHz" for lossy files, or "" when unknown. */
    fun detail(): String {
        val parts = ArrayList<String>(2)
        bitDepth?.let { parts += "$it bit" }
        if (sampleRateHz > 0) {
            parts += if (sampleRateHz % 1000 == 0) "${sampleRateHz / 1000} kHz"
            else "%.1f kHz".format(sampleRateHz / 1000f)
        }
        return parts.joinToString(" / ")
    }
}

/**
 * Pure header parsers (no Android types) so they can be unit-tested on the JVM.
 * They give the real bit depth, which the platform APIs do not expose on Android 8.
 */
object AudioHeaders {

    /** FLAC: optional ID3v2 tag, "fLaC", then the STREAMINFO block. Needs 22 bytes after the tag. */
    fun parseFlac(b: ByteArray): AudioInfo? {
        val o = skipId3(b)
        if (b.size < o + 22 || !b.hasTag(o, "fLaC")) return null
        val b18 = b[o + 18].toInt() and 0xFF
        val b19 = b[o + 19].toInt() and 0xFF
        val b20 = b[o + 20].toInt() and 0xFF
        val b21 = b[o + 21].toInt() and 0xFF
        val sampleRate = (b18 shl 12) or (b19 shl 4) or (b20 shr 4) // 20 bits
        val bitDepth = (((b20 and 1) shl 4) or (b21 shr 4)) + 1      // 5 bits, stored minus one
        return AudioInfo("FLAC", sampleRate, bitDepth, null)
    }

    /** WAV: walks the RIFF chunks until "fmt ". Bitrate is exact for PCM. */
    fun parseWav(b: ByteArray): AudioInfo? {
        if (b.size < 12 || !b.hasTag(0, "RIFF") || !b.hasTag(8, "WAVE")) return null
        var p = 12
        while (p + 8 <= b.size) {
            val size = le32(b, p + 4)
            if (size < 0) return null
            if (b.hasTag(p, "fmt ")) {
                if (p + 24 > b.size) return null
                val channels = le16(b, p + 10)
                val rate = le32(b, p + 12)
                val bits = le16(b, p + 22)
                return AudioInfo("WAV", rate, bits, rate * channels * bits / 1000)
            }
            p += 8 + size + (size and 1) // chunks are padded to an even length
        }
        return null
    }

    internal fun skipId3(b: ByteArray): Int {
        if (b.size < 10 || !b.hasTag(0, "ID3")) return 0
        val size = ((b[6].toInt() and 0x7F) shl 21) or ((b[7].toInt() and 0x7F) shl 14) or
            ((b[8].toInt() and 0x7F) shl 7) or (b[9].toInt() and 0x7F)
        return 10 + size
    }

    private fun ByteArray.hasTag(at: Int, tag: String): Boolean {
        if (at < 0 || at + tag.length > size) return false
        for (i in tag.indices) if (this[at + i] != tag[i].code.toByte()) return false
        return true
    }

    private fun le16(b: ByteArray, at: Int) =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, at: Int) =
        le16(b, at) or (le16(b, at + 2) shl 16)
}

/**
 * Format of the file that is playing. Called once per track on a background thread
 * (never at scan time). The bit depth is the file's, not necessarily what the device
 * outputs: Android's mixer sits in between.
 */
object AudioFormatProbe {

    fun probe(ctx: Context, uri: Uri, durationMs: Long): AudioInfo {
        val resolver = ctx.contentResolver
        val head: ByteArray? = try {
            resolver.openInputStream(uri)?.use { s ->
                val buf = ByteArray(4096)
                val n = s.read(buf)
                if (n > 0) buf.copyOf(n) else null
            }
        } catch (e: IOException) {
            null
        }
        val sizeBytes = try {
            resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        } catch (e: Exception) {
            -1L
        }

        val fromHeader = head?.let { AudioHeaders.parseFlac(it) ?: AudioHeaders.parseWav(it) }
        if (fromHeader != null) {
            // bits per millisecond = kilobits per second. FLAC: average over the whole file.
            val kbps = fromHeader.bitrateKbps
                ?: if (sizeBytes > 0 && durationMs > 0) (sizeBytes * 8 / durationMs).toInt() else null
            return fromHeader.copy(bitrateKbps = kbps)
        }
        return probeWithPlatform(ctx, uri)
    }

    private fun probeWithPlatform(ctx: Context, uri: Uri): AudioInfo {
        var codec = "Audio"
        var sampleRate = 0
        var kbps: Int? = null

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(ctx, uri, null)
            if (extractor.trackCount > 0) {
                val f = extractor.getTrackFormat(0)
                codec = codecName(f.getString(MediaFormat.KEY_MIME))
                if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                    sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                }
                if (f.containsKey(MediaFormat.KEY_BIT_RATE)) {
                    kbps = f.getInteger(MediaFormat.KEY_BIT_RATE) / 1000
                }
            }
        } catch (e: Exception) {
            // Unreadable file: show what we have (nothing) rather than crash the service.
        } finally {
            extractor.release()
        }

        if (kbps == null) {
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(ctx, uri)
                kbps = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                    ?.toIntOrNull()?.let { it / 1000 }
            } catch (e: Exception) {
                // same as above
            } finally {
                mmr.release()
            }
        }
        return AudioInfo(codec, sampleRate, null, kbps)
    }

    private fun codecName(mime: String?): String = when (mime) {
        "audio/mpeg" -> "MP3"
        "audio/mp4a-latm" -> "AAC"
        "audio/flac" -> "FLAC"
        "audio/vorbis" -> "Vorbis"
        "audio/opus" -> "Opus"
        "audio/raw" -> "PCM"
        "audio/alac" -> "ALAC"
        null -> "Audio"
        else -> (mime ?: "").substringAfter('/').uppercase()
    }
}

/** Embedded art first, then a cover file in the track's folder. Decoded at most ~maxPx wide. */
object CoverLoader {

    private val folderNames = listOf("cover.jpg", "folder.jpg", "front.jpg", "cover.png", "folder.png")

    fun load(ctx: Context, track: Track, maxPx: Int = 800): ImageBitmap? {
        val bytes = embedded(ctx, track.uri) ?: folderArt(track.path) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxPx && bounds.outHeight / (sample * 2) >= maxPx) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
    }

    private fun embedded(ctx: Context, uri: Uri): ByteArray? {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(ctx, uri)
            mmr.embeddedPicture
        } catch (e: Exception) {
            null
        } finally {
            mmr.release()
        }
    }

    private fun folderArt(path: String): ByteArray? {
        val dir = File(path).parentFile ?: return null
        val names = dir.list() ?: return null
        val hit = names.firstOrNull { n -> folderNames.any { it.equals(n, ignoreCase = true) } }
            ?: return null
        return try {
            File(dir, hit).readBytes()
        } catch (e: IOException) {
            null
        }
    }
}
