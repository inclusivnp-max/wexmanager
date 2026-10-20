import java.util.function.BiFunction;
import java.util.function.IntUnaryOperator;
import java.util.function.Supplier;

// Компилируется d8 с --no-desugaring: лямбды и ссылки на методы остаются invoke-custom
// (LambdaMetafactory), что проверяет перевод invoke-custom → invokedynamic и method handles.
public class Main {
    interface Greeter { String greet(String who); }

    private int base = 40;

    static String hello(String who) { return "hi " + who; }

    int plus(int x) { return base + x; }

    public static void main(String[] args) {
        Greeter g = Main::hello;
        Greeter g2 = w -> w.toUpperCase() + "!";
        Main m = new Main();
        IntUnaryOperator op = m::plus;
        BiFunction<Integer, Integer, Integer> bf = (a, b) -> a * b + m.base;
        Supplier<Main> ctor = Main::new;
        Runnable r = () -> System.out.println("run " + ctor.get().base);
        System.out.println(g.greet("bob") + " " + g2.greet("amy") + " " + op.applyAsInt(2) + " " + bf.apply(6, 7));
        r.run();
    }
}
