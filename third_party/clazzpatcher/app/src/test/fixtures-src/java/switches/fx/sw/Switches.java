package fx.sw;

/** Несколько switch + fill-array-data разных ширин в одном методе, вложенные switch, switch по строке. */
public class Switches {
    static int multi(int a, int b, char c, String s) {
        int r = 0;
        switch (a) { case 1: r = 10; break; case 2: r = 20; break; case 3: r = 30; break; case 4: r = 40; break; default: r = -1; }
        int[] arr = {7, 8, 9, 10, 11};
        byte[] bytes = {1, -2, 3};
        short[] shorts = {1000, -1000, 7};
        long[] longs = {Long.MIN_VALUE, -1L, 0x123456789ABCDEFL};
        char[] chars = {'a', '\n', 'é'};
        float[] floats = {1.5f, -0.0f, Float.NaN};
        double[] doubles = {10.0, -2.5, Double.MIN_VALUE};
        boolean[] bools = {true, false, true};
        switch (b) { case 100: r += arr[0]; break; case 101: r += arr[1]; break; case 102: r += arr[2]; break; default: r += 5; }
        switch (b) {
            case 5: r *= 2; break;
            case 500: r *= 3; break;
            case 50000: r *= 4; break;
            case Integer.MIN_VALUE: r = 0; break;
            case Integer.MAX_VALUE: r = 1; break;
        }
        switch (c) { case 'x': r ^= bytes[0]; break; case 'y': r ^= shorts[1]; break; case 'z': r ^= chars[2]; break; }
        switch (s) { case "alpha": r += 1; break; case "beta": r += 2; break; case "gamma": r += 3; break; }
        r += (int) longs[a & 1] + (int) floats[0] + (int) doubles[0] + chars.length + (bools[0] ? 1 : 0);
        return r;
    }

    static int nested(int x) {
        int acc = 0;
        for (int i = -3; i < x; i++) {
            switch (i) {
                case -3: acc += 1; break;
                case -2: acc += 2; break;
                case -1:
                    switch (acc) { case 0: acc = 7; break; case 1: acc = 9; break; case 2: acc = 11; break; }
                    break;
                case 0: acc -= 1; break;
                default: acc *= 2;
            }
        }
        return acc;
    }

    static long bigArrays() {
        int[] big = new int[] {
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19,
            20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39,
            -1, -2, -3, Integer.MIN_VALUE, Integer.MAX_VALUE
        };
        byte[] odd = {1, 2, 3, 4, 5, 6, 7};
        long sum = 0;
        for (int v : big) sum += v;
        for (byte v : odd) sum += v;
        return sum;
    }
}
