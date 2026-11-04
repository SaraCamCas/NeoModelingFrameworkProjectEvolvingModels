import geodes.sms.neo4j.io.type.AsString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.neo4j.driver.AuthTokens
import org.neo4j.driver.GraphDatabase
import java.io.File

/**
 * Exemple du rapport : suppression en cascade dans un mini-métamodèle.
 *
 *   EPackage shop-cascade
 *     ├─ eClassifiers ─> EClass Customer
 *     └─ eClassifiers ─> EClass Order                  <- on supprime cette classe
 *           ├─ eAttributes ─> EAttribute total          <- supprimé en cascade
 *           └─ eReferences ─> EReference customer       <- supprimé en cascade
 *                 └─ eReferenceType ─> EClass Customer  (référence, pas contenance : Customer reste)
 *
 * Lot 1 : construction. Lot 2 : suppression de Order depuis le paquetage.
 * Fichiers écrits dans build/cascade-example/ : graph-before.txt, graph-after.txt, change-log.txt.
 * Nécessite Neo4j + APOC.
 */
internal class CascadeDeleteExampleTest : Neo4jLogTestBase() {

    @Test
    fun cascadeDeleteOfOrderRemovesItsContentAndKeepsCustomer() {
        val pkg = root("EPackage", "pkg")
        pkg.putProperty("name", "shop-cascade")

        val customer = alias("customer", pkg.createChild("eClassifiers", "EClass"))
        customer.putProperty("name", "Customer")
        val order = alias("order", pkg.createChild("eClassifiers", "EClass"))
        order.putProperty("name", "Order")

        val total = alias("total", order.createChild("eAttributes", "EAttribute"))
        total.putProperty("name", "total")
        total.putProperty("eType", "EDouble")

        val customerRef = alias("customerRef", order.createChild("eReferences", "EReference"))
        customerRef.putProperty("name", "customer")
        customerRef.putProperty("lowerBound", 1)
        customerRef.putProperty("upperBound", 1)
        customerRef.putProperty("containment", false)
        customerRef.createOutRef("eReferenceType", customer)

        graphManager.saveChanges() // lot 1 : construction du métamodèle
        dumpGraph("graph-before.txt", pkg._id)

        pkg.removeChild("eClassifiers", order) // suppression depuis le paquetage : cascade sur tout le contenu de Order
        graphManager.saveChanges() // lot 2
        dumpGraph("graph-after.txt", pkg._id)

        // Journal du lot 2 : l'arête coupée, puis les trois nœuds supprimés (Order, total, customer), puis la fin de lot.
        assertBatch(
            2,
            "REMOVE CONTAINMENT @pkg -eClassifiers-> @order",
            "REMOVE NODE EClass @order {name=Order}",
            "REMOVE NODE EAttribute @total {eType=EDouble, name=total} cascade=@order",
            "REMOVE NODE EReference @customerRef {containment=false, lowerBound=1, name=customer, upperBound=1} cascade=@order",
            "COMMIT BATCH"
        )

        // État de la base : Order et son contenu ont disparu, Customer est intact.
        readFromDb { gm ->
            assertThrows(Exception::class.java) { gm.loadNode(order._id, "EClass") }
            assertThrows(Exception::class.java) { gm.loadNode(total._id, "EAttribute") }
            assertThrows(Exception::class.java) { gm.loadNode(customerRef._id, "EReference") }
            val classes = gm.loadNode(pkg._id, "EPackage")
                .loadOutConnectedNodes("eClassifiers", "EClass", 100)
                .map { it.getProperty("name", AsString) }
            assertEquals(listOf("Customer"), classes)
            assertEquals("Customer", gm.loadNode(customer._id, "EClass").getProperty("name", AsString))
        }

        val out = File("build/cascade-example/change-log.txt")
        out.parentFile.mkdirs()
        logFile.copyTo(out, overwrite = true)
        assertTrue(out.exists())
        println(logFile.readText())
    }

    /**
     * Écrit l'état du sous-arbre du paquetage : les nœuds, puis les relations entre eux.
     * Lit la base avec un pilote direct, indépendant du cache du framework.
     */
    private fun dumpGraph(fileName: String, pkgId: Long) {
        val driver = GraphDatabase.driver(DBCredentials.dbUri, AuthTokens.basic(DBCredentials.username, DBCredentials.password))
        val lines = mutableListOf<String>()
        try {
            val session = driver.session()
            try {
                val nodes = session.run(
                    "MATCH (p) WHERE ID(p) = \$pkg " +
                        "MATCH (p)-[*0..3]->(n) " +
                        "RETURN DISTINCT ID(n) AS id, labels(n)[0] AS label, n.name AS name, n.eType AS eType " +
                        "ORDER BY label, name",
                    mapOf("pkg" to pkgId)
                ).list()

                val describe = HashMap<Long, String>()
                lines.add("Noeuds :")
                for (r in nodes) {
                    val id = r["id"].asLong()
                    val label = r["label"].asString()
                    val name = r["name"].takeIf { !it.isNull }?.asString() ?: "-"
                    val eType = r["eType"].takeIf { !it.isNull }?.asString()
                    describe[id] = "$label $name"
                    lines.add("  #$id  $label  name=$name" + (eType?.let { "  eType=$it" } ?: ""))
                }

                val ids = nodes.map { it["id"].asLong() }
                val rels = session.run(
                    "MATCH (a)-[r]->(b) WHERE ID(a) IN \$ids AND ID(b) IN \$ids " +
                        "RETURN ID(a) AS a, type(r) AS t, ID(b) AS b ORDER BY a, t, b",
                    mapOf("ids" to ids)
                ).list()

                lines.add("")
                lines.add("Relations :")
                for (r in rels) {
                    val a = r["a"].asLong()
                    val b = r["b"].asLong()
                    lines.add("  ${describe[a]}  -${r["t"].asString()}->  ${describe[b]}")
                }
            } finally {
                session.close()
            }
        } finally {
            driver.close()
        }

        val out = File("build/cascade-example/$fileName")
        out.parentFile.mkdirs()
        out.writeText(lines.joinToString("\n") + "\n")
        println("--- $fileName ---")
        println(out.readText())
    }
}
