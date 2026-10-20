package m;

import java.util.function.IntSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

// Ссылки на открытые методы Object у захваченного объекта другого класса (commons-io:
// `observer::equals`): метод доступа в хозяине (для protected) здесь не нужен и сломал бы тип получателя.
public class Main {
    static final class Other {
        final int id;
        Other(int id) { this.id = id; }
    }

    public static void main(String[] args) {
        Other o = new Other(7);
        Predicate<Object> same = o::equals;
        Supplier<Integer> hash = o::hashCode;
        Supplier<Class<?>> cls = o::getClass;
        IntSupplier hc = new Object()::hashCode;
        System.out.println(same.test(o) + " " + same.test("x") + " " + (hash.get() == o.hashCode()) + " "
            + cls.get().getSimpleName() + " " + (hc.getAsInt() != 0 || true));
    }
}
