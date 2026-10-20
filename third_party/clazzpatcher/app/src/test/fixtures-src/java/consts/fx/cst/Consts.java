package fx.cst;

/** Константы всех примитивных типов (static_values), граничные литералы, строки и символы с экранированием. */
public class Consts {
    public static final boolean Z = true;
    public static final boolean Z0 = false;
    public static final byte B = -128;
    public static final byte B2 = 0x7f;
    public static final byte B0 = 0;
    public static final short S = -32768;
    public static final short S2 = 0x7fff;
    public static final char C = '\n';
    public static final char C2 = 'é';
    public static final char C3 = '￿';
    public static final char C4 = '\u0000';
    public static final char C5 = '\'';
    public static final char C6 = '"';
    public static final char C7 = '\\';
    public static final char C8 = ' ';
    public static final int I = Integer.MIN_VALUE;
    public static final int I2 = 0x7f;
    public static final int I3 = -1;
    public static final int I4 = 0xFF;
    public static final int I5 = Integer.MAX_VALUE;
    public static final long J = Long.MIN_VALUE;
    public static final long J2 = Long.MAX_VALUE;
    public static final long J3 = 0xFFFFFFFFL;
    public static final long J4 = -1L;
    public static final float F = 10.0f;
    public static final float F2 = Float.NaN;
    public static final float F3 = -0.0f;
    public static final float F4 = Float.MIN_VALUE;
    public static final float F5 = Float.POSITIVE_INFINITY;
    public static final float F6 = 0.1f;
    public static final double D = 10.0;
    public static final double D2 = Double.NaN;
    public static final double D3 = -0.0;
    public static final double D4 = Double.MIN_VALUE;
    public static final double D5 = Double.NEGATIVE_INFINITY;
    public static final double D6 = 0.1;
    public static final String STR = "line1\nline2\t\"quoted\" \\ back \r cr é 日本 😀 \u0000 nul \u007f del   ls \u0001";
    public static final String EMPTY = "";
    public static final String LONE_HI = "\ud800";
    public static final String LONE_LO = "x\udc00y";
    public static final String SPECIALS = "﻿￿#;:{}()->@,. /";
    public static final Class<?> CLS = String.class;
    public static final Object NUL = null;
    public static int nonFinal = 5;
    static String nonFinalStr = "x";

    long wide(int x) {
        double d = 10.0;
        float f = 10.0f;
        long a = Long.MIN_VALUE;
        long b = 0x7fL;
        long c = 0x12345678L;
        long e = 0x123456789ABCDEFL;
        long g = -0x80000000L;
        int m = Integer.MIN_VALUE;
        int n = 0x7f;
        int o = 0xFFFF;
        int p = -8;
        int q = 7;
        int big = 0x12345678;
        String s = "esc\n\"\\é\u0000\ud800";
        char ch = 'é';
        char quote = '\'';
        double nd = -2.5e-300;
        float nf = -3.4e38f;
        return a + b + c + e + g + m + n + o + p + q + big + (long) d + (long) f + s.length() + ch + quote + x
            + (long) nd + (long) nf;
    }
}
