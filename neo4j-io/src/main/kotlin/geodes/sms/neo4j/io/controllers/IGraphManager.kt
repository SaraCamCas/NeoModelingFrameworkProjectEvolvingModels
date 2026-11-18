package geodes.sms.neo4j.io.controllers

import geodes.sms.neo4j.io.GraphManager
import geodes.sms.neo4j.io.entity.INodeEntity

interface IGraphManager : AutoCloseable {
    companion object {
        fun getDefaultManager(dbUri: String, username: String, password: String): IGraphManager =
            GraphManager(dbUri, username, password)
    }
    fun saveChanges()
    fun clearCache()
    fun clearDB()
    fun createNode(label: String): INodeController
    fun renameLabel(from: String, to: String)
    fun loadNode(id: Long, label: String): INodeController
    fun <R> loadNodes(label: String, limit: Int, mapFunction: (INodeController) -> R): List<R>
    fun <R> loadNodes(limit: Int, mapFunction: (INodeController) -> R): List<R>
    fun unload(node: INodeEntity)
    fun remove(node: INodeEntity)
}