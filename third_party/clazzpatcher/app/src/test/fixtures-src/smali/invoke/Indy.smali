# invoke-custom (call sites с аргументами всех видов), const-method-handle всех видов, const-method-type,
# invoke-polymorphic(/range), method handle / method type в значениях аннотаций. Собирается: smali a --api 28.
.class public Lfx/ic/Indy;
.super Ljava/lang/Object;
.source "Indy.smali"

.annotation runtime Lfx/ic/Holder;
    mt = (IJ)Ljava/lang/String;
    mh = invoke-static@Lfx/ic/Indy;->target(II)I
    arr = {
        (I)V,
        static-get@Lfx/ic/Indy;->sf:I
    }
.end annotation


# static fields
.field public static sf:I


# instance fields
.field public f:Ljava/lang/String;


# direct methods
.method public constructor <init>()V
    .registers 1

    invoke-direct {p0}, Ljava/lang/Object;-><init>()V

    return-void
.end method

.method public static bsm(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;
    .registers 4

    const/4 v0, 0x0

    return-object v0
.end method

.method public static varargs bsmArgs(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;
    .registers 5

    const/4 v0, 0x0

    return-object v0
.end method

.method private priv()V
    .registers 1

    return-void
.end method

.method public static target(II)I
    .registers 3

    add-int v0, p0, p1

    return v0
.end method

.method public static indy(IIIIII)I
    .registers 10

    invoke-custom {p0, p1}, call_site_0("run", (II)I)@Lfx/ic/Indy;->bsm(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;

    move-result v0

    invoke-custom {p0, p1}, call_site_1("run2", (II)I, 0x7, "s\n\"", 0x10L, 1.5f, 2.0, Lfx/ic/Indy;, (I)V, invoke-static@Lfx/ic/Indy;->target(II)I, 0x5t, 0x6s, 'c', true, null)@Lfx/ic/Indy;->bsmArgs(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;

    move-result v1

    invoke-custom/range {p0 .. p5}, call_site_2("wide", (IIIIII)I)@Lfx/ic/Indy;->bsm(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;

    move-result v2

    invoke-custom {p0, p1}, call_site_3("run", (II)I)@Lfx/ic/Indy;->bsm(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;

    move-result v3

    add-int/2addr v0, v1

    add-int/2addr v0, v2

    add-int/2addr v0, v3

    return v0
.end method

.method public static handles()V
    .registers 2

    const-method-handle v0, static-put@Lfx/ic/Indy;->sf:I

    const-method-handle v0, static-get@Lfx/ic/Indy;->sf:I

    const-method-handle v0, instance-put@Lfx/ic/Indy;->f:Ljava/lang/String;

    const-method-handle v0, instance-get@Lfx/ic/Indy;->f:Ljava/lang/String;

    const-method-handle v0, invoke-static@Lfx/ic/Indy;->target(II)I

    const-method-handle v0, invoke-instance@Ljava/lang/Object;->toString()Ljava/lang/String;

    const-method-handle v0, invoke-constructor@Lfx/ic/Indy;-><init>()V

    const-method-handle v0, invoke-direct@Lfx/ic/Indy;->priv()V

    const-method-handle v0, invoke-interface@Ljava/lang/Runnable;->run()V

    const-method-type v1, (IJ)Ljava/lang/String;

    const-method-type v1, ()V

    return-void
.end method

.method public static poly(Ljava/lang/invoke/MethodHandle;II)I
    .registers 8

    invoke-polymorphic {p0, p1, p2}, Ljava/lang/invoke/MethodHandle;->invokeExact([Ljava/lang/Object;)Ljava/lang/Object;, (II)I

    move-result v0

    invoke-polymorphic {p0}, Ljava/lang/invoke/MethodHandle;->invoke([Ljava/lang/Object;)Ljava/lang/Object;, ()V

    invoke-polymorphic/range {p0 .. p2}, Ljava/lang/invoke/MethodHandle;->invokeExact([Ljava/lang/Object;)Ljava/lang/Object;, (II)I

    move-result v1

    add-int/2addr v0, v1

    return v0
.end method
