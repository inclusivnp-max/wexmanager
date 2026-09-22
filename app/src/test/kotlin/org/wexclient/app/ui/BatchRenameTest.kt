package org.wexclient.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class BatchRenameTest {

    @Test
    fun testFindAndReplace() {
        val rule = BatchRenameRule(
            findText = "photo",
            replaceText = "image"
        )
        val result = BatchRenameEngine.computeNewName("photo_01.jpg", false, 0, rule)
        assertEquals("image_01.jpg", result)
    }

    @Test
    fun testRegexReplacement() {
        val rule = BatchRenameRule(
            findText = "\\d+",
            replaceText = "NUM",
            isRegex = true
        )
        val result = BatchRenameEngine.computeNewName("track_123_final.mp3", false, 0, rule)
        assertEquals("track_NUM_final.mp3", result)
    }

    @Test
    fun testPrefixAndSuffix() {
        val rule = BatchRenameRule(
            prefix = "backup_",
            suffix = "_v2"
        )
        val result = BatchRenameEngine.computeNewName("document.pdf", false, 0, rule)
        assertEquals("backup_document_v2.pdf", result)
    }

    @Test
    fun testInvalidDigitsDoNotCrash() {
        // "0" и мусор в поле «Разрядов» раньше давали формат "%00d" и падение приложения
        for (digits in listOf(0, -3, 1000)) {
            val rule = BatchRenameRule(enableNumbering = true, numberStart = 7, numberDigits = digits, numberReplaceAll = true)
            val result = BatchRenameEngine.computeNewName("a.txt", false, 0, rule)
            assertEquals(if (digits == 1000) "000000000007.txt" else "7.txt", result)
        }
    }

    @Test
    fun testNumbering() {
        val rule = BatchRenameRule(
            enableNumbering = true,
            numberStart = 10,
            numberDigits = 3,
            numberReplaceAll = false
        )
        val result1 = BatchRenameEngine.computeNewName("frame.png", false, 0, rule)
        val result2 = BatchRenameEngine.computeNewName("frame.png", false, 1, rule)
        assertEquals("frame_010.png", result1)
        assertEquals("frame_011.png", result2)
    }

    @Test
    fun testNumberingReplaceAll() {
        val rule = BatchRenameRule(
            enableNumbering = true,
            numberStart = 1,
            numberDigits = 2,
            numberReplaceAll = true
        )
        val result = BatchRenameEngine.computeNewName("random_stuff.jpg", false, 4, rule)
        assertEquals("05.jpg", result)
    }

    @Test
    fun testExtensionChange() {
        val rule = BatchRenameRule(
            newExtension = "jpeg"
        )
        val result = BatchRenameEngine.computeNewName("picture.png", false, 0, rule)
        assertEquals("picture.jpeg", result)
    }

    @Test
    fun testCaseTransforms() {
        val ruleLower = BatchRenameRule(caseOption = CaseTransform.LOWER)
        assertEquals("hello world.txt", BatchRenameEngine.computeNewName("HELLO WORLD.txt", false, 0, ruleLower))

        val ruleUpper = BatchRenameRule(caseOption = CaseTransform.UPPER)
        assertEquals("HELLO WORLD.txt", BatchRenameEngine.computeNewName("hello world.txt", false, 0, ruleUpper))

        val ruleTitle = BatchRenameRule(caseOption = CaseTransform.TITLE)
        assertEquals("Hello World.txt", BatchRenameEngine.computeNewName("hello_world.txt", false, 0, ruleTitle))
    }

    @Test
    fun testDirectoryHandling() {
        val rule = BatchRenameRule(
            prefix = "dir_",
            newExtension = "bak" // should not affect directory
        )
        val result = BatchRenameEngine.computeNewName("my_folder", true, 0, rule)
        assertEquals("dir_my_folder", result)
    }
}
