import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.IntBinaryOperator;
import java.util.function.Supplier;

interface Shape {
    double area();
    default String describe() { return name() + String.format("[%.2f]", area()); }
    String name();
    static Shape unit() { return new Square(1); }
}

abstract class Base implements Shape {
    private static int created;
    protected final int id;
    Base() { id = ++created; }
    public String name() { return getClass().getSimpleName() + "#" + id; }
    static int created() { return created; }
}

class Circle extends Base {
    final double r;
    Circle(double r) { super(); this.r = r; }
    public double area() { return Math.PI * r * r; }
}

class Square extends Base {
    final double s;
    Square(double s) { this.s = s; }
    public double area() { return s * s; }
    @Override public String name() { return "Sq" + super.name(); }
}

class Rect extends Square {
    final double h;
    Rect(double w, double h) { super(w); this.h = h; }
    @Override public double area() { return s * h; }
}

enum Op {
    ADD("+") { int apply(int a, int b) { return a + b; } },
    MUL("*") { int apply(int a, int b) { return a * b; } },
    POW("^") { int apply(int a, int b) { int r = 1; for (int i = 0; i < b; i++) r *= a; return r; } };

    final String sym;
    Op(String sym) { this.sym = sym; }
    abstract int apply(int a, int b);
}

public class Main {
    private int counter;
    private final String tag;
    private static final List<String> LOG = new ArrayList<>();

    static { LOG.add("clinit"); }
    { counter = 5; LOG.add("init"); }

    Main() { this("default"); LOG.add("ctor()"); }
    Main(String tag) { this.tag = tag; LOG.add("ctor(" + tag + ")"); }

    class Inner {
        int twice() { return counter * 2; }
    }

    static class Nested {
        static <T extends Comparable<T>> T max(List<T> list) {
            T best = null;
            for (T t : list) if (best == null || t.compareTo(best) > 0) best = t;
            return best;
        }
    }

    int local(int x) {
        class Adder { int add(int y) { return x + y + counter; } }
        return new Adder().add(3);
    }

    static int sum(int... values) { int s = 0; for (int v : values) s += v; return s; }

    static Shape biggest(Shape a, Shape b) { return a.area() >= b.area() ? a : b; }

    static Base mixed(int i) {
        Base b = i % 2 == 0 ? new Circle(i) : new Square(i);
        return b;
    }

    public static void main(String[] args) {
        Main m = new Main();
        Main.Inner in = m.new Inner();
        System.out.println("log " + LOG + " inner " + in.twice() + " local " + m.local(4) + " tag " + m.tag);
        List<Shape> shapes = new ArrayList<>();
        shapes.add(new Circle(1.5));
        shapes.add(new Square(2));
        shapes.add(new Rect(2, 3));
        shapes.add(Shape.unit());
        for (Shape s : shapes) System.out.println(s.describe());
        System.out.println("biggest " + biggest(shapes.get(0), shapes.get(2)).name());
        double total = 0;
        for (int i = 0; i < 6; i++) total += mixed(i).area() + mixed(i).id;
        System.out.println("total " + String.format("%.3f", total) + " created " + Base.created());
        for (Op op : Op.values()) System.out.println(op + " " + op.sym + " " + op.apply(3, 4));
        System.out.println("varargs " + sum() + " " + sum(1) + " " + sum(1, 2, 3));
        List<String> words = new ArrayList<>();
        Collections.addAll(words, "pear", "apple", "fig", "banana", "kiwi");
        Collections.sort(words, new Comparator<String>() {
            @Override public int compare(String a, String b) {
                int d = a.length() - b.length();
                return d != 0 ? d : a.compareTo(b);
            }
        });
        System.out.println("sorted " + words + " max " + Nested.max(words));
        Map<String, Integer> counts = new TreeMap<>();
        for (String w : "a b a c b a d".split(" ")) counts.merge(w, 1, Integer::sum);
        System.out.println("counts " + counts);
        Function<Integer, String> f = x -> "v" + (x * x);
        Supplier<List<Integer>> sup = ArrayList::new;
        List<Integer> nums = sup.get();
        for (int i = 0; i < 5; i++) nums.add(i * 3);
        IntBinaryOperator op = (a, b) -> a * 10 + b;
        int folded = 0;
        for (int n : nums) folded = op.applyAsInt(folded % 1000, n);
        System.out.println("lambda " + f.apply(7) + " " + nums + " " + folded);
        Runnable r = () -> LOG.add("run:" + m.counter);
        r.run();
        System.out.println("last " + LOG.get(LOG.size() - 1));
        Map<Integer, String> hm = new HashMap<>();
        for (int i = 0; i < 10; i++) hm.put(i, Integer.toHexString(i * 37));
        System.out.println("hm " + hm);
        Object o = m;
        System.out.println("instanceof " + (o instanceof Main) + " " + (o instanceof Runnable) + " " + (shapes.get(2) instanceof Square));
        StringBuilder sb = new StringBuilder();
        for (char c = 'a'; c < 'f'; c++) sb.append(c).append((int) c % 3);
        System.out.println("sb " + sb.reverse());
        Integer boxed = 127, other = 127;
        Long big = 1L << 40;
        System.out.println("boxing " + (boxed == other) + " " + boxed.equals(other) + " " + (big + boxed) + " " + Character.valueOf('z'));
    }
}
