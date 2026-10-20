# Из art/test/066-mismatched-super: суперкласс — интерфейс. Переходники в интерфейс не добавляются
# (ClassFormatError); вызов остаётся как есть — ошибку даст загрузка класса, как и в ART.
.class public LSub;
.super LDefs;
.method public constructor <init>()V
    .registers 1
    invoke-direct {p0}, LDefs;-><init>()V
    return-void
.end method
