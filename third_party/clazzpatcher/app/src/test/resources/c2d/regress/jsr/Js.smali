.class public super LJs;
.super Ljava/lang/Object;
.version 49

.field static counter:I

.method public <init>()V
    .limit stack 1
    .limit locals 1
    aload_0
    invokespecial Ljava/lang/Object;-><init>()V
    return
.end method

# try/finally в стиле javac 1.4: finally — подпрограмма, jsr из каждого выхода и из обработчика.
.method static f(I)I
    .limit stack 2
    .limit locals 4
    :try_start
    iload_0
    ifle :neg
    iload_0
    iconst_2
    imul
    istore_1
    jsr :fin
    iload_1
    ireturn
    :neg
    iload_0
    ifne :nonzero
    new Ljava/lang/IllegalStateException;
    dup
    invokespecial Ljava/lang/IllegalStateException;-><init>()V
    athrow
    :nonzero
    iload_0
    ineg
    istore_1
    jsr :fin
    iload_1
    ireturn
    :try_end
    :handler
    astore_2
    jsr :fin
    aload_2
    athrow
    :fin
    astore_3
    getstatic LJs;->counter:I
    iconst_1
    iadd
    putstatic LJs;->counter:I
    ret v2

    .catchall {:try_start .. :try_end} :handler
.end method

# Вложенные подпрограммы (javac 1.4: try { … } finally { try { … } finally { … } }), в подпрограмме — цикл.
.method static g(I)I
    .limit stack 3
    .limit locals 7
    iconst_0
    istore_1
    :outer_start
    bipush 21
    iload_0
    idiv
    istore_1
    jsr :fin1
    iload_1
    ireturn
    :outer_end
    :outer_handler
    astore_2
    jsr :fin1
    aload_2
    athrow
    :fin1
    astore_3
    :loop
    getstatic LJs;->counter:I
    bipush 10
    iadd
    putstatic LJs;->counter:I
    iinc p0, -1
    iload_0
    ifgt :loop
    :inner_start
    getstatic LJs;->counter:I
    iconst_5
    iadd
    putstatic LJs;->counter:I
    :inner_end
    jsr :fin2
    goto :fin1_ret
    :inner_handler
    astore v4
    jsr :fin2
    aload v4
    athrow
    :fin1_ret
    ret v2
    :fin2
    astore v3
    getstatic LJs;->counter:I
    sipush 1000
    iadd
    putstatic LJs;->counter:I
    ret v3

    .catchall {:inner_start .. :inner_end} :inner_handler
    .catchall {:outer_start .. :outer_end} :outer_handler
.end method

.method public static main([Ljava/lang/String;)V
    .limit stack 3
    .limit locals 2
    getstatic Ljava/lang/System;->out:Ljava/io/PrintStream;
    iconst_5
    invokestatic LJs;->f(I)I
    invokevirtual Ljava/io/PrintStream;->println(I)V
    getstatic Ljava/lang/System;->out:Ljava/io/PrintStream;
    bipush -4
    invokestatic LJs;->f(I)I
    invokevirtual Ljava/io/PrintStream;->println(I)V
    :t_start
    iconst_0
    invokestatic LJs;->f(I)I
    pop
    :t_end
    goto :after
    :t_handler
    pop
    getstatic Ljava/lang/System;->out:Ljava/io/PrintStream;
    ldc "caught"
    invokevirtual Ljava/io/PrintStream;->println(Ljava/lang/String;)V
    :after
    getstatic Ljava/lang/System;->out:Ljava/io/PrintStream;
    getstatic LJs;->counter:I
    invokevirtual Ljava/io/PrintStream;->println(I)V
    getstatic Ljava/lang/System;->out:Ljava/io/PrintStream;
    bipush 7
    invokestatic LJs;->g(I)I
    invokevirtual Ljava/io/PrintStream;->println(I)V
    getstatic Ljava/lang/System;->out:Ljava/io/PrintStream;
    getstatic LJs;->counter:I
    invokevirtual Ljava/io/PrintStream;->println(I)V
    :g_start
    iconst_0
    invokestatic LJs;->g(I)I
    pop
    :g_end
    goto :after2
    :g_handler
    pop
    getstatic Ljava/lang/System;->out:Ljava/io/PrintStream;
    ldc "arith"
    invokevirtual Ljava/io/PrintStream;->println(Ljava/lang/String;)V
    :after2
    getstatic Ljava/lang/System;->out:Ljava/io/PrintStream;
    getstatic LJs;->counter:I
    invokevirtual Ljava/io/PrintStream;->println(I)V
    return

    .catch Ljava/lang/IllegalStateException; {:t_start .. :t_end} :t_handler
    .catch Ljava/lang/ArithmeticException; {:g_start .. :g_end} :g_handler
.end method
