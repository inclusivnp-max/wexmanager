.class public super LCv;
.super Ljava/lang/Object;
.source "Cv.java"
.version 52

.field static Z:Z = 0x2

.field static B:B = 0x12c

.field static C:C = 0x11170

.field static S:S

.field static NF:I = 0x7

.method public <init>()V
    .limit stack 1
    .limit locals 1

    .line 1
    aload_0

    invokespecial Ljava/lang/Object;-><init>()V

    return
.end method

.method public static main([Ljava/lang/String;)V
    .limit stack 3
    .limit locals 1

    .line 3
    getstatic Ljava/lang/System;->out:Ljava/io/PrintStream;

    new Ljava/lang/StringBuilder;

    dup

    invokespecial Ljava/lang/StringBuilder;-><init>()V

    getstatic LCv;->Z:Z

    invokevirtual Ljava/lang/StringBuilder;->append(Z)Ljava/lang/StringBuilder;

    ldc " "

    invokevirtual Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    getstatic LCv;->B:B

    invokevirtual Ljava/lang/StringBuilder;->append(I)Ljava/lang/StringBuilder;

    ldc " "

    invokevirtual Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    getstatic LCv;->C:C

    invokevirtual Ljava/lang/StringBuilder;->append(I)Ljava/lang/StringBuilder;

    ldc " "

    invokevirtual Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    getstatic LCv;->S:S

    invokevirtual Ljava/lang/StringBuilder;->append(I)Ljava/lang/StringBuilder;

    ldc " "

    invokevirtual Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    getstatic LCv;->NF:I

    invokevirtual Ljava/lang/StringBuilder;->append(I)Ljava/lang/StringBuilder;

    invokevirtual Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    invokevirtual Ljava/io/PrintStream;->println(Ljava/lang/String;)V

    return
.end method

