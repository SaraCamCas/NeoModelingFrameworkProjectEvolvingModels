package geodes.sms.nmf.evolution.dsl

abstract class ReferenceChange(): MetamodelChange() {
    abstract val cls: String
}