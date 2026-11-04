import geodes.sms.neo4j.io.GraphManager
import geodes.sms.neo4j.io.controllers.INodeController
import geodes.sms.neo4j.io.type.ChangeEvent
import geodes.sms.neo4j.io.type.ChangeLog
import geodes.sms.neo4j.io.type.ChangeType
import geodes.sms.neo4j.io.type.NodeRef
import geodes.sms.neo4j.io.type.Target
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * Socle commun des tests qui ont besoin de Neo4j (avec APOC).
 *
 * Ce que fait cette classe pour chaque test :
 *  - ouvre une connexion (graphManager) et démarre un journal propre dans un dossier temporaire ;
 *  - fournit des alias (@book, @pkg...) pour écrire les événements attendus sans dépendre des identifiants Neo4j ;
 *  - supprime à la fin tous les nœuds racines créés, pour laisser la base comme on l'a trouvée.
 */
internal abstract class Neo4jLogTestBase {

    /** Les tests de non-régression du framework redéfinissent ceci à false (journal coupé = comportement d'origine). */
    protected open val logEnabled = true

    protected lateinit var graphManager: GraphManager
    protected lateinit var logFile: File

    private val roots = mutableListOf<INodeController>()
    private val aliases = linkedMapOf<String, INodeController>()

    protected fun newManager() = GraphManager(DBCredentials.dbUri, DBCredentials.username, DBCredentials.password)

    @BeforeEach
    fun connectAndStartLog(@TempDir dir: Path) {
        graphManager = newManager()
        logFile = dir.resolve("log/change-log.txt").toFile()
        ChangeLog.enabled = true
        ChangeLog.reset(logFile.path) // repart de zéro : seq = 0, lot = 1, fichier vide
        ChangeLog.enabled = logEnabled
    }

    @AfterEach
    fun cleanDatabaseAndStopLog() {
        ChangeLog.enabled = false // le nettoyage ne doit pas polluer le journal
        graphManager.clearCache()
        graphManager.close()
        // On supprime par identifiant avec un gestionnaire neuf : marche même si le test a détaché ou supprimé les contrôleurs.
        val cleaner = newManager()
        for (root in roots) {
            if (root._id < 0) continue // jamais enregistré en base
            try { cleaner.loadNode(root._id, root.label).remove() } catch (e: Exception) { /* déjà supprimé par le test */ }
        }
        cleaner.saveChanges()
        cleaner.close()
    }

    // ------------------------------------------------------------------ création de nœuds de test

    /** Crée un nœud racine (supprimé automatiquement en fin de test). Les enfants partent avec lui (cascade). */
    protected fun root(label: String, alias: String? = null): INodeController =
        graphManager.createNode(label).also {
            roots.add(it)
            if (alias != null) aliases[alias] = it
        }

    /** Donne un nom court à un nœud pour écrire les événements attendus (@nom). Renvoie le nœud pour chaîner. */
    protected fun alias(name: String, node: INodeController): INodeController = node.also { aliases[name] = it }

    // ------------------------------------------------------------------ lecture indépendante de la base

    /** Lit la base avec une connexion NEUVE (sans cache) : ce qu'on y voit est ce qui est vraiment enregistré. */
    protected fun <T> readFromDb(block: (GraphManager) -> T): T {
        val gm = newManager()
        try {
            return block(gm)
        } finally {
            gm.close()
        }
    }

    // ------------------------------------------------------------------ lecture du journal

    protected fun batch(n: Int): List<ChangeEvent> = ChangeLog.events.filter { it.batch == n }

    protected fun List<ChangeEvent>.of(type: ChangeType, target: Target): List<ChangeEvent> =
        filter { it.type == type && it.target == target }

    /**
     * Traduit un événement en une ligne lisible et indépendante des identifiants, par exemple :
     *   CREATE NODE EClass @book {name=Book}
     *   UPDATE NODE EAttribute @year {eType: EString -> EInt}
     *   REMOVE CONTAINMENT @pkg -eClassifiers-> @book
     *   REMOVE NODE EClass @book {name=Book}          (les propriétés sont celles d'AVANT la suppression)
     *   REMOVE NODE EAttribute @title {...} cascade=@book   (supprimé parce qu'il était contenu dans @book)
     */
    protected fun symbolic(e: ChangeEvent): String {
        val names = aliases.entries.associate { it.value._id to it.key }
        fun who(r: NodeRef?): String = if (r == null) "?" else "@" + (names[r.id] ?: "#${r.id}")
        fun fmt(m: Map<String, Any?>) = m.entries.sortedBy { it.key }.joinToString(", ") { "${it.key}=${it.value}" }

        return when {
            e.type == ChangeType.COMMIT -> "COMMIT BATCH"
            e.target == Target.NODE -> {
                val detail = when (e.type) {
                    ChangeType.CREATE -> if (e.props.isEmpty()) "" else " {${fmt(e.props)}}"
                    ChangeType.UPDATE -> " {" + e.props.keys.sorted()
                        .joinToString(", ") { "$it: ${e.oldProps[it]} -> ${e.props[it]}" } + "}"
                    else -> if (e.oldProps.isEmpty()) "" else " {${fmt(e.oldProps)}}"
                }
                val cascade = e.cascade?.let { " cascade=${who(it)}" } ?: "" // supprimé en cascade sous cette racine
                "${e.type} NODE ${e.node?.label} ${who(e.node)}$detail$cascade"
            }
            else -> "${e.type} ${e.target} ${who(e.node)} -${e.refType}-> ${who(e.other)}"
        }
    }

    /** Position d'un événement dans saveChanges : suppressions, mises à jour, créations, puis fin de lot. */
    private fun phase(e: ChangeEvent): Int = when {
        e.type == ChangeType.COMMIT -> 6
        e.type == ChangeType.REMOVE && e.target == Target.CONTAINMENT -> 0
        e.type == ChangeType.REMOVE && e.target == Target.NODE -> 1
        e.type == ChangeType.REMOVE && e.target == Target.REFERENCE -> 2
        e.type == ChangeType.UPDATE -> 3
        e.type == ChangeType.CREATE && e.target == Target.NODE -> 4
        else -> 5 // CREATE CONTAINMENT / CREATE REFERENCE
    }

    /**
     * Vérifie le lot n : (1) les phases sont dans l'ordre de saveChanges, (2) l'ensemble des événements est exactement
     * celui attendu. L'ordre à l'intérieur d'une même phase n'est pas garanti (tables de hachage), on compare donc
     * les listes triées.
     */
    protected fun assertBatch(n: Int, vararg expected: String) {
        val events = batch(n)
        val phases = events.map(::phase)
        assertEquals(
            phases.sorted(), phases,
            "Événements du lot $n hors de l'ordre de saveChanges :\n" + events.joinToString("\n") { symbolic(it) }
        )
        assertEquals(expected.sorted(), events.map(::symbolic).sorted(), "Contenu du lot $n")
    }
}
