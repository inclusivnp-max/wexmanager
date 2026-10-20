package com.tim.dex.model

/**
 * Определение класса.
 *
 * Все `*Idx` — индексы в соответствующих списках DexFile. `-1` = нет.
 */
data class ClassDefItem(
    val classIdx: Int,
    val accessFlags: Int,
    val superclassIdx: Int,                 // -1 = нет (java.lang.Object)
    val interfacesTypeListIdx: Int,         // -1 = нет
    val sourceFileIdx: Int,                 // -1 = нет
    val annotationsDirectoryIdx: Int,       // -1 = нет
    val classDataIdx: Int,                  // -1 = нет
    val staticValuesIdx: Int                // -1 = нет
)