# «Сосед» текущего класса c.P: тоже подкласс A, но не суперкласс и не подкласс P.
.class public Lb/S;
.super Lb/A;
.method public constructor <init>()V
    .registers 1
    invoke-direct {p0}, Lb/A;-><init>()V
    return-void
.end method
