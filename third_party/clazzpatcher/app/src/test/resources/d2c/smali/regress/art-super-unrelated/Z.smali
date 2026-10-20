# Из art/test/594-invoke-super: invoke-super по ссылке на класс, который не предок Z, — NoSuchMethodError.
.class public LZ;
.super LA;
.method public constructor <init>()V
    .registers 1
    invoke-direct {p0}, LA;-><init>()V
    return-void
.end method
.method public foo()V
    .registers 2
    new-instance v0, LY;
    invoke-direct {v0}, LY;-><init>()V
    invoke-super {v0}, LY;->foo()V
    return-void
.end method
.method public static main([Ljava/lang/String;)V
    .registers 3
    sget-object v1, Ljava/lang/System;->out:Ljava/io/PrintStream;
    :try_start
    new-instance v0, LZ;
    invoke-direct {v0}, LZ;-><init>()V
    invoke-virtual {v0}, LZ;->foo()V
    :try_end
    .catch Ljava/lang/NoSuchMethodError; {:try_start .. :try_end} :nsme
    const-string v0, "no error"
    invoke-virtual {v1, v0}, Ljava/io/PrintStream;->println(Ljava/lang/String;)V
    return-void
    :nsme
    const-string v0, "NoSuchMethodError"
    invoke-virtual {v1, v0}, Ljava/io/PrintStream;->println(Ljava/lang/String;)V
    return-void
.end method
