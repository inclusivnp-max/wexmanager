# Цепочка проходит через класс вне DEX (ArrayList): переходник невозможен.
.class public Lb/E;
.super Ljava/util/ArrayList;

.method public static make()Lb/E;
    .registers 1
    new-instance v0, Lb/E;
    invoke-direct {v0}, Ljava/util/AbstractList;-><init>()V
    return-object v0
.end method
