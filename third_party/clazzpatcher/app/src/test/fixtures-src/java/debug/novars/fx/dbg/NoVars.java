package fx.dbg;

/** Компилируется с -g:source,lines: номера строк есть, имён параметров нет (безымянные параметры в debug_info). */
public class NoVars {
    public NoVars(int a, String b) {
        System.out.println(a + b);
    }

    static long sum(long a, int b, double c) {
        long r = a + b;
        r += (long) c;
        return r;
    }

    String instance(int x, long y, Object z) {
        return "" + x + y + z;
    }
}
