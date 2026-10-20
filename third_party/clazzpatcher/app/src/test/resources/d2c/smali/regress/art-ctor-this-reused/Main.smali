.class public LMain;
.super Ljava/lang/Object;

.method static make(I)V
    .registers 4
    sget-object v0, Ljava/lang/System;->out:Ljava/io/PrintStream;
    :s
    new-instance v1, LCtor;
    invoke-direct {v1, p0}, LCtor;-><init>(I)V
    iget v2, v1, LCtor;->v:I
    invoke-virtual {v0, v2}, Ljava/io/PrintStream;->println(I)V
    :e
    .catch Ljava/lang/IllegalArgumentException; {:s .. :e} :h
    return-void
    :h
    move-exception v1
    invoke-virtual {v1}, Ljava/lang/Throwable;->getMessage()Ljava/lang/String;
    move-result-object v1
    invoke-virtual {v0, v1}, Ljava/io/PrintStream;->println(Ljava/lang/String;)V
    return-void
.end method

.method public static main([Ljava/lang/String;)V
    .registers 4
    const/4 v0, 0x5
    invoke-static {v0}, LMain;->make(I)V
    const/4 v0, -0x3
    invoke-static {v0}, LMain;->make(I)V
    const/4 v0, 0x0
    invoke-static {v0}, LMain;->make(I)V
    sget-object v0, Ljava/lang/System;->out:Ljava/io/PrintStream;
    new-instance v1, LCtor;
    const/4 v2, 0x2
    const/4 v3, 0x0
    invoke-direct {v1, v2, v3}, LCtor;-><init>(II)V
    iget v2, v1, LCtor;->v:I
    invoke-virtual {v0, v2}, Ljava/io/PrintStream;->println(I)V
    new-instance v1, LCtor;
    const/4 v2, 0x2
    invoke-direct {v1, v2, v2}, LCtor;-><init>(II)V
    iget v2, v1, LCtor;->v:I
    invoke-virtual {v0, v2}, Ljava/io/PrintStream;->println(I)V
    return-void
.end method
