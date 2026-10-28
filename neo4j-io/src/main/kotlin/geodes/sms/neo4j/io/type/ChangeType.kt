package geodes.sms.neo4j.io.type

class ChangeType {
    enum class ChangeType { CREATE, UPDATE, REMOVE }

    internal fun logChange(type: ChangeType, label: String) {
        println("[CHANGE] $type -> $label")
    }
}