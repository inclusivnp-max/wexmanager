# ART даёт результату aget на null и слиянию одних констант тип «константа» — годится и как int, и как float.
.class public Lt/T;
.super Ljava/lang/Object;

# float[] a = null; return a[0] + 1f;   (javac + d8 без оптимизаций)
.method public static aNullFloat()F
    .registers 3
    const/4 v0, 0
    const/4 v1, 0
    aget v0, v0, v1
    const/high16 v2, 0x3f800000
    add-float/2addr v0, v2
    return v0
.end method

# double[] a = null; return a[0] + 1.0;
.method public static aNullDouble()D
    .registers 5
    const/4 v0, 0
    const/4 v1, 0
    aget-wide v2, v0, v1
    const-wide/high16 v0, 0x3ff0000000000000L
    add-double/2addr v2, v0
    return-wide v2
.end method

# φ(1.0f, 0) в if-eqz и как float.
.method public static eqz(Z)F
    .registers 2
    if-eqz p0, :a
    const/high16 v0, 0x3f800000
    goto :b
    :a
    const/4 v0, 0
    :b
    if-eqz v0, :zero
    return v0
    :zero
    const/high16 v0, -0x40800000
    return v0
.end method

# φ(-2.0f, 0) в if-ltz и if-ne как int (биты -2.0f отрицательны) и как float.
.method public static ltz(Z)F
    .registers 3
    if-eqz p0, :a
    const/high16 v0, -0x40000000
    goto :b
    :a
    const/4 v0, 0
    :b
    if-ltz v0, :neg
    const/4 v1, 0
    if-ne v0, v1, :neg
    const/high16 v0, 0x41200000
    return v0
    :neg
    return v0
.end method

.method public static main([Ljava/lang/String;)V
    .registers 4
    sget-object v0, Ljava/lang/System;->out:Ljava/io/PrintStream;
    :try1
    invoke-static {}, Lt/T;->aNullFloat()F
    :try1_end
    .catch Ljava/lang/NullPointerException; {:try1 .. :try1_end} :npe1
    :after1
    :try2
    invoke-static {}, Lt/T;->aNullDouble()D
    :try2_end
    .catch Ljava/lang/NullPointerException; {:try2 .. :try2_end} :npe2
    :after2
    const/4 v1, 1
    invoke-static {v1}, Lt/T;->eqz(Z)F
    move-result v2
    invoke-virtual {v0, v2}, Ljava/io/PrintStream;->println(F)V
    const/4 v1, 0
    invoke-static {v1}, Lt/T;->eqz(Z)F
    move-result v2
    invoke-virtual {v0, v2}, Ljava/io/PrintStream;->println(F)V
    const/4 v1, 1
    invoke-static {v1}, Lt/T;->ltz(Z)F
    move-result v2
    invoke-virtual {v0, v2}, Ljava/io/PrintStream;->println(F)V
    const/4 v1, 0
    invoke-static {v1}, Lt/T;->ltz(Z)F
    move-result v2
    invoke-virtual {v0, v2}, Ljava/io/PrintStream;->println(F)V
    return-void
    :npe1
    const-string v1, "npe-float"
    invoke-virtual {v0, v1}, Ljava/io/PrintStream;->println(Ljava/lang/String;)V
    goto :after1
    :npe2
    const-string v1, "npe-double"
    invoke-virtual {v0, v1}, Ljava/io/PrintStream;->println(Ljava/lang/String;)V
    goto :after2
.end method
