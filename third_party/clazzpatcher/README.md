# clazzpatcher

Чтение, запись, патчинг и взаимное преобразование Android DEX, smali и JVM `.class` на Kotlin — без сторонних библиотек и без рефлексии в рантайме (только kotlin-stdlib, код совместим с Java 8 и Android).

## Пакеты

Каждый пакет зависит только от нижележащих; «соседи» (dsmali ⟂ csmali, dasm ⟂ casm, c2d ⟂ d2c) друг о друге не знают.

```
            DEX                                   JVM .class
  dex     модель, DexReader/DexWriter        clazz   модель, ClassReader/ClassWriter (JVMS §4)
  dasm    пулы, DSL, (диз)ассемблер DEX      casm    DSL, сборка байткода, фреймы StackMapTable
  dsmali  DEX ⇄ smali (baksmali 3.0.9)       csmali  .class ⇄ smali-синтаксис для JVM-байткода

  c2d  .class → DEX   (clazz, dex, dasm)
  d2c  DEX → .class   (dex, dasm, casm, clazz)
  com.tim.Main — командная строка (связывает всё)
```

- `com.tim.dex` — `DexReader` / `DexWriter` на индексной модели `DexFile`. Писатель сам сортирует и дедуплицирует пулы и переписывает все ссылки, в том числе внутри байткода.
- `com.tim.dasm` — изменяемые пулы (`DexAsm`), Kotlin DSL для создания и патчинга классов, ассемблер и дизассемблер инструкций.
- `com.tim.dsmali` — DEX ⇄ smali, синтаксис совместим с baksmali/smali 3.0.9 и apktool.
- `com.tim.clazz` — `.class` по JVMS §4 (Java 1.1–26), побайтовый round-trip.
- `com.tim.casm` — сборка JVM-классов: `CodeDsl` (метки, wide-формы, расширение дальних переходов), `CodeAssembler`, расчёт `max_stack`/`max_locals` и сжатых фреймов `StackMapTable` (`frames/`), иерархия классов для слияния типов (`hierarchy/`: `MapHierarchy`, `ClassPathHierarchy` по jar/каталогам/`jrt:/`), DSL `Casm.create(...).useDsl { createClass { … } }` и патчи `Casm.load(...).useDsl { patchClass { … } }`.
- `com.tim.csmali` — `.class` ⇄ текст в синтаксисе smali (`Smali.classToSmali`, `Smali.smaliToClass`, `disassembleAll`/`assembleAll` параллельно, `saveDir`/`loadDir`).
- `com.tim.c2d` — `.class` → DEX (`ClassToDex.convert`, `convertMultiDex`).
- `com.tim.d2c` — DEX → `.class` (`DexToClass.convert`, `convertToBytes`).

## Сборка и запуск

```
./gradlew :app:fatJar          # app/build/libs/clazzpatcher.jar (java -jar ...)
./clazzpatcher --help          # обёртка: сама пересобирает jar при изменении исходников

./clazzpatcher d classes.dex -o smali            # DEX (или APK) → smali
./clazzpatcher a smali -o out.dex                # smali → DEX
./clazzpatcher cd lib.jar -o lib_csmali          # .class / каталог / jar → csmali
./clazzpatcher ca lib_csmali -o lib.jar          # csmali → .class (каталог или jar), фреймы заново
./clazzpatcher c2d lib.jar -o out                # .class → classes.dex, classes2.dex, …
./clazzpatcher d2c app.apk -o app.jar --cp android.jar   # DEX/APK → .class
./clazzpatcher info classes.dex                  # версия, счётчики, секции
./clazzpatcher rt classes.dex                    # чтение/запись DEX без изменений
./clazzpatcher class-rt Main.class               # то же для .class
./clazzpatcher bench classes.dex -n 3            # время каждой фазы
```

Программно: `com.tim.dsmali.Smali` / `com.tim.csmali.Smali`, `DexReader`/`DexWriter`, `ClassReader`/`ClassWriter`, `DexAsm.load(...).useDsl { ... }`, `Casm.create(...).useDsl { ... }`, `ClassToDex`, `DexToClass`.

**CodeAssist (Android).** `app/module.toml` собирает `src/main/kotlin`. `Main.main` принимает аргументы командной строки (без них печатает справку); из кода — `Main.run(arrayOf("d", "classes.dex", "-o", "smali"))`, `System.exit` он не вызывает.

## csmali: синтаксис

Один файл — один класс. Регистры — локалы JVM: `pN` — слот N из области параметров (у нестатического метода `p0` — `this`), `vN` — слот (слоты параметров + N). Короткие формы (`iload_1`) печатаются как есть, `wide` с малым индексом — с префиксом `wide`, поэтому сборка даёт те же байты.

```
.class public final super Lcom/example/A;
.super Ljava/lang/Object;
.source "A.java"
.version 61
.signature "Ljava/lang/Object;"

.method public static max(II)I
    .limit stack 2
    .limit locals 2
    .param p0, "a"                                  # запись MethodParameters (+ флаги: , 0x10)
    iload_0
    iload_1
    if_icmple :cond_7
    iload_0
    ireturn
    :cond_7
    iload_1
    ireturn
.end method
```

- Ссылки: `Lowner;->name:Ltype;`, `Lowner;->name(params)ret`; на метод интерфейса вне `invokeinterface` — `interface Lowner;->m()V`.
- `ldc`/`ldc_w`/`ldc2_w`: числа, строки, типы (`[I` — массив), method type `(I)V`, handle `invoke-static@Lc;->m()V`, `.dynamic имя:тип, handle, { аргументы }`.
- `invokedynamic имя(дескриптор), handle, { аргументы bootstrap }`; BootstrapMethods собирается автоматически.
- `tableswitch 0x1 default :d` / `lookupswitch default :d` — цели построчно, затем `.end tableswitch`.
- `.catch Ltype; {:start .. :end} :handler` / `.catchall …` — в порядке таблицы исключений (приоритет).
- `.line`, `.local reg, "имя":тип[, "сигнатура"]`, `.end local reg[, "имя"]` (имя — когда на слоте несколько переменных).
- Аннотации `.annotation runtime|build …` (build = RuntimeInvisible*), `.typeannotation 0xTT <target> [путь]` (в т.ч. в коде: 0x40–0x4B), `.annotation-default`.
- Прочее: `.inner`, `.enclosing`, `.nesthost`, `.nestmember`, `.permits`, `.record-component` (`.record` — record без компонент), `.module` (+ `.version`, `.package`, `.mainclass`), `.sourcedebug`, `.attribute "Имя", "hex"` (нестандартные атрибуты байт-в-байт), `.codeattribute` (внутри Code), псевдофлаги `deprecated` и `synthetic-attribute` (атрибуты Deprecated/Synthetic).
- Имена с пробелами (тесты Kotlin) — в обратных кавычках: ``.method public `my test`()V``.

Фреймы `StackMapTable` не хранятся в тексте: `ca`/`Smali.assemble` считают их заново (для слияния разных классов нужна иерархия: классы JDK из `jrt:/`, собираемые классы и `--cp`).

## class ⇄ dex

**c2d** (стек JVM → регистры DEX): декодер байткода, анализ типов стека/локалов, раскладка регистров с «ленивой» загрузкой локалов (без лишних `move`), подгонка регистров под разрядность форматов DEX (параллельные перестановки `dup*`/`swap` с разрывом циклов), try/catch с заглушками `move-exception`, `synchronized`-методы с явными monitor-enter/exit (как dx/d8), доступ к private-членам соседей по гнезду (Java 11+) — как в d8, `invokespecial` собственного виртуального метода — через private-копию (как d8; `invoke-direct` к виртуальному методу ART отвергает). Метаданные → `dalvik.annotation.*` (Signature, Throws, InnerClass, EnclosingMethod, AnnotationDefault, MethodParameters, а также Record и PermittedSubclasses — их ART читает с Android 14: `isRecord`, `getRecordComponents` с обобщёнными типами и аннотациями, `isSealed`), ConstantValue → `static_values`. `convertMultiDex` сам делит результат по пределу 65 535 ссылок (точно: число новых id класса известно до слияния); try длиннее 65 535 code units делится на несколько `try_item`; подпрограммы `jsr`/`ret` (классы до Java 6: javac до 1.4.2, ecj) встраиваются по месту вызова, как в d8. Классы переводятся параллельно, каждый в собственные пулы DEX, и сливаются в порядке входа (`dasm/util/ClassTransfer`) — результат байт в байт не зависит от числа потоков. Блокировки размечаются так, как их проверяет верификатор ART: monitor-exit получает объект из регистра, который держит блокировку (иначе ART помечает метод «failed lock verification», и он работает медленнее).

`invokedynamic` рассахаривается (`c2d/desugar`, как d8; в ART `LambdaMetafactory` — заглушка, а `StringConcatFactory`, `ObjectMethods`, `SwitchBootstraps` отсутствуют): лямбды и ссылки на методы — синтетические классы `Хозяин$$ExternalSyntheticLambdaN` (преобразования аргументов и результата как у `InnerClassLambdaMetafactory`, мосты и маркеры `altMetafactory`, без захвата — один экземпляр; доступ к private-реализации и к protected-методу суперкласса из другого пакета — через методы доступа), конкатенация строк — `StringBuilder`, `toString`/`equals`/`hashCode` record — по семантике `ObjectMethods` (float/double через `compare`), `typeSwitch`/`enumSwitch` — цепочка проверок в статическом методе хозяина. Место вызова заменяется на месте (`invokestatic` + два `nop`: смещения, таблицы исключений и фреймы не меняются), поэтому результат рассахаривания — корректные классы JVM. Некорректные аргументы bootstrap-метода оставляют `invoke-custom` (в JVM ошибка тоже возникла бы при выполнении). `--no-desugar` / `C2dOptions(desugarIndy = false)` — оставить `invoke-custom` как есть.

**d2c** (регистры → стек): граф потока, вывод типов регистров (φ-узлы, константы подставляются в месте использования в нужном типе), поворот слотов параметров, `checkcast` там, где выведенный тип не доказуемо подходит (верификатор HotSpot не знает всю иерархию), try-диапазоны только вокруг бросающих инструкций, как проверяет ART. Регистры Dalvik не типизированы: если в одной паутине слияний значение используется и как целое, и как вещественное (константы, R8), слот JVM хранит биты (`floatToRawIntBits`/`intBitsToFloat`, то же для long/double) — побитово точно.

Всё, что ART допускает, а JVM — нет, переписывается точным эквивалентом:
- `invoke-super` — реализация из таблицы прямого суперкласса (при любом владельце в ссылке, как в ART); default-метод непрямого суперинтерфейса — статический метод доступа в интерфейсе;
- встроенные R8 конструкторы (`new C` + `A.<init>` суперкласса, вызов `<init>` «через голову», `C.<init>`, объявленный только у предка) — синтетические конструкторы-переходники (`d2c/bridge`);
- protected-доступ к члену другого экземпляра или через «соседний» подкласс — синтетический статический метод доступа; `Object.clone()` массива (d8 выносит его в `I$0.m(Object)`) — `clone()` массива;
- возврат не того вида в недостижимом для ART коде — `throw VerifyError`; обработчик несуществующего типа из JDK пропускается (как в ART, с предупреждением); повторное определение метода — первое (как в ART);
- перезапись регистра со ссылкой «мёртвой» константой очищает слот JVM: объект собирается, как в ART (WeakReference, финализаторы).

Все classes*.dex APK переводятся одним сеансом (`DexToClass.convertAll`), а `convertAllToBytes` не держит модели всех классов (память — по размеру результата). Системные аннотации `dalvik/*` → атрибуты JVM (InnerClasses — по всем DEX сеанса, с локальными и анонимными классами), `static_values` → ConstantValue (нули-заполнители d8 — нет), поля — в порядке `getDeclaredFields` ART (сначала экземпляра). Версия класса (`--target`) повышается только там, где без этого конструкция невыразима или игнорируется HotSpot: MethodHandle/invokedynamic — 51, методы интерфейсов с кодом и их вызов — 52, nest — 55, record — 60, sealed — 61. Метод длиннее 65535 байт кода — ошибка (в `--lenient` — заглушка `throw VerifyError`). Фреймы — через casm с иерархией из самого DEX + `--cp` + JDK.

## Тесты

```
./gradlew :app:test
```

≈680 тестов (JUnit 5). Кроме модульных:

- **csmali** (`CsmaliRoundTripTest`): kotlin-stdlib, классы самого проекта, `java.base` (7 572 класса) и Java 21-программа — текст стабилен, границы и опкоды инструкций, лимиты, таблицы исключений/строк/локалов совпадают с исходными; пересобранные классы проходят верификатор HotSpot (отдельный процесс).
- **casm** (`FrameComputerTest`, `CasmDslTest`): фреймы ставятся там же, где у javac/kotlinc, на всём корпусе; DSL-классы (дальние переходы, wide, switch, try/finally, мёртвый код, uninitialized в локалах, LUB) исполняются в JVM.
- **c2d / d2c**: собственные наборы (`DalvikVerifier` по правилам ART, интерпретатор DEX, baksmali/dexdump/d8; d8 → d2c → HotSpot для библиотек и программ); регрессии из аудитов и тестов ART — `D2cRegressionsTest`, `C2dRegressionsTest`, `DesugarRegressionsTest`.
- **Сквозные** (`com.tim.it.ConvertersRoundTripTest`): javac → c2d → DEX → d2c → HotSpot — вывод программ (в том числе рассахаривание: все виды лямбд, конкатенация, records, pattern switch, отражение Java 21) совпадает с исходным, все классы верифицируются.
- **Фаззинг** (`com.tim.fuzz.ParserFuzzTest`): мутированные DEX, `.class` и тексты smali/csmali проходят весь конвейер (чтение, запись, дизассемблирование, конвертация); любой отказ обязан быть исключением с сообщением — не выходом за массив, NPE, OOM, StackOverflow или зависанием. Прошлые находки (`resources/fuzz`) прогоняются всегда; объём: `-Pfuzz=<итераций на seed> [-Pfuzz.seed=N]`. `PipelineFuzzTest` — случайные программы на smali (генератор дифференциального фаззинга) без устройства проходят d2c → c2d → запись DEX.
- **Утечки** (`com.tim.it.ResourceLeakTest`): повторные преобразования в одной JVM не растят кучу и число потоков.
- **ART** (opt-in, устройство или эмулятор: `-Padb=<serial>`):
  - `com.tim.it.ArtRunTest` — DEX от c2d (и путь d8 → d2c → c2d) выполняется `dalvikvm64` и сравнивается с DEX эталонного d8 в том же ART (или с HotSpot там, где d8 отступает от JDK); каждый DEX, корпуса и регрессии c2d проходят `dex2oat --abort-on-hard-verifier-error` без жёстких ошибок и без «failed lock verification»;
  - `com.tim.fuzz.DiffFuzzTest` (`-Pdifffuzz=<программ>`) — дифференциальный фаззинг: случайные корректные для ART программы на smali (`SmaliProgramGen`: пары long/double внахлёст, константы в слияниях, циклы, switch, try/catch; параметры, которые переписываются значениями других типов, и цикл по параметру-счётчику с первой инструкции — как у R8) исполняются в ART, затем после d2c в HotSpot и после d2c → c2d снова в ART; вывод обязан совпасть.
  Файлы кладутся в `/data/local/tmp/clazzpatcher-test` и удаляются.
- **Корпуса** (opt-in): `-Pcorpus=/каталог/с/dex` (DEX/smali), `-PclassCorpus=a.jar:b.jar` (csmali + верификация).

Фикстуры — `app/src/test/resources`, исходники фикстур и скрипт — `app/src/test/fixtures-src/` (`build-fixtures.sh`). Эталонные smali/baksmali 3.0.9 подключаются только для тестов (первой сборке нужна сеть). `dexdump`/`d8` ищутся в `ANDROID_HOME`, `ANDROID_SDK_ROOT` или `~/Android/Sdk/build-tools/*`; без них зависящие проверки пропускаются.

Правило проекта: никаких молчаливых «разобрать или 0» и пропусков. Некорректный вход — исключение с указанием места (класс, метод, строка, смещение).

## Копия для WEX

Этот каталог подключается к WEX Desktop как `includeBuild` (модуль `com.tim:clazzpatcher`) и собирается тем же
Kotlin, что и WEX (2.1.21 — метаданные читаются его компилятором; своей stdlib в сборке нет). Доработки под редактор:

- `com.tim.dsmali.DexClassEditor` — правка классов по одному: smali класса по запросу (остальные не разбираются),
  `compile` — разбор и пробная сборка текста одного класса, `rebuild` — DEX, где заменены только правленые классы
  (class_def на месте, `DexDslContext.replaceClass`). Call site с прежним номером `call_site_N` переиспользуются,
  флаги hidden API у замены — по числу её членов. Сохранение после правки класса в DEX на 7,6 МБ — 0,2 с и 262 МБ
  кучи против 2,0 с и 744 МБ у dexlib2 `DexPool` (15 МБ: 0,3 с против 3,7 с); размер файла не меняется.
- Ошибки smali с номером строки: `SmaliParseException` / `SmaliBuildException`, `lineOf`, `reasonOf`; регистр вне
  кадра — на строке инструкции.
- `DexWriter(dex, compact = true)` — в пулы id только используемое (`LiveIds`): строки, поля и методы прежних тел
  заменённых классов в файл не попадают. Разметка неполна — писатель падает, а не пишет висячий индекс.
- Подсказки baksmali у литералов (`const v0, 0x3f800000    # 1.0f`, `.array-data`) — `LiteralHints`, только
  в тексте; на orion.dex совпадают с baksmali 2.5.2 все 3 793.
- d2c в нестрогом режиме: `D2cOptions.degraded` (класс переведён не полностью — его можно взять у dex2jar), класс,
  который не переводится, пропускается, а не роняет DEX; потоки перевода со стеком 16 МБ.

Корпус (436 DEX из реальных APK, включая защищённые и модифицированные): чтение, smali всех классов, запись, замена
класса и сжатая запись (smali каждого класса после неё тот же) — без единой ошибки; один DEX обрезан в самом архиве.

## Что изменилось в этой итерации

Проверка шла против настоящего ART: эмулятор Android 15 (`dalvikvm64`, `dex2oat`), 1 076 тестов из AOSP `art/test` (через d8 → d2c → HotSpot), дифференциальный фаззинг, фаззинг разборщиков и аудиты по `dex_file_verifier.cc`, JDK 21 (`InnerClassLambdaMetafactory`, `ObjectMethods`, `SwitchBootstraps`) и JVMS.

### d2c: семантика ART

- `invoke-super` со ссылкой на свой класс вызывал сам метод (бесконечная рекурсия); на default-метод непрямого суперинтерфейса — VerifyError. Тесты ART 425, 551–556, 594, 736, 810.
- `invoke-direct C.<init>`, объявленный только у предка (808–810), и `<init>` «через голову» — переходники с предком, где конструктор объявлен.
- φ из констант, используемый и как int, и как float (510, 535, 552), и константа в слияниях разных категорий — хранение битов вместо отказа.
- `Object.clone()` массива после d8 (856), `o = null` не отпускал объект (036), повтор метода в `class_data` (097), возврат не того вида в недостижимом коде (819), несуществующий тип в catch (510), порядок полей (005).
- invoke-super на default-метод интерфейса вне переводимых DEX (JDK, android.jar), который класс реализует через предков (Compose: `Map.getOrDefault` в SystemUI и Settings; `OnBackAnimationCallback` в Launcher), в строгом режиме был ошибкой: теперь интерфейс добавляется в прямые суперинтерфейсы класса (иерархия и выбор default-метода не меняются) — `invokespecial` к нему допустим, вызывается default-метод интерфейса, как в ART.
- Конструктор, который переиспользует регистр `this` до вызова `super()` (R8: Messages, Dialer), давал VerifyError в HotSpot: у слияния путей, где this уже нигде не лежит, фрейм теряет flagThisUninit. Теперь слот 0 держит `this` нетронутым (схема с копированием регистров).
- Большие таблицы `fill-array-data` (сотни таблиц в `<clinit>`: DevicePersonalization, Velvet) не помещались в 64 КиБ кода JVM — в JVM нет такой инструкции, и таблица писалась по элементу. Если метод не помещается, данные таблиц переносятся строкой в пул констант, а раскладывает их синтетический `d2c$fill<тип>` класса; проверки ART (null, длина массива) — до записи, как раньше.
- Класс, определённый в нескольких DEX одного APK (GmsCore: 183 класса `j$…` и другие), в строгом режиме был ошибкой: как загрузчик ART, берётся определение из первого по порядку DEX (с предупреждением). Повтор в одном DEX — по-прежнему ошибка.
- Фреймы при неполной иерархии (d2c без `--cp`): общий предок классов приложения, чей суперкласс — класс android.jar, считался `Object`, и вызов метода этого суперкласса не проходил HotSpot (3 метода Velvet). Суперкласс всегда класс (JVMS §5.3.5), поэтому неизвестный класс, до которого доходят цепочки суперклассов, — верный общий тип (`casm/hierarchy`).
- `add-int/lit8|lit16 vA, vA, n` переводится в `iinc` (раньше — `iload; bipush; iadd; istore`): байткод kotlin-stdlib после d8 → d2c на 1 % короче, от исходного kotlinc отличается на +9,8 %.
- Первый раунд: обработчик на первой инструкции метода, `aget` на null, `if-*` над слиянием констант float, `fill-array-data` (null, короткий массив, пустой payload), методы длиннее 64 КиБ, отладочные локалы вне кадра, InnerClasses через границу DEX и для анонимных классов, нули-заполнители `static_values`, record/sealed/nest в версии 52, `--target 50/51`, protected через «соседний» класс, совпадающие дескрипторы переходников и имена методов доступа, выбор classes*.dex как в ART, запись классов за пределы каталога.

### c2d и рассахаривание

- `invokedynamic` не работал в ART вовсе (лямбды, конкатенация, records, pattern switch) — рассахаривание (`c2d/desugar`), проверено на `jdk.compiler` (1 583 класса: 0 VerifyError после рассахаривания, рассахаренный javac собирает те же байты) и Kotlin 2.3 (`-Xlambdas=indy`).
- Record и PermittedSubclasses → системные аннотации ART; record без компонент терялся и в casm/csmali/d2c (директива `.record`).
- `dup2_x2` «два cat1 поверх long» исчерпывал временные регистры; `invokespecial` собственного виртуального метода давал ICCE в ART; boolean ConstantValue 2 давал true; try длиннее 65 535 code units не записывался.
- `jsr`/`ret` не поддерживались, и один такой класс останавливал перевод всего jar: встраивание подпрограмм (как JSRInlinerAdapter в ASM и d8) — groovy 3.0.22 (5 436 классов в DEX) и antlr 2.7.7 переводятся, ART dex2oat без замечаний.
- Проверка блокировок ART: monitor-exit через копию объекта, снятую до захвата (R8: `dup2; astore` задолго до `monitorenter`), synchronized на объекте в регистре выше v255 (большие методы javac) и вложенный monitor-enter на одном регистре (R8 → d2c) давали «failed lock verification» — метод работает, но медленнее. Теперь анализ копий (`c2d/analysis/SameValues`) находит все регистры с тем же объектом и того же типа, и после monitor-enter они получают блокировку копией; устаревшие копии во временных регистрах затираются. r8 (15 424 класса): 0 таких методов вместо 72; Phonesky и GmsCore после d2c → c2d — 0 вместо 1 и 2; тестовые программы c2d — 0 вместо 2.
- Рассахаривание: ссылка на открытый метод `Object` у захваченного объекта другого класса (`observer::equals` в commons-io — в intellij-core, Kotlin Gradle Plugin, KSP) шла через метод доступа в хозяине, рассчитанный на protected-член, и ломала тип получателя — ART отвергал класс лямбды. Метод доступа теперь только для защищённых методов и получателя типа хозяина.
- Вызовы `kotlin/jvm/internal/InlineMarker` (пустые маркеры в телах inline-функций Kotlin) не переводятся, как в d8: маркер между концом try synchronized-блока и monitorexit давал «failed lock verification» (KSP: 20 методов, kotlin-compiler: 19).
- Перевод параллельный и детерминированный: классы собираются каждый в своих пулах и сливаются в порядке входа; multidex делится по точному числу новых id. Phonesky (31 290 классов): 3,1 с вместо 8,9 с.

### c2d: размер кода

Сравнение с d8 (`--release --min-api 26`) по гистограммам опкодов показало, откуда лишний код, и c2d стал короче:
- инициализаторы массивов примитивов из констант (`dup; индекс; значение; xastore` на элемент) — одна `fill-array-data`, как у d8 (guava: −2,6 % кода);
- целочисленные операции с константой — формы `/lit8` и `/lit16` (вычитание — сложением), приёмник на месте первого операнда — `/2addr`;
- раскладка регистров по частоте обращений: в большом кадре самые частые локалы (от двух обращений на регистр) встают ниже стека, пока первые четыре слова стека остаются в 4-битных полях (байткод R8 держит значения глубоко в стеке, а частые локалы читаются чаще глубоких слов: d8.jar −5,5 %); частые параметры (`this`) копируются в младшие регистры (иначе каждое обращение из 4-битного поля шло через копию во временный); временных регистров 4 вместо 6 при кадре до 256;
- без отладочных имён: записи в слоты, которые никто не читает (маркеры inline-функций Kotlin `$i$f$…`), отбрасываются, а слот-копия неизменного параметра (`$this$…`) живёт в его регистре (kotlin-stdlib: −1,9 %);
- вызов с аргументом в старшем регистре — одна копия этого аргумента (в регистр его слова стека или во временную пару) и форма 35c вместо копий всех аргументов в диапазон `/range`, если так копий не больше; `checkcast` перед записью в локал приводит сразу в его регистре, а если локал дальше не читается (анализ живости `c2d/analysis/LocalLiveness`) — прямо в регистре локала;
- `goto` на блок из одного возврата — сам возврат, без копии значения в регистр стека; блок-возврат, в который попадали только такие `goto`, не пишется;
- фабрика класса лямбды (`-$$create`) встраивается в место вызова (`new-instance` + `invoke-direct <init>` или `sget-object INSTANCE`), как у d8, и не пишется в DEX: в d8.jar на 7 007 методов меньше.

Кодовые единицы (`--no-debug`): guava 292 628 → 274 449 (−6,2 %; у d8 256 170), kotlin-stdlib 318 187 → 301 189 (−5,3 %; у d8 275 712), d8.jar (байткод R8) 2 543 024 → 2 322 609 (−8,7 %; у d8 1 955 668); файл DEX d8.jar — 11,1 → 10,4 МБ; круг Phonesky d2c → c2d (без отладки) — 30,5 → 32,3 МБ, ×1,06 (было ×1,26). Проверено тестами ART, дифференциальным фаззингом, dex2oat крупнейших jar из кэша Gradle и кругом APK эмулятора d2c → HotSpot → c2d → ART (Maps, Gmail, YouTube, Photos, Dialer, Messages, Drive, YouTube Music, GmsCore — 0 VerifyError, 0 жёстких ошибок, 0 «failed lock verification»). По дороге нашлись и исправлены две ошибки новых оптимизаций: пара long/double в нечитаемом слоте у конца кадра (фаззинг, регрессия `wide-dead`) и тип копии параметра, когда код начинается с головы цикла (круг APK: Gmail, Maps; регрессия `loop-entry`) — после этого генератор фаззинга научился параметрам. Независимый аудит кода нашёл ещё две: приведение на месте, когда приведённое значение лежит на стеке через `monitorenter` по копии того же объекта (передача блокировки копией затирала регистр — ART отвергал класс; регрессия `cast-lock`), и регистр блокировки synchronized-метода в кадре больше 256 регистров (не помещался в 8-битное поле `monitor-enter`) — исправлены. Имя скопированного параметра в отладочной информации переходит на копию.

### DEX и smali

- DexReader: неизвестные типы и ложные размеры в `map_list`, счётчики `class_data`/обработчиков, выход за файл внутри структур (теперь — сообщение со структурой и смещением), вложенность `encoded_value` (StackOverflow), `utf16_size` больше файла (OOM).
- DexWriter: не больше 65 535 `type_ids`/`proto_ids`; длинные try делятся; имена с пробелами поднимают версию до 040.
- DexWriter: `const-string`, чей индекс после сортировки строк не помещается в 16 бит (строк больше 65 536 — например, после правки smali большого DEX), становится `const-string/jumbo`; код метода перекладывается — ветвления (goto расширяется до goto/16 и goto/32, if — обратным условием в обход goto/32), switch, fill-array-data с выравниванием payload, try/catch и отладочная информация. Раньше запись останавливалась с ошибкой. Выросший сдвиг адреса в отладочной информации кодируется одним специальным опкодом (или `DBG_ADVANCE_PC` сразу к началу инструкции), как у d8: прежняя схема — `DBG_ADVANCE_PC` на прирост перед старым опкодом — давала промежуточный адрес внутри инструкции, и d8 падал, читая такой DEX; прежние элементы отладки расширенных методов больше не остаются в файле.
- `c2d … -o x.dex`: строк больше 65 535 — один DEX с `const-string/jumbo`, как у d8 (раньше — отказ «не помещаются в один DEX»: деление по строкам нужно только при записи в каталог); отказ остаётся лишь при пределе методов, полей, типов или прототипов.
- Hidden API: раскладка Android 12+ (`sdk` … `max-target-s`, домены 0x10/0x20) рядом со старой (Android 10/11, имена dexlib2), выбор по флагам файла, невыразимое — `hiddenapi-0x…`; по умолчанию 0. `d core-oj.jar` и `framework.jar` Android 15 разбираются и собираются обратно без изменений.
- Имена smali-файлов у классов, различающихся только регистром (`j$…/A` и `a` после R8), зависели от порядка классов в DEX: после сборки порядок другой — и повторная разборка давала другие имена файлов. Суффиксы теперь по порядку дескрипторов.
- smali → DEX: классы собираются параллельно, каждый в своих пулах, и переносятся по порядку (как в c2d): Phonesky `classes.dex` (8 167 классов) — 0,5 с вместо 1,6 с; результат байт в байт не зависит от числа потоков. Классы с `invoke-custom` собираются в общих пулах (номера call site из текста).
- dsmali принимает варианты эталонного smali: `: метка`, `goto: метка`, `.source` до `.class`, повтор метода; имена с пробелами (DEX 040+) в обратных кавычках теперь и собираются.
- Проверки дескрипторов и кадра (регистры, `ins_size`, типы у `new-instance`/`new-array`/`check-cast`) до перевода; tableswitch/lookupswitch сверяются с длиной кода.

### casm и csmali

Прогон csmali по всем 1 575 jar из кэша Gradle (`.class` → текст → `.class` → текст) нашёл классы, чей текст после сборки менялся или не строился вовсе:
- аннотации типов у компонентов record (`@Nullable` над компонентом: adventure, fabric, intellij-core) — класс не разбирался; теперь `.typeannotation` внутри `.record-component`;
- переменные, открытые или закрытые на одном pc (inline-функции Kotlin): порядок `.local`/`.end local` зависел от порядка записей LocalVariableTable, а сборка писала их в порядке закрытия — теперь на одном pc переменные идут по слоту, записи таблицы — в порядке `.local`;
- больше ~127 строк под `ldc` (не `ldc_w`): каждая строка занимала в пуле два слота подряд (Utf8 и String), и часть `ldc` после сборки становилась `ldc_w`; записи констант `ldc` теперь занимают индексы раньше своих Utf8, как в пулах javac;
- порядок аннотаций: атрибут `RuntimeInvisibleAnnotations` перед `RuntimeVisibleAnnotations` (kotlin-reflect, protobuf-gradle-plugin) после сборки менялся местами; теперь первым пишется атрибут той видимости, с которой начинается список (так же для аннотаций параметров и типов);
- недостижимый код: участки делятся на инструкциях без проваливания (groovy — замена `nop…athrow` иначе зависела от соседнего мёртвого кода); одиночный `goto` к достижимому коду (ECJ ставит его после `return`) и обработчик пустого `finally` `astore; aload; athrow` (javac, лексеры ANTLR 3) проверяемы без анализа — они остаются как есть, с фреймом цели или `[] [Throwable]`.

Итог: 1 574 jar из 1 575 (1 019 741 класс) — текст после `.class` → текст → `.class` → текст совпадает; единственное отличие — мёртвый обработчик `finally` с кодом у ECJ (mercury), который, как в ASM, становится `nop…athrow`.

### Память, скорость, утечки

- d2c пишет классы в байты сразу после перевода: пик памяти на Phonesky (5 DEX, 31 290 классов) — с ≈1 ГБ до ≈384 МБ, GmsCore (8 DEX, 86 000 классов) укладывается в 768 МБ.
- Параллельная запись jar с концевыми записями ZIP64 (больше 65 535 классов — без однопоточного `ZipOutputStream`).
- Узлы SSA — открытая адресация без упаковки, хеш CONSTANT_Utf8 кэшируется.
- `--cp` закрывается после работы; `ResourceLeakTest` следит за кучей и потоками.
- c2d: StackMapTable при чтении не разбирается (c2d выводит типы сам; байты сохраняются), исходные байты классов после разбора не держатся, готовый DEX пишется в фоне, пока переводятся следующие классы. Phonesky укладывается в `-Xmx768m` без потери скорости (раньше при 768 МБ сборщик мусора растягивал перевод до 7 с).
- Запускающий скрипт: кэш кода JVM 256 МБ — при 48 МБ (по умолчанию для C1) его заполнение вызывало полные сборки мусора.

### Проверено

Все проверки — настоящими верификаторами: HotSpot (`-Xverify:all`, отдельный процесс) и ART (`dex2oat --compiler-filter=verify --abort-on-hard-verifier-error` и `dalvikvm64` на эмуляторе Android 15).

| что | объём | результат |
|---|---|---|
| тесты AOSP `art/test`: javac → d8 → d2c → HotSpot | 1 076 тестов (621 применимы: без JNI/jvmti/особой сборки) | 573 совпадают с ART в строгом режиме до исправлений; все найденные расхождения d2c исправлены (см. выше), остальные — различия платформ (потоки, GC, отражение HotSpot) |
| дифференциальный фаззинг (`DiffFuzzTest`) | ≈23 000 случайных программ smali (из них ≈19 800 — после оптимизаций размера c2d, 18 000 — генератором с параметрами) | вывод ART = HotSpot после d2c = ART после d2c → c2d |
| фаззинг разборщиков (`ParserFuzzTest`) | DEX, `.class`, smali, csmali; 1 500 мутаций на seed | 0 внутренних ошибок, зависаний, OOM |
| APK и JAR образа эмулятора Android 15 (Google APIs): d2c строго (`--cp android.jar`) → HotSpot; → c2d → ART | ≈150 штук: Maps, Gmail, YouTube, YouTube Music, Photos, Drive, Dialer, Messages (100 729 кл.), SystemUI, Launcher, Settings, TalkBack, Calendar, GmsCore (144 165 кл.), Velvet (171 348 кл.), `framework.jar`, `services.jar` и системные приложения | d2c: все, кроме одного метода Velvet (94 КБ кода JVM, см. ограничения; в `--lenient` — заглушка); HotSpot: 0 VerifyError; ART dex2oat после c2d: 0 жёстких ошибок, 0 «failed lock verification». Найдено и исправлено: invoke-super к интерфейсу JDK/android.jar, `this` до `super()`, большие таблицы, повтор класса в разных DEX, общий предок вне иерархии |
| d2c: DEX → `.class` | Velvet (12 DEX, 149 394 кл.), GmsCore (8 DEX, 86 000 кл.), Phonesky (5 DEX, 31 290 кл.), SetupWizard, kotlin-compiler (d8, 5 DEX, 32 808 кл.), 6 системных APK после R8 | HotSpot: 0 VerifyError (Velvet — и без `--cp`), строгий режим без предупреждений; не линкуются только классы без зависимостей в classpath (скрытые API платформы, `org.apache.http`) |
| APK → d2c → c2d → ART | Velvet (149 394 кл.), GmsCore (86 000 кл.), Phonesky; Camera2, PrintSpooler, Traceur, DeskClock, LatinIME, GoogleContacts | ART dex2oat: 0 жёстких ошибок; у Velvet, GmsCore и Phonesky — 0 «failed lock verification» |
| c2d: `.class` → DEX | kotlin-stdlib, guava, r8, `jdk.compiler`, groovy 3.0.22 и antlr 2.7.7 (есть `jsr`/`ret`), классы проекта | ART dex2oat: 0 жёстких ошибок и ни одного «failed lock verification» |
| c2d: jar из кэша Gradle | 1 575 jar (Kotlin 1.9–2.4, AGP, IntelliJ, KSP, Compose, Guava, fastutil и т. д.) | перевод без единой ошибки; все 1 575 — ART dex2oat: 0 жёстких ошибок, 0 «failed lock verification» — и до, и после оптимизаций размера кода; итоговая сборка — 29 крупнейших (kotlin-compiler, KSP, intellij-core, AGP builder, Kotlin Gradle Plugin, fastutil, material-icons…), тоже 0 |
| c2d: детерминированность | kotlin-stdlib в 1, 3 и 8 потоков (multidex), 1 500 классов в один DEX | DEX совпадают байт в байт |
| программы в ART: javac → c2d и javac → d8 → d2c → c2d | 13 программ + рассахаривание (все виды лямбд, конкатенация, records, pattern switch, отражение Java 21) | вывод `dalvikvm64` совпадает с d8 в ART (или с HotSpot, где d8 отступает от JDK) |
| программы: javac → c2d → d2c → HotSpot | 16 программ (в т. ч. Java 21) | вывод идентичен, все классы верифицируются |
| csmali: `.class` → текст → `.class` | java.base 7 572 кл. (62 772 метода), kotlin-stdlib 987, классы проекта ~700, kotlin-compiler 26 016, guava 2 017, r8 5 670; все 1 575 jar из кэша Gradle (1 019 741 класс) | точный round-trip; множество классов, проходящих HotSpot, совпадает с исходным (0 регрессий); в кэше Gradle текст совпадает у 1 574 jar (исключение — мёртвый код ECJ, см. ограничения) |
| dsmali: DEX → smali → DEX | `core-oj.jar`, `framework.jar` (Android 15, hidden API), Phonesky, GmsCore; с эмулятора — Maps, Gmail, YouTube, YouTube Music, Photos, Drive, Dialer, Messages, SystemUI, Launcher, Settings, GmsCore (14 DEX), Velvet (14 DEX), `framework.jar`, `services.jar` | текст повторной разборки совпадает, собранные DEX проходят `dex2oat` (верификатор ART) |
| casm: расстановка фреймов | java.base, kotlin-stdlib, проект | фреймы — подмножество фреймов javac/kotlinc (все обязательные на месте) |

### Скорость

Командная строка (`./clazzpatcher`, JVM запускается заново, 16 потоков):

| операция | вход | время | память |
|---|---|---|---|
| d2c | Phonesky: 5 DEX (30 МБ), 31 290 классов | 2,9 с (перевод 2,3 с) | `-Xmx384m` достаточно |
| d2c | GmsCore: 8 DEX (≈100 МБ), 86 000 классов | 5,5 с | `-Xmx768m` достаточно |
| d2c | Velvet: 12 DEX (≈250 МБ APK), 149 394 класса | 7,9 с | — |
| c2d | 31 290 классов (выход d2c Phonesky) → 4 DEX | 3,2 с (перевод 2,5 с; было 8,9 с) | `-Xmx768m` достаточно |
| c2d | 86 000 классов (выход d2c GmsCore) → 8 DEX | 6,1 с | — |
| c2d | 149 394 класса (выход d2c Velvet) → 11 DEX | 9,1 с | — |
| smali → DEX | Phonesky `classes.dex`: 8 167 классов | 0,5 с сборка (было 1,6 с) | — |

В одной прогретой JVM полный круг Phonesky (d2c всех 5 DEX и DEX → smali → DEX первого) — ≈1,6 с.

## Предыдущие итерации

### casm, csmali, конвертеры

#### casm — исправленные ошибки

- **Фреймы StackMapTable считались неверно** и классы не проходили верификатор:
  - локалы не учитывали, что long/double занимают два слота;
  - `max_stack` считался в элементах, а не в словах;
  - `ldc_w` читался как `ldc`, неверно работали `dup_x2`/`dup2*`, `newarray` всегда давал `[I`, `aaload` — Object;
  - слияние ссылок всегда давало Object, без учёта иерархии;
  - обработчики исключений брали состояние только в начале диапазона и не получали фреймов;
  - не было фреймов после безусловных переходов, мёртвый код не обрабатывался;
  - ошибки анализа молча копились в списке.
  Теперь `casm/frames`: поток данных по JVMS §4.10 с обработчиками по правилам HotSpot (до записи в локал, после остальных инструкций, у `<init>` — до и после), LUB по иерархии, сжатые фреймы (same/append/chop/full), мёртвый код → `nop…athrow` с вырезанием из диапазонов try (как ASM), jsr/ret для версий < 51.
- `LabelResolver` молча обрезал дальний условный переход (s2) — теперь инвертированное условие + `goto_w`.
- `CodeDsl`: `lconst(5)` молча давал `lconst_1` (так же `dconst`/`fconst`), не было wide-форм локалов > 255, `wide iinc`, проверок `bipush`/`sipush`.
- `ClassDsl.method` строил дескриптор класса из имени метода; `private()` у поля/метода складывался с `public` (0x3 → ClassFormatError).
- `ClassFileBuilder`: терялись invisible-аннотации, аннотации параметров, AnnotationDefault, type-аннотации, сигнатура класса; EnclosingMethod собирался с именем «имя+дескриптор» и пустым дескриптором; ModulePackages писал Utf8 вместо CONSTANT_Package.
- `MethodPatchDsl` оставлял устаревшие LocalVariableTypeTable/type-аннотации кода; на каждый метод копировался весь пул (O(n²)).
- Взаимная блокировка инициализации `VerType`/`Ref` при параллельной работе.
- Отладочный `CAsmTest` с жёсткими путями Android убран из main (сценарий — в `CasmDslTest`).

#### csmali — исправленные ошибки

- Дизассемблер печатал первые 30 инструкций каждого метода в stdout.
- Регистры именовались по-далвиковски (от `max_locals`) — для JVM неверно.
- Любая аннотация роняла дизассемблер (Utf8-индекс читался как CONSTANT_Class); invisible-аннотации становились visible; `.enum` печатался в формате, который не разбирал ассемблер; классы примитивов (`int.class`) не разбирались.
- `invokedynamic` терял аргументы bootstrap, а ассемблер ставил в CONSTANT_InvokeDynamic индекс method handle вместо индекса BootstrapMethods (атрибута не было вовсе); `ldc` MethodType/MethodHandle/Dynamic не разбирались.
- Ссылки на методы интерфейсов всегда собирались как Methodref (`invokeinterface`, static/default-методы интерфейсов → ICCE/VerifyError).
- Типы-массивы в `checkcast`/`anewarray`/`instanceof`/`ldc` и владелец `[I.clone()` роняли сборку; имя класса, начинающееся на `L`, путалось с дескриптором.
- `tableswitch` терял `low`, а многострочный switch при разборе превращался в объявления меток.
- Порядок `.catch` менялся (группировка по метке конца) — менялся приоритет обработчиков.
- Отладка: переиспользованный слот терял переменную; пустые и пересекающиеся диапазоны; LocalVariableTypeTable терялась; `.restart local`/`.prologue` молча игнорировались.
- Версия класса всегда 52 (records/nest/sealed откатывались → IllegalAccessError), терялись ACC_SUPER, сигнатура класса, SourceDebugExtension, Deprecated/Synthetic (Synthetic превращался во флаг), нестандартные атрибуты, флаги и число записей MethodParameters, версия модуля, ModulePackages/MainClass, type-аннотации (в том числе в коде).
- `.param` перед аннотацией метода, `.throws` с несколькими типами, `.typeannotation` (несогласованные писатель/парсер, терялся последний элемент type_path) разбирались неверно.
- `ldc` всегда собирался как `ldc_w`, `jsr_w` — как `jsr`; NaN с полезной нагрузкой терялся; имена с пробелами не разбирались; `EnclosingMethod` ронял дизассемблер.
- Непарные суррогаты в строках терялись при записи UTF-8 (и в dsmali).

#### Новое

- Конвертеры `c2d` и `d2c`, команды `cd`/`ca`/`c2d`/`d2c`, параллельные пакетные операции csmali, запись jar с параллельным сжатием.
- Основной код не использует рефлексию вовсе (включая `javaClass`/`::class.java` в сообщениях).
- Устаревшие тесты (старые имена пакетов) приведены к текущему коду.

#### Проверено тогда

Все проверки — настоящими верификаторами: HotSpot (`-Xverify:all`, отдельный процесс) и ART (`dex2oat --compiler-filter=verify --abort-on-hard-verifier-error` и `dalvikvm64` на эмуляторе Android 15).

| что | объём | результат |
|---|---|---|
| csmali: `.class` → текст → `.class` | java.base 7 572 кл. (62 772 метода), kotlin-stdlib 987, классы проекта ~700, kotlin-compiler 26 016, guava 2 017, r8 5 670 | точный round-trip (границы и опкоды инструкций, лимиты, таблицы); множество классов, проходящих HotSpot, совпадает с исходным (0 регрессий) |
| csmali на выходе d2c | 32 808 кл. kotlin-compiler после d8 → d2c | 0 регрессий |
| casm: расстановка фреймов | java.base, kotlin-stdlib, проект | фреймы — подмножество фреймов javac/kotlinc (все обязательные на месте) |
| d2c: DEX → `.class` | kotlin-compiler (d8, 5 DEX, 32 808 кл.), 6 системных APK (≈44,9 тыс. кл., код после R8: Camera2, PrintSpooler, Traceur, DeskClock, LatinIME, GoogleContacts) | HotSpot: 0 VerifyError; не линкуются только классы без зависимостей в classpath (как и до перевода) |
| c2d: `.class` → DEX | kotlin-stdlib, guava, r8, классы проекта | ART dex2oat: 0 жёстких ошибок |
| APK → d2c → c2d → ART | Camera2, PrintSpooler, Traceur, DeskClock, LatinIME, GoogleContacts | ART dex2oat: 0 жёстких ошибок |
| программы: javac → c2d → d2c → HotSpot | 13 программ (в т. ч. Java 21: records, sealed, pattern switch, лямбды) | вывод идентичен, все классы верифицируются |
| программы в ART: javac → c2d и javac → d8 → d2c → c2d | 11 программ без invokedynamic | вывод `dalvikvm64` совпадает с DEX эталонного d8 |


### DEX и smali

Исходник — ветка `master` (коммит «Исходное состояние проекта»), новая версия — `overhaul`. Все отличия: `git diff master overhaul`, подробности по слоям — в сообщениях коммитов (`git log master..overhaul`).

Корректность:

- **DexWriter.**
  - ART отклонял каждый записанный файл: размер `map_list` и ненулевые смещения пустых секций.
  - `invoke-custom` уходил на чужой call site.
  - Размер sparse-switch payload считался неверно.
  - Индекс `const-string` больше 0xFFFF молча обрезался.
  - Не пересортировывались аннотации.
  - `static_values` не переставлялись вместе с полями.
  - `class_defs` не упорядочивались так, чтобы суперкласс шёл раньше.
  - Секция hiddenapi была реализована не по спецификации.
- **DexReader.** 5-байтовый SLEB128. Повторный `read()` затирал прошлый результат. Молча принимал неизвестные типы значений.
- **DEX → smali.**
  - Цели switch считались от последнего switch, а не от своего.
  - `const-wide/high16` печатался со сдвигом (`10.0` превращался в `0.0`).
  - `Int.MIN_VALUE` печатался неверно.
  - Терялись типы `byte`/`short` у значений.
  - Не экранировались символы.
  - `.param` шёл с индексом параметра вместо регистра.
  - Call sites и method handles печатались не синтаксисом smali.
  - На регистронезависимой ФС (Android) `a.smali` и `A.smali` затирали друг друга.
- **smali → DEX.**
  - Все payload'ы привязывались к последнему switch.
  - `long` разбирался как `int` (gson падал).
  - Hex, оканчивающийся на `f`, читался как float.
  - Ломалось экранирование `\\n`.
  - Терялись безымянные параметры в debug info и аннотации параметров.
  - `static_values` сдвигались.
  - Call sites, method handles и hidden API не поддерживались.
  - `SmaliPatch.patchClass` удалял все перегрузки метода.
- **.class.**
  - Type annotations 0x42–0x4B читались с неверной раскладкой.
  - Не поддерживался `CONSTANT_Dynamic`.
  - Строки пула декодировались как UTF-8 вместо Modified UTF-8.
  - NaN и `-0.0` в константах искажались.
  - Длины атрибутов не проверялись.

Скорость (прогретая JVM):

| | было | стало |
|---|---|---|
| DEX → smali → DEX, kotlin-stdlib (2 МБ) | не закончилось за 10 мин | ~0,3–0,6 с |
| то же, kotlin-compiler `classes3.dex` (13,5 МБ) | падало | ~2 с |
| чтение / запись DEX 13,5 МБ | 96 / 251 мс | ~35–60 / ~80–100 мс |
| `.class`: 26 970 классов kotlin-compiler | 418 мс | 328 мс |

Главная причина тормозов была в линейном поиске во всех пулах ассемблера (сборка O(n²)); теперь там хэш-индексы. Дизассемблирование, запись и разбор smali идут параллельно.

## Известные ограничения

**d2c**
- Константа, которая в разных слияниях одного регистра становится и ссылкой (`null`), и числом, требует разных записей в слот на разных рёбрах — такой метод не переводится (в `--lenient` — заглушка). Слияния целых и вещественных значений (в том числе констант) поддержаны.
- Код после перевода длиннее DEX-кода (стековые загрузки и записи вместо регистровых операндов): метод, близкий к 64 КиБ, может не поместиться — ошибка или заглушка в `--lenient` (тест ART 003; Velvet с образа эмулятора: `<clinit>` на 23 168 инструкций и 3 383 регистра — 94 КБ в JVM). Таблицы `fill-array-data` в такие методы уже переносятся строками; деления метода на части нет.
- `--lenient`: невыразимый метод бросает `VerifyError` при вызове, а ART бросил бы его при загрузке класса.
- `Object.clone()` на значении типа Object, которое во время работы — не массив (ART вызвал бы `Object.clone` из чужого класса): `CloneNotSupportedException`. protected-члены библиотечных классов (флаги неизвестны) через чужой экземпляр не переписываются.
- `invoke-virtual` (не `invoke-polymorphic`) на `MethodHandle.invokeExact`: ART бросает исключение, а после перевода handle вызывается.

**c2d**
- Методы с `jsr`/`ret` теряют таблицу локальных переменных (номера строк сохраняются).
- Код методов больше, чем у d8 (`--release --min-api 26`): для javac/kotlinc — на 7–9 % кодовых единиц (guava, kotlin-stdlib), для байткода после R8 — на ≈19 % (d8.jar); файлы DEX — на 0–2 % и ≈15 %. Регистров в кадре вдвое больше: полноценного распределения регистров (SSA, линейное сканирование) нет — слова стека JVM живут в регистрах по глубине, и остаются копии на границах блоков, для `/range` и `check-cast`.
- Рассахаривание: у сериализуемых лямбд нет `writeReplace`/`SerializedLambda` (как у d8); помощники используют API Java 8 (`Objects.equals/hashCode`, `Integer.hashCode(int)` — Android 7+); records и sealed-классы остаются наследниками `java.lang.Record` и требуют Android 14+ (d8 для старых API переписывает иерархию).
- NestHost/NestMembers не переносятся (как у d8: d8/R8 требуют хозяина гнезда в том же наборе входных DEX).

**casm и csmali**

- Недостижимый код, кроме одиночного `goto` к достижимому коду и обработчика `astore; aload; athrow`, заменяется на `nop…athrow` и вырезается из диапазонов try, как в ASM: верификатор проверяет и мёртвый код, а типов для него нет. У таких классов текст после `cd` → `ca` → `cd` в этом месте отличается (в кэше Gradle — 1 класс ECJ на миллион).

**DEX и smali**

- Hidden API: раскладка флагов в DEX не записана, она определяется по флагам файла; у неоднозначных файлов (только значения 0–6 и домен 0x10) выбирается раскладка Android 12+. Повторная сборка точна при любом выборе, различаются только имена в тексте.
- Небольшие отличия от эталонного smali, сделанные намеренно: явные «лишние» значения по умолчанию в конце `static_values` сохраняются, а нумерация call site берётся из `call_site_N` в тексте.
