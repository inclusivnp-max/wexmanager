# invoke-super на default-метод интерфейса JDK (Map.getOrDefault), который класс реализует через
# суперкласс (HashMap): в JVM invokespecial допустим только к прямому суперинтерфейсу — Map
# добавляется в прямые. Семантика ART: вызывается default-метод Map (он зовёт переопределённый get),
# а не HashMap.getOrDefault.
.class public LM;
.super Ljava/util/HashMap;

.method public constructor <init>()V
    .registers 1
    invoke-direct {p0}, Ljava/util/HashMap;-><init>()V
    return-void
.end method

.method public get(Ljava/lang/Object;)Ljava/lang/Object;
    .registers 3
    const-string v0, "k"
    invoke-virtual {v0, p1}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
    move-result v0
    if-eqz v0, :other
    const-string v0, "from-get"
    return-object v0
    :other
    invoke-super {p0, p1}, Ljava/util/HashMap;->get(Ljava/lang/Object;)Ljava/lang/Object;
    move-result-object v0
    return-object v0
.end method

.method public getOrDefault(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
    .registers 4
    invoke-super {p0, p1, p2}, Ljava/util/Map;->getOrDefault(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
    move-result-object v0
    return-object v0
.end method
