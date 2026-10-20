package com.tim.c2d

/**
 * Программы без обращений к JDK (кроме `System.out.println` и исключений): их вывод на JVM
 * сравнивается с исполнением получившегося DEX в [DexInterpreter]. Нацелены на места, где
 * перевод легко ошибиться: перестановки стека (dup_x1/dup_x2/dup2_x1/dup2_x2), составные
 * присваивания, long/double, ветвления, switch, исключения, synchronized, объекты и интерфейсы.
 */
object C2dCalcPrograms {

    val CALC = """
        package c2d;

        public class Calc {
            static long acc = 5;
            static int[] counters = new int[4];
            static final int K = 1000;
            static double dstat = 0.125;
            long field = 3;
            int ifield;
            double dfield = 1.25;

            interface Op {
                long apply(long a, long b);
                default long twice(long a) { return apply(a, a) + base(); }
                static long base() { return 7; }
            }

            static class Add implements Op { public long apply(long a, long b) { return a + b; } }

            static class Mul implements Op {
                public long apply(long a, long b) { return a * b; }
                public long twice(long a) { return Op.super.twice(a) * 2; }
            }

            static class Boom extends RuntimeException {
                final int code;
                Boom(int c) { super("boom"); code = c; }
            }

            static class Node {
                int v;
                Node next;
                Node(int v, Node next) { this.v = v; this.next = next; }
                int sum() { return v + (next == null ? 0 : next.sum()); }
            }

            static int shuffles(int[] a, int i) {
                int x = a[i]++;
                int y = ++a[i];
                a[i] += a[i + 1] *= 2;
                int z;
                z = a[0] = a[1] = x + y;
                int w = a[i] = a[i + 1] = z * 3;
                return x * 1000 + y * 100 + z + a[i] + w;
            }

            static long wideShuffles(long[] a, int i, Calc c) {
                long x = a[i]++;
                long y = a[i] += 5;
                c.field += 7;
                long z = c.field++;
                long w = acc++ + (acc += 3);
                double dd = c.dfield *= 2;
                double[] da = {1.5, 2.5};
                double e = da[1] += da[0];
                int q = c.ifield++;
                int t = c.ifield += 10;
                return x + y * 3 + z * 5 + w * 7 + a[i] + c.field + (long) (dd * 100) + (long) (e * 10) + q + t;
            }

            static double floats(double d, float f, int n) {
                double r = 0;
                for (int i = 0; i < n; i++) { r += d / (i + 1); f *= 1.5f; }
                r += f;
                r += (float) d;
                r += d % 0.3;
                r -= -f;
                double nan = 0.0 / 0.0;
                boolean isNan = nan != nan;
                float ff = (float) r;
                return r + (long) (r * 3) + (isNan ? 1 : 0) + (ff > 10f ? 0.5 : 0.25) + (r < d ? 1 : 2) + dstat + (f < nan ? 100 : 0);
            }

            static int switches(int k) {
                int r = 0;
                switch (k) {
                    case -1: r = 1; break;
                    case 0: r = 2;
                    case 1: r += 3; break;
                    case 2: case 3: r = 4; break;
                    case 10: r = 5; break;
                    default: r = -k;
                }
                switch (k * 1000) {
                    case 1000: r += 10; break;
                    case 3000: r += 30; break;
                    case -1000: r += 50; break;
                    case 1000000: r += 99; break;
                    default: r += 1;
                }
                return r;
            }

            static long loops(int n) {
                long s = 0;
                outer:
                for (int i = 0; i < n; i++) {
                    for (int j = n; j > 0; j -= 2) {
                        if ((i ^ j) % 5 == 0) continue outer;
                        if (i * j > 40) break outer;
                        s += i * j;
                    }
                    int k = 0;
                    do { s ^= k << i; k++; } while (k < i);
                    while (s > 1000) s -= 997;
                }
                return s;
            }

            static int exceptions(int k) {
                int r = 0;
                try {
                    try {
                        if (k == 0) r = 10 / k;
                        if (k == 1) { int[] a = new int[2]; r = a[k + 5]; }
                        if (k == 2) throw new Boom(42);
                        if (k == 3) { Object o = counters; long[] l = (long[]) o; r = l.length; }
                        if (k == 4) { int[] a = new int[k - 10]; r = a.length; }
                        r = 1;
                    } finally {
                        r += 100;
                    }
                } catch (ArithmeticException e) {
                    r += 1000;
                } catch (ArrayIndexOutOfBoundsException e) {
                    r += 2000;
                } catch (Boom e) {
                    r += 3000 + e.code;
                } catch (RuntimeException e) {
                    r += 4000;
                }
                return r;
            }

            static int rethrow(int k) {
                try {
                    try {
                        if (k > 0) throw new Boom(k);
                        return 0;
                    } catch (Boom e) {
                        if (e.code > 1) throw new Boom(e.code * 10);
                        return -1;
                    }
                } catch (Boom e) {
                    return e.code;
                }
            }

            static int arrays(int n) {
                int[][] m = new int[n][n + 1];
                for (int i = 0; i < n; i++) for (int j = 0; j <= n; j++) m[i][j] = i * j;
                boolean[][] b = new boolean[2][3];
                b[1][2] = true;
                byte[] bytes = {1, -2, 127};
                bytes[1] += 100;
                bytes[2]++;
                char[] cs = {'a', 'z'};
                cs[0] += 2;
                short[] ss = {-5};
                ss[0] *= 1000;
                long[][][] cube = new long[2][2][];
                cube[1][1] = new long[3];
                cube[1][1][2] = 1L << 40;
                float[] fs = {0.5f};
                fs[0] /= 4;
                int s = 0;
                for (int[] row : m) for (int v : row) s += v;
                return s + (b[1][2] ? 1 : 0) + bytes[1] + bytes[2] + cs[0] + ss[0] + (int) (cube[1][1][2] >> 30) + m.length + m[0].length + (int) (fs[0] * 1000);
            }

            static long objects(int n) {
                Op[] ops = {new Add(), new Mul()};
                long r = 1;
                for (int i = 0; i < n; i++) r = ops[i % 2].apply(r, i + 1) + ops[i % 2].twice(i);
                Node list = null;
                for (int i = 0; i < n; i++) list = new Node(i, list);
                Calc c = new Calc();
                c.ifield++;
                c.ifield += c.ifield++ + ++c.ifield;
                return r + list.sum() + c.ifield;
            }

            static int bools(boolean a, boolean b, byte[] bs, boolean[] zs) {
                boolean c = a & b | !a ^ b;
                zs[0] = c;
                zs[1] = !zs[0];
                bs[0] = (byte) (zs[1] ? 5 : -5);
                return (c ? 1 : 0) + (zs[1] ? 2 : 0) + bs[0] + (a == b ? 10 : 20);
            }

            static long conv(long l, int i, char c, short s, byte b, float f, double d) {
                return (int) l + (long) i + (char) i + (short) l + (byte) c + (int) f + (long) d + (int) (float) l
                    + c + s + b + (long) (f * d) + (i >>> 3) + (l >> 7) + (-i) + ~l + (l >>> 60) + (i << 3);
            }

            static synchronized int sync(int v) {
                synchronized (counters) { counters[v & 3]++; }
                return counters[v & 3];
            }

            synchronized long syncInstance(long d) { field += d; return field; }

            static int ternary(int a) {
                Object o = a > 0 ? (Object) counters : (Object) new long[3];
                return o instanceof int[] ? ((int[]) o).length : ((long[]) o).length;
            }

            static long many(int a, long b, double c, float d, Object e, int f, long g, char h, boolean i, byte j, short k) {
                return a + b + (long) c + (long) d + (e == null ? 0 : 1) + f + g + h + (i ? 1 : 0) + j + k;
            }

            static long reuse(long seed) {
                long a = seed;
                int x = (int) a;
                a = x * 3L;
                double d = a;
                x = (int) (d / 2);
                seed = x;
                return a + x + seed;
            }

            public static void main(String[] args) {
                System.out.println(shuffles(new int[] {1, 2, 3}, 1));
                long[] la = {10, 20, 30};
                Calc c = new Calc();
                System.out.println(wideShuffles(la, 1, c));
                System.out.println(la[1]);
                System.out.println(c.field);
                System.out.println(acc);
                System.out.println(c.dfield);
                System.out.println(floats(1.5, 2.5f, 4));
                for (int k = -2; k < 12; k++) System.out.println(switches(k));
                System.out.println(loops(9));
                for (int k = 0; k < 6; k++) System.out.println(exceptions(k));
                for (int k = 0; k < 4; k++) System.out.println(rethrow(k));
                System.out.println(arrays(4));
                System.out.println(objects(6));
                System.out.println(bools(true, false, new byte[1], new boolean[2]));
                System.out.println(bools(true, true, new byte[1], new boolean[2]));
                System.out.println(conv(123456789012L, -77, 'q', (short) -300, (byte) 99, 3.75f, -2.5e10));
                System.out.println(sync(5) + sync(5) + sync(6));
                System.out.println(c.syncInstance(4) + c.syncInstance(-1));
                System.out.println(ternary(1) + ternary(-1));
                System.out.println(many(1, 2L, 3.9, 4.9f, null, 6, 7L, 'h', true, (byte) 10, (short) 11));
                System.out.println(reuse(1234567L));
                System.out.println(K + dstat);
                System.out.println("done");
            }
        }
    """.trimIndent()

    /** Гнёзда Java 11+: внутренние классы обращаются к private-членам друг друга напрямую. */
    val NEST = """
        package c2d;

        public class Nest {
            private int counter = 1;
            private long big = 10;
            private static int shared = 100;

            private int bump(int by) { counter += by; return counter; }
            private long twice() { return big * 2; }
            private static int square(int x) { return x * x; }
            private Nest(int start) { counter = start; }
            public Nest() { this(1); }

            interface Shape {
                private int secret() { return 42; }
                default int reveal() { return secret(); }
                class Helper { static int ask(Shape s) { return s.secret() + 1; } }
            }

            class Inner {
                private int innerValue = 7;
                private int peek() { return counter + innerValue; }
                int work() {
                    int a = bump(5);
                    long b = twice();
                    shared += square(3);
                    Nest other = new Nest(30);
                    return a + (int) b + shared + other.bump(1) + other.counter;
                }
            }

            static class Deep {
                static int build() {
                    Nest n = new Nest(2);
                    Nest.Inner in = n.new Inner();
                    in.innerValue = 11;
                    return in.work() + in.peek() + in.innerValue;
                }
            }

            public static void main(String[] args) {
                System.out.println(Deep.build());
                System.out.println(shared);
                Shape s = new Shape() {};
                System.out.println(s.reveal() + Shape.Helper.ask(s));
                System.out.println(new Nest().new Inner().peek());
            }
        }
    """.trimIndent()

    /** Метод с сотнями int- и long-локалов: регистры за пределами 4 и 8 бит. */
    fun bigCalc(ints: Int = 260, longs: Int = 70): String {
        val sb = StringBuilder()
        sb.append("package c2d;\n\npublic class BigCalc {\n")
        sb.append("    static int[] box = new int[3];\n")
        sb.append("    static long compute(int p, long q, int[] arr) {\n")
        sb.append("        int v0 = p;\n")
        for (i in 1 until ints) sb.append("        int v$i = v${i - 1} * 3 + $i;\n")
        sb.append("        long w0 = q;\n")
        for (i in 1 until longs) sb.append("        long w$i = w${i - 1} * 7 + v${i * 3 % ints};\n")
        sb.append("        long acc = 0;\n")
        for (i in 0 until ints step 5) sb.append("        acc += v$i ^ (acc >>> 3);\n")
        for (i in 0 until longs step 3) sb.append("        acc -= w$i >> ${i % 17};\n")
        sb.append("        arr[1] += v${ints - 1}; arr[2] = arr[1]++ + arr[0];\n")
        sb.append("        try { acc += v7 / (p - 3); } catch (ArithmeticException e) { acc ^= 0x5555; }\n")
        sb.append("        synchronized (arr) { acc += arr[2]; }\n")
        sb.append("        switch (v3 & 7) { case 0: acc++; break; case 3: acc += 3; break; case 5: acc += 5; break; default: acc--; }\n")
        sb.append("        double d = acc * 0.5 + w${longs - 1};\n")
        sb.append("        return acc + (long) d + v${ints - 1} + w${longs / 2};\n")
        sb.append("    }\n")
        sb.append("    public static void main(String[] args) {\n")
        sb.append("        System.out.println(compute(3, 1234567890123L, box));\n")
        sb.append("        System.out.println(compute(5, -99L, box));\n")
        sb.append("        System.out.println(box[1] + box[2]);\n")
        sb.append("    }\n}\n")
        return sb.toString()
    }
}
