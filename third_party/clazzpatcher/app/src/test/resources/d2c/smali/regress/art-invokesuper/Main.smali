.class public LMain;
.super Ljava/lang/Object;
.method public static main([Ljava/lang/String;)V
    .registers 2
    new-instance v0, LSub;
    invoke-direct {v0}, LSub;-><init>()V
    invoke-virtual {v0}, LSub;->foo()V
    invoke-virtual {v0}, LSub;->bar()V
    return-void
.end method
