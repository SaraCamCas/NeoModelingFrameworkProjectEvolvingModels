package geodes.sms.nmf.evolution.dsl

data class AddAttribute(
    override val cls: String,
    val name: String,
    val type: String
) : AttributeChange()