package fx.tc;

import java.io.IOException;
import java.io.StringReader;

/** try/catch/finally, multi-catch, вложенные try, synchronized, try-with-resources, native/abstract/default методы. */
public class Tc {
    static int f(String s) {
        int r = 0;
        try {
            r = Integer.parseInt(s);
        } catch (NumberFormatException | NullPointerException e) {
            r = -1;
        } catch (RuntimeException e) {
            r = -2;
        } finally {
            r += 100;
        }
        try {
            synchronized (Tc.class) {
                r++;
            }
        } catch (Throwable t) {
            r = -3;
        }
        try (StringReader rd = new StringReader(s)) {
            r += rd.read();
        } catch (IOException e) {
            r = -4;
        }
        try {
            try {
                throw new IllegalStateException("inner");
            } catch (IllegalStateException e) {
                throw new RuntimeException(e);
            }
        } catch (Exception e) {
            r ^= 1;
        } finally {
            r ^= 2;
        }
        return r;
    }

    static void loopWithTry(int[] xs) {
        for (int i = 0; i < xs.length; i++) {
            try {
                xs[i] = 100 / xs[i];
            } catch (ArithmeticException e) {
                xs[i] = 0;
                continue;
            }
            if (xs[i] > 5) break;
        }
    }

    synchronized void syncMethod() {
        field++;
    }

    native void nativeMethod(long a, Object b);

    private volatile int field;
    private transient Object cache;

    abstract static class Abs {
        abstract void m();

        protected abstract int n(int x);
    }

    interface I {
        default int d() {
            return 1;
        }

        static int s() {
            return 2;
        }

        int abs();
    }
}
