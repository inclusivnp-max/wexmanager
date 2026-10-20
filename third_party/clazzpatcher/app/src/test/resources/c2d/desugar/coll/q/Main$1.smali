.class super Lq/Main$1;
.super Ljava/lang/Object;
.source "Main.java"
.version 52
.implements Lq/Main$Greeter;

.field final synthetic this$0:Lq/Main;

.method <init>(Lq/Main;)V
    .limit stack 2
    .limit locals 2
    .param p1, null, 0x8010    # Lq/Main;

    .line 36
    aload_0

    aload_1

    putfield Lq/Main$1;->this$0:Lq/Main;

    aload_0

    invokespecial Ljava/lang/Object;-><init>()V

    return
.end method

.method public greet(Ljava/lang/String;)Ljava/lang/String;
    .limit stack 2
    .limit locals 2

    .line 36
    new Ljava/lang/StringBuilder;

    dup

    invokespecial Ljava/lang/StringBuilder;-><init>()V

    ldc "<"

    invokevirtual Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    aload_1

    invokevirtual Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    ldc ">"

    invokevirtual Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invokevirtual Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    areturn
.end method

# inner classes
.inner Lq/Main$1;
.inner static interface abstract Lq/Main$Greeter; of Lq/Main; as Greeter

.enclosing Lq/Main;->run()V
