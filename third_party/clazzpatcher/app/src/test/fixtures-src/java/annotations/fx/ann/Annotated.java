package fx.ann;

import java.io.IOException;
import java.util.List;

/**
 * Аннотации на классе/полях/методах/параметрах (часть параметров без аннотаций), вложенные и массивы,
 * системные аннотации: Signature, Throws, InnerClass, EnclosingMethod, MemberClasses.
 */
@All(z = false, b = 5, s = -2, c = '\n', i = 0x7f, j = -1L, f = -0.0f, d = 10.0, str = "é\ud800",
    cls = String[][].class, e = Elem.Y, inner = @Inner(value = 5, tags = {"a", "b"}),
    ia = {}, sa = {"s1", "", "\"q\""}, ca = {List.class}, ea = {Elem.Y}, na = {@Inner(9)})
public class Annotated<T extends Number> implements Comparable<Annotated<T>> {
    @All
    @Deprecated
    public List<String> field;

    @Inner(3)
    public static final int K = 3;

    @All(str = "ctor")
    public <U> Annotated(@Inner(1) int a, @Inner(2) @All String b, long c, @Inner(4) T t, U u) throws IOException, IllegalStateException {
    }

    @SafeVarargs
    public final void varargs(String... xs) {
    }

    public void params(@Inner(1) int a, int noAnn, @Inner(2) double d, long noAnn2, @All @Inner(3) Object o) {
    }

    public <E extends Exception> void generic(List<? super T> list) throws E {
    }

    class InnerClass {
        int x;
    }

    static class Nested {
        @Inner(11)
        void m() {
        }
    }

    Object local() {
        class Local {
            int y;
        }
        Runnable r = new Runnable() {
            public void run() {
            }
        };
        return new Local().y + r.hashCode();
    }

    @Override
    public int compareTo(Annotated<T> o) {
        return 0;
    }
}
