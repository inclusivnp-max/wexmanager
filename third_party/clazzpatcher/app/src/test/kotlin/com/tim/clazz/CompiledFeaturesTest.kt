package com.tim.clazz

import com.tim.clazz.model.ClassFile
import com.tim.clazz.model.ConstantDynamicInfo
import com.tim.clazz.model.ConstantStringInfo
import com.tim.clazz.model.attributes.AttributeInfo
import com.tim.clazz.model.attributes.Code
import com.tim.clazz.model.attributes.MethodParameters
import com.tim.clazz.model.attributes.NestHost
import com.tim.clazz.model.attributes.NestMembers
import com.tim.clazz.model.attributes.PermittedSubclasses
import com.tim.clazz.model.attributes.Record
import com.tim.clazz.model.attributes.RuntimeVisibleTypeAnnotations
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Классы, собранные javac из исходника со всеми target_type аннотаций типов, записями, sealed,
 * condy (switch по enum-константам), MethodParameters и строками с \0 и суррогатами.
 */
class CompiledFeaturesTest {

    @Test
    fun `классы javac со всеми возможностями формата`(@TempDir tmp: Path) {
        val compiler = ToolProvider.getSystemJavaCompiler()
        assumeTrue(compiler != null, "нет javac (запуск не на JDK)")
        val feature = Runtime.version().feature()
        assumeTrue(feature >= 21, "нужен JDK 21+, сейчас $feature")

        val src = tmp.resolve("src/p/Features.java").toFile()
        src.parentFile.mkdirs()
        src.writeText(FEATURES_SOURCE)
        val out = tmp.resolve("out").toFile()
        out.mkdirs()
        val status = compiler!!.run(null, null, null, "-g", "-parameters", "--release", "21", "-d", out.path, src.path)
        assertEquals(0, status, "javac завершился с ошибкой")

        val bytes = out.walk().filter { it.name.endsWith(".class") }
            .associate { it.relativeTo(out).path.removeSuffix(".class").replace(File.separatorChar, '.') to it.readBytes() }
        assertTrue(bytes.size >= 10, "классов: ${bytes.keys}")
        val models = bytes.mapValues { (name, b) -> assertRoundTrip(b, name) }

        val targetTypes = sortedSetOf<Int>()
        for (model in models.values) forEachAttribute(model) { a ->
            if (a is RuntimeVisibleTypeAnnotations) a.annotations.forEach { targetTypes.add(it.targetType) }
        }
        val expected = (listOf(0x00, 0x01) + (0x10..0x17) + (0x40..0x4B)).toSortedSet()
        assertEquals(expected, targetTypes, "покрытие target_type")

        val features = models.getValue("p.Features")
        assertTrue(features.constantPool.any { it is ConstantStringInfo && features.utf8(it.stringIndex) == STRING_CONSTANT })
        assertTrue(features.methods.any { m -> m.attributes.any { it is MethodParameters } })
        assertTrue(features.attributes.any { it is NestMembers })
        assertTrue(models.getValue("p.Features\$Gen").attributes.any { it is NestHost })
        assertTrue(models.getValue("p.Shape").attributes.any { it is PermittedSubclasses })
        val record = models.getValue("p.Circle").attributes.filterIsInstance<Record>().single()
        val circle = models.getValue("p.Circle")
        assertEquals(listOf("r", "y", "names"), record.components.map { circle.utf8(it.nameIndex) })
        assertTrue(record.components.all { it.attributes.isNotEmpty() })
        assertTrue(models.getValue("p.Switches").constantPool.any { it is ConstantDynamicInfo }, "condy в p.Switches")

        // JVM принимает классы (записанные из модели байты) и выполняет их
        val loader = BytesClassLoader(models.mapValues { (_, m) -> com.tim.clazz.writer.ClassWriter(m).toByteArray() })
        val kind = loader.loadClass("p.Kind")
        val b = kind.enumConstants.single { (it as Enum<*>).name == "B" }
        val sw = loader.loadClass("p.Switches").getDeclaredMethod("sw", loader.loadClass("p.Shape"))
        sw.isAccessible = true
        assertEquals(5, sw.invoke(null, b))
        val featuresClass = loader.loadClass("p.Features")
        val instance = featuresClass.getDeclaredConstructor().newInstance()
        val tableSwitch = featuresClass.getDeclaredMethod("tableSwitch", Int::class.javaPrimitiveType)
        tableSwitch.isAccessible = true
        assertEquals(30, tableSwitch.invoke(instance, 3))
        val str = featuresClass.getDeclaredField("STR")
        str.isAccessible = true
        assertEquals(STRING_CONSTANT, str.get(null))
    }

    private fun forEachAttribute(cf: ClassFile, action: (AttributeInfo) -> Unit) {
        fun visit(list: List<AttributeInfo>) {
            for (a in list) {
                action(a)
                if (a is Code) visit(a.attributes)
                if (a is Record) a.components.forEach { visit(it.attributes) }
            }
        }
        visit(cf.attributes)
        cf.fields.forEach { visit(it.attributes) }
        cf.methods.forEach { visit(it.attributes) }
    }

    private companion object {
        const val STRING_CONSTANT = "nul:\u0000 sup:😀 cyr:Ж"

        val FEATURES_SOURCE = """
            package p;

            import java.io.Serializable;
            import java.lang.annotation.*;
            import java.util.*;
            import java.util.function.*;

            @Target({ElementType.TYPE_USE, ElementType.TYPE_PARAMETER})
            @Retention(RetentionPolicy.RUNTIME)
            @interface V { int value() default 0; }

            @Target({ElementType.TYPE_USE, ElementType.TYPE_PARAMETER})
            @Retention(RetentionPolicy.CLASS)
            @interface I { String value() default ""; }

            @Retention(RetentionPolicy.RUNTIME)
            @interface All {
                byte b() default 1; char c() default 'x'; double d() default 1.5; float f() default 2.5f;
                int i() default 3; long j() default 4L; short s() default 5; boolean z() default true;
                String str() default "s\u0000😀"; ElementType e() default ElementType.FIELD;
                Class<?> cls() default String[].class; Retention ann() default @Retention(RetentionPolicy.CLASS);
                int[] arr() default {1, 2};
            }

            public class Features<@V(0x00) T extends @V(0x11) Object & @I("bound") Comparable<T>>
                    extends @V(0x10) ArrayList<@V T> implements @I Serializable {
                static final String STR = "nul:\u0000 sup:😀 cyr:Ж";
                static final long L = Long.MIN_VALUE;
                static final double NAN = Double.NaN, NEG_ZERO = -0.0;
                static final float FNEG = -0.0f;

                @V(0x13) List<@V(0x13) ? extends @I Number> @V [] field;
                Map<@I String, @V List<@V ? super @I Integer>> map;
                Features<@V T>.@V Inner inner;

                class Inner { Inner(@All int x) { } }

                @All(b = -1, c = '\u0000', d = Double.NaN, f = Float.NEGATIVE_INFINITY, i = Integer.MIN_VALUE,
                     j = Long.MAX_VALUE, s = Short.MIN_VALUE, z = false, str = "", arr = {})
                @Deprecated
                <@V(0x01) M extends @V(0x12) Number> @V(0x14) String method(@V(0x15) Features<T> this,
                        @V(0x16) int p, @All final String q) throws @V(0x17) Exception {
                    @V(0x40) String local = "x" + p + q + STR;
                    try (@V(0x41) AutoCloseable r = () -> { }) {
                        local += r;
                    } catch (@V(0x42) Exception e) {
                        local = null;
                    }
                    Object o = local;
                    boolean b = o instanceof @V(0x43) String;
                    Object n = new @V(0x44) ArrayList<String>();
                    Supplier<List<String>> s1 = @V(0x45) ArrayList::new;
                    Function<Object, String> s2 = @V(0x46) String::valueOf;
                    String c = (@V(0x47) String) o;
                    Object g = new <@V(0x48) String>Gen("x");
                    String gm = this.<@V(0x49) String>id("y");
                    Function<String, Gen> gc = Gen::<@V(0x4A) String>new;
                    Function<String, String> gmr = this::<@V(0x4B) String>id;
                    return local + b + n + s1 + s2 + c + g + gm + gc + gmr;
                }

                <X> X id(X x) { return x; }

                static class Gen { <U> Gen(U u) { } }

                int tableSwitch(int x) {
                    switch (x) {
                        case 1: return 10;
                        case 2: return 20;
                        case 3: return 30;
                        case 5: return 50;
                        default: return -1;
                    }
                }

                long loops(long a, double d) {
                    long[] arr = new long[300];
                    for (int k = 0; k < 300; k++) arr[k] = a + k;
                    synchronized (this) { a += (long) d; }
                    int[][] multi = new int[2][3];
                    return a + arr[0] + multi.length;
                }
            }

            sealed interface Shape permits Circle, Square, Kind { }

            record Circle(@V double r, @All(i = 7) int y, List<@I String> names) implements Shape {
                Circle { Objects.requireNonNull(names); }
            }

            final class Square implements Shape { }

            enum Kind implements Shape { A, B }

            final class Switches {
                static int sw(Shape s) {
                    return switch (s) {
                        case Circle c when c.r() > 1 -> 1;
                        case Circle c -> 2;
                        case Square q -> 3;
                        case Kind.A -> 4;
                        case Kind.B -> 5;
                    };
                }
            }
        """.trimIndent()
    }
}
