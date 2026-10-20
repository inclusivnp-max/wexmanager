package fx.dbg;

import java.util.ArrayList;
import java.util.List;

/** Компилируется с -g: имена параметров, локальные переменные (в т.ч. с generic-сигнатурой), области видимости. */
public class WithVars {
    long field;

    public WithVars(long start, String name) {
        this.field = start + name.length();
    }

    static double mix(int a, long b, double c, String d, float e) {
        double r = a + b;
        {
            int scoped = (int) c;
            r += scoped;
        }
        {
            long other = b * 2;
            r -= other;
        }
        List<String> list = new ArrayList<>();
        list.add(d);
        for (String s : list) {
            r += s.length() + e;
        }
        return r;
    }

    int instance(long wide, int narrow, double wide2, boolean flag) {
        int result = narrow;
        if (flag) {
            String tmp = "t" + wide;
            result += tmp.length();
        } else {
            char ch = 'c';
            result += ch;
        }
        return result + (int) wide2;
    }
}
