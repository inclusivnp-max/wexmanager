# Обработчик исключений — первая инструкция метода: в неё же входят при вызове (стек пуст).
.class public LMain;
.super Ljava/lang/Object;

.field static n:I

.method static work()V
    .registers 2
    sget v0, LMain;->n:I
    add-int/lit8 v0, v0, 1
    sput v0, LMain;->n:I
    const/4 v1, 3
    if-ge v0, v1, :ok
    new-instance v1, Ljava/lang/RuntimeException;
    invoke-direct {v1}, Ljava/lang/RuntimeException;-><init>()V
    throw v1
    :ok
    return-void
.end method

# Обработчик указывает на первую инструкцию метода (повтор до успеха).
.method public static retry()V
    .registers 0
    :try_start
    invoke-static {}, LMain;->work()V
    :try_end
    .catch Ljava/lang/RuntimeException; {:try_start .. :try_end} :try_start
    return-void
.end method

.method public static main([Ljava/lang/String;)V
    .registers 2
    invoke-static {}, LMain;->retry()V
    sget-object v0, Ljava/lang/System;->out:Ljava/io/PrintStream;
    sget v1, LMain;->n:I
    invoke-virtual {v0, v1}, Ljava/io/PrintStream;->println(I)V
    return-void
.end method
