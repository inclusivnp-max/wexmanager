.class public super LSt;
.super Ljava/lang/Object;
.version 52

.method public <init>()V
    .limit stack 1
    .limit locals 1
    aload_0
    invokespecial Ljava/lang/Object;-><init>()V
    return
.end method

# печать int
.method static p(I)V
    .limit stack 2
    .limit locals 1
    getstatic Ljava/lang/System;->out:Ljava/io/PrintStream;
    iload_0
    invokevirtual Ljava/io/PrintStream;->println(I)V
    return
.end method

.method static pl(J)V
    .limit stack 3
    .limit locals 2
    getstatic Ljava/lang/System;->out:Ljava/io/PrintStream;
    lload_0
    invokevirtual Ljava/io/PrintStream;->println(J)V
    return
.end method

# dup_x1: a b -> b a b ; результат 3*100+1*10+3 = 313 при a=1,b=3
.method static dupx1()I
    .limit stack 4
    .limit locals 0
    iconst_1
    iconst_3
    dup_x1
    bipush 10
    imul
    swap
    bipush 100
    imul
    iadd
    iadd
    ireturn
.end method

# dup_x2 form1: a b c -> c a b c (все cat1)
.method static dupx2a()I
    .limit stack 5
    .limit locals 0
    iconst_1
    iconst_2
    iconst_3
    dup_x2
    # стек: 3 1 2 3
    bipush 10
    imul
    iadd
    # 3 1 32
    bipush 10
    imul
    iadd
    # 3 321 — нет: 1*? проверим итог
    swap
    sipush 1000
    imul
    iadd
    ireturn
.end method

# dup_x2 form2: a(long) b -> b a b
.method static dupx2b()J
    .limit stack 5
    .limit locals 0
    ldc2_w 7L
    iconst_5
    dup_x2
    # 5 7L 5
    i2l
    ladd
    # 5 12L
    lstore_0
    pop
    lload_0
    lreturn
.end method

# dup2 form1 (два cat1): a b -> a b a b
.method static dup2a()I
    .limit stack 4
    .limit locals 0
    iconst_2
    iconst_3
    dup2
    imul
    # 2 3 6
    iadd
    iadd
    # 2+9 = 11
    ireturn
.end method

# dup2 form2 (long): a -> a a
.method static dup2b()J
    .limit stack 4
    .limit locals 0
    ldc2_w 21L
    dup2
    ladd
    lreturn
.end method

# dup2_x1 form1: a b c -> b c a b c
.method static dup2x1a()I
    .limit stack 5
    .limit locals 0
    iconst_1
    iconst_2
    iconst_3
    dup2_x1
    # 2 3 1 2 3
    imul
    # 2 3 1 6
    iadd
    # 2 3 7
    imul
    # 2 21
    iadd
    # 23
    ireturn
.end method

# dup2_x1 form2: a(int) b(long) -> b a b
.method static dup2x1b()J
    .limit stack 5
    .limit locals 0
    iconst_4
    ldc2_w 100L
    dup2_x1
    # 100L 4 100L
    lstore_0
    i2l
    lload_0
    ladd
    # 100L 104L
    ladd
    lreturn
.end method

# dup2_x2 form1: a b c d (cat1) -> c d a b c d
.method static dup2x2a()I
    .limit stack 6
    .limit locals 0
    iconst_1
    iconst_2
    iconst_3
    iconst_4
    dup2_x2
    # 3 4 1 2 3 4
    iadd
    iadd
    iadd
    # 3 4 10
    imul
    # 3 40
    iadd
    ireturn
.end method

# dup2_x2 form2: a b (cat1) c (long) -> c a b c
.method static dup2x2b()J
    .limit stack 6
    .limit locals 1
    iconst_1
    iconst_2
    ldc2_w 50L
    dup2_x2
    # 50L 1 2 50L
    lstore_0
    iadd
    i2l
    lload_0
    ladd
    ladd
    # 50 + 53 = 103
    lreturn
.end method

# dup2_x2 form3: a(long) b c (cat1) -> b c a b c
.method static dup2x2c()J
    .limit stack 6
    .limit locals 2
    ldc2_w 9L
    iconst_2
    iconst_3
    dup2_x2
    # 2 3 9L 2 3
    imul
    i2l
    ladd
    # 2 3 15L
    lstore_0
    imul
    i2l
    lload_0
    ladd
    # 21
    lreturn
.end method

# dup2_x2 form4: a(long) b(long) -> b a b
.method static dup2x2d()J
    .limit stack 6
    .limit locals 2
    ldc2_w 1000L
    ldc2_w 7L
    dup2_x2
    # 7L 1000L 7L
    lsub
    # 7L 993L
    ladd
    lreturn
.end method

# swap + pop2 из двух cat1
.method static swp()I
    .limit stack 4
    .limit locals 0
    iconst_5
    iconst_2
    swap
    isub
    # 2-5 = -3
    iconst_1
    iconst_1
    pop2
    ireturn
.end method

# long в последний слот параметров: lstore в слот ins-1
.method static lastParam(II)J
    .limit stack 4
    .limit locals 3
    ldc2_w 77L
    lstore_1
    lload_1
    iload_0
    i2l
    ladd
    lreturn
.end method

# переиспользование слота разными типами
.method static reuse(I)Ljava/lang/String;
    .limit stack 3
    .limit locals 3
    iload_0
    i2l
    lstore_1
    lload_1
    ldc2_w 3L
    lmul
    l2i
    istore_2
    ldc "r="
    astore_1
    new Ljava/lang/StringBuilder;
    dup
    invokespecial Ljava/lang/StringBuilder;-><init>()V
    aload_1
    invokevirtual Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    iload_2
    invokevirtual Ljava/lang/StringBuilder;->append(I)Ljava/lang/StringBuilder;
    invokevirtual Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
    areturn
.end method

.method public static main([Ljava/lang/String;)V
    .limit stack 3
    .limit locals 1
    invokestatic LSt;->dupx1()I
    invokestatic LSt;->p(I)V
    invokestatic LSt;->dupx2a()I
    invokestatic LSt;->p(I)V
    invokestatic LSt;->dupx2b()J
    invokestatic LSt;->pl(J)V
    invokestatic LSt;->dup2a()I
    invokestatic LSt;->p(I)V
    invokestatic LSt;->dup2b()J
    invokestatic LSt;->pl(J)V
    invokestatic LSt;->dup2x1a()I
    invokestatic LSt;->p(I)V
    invokestatic LSt;->dup2x1b()J
    invokestatic LSt;->pl(J)V
    invokestatic LSt;->dup2x2a()I
    invokestatic LSt;->p(I)V
    invokestatic LSt;->dup2x2b()J
    invokestatic LSt;->pl(J)V
    invokestatic LSt;->dup2x2c()J
    invokestatic LSt;->pl(J)V
    invokestatic LSt;->dup2x2d()J
    invokestatic LSt;->pl(J)V
    invokestatic LSt;->swp()I
    invokestatic LSt;->p(I)V
    iconst_3
    iconst_0
    invokestatic LSt;->lastParam(II)J
    invokestatic LSt;->pl(J)V
    getstatic Ljava/lang/System;->out:Ljava/io/PrintStream;
    bipush 7
    invokestatic LSt;->reuse(I)Ljava/lang/String;
    invokevirtual Ljava/io/PrintStream;->println(Ljava/lang/String;)V
    return
.end method
