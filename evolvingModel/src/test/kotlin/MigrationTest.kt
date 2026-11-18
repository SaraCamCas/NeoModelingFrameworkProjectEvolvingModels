import geodes.sms.neo4j.io.controllers.IGraphManager
import geodes.sms.neo4j.io.type.AsString
import geodes.sms.nmf.evolution.dsl.AddAttribute
import geodes.sms.nmf.evolution.dsl.RenameClass
import geodes.sms.nmf.evolution.migration.migrate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class MigrationTest {

    @Test
    fun addAttributeThenRenameClass() {
        val db = IGraphManager.getDefaultManager(DBCredentials.dbUri, DBCredentials.username, DBCredentials.password)
        try {
            // setup: three instances of DemoBook
            for (title in listOf("AllsWell", "Emma", "Twilight")) db.createNode("DemoBook").putProperty("title", title)
            db.saveChanges()

            // the class got an attribute "isbn"
            migrate(AddAttribute("DemoBook", "isbn", "EString"), db, defaultValue = "unknown")
            val isbns = db.loadNodes("DemoBook", Int.MAX_VALUE) { it.getProperty("isbn", AsString) }
            assertEquals(listOf("unknown", "unknown", "unknown"), isbns)

            // the class is renamed from DemoBook to DemoBooks
            migrate(RenameClass("DemoBook", "DemoBooks"), db)
            assertEquals(3, db.loadNodes("DemoBooks", Int.MAX_VALUE) { it }.size)
            assertEquals(0, db.loadNodes("DemoBook", Int.MAX_VALUE) { it }.size)
        } finally {
            // cleanup: runs even if an assertion above failed
            db.loadNodes("DemoBook", Int.MAX_VALUE) { it }.forEach { it.remove() }
            db.loadNodes("DemoBooks", Int.MAX_VALUE) { it }.forEach { it.remove() }
            db.saveChanges()
            db.close()
        }
    }
}