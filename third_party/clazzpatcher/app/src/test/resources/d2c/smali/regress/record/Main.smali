.class public Lr/Main;
.super Ljava/lang/Object;
.method public static main([Ljava/lang/String;)V
    .registers 5
    sget-object v0, Ljava/lang/System;->out:Ljava/io/PrintStream;
    const-class v1, Lr/P;
    invoke-virtual {v1}, Ljava/lang/Class;->isRecord()Z
    move-result v2
    invoke-virtual {v0, v2}, Ljava/io/PrintStream;->println(Z)V
    invoke-virtual {v1}, Ljava/lang/Class;->getRecordComponents()[Ljava/lang/reflect/RecordComponent;
    move-result-object v1
    const/4 v2, 0
    aget-object v1, v1, v2
    invoke-virtual {v1}, Ljava/lang/reflect/RecordComponent;->getGenericSignature()Ljava/lang/String;
    move-result-object v2
    invoke-virtual {v0, v2}, Ljava/io/PrintStream;->println(Ljava/lang/String;)V
    const-class v2, Lr/Ann;
    invoke-virtual {v1, v2}, Ljava/lang/reflect/RecordComponent;->getAnnotation(Ljava/lang/Class;)Ljava/lang/annotation/Annotation;
    move-result-object v2
    if-eqz v2, :no
    const-string v3, "annotated"
    goto :print
    :no
    const-string v3, "no annotation"
    :print
    invoke-virtual {v0, v3}, Ljava/io/PrintStream;->println(Ljava/lang/String;)V
    const-class v1, Lr/S;
    invoke-virtual {v1}, Ljava/lang/Class;->isSealed()Z
    move-result v2
    invoke-virtual {v0, v2}, Ljava/io/PrintStream;->println(Z)V
    return-void
.end method
