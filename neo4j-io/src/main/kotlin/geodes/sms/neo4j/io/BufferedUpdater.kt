package geodes.sms.neo4j.io

import org.neo4j.driver.Query
import org.neo4j.driver.Session
import org.neo4j.driver.Value
import org.neo4j.driver.internal.value.IntegerValue
import org.neo4j.driver.internal.value.ListValue
import org.neo4j.driver.internal.value.MapValue
import geodes.sms.neo4j.io.type.ChangeLog
import geodes.sms.neo4j.io.type.ChangeType
import geodes.sms.neo4j.io.type.NodeRef
import geodes.sms.neo4j.io.type.Target

/** Update properties of db entities (nodes and refs) */
class BufferedUpdater(val updateBatchSize: Int = ManagerBatchSizes.updaterNodesBatchSize) {
    private val nodesToUpdate = hashMapOf<Long, Map<String, Value>>()
    private val refsToUpdate = hashMapOf<Long, Map<String, Value>>()

    fun updateNode(id: Long, props: Map<String, Value>) {
        nodesToUpdate[id] = props
    }

    fun updateRelationship(id: Long, props: Map<String, Value>) {
        refsToUpdate[id] = props
    }

    fun popNodeUpdate(id: Long) {
        nodesToUpdate.remove(id)
    }

    fun popRelationshipUpdate(id: Long) {
        refsToUpdate.remove(id)
    }

    fun commitNodesUpdate(session: Session) {
        val logProps = ChangeLog.enabled // reading the previous properties is only worth it when somebody logs them
        val paramsIterator = nodesToUpdate.asSequence().map { (id, props) ->
            MapValue(mapOf("id" to IntegerValue(id), "props" to MapValue(props)))
        }.iterator()

        fun commit(batchSize: Int) {
            val updated = session.writeTransaction { tx ->
                val res = tx.run(Query("UNWIND \$batch AS row" +
                        " MATCH (node) WHERE ID(node)=row.id" +
                        " WITH node, row" + (if (logProps) ", properties(node) AS before" else "") + // snapshot BEFORE the SET
                        " SET node += row.props" +
                        " RETURN ID(node) AS id, labels(node)[0] AS label" +
                        (if (logProps) ", before" else ""),
                     MapValue(mapOf("batch" to ListValue(*Array(batchSize) { paramsIterator.next() })))
                ))
                res.list { Triple(
                    it["id"].asLong(),
                    it["label"].asString(),
                    if (logProps) it["before"].asMap() else emptyMap<String, Any?>()
                ) }
            }
            // only nodes really matched in the DB are logged; nodesToUpdate is still intact (cleared at the end)
            if (ChangeLog.enabled) {
                for ((id, label, before) in updated) {
                    val written = nodesToUpdate[id]?.mapValues { it.value.asObject() } ?: emptyMap()
                    // keep only real changes: a property rewritten with its current value is not a metamodel change
                    val changed = written.filter { (name, value) -> before[name] != value }
                    if (changed.isEmpty()) continue
                    ChangeLog.log(ChangeType.UPDATE, Target.NODE, NodeRef(id, label),
                        props = changed, oldProps = changed.keys.associateWith { before[it] })
                }
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