# record P(@Ann List<String> xs) — как его описывает d8 для API 34+.
.class public final Lr/P;
.super Ljava/lang/Record;
.annotation system Ldalvik/annotation/Record;
    componentNames = {
        "xs"
    }
    componentTypes = {
        Ljava/util/List;
    }
    componentSignatures = {
        .subannotation Ldalvik/annotation/Signature;
            value = {
                "Ljava/util/List<",
                "Ljava/lang/String;",
                ">;"
            }
        .end subannotation
    }
    componentAnnotationVisibilities = {
        {
            0x1t
        }
    }
    componentAnnotations = {
        {
            .subannotation Lr/Ann;
            .end subannotation
        }
    }
.end annotation
.field private final xs:Ljava/util/List;
.method public constructor <init>(Ljava/util/List;)V
    .registers 2
    invoke-direct {p0}, Ljava/lang/Record;-><init>()V
    iput-object p1, p0, Lr/P;->xs:Ljava/util/List;
    return-void
.end method
.method public xs()Ljava/util/List;
    .registers 2
    iget-object v0, p0, Lr/P;->xs:Ljava/util/List;
    return-object v0
.end method
.method public equals(Ljava/lang/Object;)Z
    .registers 2
    const/4 v0, 0
    return v0
.end method
.method public hashCode()I
    .registers 2
    const/4 v0, 0
    return v0
.end method
.method public toString()Ljava/lang/String;
    .registers 2
    const-string v0, "P"
    return-object v0
.end method
