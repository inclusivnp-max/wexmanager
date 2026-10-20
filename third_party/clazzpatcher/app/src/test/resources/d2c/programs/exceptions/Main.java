public class Main {
    static class MyException extends Exception {
        final int code;
        MyException(String m, int code) { super(m); this.code = code; }
    }

    static class Res implements AutoCloseable {
        final String name;
        final StringBuilder log;
        Res(String name, StringBuilder log) { this.name = name; this.log = log; log.append("open:").append(name).append(' '); }
        @Override public void close() throws Exception {
            log.append("close:").append(name).append(' ');
            if (name.equals("bad")) throw new IllegalStateException("close " + name);
        }
    }

    static int finallyReturn(int x) {
        try {
            if (x > 0) return x * 2;
            throw new RuntimeException("neg");
        } catch (RuntimeException e) {
            return -1;
        } finally {
            counter++;
        }
    }

    static int counter;

    static void thrower(int kind) throws Exception {
        switch (kind) {
            case 0: throw new MyException("my", 42);
            case 1: throw new IllegalArgumentException("iae");
            case 2: throw new ArrayIndexOutOfBoundsException(5);
            case 3: Object o = "s"; Integer i = (Integer) o; break;
            case 4: String s = null; s.length(); break;
            case 5: throw new Error("err");
            default: break;
        }
    }

    static String classify(int kind) {
        StringBuilder sb = new StringBuilder();
        try {
            try {
                thrower(kind);
                sb.append("none");
            } catch (MyException e) {
                sb.append("my=").append(e.code);
            } catch (IllegalArgumentException | ArrayIndexOutOfBoundsException e) {
                sb.append("multi=").append(e.getClass().getSimpleName());
            } finally {
                sb.append("|fin");
            }
        } catch (ClassCastException e) {
            sb.append("|cce");
        } catch (NullPointerException e) {
            sb.append("|npe");
        } catch (Throwable t) {
            sb.append("|t=").append(t.getMessage());
        }
        return sb.toString();
    }

    static String resources(boolean bad) {
        StringBuilder log = new StringBuilder();
        try (Res a = new Res("a", log); Res b = new Res(bad ? "bad" : "b", log)) {
            log.append("body ");
            if (bad) throw new RuntimeException("body");
        } catch (Exception e) {
            log.append("caught:").append(e.getMessage());
            for (Throwable s : e.getSuppressed()) log.append(" suppressed:").append(s.getMessage());
        }
        return log.toString();
    }

    static int loopWithTry(int n) {
        int sum = 0;
        for (int i = 0; i < n; i++) {
            try {
                if (i % 3 == 0) throw new IllegalStateException("x" + i);
                sum += 100 / (i % 4);
            } catch (IllegalStateException e) {
                sum += e.getMessage().length();
            } catch (ArithmeticException e) {
                sum -= 7;
            }
        }
        return sum;
    }

    static Object lock = new Object();
    static int shared;

    static synchronized void syncMethod() { shared += 2; }

    static void syncBlock(int n) {
        for (int i = 0; i < n; i++) {
            synchronized (lock) {
                shared++;
                if (i == 3) {
                    try {
                        synchronized (Main.class) { throw new RuntimeException("in sync"); }
                    } catch (RuntimeException e) {
                        shared += 10;
                    }
                }
            }
        }
    }

    static String ctorFail() {
        try {
            new Res(null, new StringBuilder()) {
                { if (name == null) throw new IllegalStateException("null name"); }
            };
            return "ok";
        } catch (NullPointerException | IllegalStateException e) {
            return "ctor:" + e.getClass().getSimpleName();
        }
    }

    public static void main(String[] args) throws Exception {
        for (int k = 0; k < 7; k++) System.out.println("classify " + k + " " + classify(k));
        System.out.println("finally " + finallyReturn(5) + " " + finallyReturn(-5) + " " + counter);
        System.out.println("res " + resources(false));
        System.out.println("res " + resources(true));
        System.out.println("loop " + loopWithTry(20));
        syncMethod();
        syncBlock(6);
        System.out.println("sync " + shared);
        System.out.println(ctorFail());
        try {
            Object[] arr = new String[1];
            arr[0] = Integer.valueOf(1);
        } catch (ArrayStoreException e) {
            System.out.println("ase " + e.getMessage());
        }
    }
}
