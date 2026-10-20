public class Main {
    static int si = 7;
    static long sl = -3L;
    static float sf = 1.5f;
    static double sd = -2.25;

    static int ops(int a, int b) {
        int r = a + b;
        r ^= a * b;
        r += a - b;
        if (b != 0) r += a / b + a % b;
        r |= a << (b & 31);
        r &= ~(a >>> 3);
        r += a >> 2;
        return r;
    }

    static long lops(long a, long b) {
        long r = a + b;
        r ^= a * b;
        r -= a - b;
        if (b != 0) r += a / b + a % b;
        r |= a << (int) (b & 63);
        r &= ~(a >>> 5);
        r += a >> 7;
        return r;
    }

    static double fops(float a, double b) {
        float f = a * 3.25f - a / 7f + a % 2.5f;
        double d = b * b - b / 3.0 + b % 1.75;
        return f + d + (-f) + Math.sqrt(Math.abs(d));
    }

    static String conv(long v) {
        int i = (int) v;
        short s = (short) v;
        byte b = (byte) v;
        char c = (char) v;
        float f = v;
        double d = v;
        long back = (long) (d * 1.5);
        int fi = (int) (f / 3);
        return i + "," + s + "," + b + "," + (int) c + "," + f + "," + d + "," + back + "," + fi;
    }

    static int cmp(double a, double b) {
        int r = 0;
        if (a < b) r |= 1;
        if (a > b) r |= 2;
        if (a == b) r |= 4;
        if (a <= b) r |= 8;
        if (a >= b) r |= 16;
        if (a != b) r |= 32;
        return r;
    }

    static int fcmp(float a, float b) {
        return (a < b ? 1 : 0) + (a > b ? 2 : 0) + (a == b ? 4 : 0);
    }

    static int lcmp(long a, long b) {
        return (a < b ? 1 : 0) + (a > b ? 2 : 0) + (a == b ? 4 : 0) + (a != b ? 8 : 0);
    }

    static boolean bools(int x) {
        boolean a = x > 3;
        boolean b = (x & 1) == 0;
        return a ^ b || !a && b;
    }

    public static void main(String[] args) {
        int[] ints = {0, 1, -1, 7, -7, 1000, Integer.MAX_VALUE, Integer.MIN_VALUE, 12345678};
        long acc = 0;
        for (int a : ints) for (int b : ints) acc = acc * 31 + ops(a, b);
        System.out.println("int " + acc);
        long[] longs = {0L, 1L, -1L, 1L << 40, Long.MAX_VALUE, Long.MIN_VALUE, 123456789012345L};
        long lacc = 0;
        for (long a : longs) for (long b : longs) lacc = lacc * 17 + lops(a, b);
        System.out.println("long " + lacc);
        double dacc = 0;
        float[] fl = {0f, -0f, 1f, 2.5f, -3.75f, Float.MIN_VALUE, 1e20f};
        double[] db = {0.0, 1.0, -1.5, 1e-10, 3.14159};
        for (float a : fl) for (double b : db) dacc += fops(a, b);
        System.out.println("fp " + dacc);
        for (long v : new long[]{0, 1, -1, 65535, 65536, -129, 1L << 33, Long.MIN_VALUE, 0x7fffffffffL}) System.out.println("conv " + conv(v));
        double[] cm = {0.0, -0.0, 1.0, Double.NaN, Double.POSITIVE_INFINITY};
        StringBuilder sb = new StringBuilder();
        for (double a : cm) for (double b : cm) sb.append(cmp(a, b)).append(' ');
        System.out.println("cmp " + sb);
        System.out.println("fcmp " + fcmp(Float.NaN, 1f) + fcmp(1f, 2f) + fcmp(2f, 2f));
        System.out.println("lcmp " + lcmp(1, 2) + lcmp(Long.MIN_VALUE, Long.MAX_VALUE) + lcmp(5, 5));
        int bc = 0;
        for (int i = 0; i < 10; i++) if (bools(i)) bc++;
        System.out.println("bools " + bc);
        si = si * 3 + (int) sl;
        sl = sl * sl + si;
        sf = sf * sf - 0.25f;
        sd = sd / 3 + sf;
        System.out.println("statics " + si + " " + sl + " " + sf + " " + sd);
        try {
            System.out.println(ints[0] / ints[0]);
        } catch (ArithmeticException e) {
            System.out.println("div0 " + e.getMessage());
        }
        try {
            System.out.println(longs[0] % longs[0]);
        } catch (ArithmeticException e) {
            System.out.println("rem0 " + e.getMessage());
        }
        char ch = 'A';
        ch += 2;
        ch++;
        System.out.println("char " + ch + " " + (char) (ch + 1) + " " + Character.toLowerCase(ch));
        byte by = (byte) 200;
        short sh = (short) 40000;
        System.out.println("narrow " + by + " " + sh + " " + (by & 0xff) + " " + (sh >>> 4));
        System.out.println("math " + Math.max(3, 9) + " " + Math.min(-2L, 5L) + " " + Math.floorMod(-7, 3) + " " + Integer.bitCount(255) + " " + Long.numberOfTrailingZeros(64));
        System.out.println("const " + Float.NaN + " " + (-0.0) + " " + Double.MIN_VALUE + " " + Long.MIN_VALUE + " " + Integer.MIN_VALUE + " " + 0x1234abcd + " " + 3.0e300);
    }
}
