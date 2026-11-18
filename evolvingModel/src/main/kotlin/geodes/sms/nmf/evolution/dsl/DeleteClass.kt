package geodes.sms.nmf.evolution.dsl

data class DeleteClass(
    val name: String,
    val removedWith: List<String> = emptyList()
) : ClassChange()