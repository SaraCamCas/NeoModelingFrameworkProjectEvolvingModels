package geodes.sms.nmf.evolution.dsl

data class AddReference(
    override val cls: String,
    val name: String,
    val target: String,
    val lower: Int,
    val upper: Int,
    val containment: Boolean
) : ReferenceChange()