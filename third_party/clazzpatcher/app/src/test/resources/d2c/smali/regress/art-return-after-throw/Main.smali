# ART считает вызов несуществующего метода всегда бросающим: return-void после него недостижим.
.class public LMain;
.super Ljava/lang/Object;
.field static test:I
.method public static main([Ljava/lang/String;)V
    .registers 3
    invoke-static {}, LMain;->softFail()I
    move-result v0
    sget-object v1, Ljava/lang/System;->out:Ljava/io/PrintStream;
    invoke-virtual {v1, v0}, Ljava/io/PrintStream;->println(I)V
    return-void
.end method
.method public static softFail()I
    .registers 1
    sget v0, LMain;->test:I
    if-eqz v0, :zero
    invoke-static {}, LMain;->nonExistentMethod()V
    return-void
    :zero
    return v0
.end method
