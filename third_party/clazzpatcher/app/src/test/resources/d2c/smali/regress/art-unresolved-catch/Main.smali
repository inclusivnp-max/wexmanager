# Тип обработчика не существует: ART такой обработчик просто не срабатывает.
.class public LMain;
.super Ljava/lang/Object;
.method public static main([Ljava/lang/String;)V
    .registers 3
    :try_start
    sget-object v1, Ljava/lang/System;->out:Ljava/io/PrintStream;
    const-string v2, "ok"
    invoke-virtual {v1, v2}, Ljava/io/PrintStream;->println(Ljava/lang/String;)V
    :try_end
    .catch Ljava/lang/ArithmException; {:try_start .. :try_end} :handler
    return-void
    :handler
    return-void
.end method
