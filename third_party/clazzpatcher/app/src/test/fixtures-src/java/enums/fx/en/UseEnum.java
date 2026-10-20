package fx.en;

import java.util.EnumMap;
import java.util.EnumSet;

/** switch по enum (синтетический $SwitchMap$), EnumSet/EnumMap. */
public class UseEnum {
    static int f(Color c) {
        switch (c) {
            case RED: return 1;
            case GREEN: return 2;
            default: return 3;
        }
    }

    static int g(Planet p) {
        switch (p) {
            case EARTH: return 10;
            case MERCURY: return 20;
        }
        return 0;
    }

    static int all() {
        EnumSet<Color> set = EnumSet.allOf(Color.class);
        EnumMap<Planet, String> map = new EnumMap<>(Planet.class);
        for (Planet p : Planet.values()) map.put(p, p.describe());
        return set.size() + map.size() + Color.valueOf("RED").ordinal();
    }
}
