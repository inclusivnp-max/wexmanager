import java.util.Arrays;

public class Main {
    static final int[] PRIMES = {2, 3, 5, 7, 11, 13, 17, 19, 23, 29, 31, 37};
    static final long[] LONGS = {1L, -1L, Long.MAX_VALUE, 1L << 50};
    static final double[] DOUBLES = {0.5, -1.25, Double.NaN, 1e300};
    static final float[] FLOATS = {0.5f, -2f, Float.MAX_VALUE};
    static final char[] CHARS = {'h', 'e', 'l', 'l', 'o', 'Ж'};
    static final short[] SHORTS = {-1, 32767, -32768, 12};
    static final byte[] BYTES = {-128, 127, 0, 42};
    static final boolean[] BOOLS = {true, false, true, true};
    static final String[] NAMES = {"x", null, "zz"};

    static int sumBytes(byte[] b) { int s = 0; for (byte x : b) s += x; return s; }

    static int countTrue(boolean[] b) { int c = 0; for (boolean x : b) if (x) c++; return c; }

    static int[][] matrix(int n) {
        int[][] m = new int[n][n + 1];
        for (int i = 0; i < n; i++) for (int j = 0; j <= n; j++) m[i][j] = i * j - j;
        return m;
    }

    static long[][][] cube() {
        long[][][] c = new long[2][3][];
        for (int i = 0; i < 2; i++) for (int j = 0; j < 3; j++) {
            c[i][j] = new long[i + j + 1];
            for (int k = 0; k < c[i][j].length; k++) c[i][j][k] = (long) i << 40 | j << 8 | k;
        }
        return c;
    }

    static Object[] objects() {
        Object[] a = new Object[4];
        a[0] = "s";
        a[1] = new int[]{1, 2};
        a[2] = new String[]{"a"};
        a[3] = a;
        return a;
    }

    static int lengthOf(Object o) {
        if (o instanceof int[]) return ((int[]) o).length;
        if (o instanceof Object[]) return ((Object[]) o).length;
        return -1;
    }

    static char[] chars(boolean upper) {
        char[] c = CHARS.clone();
        for (int i = 0; i < c.length; i++) if (upper) c[i] = Character.toUpperCase(c[i]);
        return c;
    }

    public static void main(String[] args) {
        System.out.println(Arrays.toString(PRIMES) + " " + Arrays.toString(LONGS));
        System.out.println(Arrays.toString(DOUBLES) + " " + Arrays.toString(FLOATS));
        System.out.println(new String(chars(false)) + new String(chars(true)) + " " + Arrays.toString(SHORTS));
        System.out.println(sumBytes(BYTES) + " " + countTrue(BOOLS) + " " + Arrays.toString(NAMES));
        System.out.println(Arrays.deepToString(matrix(4)));
        System.out.println(Arrays.deepToString(cube()));
        Object[] objs = objects();
        int total = 0;
        for (Object o : objs) total += lengthOf(o);
        System.out.println("objs " + total + " " + (objs[3] == objs));
        int[] copy = new int[PRIMES.length * 2];
        System.arraycopy(PRIMES, 0, copy, 3, PRIMES.length);
        System.out.println("copy " + Arrays.toString(copy));
        boolean[] flags = new boolean[5];
        flags[2] = true;
        flags[4] = flags[2] & !flags[1];
        byte[] bs = new byte[3];
        bs[0] = (byte) (bs.length + 250);
        bs[1] = (byte) (bs[0] >> 1);
        short[] ss = {1, 2};
        ss[1] += ss[0] * 1000;
        char[] cc = new char[2];
        cc[0] = 'q';
        cc[1] = (char) (cc[0] + 1);
        float[] ff = new float[2];
        ff[1] = ff[0] - 3.5f;
        double[][] dd = new double[2][];
        dd[1] = new double[]{1, 2, 3};
        System.out.println(Arrays.toString(flags) + Arrays.toString(bs) + Arrays.toString(ss) + new String(cc) + Arrays.toString(ff) + Arrays.deepToString(dd));
        int[] empty = {};
        String[][] grid = new String[2][2];
        grid[1][0] = "g";
        System.out.println("empty " + empty.length + " " + Arrays.deepToString(grid));
        try {
            int[] nul = null;
            System.out.println(nul.length);
        } catch (NullPointerException e) {
            System.out.println("npe");
        }
        try {
            System.out.println(PRIMES[PRIMES.length]);
        } catch (ArrayIndexOutOfBoundsException e) {
            System.out.println("aioobe");
        }
        try {
            int[] neg = new int[PRIMES[0] - 5];
            System.out.println(neg.length);
        } catch (NegativeArraySizeException e) {
            System.out.println("nase");
        }
        long sum = 0;
        int[] big = new int[300];
        for (int i = 0; i < big.length; i++) big[i] = i * i;
        for (int v : big) sum += v;
        System.out.println("big " + sum);
    }
}
