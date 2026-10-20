.class public super Lq/Main;
.super Ljava/lang/Object;
.source "Main.java"
.version 52

.field static final out:Ljava/util/List;
    .signature "Ljava/util/List<Ljava/lang/String;>;"
.end field

.field private final base:I

.method static p(Ljava/lang/Object;)V
    .limit stack 2
    .limit locals 1

    .line 8
    getstatic Lq/Main;->out:Ljava/util/List;

    aload_0

    invokestatic Ljava/lang/String;->valueOf(Ljava/lang/Object;)Ljava/lang/String;

    invokeinterface Ljava/util/List;->add(Ljava/lang/Object;)Z, 2

    pop

    return
.end method

.method private <init>(I)V
    .limit stack 2
    .limit locals 2

    .line 16
    aload_0

    invokespecial Ljava/lang/Object;-><init>()V

    aload_0

    iload_1

    putfield Lq/Main;->base:I

    return
.end method

.method private <init>()V
    .limit stack 2
    .limit locals 1

    .line 17
    aload_0

    bipush 0x64

    invokespecial Lq/Main;-><init>(I)V

    return
.end method

.method private add(I)I
    .limit stack 2
    .limit locals 2

    .line 18
    aload_0

    getfield Lq/Main;->base:I

    iload_1

    iadd

    ireturn
.end method

.method private static twiceS(I)I
    .limit stack 2
    .limit locals 1

    .line 19
    iconst_2

    iload_0

    imul

    ireturn
.end method

.method private wide(JD)J
    .limit stack 4
    .limit locals 5

    .line 20
    lload_1

    dload_3

    d2l

    ladd

    aload_0

    getfield Lq/Main;->base:I

    i2l

    ladd

    lreturn
.end method

.method run()V
    .limit stack 6
    .limit locals 14

    .line 29
    aload_0

    invokedynamic applyAsInt(Lq/Main;)Ljava/util/function/IntUnaryOperator;, invoke-static@Ljava/lang/invoke/LambdaMetafactory;->metafactory(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;, { (I)I, invoke-direct@Lq/Main;->add(I)I, (I)I }

    astore_1

    .line 30
    invokedynamic applyAsInt()Ljava/util/function/IntUnaryOperator;, invoke-static@Ljava/lang/invoke/LambdaMetafactory;->metafactory(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;, { (I)I, invoke-static@Lq/Main;->twiceS(I)I, (I)I }

    astore_2

    .line 31
    invokedynamic get()Ljava/util/function/Supplier;, invoke-static@Ljava/lang/invoke/LambdaMetafactory;->metafactory(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;, { ()Ljava/lang/Object;, invoke-constructor@Lq/Main;-><init>()V, ()Lq/Main; }

    astore_3

    .line 32
    invokedynamic apply()Ljava/util/function/IntFunction;, invoke-static@Ljava/lang/invoke/LambdaMetafactory;->metafactory(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;, { (I)Ljava/lang/Object;, invoke-constructor@Lq/Main;-><init>(I)V, (I)Lq/Main; }

    astore v3

    .line 33
    new Ljava/lang/StringBuilder;

    dup

    invokespecial Ljava/lang/StringBuilder;-><init>()V

    aload_1

    iconst_5

    invokeinterface Ljava/util/function/IntUnaryOperator;->applyAsInt(I)I, 2

    invokevirtual Ljava/lang/StringBuilder;->append(I)Ljava/lang/StringBuilder;

    ldc " "

    invokevirtual Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    aload_2

    iconst_4

    invokeinterface Ljava/util/function/IntUnaryOperator;->applyAsInt(I)I, 2

    invokevirtual Ljava/lang/StringBuilder;->append(I)Ljava/lang/StringBuilder;

    ldc " "

    invokevirtual Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    aload_3

    invokeinterface Ljava/util/function/Supplier;->get()Ljava/lang/Object;, 1

    checkcast Lq/Main;

    getfield Lq/Main;->base:I

    invokevirtual Ljava/lang/StringBuilder;->append(I)Ljava/lang/StringBuilder;

    ldc " "

    invokevirtual Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    aload v3

    iconst_3

    invokeinterface Ljava/util/function/IntFunction;->apply(I)Ljava/lang/Object;, 2

    checkcast Lq/Main;

    getfield Lq/Main;->base:I

    invokevirtual Ljava/lang/StringBuilder;->append(I)Ljava/lang/StringBuilder;

    invokevirtual Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    invokestatic Lq/Main;->p(Ljava/lang/Object;)V

    .line 34
    aload_0

    invokedynamic applyAsLong(Lq/Main;)Ljava/util/function/LongBinaryOperator;, invoke-static@Ljava/lang/invoke/LambdaMetafactory;->metafactory(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;, { (JJ)J, invoke-direct@Lq/Main;->lambda$run$0(JJ)J, (JJ)J }

    astore v4

    .line 35
    aload v4

    ldc2_w 0x800000000L

    ldc2_w 0x2L

    invokeinterface Ljava/util/function/LongBinaryOperator;->applyAsLong(JJ)J, 5

    invokestatic Ljava/lang/Long;->valueOf(J)Ljava/lang/Long;

    invokestatic Lq/Main;->p(Ljava/lang/Object;)V

    .line 36
    new Lq/Main$1;

    dup

    aload_0

    invokespecial Lq/Main$1;-><init>(Lq/Main;)V

    astore v5

    .line 37
    aload v5

    invokeinterface Lq/Main$Greeter;->twice()Ljava/util/function/Function;, 1

    ldc "x"

    invokeinterface Ljava/util/function/Function;->apply(Ljava/lang/Object;)Ljava/lang/Object;, 2

    invokestatic Lq/Main;->p(Ljava/lang/Object;)V

    .line 38
    invokedynamic greet()Lq/Main$Greeter;, invoke-static@Ljava/lang/invoke/LambdaMetafactory;->metafactory(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;, { (Ljava/lang/String;)Ljava/lang/String;, invoke-static@Lq/Main;->lambda$run$1(Ljava/lang/String;)Ljava/lang/String;, (Ljava/lang/String;)Ljava/lang/String; }

    astore v6

    .line 39
    aload v6

    invokeinterface Lq/Main$Greeter;->twice()Ljava/util/function/Function;, 1

    ldc "y"

    invokeinterface Ljava/util/function/Function;->apply(Ljava/lang/Object;)Ljava/lang/Object;, 2

    invokestatic Lq/Main;->p(Ljava/lang/Object;)V

    .line 40
    invokestatic Lq/Main$Nested;->maker()Ljava/util/function/Supplier;

    invokeinterface Ljava/util/function/Supplier;->get()Ljava/lang/Object;, 1

    checkcast Lq/Main$Nested;

    astore v7

    .line 41
    aload v7

    dup

    invokestatic Ljava/util/Objects;->requireNonNull(Ljava/lang/Object;)Ljava/lang/Object;

    pop

    invokedynamic get(Lq/Main$Nested;)Ljava/util/function/Supplier;, invoke-static@Ljava/lang/invoke/LambdaMetafactory;->metafactory(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;, { ()Ljava/lang/Object;, invoke-static@Lq/Main;->lambda$run$2(Lq/Main$Nested;)Ljava/lang/String;, ()Ljava/lang/String; }

    astore v8

    .line 42
    aload v8

    invokeinterface Ljava/util/function/Supplier;->get()Ljava/lang/Object;, 1

    invokestatic Lq/Main;->p(Ljava/lang/Object;)V

    .line 43
    aload_0

    invokedynamic run(Lq/Main;)Ljava/lang/Runnable;, invoke-static@Ljava/lang/invoke/LambdaMetafactory;->metafactory(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;, { ()V, invoke-direct@Lq/Main;->lambda$run$4()V, ()V }

    astore v9

    .line 47
    aload v9

    invokeinterface Ljava/lang/Runnable;->run()V, 1

    .line 48
    invokedynamic apply()Ljava/util/function/Function;, invoke-static@Ljava/lang/invoke/LambdaMetafactory;->metafactory(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;, { (Ljava/lang/Object;)Ljava/lang/Object;, invoke-instance@Ljava/lang/String;->length()I, (Ljava/lang/String;)Ljava/lang/Integer; }

    invokestatic interface Ljava/util/Comparator;->comparing(Ljava/util/function/Function;)Ljava/util/Comparator;

    astore v10

    .line 49
    new Ljava/util/ArrayList;

    dup

    iconst_3

    anewarray Ljava/lang/String;

    dup

    iconst_0

    ldc "ccc"

    aastore

    dup

    iconst_1

    ldc "a"

    aastore

    dup

    iconst_2

    ldc "bb"

    aastore

    invokestatic Ljava/util/Arrays;->asList([Ljava/lang/Object;)Ljava/util/List;

    invokespecial Ljava/util/ArrayList;-><init>(Ljava/util/Collection;)V

    astore v11

    .line 50
    aload v11

    aload v10

    invokestatic interface Ljava/util/Comparator;->reverseOrder()Ljava/util/Comparator;

    invokeinterface Ljava/util/Comparator;->thenComparing(Ljava/util/Comparator;)Ljava/util/Comparator;, 2

    invokestatic Ljava/util/Collections;->sort(Ljava/util/List;Ljava/util/Comparator;)V

    .line 51
    aload v11

    invokestatic Lq/Main;->p(Ljava/lang/Object;)V

    .line 52
    invokedynamic apply()Ljava/util/function/BiFunction;, invoke-static@Ljava/lang/invoke/LambdaMetafactory;->metafactory(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;, { (Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;, invoke-direct@Lq/Main;->add(I)I, (Lq/Main;Ljava/lang/Integer;)Ljava/lang/Integer; }

    astore v12

    .line 53
    aload v12

    aload_0

    iconst_1

    invokestatic Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;

    invokeinterface Ljava/util/function/BiFunction;->apply(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;, 3

    invokestatic Lq/Main;->p(Ljava/lang/Object;)V

    .line 54
    return
.end method

.method public static main([Ljava/lang/String;)V
    .limit stack 3
    .limit locals 3

    .line 57
    new Lq/Main;

    dup

    bipush 0xa

    invokespecial Lq/Main;-><init>(I)V

    invokevirtual Lq/Main;->run()V

    .line 58
    getstatic Lq/Main;->out:Ljava/util/List;

    invokeinterface Ljava/util/List;->iterator()Ljava/util/Iterator;, 1

    astore_1

    :goto_15
    aload_1

    invokeinterface Ljava/util/Iterator;->hasNext()Z, 1

    ifeq :cond_32

    aload_1

    invokeinterface Ljava/util/Iterator;->next()Ljava/lang/Object;, 1

    checkcast Ljava/lang/String;

    astore_2

    getstatic Ljava/lang/System;->out:Ljava/io/PrintStream;

    aload_2

    invokevirtual Ljava/io/PrintStream;->println(Ljava/lang/String;)V

    goto :goto_15

    .line 59
    :cond_32
    return
.end method

.method private synthetic lambda$run$4()V
    .limit stack 1
    .limit locals 2

    .line 44
    aload_0

    invokedynamic run(Lq/Main;)Ljava/lang/Runnable;, invoke-static@Ljava/lang/invoke/LambdaMetafactory;->metafactory(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;, { ()V, invoke-direct@Lq/Main;->lambda$run$3()V, ()V }

    astore_1

    .line 45
    aload_1

    invokeinterface Ljava/lang/Runnable;->run()V, 1

    .line 46
    return
.end method

.method private synthetic lambda$run$3()V
    .limit stack 2
    .limit locals 1

    .line 44
    new Ljava/lang/StringBuilder;

    dup

    invokespecial Ljava/lang/StringBuilder;-><init>()V

    ldc "inner-run base="

    invokevirtual Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    aload_0

    getfield Lq/Main;->base:I

    invokevirtual Ljava/lang/StringBuilder;->append(I)Ljava/lang/StringBuilder;

    invokevirtual Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    invokestatic Lq/Main;->p(Ljava/lang/Object;)V

    return
.end method

.method private static synthetic lambda$run$2(Lq/Main$Nested;)Ljava/lang/String;
    .limit stack 1
    .limit locals 1

    .line 41
    aload_0

    invokestatic Lq/Main$Nested;->access$000(Lq/Main$Nested;)Ljava/lang/String;

    areturn
.end method

.method private static synthetic lambda$run$1(Ljava/lang/String;)Ljava/lang/String;
    .limit stack 2
    .limit locals 1

    .line 38
    new Ljava/lang/StringBuilder;

    dup

    invokespecial Ljava/lang/StringBuilder;-><init>()V

    ldc "["

    invokevirtual Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    aload_0

    invokevirtual Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    ldc "]"

    invokevirtual Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invokevirtual Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    areturn
.end method

.method private synthetic lambda$run$0(JJ)J
    .limit stack 5
    .limit locals 5

    .line 34
    aload_0

    lload_1

    lload_3

    l2d

    invokespecial Lq/Main;->wide(JD)J

    lreturn
.end method

.method static <clinit>()V
    .limit stack 2
    .limit locals 0

    .line 7
    new Ljava/util/ArrayList;

    dup

    invokespecial Ljava/util/ArrayList;-><init>()V

    putstatic Lq/Main;->out:Ljava/util/List;

    return
.end method

# inner classes
.inner Lq/Main$1;
.inner static interface abstract Lq/Main$Greeter; of Lq/Main; as Greeter
.inner static Lq/Main$Nested; of Lq/Main; as Nested
.inner public static final Ljava/lang/invoke/MethodHandles$Lookup; of Ljava/lang/invoke/MethodHandles; as Lookup

.method static -$$Lambda$access$0(Lq/Main;I)I
    .limit stack 1
    .limit locals 2
    iconst_0
    ireturn
.end method
