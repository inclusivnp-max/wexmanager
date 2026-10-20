# Все опкоды DEX 035 во всех форматах: /from16, /16, /range, /jumbo, goto/16, goto/32, payload'ы в середине
# кода, пустые switch/array-data, граничные литералы. Корректность типов не важна: проверяется только
# структурный верификатор (dexdump). Собирается: smali a --api 15.
.class public Lfx/ops/AllOps;
.super Ljava/lang/Object;
.implements Ljava/lang/Runnable;
.source "AllOps.smali"


# static fields
.field public static sB:B

.field public static sC:C

.field public static sI:I

.field public static sJ:J

.field public static sL:Ljava/lang/Object;

.field public static sS:S

.field public static sZ:Z


# instance fields
.field public iB:B

.field public iC:C

.field public iI:I

.field public iJ:J

.field public iL:Ljava/lang/Object;

.field public iS:S

.field public iZ:Z


# direct methods
.method public constructor <init>()V
    .registers 1

    invoke-direct {p0}, Ljava/lang/Object;-><init>()V

    return-void
.end method

.method public static arrays([I[J[Ljava/lang/Object;[Z[B[C[S)V
    .registers 12

    const/4 v0, 0x0

    aget v1, p0, v0

    aget-wide v2, p1, v0

    aget-object v1, p2, v0

    aget-boolean v1, p3, v0

    aget-byte v1, p4, v0

    aget-char v1, p5, v0

    aget-short v1, p6, v0

    aput v1, p0, v0

    aput-wide v2, p1, v0

    aput-object v1, p2, v0

    aput-boolean v1, p3, v0

    aput-byte v1, p4, v0

    aput-char v1, p5, v0

    aput-short v1, p6, v0

    return-void
.end method

.method public static binops()V
    .registers 16

    add-int v0, v1, v2

    sub-int v0, v1, v2

    mul-int v0, v1, v2

    div-int v0, v1, v2

    rem-int v0, v1, v2

    and-int v0, v1, v2

    or-int v0, v1, v2

    xor-int v0, v1, v2

    shl-int v0, v1, v2

    shr-int v0, v1, v2

    ushr-int v0, v1, v2

    add-long v0, v2, v4

    sub-long v0, v2, v4

    mul-long v0, v2, v4

    div-long v0, v2, v4

    rem-long v0, v2, v4

    and-long v0, v2, v4

    or-long v0, v2, v4

    xor-long v0, v2, v4

    shl-long v0, v2, v4

    shr-long v0, v2, v4

    ushr-long v0, v2, v4

    add-float v0, v1, v2

    sub-float v0, v1, v2

    mul-float v0, v1, v2

    div-float v0, v1, v2

    rem-float v0, v1, v2

    add-double v0, v2, v4

    sub-double v0, v2, v4

    mul-double v0, v2, v4

    div-double v0, v2, v4

    rem-double v0, v2, v4

    add-int/2addr v0, v1

    sub-int/2addr v0, v1

    mul-int/2addr v0, v1

    div-int/2addr v0, v1

    rem-int/2addr v0, v1

    and-int/2addr v0, v1

    or-int/2addr v0, v1

    xor-int/2addr v0, v1

    shl-int/2addr v0, v1

    shr-int/2addr v0, v1

    ushr-int/2addr v0, v1

    add-long/2addr v0, v2

    sub-long/2addr v0, v2

    mul-long/2addr v0, v2

    div-long/2addr v0, v2

    rem-long/2addr v0, v2

    and-long/2addr v0, v2

    or-long/2addr v0, v2

    xor-long/2addr v0, v2

    shl-long/2addr v0, v2

    shr-long/2addr v0, v2

    ushr-long/2addr v0, v2

    add-float/2addr v0, v1

    sub-float/2addr v0, v1

    mul-float/2addr v0, v1

    div-float/2addr v0, v1

    rem-float/2addr v0, v1

    add-double/2addr v0, v2

    sub-double/2addr v0, v2

    mul-double/2addr v0, v2

    div-double/2addr v0, v2

    rem-double/2addr v0, v2

    add-int/lit16 v0, v1, 0x7fff

    rsub-int v0, v1, -0x8000

    mul-int/lit16 v0, v1, 0x10

    div-int/lit16 v0, v1, -0x1

    rem-int/lit16 v0, v1, 0x3

    and-int/lit16 v0, v1, 0xff

    or-int/lit16 v0, v1, 0x100

    xor-int/lit16 v0, v1, -0x100

    add-int/lit8 v0, v1, 0x7f

    rsub-int/lit8 v0, v1, -0x80

    mul-int/lit8 v0, v1, 0x2

    div-int/lit8 v0, v1, 0x3

    rem-int/lit8 v0, v1, 0x4

    and-int/lit8 v0, v1, 0xf

    or-int/lit8 v0, v1, 0x1

    xor-int/lit8 v0, v1, -0x1

    shl-int/lit8 v0, v1, 0x1f

    shr-int/lit8 v0, v1, 0x1

    ushr-int/lit8 v0, v1, 0x2

    return-void
.end method

.method public static branches(IJ)I
    .registers 12

    const/4 v0, 0x0

    if-eq p0, v0, :cond_0

    if-ne p0, v0, :cond_0

    if-lt p0, v0, :cond_0

    if-ge p0, v0, :cond_0

    if-gt p0, v0, :cond_0

    if-le p0, v0, :cond_0

    if-eqz p0, :cond_0

    if-nez p0, :cond_0

    if-ltz p0, :cond_0

    if-gez p0, :cond_0

    if-gtz p0, :cond_0

    if-lez p0, :cond_0

    cmpl-float v1, v2, v3

    cmpg-float v1, v2, v3

    cmpl-double v1, v2, v4

    cmpg-double v1, v2, v4

    cmp-long v1, p1, v4

    goto :goto_0

    :cond_0
    goto/16 :goto_1

    :goto_0
    goto/32 :goto_2

    :goto_1
    packed-switch p0, :pswitch_data_0

    :pswitch_0
    sparse-switch p0, :sswitch_data_0

    :sswitch_0
    packed-switch p0, :pswitch_data_empty

    sparse-switch p0, :sswitch_data_empty

    goto :goto_2

    :pswitch_data_0
    .packed-switch -0x2
        :pswitch_0
        :goto_2
        :pswitch_0
    .end packed-switch

    :sswitch_data_0
    .sparse-switch
        -0x80000000 -> :sswitch_0
        0x0 -> :goto_2
        0x7fffffff -> :sswitch_0
    .end sparse-switch

    :goto_2
    const/4 v0, 0x1

    return v0

    :pswitch_data_empty
    .packed-switch 0x7fffffff
    .end packed-switch

    :sswitch_data_empty
    .sparse-switch
    .end sparse-switch
.end method

.method public static consts()J
    .registers 10

    const/4 v0, -0x8

    const/4 v0, 0x7

    const/16 v0, -0x8000

    const/16 v0, 0x7fff

    const v0, 0x12345678

    const v0, -0x80000000

    const/high16 v0, 0x41200000

    const/high16 v0, -0x80000000

    const/high16 v0, 0x7fff0000

    const-wide/16 v2, -0x8000

    const-wide/16 v2, 0x7fff

    const-wide/32 v2, 0x7fffffff

    const-wide/32 v2, -0x80000000

    const-wide v2, 0x123456789abcdef0L

    const-wide v2, -0x8000000000000000L

    const-wide v2, 0x7fffffffffffffffL

    const-wide/high16 v2, 0x4024000000000000L

    const-wide/high16 v2, -0x8000000000000000L

    const-wide/high16 v2, 0x7ff8000000000000L

    const-string v4, "str\n\"\u0000é"

    const-string/jumbo v4, "jumbo"

    const-class v5, Ljava/lang/String;

    const-class v5, [[I

    return-wide v2
.end method

.method public static data()V
    .registers 4

    const/4 v0, 0x5

    new-array v1, v0, [I

    fill-array-data v1, :array_int

    new-array v1, v0, [B

    fill-array-data v1, :array_byte

    goto :goto_0

    :array_int
    .array-data 4
        0x1
        -0x1
        0x7fffffff
        -0x80000000
        0x0
    .end array-data

    :array_byte
    .array-data 1
        0x1t
        -0x80t
        0x7ft
    .end array-data

    :goto_0
    new-array v1, v0, [S

    fill-array-data v1, :array_short

    new-array v1, v0, [J

    fill-array-data v1, :array_long

    new-array v1, v0, [C

    fill-array-data v1, :array_char

    new-array v1, v0, [Z

    fill-array-data v1, :array_empty

    return-void

    :array_short
    .array-data 2
        0x1s
        -0x8000s
        0x7fffs
    .end array-data

    :array_long
    .array-data 8
        0x1L
        -0x8000000000000000L
    .end array-data

    :array_char
    .array-data 2
        0x61s
        0xe9s
        -0x1s
    .end array-data

    :array_empty
    .array-data 1
    .end array-data
.end method

.method public static fieldops(Lfx/ops/AllOps;)V
    .registers 6

    sget v0, Lfx/ops/AllOps;->sI:I

    sget-wide v1, Lfx/ops/AllOps;->sJ:J

    sget-object v0, Lfx/ops/AllOps;->sL:Ljava/lang/Object;

    sget-boolean v0, Lfx/ops/AllOps;->sZ:Z

    sget-byte v0, Lfx/ops/AllOps;->sB:B

    sget-char v0, Lfx/ops/AllOps;->sC:C

    sget-short v0, Lfx/ops/AllOps;->sS:S

    sput v0, Lfx/ops/AllOps;->sI:I

    sput-wide v1, Lfx/ops/AllOps;->sJ:J

    sput-object v0, Lfx/ops/AllOps;->sL:Ljava/lang/Object;

    sput-boolean v0, Lfx/ops/AllOps;->sZ:Z

    sput-byte v0, Lfx/ops/AllOps;->sB:B

    sput-char v0, Lfx/ops/AllOps;->sC:C

    sput-short v0, Lfx/ops/AllOps;->sS:S

    iget v0, p0, Lfx/ops/AllOps;->iI:I

    iget-wide v1, p0, Lfx/ops/AllOps;->iJ:J

    iget-object v0, p0, Lfx/ops/AllOps;->iL:Ljava/lang/Object;

    iget-boolean v0, p0, Lfx/ops/AllOps;->iZ:Z

    iget-byte v0, p0, Lfx/ops/AllOps;->iB:B

    iget-char v0, p0, Lfx/ops/AllOps;->iC:C

    iget-short v0, p0, Lfx/ops/AllOps;->iS:S

    iput v0, p0, Lfx/ops/AllOps;->iI:I

    iput-wide v1, p0, Lfx/ops/AllOps;->iJ:J

    iput-object v0, p0, Lfx/ops/AllOps;->iL:Ljava/lang/Object;

    iput-boolean v0, p0, Lfx/ops/AllOps;->iZ:Z

    iput-byte v0, p0, Lfx/ops/AllOps;->iB:B

    iput-char v0, p0, Lfx/ops/AllOps;->iC:C

    iput-short v0, p0, Lfx/ops/AllOps;->iS:S

    return-void
.end method

.method public static five(IIIII)V
    .registers 5

    return-void
.end method

.method public static moves()V
    .registers 300

    nop

    move v0, v1

    move/from16 v2, v299

    move/16 v298, v299

    move-wide v0, v2

    move-wide/from16 v4, v296

    move-wide/16 v290, v296

    move-object v0, v1

    move-object/from16 v0, v299

    move-object/16 v299, v0

    invoke-static {}, Lfx/ops/AllOps;->consts()J

    move-result-wide v0

    invoke-static {v0, v1, v2, v3, v4}, Lfx/ops/AllOps;->five(IIIII)V

    invoke-static/range {v295 .. v299}, Lfx/ops/AllOps;->five(IIIII)V

    filled-new-array/range {v290 .. v299}, [I

    move-result-object v0

    return-void
.end method

.method public static objects(Ljava/lang/Object;[I)Ljava/lang/Object;
    .registers 10

    monitor-enter p0

    monitor-exit p0

    check-cast p0, Ljava/lang/String;

    instance-of v0, p0, Ljava/lang/String;

    array-length v1, p1

    new-instance v2, Ljava/lang/Object;

    invoke-direct {v2}, Ljava/lang/Object;-><init>()V

    const/4 v3, 0x3

    new-array v4, v3, [I

    new-array v5, v3, [[Ljava/lang/String;

    filled-new-array {v0, v1, v3}, [I

    move-result-object v4

    filled-new-array {}, [Ljava/lang/Object;

    move-result-object v4

    :try_start_0
    invoke-static {}, Lfx/ops/AllOps;->consts()J
    :try_end_0
    .catch Ljava/lang/RuntimeException; {:try_start_0 .. :try_end_0} :catch_0
    .catch Ljava/lang/Error; {:try_start_0 .. :try_end_0} :catch_1
    .catchall {:try_start_0 .. :try_end_0} :catchall_0

    move-result-wide v6

    return-object v4

    :catch_0
    move-exception v0

    throw v0

    :catch_1
    move-exception v1

    return-object v1

    :catchall_0
    move-exception v8

    throw v8
.end method

.method private priv()V
    .registers 1

    return-void
.end method

.method public static returns(I)I
    .registers 1

    return p0
.end method

.method public static unops()V
    .registers 16

    neg-int v0, v1

    not-int v0, v1

    neg-long v0, v2

    not-long v0, v2

    neg-float v0, v1

    neg-double v0, v2

    int-to-long v0, v2

    int-to-float v0, v1

    int-to-double v0, v2

    long-to-int v0, v2

    long-to-float v0, v2

    long-to-double v0, v2

    float-to-int v0, v1

    float-to-long v0, v2

    float-to-double v0, v2

    double-to-int v0, v2

    double-to-long v0, v2

    double-to-float v0, v2

    int-to-byte v0, v1

    int-to-char v0, v1

    int-to-short v0, v1

    return-void
.end method


# virtual methods
.method public invokes(Ljava/lang/Runnable;)Ljava/lang/String;
    .registers 8

    invoke-virtual {p0}, Ljava/lang/Object;->toString()Ljava/lang/String;

    move-result-object v0

    invoke-super {p0}, Ljava/lang/Object;->hashCode()I

    move-result v1

    invoke-direct {p0}, Lfx/ops/AllOps;->priv()V

    invoke-interface {p1}, Ljava/lang/Runnable;->run()V

    invoke-virtual/range {p0 .. p0}, Ljava/lang/Object;->toString()Ljava/lang/String;

    invoke-super/range {p0 .. p0}, Ljava/lang/Object;->hashCode()I

    invoke-direct/range {p0 .. p0}, Lfx/ops/AllOps;->priv()V

    invoke-static/range {v1 .. v1}, Lfx/ops/AllOps;->returns(I)I

    invoke-interface/range {p1 .. p1}, Ljava/lang/Runnable;->run()V

    return-object v0
.end method

.method public returnsWide()J
    .registers 3

    const-wide/16 v0, 0x1

    return-wide v0
.end method

.method public run()V
    .registers 1

    return-void
.end method
