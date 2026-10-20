.class public Lb/A;
.super Ljava/lang/Object;
.field protected pf:I
.method public constructor <init>()V
    .registers 2
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
    const/4 v0, 5
    iput v0, p0, Lb/A;->pf:I
    return-void
.end method
.method protected m()I
    .registers 2
    const/16 v0, 42
    return v0
.end method
