package geodes.sms.nmf.evolution.dsl

data class RenameClass(
    val from: String,
    val to: String
) : ClassChange()