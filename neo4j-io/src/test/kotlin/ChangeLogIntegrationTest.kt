import geodes.sms.neo4j.io.GraphManager
import geodes.sms.neo4j.io.controllers.INodeController
import geodes.sms.neo4j.io.type.ChangeEvent
import geodes.sms.neo4j.io.type.ChangeLog
import geodes.sms.neo4j.io.type.ChangeType
import geodes.sms.neo4j.io.type.Target
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * Integration tests: ChangeLog + GraphManager. Needs a running Neo4j with APOC.
 * Expected log content is described per batch (one batch = one saveChanges call).
 */
class ChangeLogIntegrationTest {

    private lateinit var graphManager: GraphManager
    private lateinit var logFile: File
    private val roots = mutableListOf<INodeController>() // removed in tearDown, so the DB is left as found

    // The InitTest scenario: n1 -ref-> n4 -ref-> n5, plus two independent roots
    private class Graph(
        val n1: INodeController, val n2: INodeController, val n3: INodeController,
        val n4: INodeController, val n5: INodeController
    )

    @BeforeEach
    fun setUp(@TempDir dir: Path) {
        graphManager = GraphManager(DBCredentials.dbUri, DBCredentials.username, DBCredentials.password)
        logFile = dir.resolve("log/change-log.txt").toFile()
        ChangeLog.enabled = true
        ChangeLog.reset(logFile.path)
    }

    @AfterEach
    fun tearDown() {
        ChangeLog.enabled = false // cleanup must not pollute the log
        for (root in roots) {
            try { root.remove() } catch (e: Exception) { /* already removed by the test */ }
        }
        graphManager.saveChanges()
        graphManager.clearCache()
        graphManager.close()
        ChangeLog.enabled = false // back to the default
    }

    // ---------- helpers ----------
    private fun root(label: String): INodeController = graphManager.createNode(label).also { roots.add(it) }

    private fun saveInitialGraph(): Graph {
        val n1 = root("Node1")
        val n2 = root("Node1")
        val n3 = root("Node2")
        val n4 = n1.createChild("ref", "Node4")
        val n5 = n4.createChild("ref", "Node5")
        graphManager.saveChanges() // batch 1; from now on the controllers hold their real DB ids
        return Graph(n1, n2, n3, n4, n5)
    }

    private fun batch(n: Int): List<ChangeEvent> = ChangeLog.events.filter { it.batch == n }

    private fun List<ChangeEvent>.of(type: ChangeType, target: Target): List<ChangeEvent> =
        filter { it.type == type && it.target == target }

    // ---------- batch 1: creation ----------
    // Lot 1 (InitTest) : 5 CREATE NODE avec étiquettes, puis 2 CREATE CONTAINMENT, puis COMMIT ; les nœuds passent avant les relations.
    @Test
    fun creationLogsEveryNodeThenEveryContainment() {
        val g = saveInitialGraph()
        val events = batch(1)

        val nodes = events.of(ChangeType.CREATE, Target.NODE)
        assertEquals(
            mapOf(g.n1._id to "Node1", g.n2._id to "Node1", g.n3._id to "Node2", g.n4._id to "Node4", g.n5._id to "Node5"),
            nodes.associate { it.node!!.id to it.node!!.label }
        )

        val containments = events.of(ChangeType.CREATE, Target.CONTAINMENT)
        assertEquals(
            setOf(Triple(g.n1._id, g.n4._id, "ref"), Triple(g.n4._id, g.n5._id, "ref")),
            containments.map { Triple(it.node!!.id, it.other!!.id, it.refType) }.toSet()
        )

        // relations need real ids, so every node is logged before every relation
        assertTrue(nodes.maxOf { it.seq } < containments.minOf { it.seq })
        assertEquals(ChangeType.COMMIT, events.last().type)
    }

    // ---------- batch 2: update + reference ----------
    // Lot 2 : un UPDATE (propriété jamais définie avant -> ancienne valeur null) puis un CREATE REFERENCE, dans l'ordre de saveChanges.
    @Test
    fun updateIsLoggedBeforeNewReference() {
        val g = saveInitialGraph()
        g.n5.createOutRef("ref2", g.n1)
        g.n1.putProperty("property", "Test property")
        graphManager.saveChanges()
        val events = batch(2)

        val update = events.of(ChangeType.UPDATE, Target.NODE).single()
        assertEquals(g.n1._id, update.node!!.id)
        assertEquals(mapOf<String, Any?>("property" to "Test property"), update.props)
        assertEquals(mapOf<String, Any?>("property" to null), update.oldProps) // the property did not exist before

        val reference = events.of(ChangeType.CREATE, Target.REFERENCE).single()
        assertEquals(Triple(g.n5._id, g.n1._id, "ref2"), Triple(reference.node!!.id, reference.other!!.id, reference.refType))

        assertTrue(update.seq < reference.seq) // saveChanges order: removes, updates, creates
        assertTrue(events.of(ChangeType.CREATE, Target.NODE).isEmpty())
    }

    // Deux écritures de la même propriété avant le commit : un seul UPDATE avec la valeur finale.
    @Test
    fun twoUpdatesOfTheSamePropertyGiveOneEventWithFinalValue() {
        val g = saveInitialGraph()
        g.n1.putProperty("property", "first")
        g.n1.putProperty("property", "second")
        graphManager.saveChanges()

        val update = batch(2).of(ChangeType.UPDATE, Target.NODE).single()
        assertEquals(mapOf<String, Any?>("property" to "second"), update.props)
        assertEquals(mapOf<String, Any?>("property" to null), update.oldProps) // "first" never reached the DB
    }

    // ---------- old values: what the instance models need to be migrated ----------
    // Cas EString -> EInt : l'UPDATE garde l'ancienne valeur enregistrée au commit précédent.
    @Test
    fun updateOfAnExistingPropertyKeepsTheOldValue() {
        val g = saveInitialGraph()
        g.n1.putProperty("eType", "EString")
        graphManager.saveChanges() // batch 2
        g.n1.putProperty("eType", "EInt") // e.g. an attribute changing from String to int
        graphManager.saveChanges() // batch 3

        val update = batch(3).of(ChangeType.UPDATE, Target.NODE).single()
        assertEquals(mapOf<String, Any?>("eType" to "EInt"), update.props)
        assertEquals(mapOf<String, Any?>("eType" to "EString"), update.oldProps)
    }

    // Réécrire la valeur déjà présente n'est pas un changement : aucun UPDATE.
    @Test
    fun rewritingTheCurrentValueIsNotLogged() {
        val g = saveInitialGraph()
        g.n1.putProperty("property", "same")
        graphManager.saveChanges() // batch 2
        g.n1.putProperty("property", "same")
        graphManager.saveChanges() // batch 3

        assertTrue(batch(3).of(ChangeType.UPDATE, Target.NODE).isEmpty())
    }

    // Un nœud supprimé garde ses propriétés dans le journal (son nom n'existe plus en base).
    @Test
    fun removedNodeKeepsItsPropertiesInTheLog() {
        val parent = root("Container")
        val child = parent.createChild("ref", "Named")
        child.putProperty("name", "Doomed")
        graphManager.saveChanges() // batch 1
        child.remove()
        graphManager.saveChanges() // batch 2

        val removed = batch(2).of(ChangeType.REMOVE, Target.NODE).single()
        assertEquals(child._id, removed.node!!.id)
        assertEquals(mapOf<String, Any?>("name" to "Doomed"), removed.oldProps) // the name is gone from the DB, not from the log
    }

    // ---------- batch 2: cascade removal ----------
    // Supprimer un conteneur journalise tout le sous-arbre en cascade, avec les étiquettes ; le parent reste.
    @Test
    fun removingAContainerLogsTheWholeSubtree() {
        val g = saveInitialGraph()
        g.n4.remove()
        graphManager.saveChanges()

        val removed = batch(2).of(ChangeType.REMOVE, Target.NODE)
        assertEquals(
            mapOf(g.n4._id to "Node4", g.n5._id to "Node5"), // cascade: n4 and its child n5, labels included
            removed.associate { it.node!!.id to it.node!!.label }
        )
        assertTrue(removed.none { it.node!!.id == g.n1._id }) // the parent stays
    }

    // ---------- cache-only operations must leave no trace ----------
    // Créé puis supprimé avant le commit : jamais en base, donc aucun événement (sauf COMMIT).
    @Test
    fun createdThenRemovedBeforeCommitLeavesNoEvent() {
        val tmp = graphManager.createNode("Tmp")
        tmp.remove()
        graphManager.saveChanges()

        assertTrue(batch(1).none { it.type != ChangeType.COMMIT })
    }

    // Modifié puis supprimé dans le même lot : pas d'UPDATE sur le nœud supprimé.
    @Test
    fun updatedThenRemovedInTheSameBatchIsNotLoggedAsUpdate() {
        val g = saveInitialGraph()
        g.n4.putProperty("property", "doomed")
        g.n4.remove()
        graphManager.saveChanges()

        assertTrue(batch(2).of(ChangeType.UPDATE, Target.NODE).none { it.node!!.id == g.n4._id })
    }

    // ---------- global invariants ----------
    // Sur trois commits : seq continu (1, 2, 3...) et un numéro de lot par saveChanges, croissant.
    @Test
    fun seqIsContiguousAndBatchesFollowSaveChanges() {
        val g = saveInitialGraph()
        g.n1.putProperty("property", "x")
        graphManager.saveChanges()
        g.n4.remove()
        graphManager.saveChanges()

        assertEquals((1..ChangeLog.events.size).toList(), ChangeLog.events.map { it.seq })
        assertEquals(listOf(1, 2, 3), ChangeLog.events.map { it.batch }.distinct())
        assertEquals(ChangeLog.events.map { it.batch }.sorted(), ChangeLog.events.map { it.batch }) // never goes back
    }

    // Le fichier écrit pendant un vrai commit a autant de lignes que d'événements.
    @Test
    fun fileHasOneLinePerEvent() {
        saveInitialGraph()
        assertEquals(ChangeLog.events.size, logFile.readLines().size)
    }
}