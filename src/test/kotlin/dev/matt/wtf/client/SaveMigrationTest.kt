package dev.matt.wtf.client

import com.google.gson.GsonBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// A save written by an older version has to survive the upgrade. Gson builds
// these classes without running the Kotlin constructor, so a field that is
// absent from the JSON lands as null even where the type says it cannot be -
// which is exactly how a truncated save NPE'd the load in proto.2. Everything
// here runs on a plain JVM against the same shapes the mod persists.
class SaveMigrationTest {

    private val gson = GsonBuilder().setPrettyPrinting().create()

    private fun parse(json: String) = gson.fromJson(json, WTFClient.ShulkerSave::class.java)

    // Mirrors readSave: normalise the tree, then bind. Calls the shipped
    // normaliser so the test fails if it stops filling a field.
    private fun parseNormalized(json: String): WTFClient.ShulkerSave {
        val root = com.google.gson.JsonParser.parseString(json).asJsonObject
        val normalize = WTFClient::class.java
            .getDeclaredMethod("normalizeEntries", com.google.gson.JsonObject::class.java)
            .apply { isAccessible = true }
        normalize.invoke(WTFClient, root)
        return gson.fromJson(root, WTFClient.ShulkerSave::class.java)
    }

    // Exactly what 2.3.1 (the Modrinth release) writes: version 15 and the
    // nextSerial field that v16 dropped.
    private val release231 = """
        {
          "tracked_shulkers": {
            "abc-123": {
              "uuid": "abc-123",
              "state": "ex-inv",
              "entity_id": "",
              "dim": "minecraft:overworld",
              "coords": "100,64,200",
              "last_update_time": "1750000000000",
              "name": "Kitt",
              "happy": true,
              "contentHash": "deadbeef",
              "type": "minecraft:purple_shulker_box",
              "from": "chest:uuid",
              "lastKnown": false,
              "slotIndex": 4,
              "firstSeen": 1749000000000
            }
          },
          "version": 15,
          "nextSerial": 1,
          "markerIcon": "★",
          "markerColorIdx": 0,
          "chestSlotLedger": { "minecraft:overworld:100_64_200": { "s4": "abc-123" } }
        }
    """.trimIndent()

    @Test
    fun `a release-format save parses whole`() {
        val save = parse(release231)
        assertEquals(15, save.version)
        assertEquals(1, save.tracked_shulkers.size)

        val entry = save.tracked_shulkers.getValue("abc-123")
        assertEquals("Kitt", entry.name)
        assertEquals("ex-inv", entry.state)
        assertEquals("100,64,200", entry.coords)
        assertEquals("minecraft:purple_shulker_box", entry.type)
        assertEquals(4, entry.slotIndex)
        assertEquals(1749000000000L, entry.firstSeen)
        assertTrue(entry.happy)
        assertEquals("★", save.markerIcon)
        assertEquals("abc-123", save.chestSlotLedger?.get("minecraft:overworld:100_64_200")?.get("s4"))
    }

    @Test
    fun `nextSerial is ignored rather than fatal`() {
        // v16 dropped the field. Gson has nowhere to put it and must not care.
        assertNotNull(parse(release231).tracked_shulkers)
    }

    @Test
    fun `a v16 save round-trips`() {
        val original = parse(release231)
        val reparsed = parse(gson.toJson(original.copy(version = 16)))
        assertEquals(16, reparsed.version)
        assertEquals(original.tracked_shulkers.keys, reparsed.tracked_shulkers.keys)
        assertEquals(
            original.tracked_shulkers.getValue("abc-123").name,
            reparsed.tracked_shulkers.getValue("abc-123").name
        )
    }

    @Test
    fun `a truncated save survives, because every field of the wrapper has a default`() {
        // ShulkerSave has a default for every parameter, so Kotlin emits a no-arg
        // constructor and Gson uses it - the defaults do apply here. The null
        // check in readSave stays anyway: it costs nothing and this guarantee
        // disappears the moment someone adds a parameter without a default.
        assertEquals(emptyMap(), parse("{}").tracked_shulkers)
        assertEquals(0, parse("{}").version)
        assertEquals(emptyMap(), parse("""{"version":16}""").tracked_shulkers)
    }

    @Test
    fun `an entry written before a field existed does NOT get the Kotlin default`() {
        // ShulkerState has required parameters, so there is no no-arg path and
        // Gson allocates the object directly: an absent field keeps the JVM zero
        // value, not the default in the class. slotIndex is the one that bites -
        // 0 is a real slot, -1 means "no slot" - and six entries on this machine
        // are missing it. This is why readSave normalises the tree first.
        val raw = parse(
            """
            { "tracked_shulkers": { "old-1": { "uuid": "old-1", "state": "inv" } }, "version": 14 }
            """.trimIndent()
        ).tracked_shulkers.getValue("old-1")
        assertEquals(0, raw.slotIndex, "unnormalised: absent slotIndex reads as slot 0")

        val fixed = parseNormalized(
            """
            { "tracked_shulkers": { "old-1": { "uuid": "old-1", "state": "inv" } }, "version": 14 }
            """.trimIndent()
        ).tracked_shulkers.getValue("old-1")
        assertEquals(-1, fixed.slotIndex, "normalised: no slot")
        assertEquals("minecraft:shulker_box", fixed.type)
        assertEquals("", fixed.name)
        assertEquals("", fixed.coords)
        assertNotNull(fixed.uuid)
    }

    @Test
    fun `normalising leaves a real slot alone`() {
        val fixed = parseNormalized(release231).tracked_shulkers.getValue("abc-123")
        assertEquals(4, fixed.slotIndex)
        assertEquals("Kitt", fixed.name)
        assertEquals("minecraft:purple_shulker_box", fixed.type)
    }

    @Test
    fun `a real chest slot 0 is preserved, not mistaken for an absent field`() {
        val fixed = parseNormalized(
            """
            {
              "tracked_shulkers": {
                "in-slot-0": { "uuid": "in-slot-0", "state": "enderchest", "slotIndex": 0 }
              },
              "version": 16
            }
            """.trimIndent()
        ).tracked_shulkers.getValue("in-slot-0")
        assertEquals(0, fixed.slotIndex)
    }
}
