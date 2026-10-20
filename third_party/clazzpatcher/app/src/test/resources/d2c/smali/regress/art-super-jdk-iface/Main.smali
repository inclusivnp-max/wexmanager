.class public LMain;
.super Ljava/lang/Object;

.method public static main([Ljava/lang/String;)V
    .registers 5
    sget-object v0, Ljava/lang/System;->out:Ljava/io/PrintStream;
    new-instance v1, LM;
    invoke-direct {v1}, LM;-><init>()V
    const-string v2, "k"
    const-string v3, "dflt"
    invoke-virtual {v1, v2, v3}, LM;->getOrDefault(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
    move-result-object v4
    invoke-virtual {v0, v4}, Ljava/io/PrintStream;->println(Ljava/lang/Object;)V
    const-string v2, "x"
    invoke-virtual {v1, v2, v3}, LM;->getOrDefault(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
    move-result-object v4
    invoke-virtual {v0, v4}, Ljava/io/PrintStream;->println(Ljava/lang/Object;)V
    return-void
.end method
