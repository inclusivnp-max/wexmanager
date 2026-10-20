# --target 50: invokestatic метода интерфейса требует 52, invoke-custom — 51.
.class public Lt/Main;
.super Ljava/lang/Object;
.method public static main([Ljava/lang/String;)V
    .registers 4
    sget-object v0, Ljava/lang/System;->out:Ljava/io/PrintStream;
    invoke-static {}, Lt/I;->s()I
    move-result v1
    invoke-virtual {v0, v1}, Ljava/io/PrintStream;->println(I)V
    const-string v2, "x"
    invoke-custom {v1, v2}, call_site_0("makeConcatWithConstants", (ILjava/lang/String;)Ljava/lang/String;, "\u0001-\u0001")@Ljava/lang/invoke/StringConcatFactory;->makeConcatWithConstants(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;
    move-result-object v2
    invoke-virtual {v0, v2}, Ljava/io/PrintStream;->println(Ljava/lang/String;)V
    return-void
.end method
