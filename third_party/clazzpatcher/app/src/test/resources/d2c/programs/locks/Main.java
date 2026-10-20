// Вложенные synchronized на одном объекте в методах с большим числом регистров:
// this и параметры лежат выше v15, и инструкциям с 4-битными полями нужны копии ссылок.
// Верификатор ART переносит признак «держит монитор» на копии — копия, снятая до повторного
// захвата того же объекта, не должна дожить до слияния путей.
public class Main {
    int a, b, c;
    final Object other = new Object();
    static int counter;

    int nested(int x, int y) {
        int l0 = x + 1, l1 = x + 2, l2 = x + 3, l3 = x + 4, l4 = x + 5, l5 = x + 6, l6 = x + 7, l7 = x + 8;
        int l8 = y + 1, l9 = y + 2, l10 = y + 3, l11 = y + 4, l12 = y + 5, l13 = y + 6;
        synchronized (this) {
            a = l0 + l1;
            if (x > 0) {
                synchronized (this) {
                    b = a + l2 + l3;
                    if (y > 5) {
                        synchronized (other) {
                            c = b + l4;
                        }
                    }
                }
            } else {
                synchronized (other) {
                    c = l5 + l6;
                }
            }
            a += l7 + l8 + l9 + l10 + l11 + l12 + l13;
        }
        return a + b + c;
    }

    int withThrow(int x) {
        int l0 = x, l1 = x * 2, l2 = x * 3, l3 = x * 4, l4 = x * 5, l5 = x * 6, l6 = x * 7, l7 = x * 8;
        int l8 = x * 9, l9 = x * 10, l10 = x * 11, l11 = x * 12;
        try {
            synchronized (this) {
                a = l0 + l1 + l2;
                synchronized (this) {
                    if (x == 3) throw new IllegalStateException("x=" + x);
                    b = l3 + l4 + l5 + l6;
                }
                c = l7 + l8 + l9 + l10 + l11;
            }
        } catch (IllegalStateException e) {
            return -1 - (Thread.holdsLock(this) ? 1000 : 0);
        }
        return a + b + c + (Thread.holdsLock(this) ? 1000 : 0);
    }

    static synchronized void incr(int n) {
        for (int i = 0; i < n; i++) {
            synchronized (Main.class) {
                synchronized (Main.class) { counter++; }
            }
        }
    }

    public static void main(String[] args) {
        Main m = new Main();
        System.out.println(m.nested(1, 7));
        System.out.println(m.nested(-1, 0));
        System.out.println(m.nested(2, 3));
        System.out.println(m.withThrow(2));
        System.out.println(m.withThrow(3));
        incr(5);
        System.out.println(counter);
        System.out.println(Thread.holdsLock(m));
    }
}
