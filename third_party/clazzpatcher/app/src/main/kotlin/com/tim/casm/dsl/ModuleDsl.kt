package com.tim.casm.dsl

import com.tim.casm.model.*

class ModuleDsl(private val name: String) {
    var version: String? = null
    var flags: Int = 0
    private val requires = ArrayList<RequiresSpec>()
    private val exports = ArrayList<ExportsSpec>()
    private val opens = ArrayList<OpensSpec>()
    private val uses = ArrayList<String>()
    private val provides = ArrayList<ProvidesSpec>()

    fun requires(module: String, flags: Int = 0, version: String? = null) {
        requires.add(RequiresSpec(module, flags, version))
    }

    fun exports(pkg: String, flags: Int = 0, to: List<String> = emptyList()) {
        exports.add(ExportsSpec(pkg, flags, to))
    }

    fun opens(pkg: String, flags: Int = 0, to: List<String> = emptyList()) {
        opens.add(OpensSpec(pkg, flags, to))
    }

    fun uses(service: String) { uses.add(service) }

    fun provides(service: String, with: List<String>) {
        provides.add(ProvidesSpec(service, with))
    }

    internal fun build(): ModuleSpec = ModuleSpec(name, version, flags, requires, exports, opens, uses, provides)
}
