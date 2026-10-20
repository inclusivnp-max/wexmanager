# Флаги hidden API (секция hiddenapi_class_data). Собирается: smali a --api 29.
.class public Lfx/hid/Hidden;
.super Ljava/lang/Object;
.source "Hidden.smali"


# static fields
.field public static whitelist sA:I

.field public static greylist sB:I = 0x5


# instance fields
.field public blacklist iC:I

.field private greylist-max-o iD:J


# direct methods
.method public constructor whitelist <init>()V
    .registers 1

    invoke-direct {p0}, Ljava/lang/Object;-><init>()V

    return-void
.end method

.method public static greylist-max-p core-platform-api a()V
    .registers 0

    return-void
.end method


# virtual methods
.method public greylist-max-q test-api b()V
    .registers 1

    return-void
.end method

.method public blacklist c()V
    .registers 1

    return-void
.end method

.method public greylist-max-r d()V
    .registers 1

    return-void
.end method
