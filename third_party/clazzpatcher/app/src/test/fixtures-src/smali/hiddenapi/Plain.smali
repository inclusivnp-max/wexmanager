# Класс без флагов hidden API рядом с классами с флагами.
.class public Lfx/hid/Plain;
.super Ljava/lang/Object;
.source "Plain.smali"


# instance fields
.field public x:I


# direct methods
.method public constructor <init>()V
    .registers 1

    invoke-direct {p0}, Ljava/lang/Object;-><init>()V

    return-void
.end method
