package fx.dbg;

/** Компилируется с -g:none: без отладочной информации вообще. */
public class NoDebug {
    static int f(int a, int b) {
        int r = a * b;
        if (r > 10) {
            r -= a;
        }
        return r;
    }

    int g(long x) {
        return (int) (x >>> 3);
    }
}
