# Целевые случаи DEX → class: каждая функция проверяет отдельный механизм конвертера.
.class public LT;
.super Ljava/lang/Object;

.method public static main([Ljava/lang/String;)V
    .registers 4

    const/4 v0, 3
    const/4 v1, 4
    invoke-static {v0, v1}, LT;->wideAcross(II)J
    move-result-wide v0
    invoke-static {v0, v1}, Ljava/lang/Long;->valueOf(J)Ljava/lang/Long;
    move-result-object v0
    invoke-static {v0}, LT;->p(Ljava/lang/Object;)V

    const/4 v0, 0
    invoke-static {v0}, LT;->mergeNull(Z)Ljava/lang/Object;
    move-result-object v0
    invoke-static {v0}, LT;->p(Ljava/lang/Object;)V

    invoke-static {}, LT;->sharedConst()Ljava/lang/String;
    move-result-object v0
    invoke-static {v0}, LT;->p(Ljava/lang/Object;)V

    const/4 v2, 1
    invoke-static {v2}, LT;->phiInt(Z)I
    move-result v0
    const/4 v2, 0
    invoke-static {v2}, LT;->phiInt(Z)I
    move-result v1
    add-int/lit16 v0, v0, 1000
    mul-int/lit8 v1, v1, 10
    add-int/2addr v0, v1
    invoke-static {v0}, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;
    move-result-object v0
    invoke-static {v0}, LT;->p(Ljava/lang/Object;)V

    const/4 v2, 1
    invoke-static {v2}, LT;->phiFloat(Z)F
    move-result v0
    const/4 v2, 0
    invoke-static {v2}, LT;->phiFloat(Z)F
    move-result v1
    mul-float/2addr v0, v1
    invoke-static {v0}, Ljava/lang/Float;->valueOf(F)Ljava/lang/Float;
    move-result-object v0
    invoke-static {v0}, LT;->p(Ljava/lang/Object;)V

    const/4 v2, 0
    invoke-static {v2}, LT;->phiDouble(Z)D
    move-result-wide v0
    invoke-static {v0, v1}, Ljava/lang/Double;->valueOf(D)Ljava/lang/Double;
    move-result-object v0
    invoke-static {v0}, LT;->p(Ljava/lang/Object;)V

    const/4 v2, 2
    invoke-static {v2}, LT;->handlerNoMove(I)I
    move-result v0
    const/4 v2, 0
    invoke-static {v2}, LT;->handlerNoMove(I)I
    move-result v1
    mul-int/lit16 v1, v1, 1000
    add-int/2addr v0, v1
    invoke-static {v0}, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;
    move-result-object v0
    invoke-static {v0}, LT;->p(Ljava/lang/Object;)V

    invoke-static {}, LT;->switches()Ljava/lang/String;
    move-result-object v0
    invoke-static {v0}, LT;->p(Ljava/lang/Object;)V

    invoke-static {}, LT;->arrays()Ljava/lang/String;
    move-result-object v0
    invoke-static {v0}, LT;->p(Ljava/lang/Object;)V

    invoke-static {}, LT;->poly()Ljava/lang/String;
    move-result-object v0
    invoke-static {v0}, LT;->p(Ljava/lang/Object;)V

    new-instance v0, Ljava/lang/Object;
    invoke-direct {v0}, Ljava/lang/Object;-><init>()V
    invoke-static {v0}, LT;->sync(Ljava/lang/Object;)I
    move-result v0
    invoke-static {v0}, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;
    move-result-object v0
    invoke-static {v0}, LT;->p(Ljava/lang/Object;)V

    invoke-static {}, LT;->initCopy()Ljava/lang/String;
    move-result-object v0
    invoke-static {v0}, LT;->p(Ljava/lang/Object;)V

    const-wide/16 v0, 3
    const-wide/high16 v2, 0x3ff0000000000000L
    invoke-static {v0, v1, v2, v3}, LT;->cmps(JD)I
    move-result v0
    invoke-static {v0}, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;
    move-result-object v0
    invoke-static {v0}, LT;->p(Ljava/lang/Object;)V

    invoke-static {}, LT;->callSum()J
    move-result-wide v0
    invoke-static {v0, v1}, Ljava/lang/Long;->valueOf(J)Ljava/lang/Long;
    move-result-object v0
    invoke-static {v0}, LT;->p(Ljava/lang/Object;)V

    const-string v0, "abc"
    invoke-static {v0}, LT;->casts(Ljava/lang/Object;)Ljava/lang/String;
    move-result-object v0
    invoke-static {v0}, LT;->p(Ljava/lang/Object;)V

    const/4 v2, 1
    invoke-static {v2}, LT;->lub(Z)I
    move-result v0
    invoke-static {v0}, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;
    move-result-object v0
    invoke-static {v0}, LT;->p(Ljava/lang/Object;)V

    return-void
.end method

.method static p(Ljava/lang/Object;)V
    .registers 2
    sget-object v0, Ljava/lang/System;->out:Ljava/io/PrintStream;
    invoke-virtual {v0, p0}, Ljava/io/PrintStream;->println(Ljava/lang/Object;)V
    return-void
.end method

# Пара long в регистрах v1:v2 заходит на p0 — схема с копированием параметров.
.method static wideAcross(II)J
    .registers 4
    add-int v0, p0, p1
    int-to-long v1, v0
    return-wide v1
.end method

# Константа null сливается с объектом: хранится в слоте как ссылка.
.method static mergeNull(Z)Ljava/lang/Object;
    .registers 2
    const/4 v0, 0
    if-eqz p0, :skip
    new-instance v0, Ljava/lang/Object;
    invoke-direct {v0}, Ljava/lang/Object;-><init>()V
    :skip
    return-object v0
.end method

# Одна константа — и int, и null; другая — и float, и int; результаты вызовов отбрасываются.
.method static sharedConst()Ljava/lang/String;
    .registers 4
    const/4 v0, 0
    const v1, 0x3f800000
    new-instance v2, Ljava/lang/StringBuilder;
    invoke-direct {v2}, Ljava/lang/StringBuilder;-><init>()V
    invoke-virtual {v2, v0}, Ljava/lang/StringBuilder;->append(I)Ljava/lang/StringBuilder;
    invoke-virtual {v2, v0}, Ljava/lang/StringBuilder;->append(Ljava/lang/Object;)Ljava/lang/StringBuilder;
    invoke-virtual {v2, v1}, Ljava/lang/StringBuilder;->append(F)Ljava/lang/StringBuilder;
    invoke-virtual {v2, v1}, Ljava/lang/StringBuilder;->append(I)Ljava/lang/StringBuilder;
    invoke-virtual {v2}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
    move-result-object v3
    return-object v3
.end method

# φ из разных констант: категория — по использованию.
.method static phiInt(Z)I
    .registers 3
    if-eqz p0, :a
    const/4 v0, 3
    goto :b
    :a
    const/4 v0, 5
    :b
    add-int/lit8 v1, v0, 1
    return v1
.end method

.method static phiFloat(Z)F
    .registers 3
    if-eqz p0, :a
    const/high16 v0, 0x3f800000
    goto :b
    :a
    const/high16 v0, 0x40000000
    :b
    add-float v1, v0, v0
    return v1
.end method

.method static phiDouble(Z)D
    .registers 5
    if-eqz p0, :a
    const-wide/high16 v0, 0x3ff0000000000000L
    goto :b
    :a
    const-wide v0, 0x400921fb54442d18L
    :b
    add-double v2, v0, v0
    return-wide v2
.end method

# Обработчик без move-exception, достижимый и обычным потоком (заглушка pop; goto).
.method static handlerNoMove(I)I
    .registers 3
    const/4 v0, 1
    :try_start
    div-int v1, v0, p0
    :try_end
    .catchall {:try_start .. :try_end} :handler
    add-int/lit8 v0, v1, 10
    :handler
    add-int/lit8 v0, v0, 100
    return v0
.end method

.method static switches()Ljava/lang/String;
    .registers 4
    new-instance v0, Ljava/lang/StringBuilder;
    invoke-direct {v0}, Ljava/lang/StringBuilder;-><init>()V
    const/4 v1, -2
    invoke-static {v1}, LT;->packed(I)I
    move-result v2
    invoke-virtual {v0, v2}, Ljava/lang/StringBuilder;->append(I)Ljava/lang/StringBuilder;
    const/4 v1, -1
    invoke-static {v1}, LT;->packed(I)I
    move-result v2
    invoke-virtual {v0, v2}, Ljava/lang/StringBuilder;->append(I)Ljava/lang/StringBuilder;
    const/4 v1, 0
    invoke-static {v1}, LT;->packed(I)I
    move-result v2
    invoke-virtual {v0, v2}, Ljava/lang/StringBuilder;->append(I)Ljava/lang/StringBuilder;
    const/high16 v1, -0x80000000
    invoke-static {v1}, LT;->sparse(I)I
    move-result v2
    invoke-virtual {v0, v2}, Ljava/lang/StringBuilder;->append(I)Ljava/lang/StringBuilder;
    const v1, 0x7fffffff
    invoke-static {v1}, LT;->sparse(I)I
    move-result v2
    invoke-virtual {v0, v2}, Ljava/lang/StringBuilder;->append(I)Ljava/lang/StringBuilder;
    const/4 v1, 5
    invoke-static {v1}, LT;->sparse(I)I
    move-result v2
    invoke-virtual {v0, v2}, Ljava/lang/StringBuilder;->append(I)Ljava/lang/StringBuilder;
    invoke-virtual {v0}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
    move-result-object v3
    return-object v3
.end method

.method static packed(I)I
    .registers 2
    packed-switch p0, :pswitch
    const/4 v0, -1
    return v0
    :p0
    const/16 v0, 100
    return v0
    :p1
    const/16 v0, 200
    return v0
    :pswitch
    .packed-switch -0x2
        :p0
        :p1
    .end packed-switch
.end method

.method static sparse(I)I
    .registers 2
    sparse-switch p0, :sswitch
    const/4 v0, 0
    return v0
    :s1
    const/4 v0, 1
    return v0
    :s2
    const/4 v0, 2
    return v0
    :sswitch
    .sparse-switch
        -0x80000000 -> :s1
        0x7fffffff -> :s2
    .end sparse-switch
.end method

# fill-array-data (float/char/boolean) и filled-new-array/range.
.method static arrays()Ljava/lang/String;
    .registers 9
    const/4 v0, 3
    new-array v1, v0, [F
    fill-array-data v1, :fdata
    new-array v2, v0, [C
    fill-array-data v2, :cdata
    new-array v3, v0, [Z
    fill-array-data v3, :zdata
    const/4 v4, 1
    const/4 v5, 2
    const/4 v6, 3
    const/4 v7, 4
    const/4 v8, 5
    filled-new-array/range {v4 .. v8}, [I
    move-result-object v4
    invoke-static {v1}, Ljava/util/Arrays;->toString([F)Ljava/lang/String;
    move-result-object v1
    invoke-static {v2}, Ljava/lang/String;->valueOf([C)Ljava/lang/String;
    move-result-object v2
    invoke-static {v3}, Ljava/util/Arrays;->toString([Z)Ljava/lang/String;
    move-result-object v3
    invoke-static {v4}, Ljava/util/Arrays;->toString([I)Ljava/lang/String;
    move-result-object v4
    invoke-virtual {v1, v2}, Ljava/lang/String;->concat(Ljava/lang/String;)Ljava/lang/String;
    move-result-object v1
    invoke-virtual {v1, v3}, Ljava/lang/String;->concat(Ljava/lang/String;)Ljava/lang/String;
    move-result-object v1
    invoke-virtual {v1, v4}, Ljava/lang/String;->concat(Ljava/lang/String;)Ljava/lang/String;
    move-result-object v1
    return-object v1
    :fdata
    .array-data 4
        1.5f
        -2.0f
        0.25f
    .end array-data
    :cdata
    .array-data 2
        0x41s
        0x42s
        0x416s
    .end array-data
    :zdata
    .array-data 1
        0x1t
        0x0t
        0x1t
    .end array-data
.end method

# const-method-handle, const-method-type, invoke-polymorphic.
.method static poly()Ljava/lang/String;
    .registers 4
    const-method-handle v0, invoke-static@Ljava/lang/String;->valueOf(I)Ljava/lang/String;
    const/16 v1, 42
    invoke-polymorphic {v0, v1}, Ljava/lang/invoke/MethodHandle;->invokeExact([Ljava/lang/Object;)Ljava/lang/Object;, (I)Ljava/lang/String;
    move-result-object v2
    const-method-type v0, (I)Ljava/lang/String;
    invoke-virtual {v0}, Ljava/lang/invoke/MethodType;->toString()Ljava/lang/String;
    move-result-object v3
    invoke-virtual {v2, v3}, Ljava/lang/String;->concat(Ljava/lang/String;)Ljava/lang/String;
    move-result-object v2
    return-object v2
.end method

# monitor-enter/exit с catch-all (как synchronized-блок).
.method static sync(Ljava/lang/Object;)I
    .registers 3
    monitor-enter p0
    :try_start
    invoke-virtual {p0}, Ljava/lang/Object;->hashCode()I
    move-result v0
    monitor-exit p0
    :try_end
    .catchall {:try_start .. :try_end} :catch
    const/4 v0, 7
    return v0
    :catch
    move-exception v1
    monitor-exit p0
    throw v1
.end method

# <init> вызывается через копию неинициализированной ссылки.
.method static initCopy()Ljava/lang/String;
    .registers 4
    new-instance v0, Ljava/lang/StringBuilder;
    move-object v1, v0
    const-string v2, "copy"
    invoke-direct {v1, v2}, Ljava/lang/StringBuilder;-><init>(Ljava/lang/String;)V
    invoke-virtual {v0}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
    move-result-object v3
    return-object v3
.end method

.method static cmps(JD)I
    .registers 8
    const-wide/16 v0, 5
    cmp-long v2, p0, v0
    const-wide v0, 0x7ff8000000000000L
    cmpg-double v3, p2, v0
    mul-int/lit8 v2, v2, 10
    add-int/2addr v2, v3
    return v2
.end method

.method static sum3(JJJ)J
    .registers 6
    add-long v0, p0, p2
    add-long/2addr v0, p4
    return-wide v0
.end method

.method static callSum()J
    .registers 6
    const-wide/16 v0, 100
    const-wide/16 v2, 20
    const-wide/16 v4, 3
    invoke-static/range {v0 .. v5}, LT;->sum3(JJJ)J
    move-result-wide v0
    return-wide v0
.end method

.method static casts(Ljava/lang/Object;)Ljava/lang/String;
    .registers 3
    const/4 v0, 0
    instance-of v1, v0, Ljava/lang/String;
    if-nez v1, :bad
    check-cast p0, Ljava/lang/CharSequence;
    invoke-interface {p0}, Ljava/lang/CharSequence;->length()I
    move-result v1
    check-cast p0, Ljava/lang/String;
    invoke-virtual {p0}, Ljava/lang/String;->toUpperCase()Ljava/lang/String;
    move-result-object v0
    return-object v0
    :bad
    const-string v0, "bad"
    return-object v0
.end method

# Слияние ArrayList и LinkedList: общий предок AbstractList (иерархия JDK) либо checkcast.
.method static lub(Z)I
    .registers 3
    if-eqz p0, :a
    new-instance v0, Ljava/util/ArrayList;
    invoke-direct {v0}, Ljava/util/ArrayList;-><init>()V
    goto :b
    :a
    new-instance v0, Ljava/util/LinkedList;
    invoke-direct {v0}, Ljava/util/LinkedList;-><init>()V
    :b
    invoke-virtual {v0}, Ljava/util/AbstractList;->size()I
    move-result v1
    const/16 v2, 9
    add-int/2addr v1, v2
    return v1
.end method
