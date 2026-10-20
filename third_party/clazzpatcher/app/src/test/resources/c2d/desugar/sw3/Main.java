package t;
public class Main {
    static String k(Character c) {
        return switch (c) {
            case '가' -> "hangul";
            case Character x when x > 'z' -> "high";
            case Character x -> "low";
        };
    }
    static String n(Integer v) {
        return switch (v) {
            case 100000 -> "lakh";
            case Integer i when i < 0 -> "neg";
            case Integer i -> "n" + i;
        };
    }
    public static void main(String[] a) { System.out.println(k('가') + " " + k('a') + " " + n(100000) + " " + n(-1)); }
}
