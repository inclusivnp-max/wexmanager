# invoke-super на метод своего класса (ART: поиск начинается с суперкласса) и на default-метод
# непрямого суперинтерфейса (Itf реализует Base, а не Sub).
.class public LSub;
.super LBase;
.method public constructor <init>()V
    .registers 1
    invoke-direct {p0}, LBase;-><init>()V
    return-void
.end method
.method public foo()V
    .registers 1
    invoke-super {p0}, LSub;->foo()V
    return-void
.end method
.method public bar()V
    .registers 1
    invoke-super {p0}, LItf;->bar()V
    return-void
.end method
