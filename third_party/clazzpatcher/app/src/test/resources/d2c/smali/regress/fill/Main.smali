# fill-array-data как в ART: null → NPE, короткий массив → AIOOBE, и оба — до первой записи.
.class public Lf/Main;
.super Ljava/lang/Object;

# Пустые данные внутри try: единственная «бросающая» инструкция диапазона.
.method public static empty([I)Ljava/lang/String;
    .registers 2
    :try_start
    fill-array-data p0, :data
    :try_end
    .catch Ljava/lang/NullPointerException; {:try_start .. :try_end} :h
    const-string v0, "empty-ok"
    return-object v0
    :h
    const-string v0, "empty-npe"
    return-object v0
    :data
    .array-data 4
    .end array-data
.end method

# Три элемента в массив длины 2: AIOOBE, a[0] и a[1] не тронуты.
.method public static shortArray()Ljava/lang/String;
    .registers 3
    const/4 v0, 2
    new-array v0, v0, [I
    :try_start
    fill-array-data v0, :data
    :try_end
    .catch Ljava/lang/ArrayIndexOutOfBoundsException; {:try_start .. :try_end} :h
    const-string v1, "no-exception"
    return-object v1
    :h
    const/4 v1, 0
    aget v1, v0, v1
    invoke-static {v1}, Ljava/lang/String;->valueOf(I)Ljava/lang/String;
    move-result-object v1
    return-object v1
    :data
    .array-data 4
        0x7
        0x8
        0x9
    .end array-data
.end method

.method public static main([Ljava/lang/String;)V
    .registers 3
    sget-object v0, Ljava/lang/System;->out:Ljava/io/PrintStream;
    const/4 v1, 1
    new-array v1, v1, [I
    invoke-static {v1}, Lf/Main;->empty([I)Ljava/lang/String;
    move-result-object v1
    invoke-virtual {v0, v1}, Ljava/io/PrintStream;->println(Ljava/lang/String;)V
    const/4 v1, 0
    invoke-static {v1}, Lf/Main;->empty([I)Ljava/lang/String;
    move-result-object v1
    invoke-virtual {v0, v1}, Ljava/io/PrintStream;->println(Ljava/lang/String;)V
    invoke-static {}, Lf/Main;->shortArray()Ljava/lang/String;
    move-result-object v1
    invoke-virtual {v0, v1}, Ljava/io/PrintStream;->println(Ljava/lang/String;)V
    return-void
.end method
