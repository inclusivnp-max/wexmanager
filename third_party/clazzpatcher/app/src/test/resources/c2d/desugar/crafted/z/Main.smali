.class public super Lz/Main;
.super Ljava/lang/Object;
.source "Main.java"
.version 65

.method public <init>()V
    .limit stack 1
    .limit locals 1

    .line 6
    aload_0

    invokespecial Ljava/lang/Object;-><init>()V

    return
.end method

.method static roundTrip(Ljava/lang/Object;)Ljava/lang/Object;
    .limit stack 5
    .limit locals 5
    .throws Ljava/lang/Exception;

    .line 9
    new Ljava/io/ByteArrayOutputStream;

    dup

    invokespecial Ljava/io/ByteArrayOutputStream;-><init>()V

    astore_1

    .line 10
    new Ljava/io/ObjectOutputStream;

    dup

    aload_1

    invokespecial Ljava/io/ObjectOutputStream;-><init>(Ljava/io/OutputStream;)V

    astore_2

    :try_start_11
    aload_2

    aload_0

    invokevirtual Ljava/io/ObjectOutputStream;->writeObject(Ljava/lang/Object;)V

    :try_end_16
    aload_2

    invokevirtual Ljava/io/ObjectOutputStream;->close()V

    goto :goto_2f

    :catch_1d
    astore_3

    :try_start_1e
    aload_2

    invokevirtual Ljava/io/ObjectOutputStream;->close()V

    :try_end_22
    goto :goto_2d

    :catch_25
    astore v3

    aload_3

    aload v3

    invokevirtual Ljava/lang/Throwable;->addSuppressed(Ljava/lang/Throwable;)V

    :goto_2d
    aload_3

    athrow

    .line 11
    :goto_2f
    new Ljava/io/ObjectInputStream;

    dup

    new Ljava/io/ByteArrayInputStream;

    dup

    aload_1

    invokevirtual Ljava/io/ByteArrayOutputStream;->toByteArray()[B

    invokespecial Ljava/io/ByteArrayInputStream;-><init>([B)V

    invokespecial Ljava/io/ObjectInputStream;-><init>(Ljava/io/InputStream;)V

    astore_2

    :try_start_42
    aload_2

    invokevirtual Ljava/io/ObjectInputStream;->readObject()Ljava/lang/Object;

    astore_3

    :try_end_47
    aload_2

    invokevirtual Ljava/io/ObjectInputStream;->close()V

    aload_3

    areturn

    :catch_4d
    astore_3

    :try_start_4e
    aload_2

    invokevirtual Ljava/io/ObjectInputStream;->close()V

    :try_end_52
    goto :goto_5d

    :catch_55
    astore v3

    aload_3

    aload v3

    invokevirtual Ljava/lang/Throwable;->addSuppressed(Ljava/lang/Throwable;)V

    :goto_5d
    aload_3

    athrow

    .catch Ljava/lang/Throwable; {:try_start_11 .. :try_end_16} :catch_1d
    .catch Ljava/lang/Throwable; {:try_start_1e .. :try_end_22} :catch_25
    .catch Ljava/lang/Throwable; {:try_start_42 .. :try_end_47} :catch_4d
    .catch Ljava/lang/Throwable; {:try_start_4e .. :try_end_52} :catch_55
.end method

.method public static main([Ljava/lang/String;)V
    .limit stack 4
    .limit locals 10
    .throws Ljava/lang/Exception;

    .line 14
    invokedynamic get()Lz/Main$SerSup;, invoke-static@Ljava/lang/invoke/LambdaMetafactory;->altMetafactory(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;, { ()Ljava/lang/Object;, invoke-static@Lz/Main;->lambda$main$ce6f152a$1()Ljava/lang/String;, ()Ljava/lang/String;, 0x2 }

    astore_1

    .line 15
    bipush 0x29

    istore_2

    .line 16
    iload_2

    invokedynamic get(I)Lz/Main$SerSup;, invoke-static@Ljava/lang/invoke/LambdaMetafactory;->altMetafactory(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;, { ()Ljava/lang/Object;, invoke-static@Lz/Main;->lambda$main$2b02fd21$1(I)Ljava/lang/String;, ()Ljava/lang/String;, 0x5, 0x0 }

    astore_3

    .line 17
    invokedynamic get()Ljava/util/function/Supplier;, invoke-static@Ljava/lang/invoke/LambdaMetafactory;->altMetafactory(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;, { ()Ljava/lang/Object;, invoke-static@Lz/Main;->lambda$main$ce6f1568$1()Ljava/lang/String;, ()Ljava/lang/String;, 0x5, 0x0 }

    checkcast Ljava/io/Serializable;

    checkcast Ljava/util/function/Supplier;

    astore v3

    .line 18
    invokedynamic run()Ljava/lang/Runnable;, invoke-static@Ljava/lang/invoke/LambdaMetafactory;->metafactory(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;, { ()V, invoke-static@Lz/Main;->lambda$main$0()V, ()V }

    astore v4

    .line 19
    aload_1

    invokestatic Lz/Main;->roundTrip(Ljava/lang/Object;)Ljava/lang/Object;

    checkcast Lz/Main$SerSup;

    astore v5

    .line 20
    aload_3

    invokestatic Lz/Main;->roundTrip(Ljava/lang/Object;)Ljava/lang/Object;

    checkcast Lz/Main$SerSup;

    astore v6

    .line 21
    aload v3

    invokestatic Lz/Main;->roundTrip(Ljava/lang/Object;)Ljava/lang/Object;

    checkcast Ljava/util/function/Supplier;

    astore v7

    .line 22
    getstatic Ljava/lang/System;->out:Ljava/io/PrintStream;

    aload v5

    invokeinterface Lz/Main$SerSup;->get()Ljava/lang/Object;, 1

    checkcast Ljava/lang/String;

    aload v6

    invokeinterface Lz/Main$SerSup;->get()Ljava/lang/Object;, 1

    checkcast Ljava/lang/String;

    aload v7

    invokeinterface Ljava/util/function/Supplier;->get()Ljava/lang/Object;, 1

    checkcast Ljava/lang/String;

    invokedynamic makeConcatWithConstants(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;, invoke-static@Ljava/lang/invoke/StringConcatFactory;->makeConcatWithConstants(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;, { "\u0001 \u0001 \u0001" }

    invokevirtual Ljava/io/PrintStream;->println(Ljava/lang/String;)V

    .line 23
    :try_start_69
    aload v4

    invokestatic Lz/Main;->roundTrip(Ljava/lang/Object;)Ljava/lang/Object;

    pop

    getstatic Ljava/lang/System;->out:Ljava/io/PrintStream;

    ldc "nonSer serialized?!"

    invokevirtual Ljava/io/PrintStream;->println(Ljava/lang/String;)V

    :try_end_77
    goto :goto_94

    :catch_7a
    astore v8

    getstatic Ljava/lang/System;->out:Ljava/io/PrintStream;

    aload v8

    invokevirtual Ljava/io/NotSerializableException;->getMessage()Ljava/lang/String;

    ifnull :cond_8b

    iconst_1

    goto :goto_8c

    :cond_8b
    iconst_0

    :goto_8c
    invokedynamic makeConcatWithConstants(Z)Ljava/lang/String;, invoke-static@Ljava/lang/invoke/StringConcatFactory;->makeConcatWithConstants(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;, { "NSE \u0001" }

    invokevirtual Ljava/io/PrintStream;->println(Ljava/lang/String;)V

    .line 24
    :goto_94
    return

    .catch Ljava/io/NotSerializableException; {:try_start_69 .. :try_end_77} :catch_7a
.end method

.method private static synthetic $deserializeLambda$(Ljava/lang/invoke/SerializedLambda;)Ljava/lang/Object;
    .limit stack 3
    .limit locals 3

    .line 6
    aload_0

    invokevirtual Ljava/lang/invoke/SerializedLambda;->getImplMethodName()Ljava/lang/String;

    astore_1

    iconst_m1

    istore_2

    aload_1

    invokevirtual Ljava/lang/String;->hashCode()I

    lookupswitch default :sswitch_53
        -0x278edf56 -> :sswitch_48
        0x2851b5a0 -> :sswitch_2c
        0x2852ed33 -> :sswitch_3a
    .end lookupswitch

    :sswitch_2c
    aload_1

    ldc "lambda$main$ce6f152a$1"

    invokevirtual Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    ifeq :cond_53

    iconst_0

    istore_2

    goto :goto_53

    :sswitch_3a
    aload_1

    ldc "lambda$main$ce6f1568$1"

    invokevirtual Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    ifeq :cond_53

    iconst_1

    istore_2

    goto :goto_53

    :sswitch_48
    aload_1

    ldc "lambda$main$2b02fd21$1"

    invokevirtual Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    ifeq :cond_53

    iconst_2

    istore_2

    :sswitch_53
    :cond_53
    :goto_53
    iload_2

    tableswitch 0x0 default :sswitch_15c
        :pswitch_70
        :pswitch_bb
        :pswitch_106
    .end tableswitch

    :pswitch_70
    aload_0

    invokevirtual Ljava/lang/invoke/SerializedLambda;->getImplMethodKind()I

    bipush 0x6

    if_icmpne :cond_15c

    aload_0

    invokevirtual Ljava/lang/invoke/SerializedLambda;->getFunctionalInterfaceClass()Ljava/lang/String;

    ldc "z/Main$SerSup"

    invokevirtual Ljava/lang/Object;->equals(Ljava/lang/Object;)Z

    ifeq :cond_15c

    aload_0

    invokevirtual Ljava/lang/invoke/SerializedLambda;->getFunctionalInterfaceMethodName()Ljava/lang/String;

    ldc "get"

    invokevirtual Ljava/lang/Object;->equals(Ljava/lang/Object;)Z

    ifeq :cond_15c

    aload_0

    invokevirtual Ljava/lang/invoke/SerializedLambda;->getFunctionalInterfaceMethodSignature()Ljava/lang/String;

    ldc "()Ljava/lang/Object;"

    invokevirtual Ljava/lang/Object;->equals(Ljava/lang/Object;)Z

    ifeq :cond_15c

    aload_0

    invokevirtual Ljava/lang/invoke/SerializedLambda;->getImplClass()Ljava/lang/String;

    ldc "z/Main"

    invokevirtual Ljava/lang/Object;->equals(Ljava/lang/Object;)Z

    ifeq :cond_15c

    aload_0

    invokevirtual Ljava/lang/invoke/SerializedLambda;->getImplMethodSignature()Ljava/lang/String;

    ldc "()Ljava/lang/String;"

    invokevirtual Ljava/lang/Object;->equals(Ljava/lang/Object;)Z

    ifeq :cond_15c

    invokedynamic get()Lz/Main$SerSup;, invoke-static@Ljava/lang/invoke/LambdaMetafactory;->altMetafactory(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;, { ()Ljava/lang/Object;, invoke-static@Lz/Main;->lambda$main$ce6f152a$1()Ljava/lang/String;, ()Ljava/lang/String;, 0x5, 0x0 }

    areturn

    :pswitch_bb
    aload_0

    invokevirtual Ljava/lang/invoke/SerializedLambda;->getImplMethodKind()I

    bipush 0x6

    if_icmpne :cond_15c

    aload_0

    invokevirtual Ljava/lang/invoke/SerializedLambda;->getFunctionalInterfaceClass()Ljava/lang/String;

    ldc "java/util/function/Supplier"

    invokevirtual Ljava/lang/Object;->equals(Ljava/lang/Object;)Z

    ifeq :cond_15c

    aload_0

    invokevirtual Ljava/lang/invoke/SerializedLambda;->getFunctionalInterfaceMethodName()Ljava/lang/String;

    ldc "get"

    invokevirtual Ljava/lang/Object;->equals(Ljava/lang/Object;)Z

    ifeq :cond_15c

    aload_0

    invokevirtual Ljava/lang/invoke/SerializedLambda;->getFunctionalInterfaceMethodSignature()Ljava/lang/String;

    ldc "()Ljava/lang/Object;"

    invokevirtual Ljava/lang/Object;->equals(Ljava/lang/Object;)Z

    ifeq :cond_15c

    aload_0

    invokevirtual Ljava/lang/invoke/SerializedLambda;->getImplClass()Ljava/lang/String;

    ldc "z/Main"

    invokevirtual Ljava/lang/Object;->equals(Ljava/lang/Object;)Z

    ifeq :cond_15c

    aload_0

    invokevirtual Ljava/lang/invoke/SerializedLambda;->getImplMethodSignature()Ljava/lang/String;

    ldc "()Ljava/lang/String;"

    invokevirtual Ljava/lang/Object;->equals(Ljava/lang/Object;)Z

    ifeq :cond_15c

    invokedynamic get()Ljava/util/function/Supplier;, invoke-static@Ljava/lang/invoke/LambdaMetafactory;->altMetafactory(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;, { ()Ljava/lang/Object;, invoke-static@Lz/Main;->lambda$main$ce6f1568$1()Ljava/lang/String;, ()Ljava/lang/String;, 0x5, 0x0 }

    areturn

    :pswitch_106
    aload_0

    invokevirtual Ljava/lang/invoke/SerializedLambda;->getImplMethodKind()I

    bipush 0x6

    if_icmpne :cond_15c

    aload_0

    invokevirtual Ljava/lang/invoke/SerializedLambda;->getFunctionalInterfaceClass()Ljava/lang/String;

    ldc "z/Main$SerSup"

    invokevirtual Ljava/lang/Object;->equals(Ljava/lang/Object;)Z

    ifeq :cond_15c

    aload_0

    invokevirtual Ljava/lang/invoke/SerializedLambda;->getFunctionalInterfaceMethodName()Ljava/lang/String;

    ldc "get"

    invokevirtual Ljava/lang/Object;->equals(Ljava/lang/Object;)Z

    ifeq :cond_15c

    aload_0

    invokevirtual Ljava/lang/invoke/SerializedLambda;->getFunctionalInterfaceMethodSignature()Ljava/lang/String;

    ldc "()Ljava/lang/Object;"

    invokevirtual Ljava/lang/Object;->equals(Ljava/lang/Object;)Z

    ifeq :cond_15c

    aload_0

    invokevirtual Ljava/lang/invoke/SerializedLambda;->getImplClass()Ljava/lang/String;

    ldc "z/Main"

    invokevirtual Ljava/lang/Object;->equals(Ljava/lang/Object;)Z

    ifeq :cond_15c

    aload_0

    invokevirtual Ljava/lang/invoke/SerializedLambda;->getImplMethodSignature()Ljava/lang/String;

    ldc "(I)Ljava/lang/String;"

    invokevirtual Ljava/lang/Object;->equals(Ljava/lang/Object;)Z

    ifeq :cond_15c

    aload_0

    iconst_0

    invokevirtual Ljava/lang/invoke/SerializedLambda;->getCapturedArg(I)Ljava/lang/Object;

    checkcast Ljava/lang/Integer;

    invokevirtual Ljava/lang/Integer;->intValue()I

    invokedynamic get(I)Lz/Main$SerSup;, invoke-static@Ljava/lang/invoke/LambdaMetafactory;->altMetafactory(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;, { ()Ljava/lang/Object;, invoke-static@Lz/Main;->lambda$main$2b02fd21$1(I)Ljava/lang/String;, ()Ljava/lang/String;, 0x5, 0x0 }

    areturn

    :sswitch_15c
    :cond_15c
    new Ljava/lang/IllegalArgumentException;

    dup

    ldc "Invalid lambda deserialization"

    invokespecial Ljava/lang/IllegalArgumentException;-><init>(Ljava/lang/String;)V

    athrow
.end method

.method private static synthetic lambda$main$0()V
    .limit stack 0
    .limit locals 0

    .line 18
    return
.end method

.method private static synthetic lambda$main$ce6f1568$1()Ljava/lang/String;
    .limit stack 1
    .limit locals 0

    .line 17
    ldc "intersect"

    areturn
.end method

.method private static synthetic lambda$main$2b02fd21$1(I)Ljava/lang/String;
    .limit stack 2
    .limit locals 1

    .line 16
    iload_0

    iconst_1

    iadd

    invokedynamic makeConcatWithConstants(I)Ljava/lang/String;, invoke-static@Ljava/lang/invoke/StringConcatFactory;->makeConcatWithConstants(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;, { "cap\u0001" }

    areturn
.end method

.method private static synthetic lambda$main$ce6f152a$1()Ljava/lang/String;
    .limit stack 1
    .limit locals 0

    .line 14
    ldc "plain"

    areturn
.end method

# inner classes
.inner static interface abstract Lz/Main$SerSup; of Lz/Main; as SerSup
.inner public static final Ljava/lang/invoke/MethodHandles$Lookup; of Ljava/lang/invoke/MethodHandles; as Lookup

# nest members
.nestmember Lz/Main$SerSup;
