package geodes.sms.nmf.neo4j.io

enum class ChangeType { CREATE, UPDATE, REMOVE }

internal fun logChange(type: ChangeType, label: String) {
    println("[CHANGE] $type -> $label")
}