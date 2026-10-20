package fx.ds;

import java.util.function.Supplier;

/**
 * Семантика JDK, которую рассахаривание c2d сохраняет, а d8 — нет (сравнивается только с HotSpot):
 * равенство компонент record float/double через compare (NaN равен NaN, -0.0 не равен 0.0) и
 * один объект у лямбды без захвата при каждом вычислении одного и того же места; record без компонент
 * (атрибут Record пуст, но есть).
 */
public class DesugarJdk {
    record F(float f, double d) {}
    record None() {}

    static Supplier<String> same() { return () -> "same"; }

    public static void main(String[] args) {
        F nan = new F(Float.NaN, Double.NaN);
        System.out.println(nan + " " + nan.equals(new F(Float.NaN, Double.NaN)) + " " + (nan.hashCode() == new F(Float.NaN, Double.NaN).hashCode()));
        System.out.println(new F(-0.0f, 0.0).equals(new F(0.0f, 0.0)) + " " + new F(0.0f, -0.0).equals(new F(0.0f, 0.0)) + " " + new F(-0.0f, -0.0).hashCode());
        System.out.println("singleton " + (same() == same()));
        System.out.println("records " + None.class.isRecord() + " " + F.class.isRecord() + " " + None.class.getRecordComponents().length + " " + new None());
    }
}
