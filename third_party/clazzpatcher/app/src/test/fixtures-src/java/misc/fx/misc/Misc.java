package fx.misc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/** Лямбды, анонимные/внутренние классы, мосты, varargs, assert, invoke/range, много регистров. */
public class Misc implements Comparator<String> {
    private static final List<String> NAMES = new ArrayList<>();
    private int counter;

    static {
        NAMES.add("a");
        NAMES.add("b");
    }

    public int compare(String a, String b) {
        return a.length() - b.length();
    }

    int lambdas(final int base) {
        Function<Integer, Integer> add = x -> x + base + counter;
        Supplier<String> sup = () -> "v" + base;
        Runnable r = this::inc;
        r.run();
        List<String> copy = new ArrayList<>(NAMES);
        Collections.sort(copy, (x, y) -> y.compareTo(x));
        return add.apply(1) + sup.get().length() + copy.size();
    }

    private void inc() {
        counter++;
    }

    static int varargs(int first, Object... rest) {
        assert rest != null : "rest";
        return first + rest.length;
    }

    static long manyArgs(int a, long b, double c, float d, byte e, short f, char g, boolean h, Object i, String j, int k) {
        return a + b + (long) c + (long) d + e + f + g + (h ? 1 : 0) + i.hashCode() + j.length() + k;
    }

    static long callMany() {
        return manyArgs(1, 2L, 3.0, 4f, (byte) 5, (short) 6, '7', true, new Object(), "10", 11);
    }

    static int manyLocals(int seed) {
        int v0 = seed, v1 = v0 + 1, v2 = v1 + 1, v3 = v2 + 1, v4 = v3 + 1, v5 = v4 + 1, v6 = v5 + 1, v7 = v6 + 1;
        int v8 = v7 + 1, v9 = v8 + 1, v10 = v9 + 1, v11 = v10 + 1, v12 = v11 + 1, v13 = v12 + 1, v14 = v13 + 1;
        long w0 = v14, w1 = w0 * 3, w2 = w1 * 5, w3 = w2 * 7, w4 = w3 * 11, w5 = w4 * 13, w6 = w5 * 17;
        double d0 = w6, d1 = d0 / 2, d2 = d1 / 3, d3 = d2 / 5;
        Object o0 = "x", o1 = o0, o2 = o1, o3 = o2;
        return v0 + v1 + v2 + v3 + v4 + v5 + v6 + v7 + v8 + v9 + v10 + v11 + v12 + v13 + v14
            + (int) (w0 + w1 + w2 + w3 + w4 + w5 + w6) + (int) (d0 + d1 + d2 + d3) + o3.hashCode();
    }

    class Inner {
        int get() {
            return counter;
        }
    }

    static class Box<T extends Comparable<T>> implements Comparable<Box<T>> {
        T value;

        public int compareTo(Box<T> o) {
            return value.compareTo(o.value);
        }
    }

    interface Shape {
        double area();

        default String name() {
            return "shape";
        }
    }

    Shape square(final double side) {
        return new Shape() {
            public double area() {
                return side * side;
            }
        };
    }
}
