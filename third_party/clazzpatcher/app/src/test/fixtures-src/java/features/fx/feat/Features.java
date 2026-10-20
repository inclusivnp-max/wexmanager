package fx.feat;

import java.lang.annotation.*;
import java.util.*;
import java.util.function.*;

/** Фикстура для csmali/конвертеров: максимум возможностей языка и байткода в одной программе. */
public class Features {

    @Retention(RetentionPolicy.RUNTIME) @Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER})
    @interface Visible { String value() default "v"; int[] nums() default {1, 2}; Class<?> type() default int.class; }

    @Retention(RetentionPolicy.CLASS) @interface Invisible { }

    @Target(ElementType.TYPE_USE) @Retention(RetentionPolicy.RUNTIME) @interface TypeUse { }

    sealed interface Shape permits Circle, Square, Rect { }
    record Circle(double r) implements Shape { }
    record Square(int side) implements Shape { }
    static final class Rect implements Shape {
        final int w, h;
        Rect(int w, int h) { this.w = w; this.h = h; }
    }

    enum Color { RED, GREEN, BLUE; Color next() { return values()[(ordinal() + 1) % 3]; } }

    interface Greeter {
        String name();
        default String greet() { return prefix() + name(); }
        private String prefix() { return "Привет, "; }
        static Greeter of(String n) { return () -> n; }
    }

    @Visible("класс") @Invisible
    static class Inner<T extends Comparable<T>> {
        @Visible private final List<@TypeUse T> items = new ArrayList<>();
        void add(@Visible("p") T t) { items.add(t); }
        T max() { return Collections.max(items); }
    }

    private int counter;
    private static long total = 1L << 40;
    static final String CONST = "константа";
    static final double PI2 = Math.PI * 2;

    static String area(Shape s) {
        return switch (s) {
            case Circle c when c.r() > 10 -> "большой круг";
            case Circle c -> String.format(Locale.ROOT, "круг %.2f", Math.PI * c.r() * c.r());
            case Square sq -> "квадрат " + sq.side() * sq.side();
            case Rect r -> "прямоугольник " + (r.w * r.h);
        };
    }

    static int sw(int x) {
        switch (x) {
            case 1: return 10;
            case 2: return 20;
            case 3: return 30;
            case 1000: return -1;
            case -7: return 7;
            default: return 0;
        }
    }

    static String sws(String s) {
        switch (s) {
            case "a": return "A";
            case "бэ": return "Б";
            default: return "?";
        }
    }

    synchronized int inc(int by) { counter += by; return counter; }

    static long mix(long a, double b, float c, byte d, short e, char f, boolean g) {
        long r = a ^ (long) b;
        r += (long) (c * 3) + d + e + f;
        return g ? r : -r;
    }

    static int tryFinally(int x) {
        int r = 0;
        try {
            r = 100 / x;
        } catch (ArithmeticException ex) {
            r = -1;
        } finally {
            r += 1000;
        }
        return r;
    }

    static String resources() throws Exception {
        StringBuilder sb = new StringBuilder();
        try (AutoCloseable a = () -> sb.append("[закрыт]")) {
            sb.append("тело");
        }
        return sb.toString();
    }

    static int arrays() {
        int[][] grid = new int[3][4];
        boolean[] flags = new boolean[3];
        byte[] bytes = {1, 2, 3};
        char[] chars = "abc".toCharArray();
        flags[1] = true;
        for (int i = 0; i < 3; i++) for (int j = 0; j < 4; j++) grid[i][j] = i * j + (flags[i] ? 1 : 0) + bytes[i] + chars[i];
        long[] ls = new long[2]; ls[1] = Long.MAX_VALUE;
        double[] ds = {0.5, -0.0, Double.NaN};
        return grid[2][3] + (int) (ls[1] >>> 60) + (Double.isNaN(ds[2]) ? 1 : 0) + (1 / ds[1] < 0 ? 1 : 0);
    }

    /** type-аннотации внутри кода: локал, cast, new, instanceof, catch. */
    static String typeUses(Object o) {
        @TypeUse String local = "локал";
        List<@TypeUse String> list = new @TypeUse ArrayList<>();
        list.add(local);
        if (o instanceof @TypeUse String s) list.add(s);
        list.add((@TypeUse String) String.valueOf(list.size()));
        try {
            Integer.parseInt("x");
        } catch (@TypeUse NumberFormatException e) {
            list.add("catch");
        }
        return String.join("|", list);
    }

    static <T> T first(List<? extends T> list) { return list.isEmpty() ? null : list.get(0); }

    @SafeVarargs
    static <T> int count(T... xs) { return xs.length; }

    @Deprecated
    static String lambdas() {
        Function<Integer, Integer> sq = x -> x * x;
        BiFunction<String, Integer, String> rep = String::repeat;
        Supplier<List<String>> mk = ArrayList::new;
        List<String> l = mk.get();
        l.add(rep.apply("ab", 2));
        l.add(String.valueOf(sq.apply(12)));
        Runnable r = () -> l.add("run");
        r.run();
        return String.join(",", l);
    }

    public static void main(String[] args) throws Exception {
        List<String> out = new ArrayList<>();
        out.add(area(new Circle(1)));
        out.add(area(new Circle(11)));
        out.add(area(new Square(4)));
        out.add(area(new Rect(2, 5)));
        out.add(Color.BLUE.next() + " " + Color.valueOf("GREEN").ordinal());
        out.add(Greeter.of("мир").greet());
        Inner<String> in = new Inner<>();
        in.add("b"); in.add("я"); in.add("a");
        out.add(in.max());
        Features f = new Features();
        f.inc(5); out.add("inc " + f.inc(7));
        out.add("mix " + mix(123456789012L, 3.75, 2.5f, (byte) -3, (short) 300, 'Ж', false));
        out.add("sw " + sw(1) + sw(3) + sw(1000) + sw(-7) + sw(99));
        out.add("sws " + sws("a") + sws("бэ") + sws("x"));
        out.add("try " + tryFinally(0) + " " + tryFinally(5));
        out.add(resources());
        out.add("arr " + arrays());
        out.add("gen " + first(List.of("x", "y")) + count(1, 2, 3));
        out.add(lambdas());
        out.add(typeUses("строка"));
        out.add(CONST + " " + (total >> 20) + " " + (PI2 > 6));
        StringBuilder sb = new StringBuilder();
        for (String s : out) sb.append(s).append('\n');
        System.out.print(sb);
    }
}
