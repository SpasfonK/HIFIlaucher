package com.hifilauncher.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryTreeTest {

    private val music = "/storage/emulated/0/Music"

    // Track has no Android dependency at construction time (its Uri is derived on demand).
    private fun t(id: Long, folder: String, file: String, trackNo: Int = 0) =
        Track(id, "$folder/$file", file.substringBeforeLast('.'), "Artist", "Album", 1_000L, trackNo, folder)

    private val tree = LibraryTree(
        listOf(
            t(1, "$music/Jazz/Kind of Blue", "02 b.flac", trackNo = 2),
            t(2, "$music/Jazz/Kind of Blue", "01 a.flac", trackNo = 1),
            t(3, "$music/Jazz/Other", "x.flac"),
            t(4, "$music/Rock", "r.flac"),
        ),
    )

    @Test
    fun root_skips_the_empty_levels_above_the_music() {
        assertEquals(music, tree.root)
    }

    @Test
    fun root_of_a_single_folder_is_that_folder() {
        val single = LibraryTree(listOf(t(1, "$music/Album", "a.flac"), t(2, "$music/Album", "b.flac")))
        assertEquals("$music/Album", single.root)
    }

    @Test
    fun empty_library_has_an_empty_root_and_no_entries() {
        val empty = LibraryTree(emptyList())
        assertEquals("", empty.root)
        assertEquals(emptyList<LibraryTree.Entry>(), empty.entries(""))
    }

    @Test
    fun folders_come_first_in_alphabetical_order_with_recursive_counts() {
        val e = tree.entries(music)
        assertEquals(
            listOf(
                LibraryTree.Entry.Folder("$music/Jazz", "Jazz", 3),
                LibraryTree.Entry.Folder("$music/Rock", "Rock", 1),
            ),
            e,
        )
        val jazz = tree.entries("$music/Jazz").map { (it as LibraryTree.Entry.Folder).name }
        assertEquals(listOf("Kind of Blue", "Other"), jazz)
    }

    @Test
    fun songs_follow_track_numbers() {
        val ids = tree.entries("$music/Jazz/Kind of Blue").map { (it as LibraryTree.Entry.Song).track.id }
        assertEquals(listOf(2L, 1L), ids)
    }

    @Test
    fun parent_stops_at_the_root() {
        assertNull(tree.parent(music))
        assertEquals(music, tree.parent("$music/Jazz"))
        assertEquals("$music/Jazz", tree.parent("$music/Jazz/Other"))
    }

    @Test
    fun tracks_under_a_folder_are_in_browsing_order() {
        assertEquals(listOf(2L, 1L, 3L), tree.tracksUnder("$music/Jazz").map { it.id })
        assertEquals(4, tree.tracksUnder(music).size)
    }

    @Test
    fun friendly_names_for_storage_roots() {
        assertEquals("Internal storage", tree.nameOf("/storage/emulated/0"))
        assertEquals("SD card", tree.nameOf("/storage/ABCD-1234"))
        assertEquals("Library", tree.nameOf(""))
        assertEquals("Jazz", tree.nameOf("$music/Jazz"))
    }
}
