import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Лямбды с разным захватом (c2d встраивает фабрику класса лямбды в место вызова): без захвата,
 * с одним-двумя значениями, с long/double и многими значениями (форма /range), в методе с большим
 * кадром (старшие регистры), ссылки на методы и конструкторы, вложенные лямбды.
 */
public class Main {
    interface Many { String get(int k); }

    private final String name;
    private int counter;

    Main(String name) {
        this.name = name;
        Runnable r = () -> counter += name.length();
        r.run();
    }

    static IntSupplier noCapture() { return () -> 42; }

    static Supplier<String> one(String s) { return () -> s + "!"; }

    static BiFunction<Integer, Integer, Long> two(long base, double scale) {
        return (a, b) -> base + (long) ((a * b) * scale);
    }

    static Many many(int a, long b, double c, String d, char e, boolean f, float g) {
        // 1 + 2 + 2 + 1 + 1 + 1 + 1 = 9 слов захвата: получатель и аргументы — только /range.
        return k -> a + ":" + b + ":" + c + ":" + d + ":" + e + ":" + f + ":" + g + ":" + k;
    }

    static String bigFrame(int p0, int p1, int p2, int p3, int p4, long p5, String p6) {
        int x0 = p0 * 3, x1 = p1 + x0, x2 = x1 * p2, x3 = x2 - p3, x4 = x3 + p4;
        long y0 = p5 * 7, y1 = y0 + x4, y2 = y1 ^ x0;
        String z0 = p6 + x1, z1 = z0 + x2, z2 = z1 + y2;
        double w0 = x3 / 2.0, w1 = w0 * y1;
        // Лямбда в кадре больше 16 регистров: захват локалов из старших регистров.
        Function<String, String> f = s -> s + x0 + x4 + y2 + z2 + w1;
        Supplier<Integer> g = () -> x1 + x2 + x3;
        return f.apply("[") + g.get() + z0.length();
    }

    int bump(int by) {
        IntSupplier s = () -> counter += by;
        return s.getAsInt();
    }

    static List<String> refs(List<String> in) {
        Function<String, Integer> len = String::length;
        Function<String, String> up = String::toUpperCase;
        String prefix = "p-";
        Function<String, String> bound = prefix::concat;
        Supplier<List<String>> make = ArrayList::new;
        List<String> out = make.get();
        for (String s : in) out.add(bound.apply(up.apply(s)) + len.apply(s));
        return out;
    }

    static Function<Integer, Function<Integer, Integer>> curried(int base) {
        return a -> b -> base * 100 + a * 10 + b;
    }

    public static void main(String[] args) {
        System.out.println(noCapture().getAsInt());
        System.out.println(one("hi").get());
        System.out.println(two(1000L, 0.5).apply(6, 7));
        System.out.println(many(1, 2L, 3.5, "four", '5', true, 7.25f).get(8));
        System.out.println(bigFrame(1, 2, 3, 4, 5, 6L, "s"));
        Main m = new Main("abcd");
        System.out.println(m.counter + " " + m.bump(3) + " " + m.bump(4) + " " + m.name);
        System.out.println(refs(java.util.Arrays.asList("x", "yy", "zzz")));
        System.out.println(curried(4).apply(5).apply(6));
        int sum = 0;
        for (int i = 0; i < 5; i++) {
            final int k = i;
            IntSupplier s = () -> k * k;
            sum += s.getAsInt();
        }
        System.out.println(sum);
    }
}
