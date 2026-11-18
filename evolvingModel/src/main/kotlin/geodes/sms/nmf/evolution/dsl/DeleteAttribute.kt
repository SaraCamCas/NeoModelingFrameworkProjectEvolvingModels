package geodes.sms.nmf.evolution.dsl

data class DeleteAttribute(
    override val cls: String,
    val name: String
) : AttributeChange()