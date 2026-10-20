package com.tim.dsmali.model

import com.tim.dsmali.data.SmaliDebugKind

data class SmaliDebug(
    val kind: SmaliDebugKind,
    val raw: String
) : SmaliBodyElement()
