.class public Lc/P;
.super Lb/A;
.method public constructor <init>()V
    .registers 1
    invoke-direct {p0}, Lb/A;-><init>()V
    return-void
.end method
.method public static peek(Lb/A;)I
    .registers 3
    invoke-virtual {p0}, Lb/A;->get$x()I
    move-result v0
    iget v1, p0, Lb/A;->x:I
    add-int/2addr v0, v1
    return v0
.end method
.method public static main([Ljava/lang/String;)V
    .registers 3
    new-instance v0, Lb/A;
    invoke-direct {v0}, Lb/A;-><init>()V
    invoke-static {v0}, Lc/P;->peek(Lb/A;)I
    move-result v0
    sget-object v1, Ljava/lang/System;->out:Ljava/io/PrintStream;
    invoke-virtual {v1, v0}, Ljava/io/PrintStream;->println(I)V
    return-void
.end method
