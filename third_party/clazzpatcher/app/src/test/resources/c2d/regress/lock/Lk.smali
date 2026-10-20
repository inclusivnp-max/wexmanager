.class public super LLk;
.super Ljava/lang/Object;
.version 52

.field a:I

.field b:I

.field static s:I

.method public <init>()V
    .limit stack 1
    .limit locals 1
    aload_0
    invokespecial Ljava/lang/Object;-><init>()V
    return
.end method

# Вложенный monitorenter на том же локале (так выглядит DEX после R8, пропущенный через d2c).
# Локалов много — this в DEX выше v15, и putfield/getfield берут копию this во временном
# регистре. Копия, снятая до второго захвата, не должна дожить до обработчика вложенного
# блока: иначе ART не согласует уровни блокировки («failed lock verification»).
.method public run(I)I
    .limit stack 3
    .limit locals 20
    aload_0
    monitorenter
    :outer_start
    aload_0
    iconst_1
    putfield LLk;->a:I
    iload_1
    ifle :skip
    aload_0
    monitorenter
    :inner_start
    getstatic LLk;->s:I
    istore_2
    aload_0
    iload_2
    iconst_2
    iadd
    putfield LLk;->b:I
    getstatic LLk;->s:I
    istore_3
    aload_0
    monitorexit
    :inner_end
    goto :skip
    :inner_handler
    astore v4
    aload_0
    monitorexit
    :inner_handler_end
    aload v4
    athrow
    :skip
    aload_0
    getfield LLk;->a:I
    aload_0
    getfield LLk;->b:I
    iadd
    istore v5
    aload_0
    monitorexit
    :outer_end
    iload v5
    ireturn
    :outer_handler
    astore v6
    aload_0
    monitorexit
    :outer_handler_end
    aload v6
    athrow

    .catchall {:inner_start .. :inner_end} :inner_handler
    .catchall {:inner_handler .. :inner_handler_end} :inner_handler
    .catchall {:outer_start .. :outer_end} :outer_handler
    .catchall {:outer_handler .. :outer_handler_end} :outer_handler
.end method

# Объект заблокирован через локал 0, а monitor-exit берёт его из локала 1, куда он скопирован
# задолго до захвата (так бывает после R8): копия должна получить признак блокировки.
.method static exitThroughEarlyCopy(Ljava/lang/Object;)I
    .limit stack 2
    .limit locals 2
    aload_0
    astore_1
    getstatic LLk;->s:I
    pop
    aload_0
    monitorenter
    :s
    getstatic LLk;->s:I
    pop
    aload_1
    monitorexit
    :e
    iconst_4
    ireturn
    :h
    aload_1
    monitorexit
    athrow

    .catchall {:s .. :e} :h
.end method

# Тот же объект после checkcast лежит в локале 1 с более узким типом: передача блокировки
# копией сделала бы его тип Object, и вызов метода StringBuilder на нём не прошёл бы верификатор ART.
.method static castCopyKeepsType(Ljava/lang/Object;)Ljava/lang/String;
    .limit stack 2
    .limit locals 2
    aload_0
    checkcast Ljava/lang/StringBuilder;
    astore_1
    aload_0
    monitorenter
    :s
    aload_1
    ldc "d"
    invokevirtual Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    pop
    aload_0
    monitorexit
    :e
    aload_1
    invokevirtual Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
    areturn
    :h
    aload_0
    monitorexit
    athrow

    .catchall {:s .. :e} :h
.end method

.method public static main([Ljava/lang/String;)V
    .limit stack 4
    .limit locals 2
    new LLk;
    dup
    invokespecial LLk;-><init>()V
    astore_1
    getstatic Ljava/lang/System;->out:Ljava/io/PrintStream;
    aload_1
    iconst_1
    invokevirtual LLk;->run(I)I
    invokevirtual Ljava/io/PrintStream;->println(I)V
    getstatic Ljava/lang/System;->out:Ljava/io/PrintStream;
    aload_1
    iconst_0
    invokevirtual LLk;->run(I)I
    invokevirtual Ljava/io/PrintStream;->println(I)V
    getstatic Ljava/lang/System;->out:Ljava/io/PrintStream;
    aload_1
    invokestatic LLk;->exitThroughEarlyCopy(Ljava/lang/Object;)I
    invokevirtual Ljava/io/PrintStream;->println(I)V
    getstatic Ljava/lang/System;->out:Ljava/io/PrintStream;
    new Ljava/lang/StringBuilder;
    dup
    ldc "abc"
    invokespecial Ljava/lang/StringBuilder;-><init>(Ljava/lang/String;)V
    invokestatic LLk;->castCopyKeepsType(Ljava/lang/Object;)Ljava/lang/String;
    invokevirtual Ljava/io/PrintStream;->println(Ljava/lang/String;)V
    return
.end method
