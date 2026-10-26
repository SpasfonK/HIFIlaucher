package com.hifilauncher.audio

/**
 * Folder view of the library, built once from the flat track list (O(n), no tag reading).
 * Pure Kotlin on purpose: it is unit-tested on the JVM.
 *
 * Paths are plain strings as MediaStore reports them ("/storage/emulated/0/Music/Jazz").
 * "" is the virtual top of the file system.
 */
class LibraryTree(tracks: List<Track>) {

    sealed interface Entry {
        data class Folder(val path: String, val name: String, val trackCount: Int) : Entry
        data class Song(val track: Track) : Entry
    }

    private val songs = HashMap<String, MutableList<Track>>()
    private val kids = HashMap<String, MutableSet<String>>()
    private val total = HashMap<String, Int>()

    init {
        for (t in tracks) {
            songs.getOrPut(t.folder) { ArrayList() } += t
            var p = t.folder
            while (true) {
                total[p] = (total[p] ?: 0) + 1
                val up = parentOf(p) ?: break
                kids.getOrPut(up) { LinkedHashSet() } += p
                p = up
            }
        }
    }

    /**
     * Where browsing starts: the first folder that holds songs or branches in two or more.
     * "/storage/emulated/0/Music" instead of four empty levels above it.
     */
    val root: String = run {
        var p = ""
        while (songs[p].isNullOrEmpty() && (kids[p]?.size ?: 0) == 1) p = kids.getValue(p).first()
        p
    }

    /** Sub-folders first (A to Z), then the songs of this folder (track number, then file name). */
    fun entries(path: String): List<Entry> {
        val out = ArrayList<Entry>()
        for (k in sortedKids(path)) out += Entry.Folder(k, nameOf(k), total[k] ?: 0)
        for (s in sortedSongs(path)) out += Entry.Song(s)
        return out
    }

    /** Null at the top of the library. */
    fun parent(path: String): String? = if (path == root) null else parentOf(path)

    fun trackCount(path: String): Int = total[path] ?: 0

    /** Everything below a folder, in browsing order: its songs, then each sub-folder in turn. */
    fun tracksUnder(path: String): List<Track> {
        val out = ArrayList<Track>()
        fun walk(p: String) {
            out += sortedSongs(p)
            for (child in sortedKids(p)) walk(child)
        }
        walk(path)
        return out
    }

    fun nameOf(path: String): String = when {
        path.isEmpty() -> "Library"
        path == "/storage/emulated/0" -> "Internal storage"
        SD_CARD.matches(path) -> "SD card"
        else -> path.substringAfterLast('/')
    }

    private fun sortedKids(p: String): List<String> =
        (kids[p] ?: emptySet<String>()).sortedBy { nameOf(it).lowercase() }

    private fun sortedSongs(p: String): List<Track> =
        (songs[p] ?: emptyList<Track>()).sortedWith(
            compareBy<Track>({ if (it.trackNo > 0) it.trackNo else Int.MAX_VALUE }, { it.path.lowercase() }),
        )

    private fun parentOf(p: String): String? =
        if (p.isEmpty()) null else p.substringBeforeLast('/', "")

    private companion object {
        val SD_CARD = Regex("/storage/[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}")
    }
}

/** Stable key for lists and for remembering where the user was (folders "f:", songs "s:"). */
fun LibraryTree.Entry.entryKey(): String = when (this) {
    is LibraryTree.Entry.Folder -> "f:$path"
    is LibraryTree.Entry.Song -> "s:${track.id}"
}
