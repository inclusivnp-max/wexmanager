.class public abstract interface LItf;
.super Ljava/lang/Object;
.method public bar()V
    .registers 3
    sget-object v0, Ljava/lang/System;->out:Ljava/io/PrintStream;
    const-string v1, "Itf.bar"
    invoke-virtual {v0, v1}, Ljava/io/PrintStream;->println(Ljava/lang/String;)V
    return-void
.end method
