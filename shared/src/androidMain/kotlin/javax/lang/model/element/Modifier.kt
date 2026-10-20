package javax.lang.model.element

/**
 * Модификаторы Java — копия javax.lang.model.element.Modifier из JDK. В Android этого пакета нет, а декомпилятор
 * Procyon печатает через него модификаторы классов и методов. Порядок и вид (toString — строчными) — как в JDK.
 */
enum class Modifier {
    PUBLIC, PROTECTED, PRIVATE, ABSTRACT, DEFAULT, STATIC, SEALED, NON_SEALED,
    FINAL, TRANSIENT, VOLATILE, SYNCHRONIZED, NATIVE, STRICTFP;

    override fun toString(): String = if (this == NON_SEALED) "non-sealed" else name.lowercase()
}
