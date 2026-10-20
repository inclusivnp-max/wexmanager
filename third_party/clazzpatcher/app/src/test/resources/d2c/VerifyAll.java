import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Тестовый харнесс (запускается отдельным процессом, jar с классами — в classpath): загружает
 * каждый класс jar без инициализации и линкует его (getDeclaredMethods), что запускает верификатор
 * HotSpot. Печатает по строке на класс: OK / VERIFY / FORMAT / LINK (нет зависимости) / OTHER.
 */
public class VerifyAll {
    public static void main(String[] args) throws Exception {
        List<String> names = new ArrayList<>();
        try (JarFile jar = new JarFile(args[0])) {
            Enumeration<JarEntry> e = jar.entries();
            while (e.hasMoreElements()) {
                String n = e.nextElement().getName();
                if (n.endsWith(".class")) names.add(n.substring(0, n.length() - 6).replace('/', '.'));
            }
        }
        Collections.sort(names);
        ClassLoader cl = VerifyAll.class.getClassLoader();
        for (String n : names) {
            try {
                Class<?> c = Class.forName(n, false, cl);
                c.getDeclaredMethods();
                c.getDeclaredFields();
                System.out.println("OK " + n);
            } catch (VerifyError e) {
                System.out.println("VERIFY " + n + ": " + oneLine(e));
            } catch (ClassFormatError e) {
                System.out.println("FORMAT " + n + ": " + oneLine(e));
            } catch (LinkageError | ClassNotFoundException e) {
                System.out.println("LINK " + n + ": " + oneLine(e));
            } catch (Throwable e) {
                System.out.println("OTHER " + n + ": " + oneLine(e));
            }
        }
    }

    private static String oneLine(Throwable e) {
        return String.valueOf(e).replace('\n', ' ').replace('\r', ' ');
    }
}
