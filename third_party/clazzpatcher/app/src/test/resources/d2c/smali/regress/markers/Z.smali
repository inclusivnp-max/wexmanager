# new Z + Y.<init>(Void) (1 шаг) и new Z + X.<init>() (2 шага): с маркером Void оба переходника Z были бы (Void, Void).
.class public Lv/Z;
.super Lv/Y;
.method public constructor <init>()V
    .registers 2
    const/4 v0, 0
    invoke-direct {p0, v0}, Lv/Y;-><init>(Ljava/lang/Void;)V
    return-void
.end method
.method public static main([Ljava/lang/String;)V
    .registers 3
    sget-object v2, Ljava/lang/System;->out:Ljava/io/PrintStream;
    new-instance v0, Lv/Z;
    const/4 v1, 0
    invoke-direct {v0, v1}, Lv/Y;-><init>(Ljava/lang/Void;)V
    invoke-virtual {v2, v0}, Ljava/io/PrintStream;->println(Ljava/lang/Object;)V
    new-instance v0, Lv/Z;
    invoke-direct {v0}, Lv/X;-><init>()V
    invoke-virtual {v2, v0}, Ljava/io/PrintStream;->println(Ljava/lang/Object;)V
    return-void
.end method
.method public toString()Ljava/lang/String;
    .registers 2
    const-string v0, "Z"
    return-object v0
.end method
