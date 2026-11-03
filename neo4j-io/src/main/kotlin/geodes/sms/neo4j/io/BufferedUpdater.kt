package geodes.sms.neo4j.io

import org.neo4j.driver.Query
import org.neo4j.driver.Session
import org.neo4j.driver.Value
import org.neo4j.driver.internal.value.IntegerValue
import org.neo4j.driver.internal.value.ListValue
import org.neo4j.driver.internal.value.MapValue
import geodes.sms.neo4j.io.type.ChangeType
import geodes.sms.neo4j.io.type.logChange

/** Update properties of db entities (nodes and refs) */
class BufferedUpdater(val updateBatchSize: Int = ManagerBatchSizes.updaterNodesBatchSize) {
    private val nodesToUpdate = hashMapOf<Long, Map<String, Value>>()
    private val refsToUpdate = hashMapOf<Long, Map<String, Value>>()

    fun updateNode(id: Long, props: Map<String, Value>) {
        logChange(ChangeType.UPDATE, "node id=$id props=${props.keys}")
        nodesToUpdate[id] = props
    }

    fun updateRelationship(id: Long, props: Map<String, Value>) {
        logChange(ChangeType.UPDATE, "relationship id=$id props=${props.keys}")
        refsToUpdate[id] = props
    }

    fun popNodeUpdate(id: Long) {
        nodesToUpdate.remove(id)
    }

    fun popRelationshipUpdate(id: Long) {
        refsToUpdate.remove(id)
    }

    fun commitNodesUpdate(session: Session) {
        val paramsIterator = nodesToUpdate.asSequence().map { (id, props) ->
            MapValue(mapOf("id" to IntegerValue(id), "props" to MapValue(props)))
        }.iterator()

        fun commit(batchSize: Int) {
            session.writeTransaction { tx ->
                tx.run(Query("UNWIND \$batch AS row" +
                        " MATCH (node) WHERE ID(node)=row.id" +
                        " SET node += row.props",
                     MapValue(mapOf("batch" to ListValue(*Array(batchSize) { paramsIterator.next() })))
                ))
            }
        }

        for (i in 1..(nodesToUpdate.size / updateBatchSize)) {
            commit(updateBatchSize)
        }
        val rem = nodesToUpdate.size % updateBatchSize
        if (rem > 0)
            commit(rem)
        nodesToUpdate.clear()
    }

    fun commitRelationshipsUpdates(session: Session) {
        val paramsIterator = refsToUpdate.asSequence().map { (id, props) ->
            MapValue(mapOf("id" to IntegerValue(id), "props" to MapValue(props)))
        }.iterator()

        fun commit(batchSize: Int) {
            session.writeTransaction { tx ->
                tx.run(Query("UNWIND \$batch AS row" +
                        " MATCH ()-[r]-() WHERE ID(r)=row.id" +
                        " SET r += row.props",
                    MapValue(mapOf("batch" to ListValue(*Array(batchSize) { paramsIterator.next() })))
                ))
            }
        }

        for (i in 1..(refsToUpdate.size / updateBatchSize)) {
            commit(updateBatchSize)
        }
        val rem = refsToUpdate.size % updateBatchSize
        if (rem > 0) commit(rem)
        refsToUpdate.clear()
    }
}