package fx.ann;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Аннотация с элементами всех видов и значениями по умолчанию (AnnotationDefault). */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.CONSTRUCTOR})
public @interface All {
    boolean z() default true;
    byte b() default -1;
    short s() default 300;
    char c() default 'é';
    int i() default Integer.MIN_VALUE;
    long j() default Long.MIN_VALUE;
    float f() default 10.0f;
    double d() default Double.NaN;
    String str() default "a\"b\\c\n\u0000";
    Class<?> cls() default Object.class;
    Elem e() default Elem.X;
    Inner inner() default @Inner(7);
    int[] ia() default {1, 2, 3};
    String[] sa() default {};
    Class<?>[] ca() default {int.class, String[].class, void.class};
    Elem[] ea() default {Elem.X, Elem.Y};
    Inner[] na() default {@Inner(1), @Inner(value = 2, tags = {"x", "y"})};
    boolean[] za() default {true, false};
    byte[] ba() default {-128, 127};
    short[] sha() default {-32768};
    char[] cha() default {'a', '\''};
    long[] ja() default {Long.MAX_VALUE, 0};
    float[] fa() default {-0.0f, Float.NEGATIVE_INFINITY};
    double[] da() default {0.1, -0.0};
}
