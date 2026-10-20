package fx.ds;
import java.lang.annotation.*;
import java.lang.reflect.*;
import java.util.*;
/**
 * Отражение метаданных Java 17+/21 после перевода: record (компоненты, обобщённые типы, аннотации),
 * sealed (разрешённые подклассы) и доступ к private внутри гнезда. В DEX — системные аннотации
 * dalvik.annotation.*, которые ART читает с Android 14; вывод обязан совпадать с HotSpot.
 */
public class Refl {
    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.RECORD_COMPONENT) @interface Vis { int v() default 7; }
    @Retention(RetentionPolicy.CLASS) @Target(ElementType.RECORD_COMPONENT) @interface Inv {}
    record P(@Vis(v = 3) @Inv List<String> xs, int n, @Vis Map<String, Integer> m) {}
    record E() {}
    sealed interface S permits A, B {}
    static final class A implements S {}
    static non-sealed class B implements S {}
    private int secret = 5;
    static class Nested { int peek(Refl r) { return r.secret; } }
    public static void main(String[] a) {
        System.out.println("P " + P.class.isRecord() + " E " + E.class.isRecord() + " Refl " + Refl.class.isRecord());
        for (RecordComponent c : P.class.getRecordComponents()) {
            Vis v = c.getAnnotation(Vis.class);
            System.out.println(c.getName() + " " + c.getType().getSimpleName() + " " + c.getGenericType() + " " + (v == null ? "-" : v.v()) + " " + c.getAnnotations().length);
        }
        System.out.println("E comps " + E.class.getRecordComponents().length);
        System.out.println("sealed " + S.class.isSealed() + " " + Arrays.toString(Arrays.stream(S.class.getPermittedSubclasses()).map(Class::getSimpleName).toArray()));
        System.out.println("nest access " + new Nested().peek(new Refl()));
    }
}
