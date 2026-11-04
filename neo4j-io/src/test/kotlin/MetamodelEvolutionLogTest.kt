import geodes.sms.neo4j.io.controllers.INodeController
import geodes.sms.neo4j.io.type.AsString
import geodes.sms.neo4j.io.type.ChangeLog
import geodes.sms.neo4j.io.type.ChangeType
import geodes.sms.neo4j.io.type.Target
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Valide le journal sur un petit métamodèle "bibliothèque" stocké comme l'EcoreLoader le fait :
 *
 *   EPackage library
 *     ├─ eClassifiers ─> EClass Item
 *     ├─ eClassifiers ─> EClass Book  ── eSuperTypes ─> Item
 *     │     ├─ eAttributes ─> EAttribute title  (eType = EString)
 *     │     ├─ eAttributes ─> EAttribute year   (eType = EString)
 *     │     └─ eReferences ─> EReference authors (lowerBound 0, upperBound -1) ── eReferenceType ─> Author
 *     └─ eClassifiers ─> EClass Author
 *
 * Chaque test construit ce métamodèle (lot 1) puis le modifie (lot 2 et suivants) et compare le journal à ce qu'il doit contenir.
 * Nécessite Neo4j + APOC
 */
internal class MetamodelEvolutionLogTest : Neo4jLogTestBase() {

    private class Library(
        val pkg: INodeController, val item: INodeController, val book: INodeController, val author: INodeController,
        val title: INodeController, val year: INodeController, val authors: INodeController
    )

    /** Construit et enregistre le métamodèle de départ : c'est le lot 1 de chaque test. */
    private fun buildLibrary(): Library {
        val pkg = root("EPackage", "pkg")
        pkg.putProperty("name", "library")

        val item = alias("item", pkg.createChild("eClassifiers", "EClass"))
        item.putProperty("name", "Item")
        val book = alias("book", pkg.createChild("eClassifiers", "EClass"))
        book.putProperty("name", "Book")
        val author = alias("author", pkg.createChild("eClassifiers", "EClass"))
        author.putProperty("name", "Author")

        val title = alias("title", book.createChild("eAttributes", "EAttribute"))
        title.putProperty("name", "title")
        title.putProperty("eType", "EString")
        val year = alias("year", book.createChild("eAttributes", "EAttribute"))
        year.putProperty("name", "year")
        year.putProperty("eType", "EString")

        val authors = alias("authors", book.createChild("eReferences", "EReference"))
        authors.putProperty("name", "authors")
        authors.putProperty("lowerBound", 0)
        authors.putProperty("upperBound", -1)
        authors.putProperty("containment", false)

        book.createOutRef("eSuperTypes", item)
        authors.createOutRef("eReferenceType", author)

        graphManager.saveChanges() // lot 1
        return Library(pkg, item, book, author, title, year, authors)
    }

    private val libraryCreation = arrayOf(
        "CREATE NODE EPackage @pkg {name=library}",
        "CREATE NODE EClass @item {name=Item}",
        "CREATE NODE EClass @book {name=Book}",
        "CREATE NODE EClass @author {name=Author}",
        "CREATE NODE EAttribute @title {eType=EString, name=title}",
        "CREATE NODE EAttribute @year {eType=EString, name=year}",
        "CREATE NODE EReference @authors {containment=false, lowerBound=0, name=authors, upperBound=-1}",
        "CREATE CONTAINMENT @pkg -eClassifiers-> @item",
        "CREATE CONTAINMENT @pkg -eClassifiers-> @book",
        "CREATE CONTAINMENT @pkg -eClassifiers-> @author",
        "CREATE CONTAINMENT @book -eAttributes-> @title",
        "CREATE CONTAINMENT @book -eAttributes-> @year",
        "CREATE CONTAINMENT @book -eReferences-> @authors",
        "CREATE REFERENCE @book -eSuperTypes-> @item",
        "CREATE REFERENCE @authors -eReferenceType-> @author",
        "COMMIT BATCH"
    )

    // ======================================================================= CRÉATION

    // Création du métamodèle complet : 7 nœuds (avec leurs propriétés), 6 contenances, 2 références, puis la fin de lot.
    @Test
    fun libraryCreationIsFullyLogged() {
        buildLibrary()
        assertBatch(1, *libraryCreation)
    }

    // Un enregistrement sans aucune modification n'écrit que le marqueur de fin de lot.
    @Test
    fun emptySaveWritesOnlyTheCommitMarker() {
        graphManager.saveChanges()
        assertBatch(1, "COMMIT BATCH")
    }

    // Beaucoup d'éléments dans un seul lot (300 attributs) : rien n'est perdu ni dupliqué, et seq reste continu.
    @Test
    fun largeBatchIsFullyLogged() {
        val lib = buildLibrary()
        repeat(300) { i ->
            val attribute = lib.book.createChild("eAttributes", "EAttribute")
            attribute.putProperty("name", "attr$i")
            attribute.putProperty("eType", "EString")
        }
        graphManager.saveChanges() // lot 2

        val events = batch(2)
        assertEquals(300, events.of(ChangeType.CREATE, Target.NODE).size)
        assertEquals(300, events.of(ChangeType.CREATE, Target.CONTAINMENT).size)
        assertEquals(601, events.size) // 300 nœuds + 300 contenances + COMMIT
        assertEquals((1..ChangeLog.events.size).toList(), ChangeLog.events.map { it.seq })

        val attributesInDb = readFromDb { gm ->
            gm.loadNode(lib.book._id, "EClass").loadOutConnectedNodes("eAttributes", "EAttribute", 1000).size
        }
        assertEquals(302, attributesInDb) // title + year + les 300 nouveaux
    }

    // ======================================================================= MISES À JOUR D'ATTRIBUTS

    // Renommer une classe : un seul UPDATE avec l'ancien nom (indispensable pour retrouver les instances) et le nouveau.
    @Test
    fun renamingAClassLogsOldAndNewName() {
        val lib = buildLibrary()
        lib.book.putProperty("name", "Publication")
        graphManager.saveChanges() // lot 2

        assertBatch(2, "UPDATE NODE EClass @book {name: Book -> Publication}", "COMMIT BATCH")
    }

    // Cas central du projet : le type d'un attribut passe de String à int. Le journal doit garder EString -> EInt.
    @Test
    fun attributeTypeChangeStringToIntLogsBothTypes() {
        val lib = buildLibrary()
        lib.year.putProperty("eType", "EInt")
        graphManager.saveChanges() // lot 2

        assertBatch(2, "UPDATE NODE EAttribute @year {eType: EString -> EInt}", "COMMIT BATCH")
    }

    // Plusieurs propriétés du même nœud modifiées dans un lot (bornes d'une référence) : un seul événement, toutes les paires ancien -> nouveau.
    @Test
    fun changingSeveralBoundsOfAReferenceGivesOneEvent() {
        val lib = buildLibrary()
        lib.authors.putProperty("lowerBound", 1)
        lib.authors.putProperty("upperBound", 1)
        graphManager.saveChanges() // lot 2

        assertBatch(2, "UPDATE NODE EReference @authors {lowerBound: 0 -> 1, upperBound: -1 -> 1}", "COMMIT BATCH")
    }

    // Supprimer une propriété (removeProperty) est journalisé comme un passage de l'ancienne valeur à null.
    @Test
    fun removingAPropertyIsLoggedAsNull() {
        val lib = buildLibrary()
        lib.authors.removeProperty("lowerBound")
        graphManager.saveChanges() // lot 2

        assertBatch(2, "UPDATE NODE EReference @authors {lowerBound: 0 -> null}", "COMMIT BATCH")
    }

    // Renommer puis revenir au nom d'origine dans le même lot : aucun changement réel, donc aucun UPDATE.
    @Test
    fun renameAndRevertInTheSameBatchIsNotLogged() {
        val lib = buildLibrary()
        lib.book.putProperty("name", "Temporary")
        lib.book.putProperty("name", "Book")
        graphManager.saveChanges() // lot 2

        assertBatch(2, "COMMIT BATCH")
    }

    // ======================================================================= RELATIONS

    // Changer la cible d'une référence : suppression de l'ancienne relation, création de la classe cible et de la nouvelle relation.
    @Test
    fun retypingAReferenceLogsRemoveThenCreate() {
        val lib = buildLibrary()
        val editor = alias("editor", lib.pkg.createChild("eClassifiers", "EClass"))
        editor.putProperty("name", "Editor")
        lib.authors.removeOutRef("eReferenceType", lib.author)
        lib.authors.createOutRef("eReferenceType", editor)
        graphManager.saveChanges() // lot 2

        assertBatch(
            2,
            "REMOVE REFERENCE @authors -eReferenceType-> @author",
            "CREATE NODE EClass @editor {name=Editor}",
            "CREATE CONTAINMENT @pkg -eClassifiers-> @editor",
            "CREATE REFERENCE @authors -eReferenceType-> @editor",
            "COMMIT BATCH"
        )
        // La base doit refléter le changement : la référence ne pointe plus que vers Editor.
        val targets = readFromDb { gm ->
            gm.loadNode(lib.authors._id, "EReference").loadOutConnectedNodes("eReferenceType", "EClass", 10)
                .map { it.getProperty("name", AsString) }
        }
        assertEquals(listOf("Editor"), targets)
    }

    // Changer le supertype d'une classe : un REMOVE REFERENCE et un CREATE REFERENCE sur eSuperTypes.
    @Test
    fun changingTheSuperTypeLogsRemoveAndCreateOfTheReference() {
        val lib = buildLibrary()
        lib.book.removeOutRef("eSuperTypes", lib.item)
        lib.book.createOutRef("eSuperTypes", lib.author)
        graphManager.saveChanges() // lot 2

        assertBatch(
            2,
            "REMOVE REFERENCE @book -eSuperTypes-> @item",
            "CREATE REFERENCE @book -eSuperTypes-> @author",
            "COMMIT BATCH"
        )
    }

    // ======================================================================= SUPPRESSIONS

    // Supprimer une classe depuis son parent : l'arête coupée, puis TOUT le sous-arbre (classe, attributs, référence) avec leurs propriétés.
    @Test
    fun removingAClassByParentLogsContainmentThenWholeSubtree() {
        val lib = buildLibrary()
        lib.pkg.removeChild("eClassifiers", lib.book)
        graphManager.saveChanges() // lot 2

        assertBatch(
            2,
            "REMOVE CONTAINMENT @pkg -eClassifiers-> @book",
            "REMOVE NODE EClass @book {name=Book}",
            "REMOVE NODE EAttribute @title {eType=EString, name=title}",
            "REMOVE NODE EAttribute @year {eType=EString, name=year}",
            "REMOVE NODE EReference @authors {containment=false, lowerBound=0, name=authors, upperBound=-1}",
            "COMMIT BATCH"
        )
    }

    // Supprimer la même classe par son identifiant (node.remove()) : même sous-arbre, mais aucun événement d'arête (le parent est inconnu).
    @Test
    fun removingAClassByIdLogsSubtreeWithoutContainmentEvent() {
        val lib = buildLibrary()
        lib.book.remove()
        graphManager.saveChanges() // lot 2

        assertBatch(
            2,
            "REMOVE NODE EClass @book {name=Book}",
            "REMOVE NODE EAttribute @title {eType=EString, name=title}",
            "REMOVE NODE EAttribute @year {eType=EString, name=year}",
            "REMOVE NODE EReference @authors {containment=false, lowerBound=0, name=authors, upperBound=-1}",
            "COMMIT BATCH"
        )
    }

    // LIMITE CONNUE : supprimer une classe supprime aussi, sans le dire, les relations qui y pointent (ici Book -eSuperTypes-> Item).
    // Ce test documente le comportement actuel ; s'il échoue un jour parce que ces relations sont journalisées, mettre le test à jour.
    @Test
    fun implicitRelationshipRemovalIsNotLoggedYet() {
        val lib = buildLibrary()
        lib.pkg.removeChild("eClassifiers", lib.item)
        graphManager.saveChanges() // lot 2

        assertBatch(
            2,
            "REMOVE CONTAINMENT @pkg -eClassifiers-> @item",
            "REMOVE NODE EClass @item {name=Item}",
            "COMMIT BATCH"
        )
        assertTrue(batch(2).of(ChangeType.REMOVE, Target.REFERENCE).isEmpty())
    }

    // Modifier un attribut puis le supprimer dans le même lot : le journal garde les valeurs de la BASE (title), pas la valeur jamais enregistrée (tmp), et aucun UPDATE.
    @Test
    fun updatedThenRemovedAttributeLogsDatabaseValuesOnly() {
        val lib = buildLibrary()
        lib.title.putProperty("name", "tmp")
        lib.book.removeChild("eAttributes", lib.title)
        graphManager.saveChanges() // lot 2

        assertBatch(
            2,
            "REMOVE CONTAINMENT @book -eAttributes-> @title",
            "REMOVE NODE EAttribute @title {eType=EString, name=title}",
            "COMMIT BATCH"
        )
    }

    // "Déplacer" un attribut de Book vers Author = suppression + recréation (l'API n'a pas de déplacement). Le journal doit contenir les deux moitiés.
    @Test
    fun movingAnAttributeIsRemovePlusCreate() {
        val lib = buildLibrary()
        lib.book.removeChild("eAttributes", lib.title)
        val moved = alias("movedTitle", lib.author.createChild("eAttributes", "EAttribute"))
        moved.putProperty("name", "title")
        moved.putProperty("eType", "EString")
        graphManager.saveChanges() // lot 2

        assertBatch(
            2,
            "REMOVE CONTAINMENT @book -eAttributes-> @title",
            "REMOVE NODE EAttribute @title {eType=EString, name=title}",
            "CREATE NODE EAttribute @movedTitle {eType=EString, name=title}",
            "CREATE CONTAINMENT @author -eAttributes-> @movedTitle",
            "COMMIT BATCH"
        )
    }

    // Deux suppressions qui se recouvrent (la classe ET son attribut) : chaque nœud n'est journalisé qu'une seule fois.
    @Test
    fun overlappingRemovalsLogEachNodeOnce() {
        val lib = buildLibrary()
        lib.book.remove()
        lib.title.remove()
        graphManager.saveChanges() // lot 2

        assertBatch(
            2,
            "REMOVE NODE EClass @book {name=Book}",
            "REMOVE NODE EAttribute @title {eType=EString, name=title}",
            "REMOVE NODE EAttribute @year {eType=EString, name=year}",
            "REMOVE NODE EReference @authors {containment=false, lowerBound=0, name=authors, upperBound=-1}",
            "COMMIT BATCH"
        )
    }

    // ======================================================================= CE QUI NE DOIT PAS ÊTRE JOURNALISÉ

    // Un élément créé puis supprimé avant l'enregistrement n'existe jamais en base : aucun événement, et la base est restée intacte.
    @Test
    fun createdThenRemovedInTheSameBatchLeavesNoTrace() {
        val lib = buildLibrary()
        val tmp = lib.pkg.createChild("eClassifiers", "EClass")
        tmp.putProperty("name", "Tmp")
        val tmpAttribute = tmp.createChild("eAttributes", "EAttribute")
        tmpAttribute.putProperty("name", "x")
        lib.pkg.removeChild("eClassifiers", tmp)
        graphManager.saveChanges() // lot 2

        assertBatch(2, "COMMIT BATCH")
        val classes = readFromDb { gm ->
            gm.loadNode(lib.pkg._id, "EPackage").loadOutConnectedNodes("eClassifiers", "EClass", 100)
                .map { it.getProperty("name", AsString) }.toSet()
        }
        assertEquals(setOf("Item", "Book", "Author"), classes)
    }

    // Lire (chargement depuis la base, getProperty) et vider le cache ne modifient pas le métamodèle : aucun événement ne doit apparaître.
    @Test
    fun readingAndClearingCacheLogNothing() {
        val lib = buildLibrary()
        val eventsBefore = ChangeLog.events.size

        readFromDb { gm -> // lecture depuis la base par une autre connexion (n'invalide pas les contrôleurs du test)
            val pkg = gm.loadNode(lib.pkg._id, "EPackage")
            pkg.loadOutConnectedNodes("eClassifiers", "EClass", 100).map { it.getProperty("name", AsString) }
        }
        lib.book.getProperty("name", AsString)
        graphManager.clearCache()

        assertEquals(eventsBefore, ChangeLog.events.size)
    }

    // ======================================================================= FORMAT DU FICHIER

    // Chaque ligne du fichier doit pouvoir être relue par la future DSL : "seq | bN | heure | TYPE | CIBLE | champs".
    @Test
    fun logFileCanBeParsedBackLineByLine() {
        val lib = buildLibrary()
        lib.book.putProperty("name", "Publication")
        graphManager.saveChanges() // lot 2

        val line = Regex("""^(\d+) \| b(\d+) \| (\S+) \| (\w+) \| (\w+) \|( .*)?$""")
        val lines = logFile.readLines()
        assertEquals(ChangeLog.events.size, lines.size)
        for ((index, text) in lines.withIndex()) {
            val match = line.matchEntire(text) ?: throw AssertionError("Ligne illisible : [$text]")
            val event = ChangeLog.events[index]
            assertEquals(event.seq, match.groupValues[1].toInt())
            assertEquals(event.batch, match.groupValues[2].toInt())
            assertEquals(event.type.name, match.groupValues[4])
            assertEquals(event.target.name, match.groupValues[5])
        }
        assertTrue(lines.any { it.contains("props={name=Publication}") && it.contains("old={name=Book}") })
    }

    // ======================================================================= L'EXEMPLE COMPLET (4 lots)

    // Exemple de bout en bout : on construit la bibliothèque (lot 1) puis on la fait évoluer en 3 commits.
    // Le journal obtenu est copié dans build/change-log-demo.txt pour que tu puisses le lire à la main.
    @Test
    fun fullEvolutionExampleProducesTheExpectedLog() {
        // ---- lot 1 : création du métamodèle
        val lib = buildLibrary()
        assertBatch(1, *libraryCreation)

        // ---- lot 2 : renommage de classe + changement de type d'attribut + changement de bornes
        lib.book.putProperty("name", "Publication")
        lib.year.putProperty("eType", "EInt")
        lib.authors.putProperty("lowerBound", 1)
        lib.authors.putProperty("upperBound", 1)
        graphManager.saveChanges()
        assertBatch(
            2,
            "UPDATE NODE EClass @book {name: Book -> Publication}",
            "UPDATE NODE EAttribute @year {eType: EString -> EInt}",
            "UPDATE NODE EReference @authors {lowerBound: 0 -> 1, upperBound: -1 -> 1}",
            "COMMIT BATCH"
        )

        // ---- lot 3 : nouvelle classe Editor, nouvel attribut isbn, et la référence "authors" est retypée vers Editor
        val editor = alias("editor", lib.pkg.createChild("eClassifiers", "EClass"))
        editor.putProperty("name", "Editor")
        val isbn = alias("isbn", lib.book.createChild("eAttributes", "EAttribute"))
        isbn.putProperty("name", "isbn")
        isbn.putProperty("eType", "EString")
        lib.authors.removeOutRef("eReferenceType", lib.author)
        lib.authors.createOutRef("eReferenceType", editor)
        graphManager.saveChanges()
        assertBatch(
            3,
            "REMOVE REFERENCE @authors -eReferenceType-> @author",
            "CREATE NODE EClass @editor {name=Editor}",
            "CREATE NODE EAttribute @isbn {eType=EString, name=isbn}",
            "CREATE CONTAINMENT @pkg -eClassifiers-> @editor",
            "CREATE CONTAINMENT @book -eAttributes-> @isbn",
            "CREATE REFERENCE @authors -eReferenceType-> @editor",
            "COMMIT BATCH"
        )

        // ---- lot 4 : suppression de la classe Author (devenue inutile) et de Publication avec tout son contenu
        lib.pkg.removeChild("eClassifiers", lib.author)
        lib.pkg.removeChild("eClassifiers", lib.book)
        graphManager.saveChanges()
        assertBatch(
            4,
            "REMOVE CONTAINMENT @pkg -eClassifiers-> @author",
            "REMOVE CONTAINMENT @pkg -eClassifiers-> @book",
            "REMOVE NODE EClass @author {name=Author}",
            "REMOVE NODE EClass @book {name=Publication}", // le nom d'AVANT suppression, tel qu'il était en base
            "REMOVE NODE EAttribute @title {eType=EString, name=title}",
            "REMOVE NODE EAttribute @year {eType=EInt, name=year}", // type modifié au lot 2
            "REMOVE NODE EAttribute @isbn {eType=EString, name=isbn}",
            "REMOVE NODE EReference @authors {containment=false, lowerBound=1, name=authors, upperBound=1}",
            "COMMIT BATCH"
        )

        // ---- invariants globaux sur tout le journal
        assertEquals((1..ChangeLog.events.size).toList(), ChangeLog.events.map { it.seq }, "seq doit être continu")
        assertEquals(listOf(1, 2, 3, 4), ChangeLog.events.map { it.batch }.distinct(), "un lot par saveChanges")

        // ---- état final de la base : il ne reste que Item et Editor dans le paquetage
        val classesLeft = readFromDb { gm ->
            gm.loadNode(lib.pkg._id, "EPackage").loadOutConnectedNodes("eClassifiers", "EClass", 100)
                .map { it.getProperty("name", AsString) }.toSet()
        }
        assertEquals(setOf("Item", "Editor"), classesLeft)

        // ---- on garde une copie lisible du journal
        val demo = File("build/change-log-demo.txt")
        demo.parentFile?.mkdirs()
        logFile.copyTo(demo, overwrite = true)
        println("Journal de l'exemple complet copié dans: ${demo.absolutePath}")
        println(logFile.readText())
    }
}
