import geodes.sms.neo4j.io.controllers.INodeController
import geodes.sms.neo4j.io.type.AsString
import geodes.sms.neo4j.io.type.ChangeLog
import geodes.sms.neo4j.io.type.ChangeType
import geodes.sms.neo4j.io.type.Target
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Exemple du rapport : le métamodèle Library / Bookshelf / LibraryContent / Author (Book et Magazine en sous-classes),
 * stocké comme le fait l'EcoreLoader (EPackage, EClass, EAttribute, EReference + relations eClassifiers, eAttributes,
 * eReferences, eReferenceType, eOpposite, eSuperTypes).
 *
 *   lot 1 : création du métamodèle
 *   lot 2 : petite modification -> l'attribut LibraryContent.year passe de String à int
 *
 * Le journal est copié dans build/library-example/change-log.txt (à citer dans le rapport).
 * Nécessite Neo4j + APOC.
 */
internal class LibraryExampleTest : Neo4jLogTestBase() {

    // Crée le métamodèle du diagramme, fait la modification, et vérifie ce qui est écrit dans le journal.
    @Test
    fun bookshelfMetamodelWithOneModification() {
        val pkg = root("EPackage", "pkg")
        pkg.putProperty("name", "library")

        fun eClass(key: String, name: String): INodeController =
            alias(key, pkg.createChild("eClassifiers", "EClass")).also { it.putProperty("name", name) }

        fun attribute(owner: INodeController, key: String, name: String): INodeController =
            alias(key, owner.createChild("eAttributes", "EAttribute")).also {
                it.putProperty("name", name)
                it.putProperty("eType", "EString")
            }

        fun reference(
            owner: INodeController, key: String, name: String,
            lower: Int, upper: Int, containment: Boolean, target: INodeController
        ): INodeController =
            alias(key, owner.createChild("eReferences", "EReference")).also {
                it.putProperty("name", name)
                it.putProperty("lowerBound", lower)
                it.putProperty("upperBound", upper)
                it.putProperty("containment", containment)
                it.createOutRef("eReferenceType", target)
            }

        // ---- les six classes du diagramme
        val library = eClass("library", "Library")
        val bookshelf = eClass("bookshelf", "Bookshelf")
        val content = eClass("content", "LibraryContent")
        val book = eClass("book", "Book")
        val magazine = eClass("magazine", "Magazine")
        val author = eClass("author", "Author")
        book.createOutRef("eSuperTypes", content)
        magazine.createOutRef("eSuperTypes", content)

        // ---- les attributs
        attribute(library, "libraryName", "name")
        attribute(library, "address", "address")
        attribute(bookshelf, "category", "category")
        attribute(content, "title", "title")
        val year = attribute(content, "year", "year") // à l'origine un texte : c'est lui qu'on va corriger
        attribute(author, "authorName", "name")

        // ---- les relations du diagramme
        reference(library, "shelves", "shelves", 0, -1, false, bookshelf) // agrégation Library -> Bookshelf
        reference(bookshelf, "contents", "contents", 0, -1, true, content) // composition Bookshelf -> LibraryContent
        val authors = reference(content, "authors", "authors", 1, -1, false, author)
        val works = reference(author, "works", "works", 0, -1, false, content)
        authors.createOutRef("eOpposite", works) // association bidirectionnelle LibraryContent <-> Author
        works.createOutRef("eOpposite", authors)

        graphManager.saveChanges() // ===== lot 1 : création

        val creation = batch(1)
        assertEquals(17, creation.of(ChangeType.CREATE, Target.NODE).size) // 1 paquetage + 6 classes + 6 attributs + 4 références
        assertEquals(16, creation.of(ChangeType.CREATE, Target.CONTAINMENT).size) // chaque élément est contenu par son parent
        assertEquals(8, creation.of(ChangeType.CREATE, Target.REFERENCE).size) // 2 supertypes + 4 eReferenceType + 2 eOpposite
        assertEquals(42, creation.size) // + le marqueur de fin de lot

        // ===== lot 2 : LibraryContent.year passe de String à int
        year.putProperty("eType", "EInt")
        graphManager.saveChanges()

        assertBatch(
            2,
            "UPDATE NODE EAttribute @year {eType: EString -> EInt}",
            "COMMIT BATCH"
        )

        val typeInDb = readFromDb { gm -> gm.loadNode(year._id, "EAttribute").getProperty("eType", AsString) }
        assertEquals("EInt", typeInDb)

        val out = File("build/library-example/change-log.txt")
        out.parentFile?.mkdirs()
        logFile.copyTo(out, overwrite = true)
        println("Journal de l'exemple copié dans: ${out.absolutePath}")
        println("ID de l'attribut year: ${year._id}")
        println(logFile.readText())
        check(ChangeLog.events.isNotEmpty())
    }
}
