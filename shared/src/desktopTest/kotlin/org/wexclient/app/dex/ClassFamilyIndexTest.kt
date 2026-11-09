package org.wexclient.app.dex

import kotlin.test.*

class ClassFamilyIndexTest {
    private val dollar = '$'
    private fun n(tail: String = "") = "p.MainActivity$tail"
    @Test fun `all nested levels share the real outer class`() {
        val names = listOf(n(), n("${dollar}Inner"), n("${dollar}Inner${dollar}Nested"), n("${dollar}Companion"))
        val index = ClassFamilyIndex(names, { it })
        assertEquals(1, index.families.size)
        assertEquals(n(), index.familyOf(names.last()).root)
        assertEquals(names.toSet(), index.families.single().members.toSet())
        assertTrue(index.isWithin(names[2], names[1]))
        assertFalse(index.isWithin(names[1], names[2]))
    }
    @Test fun `numeric anonymous classes use natural order`() {
        val index = ClassFamilyIndex(listOf(n("${dollar}10"), n("${dollar}2"), n(), n("${dollar}1")), { it })
        assertEquals(listOf(n(), n("${dollar}1"), n("${dollar}2"), n("${dollar}10")), index.families.single().members)
    }
    @Test fun `similar class names do not get merged`() {
        val index = ClassFamilyIndex(listOf(n(), n("Extra"), n("${dollar}Inner")), { it })
        assertEquals(2, index.families.size)
        assertTrue(index.isRoot(n("Extra")))
    }
    @Test fun `same simple name in different packages stays separate`() {
        val index = ClassFamilyIndex(listOf(n(), "q.MainActivity", n("${dollar}Inner")), { it })
        assertEquals(2, index.families.size)
        assertFalse(index.isWithin(n("${dollar}Inner"), "q.MainActivity"))
    }
    @Test fun `missing outer class does not hide orphan parts`() {
        val index = ClassFamilyIndex(listOf(n("${dollar}Inner"), n("${dollar}Inner${dollar}Nested")), { it })
        assertEquals(n("${dollar}Inner"), index.families.single().root)
    }
    @Test fun `explicit nesting works after renaming`() {
        val index = ClassFamilyIndex(listOf("a", n()), { it }) { if (it == "a") n() else null }
        assertEquals(n(), index.familyOf("a").root)
    }
    @Test fun `explicit missing parent wins over misleading prefix`() {
        val inner = n("${dollar}Inner")
        val index = ClassFamilyIndex(listOf(n(), inner), { it }) { if (it == inner) "missing.Owner" else null }
        assertEquals(2, index.families.size)
    }
    @Test fun `cyclic metadata keeps every class visible`() {
        val index = ClassFamilyIndex(listOf("A", "B", "C"), { it }) { if (it == "A") "B" else if (it == "B") "A" else null }
        assertEquals(setOf("A", "B", "C"), index.families.map { it.root }.toSet())
        assertFalse(index.isWithin("A", "B"))
    }
    @Test fun `synthetic lambda suffixes belong to the existing owner`() {
        val synthetic = n("${dollar}${dollar}ExternalSyntheticLambda0")
        assertEquals(n(), ClassFamilyIndex(listOf(n(), synthetic), { it }).familyOf(synthetic).root)
    }
    @Test fun `unknown names are not owned by any family`() {
        assertFalse(ClassFamilyIndex(listOf(n()), { it }).isWithin(n("${dollar}Absent"), n()))
    }
}
