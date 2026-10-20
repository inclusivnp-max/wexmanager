# Корень цепочки: A.<init>(I), protected-метод и protected-поле.
.class public Lb/A;
.super Ljava/lang/Object;

.field public v:I
.field protected pf:I

.method public constructor <init>(I)V
    .registers 2
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
    iput p1, p0, Lb/A;->v:I
    return-void
.end method

.method protected m()I
    .registers 2
    iget v0, p0, Lb/A;->v:I
    mul-int/lit8 v0, v0, 10
    return v0
.end method

.method public getV()I
    .registers 2
    iget v0, p0, Lb/A;->v:I
    return v0
.end method
