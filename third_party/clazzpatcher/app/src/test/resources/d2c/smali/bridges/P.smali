# Другой пакет: protected-члены A на объекте не своего типа (делегирование после переупаковки R8).
.class public Lc/P;
.super Lb/A;

.method public constructor <init>()V
    .registers 2
    const/4 v0, 1
    invoke-direct {p0, v0}, Lb/A;-><init>(I)V
    return-void
.end method

.method public static peek(Lb/A;)I
    .registers 3
    invoke-virtual {p0}, Lb/A;->m()I
    move-result v0
    const/4 v1, 3
    iput v1, p0, Lb/A;->pf:I
    iget v1, p0, Lb/A;->pf:I
    add-int/2addr v0, v1
    return v0
.end method
