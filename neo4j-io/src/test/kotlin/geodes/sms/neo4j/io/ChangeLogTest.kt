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
        ChangeLog.enabled = false // back to the default, so other test classes in the same JVM are not logged
    }

    // reset() crée le dossier du journal s'il n'existe pas et vide le fichier.
    @Test
    fun resetCreatesFolderAndEmptyFile() {
        assertTrue(logFile.exists())
        assertEquals("", logFile.readText())
    }

    // Le numéro d'ordre (seq) commence à 1 et augmente de 1 à chaque événement.
    @Test
    fun seqStartsAtOneAndIncrements() {
        ChangeLog.log(ChangeType.CREATE, Target.NODE, node = NodeRef(1, "A", persisted = false))
        ChangeLog.log(ChangeType.CREATE, Target.NODE, node = NodeRef(2, "B", persisted = false))

        assertEquals(listOf(1, 2), ChangeLog.events.map { it.seq })
    }

    // endBatch() fait passer au lot suivant : les événements suivants portent le numéro de lot + 1.
    @Test
    fun endBatchIncrementsBatchNumber() {
        ChangeLog.log(ChangeType.CREATE, Target.NODE)
        ChangeLog.endBatch()
        ChangeLog.log(ChangeType.UPDATE, Target.NODE)

        assertEquals(listOf(1, 2), ChangeLog.events.map { it.batch })
    }

    // Un nœud est affiché #id(étiquette) s'il est en base, ~id s'il n'est qu'un identifiant temporaire.
    @Test
    fun nodeRefDistinguishesAliasAndId() {
        assertEquals("#17(EAttribute)", NodeRef(17, "EAttribute").toString())
        assertEquals("~3", NodeRef(3, persisted = false).toString())
    }

    // Une ligne ne contient que les champs renseignés (pas de other=, ref=, props= vides).
    @Test
    fun formatOmitsAbsentFields() {
        ChangeLog.log(ChangeType.REMOVE, Target.NODE, node = NodeRef(9, "B"))

        val line = logFile.readLines().single()
        assertTrue(line.endsWith("| REMOVE | NODE | node=#9(B)"), line)
        assertFalse(line.contains("other="), line)
        assertFalse(line.contains("ref="), line)
        assertFalse(line.contains("props="), line)
    }

    // Un événement sans aucun champ (ex. COMMIT) ne laisse pas d'espace en fin de ligne.
    @Test
    fun lineWithoutFieldsHasNoTrailingSpace() {
        ChangeLog.log(ChangeType.COMMIT, Target.BATCH)

        val line = logFile.readLines().single()
        assertFalse(line.endsWith(" "), "[$line]")
    }

    // Le fichier contient exactement une ligne par événement, dans l'ordre d'enregistrement.
    @Test
    fun fileHasOneLinePerEventInOrder() {
        ChangeLog.log(ChangeType.CREATE, Target.NODE, node = NodeRef(1, "A", persisted = false))
        ChangeLog.log(ChangeType.REMOVE, Target.NODE, node = NodeRef(9, "B"))

        val lines = logFile.readLines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("| CREATE | NODE | node=~1(A)"), lines[0])
        assertTrue(lines[1].contains("| REMOVE | NODE | node=#9(B)"), lines[1])
    }

    // Un événement de relation affiche le nœud d'origine, le nœud d'arrivée et le type de relation.
    @Test
    fun relationEventShowsBothEndsAndRefType() {
        ChangeLog.log(
            ChangeType.CREATE, Target.CONTAINMENT,
            node = NodeRef(17), other = NodeRef(4, "Node4", persisted = false), refType = "ref"
        )

        val line = logFile.readLines().single()
        assertTrue(line.endsWith("node=#17 other=~4(Node4) ref=ref"), line)
    }

    // Les propriétés sont copiées à l'écriture : si le framework vide sa carte ensuite, le journal garde les valeurs.
    @Test
    fun propsAreCopiedAtLogTime() {
        val props = hashMapOf<String, Any?>("name" to "title")
        ChangeLog.log(ChangeType.UPDATE, Target.NODE, node = NodeRef(17, "EAttribute"), props = props)

        props.clear() // the framework clears its map after a commit: the log must keep its own copy

        assertEquals(mapOf<String, Any?>("name" to "title"), ChangeLog.events.single().props)
    }

    // Les anciennes valeurs s'écrivent après les nouvelles : props={...} old={...}.
    @Test
    fun oldPropsAreFormattedAfterProps() {
        ChangeLog.log(
            ChangeType.UPDATE, Target.NODE, node = NodeRef(17, "EAttribute"),
            props = mapOf("eType" to "EInt"), oldProps = mapOf("eType" to "EString")
        )

        val line = logFile.readLines().single()
        assertTrue(line.endsWith("props={eType=EInt} old={eType=EString}"), line)
    }

    // Les anciennes valeurs (oldProps) sont aussi copiées à l'écriture, comme les nouvelles.
    @Test
    fun oldPropsAreCopiedAtLogTime() {
        val old = hashMapOf<String, Any?>("name" to "Person")
        ChangeLog.log(ChangeType.REMOVE, Target.NODE, node = NodeRef(5, "EClass"), oldProps = old)

        old.clear()

        assertEquals(mapOf<String, Any?>("name" to "Person"), ChangeLog.events.single().oldProps)
    }

    // Journal désactivé : aucun événement en mémoire et aucune ligne dans le fichier.
    @Test
    fun disabledLogWritesNothing() {
        ChangeLog.enabled = false
        ChangeLog.log(ChangeType.CREATE, Target.NODE)

        assertTrue(ChangeLog.events.isEmpty())
        assertEquals("", logFile.readText())
    }

    // reset() remet à zéro les événements, le fichier et les compteurs (seq = 1, lot = 1 au prochain événement).
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

    // Un événement complet (nœud, propriétés, anciennes valeurs) s'écrit sur une ligne que l'on peut redécouper avec une expression régulière.
    @Test
    fun fullEventLineCanBeSplitBackIntoItsFields() {
        ChangeLog.log(
            ChangeType.UPDATE, Target.NODE, node = NodeRef(17, "EAttribute"),
            props = mapOf("eType" to "EInt"), oldProps = mapOf("eType" to "EString")
        )

        val line = logFile.readLines().single()
        val match = Regex("""^(\d+) \| b(\d+) \| (\S+) \| (\w+) \| (\w+) \| (.*)$""").matchEntire(line)
            ?: throw AssertionError("Ligne illisible : [$line]")
        assertEquals("1", match.groupValues[1])
        assertEquals("1", match.groupValues[2])
        assertEquals("UPDATE", match.groupValues[4])
        assertEquals("NODE", match.groupValues[5])
        assertEquals("node=#17(EAttribute) props={eType=EInt} old={eType=EString}", match.groupValues[6])
    }

    // Quand le journal est coupé puis réactivé, les numéros seq ne sautent pas : seuls les événements réellement écrits comptent.
    @Test
    fun seqDoesNotAdvanceWhileDisabled() {
        ChangeLog.log(ChangeType.CREATE, Target.NODE)
        ChangeLog.enabled = false
        ChangeLog.log(ChangeType.CREATE, Target.NODE)
        ChangeLog.enabled = true
        ChangeLog.log(ChangeType.CREATE, Target.NODE)

        assertEquals(listOf(1, 2), ChangeLog.events.map { it.seq })
    }

    // Les événements sont conservés exactement dans l'ordre où ils ont été enregistrés (dans la mémoire comme dans le fichier).
    @Test
    fun eventsKeepInsertionOrderInMemoryAndInFile() {
        ChangeLog.log(ChangeType.REMOVE, Target.NODE, node = NodeRef(1, "A"))
        ChangeLog.log(ChangeType.UPDATE, Target.NODE, node = NodeRef(2, "B"))
        ChangeLog.log(ChangeType.CREATE, Target.NODE, node = NodeRef(3, "C"))

        assertEquals(listOf(ChangeType.REMOVE, ChangeType.UPDATE, ChangeType.CREATE), ChangeLog.events.map { it.type })
        val lines = logFile.readLines()
        assertTrue(lines[0].contains("| REMOVE |") && lines[1].contains("| UPDATE |") && lines[2].contains("| CREATE |"))
    }

    // Un nœud supprimé en cascade affiche sa racine à la fin de la ligne : old={...} cascade=#id(Label).
    @Test
    fun cascadeRootIsWrittenAfterOldProps() {
        ChangeLog.log(
            ChangeType.REMOVE, Target.NODE, node = NodeRef(115, "EAttribute"),
            oldProps = mapOf("name" to "total"), cascade = NodeRef(114, "EClass")
        )

        val line = logFile.readLines().single()
        assertTrue(line.endsWith("node=#115(EAttribute) old={name=total} cascade=#114(EClass)"), line)
        assertEquals(NodeRef(114, "EClass"), ChangeLog.events.single().cascade)
    }

    // Sans cascade (suppression d'une racine, ou autre type d'événement), le champ cascade= n'apparaît pas.
    @Test
    fun cascadeFieldIsOmittedWhenAbsent() {
        ChangeLog.log(ChangeType.REMOVE, Target.NODE, node = NodeRef(114, "EClass"), oldProps = mapOf("name" to "Order"))

        val line = logFile.readLines().single()
        assertFalse(line.contains("cascade="), line)
    }
}