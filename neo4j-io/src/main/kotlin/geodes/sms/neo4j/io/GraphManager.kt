package geodes.sms.neo4j.io

import geodes.sms.neo4j.io.controllers.IGraphManager
import geodes.sms.neo4j.io.controllers.INodeController
import geodes.sms.neo4j.io.entity.INodeEntity
import org.neo4j.driver.*

/** Graph proxy */
internal class GraphManager(dbUri: String, username: String, password: String) : IGraphManager {
    private val driver = GraphDatabase.driver(dbUri, AuthTokens.basic(username, password))

    private val creator = BufferedCreator()
    private val updater = BufferedUpdater()
    private val remover = BufferedRemover()
    private val reader = DBReader(driver)
    private val mapper = Mapper(creator, updater, remover, reader)

    override fun saveChanges() {
        val session = driver.session()
        mapper.saveChanges(session)
        session.close()
    }

    //fun clearChanges() {}

    override fun clearCache() = mapper.clearCache()

    override fun clearDB() {
        val session = driver.session()
        remover.removeAll(session)
        session.close()
    }

    override fun createNode(label: String): INodeController {
        return mapper.createNode(label)
    }

    override fun loadNode(id: Long, label: String): INodeController {
        return mapper.loadNode(id, label)
    }

    @Suppress("OVERRIDE_BY_INLINE")
    override inline fun <R> loadNodes(label: String, limit: Int, crossinline mapFunction: (INodeController) -> R): List<R> {
        return mapper.loadNodesByLabel(label, limit, mapFunction)
    }

    @Suppress("OVERRIDE_BY_INLINE")
    override inline fun <R> loadNodes(limit: Int, crossinline mapFunction: (INodeController) -> R): List<R> {
        return mapper.loadNodes(limit, mapFunction)
    }

    //also unloads all out/in refs
    override fun unload(node: INodeEntity) {
        if (node is INodeController) node.unload()
        else throw Exception("object $node must be instance of INodeController")
    }

    override fun remove(node: INodeEntity) {
        if (node is INodeController) {
            println("REMOVE queued: ${node.label}")
            node.remove()
        } else throw Exception("object $node must be instance of INodeController")
    }

    override fun close() {
        mapper.clearCache()
        driver.close()
    }
}