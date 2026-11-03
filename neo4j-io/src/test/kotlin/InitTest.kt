import geodes.sms.neo4j.io.GraphManager
import org.junit.jupiter.api.Test

class InitTest {

 @Test
 fun initTest() {
        val graphManager = GraphManager(DBCredentials.dbUri, DBCredentials.username, DBCredentials.password)  // init a connection with the database

        val n1 = graphManager.createNode("Node1") // n1 is a node controller
        val n2 = graphManager.createNode("Node1")
        val n3 = graphManager.createNode("Node2")

        val n4 = n1.createChild("ref", "Node4")
        val n5 = n4.createChild("ref", "Node5")

        val n6 = n5.createChild("ref", "Node6")
        graphManager.saveChanges()  // commit updates to the storage

        n5.createOutRef("ref2", n1) // controllers remain interactable after the commit
        n1.putProperty("property", "Test property")
        graphManager.saveChanges()  // commit new changes

<<<<<<< Updated upstream
        graphManager.remove(n4)   // goes through GraphManager, so the print fires

=======
        graphManager.remove(n4) // remove the node 'n4' within its children (cascade delete)
>>>>>>> Stashed changes
        graphManager.saveChanges()
        graphManager.close()
        graphManager.clearCache()
    }

}


