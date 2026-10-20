package com.tim.casm

import com.tim.casm.hierarchy.ClassHierarchy
import com.tim.casm.hierarchy.CommonSuperClass
import com.tim.casm.hierarchy.MapHierarchy
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Общий предок для фреймов, когда иерархия неполная: классы приложения известны (из DEX), а их
 * предок — нет (класс android.jar без `--cp`). Суперкласс всегда класс, поэтому неизвестный
 * предок, до которого доходят цепочки суперклассов, — верный общий тип (d2c Velvet без `--cp`:
 * иначе фрейм давал Object, и вызов метода предка не проходил верификатор HotSpot).
 */
class CommonSuperClassTest {
    private val h = MapHierarchy()
        .add("app/A", "android/Cb", false)
        .add("app/B", "android/Cb", false)
        .add("app/C", "app/A", false)
        .add("app/I", "java/lang/Object", true)

    private val lub = CommonSuperClass(h)

    @Test
    fun unknownAncestorOfKnownClasses() {
        assertEquals("android/Cb", lub.of("app/A", "app/B"))
        assertEquals("android/Cb", lub.of("app/C", "app/B"))
        assertEquals("android/Cb", lub.of("app/A", "android/Cb"))
        assertEquals("android/Cb", lub.of("android/Cb", "app/C"))
        assertEquals("app/A", lub.of("app/C", "app/A"))
    }

    @Test
    fun unknownOrInterfaceOtherwiseObject() {
        // Два неизвестных класса: ничего не известно о предках.
        assertEquals(ClassHierarchy.OBJECT, lub.of("android/Cb", "android/Other"))
        // Неизвестный класс, который не встречается в цепочке другого: может быть и интерфейсом.
        assertEquals(ClassHierarchy.OBJECT, lub.of("app/A", "android/Other"))
        assertEquals(ClassHierarchy.OBJECT, lub.of("android/Other", "app/B"))
        // Интерфейс для верификатора — Object.
        assertEquals(ClassHierarchy.OBJECT, lub.of("app/I", "app/A"))
    }
}
