package geodes.sms.neo4j.io

import org.neo4j.driver.Query
import org.neo4j.driver.Session
import org.neo4j.driver.internal.value.IntegerValue
import org.neo4j.driver.internal.value.ListValue
import org.neo4j.driver.internal.value.MapValue
import org.neo4j.driver.internal.value.StringValue
import geodes.sms.neo4j.io.type.ChangeLog
import geodes.sms.neo4j.io.type.ChangeType
import geodes.sms.neo4j.io.type.NodeRef
import geodes.sms.neo4j.io.type.Target
import java.util.*

class BufferedRemover(val nodesBatchSize: Int = ManagerBatchSizes.removerNodesBatchSize, val refsBatchSize: Int = 10000) {
    /** endNode ID --> input ref type */
    private val nodesToRemoveByID = hashSetOf<Long>()
    //private val refsToRemoveByID = hashSetOf<Long>()
    private val nodesToRemoveByHost = hashSetOf<PathMatchParameter>()
    private val refsToRemoveByHostNodes = hashSetOf<ReferenceMatchParameter>()

    fun removeNode(id: Long) {
        nodesToRemoveByID.add(id)
    }

    fun removeChild(startID: Long, rType: String, endID: Long) {
        nodesToRemoveByHost.add(PathMatchParameter(startID, rType, endID))
    }

    fun removeRelationship(startID: Long, rType: String, endID: Long) {
        refsToRemoveByHostNodes.add(ReferenceMatchParameter(startID, rType, endID))
    }

//    fun removeRelationship(id: Long) {
//        refsToRemoveByID.add(id)
//    }
//
//    fun popNodeRemove(id: Long) {
//        nodesToRemove.remove(id)
//    }
//
//    fun popRelationshipRemove(id: Long) {
//        refsToRemoveByID.remove(id)
//    }

    /** One row of the containment-removal query: a deleted node + the edge that was cut to reach it. */
    private class ContainmentRemoval(val node: RemovedNode, val startID: Long, val startLabel: String)

    /**
     * A node can come back several times when removal requests overlap (e.g. a class AND one of its attributes).
     * Keep one entry per node, preferring "deleted in cascade below X" over "deleted explicitly", so the result is deterministic.
     */
    private fun distinctNodes(rows: List<RemovedNode>): List<RemovedNode> =
        rows.groupBy { it.id }.values.map { same ->
            same.filter { it.rootId != it.id }.minByOrNull { it.rootId } ?: same.first()
        }

    /** mapFunction receives every node actually deleted: its label, the properties it had just before deletion, and the root of its cascade. */
    fun commitContainmentsRemove(session: Session, mapFunction: (List<RemovedNode>) -> Unit) {
        val logProps = ChangeLog.enabled // reading all properties is only worth it when somebody logs them
        val pending = nodesToRemoveByHost.toList()
        val paramsIterator = pending.asSequence().map { MapValue(mapOf(
            "startID" to IntegerValue(it.startID),
            "endID" to IntegerValue(it.endID),
            "rType" to StringValue(it.rType)))
        }.iterator()
        var done = 0

        fun commit(batchSize: Int) {
            val rows = session.writeTransaction { tx ->
                val res = tx.run(Query("UNWIND \$batch AS row" +
                        " MATCH (start)-[r{containment:true}]->(end)" +
                        " WHERE ID(start)=row.startID AND ID(end)=row.endID AND type(r)=row.rType" +
                        " WITH start, end" +
                        " MATCH (end)-[*0..{containment:true}]->(d)" +
                        // everything below is computed BEFORE the delete: afterwards the nodes can no longer be read
                        " WITH d, ID(d) AS removedIDs, labels(d)[0] AS removedLabels," +
                        " ID(end) AS rootID, labels(end)[0] AS rootLabel," +
                        " ID(start) AS startID, labels(start)[0] AS startLabel" +
                        (if (logProps) ", properties(d) AS removedProps" else "") +
                        " DETACH DELETE d" +
                        " RETURN removedIDs, removedLabels, rootID, rootLabel, startID, startLabel" +
                        (if (logProps) ", removedProps" else ""),
                    MapValue(mapOf("batch" to ListValue(*Array(batchSize) { paramsIterator.next() })))
                ))
                res.list { ContainmentRemoval(
                    RemovedNode(
                        it["removedIDs"].asLong(),
                        it["removedLabels"].asString(),
                        if (logProps) it["removedProps"].asMap() else emptyMap<String, Any?>(),
                        it["rootID"].asLong(),
                        it["rootLabel"].asString()
                    ),
                    it["startID"].asLong(),
                    it["startLabel"].asString()
                ) }
            }
            if (ChangeLog.enabled) {
                // labels of both ends of every edge really cut (a requested edge that did not exist returns no row, hence no event)
                val cut = rows.associate { Pair(it.startID, it.node.rootId) to Pair(it.startLabel, it.node.rootLabel) }
                for (p in pending.subList(done, done + batchSize)) {
                    val labels = cut[Pair(p.startID, p.endID)] ?: continue
                    ChangeLog.log(ChangeType.REMOVE, Target.CONTAINMENT,
                        NodeRef(p.startID, labels.first), NodeRef(p.endID, labels.second), p.rType)
                }
            }
            done += batchSize
            mapFunction(distinctNodes(rows.map { it.node })) // the removed nodes are logged by the Mapper callback
        }

        for (i in 1..(pending.size / nodesBatchSize)) {
            commit(nodesBatchSize)
        }
        val rem = pending.size % nodesBatchSize
        if (rem > 0) commit(rem)
        nodesToRemoveByHost.clear()
    }

    fun commitRelationshipsRemoveByHost(session: Session) {
        val pending = refsToRemoveByHostNodes.toList()
        val paramsIterator = pending.asSequence().map { MapValue(mapOf(
            "startID" to IntegerValue(it.startID),
            "endID" to IntegerValue(it.endID),
            "rType" to StringValue(it.rType),
            "limit" to IntegerValue(it.limit)))
        }.iterator()
        var done = 0

        fun commit(batchSize: Int) {
            session.writeTransaction { tx ->
                tx.run(Query("UNWIND \$batch as row" +
                        " MATCH (start) WHERE ID(start)=row.startID" +
                        " CALL apoc.cypher.doIt('" +
                        "  MATCH (start)-[r]->(end)" +
                        "  WHERE type(r)=rType AND ID(end)=endID" +
                        "  WITH r LIMIT \$l DELETE r'," +
                        " {start:start,rType:row.rType,endID:row.endID,l:row.limit}) YIELD value" +
                        " RETURN value", //return nothing
                    MapValue(mapOf("batch" to ListValue(*Array(batchSize) { paramsIterator.next() })))
                ))
            }
            if (ChangeLog.enabled) {
                for (p in pending.subList(done, done + batchSize))
                    repeat(p.limit.toInt()) { // limit = how many identical relationships were requested
                        ChangeLog.log(ChangeType.REMOVE, Target.REFERENCE, NodeRef(p.startID), NodeRef(p.endID), p.rType)
                    }
            }
            done += batchSize
        }

        for (i in 1..(pending.size / refsBatchSize)) {
            commit(refsBatchSize)
        }
        val rem = pending.size % refsBatchSize
        if (rem > 0) commit(rem)
        refsToRemoveByHostNodes.clear()
    }

    /** mapFunction receives every node actually deleted: its label, the properties it had just before deletion, and the root of its cascade. */
    fun commitNodesRemoveByID(session: Session, mapFunction: (List<RemovedNode>) -> Unit) {
        val logProps = ChangeLog.enabled
        val pending = nodesToRemoveByID.toList()
        val paramsIterator = pending.asSequence().map {
            MapValue(mapOf("id" to IntegerValue(it)))
        }.iterator()

        fun commit(batchSize: Int) {
            val removed = session.writeTransaction { tx ->
                val res = tx.run(Query("UNWIND \$batch AS row" +
                        " MATCH (node) WHERE ID(node)=row.id" +
                        " WITH node" +
                        " MATCH (node)-[*0..{containment:true}]->(d)" +
                        // everything below is computed BEFORE the delete: afterwards the nodes can no longer be read
                        " WITH d, ID(d) AS removedIDs, labels(d)[0] AS removedLabels," +
                        " ID(node) AS rootID, labels(node)[0] AS rootLabel" +
                        (if (logProps) ", properties(d) AS removedProps" else "") +
                        " DETACH DELETE d" +
                        " RETURN removedIDs, removedLabels, rootID, rootLabel" +
                        (if (logProps) ", removedProps" else ""),
                    MapValue(mapOf("batch" to ListValue(*Array(batchSize) { paramsIterator.next() })))
                ))
                res.list { RemovedNode(
                    it["removedIDs"].asLong(),
                    it["removedLabels"].asString(),
                    if (logProps) it["removedProps"].asMap() else emptyMap<String, Any?>(),
                    it["rootID"].asLong(),
                    it["rootLabel"].asString()
                ) }
            }
            mapFunction(distinctNodes(removed))
        }

        for (i in 1..(pending.size / nodesBatchSize)) {
            commit(nodesBatchSize)
        }
        val rem = pending.size % nodesBatchSize
        if (rem > 0) commit(rem)
        nodesToRemoveByID.clear()
    }

//    val res = tx.run(Query("UNWIND \$batch AS id" +
//                    " MATCH ()-[r]->()" +
//                    " WHERE ID(r)=id" +
//                    " WITH ID(r) AS removedIDs" +
//                    " DELETE d" +
//                    " RETURN removedIDs",
//                MapValue(mapOf("batch" to ListValue(*Array(batchData.size) {i -> batchData[i]} )))

    fun removeAll(session: Session) {
        session.writeTransaction { tx ->
            tx.run(Query("call apoc.periodic.iterate(" +
                    "\"MATCH (n) return n\", \"DETACH DELETE n\"," +
                    " {batchSize:10000, parallel:true})" +
                    " YIELD batches, total return batches, total"))
        }
    }
}