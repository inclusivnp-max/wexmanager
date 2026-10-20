# Из art/test/535-regression-const-val: φ из двух констант, используемый и как float, и как int.
.class public LTestCase;
.super Ljava/lang/Object;

.method public static testCase(ZZ)I
  .registers 5
  if-eqz p0, :else
  const v0, 0x0
  goto :merge
  :else
  const/high16 v0, 0xbe800000
  :merge
  if-eqz p1, :return
  float-to-int v0, v0
  :return
  return v0
.end method

.method public static main([Ljava/lang/String;)V
  .registers 3
  sget-object v0, Ljava/lang/System;->out:Ljava/io/PrintStream;
  const/4 v1, 0
  const/4 v2, 1
  invoke-static {v1, v2}, LTestCase;->testCase(ZZ)I
  move-result v1
  invoke-virtual {v0, v1}, Ljava/io/PrintStream;->println(I)V
  return-void
.end method
