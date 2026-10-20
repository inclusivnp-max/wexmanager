# Отладочные директивы, которые javac+d8 не порождают: .prologue/.epilogue, .restart local, .source внутри
# метода, .local с сигнатурой и без имени, несколько .line на одном адресе, большие скачки строк,
# безымянные параметры с аннотациями, .param для wide-параметров. Собирается: smali a --api 15.
.class public abstract Lfx/dd/Dbg;
.super Ljava/lang/Object;
.source "Dbg.java"


# direct methods
.method public constructor <init>()V
    .registers 1

    invoke-direct {p0}, Ljava/lang/Object;-><init>()V

    return-void
.end method

.method public static m(IJLjava/lang/String;D)V
    .registers 10
    .param p0, "a"    # I
    .param p1
        .annotation runtime Lfx/dd/Ann;
            value = "wide"
        .end annotation
    .end param
    .param p3, "s"    # Ljava/lang/String;
    .param p4
        .annotation build Lfx/dd/Ann;
        .end annotation
    .end param

    .prologue
    .line 10
    const/4 v0, 0x1

    .local v0, "x":I
    .line 11
    .line 12
    add-int/lit8 v0, v0, 0x1

    .end local v0
    .line 5
    const/4 v1, 0x0

    .local v1, "list":Ljava/util/List;, "Ljava/util/List<Ljava/lang/String;>;"
    .restart local v0    # "x":I
    .source "Other.java"
    .line 1000000
    nop

    .local v2
    .source "Dbg.java"
    .line 7
    const/4 v2, 0x0

    .end local v2
    .epilogue
    .line 9
    return-void
.end method

.method public namesOnly(ILjava/lang/Object;)V
    .registers 3
    .param p1, "first"    # I
    .param p2, "second"    # Ljava/lang/Object;

    return-void
.end method

.method public unnamedOnly(JI)V
    .registers 4
    .param p1
        .annotation runtime Lfx/dd/Ann;
        .end annotation
    .end param

    .line 3
    return-void
.end method

.method public abstract abs(I)V
.end method

.method public native nat(Ljava/lang/String;)V
.end method
