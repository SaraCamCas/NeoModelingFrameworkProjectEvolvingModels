package geodes.sms.nmf.evolution.dsl

data class ChangeAttributeType(
    override val cls: String,
    val from: String,
    val to: String,
) : AttributeChange()