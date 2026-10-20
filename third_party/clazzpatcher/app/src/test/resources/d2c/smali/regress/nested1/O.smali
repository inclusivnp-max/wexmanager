.class public Lp/O;
.super Ljava/lang/Object;

.annotation system Ldalvik/annotation/MemberClasses;
    value = {
        Lp/O$M;
    }
.end annotation

.method public constructor <init>()V
    .registers 1
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
    return-void
.end method

.method public anon()Ljava/lang/Object;
    .registers 2
    new-instance v0, Lp/O$1;
    invoke-direct {v0}, Lp/O$1;-><init>()V
    return-object v0
.end method
