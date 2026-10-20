# Регистр this переиспользуется до вызова super() (так делает R8): на путях, где конструктор
# бросает исключение, this больше нигде не лежит. В JVM у слияния этих путей фрейм без
# uninitializedThis теряет flagThisUninit — HotSpot отверг бы метод; слот 0 обязан держать this.
.class public LCtor;
.super Ljava/lang/Object;

.field public v:I

.method public constructor <init>(I)V
    .registers 4
    if-lez p1, :bad
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
    iput p1, p0, LCtor;->v:I
    return-void
    :bad
    const/4 p0, 0x0
    if-eqz p1, :zero
    const/4 p0, 0x1
    :zero
    new-instance v0, Ljava/lang/IllegalArgumentException;
    invoke-static {p0}, Ljava/lang/String;->valueOf(I)Ljava/lang/String;
    move-result-object v1
    invoke-direct {v0, v1}, Ljava/lang/IllegalArgumentException;-><init>(Ljava/lang/String;)V
    throw v0
.end method

# Обработчик до super(), в котором регистр this занят исключением.
.method public constructor <init>(II)V
    .registers 6
    move-object v0, p0
    :try_start
    const/16 v2, 0xa
    div-int v2, v2, p2
    :try_end
    .catch Ljava/lang/ArithmeticException; {:try_start .. :try_end} :handler
    invoke-direct {v0}, Ljava/lang/Object;-><init>()V
    iput v2, v0, LCtor;->v:I
    return-void
    :handler
    move-exception p0
    invoke-direct {v0}, Ljava/lang/Object;-><init>()V
    const/4 v2, -0x1
    iput v2, v0, LCtor;->v:I
    return-void
.end method
