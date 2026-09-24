package dev.matt.wtf.client

import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

// The save's three files and what each one means, free of Minecraft so the
// ordering can be tested by killing a real process mid-write (SaveFilesTest).
//
//   moods.json      the last completed write
//   moods.json.bak  the write before that
//   moods.json.tmp  a write that was interrupted - it only exists at all if the
//                   process died between starting a write and the final rename
//
// writeAtomically goes tmp (fsynced) -> rotate primary to .bak -> rename tmp
// over primary. Kill it between the two renames and the primary is GONE, .bak
// is one write old, and .tmp is the newest complete save there is.

fun tmpOf(file: File) = File(file.parentFile, file.name + ".tmp")
fun bakOf(file: File) = File(file.parentFile, file.name + ".bak")
fun corruptOf(file: File) = File(file.parentFile, file.name + ".corrupt")

enum class SaveSource { PRIMARY, TMP, BACKUP }

class LoadedSave<T>(val value: T, val source: SaveSource)

// Newest readable copy first. A .tmp that parses is newer than anything else on
// disk by construction: every write that completes renames it away, and every
// write that fails deletes it. One that does not parse is a write killed while
// the bytes were still going out, and the primary beside it is intact.
//
// This used to try primary, then .bak, then .tmp - and returned early when
// neither of the first two existed. Both orders are wrong for the one window a
// kill actually leaves ambiguous: primary missing, .bak good, .tmp newer. The
// .bak always won, so the last write was thrown away, and a first-ever save
// killed before its rename (only a .tmp on disk) was not even looked at.
//
// A primary that exists but cannot be read is moved to .corrupt once something
// else has been recovered. Left in place, the next save would rotate it over
// .bak - destroying the good copy we just recovered from with the bad one.
fun <T> loadNewestSave(file: File, read: (File) -> T?, log: (String) -> Unit = {}): LoadedSave<T>? {
    val order = listOf(tmpOf(file) to SaveSource.TMP, file to SaveSource.PRIMARY, bakOf(file) to SaveSource.BACKUP)
    for ((candidate, source) in order) {
        if (!candidate.exists()) continue
        val value = read(candidate) ?: continue
        if (source != SaveSource.PRIMARY && file.exists() && read(file) == null) {
            try {
                Files.move(file.toPath(), corruptOf(file).toPath(), StandardCopyOption.REPLACE_EXISTING)
                log("load: unreadable ${file.name} moved to ${corruptOf(file).name}")
            } catch (e: Exception) {
                log("load: could not move unreadable ${file.name} aside: $e")
            }
        }
        return LoadedSave(value, source)
    }
    return null
}

fun anySaveExists(file: File) = file.exists() || bakOf(file).exists() || tmpOf(file).exists()

// save() runs from ~20 call sites including per-tick sweeps, so a plain
// writeText() leaves a truncated file behind on any crash mid-write - and a
// truncated file is exactly what load() cannot parse. Write a temp file,
// rotate the current one to .bak, then move the temp into place.
//
// The rename alone is not enough on a power cut. Without fsync the file data
// sits in page cache while the rename hits the journal, so the machine comes
// back with a 0-byte moods.json - and because save() runs again seconds later,
// that empty file gets rotated into .bak too and both copies are gone. Flush
// the temp's contents before renaming, and flush the directory after, so the
// rename itself is durable. (A kill -9 cannot test that part: the page cache
// survives a killed process. Only the ordering is tested.)
//
// `betweenRenames` exists for SaveFilesTest alone, which widens the one window
// that matters so a real SIGKILL can land in it. Shipped code never passes it.
fun writeAtomically(file: File, json: String, log: (String) -> Unit = {}, betweenRenames: () -> Unit = {}) {
    val tmp = tmpOf(file)
    try {
        java.io.FileOutputStream(tmp).use { out ->
            out.write(json.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
    } catch (e: Exception) {
        // A half-written temp never parses, so the loader would skip it - but
        // there is no reason to leave one lying around either.
        tmp.delete()
        throw e
    }
    // A zero-length primary is the post-crash corpse of an older write.
    // Rotating it over .bak destroys the last good copy, so don't.
    if (file.exists() && file.length() > 0) {
        Files.move(file.toPath(), bakOf(file).toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
    betweenRenames()
    // From here on the temp is complete and synced. If this rename fails it is
    // the newest save there is, so it stays for loadNewestSave to find.
    Files.move(
        tmp.toPath(), file.toPath(),
        StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.ATOMIC_MOVE
    )
    syncDir(file.parentFile, log)
}

// Renames are only durable once the directory entry is on disk. Best effort:
// Windows refuses to open a directory as a channel, and NTFS journals the
// rename anyway, so a failure here is logged and ignored.
private fun syncDir(dir: File, log: (String) -> Unit) {
    try {
        FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
    } catch (e: Exception) {
        log("save: dir sync skipped: $e")
    }
}
