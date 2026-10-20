package com.tim.c2d

/**
 * Java-программы для тестов c2d. У каждой есть `main` с детерминированным выводом: после
 * преобразования их можно запустить и на ART (`dalvikvm -cp out.dex c2d.Samples`) и сравнить
 * с выводом `java`.
 */
object C2dSamples {

    /** Основная программа: арифметика, ветвления, switch, исключения, массивы, объекты (Java 8, без лямбд). */
    val SAMPLES = """
        package c2d;

        import java.lang.annotation.Retention;
        import java.lang.annotation.RetentionPolicy;
        import java.util.ArrayList;
        import java.util.Arrays;
        import java.util.List;

        public class Samples {
            interface Shape {
                double area();
                default String describe() { return name() + ":" + fmt(area()); }
                String name();
                static String fmt(double d) { return String.valueOf(Math.round(d * 100) / 100.0); }
            }

            static final class Circle implements Shape {
                final double r;
                Circle(double r) { this.r = r; }
                public double area() { return Math.PI * r * r; }
                public String name() { return "circle"; }
            }

            static final class Rect implements Shape {
                final double w, h;
                Rect(double w, double h) { this.w = w; this.h = h; }
                public double area() { return w * h; }
                public String name() { return "rect"; }
            }

            enum Color { RED, GREEN, BLUE; Color next() { return values()[(ordinal() + 1) % values().length]; } }

            @Retention(RetentionPolicy.RUNTIME)
            @interface Tag { String value() default "def"; int[] nums() default {1, 2}; Color color() default Color.GREEN; }

            static class Failure extends Exception { Failure(String m) { super(m); } }

            static int counter;
            static final String CONST = "const";
            static final long BIG = 1L << 40;
            static final double PI2 = 6.28;
            static final char CH = 'x';
            static final boolean FLAG = true;
            static final byte B = -5;
            static final short S = 300;
            static final float F = 1.5f;
            static final int[] TABLE;
            static { TABLE = new int[] {3, 1, 4, 1, 5, 9, 2, 6}; }

            long total;
            private int secret = 7;

            synchronized void add(long v) { total += v; }
            static synchronized int inc() { return ++counter; }

            static int sparse(int x) {
                switch (x) { case 1: return 10; case 2: return 20; case 5: return 50; case 100: return 1000; case -7: return -70; default: return -1; }
            }

            static int dense(int x) {
                switch (x) { case 0: case 1: return 1; case 2: return 4; case 3: return 9; case 4: return 16; default: return 0; }
            }

            static String strings(String s) {
                switch (s) { case "a": return "A"; case "bb": return "BB"; case "FB": return "fb"; case "Ea": return "ea"; default: return "?"; }
            }

            static String colors(Color c) {
                switch (c) { case RED: return "r"; case BLUE: return "b"; default: return "g"; }
            }

            static long fib(int n) { long a = 0, b = 1; for (int i = 0; i < n; i++) { long t = a + b; a = b; b = t; } return a; }

            static double mix(int i, long l, float f, double d) {
                return i * l + f / d - (i % 3) + (l >>> 3) + (l << 2) + (i >> 1) + (-l) + (~i) + (i ^ 0x55)
                    + (l & 0xFF) + (int) d + (long) f + (float) l + (short) i + (byte) l + (char) i + d % 1.5 + f % 0.5f;
            }

            static String compare(double a, double b, float c, long x, long y) {
                return "" + (a < b) + (a > b) + (a == b) + (c < 1f) + (c >= 2f) + (x < y) + (x == y) + (Double.isNaN(a) ? "nan" : "num");
            }

            static String tryCatch(int x) {
                StringBuilder sb = new StringBuilder();
                try {
                    sb.append("t");
                    if (x == 0) throw new IllegalStateException("zero");
                    if (x == 3) throw new Failure("three");
                    sb.append(10 / (x - 1));
                } catch (IllegalStateException e) {
                    sb.append("ise:").append(e.getMessage());
                } catch (ArithmeticException e) {
                    sb.append("ae");
                } catch (Failure e) {
                    sb.append("f:").append(e.getMessage());
                } finally {
                    sb.append("|fin");
                }
                return sb.toString();
            }

            static int nestedTry() {
                int r = 0;
                try {
                    try { throw new RuntimeException("x"); } finally { r += 1; }
                } catch (RuntimeException e) { r += 10; }
                try { r += Integer.parseInt("zz"); } catch (NumberFormatException e) { r += 100; }
                return r;
            }

            static String syncBlock(Object lock, int n) {
                int s = 0;
                for (int i = 0; i < n; i++) { synchronized (lock) { s += i; if (s > 1000) break; } }
                synchronized (Samples.class) { s++; }
                return "sync" + s;
            }

            static String arrays() {
                boolean[] z = new boolean[3]; z[1] = true; z[2] = !z[1];
                byte[] b = {1, -2, 3}; b[0] += b[1];
                char[] c = {'a', 'b'}; c[1]++;
                short[] s = {1000, -1000}; s[0] >>= 2;
                int[] i = new int[4]; i[2] = 7; i[3] += 5; i[3]++;
                long[] l = new long[2]; l[0] = 1L << 50; l[1] += 3; l[1]++;
                float[] f = {1.5f}; f[0] *= 2;
                double[] d = {2.25}; d[0] /= 3;
                int[][] m = new int[3][4]; m[1][2] = 12;
                long[][][] mm = new long[2][3][]; mm[1][2] = new long[5]; mm[1][2][4] = 9;
                boolean[][] zz = new boolean[2][2]; zz[1][1] = true;
                String[][] ss = new String[2][2]; ss[0][1] = "s";
                Object[] o = {z, b, null};
                int[] copy = i.clone();
                copy[0] = 99;
                return Arrays.toString(z) + Arrays.toString(b) + Arrays.toString(c) + Arrays.toString(s)
                    + Arrays.toString(i) + Arrays.toString(copy) + Arrays.toString(l) + Arrays.toString(f) + Arrays.toString(d)
                    + m[1][2] + m.length + m[0].length + mm[1][2][4] + (zz[1][1] ? "Z" : "z") + ss[0][1] + o.length
                    + (z[1] ? "T" : "F") + b[1] + (o[2] == null);
            }

            int dupForms() {
                int[] a = {1};
                int x, y;
                x = y = a[0] += 5;
                total++;
                long[] la = {5};
                la[0]++;
                long q = total++ + la[0]--;
                long w;
                long v = w = q * 2;
                secret += 3;
                return x + y + (int) q + (int) v + (int) w + secret + a[0]-- + --a[0];
            }

            static Object pick(int k) {
                Object o = k > 0 ? (Object) "str" : (k < 0 ? (Object) Integer.valueOf(k) : new ArrayList<String>());
                return o instanceof String ? ((String) o).toUpperCase() : o;
            }

            static String loops() {
                StringBuilder sb = new StringBuilder();
                outer:
                for (int i = 0; i < 5; i++) {
                    int j = 0;
                    do {
                        if (j == 3) continue outer;
                        if (i == 4) break outer;
                        sb.append(i * j);
                        j++;
                    } while (j < i);
                    while (j > 0) { j -= 2; sb.append('.'); }
                }
                return sb.toString();
            }

            static String varargs(String sep, Object... parts) {
                StringBuilder sb = new StringBuilder();
                for (Object p : parts) { if (sb.length() > 0) sb.append(sep); sb.append(p); }
                return sb.toString();
            }

            static char chars(char c, short s, byte b) { return (char) (c + s + b); }

            static long manyArgs(int a, long b, double c, float d, Object e, int f, long g, char h, boolean i, byte j, short k) {
                return a + b + (long) c + (long) d + (e == null ? 0 : 1) + f + g + h + (i ? 1 : 0) + j + k;
            }

            class Inner { int value() { return secret + (int) total; } }

            static Runnable anon(final int k) { return new Runnable() { public void run() { counter += k; } }; }

            @Tag(value = "x", nums = {3}, color = Color.BLUE)
            static int annotated(@Tag int p) { return p; }

            static String exceptions(int k) {
                try {
                    if (k == 1) { Object o = "s"; return ((Integer) o).toString(); }
                    if (k == 2) { int[] a = new int[1]; return "" + a[k]; }
                    if (k == 3) { String s = null; return s.trim(); }
                    if (k == 4) throw new Failure("custom");
                    return "ok";
                } catch (ClassCastException e) {
                    return "cce";
                } catch (ArrayIndexOutOfBoundsException e) {
                    return "aioobe";
                } catch (NullPointerException e) {
                    return "npe";
                } catch (Exception e) {
                    return "ex:" + e.getMessage();
                }
            }

            public static void main(String[] args) throws Exception {
                List<String> out = new ArrayList<String>();
                Shape[] shapes = {new Circle(1.5), new Rect(2, 3.5)};
                for (Shape s : shapes) out.add(s.describe());
                out.add(CONST + BIG + PI2 + CH + FLAG + B + S + F + TABLE.length + TABLE[5]);
                for (int x : new int[] {1, 2, 5, 100, -7, 3}) out.add("sp" + sparse(x) + " de" + dense(x));
                for (String s : new String[] {"a", "bb", "FB", "Ea", "zz"}) out.add(strings(s));
                for (Color c : Color.values()) out.add(colors(c) + c.next());
                out.add("fib" + fib(50) + " " + fib(1));
                out.add("mix" + mix(7, 123456789012L, 2.5f, 0.75));
                out.add(compare(1.0, 2.0, 0.5f, 5L, 5L) + compare(Double.NaN, 1.0, 3f, -1L, 1L));
                for (int x = 0; x < 5; x++) out.add(tryCatch(x));
                out.add("nested" + nestedTry());
                out.add(syncBlock(new Object(), 50));
                out.add(arrays());
                Samples self = new Samples();
                self.add(40); self.add(2);
                out.add("dup" + self.dupForms() + " total" + self.total);
                out.add("inc" + inc() + inc());
                out.add("pick" + pick(1) + pick(-3) + pick(0));
                out.add("loops" + loops());
                out.add(varargs("-", 1, "two", 3.0, 'c', null));
                out.add("chars" + (int) chars('a', (short) 2, (byte) -1));
                out.add("many" + manyArgs(1, 2L, 3.9, 4.9f, null, 6, 7L, 'h', true, (byte) 10, (short) 11));
                out.add("inner" + self.new Inner().value());
                Runnable r = anon(5); r.run(); r.run();
                out.add("anon" + counter);
                out.add("ann" + annotated(9));
                for (int k = 0; k < 5; k++) out.add(exceptions(k));
                StringBuilder all = new StringBuilder();
                for (String s : out) all.append(s).append('\n');
                System.out.print(all);
            }
        }
    """.trimIndent()

    /** Метод с сотнями локалов: номера регистров выходят за 4 и 8 бит. */
    fun bigMethod(locals: Int = 300): String {
        val sb = StringBuilder()
        sb.append("package c2d;\n\npublic class Big {\n")
        sb.append("    static long compute(int p, long q, Object o) {\n")
        sb.append("        int v0 = p;\n")
        for (i in 1 until locals) sb.append("        int v$i = v${i - 1} * 3 + $i;\n")
        sb.append("        long acc = q;\n")
        sb.append("        String s = String.valueOf(o);\n")
        for (i in 0 until locals step 7) sb.append("        acc += v$i ^ (acc >>> 3);\n")
        sb.append("        double d = acc * 0.5;\n")
        sb.append("        long[] arr = new long[3]; arr[1] = acc; arr[2] += (long) d;\n")
        sb.append("        boolean[] flags = new boolean[2]; flags[1] = acc > 0;\n")
        sb.append("        try { if (s.length() > 100) throw new IllegalStateException(s); } catch (IllegalStateException e) { acc--; }\n")
        sb.append("        synchronized (s) { acc += s.length(); }\n")
        sb.append("        return acc + arr[2] + (flags[1] ? 1 : 0) + v${locals - 1} + s.hashCode();\n")
        sb.append("    }\n")
        sb.append("    public static void main(String[] args) { System.out.println(compute(3, 1234567890123L, \"obj\")); }\n")
        sb.append("}\n")
        return sb.toString()
    }

    /** Возможности Java 9+ (компилируются с --release 17): лямбды, indy-конкатенация, record, private-методы интерфейсов. */
    val MODERN = """
        package c2d;

        import java.util.ArrayList;
        import java.util.List;
        import java.util.function.Function;
        import java.util.function.Supplier;

        public class Modern {
            interface Greeter {
                String name();
                default String greet() { return prefix() + name(); }
                private String prefix() { return "hi "; }
                static Greeter of(String n) { return () -> n; }
            }

            record Point(int x, int y) { int sum() { return x + y; } }

            sealed interface Expr permits Num, Add {}
            record Num(int v) implements Expr {}
            record Add(Expr l, Expr r) implements Expr {}

            static int eval(Expr e) {
                if (e instanceof Num n) return n.v();
                Add a = (Add) e;
                return eval(a.l()) + eval(a.r());
            }

            private int hidden = 5;

            class Nested { int peek() { return hidden * 2; } }

            static String concat(String a, int b, long c, char d) { return a + b + c + d + "!"; }

            public static void main(String[] args) {
                List<String> out = new ArrayList<>();
                Function<Integer, Integer> sq = x -> x * x;
                Supplier<String> s = () -> "sup";
                out.add("sq" + sq.apply(7) + s.get());
                out.add(Greeter.of("bob").greet());
                out.add(new Point(2, 3).sum() + " " + new Point(1, 1));
                out.add("eval" + eval(new Add(new Num(2), new Add(new Num(3), new Num(4)))));
                out.add(concat("a", 1, 2L, 'c'));
                out.add("nested" + new Modern().new Nested().peek());
                var text = switch (out.size()) { case 6 -> "six"; default -> "other"; };
                out.add(text);
                out.forEach(System.out::println);
            }
        }
    """.trimIndent()
}
