package geodes.sms.nmf.evolution.dsl

data class DeleteReference(
    override val cls: String,
    val name: String
) : ReferenceChange()