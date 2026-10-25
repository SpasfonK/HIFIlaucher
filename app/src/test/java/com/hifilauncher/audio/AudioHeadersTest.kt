package com.hifilauncher.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

// Goes in src/test/java/com/hifilauncher/audio/. Needs testImplementation("junit:junit:4.13.2").
class AudioHeadersTest {

    /** "fLaC", a STREAMINFO block header, then STREAMINFO with the 4 interesting bytes set. */
    private fun flac(b18: Int, b19: Int, b20: Int, b21: Int, prefix: ByteArray = ByteArray(0)): ByteArray {
        val body = ByteArray(42)
        "fLaC".forEachIndexed { i, ch -> body[i] = ch.code.toByte() }
        body[4] = 0x80.toByte() // last metadata block, type 0 (STREAMINFO)
        body[7] = 34            // STREAMINFO length
        body[18] = b18.toByte()
        body[19] = b19.toByte()
        body[20] = b20.toByte()
        body[21] = b21.toByte()
        return prefix + body
    }

    @Test
    fun flac_24bit_96k() {
        // 96000 = 0x17700 -> bytes 0x17 0x70 0x0_; stereo = 1 -> 0x02; 24 bit -> 23 = 0b10111 -> 0x01 | 0x70
        val info = AudioHeaders.parseFlac(flac(0x17, 0x70, 0x03, 0x70))!!
        assertEquals("FLAC", info.codec)
        assertEquals(96_000, info.sampleRateHz)
        assertEquals(24, info.bitDepth)
        assertEquals("24 bit / 96 kHz", info.detail())
    }

    @Test
    fun flac_16bit_44k1() {
        // 44100 = 0xAC44 -> 0x0A 0xC4 0x4_; stereo -> 0x02; 16 bit -> 15 = 0b01111 -> 0xF0
        val info = AudioHeaders.parseFlac(flac(0x0A, 0xC4, 0x42, 0xF0))!!
        assertEquals(44_100, info.sampleRateHz)
        assertEquals(16, info.bitDepth)
    }

    @Test
    fun flac_after_an_id3_tag() {
        // "ID3", version, flags, then a syncsafe size of 20 bytes
        val id3 = ByteArray(10 + 20)
        "ID3".forEachIndexed { i, ch -> id3[i] = ch.code.toByte() }
        id3[9] = 20
        val info = AudioHeaders.parseFlac(flac(0x17, 0x70, 0x03, 0x70, prefix = id3))!!
        assertEquals(96_000, info.sampleRateHz)
        assertEquals(24, info.bitDepth)
    }

    @Test
    fun flac_rejects_other_data_and_truncated_input() {
        assertNull(AudioHeaders.parseFlac(ByteArray(64)))
        assertNull(AudioHeaders.parseFlac(flac(0x17, 0x70, 0x03, 0x70).copyOf(10)))
    }

    @Test
    fun wav_16bit_44k1_stereo() {
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16)
            putShort(1); putShort(2); putInt(44_100); putInt(176_400); putShort(4); putShort(16)
            put("data".toByteArray()); putInt(0)
        }.array()
        val info = AudioHeaders.parseWav(b)!!
        assertEquals("WAV", info.codec)
        assertEquals(44_100, info.sampleRateHz)
        assertEquals(16, info.bitDepth)
        assertEquals(1411, info.bitrateKbps) // 44100 * 2 * 16 / 1000
    }

    @Test
    fun wav_with_a_chunk_before_fmt() {
        val b = ByteBuffer.allocate(12 + 8 + 4 + 8 + 16).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(0); put("WAVE".toByteArray())
            put("LIST".toByteArray()); putInt(4); put(ByteArray(4))
            put("fmt ".toByteArray()); putInt(16)
            putShort(1); putShort(2); putInt(96_000); putInt(576_000); putShort(6); putShort(24)
        }.array()
        val info = AudioHeaders.parseWav(b)!!
        assertEquals(96_000, info.sampleRateHz)
        assertEquals(24, info.bitDepth)
    }

    @Test
    fun wav_rejects_other_data() {
        assertNull(AudioHeaders.parseWav(ByteArray(64)))
    }
}
