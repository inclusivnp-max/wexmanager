import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

// Метаданные после DEX → class проверяются рефлексией (в тестовой программе, не в конвертере):
// InnerClasses/EnclosingMethod, Signature, Exceptions, аннотации (видимые, по умолчанию, у параметров).
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER})
@interface Info {
    String value() default "def";
    int level() default 3;
    Class<?> type() default Object.class;
    ElementType kind() default ElementType.TYPE;
    String[] tags() default {"a", "b"};
}

@Retention(RetentionPolicy.CLASS)
@interface Hidden {}

@Info(value = "main", level = 7, tags = {})
public class Main {
    @Info(kind = ElementType.FIELD) public static Map<String, List<Integer>> table;
    @Hidden int hidden;

    static class Nested<K extends Comparable<K>, V> {
        <T extends Number> T pick(List<? super T> in, T fallback) throws java.io.IOException, IllegalStateException { return fallback; }
    }

    class Inner {
        class Deeper {}
    }

    interface Callback { String call(); }

    @Info("method")
    static String annotated(@Info(level = 1) String p, int q) { return p + q; }

    static Callback make() {
        return new Callback() {
            public String call() {
                class LocalInAnon {}
                return getClass().isAnonymousClass() + ":" + new LocalInAnon().getClass().getSimpleName();
            }
        };
    }

    public static void main(String[] args) throws Exception {
        class Local {}
        Object anon = new Object() {};
        System.out.println(Nested.class.getSimpleName() + " " + Nested.class.getDeclaringClass().getSimpleName() + " " + Nested.class.getModifiers());
        System.out.println(Inner.Deeper.class.getName() + " " + Inner.Deeper.class.getDeclaringClass().getSimpleName() + " " + Inner.class.isMemberClass());
        System.out.println(Local.class.getSimpleName() + " " + Local.class.isLocalClass() + " " + Local.class.getEnclosingMethod().getName());
        System.out.println(anon.getClass().isAnonymousClass() + " " + anon.getClass().getEnclosingMethod().getName() + " " + make().call());
        System.out.println(Arrays.toString(Main.class.getDeclaredClasses().length > 0 ? new int[]{Main.class.getDeclaredClasses().length} : new int[0]));
        Method pick = Nested.class.getDeclaredMethod("pick", List.class, Number.class);
        System.out.println(pick.toGenericString());
        System.out.println(Arrays.toString(pick.getExceptionTypes()));
        System.out.println(Nested.class.toGenericString() + " " + Arrays.toString(Nested.class.getTypeParameters()));
        System.out.println(Main.class.getField("table").getGenericType());
        Info ci = Main.class.getAnnotation(Info.class);
        System.out.println(ci.value() + " " + ci.level() + " " + ci.type().getSimpleName() + " " + ci.kind() + " " + Arrays.toString(ci.tags()));
        Info fi = Main.class.getField("table").getAnnotation(Info.class);
        System.out.println(fi.value() + " " + fi.kind() + " " + Arrays.toString(fi.tags()));
        Method am = Main.class.getDeclaredMethod("annotated", String.class, int.class);
        Info mi = am.getAnnotation(Info.class);
        Info pi = (Info) am.getParameterAnnotations()[0][0];
        System.out.println(mi.value() + " " + pi.level() + " " + am.getParameterAnnotations()[1].length);
        System.out.println(Main.class.getDeclaredField("hidden").getAnnotations().length + " " + Info.class.getDeclaredMethod("level").getDefaultValue());
    }
}
