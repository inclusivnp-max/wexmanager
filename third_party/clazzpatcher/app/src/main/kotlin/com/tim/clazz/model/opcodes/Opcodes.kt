package com.tim.clazz.model.opcodes

/**
 * Коды операций байт-кода Java
 */
object Opcodes {
    const val NOP = 0x00
    const val ACONST_NULL = 0x01
    const val ICONST_M1 = 0x02
    const val ICONST_0 = 0x03
    const val ICONST_1 = 0x04
    const val ICONST_2 = 0x05
    const val ICONST_3 = 0x06
    const val ICONST_4 = 0x07
    const val ICONST_5 = 0x08
    const val LCONST_0 = 0x09
    const val LCONST_1 = 0x0A
    const val FCONST_0 = 0x0B
    const val FCONST_1 = 0x0C
    const val FCONST_2 = 0x0D
    const val DCONST_0 = 0x0E
    const val DCONST_1 = 0x0F
    
    const val BIPUSH = 0x10
    const val SIPUSH = 0x11
    const val LDC = 0x12
    const val LDC_W = 0x13
    const val LDC2_W = 0x14
    
    const val ILOAD = 0x15
    const val LLOAD = 0x16
    const val FLOAD = 0x17
    const val DLOAD = 0x18
    const val ALOAD = 0x19
    const val ILOAD_0 = 0x1A
    const val ILOAD_1 = 0x1B
    const val ILOAD_2 = 0x1C
    const val ILOAD_3 = 0x1D
    const val LLOAD_0 = 0x1E
    const val LLOAD_1 = 0x1F
    const val LLOAD_2 = 0x20
    const val LLOAD_3 = 0x21
    const val FLOAD_0 = 0x22
    const val FLOAD_1 = 0x23
    const val FLOAD_2 = 0x24
    const val FLOAD_3 = 0x25
    const val DLOAD_0 = 0x26
    const val DLOAD_1 = 0x27
    const val DLOAD_2 = 0x28
    const val DLOAD_3 = 0x29
    const val ALOAD_0 = 0x2A
    const val ALOAD_1 = 0x2B
    const val ALOAD_2 = 0x2C
    const val ALOAD_3 = 0x2D
    
    const val IALOAD = 0x2E
    const val LALOAD = 0x2F
    const val FALOAD = 0x30
    const val DALOAD = 0x31
    const val AALOAD = 0x32
    const val BALOAD = 0x33
    const val CALOAD = 0x34
    const val SALOAD = 0x35
    
    const val ISTORE = 0x36
    const val LSTORE = 0x37
    const val FSTORE = 0x38
    const val DSTORE = 0x39
    const val ASTORE = 0x3A
    const val ISTORE_0 = 0x3B
    const val ISTORE_1 = 0x3C
    const val ISTORE_2 = 0x3D
    const val ISTORE_3 = 0x3E
    const val LSTORE_0 = 0x3F
    const val LSTORE_1 = 0x40
    const val LSTORE_2 = 0x41
    const val LSTORE_3 = 0x42
    const val FSTORE_0 = 0x43
    const val FSTORE_1 = 0x44
    const val FSTORE_2 = 0x45
    const val FSTORE_3 = 0x46
    const val DSTORE_0 = 0x47
    const val DSTORE_1 = 0x48
    const val DSTORE_2 = 0x49
    const val DSTORE_3 = 0x4A
    const val ASTORE_0 = 0x4B
    const val ASTORE_1 = 0x4C
    const val ASTORE_2 = 0x4D
    const val ASTORE_3 = 0x4E
    
    const val IASTORE = 0x4F
    const val LASTORE = 0x50
    const val FASTORE = 0x51
    const val DASTORE = 0x52
    const val AASTORE = 0x53
    const val BASTORE = 0x54
    const val CASTORE = 0x55
    const val SASTORE = 0x56
    
    const val POP = 0x57
    const val POP2 = 0x58
    const val DUP = 0x59
    const val DUP_X1 = 0x5A
    const val DUP_X2 = 0x5B
    const val DUP2 = 0x5C
    const val DUP2_X1 = 0x5D
    const val DUP2_X2 = 0x5E
    const val SWAP = 0x5F
    
    const val IADD = 0x60
    const val LADD = 0x61
    const val FADD = 0x62
    const val DADD = 0x63
    const val ISUB = 0x64
    const val LSUB = 0x65
    const val FSUB = 0x66
    const val DSUB = 0x67
    const val IMUL = 0x68
    const val LMUL = 0x69
    const val FMUL = 0x6A
    const val DMUL = 0x6B
    const val IDIV = 0x6C
    const val LDIV = 0x6D
    const val FDIV = 0x6E
    const val DDIV = 0x6F
    const val IREM = 0x70
    const val LREM = 0x71
    const val FREM = 0x72
    const val DREM = 0x73
    const val INEG = 0x74
    const val LNEG = 0x75
    const val FNEG = 0x76
    const val DNEG = 0x77
    
    const val ISHL = 0x78
    const val LSHL = 0x79
    const val ISHR = 0x7A
    const val LSHR = 0x7B
    const val IUSHR = 0x7C
    const val LUSHR = 0x7D
    
    const val IAND = 0x7E
    const val LAND = 0x7F
    const val IOR = 0x80
    const val LOR = 0x81
    const val IXOR = 0x82
    const val LXOR = 0x83
    
    const val IINC = 0x84
    
    const val I2L = 0x85
    const val I2F = 0x86
    const val I2D = 0x87
    const val L2I = 0x88
    const val L2F = 0x89
    const val L2D = 0x8A
    const val F2I = 0x8B
    const val F2L = 0x8C
    const val F2D = 0x8D
    const val D2I = 0x8E
    const val D2L = 0x8F
    const val D2F = 0x90
    const val I2B = 0x91
    const val I2C = 0x92
    const val I2S = 0x93
    
    const val LCMP = 0x94
    const val FCMPL = 0x95
    const val FCMPG = 0x96
    const val DCMPL = 0x97
    const val DCMPG = 0x98
    
    const val IFEQ = 0x99
    const val IFNE = 0x9A
    const val IFLT = 0x9B
    const val IFGE = 0x9C
    const val IFGT = 0x9D
    const val IFLE = 0x9E
    const val IF_ICMPEQ = 0x9F
    const val IF_ICMPNE = 0xA0
    const val IF_ICMPLT = 0xA1
    const val IF_ICMPGE = 0xA2
    const val IF_ICMPGT = 0xA3
    const val IF_ICMPLE = 0xA4
    const val IF_ACMPEQ = 0xA5
    const val IF_ACMPNE = 0xA6
    
    const val GOTO = 0xA7
    const val JSR = 0xA8
    const val RET = 0xA9
    const val TABLESWITCH = 0xAA
    const val LOOKUPSWITCH = 0xAB
    
    const val IRETURN = 0xAC
    const val LRETURN = 0xAD
    const val FRETURN = 0xAE
    const val DRETURN = 0xAF
    const val ARETURN = 0xB0
    const val RETURN = 0xB1
    
    const val GETSTATIC = 0xB2
    const val PUTSTATIC = 0xB3
    const val GETFIELD = 0xB4
    const val PUTFIELD = 0xB5
    
    const val INVOKEVIRTUAL = 0xB6
    const val INVOKESPECIAL = 0xB7
    const val INVOKESTATIC = 0xB8
    const val INVOKEINTERFACE = 0xB9
    const val INVOKEDYNAMIC = 0xBA
    
    const val NEW = 0xBB
    const val NEWARRAY = 0xBC
    const val ANEWARRAY = 0xBD
    const val ARRAYLENGTH = 0xBE
    
    const val ATHROW = 0xBF
    
    const val CHECKCAST = 0xC0
    const val INSTANCEOF = 0xC1
    
    const val MONITORENTER = 0xC2
    const val MONITOREXIT = 0xC3
    
    const val WIDE = 0xC4
    const val MULTIANEWARRAY = 0xC5
    const val IFNULL = 0xC6
    const val IFNONNULL = 0xC7
    const val GOTO_W = 0xC8
    const val JSR_W = 0xC9
    
    const val BREAKPOINT = 0xCA
    const val IMPDEP1 = 0xFE
    const val IMPDEP2 = 0xFF
    
    fun getOpcodeName(opcode: Int): String {
        return when (opcode) {
            NOP -> "NOP"
            ACONST_NULL -> "ACONST_NULL"
            ICONST_M1 -> "ICONST_M1"
            ICONST_0 -> "ICONST_0"
            ICONST_1 -> "ICONST_1"
            ICONST_2 -> "ICONST_2"
            ICONST_3 -> "ICONST_3"
            ICONST_4 -> "ICONST_4"
            ICONST_5 -> "ICONST_5"
            LCONST_0 -> "LCONST_0"
            LCONST_1 -> "LCONST_1"
            FCONST_0 -> "FCONST_0"
            FCONST_1 -> "FCONST_1"
            FCONST_2 -> "FCONST_2"
            DCONST_0 -> "DCONST_0"
            DCONST_1 -> "DCONST_1"
            BIPUSH -> "BIPUSH"
            SIPUSH -> "SIPUSH"
            LDC -> "LDC"
            LDC_W -> "LDC_W"
            LDC2_W -> "LDC2_W"
            ILOAD -> "ILOAD"
            LLOAD -> "LLOAD"
            FLOAD -> "FLOAD"
            DLOAD -> "DLOAD"
            ALOAD -> "ALOAD"
            ILOAD_0 -> "ILOAD_0"
            ILOAD_1 -> "ILOAD_1"
            ILOAD_2 -> "ILOAD_2"
            ILOAD_3 -> "ILOAD_3"
            LLOAD_0 -> "LLOAD_0"
            LLOAD_1 -> "LLOAD_1"
            LLOAD_2 -> "LLOAD_2"
            LLOAD_3 -> "LLOAD_3"
            FLOAD_0 -> "FLOAD_0"
            FLOAD_1 -> "FLOAD_1"
            FLOAD_2 -> "FLOAD_2"
            FLOAD_3 -> "FLOAD_3"
            DLOAD_0 -> "DLOAD_0"
            DLOAD_1 -> "DLOAD_1"
            DLOAD_2 -> "DLOAD_2"
            DLOAD_3 -> "DLOAD_3"
            ALOAD_0 -> "ALOAD_0"
            ALOAD_1 -> "ALOAD_1"
            ALOAD_2 -> "ALOAD_2"
            ALOAD_3 -> "ALOAD_3"
            IALOAD -> "IALOAD"
            LALOAD -> "LALOAD"
            FALOAD -> "FALOAD"
            DALOAD -> "DALOAD"
            AALOAD -> "AALOAD"
            BALOAD -> "BALOAD"
            CALOAD -> "CALOAD"
            SALOAD -> "SALOAD"
            ISTORE -> "ISTORE"
            LSTORE -> "LSTORE"
            FSTORE -> "FSTORE"
            DSTORE -> "DSTORE"
            ASTORE -> "ASTORE"
            ISTORE_0 -> "ISTORE_0"
            ISTORE_1 -> "ISTORE_1"
            ISTORE_2 -> "ISTORE_2"
            ISTORE_3 -> "ISTORE_3"
            LSTORE_0 -> "LSTORE_0"
            LSTORE_1 -> "LSTORE_1"
            LSTORE_2 -> "LSTORE_2"
            LSTORE_3 -> "LSTORE_3"
            FSTORE_0 -> "FSTORE_0"
            FSTORE_1 -> "FSTORE_1"
            FSTORE_2 -> "FSTORE_2"
            FSTORE_3 -> "FSTORE_3"
            DSTORE_0 -> "DSTORE_0"
            DSTORE_1 -> "DSTORE_1"
            DSTORE_2 -> "DSTORE_2"
            DSTORE_3 -> "DSTORE_3"
            ASTORE_0 -> "ASTORE_0"
            ASTORE_1 -> "ASTORE_1"
            ASTORE_2 -> "ASTORE_2"
            ASTORE_3 -> "ASTORE_3"
            IASTORE -> "IASTORE"
            LASTORE -> "LASTORE"
            FASTORE -> "FASTORE"
            DASTORE -> "DASTORE"
            AASTORE -> "AASTORE"
            BASTORE -> "BASTORE"
            CASTORE -> "CASTORE"
            SASTORE -> "SASTORE"
            POP -> "POP"
            POP2 -> "POP2"
            DUP -> "DUP"
            DUP_X1 -> "DUP_X1"
            DUP_X2 -> "DUP_X2"
            DUP2 -> "DUP2"
            DUP2_X1 -> "DUP2_X1"
            DUP2_X2 -> "DUP2_X2"
            SWAP -> "SWAP"
            IADD -> "IADD"
            LADD -> "LADD"
            FADD -> "FADD"
            DADD -> "DADD"
            ISUB -> "ISUB"
            LSUB -> "LSUB"
            FSUB -> "FSUB"
            DSUB -> "DSUB"
            IMUL -> "IMUL"
            LMUL -> "LMUL"
            FMUL -> "FMUL"
            DMUL -> "DMUL"
            IDIV -> "IDIV"
            LDIV -> "LDIV"
            FDIV -> "FDIV"
            DDIV -> "DDIV"
            IREM -> "IREM"
            LREM -> "LREM"
            FREM -> "FREM"
            DREM -> "DREM"
            INEG -> "INEG"
            LNEG -> "LNEG"
            FNEG -> "FNEG"
            DNEG -> "DNEG"
            ISHL -> "ISHL"
            LSHL -> "LSHL"
            ISHR -> "ISHR"
            LSHR -> "LSHR"
            IUSHR -> "IUSHR"
            LUSHR -> "LUSHR"
            IAND -> "IAND"
            LAND -> "LAND"
            IOR -> "IOR"
            LOR -> "LOR"
            IXOR -> "IXOR"
            LXOR -> "LXOR"
            IINC -> "IINC"
            I2L -> "I2L"
            I2F -> "I2F"
            I2D -> "I2D"
            L2I -> "L2I"
            L2F -> "L2F"
            L2D -> "L2D"
            F2I -> "F2I"
            F2L -> "F2L"
            F2D -> "F2D"
            D2I -> "D2I"
            D2L -> "D2L"
            D2F -> "D2F"
            I2B -> "I2B"
            I2C -> "I2C"
            I2S -> "I2S"
            LCMP -> "LCMP"
            FCMPL -> "FCMPL"
            FCMPG -> "FCMPG"
            DCMPL -> "DCMPL"
            DCMPG -> "DCMPG"
            IFEQ -> "IFEQ"
            IFNE -> "IFNE"
            IFLT -> "IFLT"
            IFGE -> "IFGE"
            IFGT -> "IFGT"
            IFLE -> "IFLE"
            IF_ICMPEQ -> "IF_ICMPEQ"
            IF_ICMPNE -> "IF_ICMPNE"
            IF_ICMPLT -> "IF_ICMPLT"
            IF_ICMPGE -> "IF_ICMPGE"
            IF_ICMPGT -> "IF_ICMPGT"
            IF_ICMPLE -> "IF_ICMPLE"
            IF_ACMPEQ -> "IF_ACMPEQ"
            IF_ACMPNE -> "IF_ACMPNE"
            GOTO -> "GOTO"
            JSR -> "JSR"
            RET -> "RET"
            TABLESWITCH -> "TABLESWITCH"
            LOOKUPSWITCH -> "LOOKUPSWITCH"
            IRETURN -> "IRETURN"
            LRETURN -> "LRETURN"
            FRETURN -> "FRETURN"
            DRETURN -> "DRETURN"
            ARETURN -> "ARETURN"
            RETURN -> "RETURN"
            GETSTATIC -> "GETSTATIC"
            PUTSTATIC -> "PUTSTATIC"
            GETFIELD -> "GETFIELD"
            PUTFIELD -> "PUTFIELD"
            INVOKEVIRTUAL -> "INVOKEVIRTUAL"
            INVOKESPECIAL -> "INVOKESPECIAL"
            INVOKESTATIC -> "INVOKESTATIC"
            INVOKEINTERFACE -> "INVOKEINTERFACE"
            INVOKEDYNAMIC -> "INVOKEDYNAMIC"
            NEW -> "NEW"
            NEWARRAY -> "NEWARRAY"
            ANEWARRAY -> "ANEWARRAY"
            ARRAYLENGTH -> "ARRAYLENGTH"
            ATHROW -> "ATHROW"
            CHECKCAST -> "CHECKCAST"
            INSTANCEOF -> "INSTANCEOF"
            MONITORENTER -> "MONITORENTER"
            MONITOREXIT -> "MONITOREXIT"
            WIDE -> "WIDE"
            MULTIANEWARRAY -> "MULTIANEWARRAY"
            IFNULL -> "IFNULL"
            IFNONNULL -> "IFNONNULL"
            GOTO_W -> "GOTO_W"
            JSR_W -> "JSR_W"
            BREAKPOINT -> "BREAKPOINT"
            IMPDEP1 -> "IMPDEP1"
            IMPDEP2 -> "IMPDEP2"
            else -> "UNKNOWN_$opcode"
        }
    }
}