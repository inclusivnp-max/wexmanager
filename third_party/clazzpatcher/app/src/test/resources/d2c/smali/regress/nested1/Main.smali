.class public Lp/Main;
.super Ljava/lang/Object;
.method public static main([Ljava/lang/String;)V
    .registers 4
    sget-object v0, Ljava/lang/System;->out:Ljava/io/PrintStream;
    new-instance v1, Lp/O;
    invoke-direct {v1}, Lp/O;-><init>()V
    invoke-virtual {v1}, Lp/O;->anon()Ljava/lang/Object;
    move-result-object v1
    invoke-virtual {v1}, Ljava/lang/Object;->getClass()Ljava/lang/Class;
    move-result-object v1
    invoke-virtual {v1}, Ljava/lang/Class;->getDeclaringClass()Ljava/lang/Class;
    move-result-object v2
    invoke-virtual {v0, v2}, Ljava/io/PrintStream;->println(Ljava/lang/Object;)V
    invoke-virtual {v1}, Ljava/lang/Class;->getEnclosingClass()Ljava/lang/Class;
    move-result-object v2
    invoke-virtual {v0, v2}, Ljava/io/PrintStream;->println(Ljava/lang/Object;)V
    const-class v1, Lp/O$M;
    invoke-virtual {v1}, Ljava/lang/Class;->getDeclaringClass()Ljava/lang/Class;
    move-result-object v2
    invoke-virtual {v0, v2}, Ljava/io/PrintStream;->println(Ljava/lang/Object;)V
    return-void
.end method
