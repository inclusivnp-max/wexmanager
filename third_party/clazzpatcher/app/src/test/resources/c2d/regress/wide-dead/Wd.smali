.class public super LWd;
.super Ljava/lang/Object;
.version 52

.method public <init>()V
    .limit stack 1
    .limit locals 1
    aload_0
    invokespecial Ljava/lang/Object;-><init>()V
    return
.end method

.method public static main([Ljava/lang/String;)V
    .limit stack 0
    .limit locals 1
    invokestatic LWd;->run()V
    return
.end method

# Без параметров: пара слотов 0–1 — последние регистры кадра. Слот 0 читается (int), и dstore_0 делает
# его парой (0, 1); слот 1 никто не читает (и double в слоте 0 — тоже), но у слота 1 есть регистр — вторая
# половина пары. dstore_1 (пара 1, 2) должна отбрасываться: у слота 2 регистра нет, и пара легла бы за конец
# кадра (difffuzz: «mul-double v12 … использует регистр v13, а в методе всего 13 регистров»).
.method public static run()V
    .limit stack 4
    .limit locals 3
    iconst_3
    istore_0
    getstatic Ljava/lang/System;->out:Ljava/io/PrintStream;
    iload_0
    invokevirtual Ljava/io/PrintStream;->println(I)V
    dconst_1
    dstore_0
    dconst_1
    dconst_1
    dmul
    dstore_1
    dconst_0
    dstore_1
    bipush 7
    istore_0
    getstatic Ljava/lang/System;->out:Ljava/io/PrintStream;
    iload_0
    invokevirtual Ljava/io/PrintStream;->println(I)V
    return
.end method
