# static_values от d8: поля до последней настоящей константы дополнены нулями.
.class public Lk/K;
.super Ljava/lang/Object;

# static final int a = Integer.getInteger("nope", 42) — вычисляется в <clinit>, нуль в static_values — заглушка.
.field public static final a:I = 0x0
# static final int z = 0 — настоящая константа (в <clinit> не присваивается).
.field public static final z:I = 0x0
# static int c; — не final, нуль — значение по умолчанию.
.field public static c:I = 0x0
# static final int b = 5.
.field public static final b:I = 0x5

.method static constructor <clinit>()V
    .registers 2
    const-string v0, "nope"
    const/16 v1, 42
    invoke-static {v0, v1}, Ljava/lang/Integer;->getInteger(Ljava/lang/String;I)Ljava/lang/Integer;
    move-result-object v0
    invoke-virtual {v0}, Ljava/lang/Integer;->intValue()I
    move-result v0
    sput v0, Lk/K;->a:I
    return-void
.end method
