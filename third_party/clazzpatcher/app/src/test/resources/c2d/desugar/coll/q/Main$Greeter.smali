.class interface abstract Lq/Main$Greeter;
.super Ljava/lang/Object;
.source "Main.java"
.version 52

.method public abstract greet(Ljava/lang/String;)Ljava/lang/String;
.end method

.method public twice()Ljava/util/function/Function;
    .limit stack 1
    .limit locals 1
    .signature "()Ljava/util/function/Function<Ljava/lang/String;Ljava/lang/String;>;"

    .line 12
    aload_0

    invokedynamic apply(Lq/Main$Greeter;)Ljava/util/function/Function;, invoke-static@Ljava/lang/invoke/LambdaMetafactory;->metafactory(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;, { (Ljava/lang/Object;)Ljava/lang/Object;, invoke-direct@interface Lq/Main$Greeter;->lambda$twice$0(Ljava/lang/String;)Ljava/lang/String;, (Ljava/lang/String;)Ljava/lang/String; }

    areturn
.end method

.method private synthetic lambda$twice$0(Ljava/lang/String;)Ljava/lang/String;
    .limit stack 3
    .limit locals 2

    .line 12
    aload_0

    aload_0

    aload_1

    invokeinterface Lq/Main$Greeter;->greet(Ljava/lang/String;)Ljava/lang/String;, 2

    invokeinterface Lq/Main$Greeter;->greet(Ljava/lang/String;)Ljava/lang/String;, 2

    areturn
.end method

# inner classes
.inner static interface abstract Lq/Main$Greeter; of Lq/Main; as Greeter
.inner public static final Ljava/lang/invoke/MethodHandles$Lookup; of Ljava/lang/invoke/MethodHandles; as Lookup
