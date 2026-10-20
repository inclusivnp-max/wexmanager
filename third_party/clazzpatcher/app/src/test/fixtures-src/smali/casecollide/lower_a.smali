# Классы, чьи имена файлов совпадают без учёта регистра: La; / LA; и каталоги fx/cc / fx/CC.
.class public Lfx/cc/a;
.super Ljava/lang/Object;
.source "a.java"


# direct methods
.method public static f()I
    .registers 1

    invoke-static {}, Lfx/cc/A;->f()I

    move-result v0

    add-int/lit8 v0, v0, 0x1

    return v0
.end method
