package fx.en;

/** Перечисление с конструктором, полями и телами констант (синтетические подклассы). */
public enum Planet {
    MERCURY(3.303e+23, 2.4397e6) {
        @Override String describe() { return "hot"; }
    },
    EARTH(5.976e+24, 6.37814e6) {
        @Override String describe() { return "home"; }
    };

    private final double mass;
    private final double radius;

    Planet(double mass, double radius) {
        this.mass = mass;
        this.radius = radius;
    }

    abstract String describe();

    double surfaceGravity() { return 6.67300E-11 * mass / (radius * radius); }
}
