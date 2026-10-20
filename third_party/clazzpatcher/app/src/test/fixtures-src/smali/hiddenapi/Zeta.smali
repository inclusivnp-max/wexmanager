# Ещё один класс с флагами hidden API (после класса без флагов).
.class public Lfx/hid/Zeta;
.super Lfx/hid/Hidden;
.source "Zeta.smali"


# direct methods
.method public constructor greylist <init>()V
    .registers 1

    invoke-direct {p0}, Lfx/hid/Hidden;-><init>()V

    return-void
.end method

.method private static blacklist core-platform-api z(J)J
    .registers 2

    return-wide p0
.end method
