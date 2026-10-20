package com.tim.dex.data

/**
 * Полная таблица опкодов Dalvik/ART DEX.
 *
 * @property value        Числовое значение опкода (0x00..0xFF).
 * @property mnemonic     Мнемоника (для отладки/дампов).
 * @property format       Формат инструкции (см. [InsnFormat]).
 * @property sizeUnits    Размер инструкции в 16-битных словах (units), БЕЗ пейлоадов.
 * @property indexPool    Какой пул индексирует инструкция (или NONE).
 * @property indexOffset  Смещение индексного поля внутри инструкции в units (0, 1, 2...).
 * @property version      Минимальная версия DEX, где опкод валиден (обычно 035).
 */
enum class Opcode(
    val value: Int,
    val mnemonic: String,
    val format: InsnFormat,
    val sizeUnits: Int,
    val indexPool: IndexPool = IndexPool.NONE,
    val indexOffset: Int = 0,
    val version: Int = 35
) {
    NOP                 (0x00, "nop",                    InsnFormat.FORMAT_10x, 1),
    MOVE                (0x01, "move",                   InsnFormat.FORMAT_12x, 1),
    MOVE_FROM16         (0x02, "move/from16",            InsnFormat.FORMAT_22x, 2),
    MOVE_16             (0x03, "move/16",                InsnFormat.FORMAT_32x, 3),
    MOVE_WIDE           (0x04, "move-wide",              InsnFormat.FORMAT_12x, 1),
    MOVE_WIDE_FROM16    (0x05, "move-wide/from16",       InsnFormat.FORMAT_22x, 2),
    MOVE_WIDE_16        (0x06, "move-wide/16",           InsnFormat.FORMAT_32x, 3),
    MOVE_OBJECT         (0x07, "move-object",            InsnFormat.FORMAT_12x, 1),
    MOVE_OBJECT_FROM16  (0x08, "move-object/from16",     InsnFormat.FORMAT_22x, 2),
    MOVE_OBJECT_16      (0x09, "move-object/16",         InsnFormat.FORMAT_32x, 3),
    MOVE_RESULT         (0x0a, "move-result",            InsnFormat.FORMAT_11x, 1),
    MOVE_RESULT_WIDE    (0x0b, "move-result-wide",       InsnFormat.FORMAT_11x, 1),
    MOVE_RESULT_OBJECT  (0x0c, "move-result-object",     InsnFormat.FORMAT_11x, 1),
    MOVE_EXCEPTION      (0x0d, "move-exception",         InsnFormat.FORMAT_11x, 1),
    RETURN_VOID         (0x0e, "return-void",            InsnFormat.FORMAT_10x, 1),
    RETURN              (0x0f, "return",                 InsnFormat.FORMAT_11x, 1),
    RETURN_WIDE         (0x10, "return-wide",            InsnFormat.FORMAT_11x, 1),
    RETURN_OBJECT       (0x11, "return-object",          InsnFormat.FORMAT_11x, 1),
    CONST_4             (0x12, "const/4",                InsnFormat.FORMAT_11n, 1),
    CONST_16            (0x13, "const/16",               InsnFormat.FORMAT_21s, 2),
    CONST               (0x14, "const",                  InsnFormat.FORMAT_31i, 3),
    CONST_HIGH16        (0x15, "const/high16",           InsnFormat.FORMAT_21h, 2),
    CONST_WIDE_16       (0x16, "const-wide/16",          InsnFormat.FORMAT_21s, 2),
    CONST_WIDE_32       (0x17, "const-wide/32",          InsnFormat.FORMAT_31i, 3),
    CONST_WIDE          (0x18, "const-wide",             InsnFormat.FORMAT_51l, 5),
    CONST_WIDE_HIGH16   (0x19, "const-wide/high16",      InsnFormat.FORMAT_21h, 2),
    CONST_STRING        (0x1a, "const-string",           InsnFormat.FORMAT_21c, 2, IndexPool.STRING, 1),
    CONST_STRING_JUMBO  (0x1b, "const-string/jumbo",     InsnFormat.FORMAT_31c, 3, IndexPool.STRING, 1),
    CONST_CLASS         (0x1c, "const-class",            InsnFormat.FORMAT_21c, 2, IndexPool.TYPE,   1),
    MONITOR_ENTER       (0x1d, "monitor-enter",          InsnFormat.FORMAT_11x, 1),
    MONITOR_EXIT        (0x1e, "monitor-exit",           InsnFormat.FORMAT_11x, 1),
    CHECK_CAST          (0x1f, "check-cast",             InsnFormat.FORMAT_21c, 2, IndexPool.TYPE,   1),
    INSTANCE_OF         (0x20, "instance-of",            InsnFormat.FORMAT_22c, 2, IndexPool.TYPE,   1),
    ARRAY_LENGTH        (0x21, "array-length",           InsnFormat.FORMAT_12x, 1),
    NEW_INSTANCE        (0x22, "new-instance",           InsnFormat.FORMAT_21c, 2, IndexPool.TYPE,   1),
    NEW_ARRAY           (0x23, "new-array",              InsnFormat.FORMAT_22c, 2, IndexPool.TYPE,   1),
    FILLED_NEW_ARRAY    (0x24, "filled-new-array",       InsnFormat.FORMAT_35c, 3, IndexPool.TYPE,   1),
    FILLED_NEW_ARRAY_RANGE(0x25, "filled-new-array/range",InsnFormat.FORMAT_3rc, 3, IndexPool.TYPE,  1),
    FILL_ARRAY_DATA     (0x26, "fill-array-data",        InsnFormat.FORMAT_31t, 3),
    THROW               (0x27, "throw",                  InsnFormat.FORMAT_11x, 1),
    GOTO                (0x28, "goto",                   InsnFormat.FORMAT_10t, 1),
    GOTO_16             (0x29, "goto/16",                InsnFormat.FORMAT_20t, 2),
    GOTO_32             (0x2a, "goto/32",                InsnFormat.FORMAT_30t, 3),
    PACKED_SWITCH       (0x2b, "packed-switch",          InsnFormat.FORMAT_31t, 3),
    SPARSE_SWITCH       (0x2c, "sparse-switch",          InsnFormat.FORMAT_31t, 3),

    CMPL_FLOAT          (0x2d, "cmpl-float",             InsnFormat.FORMAT_23x, 2),
    CMPG_FLOAT          (0x2e, "cmpg-float",             InsnFormat.FORMAT_23x, 2),
    CMPL_DOUBLE         (0x2f, "cmpl-double",            InsnFormat.FORMAT_23x, 2),
    CMPG_DOUBLE         (0x30, "cmpg-double",            InsnFormat.FORMAT_23x, 2),
    CMP_LONG            (0x31, "cmp-long",               InsnFormat.FORMAT_23x, 2),

    IF_EQ               (0x32, "if-eq",                  InsnFormat.FORMAT_22t, 2),
    IF_NE               (0x33, "if-ne",                  InsnFormat.FORMAT_22t, 2),
    IF_LT               (0x34, "if-lt",                  InsnFormat.FORMAT_22t, 2),
    IF_GE               (0x35, "if-ge",                  InsnFormat.FORMAT_22t, 2),
    IF_GT               (0x36, "if-gt",                  InsnFormat.FORMAT_22t, 2),
    IF_LE               (0x37, "if-le",                  InsnFormat.FORMAT_22t, 2),
    IF_EQZ              (0x38, "if-eqz",                 InsnFormat.FORMAT_21t, 2),
    IF_NEZ              (0x39, "if-nez",                 InsnFormat.FORMAT_21t, 2),
    IF_LTZ              (0x3a, "if-ltz",                 InsnFormat.FORMAT_21t, 2),
    IF_GEZ              (0x3b, "if-gez",                 InsnFormat.FORMAT_21t, 2),
    IF_GTZ              (0x3c, "if-gtz",                 InsnFormat.FORMAT_21t, 2),
    IF_LEZ              (0x3d, "if-lez",                 InsnFormat.FORMAT_21t, 2),

    AGET                (0x44, "aget",                   InsnFormat.FORMAT_23x, 2),
    AGET_WIDE           (0x45, "aget-wide",              InsnFormat.FORMAT_23x, 2),
    AGET_OBJECT         (0x46, "aget-object",            InsnFormat.FORMAT_23x, 2),
    AGET_BOOLEAN        (0x47, "aget-boolean",           InsnFormat.FORMAT_23x, 2),
    AGET_BYTE           (0x48, "aget-byte",              InsnFormat.FORMAT_23x, 2),
    AGET_CHAR           (0x49, "aget-char",              InsnFormat.FORMAT_23x, 2),
    AGET_SHORT          (0x4a, "aget-short",             InsnFormat.FORMAT_23x, 2),
    APUT                (0x4b, "aput",                   InsnFormat.FORMAT_23x, 2),
    APUT_WIDE           (0x4c, "aput-wide",              InsnFormat.FORMAT_23x, 2),
    APUT_OBJECT         (0x4d, "aput-object",            InsnFormat.FORMAT_23x, 2),
    APUT_BOOLEAN        (0x4e, "aput-boolean",           InsnFormat.FORMAT_23x, 2),
    APUT_BYTE           (0x4f, "aput-byte",              InsnFormat.FORMAT_23x, 2),
    APUT_CHAR           (0x50, "aput-char",              InsnFormat.FORMAT_23x, 2),
    APUT_SHORT          (0x51, "aput-short",             InsnFormat.FORMAT_23x, 2),

    IGET                (0x52, "iget",                   InsnFormat.FORMAT_22c, 2, IndexPool.FIELD, 1),
    IGET_WIDE           (0x53, "iget-wide",              InsnFormat.FORMAT_22c, 2, IndexPool.FIELD, 1),
    IGET_OBJECT         (0x54, "iget-object",            InsnFormat.FORMAT_22c, 2, IndexPool.FIELD, 1),
    IGET_BOOLEAN        (0x55, "iget-boolean",           InsnFormat.FORMAT_22c, 2, IndexPool.FIELD, 1),
    IGET_BYTE           (0x56, "iget-byte",              InsnFormat.FORMAT_22c, 2, IndexPool.FIELD, 1),
    IGET_CHAR           (0x57, "iget-char",              InsnFormat.FORMAT_22c, 2, IndexPool.FIELD, 1),
    IGET_SHORT          (0x58, "iget-short",             InsnFormat.FORMAT_22c, 2, IndexPool.FIELD, 1),
    IPUT                (0x59, "iput",                   InsnFormat.FORMAT_22c, 2, IndexPool.FIELD, 1),
    IPUT_WIDE           (0x5a, "iput-wide",              InsnFormat.FORMAT_22c, 2, IndexPool.FIELD, 1),
    IPUT_OBJECT         (0x5b, "iput-object",            InsnFormat.FORMAT_22c, 2, IndexPool.FIELD, 1),
    IPUT_BOOLEAN        (0x5c, "iput-boolean",           InsnFormat.FORMAT_22c, 2, IndexPool.FIELD, 1),
    IPUT_BYTE           (0x5d, "iput-byte",              InsnFormat.FORMAT_22c, 2, IndexPool.FIELD, 1),
    IPUT_CHAR           (0x5e, "iput-char",              InsnFormat.FORMAT_22c, 2, IndexPool.FIELD, 1),
    IPUT_SHORT          (0x5f, "iput-short",             InsnFormat.FORMAT_22c, 2, IndexPool.FIELD, 1),

    SGET                (0x60, "sget",                   InsnFormat.FORMAT_21c, 2, IndexPool.FIELD, 1),
    SGET_WIDE           (0x61, "sget-wide",              InsnFormat.FORMAT_21c, 2, IndexPool.FIELD, 1),
    SGET_OBJECT         (0x62, "sget-object",            InsnFormat.FORMAT_21c, 2, IndexPool.FIELD, 1),
    SGET_BOOLEAN        (0x63, "sget-boolean",           InsnFormat.FORMAT_21c, 2, IndexPool.FIELD, 1),
    SGET_BYTE           (0x64, "sget-byte",              InsnFormat.FORMAT_21c, 2, IndexPool.FIELD, 1),
    SGET_CHAR           (0x65, "sget-char",              InsnFormat.FORMAT_21c, 2, IndexPool.FIELD, 1),
    SGET_SHORT          (0x66, "sget-short",             InsnFormat.FORMAT_21c, 2, IndexPool.FIELD, 1),
    SPUT                (0x67, "sput",                   InsnFormat.FORMAT_21c, 2, IndexPool.FIELD, 1),
    SPUT_WIDE           (0x68, "sput-wide",              InsnFormat.FORMAT_21c, 2, IndexPool.FIELD, 1),
    SPUT_OBJECT         (0x69, "sput-object",            InsnFormat.FORMAT_21c, 2, IndexPool.FIELD, 1),
    SPUT_BOOLEAN        (0x6a, "sput-boolean",           InsnFormat.FORMAT_21c, 2, IndexPool.FIELD, 1),
    SPUT_BYTE           (0x6b, "sput-byte",              InsnFormat.FORMAT_21c, 2, IndexPool.FIELD, 1),
    SPUT_CHAR           (0x6c, "sput-char",              InsnFormat.FORMAT_21c, 2, IndexPool.FIELD, 1),
    SPUT_SHORT          (0x6d, "sput-short",             InsnFormat.FORMAT_21c, 2, IndexPool.FIELD, 1),

    INVOKE_VIRTUAL      (0x6e, "invoke-virtual",         InsnFormat.FORMAT_35c, 3, IndexPool.METHOD, 1),
    INVOKE_SUPER        (0x6f, "invoke-super",           InsnFormat.FORMAT_35c, 3, IndexPool.METHOD, 1),
    INVOKE_DIRECT       (0x70, "invoke-direct",          InsnFormat.FORMAT_35c, 3, IndexPool.METHOD, 1),
    INVOKE_STATIC       (0x71, "invoke-static",          InsnFormat.FORMAT_35c, 3, IndexPool.METHOD, 1),
    INVOKE_INTERFACE    (0x72, "invoke-interface",       InsnFormat.FORMAT_35c, 3, IndexPool.METHOD, 1),
    // 0x73 — unused
    INVOKE_VIRTUAL_RANGE    (0x74, "invoke-virtual/range",   InsnFormat.FORMAT_3rc, 3, IndexPool.METHOD, 1),
    INVOKE_SUPER_RANGE      (0x75, "invoke-super/range",     InsnFormat.FORMAT_3rc, 3, IndexPool.METHOD, 1),
    INVOKE_DIRECT_RANGE     (0x76, "invoke-direct/range",    InsnFormat.FORMAT_3rc, 3, IndexPool.METHOD, 1),
    INVOKE_STATIC_RANGE     (0x77, "invoke-static/range",    InsnFormat.FORMAT_3rc, 3, IndexPool.METHOD, 1),
    INVOKE_INTERFACE_RANGE  (0x78, "invoke-interface/range", InsnFormat.FORMAT_3rc, 3, IndexPool.METHOD, 1),
    // 0x79, 0x7a — unused

    NEG_INT             (0x7b, "neg-int",                InsnFormat.FORMAT_12x, 1),
    NOT_INT             (0x7c, "not-int",                InsnFormat.FORMAT_12x, 1),
    NEG_LONG            (0x7d, "neg-long",               InsnFormat.FORMAT_12x, 1),
    NOT_LONG            (0x7e, "not-long",               InsnFormat.FORMAT_12x, 1),
    NEG_FLOAT           (0x7f, "neg-float",              InsnFormat.FORMAT_12x, 1),
    NEG_DOUBLE          (0x80, "neg-double",             InsnFormat.FORMAT_12x, 1),
    INT_TO_LONG         (0x81, "int-to-long",            InsnFormat.FORMAT_12x, 1),
    INT_TO_FLOAT        (0x82, "int-to-float",           InsnFormat.FORMAT_12x, 1),
    INT_TO_DOUBLE       (0x83, "int-to-double",          InsnFormat.FORMAT_12x, 1),
    LONG_TO_INT         (0x84, "long-to-int",            InsnFormat.FORMAT_12x, 1),
    LONG_TO_FLOAT       (0x85, "long-to-float",          InsnFormat.FORMAT_12x, 1),
    LONG_TO_DOUBLE      (0x86, "long-to-double",         InsnFormat.FORMAT_12x, 1),
    FLOAT_TO_INT        (0x87, "float-to-int",           InsnFormat.FORMAT_12x, 1),
    FLOAT_TO_LONG       (0x88, "float-to-long",          InsnFormat.FORMAT_12x, 1),
    FLOAT_TO_DOUBLE     (0x89, "float-to-double",        InsnFormat.FORMAT_12x, 1),
    DOUBLE_TO_INT       (0x8a, "double-to-int",          InsnFormat.FORMAT_12x, 1),
    DOUBLE_TO_LONG      (0x8b, "double-to-long",         InsnFormat.FORMAT_12x, 1),
    DOUBLE_TO_FLOAT     (0x8c, "double-to-float",        InsnFormat.FORMAT_12x, 1),
    INT_TO_BYTE         (0x8d, "int-to-byte",            InsnFormat.FORMAT_12x, 1),
    INT_TO_CHAR         (0x8e, "int-to-char",            InsnFormat.FORMAT_12x, 1),
    INT_TO_SHORT        (0x8f, "int-to-short",           InsnFormat.FORMAT_12x, 1),

    ADD_INT             (0x90, "add-int",                InsnFormat.FORMAT_23x, 2),
    SUB_INT             (0x91, "sub-int",                InsnFormat.FORMAT_23x, 2),
    MUL_INT             (0x92, "mul-int",                InsnFormat.FORMAT_23x, 2),
    DIV_INT             (0x93, "div-int",                InsnFormat.FORMAT_23x, 2),
    REM_INT             (0x94, "rem-int",                InsnFormat.FORMAT_23x, 2),
    AND_INT             (0x95, "and-int",                InsnFormat.FORMAT_23x, 2),
    OR_INT              (0x96, "or-int",                 InsnFormat.FORMAT_23x, 2),
    XOR_INT             (0x97, "xor-int",                InsnFormat.FORMAT_23x, 2),
    SHL_INT             (0x98, "shl-int",                InsnFormat.FORMAT_23x, 2),
    SHR_INT             (0x99, "shr-int",                InsnFormat.FORMAT_23x, 2),
    USHR_INT            (0x9a, "ushr-int",               InsnFormat.FORMAT_23x, 2),
    ADD_LONG            (0x9b, "add-long",               InsnFormat.FORMAT_23x, 2),
    SUB_LONG            (0x9c, "sub-long",               InsnFormat.FORMAT_23x, 2),
    MUL_LONG            (0x9d, "mul-long",               InsnFormat.FORMAT_23x, 2),
    DIV_LONG            (0x9e, "div-long",               InsnFormat.FORMAT_23x, 2),
    REM_LONG            (0x9f, "rem-long",               InsnFormat.FORMAT_23x, 2),
    AND_LONG            (0xa0, "and-long",               InsnFormat.FORMAT_23x, 2),
    OR_LONG             (0xa1, "or-long",                InsnFormat.FORMAT_23x, 2),
    XOR_LONG            (0xa2, "xor-long",               InsnFormat.FORMAT_23x, 2),
    SHL_LONG            (0xa3, "shl-long",               InsnFormat.FORMAT_23x, 2),
    SHR_LONG            (0xa4, "shr-long",               InsnFormat.FORMAT_23x, 2),
    USHR_LONG           (0xa5, "ushr-long",              InsnFormat.FORMAT_23x, 2),
    ADD_FLOAT           (0xa6, "add-float",              InsnFormat.FORMAT_23x, 2),
    SUB_FLOAT           (0xa7, "sub-float",              InsnFormat.FORMAT_23x, 2),
    MUL_FLOAT           (0xa8, "mul-float",              InsnFormat.FORMAT_23x, 2),
    DIV_FLOAT           (0xa9, "div-float",              InsnFormat.FORMAT_23x, 2),
    REM_FLOAT           (0xaa, "rem-float",              InsnFormat.FORMAT_23x, 2),
    ADD_DOUBLE          (0xab, "add-double",             InsnFormat.FORMAT_23x, 2),
    SUB_DOUBLE          (0xac, "sub-double",             InsnFormat.FORMAT_23x, 2),
    MUL_DOUBLE          (0xad, "mul-double",             InsnFormat.FORMAT_23x, 2),
    DIV_DOUBLE          (0xae, "div-double",             InsnFormat.FORMAT_23x, 2),
    REM_DOUBLE          (0xaf, "rem-double",             InsnFormat.FORMAT_23x, 2),

    ADD_INT_2ADDR       (0xb0, "add-int/2addr",          InsnFormat.FORMAT_12x, 1),
    SUB_INT_2ADDR       (0xb1, "sub-int/2addr",          InsnFormat.FORMAT_12x, 1),
    MUL_INT_2ADDR       (0xb2, "mul-int/2addr",          InsnFormat.FORMAT_12x, 1),
    DIV_INT_2ADDR       (0xb3, "div-int/2addr",          InsnFormat.FORMAT_12x, 1),
    REM_INT_2ADDR       (0xb4, "rem-int/2addr",          InsnFormat.FORMAT_12x, 1),
    AND_INT_2ADDR       (0xb5, "and-int/2addr",          InsnFormat.FORMAT_12x, 1),
    OR_INT_2ADDR        (0xb6, "or-int/2addr",           InsnFormat.FORMAT_12x, 1),
    XOR_INT_2ADDR       (0xb7, "xor-int/2addr",          InsnFormat.FORMAT_12x, 1),
    SHL_INT_2ADDR       (0xb8, "shl-int/2addr",          InsnFormat.FORMAT_12x, 1),
    SHR_INT_2ADDR       (0xb9, "shr-int/2addr",          InsnFormat.FORMAT_12x, 1),
    USHR_INT_2ADDR      (0xba, "ushr-int/2addr",         InsnFormat.FORMAT_12x, 1),
    ADD_LONG_2ADDR      (0xbb, "add-long/2addr",         InsnFormat.FORMAT_12x, 1),
    SUB_LONG_2ADDR      (0xbc, "sub-long/2addr",         InsnFormat.FORMAT_12x, 1),
    MUL_LONG_2ADDR      (0xbd, "mul-long/2addr",         InsnFormat.FORMAT_12x, 1),
    DIV_LONG_2ADDR      (0xbe, "div-long/2addr",         InsnFormat.FORMAT_12x, 1),
    REM_LONG_2ADDR      (0xbf, "rem-long/2addr",         InsnFormat.FORMAT_12x, 1),
    AND_LONG_2ADDR      (0xc0, "and-long/2addr",         InsnFormat.FORMAT_12x, 1),
    OR_LONG_2ADDR       (0xc1, "or-long/2addr",          InsnFormat.FORMAT_12x, 1),
    XOR_LONG_2ADDR      (0xc2, "xor-long/2addr",         InsnFormat.FORMAT_12x, 1),
    SHL_LONG_2ADDR      (0xc3, "shl-long/2addr",         InsnFormat.FORMAT_12x, 1),
    SHR_LONG_2ADDR      (0xc4, "shr-long/2addr",         InsnFormat.FORMAT_12x, 1),
    USHR_LONG_2ADDR     (0xc5, "ushr-long/2addr",        InsnFormat.FORMAT_12x, 1),
    ADD_FLOAT_2ADDR     (0xc6, "add-float/2addr",        InsnFormat.FORMAT_12x, 1),
    SUB_FLOAT_2ADDR     (0xc7, "sub-float/2addr",        InsnFormat.FORMAT_12x, 1),
    MUL_FLOAT_2ADDR     (0xc8, "mul-float/2addr",        InsnFormat.FORMAT_12x, 1),
    DIV_FLOAT_2ADDR     (0xc9, "div-float/2addr",        InsnFormat.FORMAT_12x, 1),
    REM_FLOAT_2ADDR     (0xca, "rem-float/2addr",        InsnFormat.FORMAT_12x, 1),
    ADD_DOUBLE_2ADDR    (0xcb, "add-double/2addr",       InsnFormat.FORMAT_12x, 1),
    SUB_DOUBLE_2ADDR    (0xcc, "sub-double/2addr",       InsnFormat.FORMAT_12x, 1),
    MUL_DOUBLE_2ADDR    (0xcd, "mul-double/2addr",       InsnFormat.FORMAT_12x, 1),
    DIV_DOUBLE_2ADDR    (0xce, "div-double/2addr",       InsnFormat.FORMAT_12x, 1),
    REM_DOUBLE_2ADDR    (0xcf, "rem-double/2addr",       InsnFormat.FORMAT_12x, 1),

    ADD_INT_LIT16       (0xd0, "add-int/lit16",          InsnFormat.FORMAT_22s, 2),
    RSUB_INT            (0xd1, "rsub-int",               InsnFormat.FORMAT_22s, 2),
    MUL_INT_LIT16       (0xd2, "mul-int/lit16",          InsnFormat.FORMAT_22s, 2),
    DIV_INT_LIT16       (0xd3, "div-int/lit16",          InsnFormat.FORMAT_22s, 2),
    REM_INT_LIT16       (0xd4, "rem-int/lit16",          InsnFormat.FORMAT_22s, 2),
    AND_INT_LIT16       (0xd5, "and-int/lit16",          InsnFormat.FORMAT_22s, 2),
    OR_INT_LIT16        (0xd6, "or-int/lit16",           InsnFormat.FORMAT_22s, 2),
    XOR_INT_LIT16       (0xd7, "xor-int/lit16",          InsnFormat.FORMAT_22s, 2),

    ADD_INT_LIT8        (0xd8, "add-int/lit8",           InsnFormat.FORMAT_22b, 2),
    RSUB_INT_LIT8       (0xd9, "rsub-int/lit8",          InsnFormat.FORMAT_22b, 2),
    MUL_INT_LIT8        (0xda, "mul-int/lit8",           InsnFormat.FORMAT_22b, 2),
    DIV_INT_LIT8        (0xdb, "div-int/lit8",           InsnFormat.FORMAT_22b, 2),
    REM_INT_LIT8        (0xdc, "rem-int/lit8",           InsnFormat.FORMAT_22b, 2),
    AND_INT_LIT8        (0xdd, "and-int/lit8",           InsnFormat.FORMAT_22b, 2),
    OR_INT_LIT8         (0xde, "or-int/lit8",            InsnFormat.FORMAT_22b, 2),
    XOR_INT_LIT8        (0xdf, "xor-int/lit8",           InsnFormat.FORMAT_22b, 2),
    SHL_INT_LIT8        (0xe0, "shl-int/lit8",           InsnFormat.FORMAT_22b, 2),
    SHR_INT_LIT8        (0xe1, "shr-int/lit8",           InsnFormat.FORMAT_22b, 2),
    USHR_INT_LIT8       (0xe2, "ushr-int/lit8",          InsnFormat.FORMAT_22b, 2),

    // --- ART / DEX 038+ (invoke-polymorphic/custom), 039+ (const-method-handle/type) ---
    INVOKE_POLYMORPHIC      (0xfa, "invoke-polymorphic",       InsnFormat.FORMAT_45cc, 4, IndexPool.METHOD, 1, 38),
    INVOKE_POLYMORPHIC_RANGE(0xfb, "invoke-polymorphic/range", InsnFormat.FORMAT_4rcc, 4, IndexPool.METHOD, 1, 38),
    INVOKE_CUSTOM           (0xfc, "invoke-custom",            InsnFormat.FORMAT_35c,  3, IndexPool.CALL_SITE, 1, 38),
    INVOKE_CUSTOM_RANGE     (0xfd, "invoke-custom/range",      InsnFormat.FORMAT_3rc,  3, IndexPool.CALL_SITE, 1, 38),
    CONST_METHOD_HANDLE     (0xfe, "const-method-handle",      InsnFormat.FORMAT_21c,  2, IndexPool.METHOD_HANDLE, 1, 39),
    CONST_METHOD_TYPE       (0xff, "const-method-type",        InsnFormat.FORMAT_21c,  2, IndexPool.PROTO, 1, 39);

    companion object {
        private val BY_VALUE = IntArray(256) { -1 }
        private val BY_INDEX = arrayOfNulls<Opcode>(256)

        init {
            for (op in entries) {
                if (op.value in 0..255) {
                    BY_VALUE[op.value] = op.ordinal
                    BY_INDEX[op.value] = op
                }
            }
        }

        /** Быстрый доступ по числовому значению. Возвращает null для unused-опкодов. */
        fun of(value: Int): Opcode? = if (value in 0..255) BY_INDEX[value] else null

        /** Проверка, является ли опкод unused. */
        fun isUnused(value: Int): Boolean = of(value) == null

        /**
         * Для пейлоадов (`packed-switch-payload`, `sparse-switch-payload`,
         * `fill-array-data-payload`) возвращает их ident-код по второму юниту.
         */
        fun payloadIdentOf(unit0: Int): PayloadIdent = when (unit0) {
            0x0100 -> PayloadIdent.PACKED_SWITCH
            0x0200 -> PayloadIdent.SPARSE_SWITCH
            0x0300 -> PayloadIdent.FILL_ARRAY_DATA
            else   -> PayloadIdent.NONE
        }
    }
}
