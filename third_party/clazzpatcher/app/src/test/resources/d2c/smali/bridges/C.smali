# Конструктор после встраивания R8: A.<init> на this в обход прямого суперкласса B.
.class public Lb/C;
.super Lb/B;

.method public constructor <init>()V
    .registers 2
    const/4 v0, 7
    invoke-direct {p0, v0}, Lb/A;-><init>(I)V
    return-void
.end method
