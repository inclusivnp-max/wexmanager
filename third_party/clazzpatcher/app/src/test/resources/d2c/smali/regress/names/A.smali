# protected-метод get$x() и protected-поле x: методы доступа к ним не должны совпасть по имени.
.class public Lb/A;
.super Ljava/lang/Object;
.field protected x:I
.method public constructor <init>()V
    .registers 1
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
    return-void
.end method
.method protected get$x()I
    .registers 2
    const/4 v0, 1
    return v0
.end method
