.class public super LLe;
.super Ljava/lang/Object;
.version 52

.field n:I

.field acc:I

.method public <init>()V
    .limit stack 1
    .limit locals 1
    aload_0
    invokespecial Ljava/lang/Object;-><init>()V
    return
.end method

.method public static main([Ljava/lang/String;)V
    .limit stack 3
    .limit locals 1
    getstatic Ljava/lang/System;->out:Ljava/io/PrintStream;
    new LLe;
    dup
    invokespecial LLe;-><init>()V
    ldc "abc"
    invokevirtual LLe;->run(Ljava/lang/Object;)I
    invokevirtual Ljava/io/PrintStream;->println(I)V
    return
.end method

# Код начинается с головы цикла (R8: Gmail, Maps): вход блока 0 — слияние с обратной дугой, где слот
# параметра 1 уже int (тип top). Параметры частые и в кадре больше 16 регистров (девять локалов в конце)
# копируются в младшие регистры в прологе — тип для копии берётся из сигнатуры, а не из фрейма блока 0.
.method public run(Ljava/lang/Object;)I
    .limit stack 4
    .limit locals 16
    :loop
    aload_0
    getfield LLe;->n:I
    iconst_3
    if_icmpge :done
    aload_0
    getfield LLe;->n:I
    bipush 10
    imul
    istore_1
    aload_0
    aload_0
    getfield LLe;->acc:I
    iload_1
    iadd
    putfield LLe;->acc:I
    aload_0
    aload_0
    getfield LLe;->n:I
    iconst_1
    iadd
    putfield LLe;->n:I
    goto :loop
    :done
    bipush 1
    istore v0
    bipush 2
    istore v1
    bipush 3
    istore v2
    bipush 4
    istore v3
    bipush 5
    istore v4
    bipush 6
    istore v5
    bipush 7
    istore v6
    bipush 8
    istore v7
    bipush 9
    istore v8
    aload_0
    getfield LLe;->acc:I
    iload v0
    iadd
    iload v1
    iadd
    iload v2
    iadd
    iload v3
    iadd
    iload v4
    iadd
    iload v5
    iadd
    iload v6
    iadd
    iload v7
    iadd
    iload v8
    iadd
    ireturn
.end method
