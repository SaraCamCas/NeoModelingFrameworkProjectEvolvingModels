package geodes.sms.neo4j.io.type

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * Unit tests for ChangeLog: no Neo4j database required.
 *
 * ChangeLog is an `object` (shared global state), so every test starts
 * from a clean state thanks to reset() in setUp().
 */
class ChangeLogTest {

    private lateinit var logFile: File

    // @TempDir: JUnit provides a temporary folder, deleted after the test.
    @BeforeEach
    fun setUp(@TempDir dir: Path) {
        // The "log" subfolder deliberately does not exist yet: this also tests folder creation
        logFile = dir.resolve("log/change-log.txt").toFile()
        ChangeLog.enabled = true
        ChangeLog.reset(logFile.path)
    }

    @AfterEach
    fun tearDown() {
        ChangeLog.enabled = true
    }

    @Test
    fun resetCreatesFolderAndEmptyFile() {
        assertTrue(logFile.exists())
        assertEquals("", logFile.readText())
    }

    @Test
    fun seqStartsAtOneAndIncrements() {
        ChangeLog.log(ChangeType.CREATE, Target.NODE, node = NodeRef(1, "A", persisted = false))
        ChangeLog.log(ChangeType.CREATE, Target.NODE, node = NodeRef(2, "B", persisted = false))

        assertEquals(listOf(1, 2), ChangeLog.events.map { it.seq })
    }

    @Test
    fun endBatchIncrementsBatchNumber() {
        ChangeLog.log(ChangeType.CREATE, Target.NODE)
        ChangeLog.endBatch()
        ChangeLog.log(ChangeType.UPDATE, Target.NODE)

        assertEquals(listOf(1, 2), ChangeLog.events.map { it.batch })
    }

    @Test
    fun nodeRefDistinguishesAliasAndId() {
        assertEquals("#17(EAttribute)", NodeRef(17, "EAttribute").toString())
        assertEquals("~3", NodeRef(3, persisted = false).toString())
    }

    @Test
    fun formatOmitsAbsentFields() {
        ChangeLog.log(ChangeType.COMMIT, Target.BATCH, detail = "begin")

        val line = logFile.readLines().single()
        assertTrue(line.endsWith("| COMMIT | BATCH | detail=begin"), line)
        assertFalse(line.contains("node="), line)
        assertFalse(line.contains("props="), line)
    }

    // This test FAILS until format() ends with .trimEnd():
    // with no fields, the line ends with "| " (a trailing space).
    @Test
    fun lineWithoutFieldsHasNoTrailingSpace() {
        ChangeLog.log(ChangeType.COMMIT, Target.BATCH)

        val line = logFile.readLines().single()
        assertFalse(line.endsWith(" "), "[$line]")
    }

    @Test
    fun fileHasOneLinePerEventInOrder() {
        ChangeLog.log(ChangeType.CREATE, Target.NODE, node = NodeRef(1, "A", persisted = false))
        ChangeLog.log(ChangeType.REMOVE, Target.NODE, node = NodeRef(9, "B"))

        val lines = logFile.readLines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("| CREATE | NODE | node=~1(A)"), lines[0])
        assertTrue(lines[1].contains("| REMOVE | NODE | node=#9(B)"), lines[1])
    }

    @Test
    fun relationEventShowsBothEndsAndRefType() {
        ChangeLog.log(
            ChangeType.CREATE, Target.CONTAINMENT,
            node = NodeRef(17), other = NodeRef(4, "Node4", persisted = false), refType = "ref"
        )

        val line = logFile.readLines().single()
        assertTrue(line.endsWith("node=#17 other=~4(Node4) ref=ref"), line)
    }

    @Test
    fun propsAreCopiedAtLogTime() {
        val props = hashMapOf<String, Any?>("name" to "title")
        ChangeLog.log(ChangeType.UPDATE, Target.NODE, node = NodeRef(17, "EAttribute"), props = props)

        props.clear() // the framework clears its map after a commit: the log must keep its own copy

        assertEquals(mapOf<String, Any?>("name" to "title"), ChangeLog.events.single().props)
    }

    @Test
    fun disabledLogWritesNothing() {
        ChangeLog.enabled = false
        ChangeLog.log(ChangeType.CREATE, Target.NODE)

        assertTrue(ChangeLog.events.isEmpty())
        assertEquals("", logFile.readText())
    }

    @Test
    fun resetClearsEventsFileAndCounters() {
        ChangeLog.log(ChangeType.CREATE, Target.NODE)
        ChangeLog.endBatch()

        ChangeLog.reset(logFile.path)
        assertTrue(ChangeLog.events.isEmpty())
        assertEquals("", logFile.readText())

        ChangeLog.log(ChangeType.CREATE, Target.NODE)
        assertEquals(1, ChangeLog.events.single().seq)
        assertEquals(1, ChangeLog.events.single().batch)
    }
}