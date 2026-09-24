package dev.matt.wtf.client

import com.google.gson.JsonParser
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Ticket 014: kill the client mid-write. The unit cases pin each on-disk state
// a kill can leave behind; the last test produces those states for real, by
// SIGKILLing a JVM that is saving in a loop.
class SaveFilesTest {

    private fun json(n: Int, padding: Int = 0) = """{"n":$n,"pad":"${"x".repeat(padding)}"}"""

    // Stands in for readSave: a value for a complete file, null for anything
    // truncated or empty.
    private fun read(f: File): Int? = try {
        JsonParser.parseString(f.readText()).asJsonObject.get("n").asInt
    } catch (e: Exception) {
        null
    }

    private fun dir(): File = Files.createTempDirectory("wtf-save").toFile()

    @Test
    fun `primary alone is read`() {
        val f = File(dir(), "moods.json").apply { writeText(json(3)) }
        val got = assertNotNull(loadNewestSave(f, ::read))
        assertEquals(3, got.value)
        assertEquals(SaveSource.PRIMARY, got.source)
    }

    @Test
    fun `killed between the renames - the tmp is newer than the backup and wins`() {
        val f = File(dir(), "moods.json")
        bakOf(f).writeText(json(1))
        tmpOf(f).writeText(json(2))
        val got = assertNotNull(loadNewestSave(f, ::read))
        assertEquals(2, got.value, "the .bak is one write old; the .tmp is the save that was being committed")
        assertEquals(SaveSource.TMP, got.source)
    }

    @Test
    fun `first ever save killed before its rename is still found`() {
        val f = File(dir(), "moods.json")
        tmpOf(f).writeText(json(1))
        assertTrue(anySaveExists(f), "the old guard returned early here and never looked at the .tmp")
        assertEquals(1, assertNotNull(loadNewestSave(f, ::read)).value)
    }

    @Test
    fun `a tmp killed mid-write is skipped for the intact primary`() {
        val f = File(dir(), "moods.json").apply { writeText(json(5)) }
        tmpOf(f).writeText(json(6, 100).take(20))
        val got = assertNotNull(loadNewestSave(f, ::read))
        assertEquals(5, got.value)
        assertEquals(SaveSource.PRIMARY, got.source)
    }

    @Test
    fun `recovering from backup quarantines the bad primary so the next save keeps the backup`() {
        val f = File(dir(), "moods.json").apply { writeText("{\"n\":9, trunc") }
        bakOf(f).writeText(json(8))
        val got = assertNotNull(loadNewestSave(f, ::read))
        assertEquals(8, got.value)
        assertEquals(SaveSource.BACKUP, got.source)
        assertTrue(corruptOf(f).exists(), "the unreadable primary is kept as evidence")
        writeAtomically(f, json(10))
        assertEquals(10, read(f))
        assertEquals(8, read(bakOf(f)), "without the quarantine the corrupt primary was rotated over the good backup")
    }

    @Test
    fun `a zero-length primary is never rotated over the backup`() {
        val f = File(dir(), "moods.json").apply { writeText("") }
        bakOf(f).writeText(json(4))
        writeAtomically(f, json(5))
        assertEquals(5, read(f))
        assertEquals(4, read(bakOf(f)))
    }

    @Test
    fun `nothing on disk loads nothing`() {
        val f = File(dir(), "moods.json")
        assertTrue(!anySaveExists(f))
        assertNull(loadNewestSave(f, ::read))
    }

    // The pre-014 order, kept only to count how often it would have lost the
    // newest write in the kill test below.
    private fun oldOrder(f: File): Int? {
        if (!f.exists() && !bakOf(f).exists()) return null
        return (if (f.exists()) read(f) else null)
            ?: (if (bakOf(f).exists()) read(bakOf(f)) else null)
            ?: (if (tmpOf(f).exists()) read(tmpOf(f)) else null)
    }

    // A real process, really killed. SaveWriterChild saves in a loop through the
    // shipped writeAtomically; this test SIGKILLs it at random moments and then
    // loads the way the mod does. The one window that loses data under the old
    // order - between the two renames - is microseconds wide, so the child widens
    // it with a sleep (the test-only hook); the kill itself is not simulated.
    //
    // What this does NOT prove: fsync. The page cache survives a killed process,
    // so only a power cut tests that the bytes reached the disk.
    @Test
    fun `sigkill at random moments never loses the newest complete save`() {
        val f = File(dir(), "moods.json")
        val java = File(System.getProperty("java.home"), "bin/java").path
        val cp = System.getProperty("java.class.path")
        val rnd = Random(14)
        var next = 1
        var betweenRenames = 0
        var partialTmp = 0
        var oldOrderLost = 0
        var otherWindows = 0
        repeat(40) { round ->
            val p = ProcessBuilder(
                java, "-cp", cp, SaveWriterChild::class.java.name, f.path, next.toString(),
                // Half the rounds unwidened, so kills also land where they
                // naturally do: in the write, the fsync, and the renames.
                if (round % 2 == 0) "15" else "0",
            ).redirectErrorStream(true).start()
            Thread.sleep(250L + rnd.nextLong(400))
            p.destroyForcibly()
            p.waitFor(10, TimeUnit.SECONDS)

            val onDisk = listOf(f, tmpOf(f), bakOf(f)).filter { it.exists() }.mapNotNull { read(it) }
            if (onDisk.isEmpty()) return@repeat // killed before the first write finished
            if (!f.exists() && tmpOf(f).exists()) betweenRenames++
            if (tmpOf(f).exists() && read(tmpOf(f)) == null) partialTmp++
            if (f.exists()) otherWindows++
            val newest = onDisk.max()
            if (oldOrder(f) != newest) oldOrderLost++

            val got = loadNewestSave(f, ::read)
            assertNotNull(got, "round $round: nothing loaded with $onDisk on disk")
            assertEquals(newest, got.value, "round $round: loaded ${got.value} from ${got.source}, newest on disk is $newest")
            next = newest + 1
        }
        println("kill test: $betweenRenames kills between the renames, $partialTmp mid-tmp, $otherWindows with the primary in place, old order would have lost the newest save $oldOrderLost times")
        assertTrue(betweenRenames > 0, "no kill landed between the renames - the test did not test the window")
    }
}

// The process the kill test kills: saves n, n+1, n+2 ... forever.
object SaveWriterChild {
    @JvmStatic
    fun main(args: Array<String>) {
        val file = File(args[0])
        var n = args[1].toInt()
        val widenMs = args[2].toLong()
        val pad = "x".repeat(200_000) // big enough that a kill can land mid-write too
        while (true) {
            writeAtomically(file, """{"n":$n,"pad":"$pad"}""", betweenRenames = { Thread.sleep(widenMs) })
            n++
        }
    }
}
