.class super Lq/Main$Nested;
.super Ljava/lang/Object;
.source "Main.java"
.version 52

.method private secret()Ljava/lang/String;
    .limit stack 1
    .limit locals 1

    .line 23
    ldc "nested-secret"

    areturn
.end method

.method private <init>()V
    .limit stack 1
    .limit locals 1

    .line 24
    aload_0

    invokespecial Ljava/lang/Object;-><init>()V

    return
.end method

.method static maker()Ljava/util/function/Supplier;
    .limit stack 1
    .limit locals 0
    .signature "()Ljava/util/function/Supplier<Lq/Main$Nested;>;"

    .line 25
    invokedynamic get()Ljava/util/function/Supplier;, invoke-static@Ljava/lang/invoke/LambdaMetafactory;->metafactory(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;, { ()Ljava/lang/Object;, invoke-constructor@Lq/Main$Nested;-><init>()V, ()Lq/Main$Nested; }

    areturn
.end method

.method static synthetic access$000(Lq/Main$Nested;)Ljava/lang/String;
    .limit stack 1
    .limit locals 1

    .line 22
    aload_0

    invokespecial Lq/Main$Nested;->secret()Ljava/lang/String;

    areturn
.end method

# inner classes
.inner static Lq/Main$Nested; of Lq/Main; as Nested
.inner public static final Ljava/lang/invoke/MethodHandles$Lookup; of Ljava/lang/invoke/MethodHandles; as Lookup
