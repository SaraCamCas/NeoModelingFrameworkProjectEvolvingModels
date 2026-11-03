package geodes.sms.neo4j.io

import org.neo4j.driver.Query
import org.neo4j.driver.Session
import org.neo4j.driver.internal.value.IntegerValue
import org.neo4j.driver.internal.value.ListValue
import org.neo4j.driver.internal.value.MapValue
import org.neo4j.driver.internal.value.StringValue
import geodes.sms.neo4j.io.type.ChangeType
import geodes.sms.neo4j.io.type.logChange
import java.util.*

class BufferedRemover(val nodesBatchSize: Int = ManagerBatchSizes.removerNodesBatchSize, val refsBatchSize: Int = 10000) {
    /** endNode ID --> input ref type */
    private val nodesToRemoveByID = hashSetOf<Long>()
    //private val refsToRemoveByID = hashSetOf<Long>()
    private val nodesToRemoveByHost = hashSetOf<PathMatchParameter>()
    private val refsToRemoveByHostNodes = hashSetOf<ReferenceMatchParameter>()

    fun removeNode(id: Long) {
        logChange(ChangeType.REMOVE, "node id=$id") // removes one node directly by ID
        nodesToRemoveByID.add(id)
    }

    fun removeChild(startID: Long, rType: String, endID: Long) {
        logChange(ChangeType.REMOVE, "child $endID of $startID via $rType") // removes the child node, found through parent (startID) and the relationship type
        nodesToRemoveByHost.add(PathMatchParameter(startID, rType, endID))
    }

    fun removeRelationship(startID: Long, rType: String, endID: Long) {
        logChange(ChangeType.REMOVE, "relationship $rType from $startID to $endID") // removes only the edge between two nodes
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

    fun commitContainmentsRemove(session: Session, mapFunction: (Sequence<Long>) -> Unit) {
        val paramsIterator = nodesToRemoveByHost.asSequence().map { MapValue(mapOf(
            "startID" to IntegerValue(it.startID),
            "endID" to IntegerValue(it.endID),
            "rType" to StringValue(it.rType)))
        }.iterator()

        fun commit(batchSize: Int) {
            session.writeTransaction { tx ->
                val res = tx.run(Query("UNWIND \$batch AS row" +
                        " MATCH (start)-[r{containment:true}]->(end)" +
                        " WHERE ID(start)=row.startID AND ID(end)=row.endID AND type(r)=row.rType" +
                        " WITH end" +
                        " MATCH (end)-[*0..{containment:true}]->(d)" +
                        " WITH d, ID(d) AS removedIDs" +
                        " DETACH DELETE d" +
                        " RETURN removedIDs",
                    MapValue(mapOf("batch" to ListValue(*Array(batchSize) { paramsIterator.next() })))
                ))
                mapFunction(Sequence { res }.map { it["removedIDs"].asLong() })
            }
        }

        for (i in 1..(nodesToRemoveByHost.size / nodesBatchSize)) {
            commit(nodesBatchSize)
        }
        val rem = nodesToRemoveByHost.size % nodesBatchSize
        if (rem > 0) commit(rem)
        nodesToRemoveByHost.clear()
    }

    fun commitRelationshipsRemoveByHost(session: Session) {
        val paramsIterator = refsToRemoveByHostNodes.asSequence().map { MapValue(mapOf(
            "startID" to IntegerValue(it.startID),
            "endID" to IntegerValue(it.endID),
            "rType" to StringValue(it.rType),
            "limit" to IntegerValue(it.limit)))
        }.iterator()

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
        }

        for (i in 1..(refsToRemoveByHostNodes.size / refsBatchSize)) {
            commit(refsBatchSize)
        }
        val rem = refsToRemoveByHostNodes.size % refsBatchSize
        if (rem > 0) commit(rem)
        refsToRemoveByHostNodes.clear()
    }

    fun commitNodesRemoveByID(session: Session, mapFunction: (Sequence<Long>) -> Unit) {
        val paramsIterator = nodesToRemoveByID.asSequence().map {
            MapValue(mapOf("id" to IntegerValue(it)))
        }.iterator()

        fun commit(batchSize: Int) {
            session.writeTransaction { tx ->
                val res = tx.run(Query("UNWIND \$batch AS row" +
                        " MATCH (node) WHERE ID(node)=row.id" +
                        " WITH node" +
                        " MATCH (node)-[*0..{containment:true}]->(d)" +
                        " WITH d, ID(d) AS removedIDs" +
                        " DETACH DELETE d" +
                        " RETURN removedIDs",
                    MapValue(mapOf("batch" to ListValue(*Array(batchSize) { paramsIterator.next() })))
                ))
                mapFunction(Sequence { res }.map { it["removedIDs"].asLong() })
            }
        }

        for (i in 1..(nodesToRemoveByID.size / nodesBatchSize)) {
            commit(nodesBatchSize)
        }
        val rem = nodesToRemoveByID.size % nodesBatchSize
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