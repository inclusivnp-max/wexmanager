# Промежуточный класс: его конструкторы при переходниках выполняться не должны.
.class public Lb/B;
.super Lb/A;

.field public static count:I

.method public constructor <init>(I)V
    .registers 3
    invoke-direct {p0, p1}, Lb/A;-><init>(I)V
    sget v0, Lb/B;->count:I
    add-int/lit8 v0, v0, 1
    sput v0, Lb/B;->count:I
    return-void
.end method

# Занимает дескриптор первого переходника (I, Void): переходник B получает больше Void.
.method public constructor <init>(ILjava/lang/Void;)V
    .registers 4
    invoke-direct {p0, p1}, Lb/A;-><init>(I)V
    sget v0, Lb/B;->count:I
    add-int/lit8 v0, v0, 100
    sput v0, Lb/B;->count:I
    return-void
.end method
