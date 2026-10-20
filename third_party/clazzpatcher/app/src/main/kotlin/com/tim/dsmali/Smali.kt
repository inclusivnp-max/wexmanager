package com.tim.dsmali

import com.tim.dex.model.DexFile
import com.tim.dex.parser.DexReader
import com.tim.dex.writer.DexWriter
import com.tim.dsmali.asm.TreeToDex
import com.tim.dsmali.disasm.DexToTree
import com.tim.dsmali.io.SmaliFiles
import com.tim.dsmali.model.SmaliTree
import com.tim.dsmali.patch.SmaliPatch
import java.io.File

object Smali {
    fun disassemble(dex: File): SmaliTree =
    disassemble(DexReader().read(dex))

    fun disassemble(dex: DexFile): SmaliTree =
    DexToTree().build(dex)

    fun assemble(tree: SmaliTree): DexFile =
    TreeToDex().build(tree)

    fun assemble(tree: SmaliTree, out: File) {
        DexWriter(assemble(tree)).write(out)
    }

    fun save(tree: SmaliTree, outputDir: File) =
    SmaliFiles.write(tree, outputDir)

    fun load(inputDir: File): SmaliTree =
    SmaliFiles.read(inputDir)

    fun patch(tree: SmaliTree, block: SmaliPatch.() -> Unit): SmaliTree {
        SmaliPatch(tree).block()
        return tree
    }

    fun patch(tree: SmaliTree, smali: String): SmaliTree =
    patch(tree) { applyText(smali) }
}
