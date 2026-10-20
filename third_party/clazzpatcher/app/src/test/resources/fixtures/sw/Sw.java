public class Sw {
    static int f(int a, int b) {
        int r = 0;
        switch (a) { case 1: r = 10; break; case 2: r = 20; break; case 3: r = 30; break; case 4: r = 40; break; default: r = -1; }
        int[] arr = {7, 8, 9, 10, 11};
        switch (b) { case 100: r += arr[0]; break; case 101: r += arr[1]; break; case 102: r += arr[2]; break; default: r += 5; }
        switch (b) { case 5: r *= 2; break; case 500: r *= 3; break; case 50000: r *= 4; break; }
        return r;
    }
    public static void main(String[] args) { System.out.println(f(Integer.parseInt(args[0]), Integer.parseInt(args[1]))); }
}
