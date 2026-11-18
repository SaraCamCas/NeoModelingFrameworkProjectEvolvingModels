package geodes.sms.nmf.evolution.dsl

data class RenameReference(
    override val cls: String,
    val from: String,
    val to: String
):  ReferenceChange()