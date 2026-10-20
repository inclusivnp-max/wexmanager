package fx.modern;

import java.util.List;
import java.util.function.IntUnaryOperator;

/**
 * Современный байткод (javac --release 21) для проверки round-trip .class: record, sealed, nest,
 * invokedynamic (лямбды, конкатенация строк), switch с паттернами, StackMapTable, MethodParameters.
 */
public class Modern {
    public sealed interface Shape permits Circle, Square {}

    public record Circle(double r) implements Shape {}

    public record Square(double side) implements Shape {
        public Square {
            if (side < 0) throw new IllegalArgumentException("side " + side);
        }
    }

    private int secret = 42;

    class Peer {
        int peek() {
            return secret;
        }
    }

    static double area(Shape s) {
        return switch (s) {
            case Circle c -> Math.PI * c.r() * c.r();
            case Square q -> q.side() * q.side();
        };
    }

    static String describe(Object o, int n) {
        IntUnaryOperator twice = x -> x * 2;
        String text = """
            text block "with quotes"
            and a second line\tend
            """;
        if (o instanceof String str && !str.isEmpty()) {
            return "string " + str + " " + twice.applyAsInt(n) + text;
        }
        return List.of(o).toString() + n;
    }
}
