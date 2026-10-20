import java.util.ArrayList;
import java.util.List;

public class Main {
    enum Color { RED, GREEN, BLUE, BLACK }

    static int dense(int x) {
        switch (x) {
            case 0: return 10;
            case 1: return 11;
            case 2: return 12;
            case 3: return 13;
            case 4: return 14;
            case 6: return 16;
            default: return -1;
        }
    }

    static String sparse(int x) {
        switch (x) {
            case -1000000: return "neg";
            case 7: return "seven";
            case 1 << 20: return "mega";
            case Integer.MAX_VALUE: return "max";
            default: return "other";
        }
    }

    static int strings(String s) {
        switch (s) {
            case "alpha": return 1;
            case "beta": return 2;
            case "Aa": return 3;
            case "BB": return 4; // та же hashCode, что у "Aa"
            default: return 0;
        }
    }

    static String colors(Color c) {
        switch (c) {
            case RED: return "r";
            case BLUE: return "b";
            default: return "?" + c.ordinal();
        }
    }

    static int loops(int n) {
        int sum = 0;
        outer:
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (j > i) continue outer;
                if (i * j > 50) break outer;
                sum += i ^ j;
            }
        }
        int k = 0;
        do {
            sum += k;
            k += 3;
        } while (k < n * 2);
        while (sum > 1000) sum /= 2;
        return sum;
    }

    static long fib(int n) { return n < 2 ? n : fib(n - 1) + fib(n - 2); }

    static int ack(int m, int n) {
        if (m == 0) return n + 1;
        if (n == 0) return ack(m - 1, 1);
        return ack(m - 1, ack(m, n - 1));
    }

    static int ternary(int a, int b, boolean f) {
        int r = f ? a : b;
        long w = a > b ? (long) a * 3 : b;
        double d = f ? 1.5 : a;
        return r + (int) w + (int) (d * 2);
    }

    static Object pick(int i) {
        Object o;
        if (i % 3 == 0) o = "str" + i;
        else if (i % 3 == 1) o = Integer.valueOf(i);
        else o = null;
        return o;
    }

    public static void main(String[] args) {
        StringBuilder sb = new StringBuilder();
        for (int i = -1; i < 8; i++) sb.append(dense(i)).append(',');
        System.out.println("dense " + sb);
        for (int v : new int[]{-1000000, 7, 1 << 20, Integer.MAX_VALUE, 8}) System.out.println("sparse " + sparse(v));
        for (String s : new String[]{"alpha", "beta", "Aa", "BB", "gamma"}) System.out.println("str " + strings(s));
        for (Color c : Color.values()) System.out.println("enum " + colors(c) + " " + c.name());
        System.out.println("loops " + loops(12) + " " + loops(3));
        System.out.println("fib " + fib(20));
        System.out.println("ack " + ack(2, 3));
        System.out.println("ternary " + ternary(3, 5, true) + " " + ternary(9, 2, false));
        List<Object> list = new ArrayList<>();
        for (int i = 0; i < 7; i++) list.add(pick(i));
        System.out.println("pick " + list);
        int count = 0;
        for (Object o : list) {
            if (o instanceof String) count += ((String) o).length();
            else if (o instanceof Integer) count += (Integer) o;
            else count -= 1;
        }
        System.out.println("count " + count);
    }
}
