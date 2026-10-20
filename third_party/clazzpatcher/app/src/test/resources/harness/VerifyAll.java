import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

/**
 * Тестовый харнесс (запускается отдельным процессом JVM): линкует каждый класс из списка,
 * что заставляет HotSpot выполнить верификацию байткода, и печатает итог по классу:
 * OK — верификация пройдена, FAIL — VerifyError/ClassFormatError/IncompatibleClassChangeError,
 * SKIP — класс не удалось слинковать из-за отсутствующих зависимостей.
 * Использует Class.forName/getDeclaredMethods только здесь — это инструмент проверки, не код библиотеки.
 */
public class VerifyAll {
    public static void main(String[] args) throws Exception {
        List<String> names = Files.readAllLines(Paths.get(args[0]));
        ClassLoader loader = VerifyAll.class.getClassLoader();
        for (String name : names) {
            if (name.isEmpty()) continue;
            try {
                Class<?> c = Class.forName(name, false, loader);
                c.getDeclaredMethods(); // link_class → верификация
                System.out.println("OK " + name);
            } catch (VerifyError | ClassFormatError e) {
                System.out.println("FAIL " + name + " " + oneLine(e));
            } catch (IncompatibleClassChangeError e) {
                System.out.println("FAIL " + name + " " + oneLine(e));
            } catch (Throwable e) {
                System.out.println("SKIP " + name + " " + oneLine(e));
            }
        }
    }

    private static String oneLine(Throwable e) {
        return (e.getClass().getName() + ": " + e.getMessage()).replace('\n', ' ');
    }
}
