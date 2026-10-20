package fx.ds;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.*;

/**
 * Рассахаривание invokedynamic (c2d → ART): все виды реализаций лямбд и преобразований типов
 * LambdaMetafactory, мосты и маркеры altMetafactory, конкатенация строк всех типов, методы record,
 * pattern switch (типы, константы, enum, guard, null). Вывод детерминирован (без identity hash) и
 * одинаков у JDK и d8 в ART; семантика JDK, от которой d8 отступает, — в {@link DesugarJdk}.
 */
public class Desugar {

    // ---- Лямбды ----

    interface A { Object get(); }
    interface B extends A { String get(); }              // лямбда B требует моста Object get()
    interface G<T> { int apply(T t); }                      // стёртый (Object)I, инстанцированный (Integer)I
    interface P<T> { void take(T t); }                      // (Object)V → реализация (I)V: приведение + распаковка
    interface Wide { long get(); }                          // int → long расширение результата
    interface Fn3 { String f(int a, long b, double c); }
    interface Def { default String hi() { return "def"; } String name(); }

    private int base = 40;
    private static String secret(String s) { return "secret:" + s; }
    private String inst(int x) { return "inst:" + (base + x); }
    static void takesInt(int v) { out.add("takesInt " + v); }
    static final List<String> out = new ArrayList<>();

    class Inner {
        Function<String, String> viaOuter() { return Desugar::secret; }     // private метод соседа по гнезду
        Supplier<String> outerField() { return () -> "base=" + base; }
    }

    static class Parent { String who() { return "parent"; } }
    static class Child extends Parent {
        @Override String who() { return "child"; }
        Supplier<String> sup() { return super::who; }                   // super:: — мост javac
    }

    void lambdas() {
        Supplier<String> s = () -> "plain";
        Runnable r = () -> out.add("run");
        r.run();
        Function<String, Integer> len = String::length;                // невязанный получатель
        String hello = "hello";
        Supplier<String> bound = hello::toUpperCase;                   // связанный получатель
        Function<String, String> stat = Desugar::secret;               // private static
        IntFunction<String> priv = this::inst;                          // private экземпляра
        BiFunction<String, Integer, String> sub = String::substring;
        Supplier<List<String>> ctor = ArrayList::new;
        Function<String, StringBuilder> ctorArg = StringBuilder::new;
        IntFunction<int[]> arr = int[]::new;
        ToLongFunction<String> widen = String::length;                  // int → long
        Function<Integer, Integer> abs = Math::abs;                     // распаковка/упаковка
        DoubleUnaryOperator dabs = Math::abs;
        G<Integer> g = Integer::intValue;
        P<Integer> p = Desugar::takesInt;
        Wide w = () -> 7;
        B b = () -> "bridge";
        A a = b;
        Comparator<String> ci = String::compareToIgnoreCase;
        Consumer<List<String>> adder = l -> l.add("x");
        Predicate<String> empty = String::isEmpty;
        Def def = () -> "named";
        Fn3 f3 = (x, y, z) -> x + ":" + y + ":" + z;
        UnaryOperator<String> twice = t -> t + t;
        BinaryOperator<Integer> max = BinaryOperator.maxBy(Comparator.naturalOrder());

        int ci1 = 3; long cl = 4L; double cd = 2.5; float cf = 1.5f; char cc = 'q'; boolean cz = true;
        byte cb = (byte) -2; short cs = (short) 300; Object co = "obj"; int[] carr = {1, 2};
        Supplier<String> captureAll = () -> ci1 + "|" + cl + "|" + cd + "|" + cf + "|" + cc + "|" + cz + "|" + cb + "|" + cs + "|" + co + "|" + carr.length;

        List<String> list = ctor.get();
        adder.accept(list);
        Consumer<String> discard = list::add;                           // boolean add → void
        discard.accept("y");

        out.add(s.get() + " " + len.apply("four") + " " + bound.get() + " " + stat.apply("s") + " " + priv.apply(2));
        out.add(sub.apply("abcdef", 2) + " " + list + " " + ctorArg.apply("sb").append('!') + " " + arr.apply(3).length);
        out.add(widen.applyAsLong("12345") + " " + abs.apply(-5) + " " + dabs.applyAsDouble(-2.5) + " " + g.apply(9));
        p.take(11);
        out.add(w.get() + " " + b.get() + " " + a.get() + " " + ci.compare("A", "a") + " " + empty.test("") + " " + empty.test("z"));
        out.add(def.hi() + " " + def.name() + " " + f3.f(1, 2L, 3.5) + " " + twice.apply("ab") + " " + max.apply(3, 8));
        out.add(captureAll.get());

        Runnable ser = (Runnable & Serializable) () -> out.add("ser");
        ser.run();
        out.add("serializable " + (ser instanceof Serializable));
        Runnable marked = (Runnable & Cloneable) () -> out.add("marked");
        marked.run();
        out.add("cloneable " + (marked instanceof Cloneable));

        Inner in = new Inner();
        out.add(in.viaOuter().apply("n") + " " + in.outerField().get() + " " + new Child().sup().get());
    }

    // ---- Конкатенация ----

    static class Custom { @Override public String toString() { return "custom!"; } }

    static String concat() {
        byte b = -1; short sh = 2; char c = 'Ж'; int i = Integer.MIN_VALUE; long l = Long.MAX_VALUE;
        float f = Float.NaN; float nz = -0.0f; double d = Double.NEGATIVE_INFINITY; double e = 1e-300;
        boolean z = false; String sn = null; Object on = null; CharSequence cs = new StringBuilder("cs");
        Custom custom = new Custom();
        String tag = "a\u0001b\u0002c";                                  // служебные символы рецепта в константе
        return b + "," + sh + "," + c + "," + i + "," + l + "," + f + "," + nz + "," + d + "," + e + "," + z + "," + sn + "," + on
            + "," + cs + "," + custom + "," + tag + "|" + tag.length() + "|" + 'x' + 7 + 7L + 1.0f + 2.0 + true;
    }

    static String manyArgs() {
        int a0 = 0, a1 = 1, a2 = 2, a3 = 3, a4 = 4, a5 = 5, a6 = 6, a7 = 7, a8 = 8, a9 = 9;
        String s = "";
        s = s + a0 + a1 + a2 + a3 + a4 + a5 + a6 + a7 + a8 + a9 + a0 + a1 + a2 + a3 + a4 + a5 + a6 + a7 + a8 + a9
            + a0 + a1 + a2 + a3 + a4 + a5 + a6 + a7 + a8 + a9 + a0 + a1 + a2 + a3 + a4 + a5 + a6 + a7 + a8 + a9;
        return s;
    }

    // ---- Records ----

    record All(boolean z, byte b, short s, char c, int i, long l, float f, double d, String str, Object o) {}
    record Empty() {}
    record Nested(All all, Empty empty) {}
    record WithArray(int[] data) {}

    static void records() {
        All x = new All(true, (byte) 1, (short) 2, 'c', 3, 4L, 1.5f, 2.25, "s", null);
        All y = new All(true, (byte) 1, (short) 2, 'c', 3, 4L, 1.5f, 2.25, "s", null);
        All z = new All(true, (byte) 1, (short) 2, 'c', 3, 4L, 1.5f, 2.5, "s", null);
        out.add(x.toString());
        out.add(x.equals(y) + " " + x.equals(z) + " " + (x.hashCode() == y.hashCode()) + " " + x.hashCode() + " " + z.hashCode());
        out.add(new Empty() + " " + new Empty().hashCode() + " " + new Empty().equals(new Empty()) + " " + new Empty().equals("x"));
        out.add(new Nested(x, new Empty()).toString());
        int[] data = {1};
        out.add(new WithArray(data).equals(new WithArray(data)) + " " + new WithArray(data).equals(new WithArray(new int[]{1})));
        record Local(String v) {}
        out.add(new Local("loc") + " " + new Local("loc").equals(new Local("loc")) + " " + new Local(null).hashCode());
    }

    // ---- Pattern switch ----

    sealed interface Shape permits Circle, Square, Kind {}
    record Circle(double r) implements Shape {}
    record Square(int side) implements Shape {}
    enum Kind implements Shape { DOT, LINE { }, BLOB }

    static String shape(Shape s) {
        return switch (s) {
            case null -> "null";
            case Kind.DOT -> "dot";
            case Kind k when k == Kind.BLOB -> "blob";
            case Kind k -> "kind " + k.name();
            case Circle c when c.r() > 10 -> "big circle";
            case Circle(double r) -> "circle " + r;
            case Square(int side) when side == 0 -> "empty square";
            case Square q -> "square " + q.side();
        };
    }

    static String object(Object o) {
        return switch (o) {
            case null -> "null";
            case Integer i when i > 100 -> "big int";
            case Integer i -> "int " + i;
            case String str when str.isEmpty() -> "empty";
            case String str -> "str " + str;
            case int[] ia -> "ints " + ia.length;
            case Kind k -> "enum " + k;
            default -> "other " + o.getClass().getSimpleName();
        };
    }

    static String boxed(Integer v) {
        return switch (v) {
            case 1 -> "one";
            case 2 -> "two";
            case Integer i when i < 0 -> "negative";
            case Integer i -> "n" + i;
        };
    }

    static String chars(Character c) {
        return switch (c) {
            case 'a' -> "A";
            case Character x when Character.isDigit(x) -> "digit";
            case Character x -> "ch " + x;
        };
    }

    static String kinds(Kind k) {
        return switch (k) {
            case DOT -> "D";
            case Kind x when x.ordinal() == 1 -> "L1";
            case BLOB -> "B";
            default -> "?";
        };
    }

    static String strings(String s) {
        return switch (s) {
            case "a" -> "is a";
            case String x when x.length() > 3 -> "long";
            case String x -> "s:" + x;
        };
    }

    public static void main(String[] args) {
        new Desugar().lambdas();
        out.add(concat());
        out.add(manyArgs());
        records();
        for (Shape s : new Shape[]{null, Kind.DOT, Kind.LINE, Kind.BLOB, new Circle(11), new Circle(2), new Square(0), new Square(5)}) out.add(shape(s));
        for (Object o : new Object[]{null, 500, 5, "", "q", new int[3], Kind.LINE, 2.5}) out.add(object(o));
        for (Integer v : new Integer[]{1, 2, -7, 9}) out.add(boxed(v));
        for (Character c : new Character[]{'a', '7', 'z'}) out.add(chars(c));
        for (Kind k : Kind.values()) out.add(kinds(k));
        for (String s : new String[]{"a", "abcd", "b"}) out.add(strings(s));
        try {
            boxed(null);
        } catch (NullPointerException e) {
            out.add("npe");
        }
        for (String line : out) System.out.println(line);
    }
}
