package org.wexclient.app.dex

import org.antlr.runtime.CommonTokenStream
import org.antlr.runtime.TokenSource
import org.antlr.runtime.tree.CommonTree
import org.antlr.runtime.tree.CommonTreeNodeStream
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.dexbacked.DexBackedDexFile
import org.jf.dexlib2.dexbacked.raw.HeaderItem
import org.jf.dexlib2.iface.ClassDef
import org.jf.dexlib2.writer.builder.DexBuilder
import org.jf.dexlib2.writer.io.MemoryDataStore
import org.jf.dexlib2.writer.pool.DexPool
import org.jf.smali.smaliFlexLexer
import org.jf.smali.smaliParser
import org.jf.smali.smaliTreeWalker
import java.io.ByteArrayInputStream
import java.io.StringReader

class SmaliSyntaxException(val errors: List<String>) :
    Exception(errors.take(8).joinToString("\n").ifEmpty { "Ошибка синтаксиса smali" })

/** Сборка smali -> ClassDef и пересборка DEX с заменёнными классами (smali/dexlib2 2.5.2). */
object SmaliAssembler {

    /** Opcodes по версии из заголовка DEX: иначе новые инструкции (invoke-custom и т.п.) не запишутся. */
    fun opcodesFor(dexBytes: ByteArray): Opcodes {
        return try {
            if (HeaderItem.verifyMagic(dexBytes, 0)) Opcodes.forDexVersion(HeaderItem.getVersion(dexBytes, 0))
            else Opcodes.getDefault()
        } catch (_: Exception) {
            Opcodes.getDefault()
        }
    }

    fun assemble(smali: String, opcodes: Opcodes): ClassDef {
        val errors = ArrayList<String>()
        val lexer = smaliFlexLexer(StringReader(smali), opcodes.api)
        // Лексер печатает в stderr; его ошибки всё равно всплывут в парсере как INVALID_TOKEN
        lexer.setSuppressErrors(true)
        val tokens = CommonTokenStream(lexer as TokenSource)
        val parser = object : smaliParser(tokens) {
            override fun emitErrorMessage(msg: String) {
                errors.add(msg)
            }
        }
        parser.setVerboseErrors(false)
        parser.setAllowOdex(false)
        parser.setApiLevel(opcodes.api)
        val result = parser.smali_file()
        if (parser.numberOfSyntaxErrors > 0 || lexer.numberOfSyntaxErrors > 0) {
            throw SmaliSyntaxException(errors.ifEmpty { listOf("Ошибка лексического разбора smali") })
        }

        val treeStream = CommonTreeNodeStream(result.tree as CommonTree)
        treeStream.tokenStream = tokens
        val dexBuilder = DexBuilder(opcodes)
        val walker = object : smaliTreeWalker(treeStream) {
            override fun emitErrorMessage(msg: String) {
                errors.add(msg)
            }
        }
        walker.setApiLevel(opcodes.api)
        walker.setVerboseErrors(false)
        walker.setDexBuilder(dexBuilder)
        try {
            walker.smali_file()
        } catch (e: Exception) {
            errors.add(e.message ?: e.javaClass.simpleName)
        }
        if (walker.numberOfSyntaxErrors > 0 || errors.isNotEmpty()) {
            throw SmaliSyntaxException(errors.ifEmpty { listOf("Ошибка сборки smali") })
        }

        // Мини-DEX с одним классом -> неизменяемый ClassDef, пригодный для DexPool
        val store = MemoryDataStore()
        dexBuilder.writeTo(store)
        val mini = DexBackedDexFile.fromInputStream(opcodes, ByteArrayInputStream(store.data))
        return mini.classes.singleOrNull()
            ?: throw SmaliSyntaxException(listOf("В файле должен быть ровно один .class"))
    }

    /** Новый DEX: все классы исходного, классы из [replacements] (по дескриптору) — заменены. */
    fun rebuildDex(dexBytes: ByteArray, replacements: Map<String, ClassDef>): ByteArray {
        val opcodes = opcodesFor(dexBytes)
        val original = DexBackedDexFile.fromInputStream(opcodes, ByteArrayInputStream(dexBytes))
        val pool = DexPool(opcodes)
        val seen = HashSet<String>()
        for (c in original.classes) {
            pool.internClass(replacements[c.type] ?: c)
            seen.add(c.type)
        }
        // Новые классы (которых не было в DEX) тоже добавляем
        for ((type, def) in replacements) {
            if (type !in seen) pool.internClass(def)
        }
        val store = MemoryDataStore()
        pool.writeTo(store)
        return store.data
    }
}
