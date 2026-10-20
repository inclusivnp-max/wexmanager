.class public Lc/Main;
.super Ljava/lang/Object;

.method public static main([Ljava/lang/String;)V
    .registers 4
    # 1. new D + A.<init>(I) в обход D, C и B.
    new-instance v0, Lb/D;
    const/4 v1, 5
    invoke-direct {v0, v1}, Lb/A;-><init>(I)V
    invoke-virtual {v0}, Lb/A;->getV()I
    move-result v1
    invoke-static {v1}, Lc/Main;->p(I)V
    # 2. new C: C.<init>() вызывает A.<init>(I) в обход B.
    new-instance v0, Lb/C;
    invoke-direct {v0}, Lb/C;-><init>()V
    invoke-virtual {v0}, Lb/A;->getV()I
    move-result v1
    invoke-static {v1}, Lc/Main;->p(I)V
    # Конструкторы B не выполнялись.
    sget v1, Lb/B;->count:I
    invoke-static {v1}, Lc/Main;->p(I)V
    # 3. protected-члены через метод доступа: 7 * 10 + 3.
    invoke-static {v0}, Lc/P;->peek(Lb/A;)I
    move-result v1
    invoke-static {v1}, Lc/Main;->p(I)V
    # 4. Обычный конструктор B не затронут.
    new-instance v0, Lb/B;
    const/4 v1, 2
    invoke-direct {v0, v1}, Lb/B;-><init>(I)V
    sget v1, Lb/B;->count:I
    invoke-static {v1}, Lc/Main;->p(I)V
    return-void
.end method

.method static p(I)V
    .registers 2
    sget-object v0, Ljava/lang/System;->out:Ljava/io/PrintStream;
    invoke-virtual {v0, p0}, Ljava/io/PrintStream;->println(I)V
    return-void
.end method
