package geodes.sms.nmf.evolution.migration

import geodes.sms.nmf.evolution.dsl.AddAttribute
import geodes.sms.nmf.evolution.dsl.MetamodelChange
import geodes.sms.neo4j.io.controllers.IGraphManager
import geodes.sms.nmf.evolution.dsl.RenameAttribute
import geodes.sms.neo4j.io.type.AsObject
import java.math.BigDecimal
import java.math.BigInteger
import java.time.ZonedDateTime

fun migrate(
    change: MetamodelChange,
    db: IGraphManager,
    defaultValue: String = "",
    subclasses: List<String> = emptyList()
) {
    when (change) {
        is AddAttribute -> addAttribute(change, db, defaultValue, subclasses)
        is RenameAttribute -> renameAttribute(change, db, subclasses)
        else -> throw IllegalArgumentException("Pas de migration pour ${change::class.simpleName}")
    }
}

/** Ajoute la propriété [AddAttribute.name] à toutes les instances de [AddAttribute.cls] et de ses sous-classes. */
private fun addAttribute(change: AddAttribute, db: IGraphManager, defaultValue: String, subclasses: List<String>) {
    for (label in listOf(change.cls) + subclasses) {
        val instances = db.loadNodes(label, Int.MAX_VALUE) { it }
        for (node in instances) {
            node.putProperty(change.name, defaultValue)
        }
    }
    db.saveChanges()
}

/** Renomme la propriété [RenameAttribute.from] en [RenameAttribute.to] pour toutes les instances de [RenameAttribute.cls] et de ses sous-classes. */
private fun renameAttribute(change: RenameAttribute, db: IGraphManager, subclasses: List<String>) {
    for (label in listOf(change.cls) + subclasses) {
        val instances = db.loadNodes(label, Int.MAX_VALUE) { it }
        for (node in instances) {
            val value = node.getProperty(change.from, AsObject) ?: continue
            when (value) {
                is String -> node.putProperty(change.to, value)
                is Int -> node.putProperty(change.to, value)
                is Long -> node.putProperty(change.to, value)
                is Short -> node.putProperty(change.to, value)
                is Boolean -> node.putProperty(change.to, value)
                is Byte -> node.putProperty(change.to, value)
                is ByteArray -> node.putProperty(change.to, value)
                is Char -> node.putProperty(change.to, value)
                is Double -> node.putProperty(change.to, value)
                is Float -> node.putProperty(change.to, value)
                is Enum<*> -> node.putProperty(change.to, value.name)
                is ZonedDateTime -> node.putProperty(change.to, value)
                is BigDecimal -> node.putProperty(change.to, value)
                is BigInteger -> node.putProperty(change.to, value)
                is List<*> -> node.putProperty(change.to, value)
                else -> throw IllegalArgumentException(
                    "Type non géré pour ${change.from} : ${value::class.simpleName}"
                )
            }
            node.removeProperty(change.from)
        }
    }
    db.saveChanges()
}