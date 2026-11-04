import geodes.sms.neo4j.io.EntityState
import geodes.sms.neo4j.io.controllers.INodeController
import geodes.sms.neo4j.io.exception.LoverBoundExceedException
import geodes.sms.neo4j.io.exception.UpperBoundExceedException
import geodes.sms.neo4j.io.type.AsBoolean
import geodes.sms.neo4j.io.type.AsDouble
import geodes.sms.neo4j.io.type.AsInt
import geodes.sms.neo4j.io.type.AsLong
import geodes.sms.neo4j.io.type.AsString
import geodes.sms.neo4j.io.type.ChangeLog
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Tests de NON-RÉGRESSION du framework NMF d'origine (écriture, lecture, suppression, bornes, états, unicité).
 *
 * Ils tournent avec le journal COUPÉ (logEnabled = false) : ils vérifient donc que le framework se comporte
 * exactement comme avant l'ajout du journal. Chaque vérification de contenu relit la base avec une connexion neuve.
 * Nécessite Neo4j + APOC.
 */
internal class FrameworkRegressionTest : Neo4jLogTestBase() {

    override val logEnabled = false

    // ======================================================================= ÉCRITURE ET LECTURE

    // Les propriétés de plusieurs types (texte, entier, long, booléen, décimal) sont relues à l'identique après un enregistrement.
    @Test
    fun propertiesOfAllTypesSurviveACommit() {
        val node = root("Props")
        node.putProperty("s", "text")
        node.putProperty("i", 42)
        node.putProperty("l", 123456789012L)
        node.putProperty("b", true)
        node.putProperty("d", 3.5)
        graphManager.saveChanges()

        readFromDb { gm ->
            val loaded = gm.loadNode(node._id, "Props")
            assertEquals("text", loaded.getProperty("s", AsString))
            assertEquals(42, loaded.getProperty("i", AsInt))
            assertEquals(123456789012L, loaded.getProperty("l", AsLong))
            assertEquals(true, loaded.getProperty("b", AsBoolean))
            assertEquals(3.5, loaded.getProperty("d", AsDouble))
        }
    }

    // Les enfants créés avec createChild sont bien stockés et retrouvés par la relation de contenance.
    @Test
    fun childrenAreReadBackFromTheDatabase() {
        val parent = root("Parent")
        repeat(3) { i -> parent.createChild("ref", "Child").putProperty("name", "c$i") }
        graphManager.saveChanges()

        val names = readFromDb { gm ->
            gm.loadNode(parent._id, "Parent").loadOutConnectedNodes("ref", "Child", 100)
                .map { it.getProperty("name", AsString) }.toSet()
        }
        assertEquals(setOf("c0", "c1", "c2"), names)
    }

    // Une référence (non contenante) entre deux nœuds déjà enregistrés est stockée et relue.
    @Test
    fun referenceBetweenPersistedNodesIsStored() {
        val a = root("A")
        val b = root("B")
        graphManager.saveChanges()
        a.createOutRef("link", b)
        graphManager.saveChanges()

        val linked = readFromDb { gm ->
            gm.loadNode(a._id, "A").loadOutConnectedNodes("link", "B", 10).map { it._id }
        }
        assertEquals(listOf(b._id), linked)
    }

    // Modifier plusieurs fois une propriété d'un nœud déjà enregistré : la base contient la dernière valeur.
    @Test
    fun updateOfAPersistedNodeIsVisibleAfterReload() {
        val node = root("P")
        node.putProperty("v", 1)
        graphManager.saveChanges()
        node.putProperty("v", 2)
        graphManager.saveChanges()

        assertEquals(2, readFromDb { gm -> gm.loadNode(node._id, "P").getProperty("v", AsInt) })
    }

    // ======================================================================= SUPPRESSIONS

    // node.remove() supprime le nœud ET tous ses descendants par contenance ; le parent reste.
    @Test
    fun removeNodeDeletesItsSubtreeFromTheDatabase() {
        val parent = root("R")
        val child = parent.createChild("ref", "C")
        val grandChild = child.createChild("ref", "C")
        graphManager.saveChanges()
        child.remove()
        graphManager.saveChanges()

        readFromDb { gm ->
            assertThrows(Exception::class.java) { gm.loadNode(child._id, "C") }
            assertThrows(Exception::class.java) { gm.loadNode(grandChild._id, "C") }
            gm.loadNode(parent._id, "R") // le parent existe toujours
        }
    }

    // parent.removeChild(...) supprime l'enfant et son sous-arbre, comme remove().
    @Test
    fun removeChildDeletesTheChildSubtree() {
        val parent = root("R")
        val child = parent.createChild("ref", "C")
        val grandChild = child.createChild("ref", "C")
        graphManager.saveChanges()
        parent.removeChild("ref", child)
        graphManager.saveChanges()

        readFromDb { gm ->
            assertThrows(Exception::class.java) { gm.loadNode(child._id, "C") }
            assertThrows(Exception::class.java) { gm.loadNode(grandChild._id, "C") }
            gm.loadNode(parent._id, "R")
        }
    }

    // removeOutRef supprime seulement la relation : les deux nœuds restent en base.
    @Test
    fun removedReferenceDisappearsButNodesRemain() {
        val a = root("A")
        val b = root("B")
        a.createOutRef("link", b)
        graphManager.saveChanges()
        a.removeOutRef("link", b)
        graphManager.saveChanges()

        readFromDb { gm ->
            assertTrue(gm.loadNode(a._id, "A").loadOutConnectedNodes("link", "B", 10).isEmpty())
            gm.loadNode(b._id, "B")
        }
    }

    // ======================================================================= ÉTATS ET RÈGLES DU FRAMEWORK

    // Cycle de vie d'un nœud : NEW -> PERSISTED -> MODIFIED -> PERSISTED -> REMOVED.
    @Test
    fun entityStatesFollowTheLifecycle() {
        val node = root("S")
        assertEquals(EntityState.NEW, node.getState())
        graphManager.saveChanges()
        assertEquals(EntityState.PERSISTED, node.getState())
        node.putProperty("x", "1")
        assertEquals(EntityState.MODIFIED, node.getState())
        graphManager.saveChanges()
        assertEquals(EntityState.PERSISTED, node.getState())
        node.remove()
        assertEquals(EntityState.REMOVED, node.getState())
        graphManager.saveChanges()
        assertEquals(EntityState.REMOVED, node.getState())
    }

    // La borne supérieure d'une relation est toujours respectée : le 2e enfant d'une relation limitée à 1 est refusé.
    @Test
    fun upperBoundIsStillEnforced() {
        val parent = root("P")
        parent.createChild("ref", "C", 1)
        assertThrows(UpperBoundExceedException::class.java) { parent.createChild("ref", "C", 1) }
    }

    // La borne inférieure est toujours respectée : on ne peut pas retirer le dernier enfant d'une relation qui en exige 1.
    @Test
    fun lowerBoundIsStillEnforced() {
        val parent = root("P")
        val child = parent.createChild("ref", "C")
        graphManager.saveChanges()
        assertThrows(LoverBoundExceedException::class.java) { parent.removeChild("ref", child, 1) }
        assertEquals(EntityState.PERSISTED, child.getState()) // l'enfant n'a pas été touché
    }

    // putUniqueProperty refuse une valeur déjà présente en base pour la même étiquette.
    @Test
    fun uniquePropertyIsRefusedWhenAlreadyInDatabase() {
        val code = "code-" + UUID.randomUUID()
        val first = root("UniqueHolder")
        first.putUniqueProperty("code", code)
        graphManager.saveChanges()

        val second = root("UniqueHolder")
        assertThrows(Exception::class.java) { second.putUniqueProperty("code", code) }
    }

    // putUniqueProperty refuse aussi une valeur déjà utilisée par un autre nœud pas encore enregistré (cache).
    @Test
    fun uniquePropertyIsRefusedAmongNewNodes() {
        val code = "code-" + UUID.randomUUID()
        val first = root("UniqueHolder")
        first.putUniqueProperty("code", code)

        val second = root("UniqueHolder")
        assertThrows(Exception::class.java) { second.putUniqueProperty("code", code) }
    }

    // Un nœud déchargé (unload) est détaché : toute opération sur lui est refusée.
    @Test
    fun unloadedNodeRejectsOperations() {
        val node = root("U")
        graphManager.saveChanges()
        node.unload()

        assertEquals(EntityState.DETACHED, node.getState())
        assertThrows(Exception::class.java) { node.putProperty("x", "y") }
    }

    // ======================================================================= LE JOURNAL N'AFFECTE PAS LE FRAMEWORK

    // Journal coupé : après des créations, mises à jour et suppressions, aucun événement et fichier vide.
    @Test
    fun disabledLogLeavesNoEventAndNoFileContent() {
        val node = root("D")
        node.putProperty("p", "v")
        node.createChild("ref", "C")
        graphManager.saveChanges()
        node.putProperty("p", "w")
        graphManager.saveChanges()
        node.remove()
        graphManager.saveChanges()

        assertTrue(ChangeLog.events.isEmpty())
        assertEquals("", logFile.readText())
    }

    /** Scénario commun : arbre, renommage + ajout, suppression d'une branche. Renvoie l'arbre final relu depuis la base. */
    private fun runScenario(): String {
        val whole = root("Whole")
        whole.putProperty("name", "w")
        val p1 = whole.createChild("child", "Part")
        p1.putProperty("name", "p1")
        val p2 = whole.createChild("child", "Part")
        p2.putProperty("name", "p2")
        val p1a = p1.createChild("child", "Part")
        p1a.putProperty("name", "p1a")
        graphManager.saveChanges()

        p2.putProperty("name", "p2-renamed")
        val p3 = whole.createChild("child", "Part")
        p3.putProperty("name", "p3")
        graphManager.saveChanges()

        p1.remove()
        graphManager.saveChanges()

        return readFromDb { gm -> snapshot(gm.loadNode(whole._id, "Whole")) }
    }

    private fun snapshot(node: INodeController): String {
        val children = node.loadOutConnectedNodes("child", "Part", 100).map { snapshot(it) }.sorted()
        val tail = if (children.isEmpty()) "" else children.joinToString(",", "[", "]")
        return "${node.label}(${node.getProperty("name", AsString)})$tail"
    }

    // Preuve de non-régression : le même scénario, avec le journal coupé puis activé, donne exactement le même graphe en base.
    @Test
    fun loggingDoesNotChangeTheResultingGraph() {
        ChangeLog.enabled = false
        ChangeLog.reset(logFile.path)
        val withoutLog = runScenario()
        assertTrue(ChangeLog.events.isEmpty())

        ChangeLog.enabled = true
        ChangeLog.reset(logFile.path)
        val withLog = runScenario()
        assertFalse(ChangeLog.events.isEmpty())

        assertEquals("Whole(w)[Part(p2-renamed),Part(p3)]", withoutLog)
        assertEquals(withoutLog, withLog)
    }
}
