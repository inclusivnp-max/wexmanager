# ART разрешает Sub.<init>()V по иерархии (Base.<init>); в Sub конструктора нет.
.class public LMain;
.super Ljava/lang/Object;
.method public static main([Ljava/lang/String;)V
    .registers 3
    new-instance v0, LSub;
    invoke-direct {v0}, LSub;-><init>()V
    sget-object v1, Ljava/lang/System;->out:Ljava/io/PrintStream;
    const-string v2, "ok"
    invoke-virtual {v1, v2}, Ljava/io/PrintStream;->println(Ljava/lang/String;)V
    return-void
.end method
