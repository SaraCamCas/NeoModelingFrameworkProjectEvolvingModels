package geodes.sms.nmf.evolution.dsl

data class RenameAttribute(
    override val cls: String,
    val from: String,
    val to: String,
) : AttributeChange()