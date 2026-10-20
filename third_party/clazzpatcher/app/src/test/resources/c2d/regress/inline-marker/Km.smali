# Тело inline-функции Kotlin (как analyze {} из Analysis API): synchronized внутри try/finally,
# маркеры InlineMarker между концом try блокировки и monitorexit накрыты только внешним обработчиком.
# c2d убирает маркеры (как d8), иначе ART: «failed lock verification».
.class public super LKm;
.super Ljava/lang/Object;
.version 52

.field static counter:I

.method static run(I)I
    .limit stack 2
    .limit locals 5
    :outer_start
    new Ljava/lang/Object;
    dup
    invokespecial Ljava/lang/Object;-><init>()V
    astore_1
    aload_1
    astore_2
    aload_2
    monitorenter
    :inner_start
    getstatic LKm;->counter:I
    iload_0
    iadd
    putstatic LKm;->counter:I
    :inner_end
    iconst_1
    invokestatic Lkotlin/jvm/internal/InlineMarker;->finallyStart(I)V
    aload_2
    monitorexit
    iconst_1
    invokestatic Lkotlin/jvm/internal/InlineMarker;->finallyEnd(I)V
    goto :after
    :inner_handler
    astore_3
    iconst_1
    :handler_marker
    invokestatic Lkotlin/jvm/internal/InlineMarker;->finallyStart(I)V
    aload_2
    monitorexit
    iconst_1
    invokestatic Lkotlin/jvm/internal/InlineMarker;->finallyEnd(I)V
    aload_3
    athrow
    :after
    getstatic LKm;->counter:I
    istore v3
    :outer_end
    iload v3
    ireturn
    :outer_handler
    astore_3
    iconst_m1
    ireturn

    .catchall {:inner_start .. :inner_end} :inner_handler
    .catchall {:inner_handler .. :handler_marker} :inner_handler
    .catchall {:outer_start .. :outer_end} :outer_handler
.end method

.method public static main([Ljava/lang/String;)V
    .limit stack 2
    .limit locals 1
    getstatic Ljava/lang/System;->out:Ljava/io/PrintStream;
    iconst_5
    invokestatic LKm;->run(I)I
    invokevirtual Ljava/io/PrintStream;->println(I)V
    getstatic Ljava/lang/System;->out:Ljava/io/PrintStream;
    bipush 7
    invokestatic LKm;->run(I)I
    invokevirtual Ljava/io/PrintStream;->println(I)V
    return
.end method
