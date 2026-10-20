public class Main {
    static final int ICONST = 123456;
    static final long LCONST = -9876543210L;
    static final float FCONST = 3.5f;
    static final double DCONST = -0.000123;
    static final String SCONST = "константа ☃ \n tab\t";
    static final char CCONST = 'Ж';
    static final boolean ZCONST = true;
    static final byte BCONST = -5;
    static final short SHCONST = 30000;
    static int mutable = 17;
    static String nothing;

    // Много локальных переменных, чтобы d8 использовал регистры > 15 и > 255.
    static long manyLocals(int seed) {
        long a0 = seed, a1 = a0 * 3 + 1, a2 = a1 ^ a0, a3 = a2 + 7, a4 = a3 * a1, a5 = a4 - a2, a6 = a5 >> 2, a7 = a6 | a3;
        double d0 = a0 * 0.5, d1 = d0 + a1, d2 = d1 * d0, d3 = d2 - a2, d4 = d3 / 3, d5 = d4 + a5, d6 = d5 * 1.1, d7 = d6 - d0;
        int i0 = (int) a7, i1 = i0 + 1, i2 = i1 * i0, i3 = i2 - 5, i4 = i3 ^ i1, i5 = i4 << 3, i6 = i5 >>> 1, i7 = i6 + i2;
        float f0 = i0, f1 = f0 * 2, f2 = f1 - f0, f3 = f2 * f1, f4 = f3 + 0.5f, f5 = f4 / 3, f6 = f5 - f1, f7 = f6 * f6;
        long b0 = a0 + i7, b1 = b0 * a7, b2 = b1 - (long) d7, b3 = b2 ^ (long) f7, b4 = b3 + a1, b5 = b4 * a2, b6 = b5 - a3, b7 = b6 + a4;
        return a0 + a1 + a2 + a3 + a4 + a5 + a6 + a7 + (long) (d0 + d1 + d2 + d3 + d4 + d5 + d6 + d7)
            + i0 + i1 + i2 + i3 + i4 + i5 + i6 + i7 + (long) (f0 + f1 + f2 + f3 + f4 + f5 + f6 + f7)
            + b0 + b1 + b2 + b3 + b4 + b5 + b6 + b7;
    }

    static String callMany(int a, long b, double c, String d, float e, char f, boolean g, byte h, short i, Object j, int k, long l) {
        return a + "|" + b + "|" + c + "|" + d + "|" + e + "|" + f + "|" + g + "|" + h + "|" + i + "|" + j + "|" + k + "|" + l;
    }

    long field = 5;
    double dfield;

    long instanceWork(long x) {
        field += x;
        dfield = field / 2.0;
        return field * 2 + (long) dfield;
    }

    static int nullChecks(String s, Object o) {
        int r = 0;
        if (s == null) r |= 1;
        if (o != null) r |= 2;
        if (s == o) r |= 4;
        String t = s == null ? "d" : s;
        return r + t.length();
    }

    static String concat(Object... parts) {
        StringBuilder sb = new StringBuilder();
        for (Object p : parts) sb.append(p).append(';');
        return sb.toString();
    }

    public static void main(String[] args) {
        System.out.println(ICONST + " " + LCONST + " " + FCONST + " " + DCONST + " " + SCONST + CCONST + ZCONST + BCONST + SHCONST);
        System.out.println("mutable " + mutable + " " + nothing);
        long t = 0;
        for (int s = 0; s < 20; s++) t = t * 7 + manyLocals(s);
        System.out.println("many " + t);
        System.out.println(callMany(1, 2L, 3.0, "4", 5f, '6', true, (byte) 8, (short) 9, null, 11, 12L));
        Main m = new Main();
        long w = 0;
        for (int i = 0; i < 5; i++) w += m.instanceWork(i);
        System.out.println("inst " + w + " " + m.field + " " + m.dfield);
        System.out.println("nulls " + nullChecks(null, null) + " " + nullChecks("abc", "abc") + " " + nullChecks("x", new Object() {
            @Override public String toString() { return "anon"; }
        }));
        System.out.println(concat(1, 2.5, 'c', "s", null, true, 7L, new int[0].length));
        String s = "";
        for (int i = 0; i < 5; i++) s += i;
        System.out.println("concat " + s + " hash " + s.hashCode() + " " + "Aa".hashCode() + " " + Integer.toBinaryString(-8 >>> 28));
    }
}
