.class public super LCl;
.super Ljava/lang/Object;
.version 52

.method public <init>()V
    .limit stack 1
    .limit locals 1
    aload_0
    invokespecial Ljava/lang/Object;-><init>()V
    return
.end method

.method public static id(Ljava/lang/Object;)Ljava/lang/Object;
    .limit stack 1
    .limit locals 1
    aload_0
    areturn
.end method

# Слот 1 дальше не читается, и checkcast приводится прямо в его регистре; приведённое значение лежит
# на стеке через monitorenter по копии того же объекта (слот 2). Передача блокировки копией в регистр
# слота 1 должна сначала вынести ссылку стека в свой регистр — иначе invoke-virtual String.length()
# получает регистр с типом Object (ART: «not instance of String», класс отвергнут).
.method public static test(Ljava/lang/Object;)I
    .limit stack 3
    .limit locals 3
    aload_0
    invokestatic LCl;->id(Ljava/lang/Object;)Ljava/lang/Object;
    astore_1
    aload_1
    astore_2
    aload_1
    checkcast Ljava/lang/String;
    aload_2
    monitorenter
    aload_2
    monitorexit
    invokevirtual Ljava/lang/String;->length()I
    ireturn
.end method

.method public static main([Ljava/lang/String;)V
    .limit stack 2
    .limit locals 1
    getstatic Ljava/lang/System;->out:Ljava/io/PrintStream;
    ldc "abc"
    invokestatic LCl;->test(Ljava/lang/Object;)I
    invokevirtual Ljava/io/PrintStream;->println(I)V
    return
.end method
