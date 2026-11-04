package geodes.sms.neo4j.io.type

import java.io.File
import java.time.LocalTime

enum class ChangeType { CREATE, UPDATE, REMOVE, CANCEL, COMMIT } // Cancel = non persisted element
enum class Target { NODE, CONTAINMENT, REFERENCE, BATCH }

// Identity of a node (id, label, persisted or not -> already in Neo4j ?)
data class NodeRef(val id: Long, val label: String? = null, val persisted: Boolean = true) {
    override fun toString() = (if (persisted) "#" else "~") + id + (label?.let { "($it)" } ?: "")
}

// log with all possible informations, optional when = null or emptyMap()
data class ChangeEvent(
    val seq: Int, // numéro d'ordre de l'événément dans le log
    val batch: Int, // numéro du commit qui produit l'événement
    val time: String,
    val type: ChangeType,
    val target: Target,
    val node: NodeRef? = null, // noeud concerné ou origine de la relation
    val other: NodeRef? = null, // second noeud pour une relation
    val refType: String? = null, // type de la relation, vide si aucune
    val props: Map<String, Any?> = emptyMap(), // propriétés écrites ou modifiées (nom -> valeur), vide si aucune
) {
    fun format(): String {
        val fields = listOfNotNull(
            node?.let { "node=$it" },
            other?.let { "other=$it" },
            refType?.let { "ref=$it" },
            props.takeIf { it.isNotEmpty() }?.let { "props=$it" },
        ).joinToString(" ")
        return "$seq | b$batch | $time | $type | $target | $fields".trimEnd()
    }
}

object ChangeLog {
    var enabled = true
    private var file = File("../log/change-log.txt")
    private var seq = 0
    private var batch = 1
    val events = mutableListOf<ChangeEvent>()

    fun reset(path: String) {
        file = File(path)
        file.parentFile?.mkdirs()
        file.writeText("")
        seq = 0
        batch = 1
        events.clear()
    }

    fun endBatch() { batch++ }

    fun log(
        type: ChangeType,
        target: Target,
        node: NodeRef? = null,
        other: NodeRef? = null,
        refType: String? = null,
        props: Map<String, Any?> = emptyMap(),
    ) {
        if (!enabled) return
        seq++
        val time = LocalTime.now().toString().take(12)
        val event = ChangeEvent(seq, batch, time, type, target, node, other, refType, props.toMap())
        events.add(event)
        file.parentFile?.mkdirs()
        file.appendText(event.format() + "\n")
    }
}