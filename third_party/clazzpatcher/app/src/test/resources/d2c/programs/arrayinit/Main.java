import java.util.Arrays;

/** Инициализаторы массивов из констант (c2d: fill-array-data) — все типы элементов и граничные значения. */
public class Main {
    static final int[] TABLE = {0, 1, -1, 7, 8, -8, -9, 127, 128, -128, -129, 32767, 32768, -32768, -32769,
        0x10000, 0x7FFFFFFF, 0x80000000, 0xDEADBEEF, 65536 * 3};
    static final long[] LONGS = {0L, 1L, -1L, 32767L, -32768L, 0x7FFFFFFFL, 0x80000000L, -0x80000001L,
        Long.MAX_VALUE, Long.MIN_VALUE, 0x1234000000000000L, 0xCAFEBABEDEADBEEFL};
    static final byte[] BYTES = {0, 1, -1, 127, -128, (byte) 200, (byte) 0x80, 42};
    static final short[] SHORTS = {0, 1, -1, 32767, -32768, (short) 40000, 12345};
    static final char[] CHARS = {'a', 'Z', '0', '\u0000', '￿', 'Ж', '€', '\n'};
    static final boolean[] BOOLS = {true, false, true, true, false, false, true};
    static final float[] FLOATS = {0f, 1f, 2f, -0f, 0.5f, -3.25f, Float.NaN, Float.POSITIVE_INFINITY,
        Float.NEGATIVE_INFINITY, Float.MIN_VALUE, Float.MAX_VALUE, 1e-10f};
    static final double[] DOUBLES = {0d, 1d, -0d, 0.1, -2.5e300, Double.NaN, Double.POSITIVE_INFINITY,
        Double.MIN_VALUE, Double.MAX_VALUE, Math.PI};

    static int sum(int[] a) { int s = 0; for (int v : a) s = 31 * s + v; return s; }

    static int[] partial(int x) {
        // Неконстантный элемент посередине: префикс — fill-array-data, остальное — поэлементно.
        return new int[]{10, 20, 30, 40, x, 60, 70, 80};
    }

    static int[][] nested() {
        return new int[][]{{1, 2, 3, 4}, {5, 6, 7, 8, 9}, {}, {-1}};
    }

    static long[] inTry(int n) {
        try {
            long[] a = {5L, 6L, 7L, 8L, 9L};
            return Arrays.copyOf(a, n);
        } catch (NegativeArraySizeException e) {
            return new long[]{-1L, -2L, -3L, -4L};
        }
    }

    static int[] big() {
        return new int[]{
            1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30,
            31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51, 52, 53, 54, 55, 56, 57, 58,
            59, 60, 61, 62, 63, 64, 65, 66, 67, 68, 69, 70, 71, 72, 73, 74, 75, 76, 77, 78, 79, 80, 81, 82, 83, 84, 85, 86,
            87, 88, 89, 90, 91, 92, 93, 94, 95, 96, 97, 98, 99, 100, 101, 102, 103, 104, 105, 106, 107, 108, 109, 110, 111,
            112, 113, 114, 115, 116, 117, 118, 119, 120, 121, 122, 123, 124, 125, 126, 127, 128, 129, 130, 131, 132, 133,
            134, 135, 136, 137, 138, 139, 140, 141, 142, 143, 144, 145, 146, 147, 148, 149, 150, 151, 152, 153, 154, 155,
            156, 157, 158, 159, 160, 161, 162, 163, 164, 165, 166, 167, 168, 169, 170, 171, 172, 173, 174, 175, 176, 177,
            178, 179, 180, 181, 182, 183, 184, 185, 186, 187, 188, 189, 190, 191, 192, 193, 194, 195, 196, 197, 198, 199, -200
        };
    }

    public static void main(String[] args) {
        System.out.println(Arrays.toString(TABLE));
        System.out.println(Arrays.toString(LONGS));
        System.out.println(Arrays.toString(BYTES));
        System.out.println(Arrays.toString(SHORTS));
        StringBuilder sb = new StringBuilder();
        for (char c : CHARS) sb.append((int) c).append(' ');
        System.out.println(sb);
        System.out.println(Arrays.toString(BOOLS));
        for (float f : FLOATS) System.out.print(Float.floatToRawIntBits(f) + " ");
        System.out.println();
        for (double d : DOUBLES) System.out.print(Double.doubleToRawLongBits(d) + " ");
        System.out.println();
        System.out.println(Arrays.toString(partial(args.length + 50)));
        System.out.println(Arrays.deepToString(nested()));
        System.out.println(Arrays.toString(inTry(3)) + " " + Arrays.toString(inTry(-1)));
        System.out.println(big().length + " " + sum(big()));
        byte[] small = {1, 2};
        char[] one = {'x'};
        System.out.println(small.length + " " + one[0]);
    }
}
