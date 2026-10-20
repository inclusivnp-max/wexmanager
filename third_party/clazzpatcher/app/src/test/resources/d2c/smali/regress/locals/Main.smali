# debug_info после обфускатора: long на паре через границу параметров и недопустимое имя. ART их не проверяет,
# HotSpot отверг бы весь класс — такие записи в LocalVariableTable не попадают.
.class public Ll/Main;
.super Ljava/lang/Object;

.method public static f(I)I
    .registers 2
    const/4 v0, 1
    .local v0, "bogus":J
    .local v0, "a.b":I
    add-int/2addr v0, p0
    .local v0, "ok":I
    return v0
.end method

.method public static main([Ljava/lang/String;)V
    .registers 2
    const/4 v0, 1
    invoke-static {v0}, Ll/Main;->f(I)I
    move-result v0
    sget-object v1, Ljava/lang/System;->out:Ljava/io/PrintStream;
    invoke-virtual {v1, v0}, Ljava/io/PrintStream;->println(I)V
    return-void
.end method
