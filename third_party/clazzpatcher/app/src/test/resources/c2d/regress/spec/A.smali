.class public super LA;
.super Ljava/lang/Object;
.version 52

.method public <init>()V
    .limit stack 1
    .limit locals 1
    aload_0
    invokespecial Ljava/lang/Object;-><init>()V
    return
.end method

.method public m()Ljava/lang/String;
    .limit stack 1
    .limit locals 1
    ldc "A.m"
    areturn
.end method

# invokespecial на собственный не-private метод: без виртуального выбора
.method public callDirect()Ljava/lang/String;
    .limit stack 1
    .limit locals 1
    aload_0
    invokespecial LA;->m()Ljava/lang/String;
    areturn
.end method

.method public static main([Ljava/lang/String;)V
    .limit stack 3
    .limit locals 1
    getstatic Ljava/lang/System;->out:Ljava/io/PrintStream;
    new LB;
    dup
    invokespecial LB;-><init>()V
    invokevirtual LA;->callDirect()Ljava/lang/String;
    invokevirtual Ljava/io/PrintStream;->println(Ljava/lang/String;)V
    return
.end method
