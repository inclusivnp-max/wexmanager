package fx.ann;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** Вложенная аннотация (subannotation). */
@Retention(RetentionPolicy.RUNTIME)
public @interface Inner {
    int value();
    String[] tags() default {"t"};
}
