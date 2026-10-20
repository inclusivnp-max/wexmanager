package org.wexclient.app.editor.lang

/** Сниппет: `$1`, `${1:текст}` — поля по Tab, `$0` — где окажется курсор в конце. */
class Snippet(val trigger: String, val description: String, val body: String)

/** Вид элемента автодополнения (буква/значок в списке и порядок при равном счёте). */
enum class ItemKind(val badge: String, val title: String) {
    KEYWORD("k", "ключевое слово"),
    TYPE("T", "тип"),
    FUNCTION("ƒ", "функция"),
    PROPERTY("p", "свойство"),
    CONSTANT("c", "константа"),
    SNIPPET("{}", "шаблон"),
    WORD("w", "из файла"),
    TAG("<>", "тег"),
    ATTRIBUTE("@", "атрибут"),
    VALUE("v", "значение"),
    OPCODE("op", "инструкция"),
    DIRECTIVE(".", "директива"),
    MODULE("m", "модуль"),
    VARIABLE("$", "переменная"),
    FILE("/", "путь"),
    LABEL(":", "метка"),
    COMMAND(">", "команда")
}

/** Словарь автодополнения языка: слова по видам (с подсказкой) и шаблоны. */
class Vocabulary(
    val words: List<VocabWord> = emptyList(),
    val snippets: List<Snippet> = emptyList()
)

class VocabWord(val text: String, val kind: ItemKind, val detail: String = "")

class Language(
    val id: String,
    val name: String,
    val extensions: List<String> = emptyList(),
    val fileNames: List<String> = emptyList(),
    /** Шаблоны имени файла (по нижнему регистру). */
    val fileNamePatterns: List<Regex> = emptyList(),
    val interpreters: List<String> = emptyList(),
    val lineComment: String? = null,
    val blockComment: Pair<String, String>? = null,
    /** Автозакрываемые пары подряд: "()[]{}\"\"". */
    val autoPairs: String = "()[]{}\"\"''",
    /** Строка (до курсора) открывает блок — следующая строка с отступом. */
    val indentAfter: Regex? = null,
    /** Строка закрывает блок (end/fi/else…) — при Enter сдвигается влево. */
    val dedentLine: Regex? = null,
    /** После таких строк (return/pass…) следующая строка — на уровень левее. */
    val dedentAfter: Regex? = null,
    val defaultIndent: String = "    ",
    val wordChars: String = "_",
    val caseInsensitive: Boolean = false,
    /** Язык разметки с тегами (<a></a>): автозакрытие тегов и парные теги. */
    val markup: Boolean = false,
    /** Отступы значимы (Python/YAML): форматирование только чистит пробелы. */
    val significantIndent: Boolean = false,
    val spec: LexSpec? = null,
    private val lexerFactory: () -> LineLexer = { spec?.let { CLikeLexer(it) } ?: PlainLexer },
    private val vocabularyFactory: () -> Vocabulary = { Vocabulary() }
) {
    val lexer: LineLexer by lazy { lexerFactory() }

    /** Слова из описания лексера + словарь языка. */
    val vocabulary: Vocabulary by lazy {
        val own = vocabularyFactory()
        val s = spec ?: return@lazy own
        val seen = HashSet<String>()
        val list = ArrayList<VocabWord>()
        fun addAll(words: Set<String>, kind: ItemKind) {
            for (w in words) if (w.length > 1 && seen.add(w)) list.add(VocabWord(w, kind))
        }
        own.words.forEach { if (seen.add(it.text)) list.add(it) }
        addAll(s.control, ItemKind.KEYWORD)
        addAll(s.keywords, ItemKind.KEYWORD)
        addAll(s.modifiers, ItemKind.KEYWORD)
        addAll(s.types, ItemKind.TYPE)
        addAll(s.builtins, ItemKind.FUNCTION)
        addAll(s.constants, ItemKind.CONSTANT)
        Vocabulary(list, own.snippets)
    }

    fun isWordChar(c: Char) = c.isLetterOrDigit() || c in wordChars

    override fun toString() = name
}

internal fun words(s: String): Set<String> = s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.toSet()

private val BRACE_INDENT = Regex("[{\\[(]\\s*$")
private val CLOSE_DEDENT = Regex("^\\s*[}\\])]")

object Languages {
    // ---------- Спецификации лексеров ----------

    private val kotlinSpec = LexSpec(
        control = words("if else when for while do return break continue throw try catch finally"),
        keywords = words("package import class interface fun val var object typealias constructor init companion this super is as in out by where get set field typeof dynamic"),
        modifiers = words("public private protected internal abstract final open override lateinit data sealed enum annotation inner inline noinline crossinline reified suspend tailrec operator infix external const vararg expect actual value"),
        constants = words("true false null"),
        types = words("Int Long Short Byte Float Double Boolean Char String Unit Any Nothing Array List MutableList Map MutableMap Set MutableSet Sequence Pair Triple IntArray ByteArray LongArray CharArray FloatArray DoubleArray BooleanArray ShortArray UInt ULong UByte UShort Number Comparable Iterable Collection MutableCollection Iterator Result Lazy Regex StringBuilder Throwable Exception"),
        builtins = words("println print listOf mutableListOf arrayListOf mapOf mutableMapOf hashMapOf linkedMapOf setOf mutableSetOf hashSetOf arrayOf intArrayOf byteArrayOf emptyList emptyMap emptySet emptyArray lazy require requireNotNull check checkNotNull error TODO repeat run let apply also with takeIf takeUnless buildList buildMap buildSet buildString sequenceOf generateSequence maxOf minOf readLine readln synchronized runCatching suspendCoroutine coroutineScope launch async withContext delay"),
        blockComments = listOf(BlockComment("/**", "*/", doc = true, nested = true), BlockComment("/*", "*/", nested = true)),
        strings = listOf(
            StringRule("\"\"\"", escape = null, multiline = true, interpolation = listOf("\${"), dollarIdent = true),
            StringRule("\"", interpolation = listOf("\${"), dollarIdent = true),
            StringRule("'")
        ),
        identExtra = "_$",
        annotationPrefix = '@',
        declFunction = words("fun"),
        declType = words("class interface object typealias"),
        numberSuffixes = "lLfFuU"
    )

    private val javaSpec = LexSpec(
        control = words("if else switch case default for while do return break continue throw try catch finally yield assert"),
        keywords = words("package import class interface enum record extends implements new this super instanceof var void throws permits"),
        modifiers = words("public private protected static final abstract native synchronized transient volatile strictfp sealed non-sealed"),
        types = words("boolean byte char short int long float double String Object Integer Long Boolean Character Double Float Byte Short Void List ArrayList Map HashMap Set HashSet"),
        constants = words("true false null"),
        builtins = words("System Math Arrays Collections Objects Optional Thread Runnable"),
        strings = listOf(StringRule("\"\"\"", multiline = true), StringRule("\""), StringRule("'")),
        identExtra = "_$",
        identStartExtra = "_$",
        annotationPrefix = '@',
        declType = words("class interface enum record")
    )

    private val groovySpec = LexSpec(
        control = words("if else switch case default for while do return break continue throw try catch finally assert"),
        keywords = words("def as class interface enum trait extends implements import package new this super in instanceof var void throws"),
        modifiers = words("public private protected static final abstract native synchronized transient volatile"),
        constants = words("true false null"),
        types = words("String Integer Boolean List Map Object int long boolean double float"),
        builtins = words("plugins dependencies repositories android defaultConfig buildTypes release debug signingConfigs compileOptions kotlinOptions buildFeatures sourceSets packagingOptions packaging lint namespace compileSdk minSdk targetSdk versionCode versionName applicationId implementation api compileOnly runtimeOnly testImplementation androidTestImplementation kapt ksp annotationProcessor classpath mavenCentral mavenLocal google gradlePluginPortal maven url id version apply include rootProject project task tasks register named println file files fileTree jvmTarget minifyEnabled shrinkResources proguardFiles getDefaultProguardFile allprojects subprojects buildscript ext"),
        strings = listOf(
            StringRule("'''", multiline = true),
            StringRule("\"\"\"", multiline = true, interpolation = listOf("\${"), dollarIdent = true),
            StringRule("\"", interpolation = listOf("\${"), dollarIdent = true),
            StringRule("'")
        ),
        identExtra = "_$",
        annotationPrefix = '@',
        declFunction = words("def"),
        declType = words("class interface enum trait")
    )

    private val scalaSpec = LexSpec(
        control = words("if else match case for while do return throw try catch finally yield then"),
        keywords = words("abstract class def extends forSome implicit import lazy new object override package sealed super this trait type val var with given using enum export end derives extension"),
        modifiers = words("private protected final inline opaque transparent open infix"),
        constants = words("true false null"),
        types = words("Int Long Double Float Boolean Char String Unit Any AnyRef Nothing Option Some None List Seq Vector Map Set Array Future Either Left Right"),
        builtins = words("println print require assert"),
        strings = listOf(StringRule("\"\"\"", escape = null, multiline = true), StringRule("\"", dollarIdent = true, interpolation = listOf("\${")), StringRule("'")),
        stringPrefixes = words("s f raw"),
        annotationPrefix = '@',
        declFunction = words("def"),
        declType = words("class trait object enum type")
    )

    private val cTypes = "void char short int long float double signed unsigned _Bool bool size_t ssize_t off_t pid_t int8_t int16_t int32_t int64_t uint8_t uint16_t uint32_t uint64_t intptr_t uintptr_t ptrdiff_t wchar_t FILE va_list jint jlong jboolean jbyte jchar jshort jfloat jdouble jobject jclass jstring jarray jobjectArray jbyteArray jintArray jmethodID jfieldID JNIEnv JavaVM"
    private val cBuiltins = "printf fprintf sprintf snprintf vsnprintf scanf sscanf malloc calloc realloc free memcpy memmove memset memcmp strlen strcpy strncpy strcat strncat strcmp strncmp strchr strrchr strstr strdup strtok fopen fclose fread fwrite fgets fputs fputc fgetc fseek ftell fflush puts putchar getchar exit abort atexit atoi atol atof strtol strtoul strtod qsort bsearch abs labs rand srand time clock sleep usleep open close read write ioctl mmap munmap dlopen dlsym dlclose pthread_create pthread_join pthread_mutex_lock pthread_mutex_unlock assert offsetof sizeof __android_log_print"

    private val cSpec = LexSpec(
        control = words("if else switch case default for while do return break continue goto"),
        keywords = words("struct union enum typedef sizeof static_assert _Alignas _Alignof _Atomic _Generic _Noreturn _Static_assert _Thread_local inline restrict"),
        modifiers = words("static extern const volatile register auto"),
        types = words(cTypes),
        constants = words("NULL true false EOF stdin stdout stderr"),
        builtins = words(cBuiltins),
        preprocessor = true,
        capitalizedTypes = false,
        declType = words("struct union enum"),
        stringPrefixes = words("L u U u8")
    )

    private val cppSpec = cSpec.copy(
        keywords = cSpec.keywords + words("class namespace template typename using new delete this nullptr operator friend virtual override final explicit mutable concept requires co_await co_return co_yield static_cast dynamic_cast const_cast reinterpret_cast typeid decltype noexcept alignas alignof thread_local export module import try catch throw"),
        modifiers = cSpec.modifiers + words("public private protected constexpr constinit consteval"),
        types = cSpec.types + words("std string wstring string_view vector array map unordered_map set unordered_set list deque queue stack pair tuple optional variant any unique_ptr shared_ptr weak_ptr function thread mutex atomic size_type iterator auto"),
        builtins = cSpec.builtins + words("cout cin cerr endl make_unique make_shared move forward swap min max sort find begin end to_string stoi stol stod getline"),
        strings = listOf(StringRule("R\"(", ")\"", escape = null, multiline = true), StringRule("\""), StringRule("'")),
        declType = words("struct union enum class namespace"),
        capitalizedTypes = true
    )

    private val objcSpec = cSpec.copy(
        keywords = cSpec.keywords + words("self super nil Nil YES NO id instancetype SEL IMP BOOL"),
        types = cSpec.types + words("NSString NSArray NSDictionary NSNumber NSObject NSInteger NSUInteger CGFloat"),
        annotationPrefix = '@',
        stringPrefixes = words("@"),
        capitalizedTypes = true
    )

    private val csharpSpec = LexSpec(
        control = words("if else switch case default for foreach while do return break continue goto throw try catch finally yield await when"),
        keywords = words("using namespace class struct interface enum record delegate event new this base is as in out ref typeof sizeof nameof operator implicit explicit var dynamic get set init value where select from orderby group into join let"),
        modifiers = words("public private protected internal static readonly const abstract virtual override sealed async unsafe extern volatile partial required file params"),
        types = words("bool byte sbyte char decimal double float int uint long ulong short ushort object string void nint nuint String Object Task List Dictionary IEnumerable Action Func"),
        constants = words("true false null"),
        builtins = words("Console Math Convert Environment"),
        strings = listOf(
            StringRule("\"\"\"", escape = null, multiline = true),
            StringRule("\"", doubledQuoteEscape = true),
            StringRule("'")
        ),
        stringPrefixes = words("@ $ \$@ @$"),
        preprocessor = true,
        declType = words("class struct interface enum record")
    )

    private val goSpec = LexSpec(
        control = words("if else switch case default for range return break continue goto fallthrough select defer go"),
        keywords = words("package import func var const type struct interface map chan"),
        types = words("bool string int int8 int16 int32 int64 uint uint8 uint16 uint32 uint64 uintptr byte rune float32 float64 complex64 complex128 error any comparable"),
        constants = words("true false nil iota"),
        builtins = words("append cap clear close complex copy delete imag len make max min new panic print println real recover"),
        strings = listOf(StringRule("`", escape = null, multiline = true), StringRule("\""), StringRule("'")),
        capitalizedTypes = false,
        allCapsConstants = false,
        declFunction = words("func"),
        declType = words("type"),
        numberSuffixes = "i"
    )

    private val rustSpec = LexSpec(
        control = words("if else match for while loop return break continue in"),
        keywords = words("as async await const crate dyn enum extern fn impl let mod move mut pub ref self Self static struct super trait type union unsafe use where macro_rules"),
        types = words("i8 i16 i32 i64 i128 isize u8 u16 u32 u64 u128 usize f32 f64 bool char str String Vec Option Result Box Rc Arc RefCell Cell Mutex RwLock HashMap HashSet BTreeMap BTreeSet VecDeque Path PathBuf"),
        constants = words("true false None Some Ok Err"),
        blockComments = listOf(
            BlockComment("/**", "*/", doc = true, nested = true),
            BlockComment("/*!", "*/", doc = true, nested = true),
            BlockComment("/*", "*/", nested = true)
        ),
        strings = listOf(
            StringRule("r##\"", "\"##", escape = null, multiline = true),
            StringRule("r#\"", "\"#", escape = null, multiline = true),
            StringRule("\"", multiline = true),
            StringRule("'")
        ),
        stringPrefixes = words("r b br c"),
        hashAttributes = true,
        macroBang = true,
        lifetimes = true,
        declFunction = words("fn"),
        declType = words("struct enum trait type union mod")
    )

    private val swiftSpec = LexSpec(
        control = words("if else guard switch case default for while repeat return break continue fallthrough throw do try catch defer where in"),
        keywords = words("import class struct enum protocol extension func var let init deinit subscript typealias associatedtype operator self Self super is as some any inout throws rethrows async await get set willSet didSet"),
        modifiers = words("public private fileprivate internal open static final lazy weak unowned mutating nonmutating override required convenience dynamic indirect optional"),
        types = words("Int Int8 Int16 Int32 Int64 UInt Double Float Bool String Character Array Dictionary Set Optional Any AnyObject Void Never Error"),
        constants = words("true false nil"),
        builtins = words("print debugPrint fatalError precondition assert min max abs zip stride"),
        strings = listOf(StringRule("\"\"\"", multiline = true, interpolation = listOf("\\(")), StringRule("\"", interpolation = listOf("\\("))),
        annotationPrefix = '@',
        preprocessor = true,
        declFunction = words("func"),
        declType = words("class struct enum protocol extension typealias")
    )

    private val dartSpec = LexSpec(
        control = words("if else switch case default for while do return break continue throw try catch finally on rethrow assert yield await"),
        keywords = words("import export library part of class extends implements with mixin enum typedef new this super is as in var final const late required get set operator factory external extension show hide async sync"),
        modifiers = words("static abstract covariant sealed base interface"),
        types = words("int double num bool String List Map Set dynamic void Object Future Stream Iterable Function Never Null Widget BuildContext State StatelessWidget StatefulWidget"),
        constants = words("true false null"),
        builtins = words("print setState build runApp"),
        strings = listOf(
            StringRule("'''", multiline = true, interpolation = listOf("\${"), dollarIdent = true),
            StringRule("\"\"\"", multiline = true, interpolation = listOf("\${"), dollarIdent = true),
            StringRule("\"", interpolation = listOf("\${"), dollarIdent = true),
            StringRule("'", interpolation = listOf("\${"), dollarIdent = true)
        ),
        stringPrefixes = words("r"),
        annotationPrefix = '@',
        declType = words("class mixin enum typedef extension")
    )

    private val jsKeywords = words("var let const function class extends new delete typeof instanceof in of this super import export from as async static get set void with debugger")
    private val jsControl = words("if else switch case default for while do return break continue throw try catch finally await yield")
    private val jsBuiltins = words("console window document globalThis process require module exports JSON Math Object Array String Number Boolean Promise Map Set WeakMap WeakSet Symbol BigInt Date RegExp Error TypeError fetch setTimeout setInterval clearTimeout clearInterval parseInt parseFloat isNaN isFinite encodeURIComponent decodeURIComponent localStorage sessionStorage navigator location history alert confirm structuredClone queueMicrotask")

    private val jsSpec = LexSpec(
        control = jsControl,
        keywords = jsKeywords,
        constants = words("true false null undefined NaN Infinity"),
        builtins = jsBuiltins,
        strings = listOf(
            StringRule("`", multiline = true, interpolation = listOf("\${")),
            StringRule("\""),
            StringRule("'")
        ),
        identExtra = "_$",
        identStartExtra = "_$",
        annotationPrefix = '@',
        regexLiterals = true,
        declFunction = words("function"),
        declType = words("class"),
        numberSuffixes = "n"
    )

    private val tsSpec = jsSpec.copy(
        keywords = jsKeywords + words("interface type enum namespace declare implements keyof infer is asserts satisfies module unique"),
        modifiers = words("abstract private public protected readonly override accessor"),
        types = words("string number boolean any unknown never void object bigint symbol Record Partial Required Readonly Pick Omit Exclude Extract NonNullable ReturnType Parameters Awaited Promise Array"),
        declType = words("class interface type enum namespace")
    )

    private val qmlSpec = jsSpec.copy(
        keywords = jsKeywords + words("import property signal readonly required default alias on component pragma enum as"),
        types = words("Item Rectangle Text Image Column Row Grid Flow Repeater Loader MouseArea HoverHandler TapHandler ListView GridView PathView Flickable ScrollView Timer Connections Binding Behavior NumberAnimation ColorAnimation PropertyAnimation SequentialAnimation ParallelAnimation PauseAnimation SpringAnimation State Transition QtObject Component Window ApplicationWindow Button Label TextField TextArea ListModel ListElement FileDialog Canvas ShaderEffect Layout RowLayout ColumnLayout GridLayout StackLayout StackView SwipeView Popup Dialog Menu MenuItem ToolTip Slider Switch CheckBox RadioButton ComboBox SpinBox ProgressBar BusyIndicator Pane Frame Page Control Shortcut Keys Qt ShellRoot PanelWindow FloatingWindow PopupWindow Variants Scope Singleton Process Io FileView Quickshell Hyprland Wayland"),
        builtins = jsBuiltins + words("parent anchors width height x y z visible opacity color text font source radius border implicitWidth implicitHeight spacing margins fill centerIn model delegate index modelData onClicked onPressed onReleased onCompleted onTriggered interval running repeat qsTr console"),
        colonProperties = true
    )

    private val pythonSpec = LexSpec(
        control = words("if elif else for while break continue return pass raise try except finally with yield await async match case"),
        keywords = words("def class import from as global nonlocal lambda del assert in is not and or"),
        constants = words("True False None Ellipsis NotImplemented __name__ __file__ __main__ __init__ __doc__ self cls"),
        builtins = words("print len range enumerate zip map filter sorted reversed sum min max abs round open input isinstance issubclass hasattr getattr setattr delattr type id dir vars super property staticmethod classmethod iter next any all repr format hash callable chr ord bin hex oct divmod pow compile exec eval globals locals breakpoint help slice"),
        types = words("int float str bool bytes bytearray list dict set frozenset tuple object complex memoryview Exception ValueError TypeError KeyError IndexError RuntimeError OSError IOError AttributeError ImportError StopIteration NotImplementedError"),
        lineComments = listOf("#"),
        blockComments = emptyList(),
        strings = listOf(
            StringRule("\"\"\"", multiline = true),
            StringRule("'''", multiline = true),
            StringRule("\""),
            StringRule("'")
        ),
        stringPrefixes = words("r u b f rb br fr rf"),
        annotationPrefix = '@',
        declFunction = words("def"),
        declType = words("class"),
        numberSuffixes = "jJ"
    )

    // Pine Script v5/v6 (TradingView)
    private val pineSpec = LexSpec(
        control = words("if else for to by while switch break continue"),
        keywords = words("and or not import as type enum method export in"),
        modifiers = words("var varip const simple series"),
        types = words("int float bool color string line label box table array matrix map polyline linefill void"),
        constants = words("true false na"),
        builtins = words(
            // пространства имён
            "ta math str request strategy input syminfo timeframe barstate session chart runtime log ticker currency " +
                "display extend format hline location order plot position scale shape size text xloc yloc earnings " +
                "dividends splits adjustment alert backadjustment dayofweek font settlement_as_close barmerge " +
                // функции
                "indicator library plotshape plotchar plotarrow plotbar plotcandle bgcolor barcolor fill alertcondition " +
                "nz fixnan timestamp max_bars_back time_close dayofmonth hour minute month second weekofyear year " +
                // переменные графика
                "open high low close volume hl2 hlc3 ohlc4 hlcc4 bar_index last_bar_index last_bar_time timenow " +
                "time_tradingday time ask bid"
        ),
        lineComments = listOf("//"),
        blockComments = emptyList(),
        strings = listOf(StringRule("\""), StringRule("'")),
        declFunction = words("method"),
        declType = words("type enum"),
        numberSuffixes = ""
    )

    private val rubySpec = LexSpec(
        control = words("if elsif else unless case when while until for in do begin rescue ensure retry redo return break next yield then end raise"),
        keywords = words("def class module self super alias undef defined? and or not require require_relative include extend prepend attr_accessor attr_reader attr_writer lambda proc loop puts print p"),
        modifiers = words("private public protected module_function"),
        constants = words("true false nil __FILE__ __LINE__ __method__"),
        lineComments = listOf("#"),
        blockComments = listOf(BlockComment("=begin", "=end", lineStart = true)),
        strings = listOf(StringRule("\"", interpolation = listOf("#{"), multiline = true), StringRule("'", multiline = true), StringRule("`")),
        variablePrefixes = "@$",
        identExtra = "_?!",
        atoms = true,
        colonProperties = true,
        declFunction = words("def"),
        declType = words("class module"),
        regexLiterals = true
    )

    private val phpSpec = LexSpec(
        control = words("if else elseif endif switch case default for foreach endfor endforeach while endwhile do return break continue throw try catch finally match yield goto"),
        keywords = words("function class interface trait enum extends implements new clone instanceof namespace use as echo print include include_once require require_once global fn list array isset unset empty die exit declare insteadof php"),
        modifiers = words("public private protected static final abstract readonly var const"),
        constants = words("true false null TRUE FALSE NULL __DIR__ __FILE__ __LINE__ __CLASS__ __FUNCTION__"),
        types = words("int float string bool array object callable iterable void mixed never self parent"),
        builtins = words("strlen str_replace substr strpos explode implode count array_map array_filter array_merge array_keys array_values in_array json_encode json_decode var_dump print_r sprintf printf preg_match preg_replace file_get_contents file_put_contents is_array is_string intval header session_start htmlspecialchars"),
        caseInsensitive = true,
        lineComments = listOf("//", "#"),
        strings = listOf(StringRule("\"", dollarIdent = true, interpolation = listOf("{$")), StringRule("'")),
        variablePrefixes = "$",
        annotationPrefix = null,
        declFunction = words("function"),
        declType = words("class interface trait enum")
    )

    private val perlSpec = LexSpec(
        control = words("if elsif else unless while until for foreach last next redo return do eval given when"),
        keywords = words("my our local sub package use require no print printf say open close die warn chomp chop defined undef ref bless shift unshift push pop splice keys values each exists delete join split map grep sort reverse sprintf lc uc length substr index scalar wantarray qw qq q and or not eq ne lt gt le ge cmp"),
        constants = words("__PACKAGE__ __FILE__ __LINE__ __END__ __DATA__ STDIN STDOUT STDERR"),
        lineComments = listOf("#"),
        blockComments = listOf(BlockComment("=pod", "=cut", lineStart = true), BlockComment("=head1", "=cut", lineStart = true)),
        strings = listOf(StringRule("\"", dollarIdent = true, multiline = true), StringRule("'", multiline = true), StringRule("`")),
        variablePrefixes = "$@%",
        regexLiterals = true,
        declFunction = words("sub"),
        declType = words("package")
    )

    private val luaSpec = LexSpec(
        control = words("if then else elseif end for while do repeat until return break goto in"),
        keywords = words("function local and or not"),
        constants = words("true false nil"),
        builtins = words("print pairs ipairs next type tostring tonumber require pcall xpcall error assert select setmetatable getmetatable rawget rawset rawequal rawlen unpack table string math os io coroutine debug utf8 _G _ENV self load loadfile dofile collectgarbage"),
        lineComments = listOf("--"),
        blockComments = listOf(BlockComment("--[==[", "]==]"), BlockComment("--[=[", "]=]"), BlockComment("--[[", "]]")),
        strings = listOf(StringRule("[[", "]]", escape = null, multiline = true), StringRule("\""), StringRule("'")),
        capitalizedTypes = false,
        declFunction = words("function")
    )

    private val shellControl = words("if then else elif fi case esac for select while until do done in function time coproc return exit break continue")
    private val shellCommands = "echo printf read cd pwd pushd popd dirs ls cat grep egrep fgrep rg sed awk find fd xargs cut sort uniq wc head tail tr tee cp mv rm mkdir rmdir touch chmod chown chgrp ln stat du df mount umount ps kill killall pkill pgrep sleep wait jobs bg fg true false curl wget tar gzip gunzip xz zstd zip unzip 7z ssh scp rsync git make cmake ninja gcc clang sudo doas systemctl journalctl loginctl pacman paru yay makepkg apt dnf flatpak adb fastboot java javac python python3 pip node npm npx bun deno docker podman date basename dirname realpath readlink mktemp command type which hash getopts ulimit umask printenv env nohup timeout test notify-send niri hyprctl swaymsg wl-copy wl-paste xdg-open jq yq less more nano vim nvim diff patch file uname whoami id groups hostname ip ping ss lsblk blkid fdisk mkfs chroot dd sha256sum md5sum base64 xxd hexdump strings tput clear watch seq shuf bc expr yes nproc free top htop"

    private val shellSpec = LexSpec(
        control = shellControl,
        keywords = words("local export readonly declare typeset unset source alias unalias eval exec set shift trap let shopt builtin enable mapfile readarray"),
        builtins = words(shellCommands),
        constants = words("true false"),
        lineComments = listOf("#"),
        lineCommentNeedsBoundary = true,
        blockComments = emptyList(),
        strings = listOf(
            StringRule("\"", multiline = true, dollarIdent = true, interpolation = listOf("\${", "$(")),
            StringRule("'", escape = null, multiline = true),
            StringRule("`", multiline = true)
        ),
        stringPrefixes = words("$"),
        variablePrefixes = "$",
        capitalizedTypes = false,
        allCapsConstants = false,
        functionCalls = false,
        declFunction = words("function"),
        operators = "=<>!&|;"
    )

    private val fishSpec = shellSpec.copy(
        control = words("if else switch case for in while break continue return end begin and or not function"),
        keywords = words("set set_color status test string math contains count argparse emit functions source builtin command abbr alias bind complete echo printf read eval exec exit type"),
        strings = listOf(StringRule("\"", multiline = true, dollarIdent = true), StringRule("'", multiline = true, escape = '\\'))
    )

    private val powershellSpec = LexSpec(
        control = words("if elseif else switch foreach for while do until break continue return throw try catch finally trap exit"),
        keywords = words("begin end process filter function param dynamicparam class enum using namespace module in data hidden static"),
        constants = words("\$true \$false \$null"),
        builtins = words("Write-Host Write-Output Write-Error Get-ChildItem Set-Location Get-Content Set-Content Get-Item Remove-Item Copy-Item Move-Item New-Item Test-Path Invoke-WebRequest Invoke-RestMethod Get-Process Stop-Process Start-Process Select-Object Where-Object ForEach-Object Sort-Object Import-Module"),
        caseInsensitive = true,
        lineComments = listOf("#"),
        blockComments = listOf(BlockComment("<#", "#>")),
        strings = listOf(
            StringRule("@\"", "\"@", escape = '`', multiline = true, dollarIdent = true),
            StringRule("@'", "'@", escape = null, multiline = true),
            StringRule("\"", escape = '`', dollarIdent = true, interpolation = listOf("$(")),
            StringRule("'", escape = null, doubledQuoteEscape = true)
        ),
        variablePrefixes = "$",
        identExtra = "_-",
        capitalizedTypes = false,
        allCapsConstants = false
    )

    private val sqlSpec = LexSpec(
        keywords = words("select from where insert into values update set delete create table drop alter add column index view trigger procedure function database schema primary key foreign references unique not null default check constraint join inner left right full outer cross natural on using group by order having limit offset fetch union all distinct as and or in between like ilike glob is exists case when then else end asc desc begin commit rollback savepoint transaction if replace returning with recursive over partition window grant revoke cascade restrict explain analyze vacuum pragma autoincrement auto_increment temporary temp truncate rename to declare return returns language plpgsql execute do conflict nothing upsert merge matched lateral materialized sequence extension type enum domain rows row only share nowait skip locked collate escape any some"),
        types = words("int integer bigint smallint tinyint mediumint serial bigserial decimal numeric real float double precision char varchar nvarchar text blob clob boolean bool date time timestamp timestamptz datetime interval json jsonb uuid bytea money inet cidr xml array"),
        builtins = words("count sum avg min max coalesce nullif cast convert substring substr trim ltrim rtrim lower upper length char_length concat now current_date current_time current_timestamp date_trunc extract round floor ceil abs ifnull isnull group_concat string_agg array_agg json_agg jsonb_build_object row_number rank dense_rank ntile lag lead first_value last_value random strftime datetime julianday printf instr hex quote typeof last_insert_rowid changes"),
        constants = words("true false null"),
        caseInsensitive = true,
        lineComments = listOf("--"),
        blockComments = listOf(BlockComment("/*", "*/")),
        strings = listOf(
            StringRule("'", escape = null, doubledQuoteEscape = true, multiline = true),
            StringRule("\"", escape = null, tok = Tok.PROPERTY),
            StringRule("`", escape = null, tok = Tok.PROPERTY)
        ),
        variablePrefixes = "@$",
        capitalizedTypes = false,
        allCapsConstants = false,
        functionCalls = true
    )

    private val cmakeSpec = LexSpec(
        control = words("if elseif else endif foreach endforeach while endwhile function endfunction macro endmacro return break continue block endblock"),
        builtins = words("add_executable add_library add_subdirectory add_custom_command add_custom_target add_compile_options add_definitions add_dependencies add_link_options cmake_minimum_required cmake_policy configure_file enable_language enable_testing add_test find_package find_library find_path find_program find_file include include_directories install link_directories link_libraries list message option project set unset set_property get_property set_target_properties target_compile_definitions target_compile_features target_compile_options target_include_directories target_link_libraries target_link_options target_sources get_filename_component file execute_process string math FetchContent_Declare FetchContent_MakeAvailable"),
        constants = words("ON OFF TRUE FALSE YES NO PUBLIC PRIVATE INTERFACE REQUIRED STATIC SHARED MODULE CMAKE_CXX_STANDARD CMAKE_BUILD_TYPE CMAKE_SOURCE_DIR CMAKE_BINARY_DIR PROJECT_NAME"),
        caseInsensitive = true,
        lineComments = listOf("#"),
        blockComments = listOf(BlockComment("#[[", "]]")),
        strings = listOf(StringRule("\"", multiline = true, interpolation = listOf("\${")), StringRule("[[", "]]", escape = null, multiline = true)),
        variablePrefixes = "$",
        capitalizedTypes = false
    )

    private val nixSpec = LexSpec(
        control = words("if then else assert with let in"),
        keywords = words("rec inherit import or"),
        constants = words("true false null"),
        builtins = words("builtins map filter toString derivation fetchurl fetchGit fetchTarball mkDerivation callPackage lib pkgs stdenv config mkIf mkOption mkEnableOption types optionals optional concatStringsSep"),
        lineComments = listOf("#"),
        blockComments = listOf(BlockComment("/*", "*/")),
        strings = listOf(StringRule("''", escape = null, multiline = true, interpolation = listOf("\${")), StringRule("\"", multiline = true, interpolation = listOf("\${"))),
        identExtra = "_-'",
        capitalizedTypes = false,
        functionCalls = false
    )

    private val haskellSpec = LexSpec(
        control = words("if then else case of do let in where"),
        keywords = words("module import qualified as hiding data type newtype class instance deriving forall family infixl infixr infix default foreign"),
        constants = words("True False Nothing Just Left Right otherwise"),
        builtins = words("map filter foldr foldl zip putStrLn print show read return pure fmap mapM mapM_ length head tail null reverse concat"),
        lineComments = listOf("--"),
        blockComments = listOf(BlockComment("{-", "-}", nested = true)),
        strings = listOf(StringRule("\""), StringRule("'")),
        identExtra = "_'",
        functionCalls = false,
        declType = words("data type newtype class")
    )

    private val mlSpec = LexSpec(
        control = words("if then else match with when for while do done to downto begin end try raise function fun"),
        keywords = words("let rec in and type module struct sig val open include of mutable exception functor method object class inherit new private virtual namespace member use yield async"),
        constants = words("true false None Some Ok Error"),
        lineComments = listOf("//"),
        blockComments = listOf(BlockComment("(*", "*)", nested = true)),
        strings = listOf(StringRule("\"\"\"", escape = null, multiline = true), StringRule("\"", multiline = true)),
        identExtra = "_'",
        functionCalls = false
    )

    private val elixirSpec = LexSpec(
        control = words("if else unless case cond with for receive try catch rescue after raise do end fn when"),
        keywords = words("def defp defmodule defmacro defmacrop defstruct defprotocol defimpl defdelegate defguard defexception import alias require use quote unquote and or not in"),
        constants = words("true false nil"),
        builtins = words("IO Enum Map List String Kernel Process Agent GenServer Task Supervisor"),
        lineComments = listOf("#"),
        blockComments = emptyList(),
        strings = listOf(
            StringRule("\"\"\"", multiline = true, interpolation = listOf("#{")),
            StringRule("\"", interpolation = listOf("#{")),
            StringRule("'")
        ),
        identExtra = "_?!",
        annotationPrefix = '@',
        atoms = true,
        colonProperties = true,
        declFunction = words("def defp defmacro"),
        declType = words("defmodule defprotocol")
    )

    private val erlangSpec = LexSpec(
        control = words("if case of receive after when end try catch begin fun"),
        keywords = words("module export import define record include behaviour behavior spec type callback and andalso or orelse not xor div rem band bor bxor bnot bsl bsr"),
        constants = words("true false undefined ok error"),
        lineComments = listOf("%"),
        blockComments = emptyList(),
        strings = listOf(StringRule("\""), StringRule("'", tok = Tok.CONSTANT)),
        variablePrefixes = "?",
        allCapsConstants = false
    )

    private val rSpec = LexSpec(
        control = words("if else repeat while function for in next break return switch"),
        constants = words("TRUE FALSE NULL Inf NaN NA NA_integer_ NA_real_ NA_character_ T F"),
        builtins = words("library require print cat paste paste0 c list vector matrix data.frame length nrow ncol sum mean median sd var min max apply sapply lapply mapply seq rep head tail summary str names colnames rownames subset merge read.csv write.csv plot ggplot aes"),
        lineComments = listOf("#"),
        blockComments = emptyList(),
        strings = listOf(StringRule("\"", multiline = true), StringRule("'", multiline = true), StringRule("`", tok = Tok.VARIABLE)),
        identExtra = "_.",
        capitalizedTypes = false
    )

    private val juliaSpec = LexSpec(
        control = words("if elseif else end for while break continue return try catch finally do begin let"),
        keywords = words("function macro module baremodule struct mutable abstract primitive type import using export const global local quote in isa where"),
        constants = words("true false nothing missing NaN Inf pi"),
        builtins = words("println print push! pop! append! length size zeros ones rand collect map filter reduce sum typeof convert string parse"),
        lineComments = listOf("#"),
        blockComments = listOf(BlockComment("#=", "=#", nested = true)),
        strings = listOf(StringRule("\"\"\"", multiline = true, interpolation = listOf("$("), dollarIdent = true), StringRule("\"", interpolation = listOf("$("), dollarIdent = true)),
        identExtra = "_!",
        annotationPrefix = '@',
        declFunction = words("function macro"),
        declType = words("struct module")
    )

    private val zigSpec = LexSpec(
        control = words("if else switch for while break continue return defer errdefer try catch orelse unreachable suspend resume nosuspend"),
        keywords = words("const var fn pub struct enum union error test comptime inline export extern packed align linksection threadlocal usingnamespace anytype anyframe opaque and or asm callconv noalias allowzero volatile"),
        types = words("i8 i16 i32 i64 i128 isize u8 u16 u32 u64 u128 usize f16 f32 f64 f80 f128 bool void noreturn type anyerror comptime_int comptime_float c_int c_uint c_char"),
        constants = words("true false null undefined"),
        blockComments = emptyList(),
        strings = listOf(StringRule("\""), StringRule("'")),
        annotationPrefix = '@',
        declFunction = words("fn"),
        declType = words("struct enum union")
    )

    private val nimSpec = LexSpec(
        control = words("if elif else when case of for while break continue return yield try except finally raise defer block"),
        keywords = words("proc func method iterator template macro converter var let const type object enum tuple ref ptr import include from export as nil and or not xor shl shr div mod in notin is isnot distinct concept static discard mixin bind using"),
        constants = words("true false nil"),
        types = words("int int8 int16 int32 int64 uint uint8 uint16 uint32 uint64 float float32 float64 bool char string seq array set Table cstring pointer"),
        builtins = words("echo len add high low inc dec newSeq toSeq assert doAssert"),
        lineComments = listOf("#"),
        blockComments = listOf(BlockComment("#[", "]#", nested = true)),
        strings = listOf(StringRule("\"\"\"", escape = null, multiline = true), StringRule("\""), StringRule("'")),
        stringPrefixes = words("r"),
        declFunction = words("proc func method iterator template macro"),
        declType = words("type")
    )

    private val dSpec = cppSpec.copy(
        keywords = words("module import class struct interface union enum template mixin alias typeof typeid is new delete this super null cast function delegate unittest version debug in out ref scope lazy pure nothrow shared immutable inout auto assert with invariant"),
        blockComments = listOf(BlockComment("/**", "*/", doc = true), BlockComment("/++", "+/", doc = true, nested = true), BlockComment("/*", "*/"), BlockComment("/+", "+/", nested = true)),
        preprocessor = false,
        annotationPrefix = '@'
    )

    private val pascalSpec = LexSpec(
        control = words("if then else case of for to downto do while repeat until with begin end try except finally raise exit break continue goto"),
        keywords = words("program unit uses interface implementation initialization finalization var const type procedure function record array set string class object constructor destructor property inherited nil and or not xor div mod shl shr in is as label packed file"),
        modifiers = words("private public protected published strict virtual override abstract overload reintroduce static inline"),
        types = words("integer cardinal longint int64 byte word boolean char real double single extended string ansistring widestring pchar pointer variant"),
        constants = words("true false nil"),
        caseInsensitive = true,
        blockComments = listOf(BlockComment("{", "}"), BlockComment("(*", "*)")),
        strings = listOf(StringRule("'", escape = null, doubledQuoteEscape = true)),
        capitalizedTypes = false,
        declFunction = words("procedure function constructor destructor")
    )

    private val vbSpec = LexSpec(
        control = words("if then else elseif end select case for each to step next while wend do loop until return exit goto on error resume"),
        keywords = words("dim redim preserve set let const sub function property class module structure enum interface imports namespace inherits implements new me mybase and or not xor andalso orelse is isnot like mod call byval byref optional paramarray as of get set with option explicit strict"),
        modifiers = words("public private protected friend shared static overrides overridable mustoverride readonly withevents"),
        types = words("integer long short byte single double decimal string boolean date object variant char"),
        constants = words("true false nothing null empty vbcrlf vbnewline"),
        caseInsensitive = true,
        lineComments = listOf("'", "REM "),
        lineCommentNeedsBoundary = true,
        blockComments = emptyList(),
        strings = listOf(StringRule("\"", escape = null, doubledQuoteEscape = true)),
        capitalizedTypes = false,
        declFunction = words("sub function"),
        declType = words("class module structure enum interface")
    )

    private val glslSpec = cSpec.copy(
        keywords = cSpec.keywords + words("uniform attribute varying in out inout layout precision highp mediump lowp discard invariant centroid flat smooth noperspective buffer shared subroutine fn let var struct cbuffer register"),
        types = words("void bool int uint float double vec2 vec3 vec4 ivec2 ivec3 ivec4 uvec2 uvec3 uvec4 bvec2 bvec3 bvec4 dvec2 dvec3 dvec4 mat2 mat3 mat4 mat2x2 mat3x3 mat4x4 sampler2D sampler3D samplerCube sampler2DArray image2D float2 float3 float4 float4x4 half half3 half4 Texture2D SamplerState f32 i32 u32 vec2f vec3f vec4f mat4x4f"),
        builtins = words("texture texture2D mix clamp smoothstep step normalize dot cross length distance reflect refract pow exp log sqrt inversesqrt abs sign floor ceil fract mod min max sin cos tan asin acos atan radians degrees transpose inverse determinant dFdx dFdy fwidth lerp saturate mul gl_Position gl_FragCoord gl_FragColor gl_VertexID main"),
        capitalizedTypes = true
    )

    private val protoSpec = LexSpec(
        keywords = words("syntax edition package import option message enum service rpc returns stream repeated optional required oneof map reserved extensions extend to max public weak group"),
        types = words("double float int32 int64 uint32 uint64 sint32 sint64 fixed32 fixed64 sfixed32 sfixed64 bool string bytes google"),
        constants = words("true false"),
        strings = listOf(StringRule("\""), StringRule("'")),
        declType = words("message enum service"),
        declFunction = words("rpc")
    )

    private val graphqlSpec = LexSpec(
        keywords = words("query mutation subscription fragment on type interface union enum input scalar schema extend implements directive repeatable"),
        types = words("Int Float String Boolean ID"),
        constants = words("true false null"),
        lineComments = listOf("#"),
        blockComments = emptyList(),
        strings = listOf(StringRule("\"\"\"", multiline = true), StringRule("\"")),
        variablePrefixes = "$",
        annotationPrefix = '@',
        declType = words("type interface union enum input scalar fragment")
    )

    private val hclSpec = LexSpec(
        keywords = words("resource data variable output locals module provider terraform backend required_providers for_each count depends_on lifecycle dynamic content for in if source version"),
        constants = words("true false null"),
        builtins = words("var local module data each self path file templatefile jsonencode jsondecode lookup merge concat length element format join split toset tomap tolist"),
        lineComments = listOf("#", "//"),
        strings = listOf(StringRule("\"", interpolation = listOf("\${", "%{"))),
        identExtra = "_-",
        capitalizedTypes = false,
        allCapsConstants = false
    )

    private val solidityCommon = words("pragma solidity contract interface library function modifier event emit struct enum mapping payable returns import using is new delete memory storage calldata constant immutable override virtual abstract constructor receive fallback assembly unchecked error revert require assert")
    private val soliditySpec = cSpec.copy(
        keywords = solidityCommon,
        modifiers = words("public private internal external view pure"),
        types = words("address bool string bytes byte int uint int8 int16 int32 int64 int128 int256 uint8 uint16 uint32 uint64 uint128 uint256 bytes1 bytes4 bytes32"),
        constants = words("true false wei gwei ether seconds minutes hours days weeks msg block tx this"),
        builtins = words("keccak256 sha256 ecrecover abi"),
        preprocessor = false,
        capitalizedTypes = true,
        declFunction = words("function modifier event"),
        declType = words("contract interface library struct enum")
    )

    private val awkSpec = LexSpec(
        control = words("if else while for do break continue next nextfile exit return in"),
        keywords = words("BEGIN END BEGINFILE ENDFILE function func delete getline print printf"),
        builtins = words("length substr index split sub gsub gensub match sprintf tolower toupper system close fflush srand rand int sqrt exp log sin cos atan2 strftime systime"),
        constants = words("NR NF FS OFS RS ORS FILENAME FNR RSTART RLENGTH SUBSEP ENVIRON ARGC ARGV CONVFMT OFMT"),
        lineComments = listOf("#"),
        blockComments = emptyList(),
        strings = listOf(StringRule("\"")),
        variablePrefixes = "$",
        regexLiterals = true,
        capitalizedTypes = false,
        declFunction = words("function func")
    )

    private val tclSpec = LexSpec(
        control = words("if else elseif for foreach while switch return break continue catch try finally throw"),
        keywords = words("proc set unset global upvar uplevel variable namespace package source expr puts gets open close list lindex lappend llength lrange lsearch lsort string regexp regsub incr append eval array dict info after exec exit format scan"),
        lineComments = listOf("#"),
        blockComments = emptyList(),
        strings = listOf(StringRule("\"", multiline = true)),
        variablePrefixes = "$",
        functionCalls = false,
        capitalizedTypes = false,
        declFunction = words("proc")
    )

    private val vimSpec = LexSpec(
        control = words("if else elseif endif for endfor while endwhile try catch finally endtry return break continue throw"),
        keywords = words("function endfunction func endfunc let unlet const set setlocal setglobal call echo echom echohl execute exe autocmd au augroup command map nmap vmap xmap imap omap cmap nnoremap vnoremap xnoremap inoremap onoremap cnoremap noremap syntax highlight hi colorscheme source runtime filetype packadd lua silent normal abort range dict closure"),
        constants = words("v:true v:false v:null"),
        lineComments = listOf("\""),
        lineCommentOnlyAtStart = true,
        blockComments = emptyList(),
        strings = listOf(StringRule("'", escape = null, doubledQuoteEscape = true), StringRule("\"")),
        identExtra = "_:#",
        capitalizedTypes = false,
        allCapsConstants = false
    )

    private val mesonSpec = LexSpec(
        control = words("if elif else endif foreach endforeach break continue"),
        keywords = words("and or not in"),
        constants = words("true false"),
        builtins = words("project executable library shared_library static_library both_libraries shared_module dependency declare_dependency find_program run_command run_target configure_file custom_target subdir subproject install_data install_headers install_subdir install_man message error warning summary import files include_directories get_option get_variable set_variable meson host_machine build_machine target_machine test benchmark generator environment join_paths"),
        lineComments = listOf("#"),
        blockComments = emptyList(),
        strings = listOf(StringRule("'''", multiline = true), StringRule("'")),
        stringPrefixes = words("f"),
        capitalizedTypes = false
    )

    private val odinSpec = cSpec.copy(
        keywords = words("package import proc struct enum union map bit_set distinct using cast auto_cast transmute defer when where in not_in dynamic context or_else or_return foreign"),
        types = words("int i8 i16 i32 i64 uint u8 u16 u32 u64 f32 f64 bool b8 string cstring rune rawptr typeid any"),
        constants = words("true false nil"),
        preprocessor = false,
        annotationPrefix = '@',
        capitalizedTypes = true
    )

    private val crystalSpec = rubySpec.copy(
        keywords = rubySpec.keywords + words("struct lib fun enum macro abstract annotation property getter setter record typeof sizeof pointerof uninitialized as"),
        types = words("Int32 Int64 UInt8 Float64 String Bool Nil Array Hash Symbol Char Tuple NamedTuple Proc")
    )

    // ---------- Языки ----------

    private fun ext(s: String) = words(s).toList()

    val PLAIN = Language("text", "Текст", ext("txt text"), listOf("README", "LICENSE", "COPYING", "AUTHORS", "CHANGELOG", "NEWS", "TODO", "INSTALL"),
        autoPairs = "()[]{}\"\"", lexerFactory = { PlainTextLexer })

    val SMALI = Language(
        "smali", "Smali", ext("smali"),
        lineComment = "#",
        autoPairs = "(){}\"\"",
        indentAfter = Regex("^\\s*\\.(method|annotation|subannotation|packed-switch|sparse-switch|array-data)\\b.*$"),
        dedentLine = Regex("^\\s*\\.end\\s+\\w[\\w-]*\\s*$"),
        wordChars = "_$",
        lexerFactory = { SmaliLexer },
        vocabularyFactory = { LanguageData.smali() }
    )

    /** Листинг .class (вкладка «Байт-код»): своего расширения у формата нет. */
    val JVM_BYTECODE = Language(
        "jvm-bytecode", "Байт-код JVM",
        lineComment = "//",
        autoPairs = "()[]{}\"\"",
        wordChars = "_$/<>",
        lexerFactory = { JvmBytecodeLexer }
    )

    val JAVA = Language("java", "Java", ext("java jav aidl jsh"), lineComment = "//", blockComment = "/*" to "*/",
        indentAfter = BRACE_INDENT, wordChars = "_$", spec = javaSpec, vocabularyFactory = { LanguageData.java() })

    val KOTLIN = Language("kotlin", "Kotlin", ext("kt kts ktm"), lineComment = "//", blockComment = "/*" to "*/",
        indentAfter = BRACE_INDENT, wordChars = "_$", spec = kotlinSpec, vocabularyFactory = { LanguageData.kotlin() })

    val GROOVY = Language("groovy", "Groovy / Gradle", ext("groovy gradle gvy gy gsh"), listOf("Jenkinsfile"),
        lineComment = "//", blockComment = "/*" to "*/", indentAfter = BRACE_INDENT, wordChars = "_$", spec = groovySpec,
        vocabularyFactory = { LanguageData.gradle() })

    val SCALA = Language("scala", "Scala", ext("scala sc sbt"), lineComment = "//", blockComment = "/*" to "*/",
        indentAfter = Regex("([{\\[(]|=>|=|:)\\s*$"), defaultIndent = "  ", spec = scalaSpec)

    val C = Language("c", "C", ext("c h"), lineComment = "//", blockComment = "/*" to "*/", indentAfter = BRACE_INDENT,
        spec = cSpec, vocabularyFactory = { LanguageData.c() })

    val CPP = Language("cpp", "C++", ext("cpp cc cxx c++ hpp hh hxx h++ ipp inl ino tpp cppm ixx"), lineComment = "//",
        blockComment = "/*" to "*/", indentAfter = BRACE_INDENT, spec = cppSpec, vocabularyFactory = { LanguageData.c() })

    val OBJC = Language("objc", "Objective-C", ext("m mm"), lineComment = "//", blockComment = "/*" to "*/",
        indentAfter = BRACE_INDENT, spec = objcSpec)

    val CSHARP = Language("csharp", "C#", ext("cs csx"), lineComment = "//", blockComment = "/*" to "*/",
        indentAfter = BRACE_INDENT, spec = csharpSpec)

    val GO = Language("go", "Go", ext("go"), lineComment = "//", blockComment = "/*" to "*/", autoPairs = "()[]{}\"\"''``",
        indentAfter = BRACE_INDENT, defaultIndent = "\t", spec = goSpec, vocabularyFactory = { LanguageData.go() })

    val RUST = Language("rust", "Rust", ext("rs"), lineComment = "//", blockComment = "/*" to "*/", autoPairs = "()[]{}\"\"",
        indentAfter = BRACE_INDENT, spec = rustSpec, vocabularyFactory = { LanguageData.rust() })

    val SWIFT = Language("swift", "Swift", ext("swift"), lineComment = "//", blockComment = "/*" to "*/",
        autoPairs = "()[]{}\"\"", indentAfter = BRACE_INDENT, spec = swiftSpec)

    val DART = Language("dart", "Dart", ext("dart"), lineComment = "//", blockComment = "/*" to "*/",
        indentAfter = BRACE_INDENT, defaultIndent = "  ", spec = dartSpec)

    val JAVASCRIPT = Language("javascript", "JavaScript", ext("js mjs cjs jsx es6 gs"), interpreters = listOf("node", "nodejs", "bun", "deno", "gjs"),
        lineComment = "//", blockComment = "/*" to "*/", autoPairs = "()[]{}\"\"''``", indentAfter = BRACE_INDENT,
        defaultIndent = "  ", wordChars = "_$", spec = jsSpec, vocabularyFactory = { LanguageData.javascript() })

    val TYPESCRIPT = Language("typescript", "TypeScript", ext("ts tsx mts cts"), lineComment = "//", blockComment = "/*" to "*/",
        autoPairs = "()[]{}\"\"''``", indentAfter = BRACE_INDENT, defaultIndent = "  ", wordChars = "_$", spec = tsSpec,
        vocabularyFactory = { LanguageData.javascript() })

    val QML = Language("qml", "QML", ext("qml qmltypes"), lineComment = "//", blockComment = "/*" to "*/",
        indentAfter = BRACE_INDENT, wordChars = "_$", spec = qmlSpec, vocabularyFactory = { LanguageData.qml() })

    val PYTHON = Language(
        "python", "Python", ext("py pyw pyi pyx pxd gyp gypi bzl star"),
        listOf("SConstruct", "SConscript", "BUILD", "BUILD.bazel", "WORKSPACE", "WORKSPACE.bazel", "Tiltfile", "Snakefile"),
        interpreters = listOf("python", "python2", "python3", "pypy", "pypy3"),
        lineComment = "#",
        indentAfter = Regex(":\\s*(#.*)?$"),
        dedentLine = Regex("^\\s*(else|elif\\b.*|except\\b.*|finally)\\s*:\\s*$"),
        dedentAfter = Regex("^\\s*(return\\b.*|pass|break|continue|raise\\b.*)\\s*$"),
        significantIndent = true,
        spec = pythonSpec,
        vocabularyFactory = { LanguageData.python() }
    )

    val PINE = Language(
        "pine", "Pine Script", ext("pine pinescript"),
        lineComment = "//",
        autoPairs = "()[]{}\"\"''",
        // Блок открывают: функция «f(x) =>», if/else/for/while/switch/type/enum, «x = if …» / «x = switch …»
        indentAfter = Regex("=>\\s*(//.*)?$|^\\s*(if|else|for|while|switch|type|enum)\\b.*$|(?<![=!<>])[=:]\\s*(if|switch)\\b.*$"),
        dedentLine = Regex("^\\s*else(\\s+if\\b.*)?\\s*$"),
        significantIndent = true,
        spec = pineSpec,
        lexerFactory = { PineLexer(pineSpec) },
        vocabularyFactory = { LanguageData.pine() }
    )

    val RUBY = Language(
        "ruby", "Ruby", ext("rb rake gemspec ru erb rbw"), listOf("Gemfile", "Rakefile", "Podfile", "Vagrantfile", "Brewfile", "Fastfile", "Guardfile"),
        interpreters = listOf("ruby"),
        lineComment = "#", blockComment = "=begin" to "=end",
        indentAfter = Regex("(\\b(do|then)\\s*(\\|[^|]*\\|)?|[{\\[(])\\s*$|^\\s*(def|class|module|if|unless|while|until|case|begin|else|elsif|when|rescue|ensure|for)\\b(?!.*\\bend\\s*$).*$"),
        dedentLine = Regex("^\\s*(end|else|elsif\\b.*|when\\b.*|rescue\\b.*|ensure)\\s*$"),
        defaultIndent = "  ", wordChars = "_?!", spec = rubySpec
    )

    val CRYSTAL = Language("crystal", "Crystal", ext("cr"), interpreters = listOf("crystal"), lineComment = "#",
        indentAfter = RUBY.indentAfter, dedentLine = RUBY.dedentLine, defaultIndent = "  ", wordChars = "_?!", spec = crystalSpec)

    val PHP = Language("php", "PHP", ext("php phtml php3 php4 php5 php7 phps inc"), interpreters = listOf("php"),
        lineComment = "//", blockComment = "/*" to "*/", indentAfter = BRACE_INDENT, wordChars = "_$", spec = phpSpec)

    val PERL = Language("perl", "Perl", ext("pl pm t pod psgi"), interpreters = listOf("perl", "perl5"),
        lineComment = "#", indentAfter = BRACE_INDENT, wordChars = "_", spec = perlSpec)

    val LUA = Language(
        "lua", "Lua", ext("lua luau rockspec wlua"), interpreters = listOf("lua", "luajit", "lua5.1", "lua5.3", "lua5.4"),
        lineComment = "--", blockComment = "--[[" to "]]",
        indentAfter = Regex("(\\b(then|do|else|repeat)|\\bfunction\\b[^)]*\\)|[{(\\[])\\s*(--.*)?$"),
        dedentLine = Regex("^\\s*(end|else|elseif\\b.*|until\\b.*)\\s*[)},]*\\s*$"),
        spec = luaSpec, vocabularyFactory = { LanguageData.lua() }
    )

    private val shellIndent = Regex("(\\b(then|do|else)|\\{|\\(|\\bin)\\s*(#.*)?$")
    private val shellDedent = Regex("^\\s*(fi|done|esac|else|elif\\b.*|\\})\\s*;?\\s*$")

    val SHELL = Language(
        "shell", "Shell", ext("sh bash zsh ksh mksh dash ash bats command install ebuild eclass zshrc bashrc"),
        listOf(".bashrc", ".bash_profile", ".bash_logout", ".bash_aliases", ".profile", ".zshrc", ".zshenv", ".zprofile", ".zlogin", ".zlogout", ".kshrc", ".xinitrc", ".xprofile", ".xsession", "PKGBUILD", "APKBUILD", "configure", "gradlew", "mvnw", ".envrc", "profile", "bashrc", "zshrc"),
        interpreters = listOf("sh", "bash", "zsh", "ksh", "mksh", "dash", "ash", "busybox", "bats", "nix-shell"),
        lineComment = "#",
        autoPairs = "()[]{}\"\"''``",
        indentAfter = shellIndent,
        dedentLine = shellDedent,
        spec = shellSpec,
        lexerFactory = { HeredocLexer(CLikeLexer(shellSpec)) },
        vocabularyFactory = { LanguageData.shell() }
    )

    val FISH = Language(
        "fish", "Fish", ext("fish"), interpreters = listOf("fish"),
        lineComment = "#",
        indentAfter = Regex("^\\s*(if|else|for|while|function|switch|case|begin)\\b.*$"),
        dedentLine = Regex("^\\s*(end|else\\b.*|case\\b.*)\\s*$"),
        spec = fishSpec,
        vocabularyFactory = { LanguageData.shell() }
    )

    val POWERSHELL = Language("powershell", "PowerShell", ext("ps1 psm1 psd1"), interpreters = listOf("pwsh", "powershell"),
        lineComment = "#", blockComment = "<#" to "#>", indentAfter = BRACE_INDENT, wordChars = "_-", caseInsensitive = true, spec = powershellSpec)

    val BATCH = Language("batch", "Batch", ext("bat cmd btm"), lineComment = "REM ", autoPairs = "()\"\"",
        indentAfter = Regex("\\(\\s*$"), caseInsensitive = true, lexerFactory = { BatchLexer })

    val SQL = Language("sql", "SQL", ext("sql ddl dml pgsql psql mysql sqlite hql cql"), lineComment = "--", blockComment = "/*" to "*/",
        indentAfter = Regex("\\(\\s*$|\\b(BEGIN|begin)\\s*$"), caseInsensitive = true, spec = sqlSpec,
        vocabularyFactory = { LanguageData.sql() })

    val XML = Language(
        "xml", "XML", ext("xml xsd xsl xslt wsdl rss atom xaml csproj vbproj vcxproj props targets fxml iml pom plist svg kml gpx dae qrc ui policy xhtml xul tld jnlp resx nuspec storyboard xib glade mobileconfig manifest ant"),
        fileNamePatterns = listOf(Regex(".*\\.(xml|svg)\\.(in|tmpl)")),
        blockComment = "<!--" to "-->",
        autoPairs = "\"\"''",
        wordChars = "_-:.",
        markup = true,
        lexerFactory = { XmlLexer },
        vocabularyFactory = { LanguageData.androidXml() }
    )

    val HTML = Language(
        "html", "HTML", ext("html htm shtml xht vue svelte astro hbs handlebars mustache jinja jinja2 j2 twig njk ejs"),
        blockComment = "<!--" to "-->",
        autoPairs = "()[]{}\"\"''",
        wordChars = "_-",
        markup = true,
        defaultIndent = "  ",
        lexerFactory = { HtmlLexer(JAVASCRIPT.lexer, CssLexer(false)) },
        vocabularyFactory = { LanguageData.html() }
    )

    val CSS = Language("css", "CSS", ext("css rasi"), blockComment = "/*" to "*/", indentAfter = BRACE_INDENT,
        wordChars = "_-", defaultIndent = "  ", lexerFactory = { CssLexer(false) }, vocabularyFactory = { LanguageData.css() })

    val SCSS = Language("scss", "SCSS / Less", ext("scss sass less styl pcss postcss"), lineComment = "//", blockComment = "/*" to "*/",
        indentAfter = BRACE_INDENT, wordChars = "_-", defaultIndent = "  ", lexerFactory = { CssLexer(true) },
        vocabularyFactory = { LanguageData.css() })

    val JSON = Language(
        "json", "JSON", ext("json jsonc json5 jsonl ndjson geojson har webmanifest topojson avsc mcmeta ipynb gltf babelrc eslintrc prettierrc jshintrc swcrc code-workspace code-snippets sublime-settings sublime-project arb"),
        listOf(".babelrc", ".eslintrc", ".prettierrc", ".jshintrc", ".swcrc", "composer.lock", "flake.lock", "package-lock.json", "Pipfile.lock", ".watchmanconfig", "waybar-config", ".luarc", ".clangd.json"),
        lineComment = "//", blockComment = "/*" to "*/",
        autoPairs = "{}[]\"\"",
        indentAfter = BRACE_INDENT,
        defaultIndent = "  ",
        wordChars = "_-$",
        lexerFactory = { JsonLexer },
        vocabularyFactory = { Vocabulary(listOf(VocabWord("true", ItemKind.CONSTANT), VocabWord("false", ItemKind.CONSTANT), VocabWord("null", ItemKind.CONSTANT))) }
    )

    val YAML = Language(
        "yaml", "YAML", ext("yml yaml clang-format clang-tidy sublime-syntax gemrc condarc"),
        listOf(".clang-format", ".clang-tidy", ".gemrc", "yarn.lock", "pubspec.lock", ".yamllint", "apktool.yml", ".gitlab-ci.yml", "CITATION.cff"),
        lineComment = "#",
        autoPairs = "{}[]\"\"''",
        indentAfter = Regex(":\\s*(#.*)?$|^\\s*-\\s*$|[\\[{]\\s*$"),
        defaultIndent = "  ",
        wordChars = "_-",
        significantIndent = true,
        lexerFactory = { YamlLexer },
        vocabularyFactory = { LanguageData.yaml() }
    )

    val TOML = Language("toml", "TOML", ext("toml"), listOf("Cargo.lock", "Pipfile", "poetry.lock", "uv.lock", "pdm.lock", "gopkg.lock", ".cargo-ok", "rust-toolchain"),
        lineComment = "#", autoPairs = "{}[]\"\"''", wordChars = "_-", indentAfter = Regex("[\\[{]\\s*$"),
        lexerFactory = { IniLexer(toml = true) })

    val INI = Language(
        "ini", "INI / conf", ext("ini cfg conf cnf desktop service socket timer target mount automount path slice network netdev link container volume pod kube kvconfig theme inf reg url editorconfig gitconfig gitmodules npmrc pylintrc flake8 wgetrc hgrc directory lnk list rules repo tmux"),
        listOf(".editorconfig", ".gitconfig", ".gitmodules", ".gitattributes", ".npmrc", ".yarnrc", ".pylintrc", ".flake8", ".wgetrc", ".hgrc", ".coveragerc", ".pypirc", "mimeapps.list", "pacman.conf", "makepkg.conf", "hyprland.conf", "hyprpaper.conf", "hypridle.conf", "hyprlock.conf", "kitty.conf", "foot.ini", "alacritty.toml", "user-dirs.dirs", "user-dirs.locale", "config", "setup.cfg", "tox.ini", "mpv.conf", "input.conf", "picom.conf", "dunstrc", "fuzzel.ini", "wofi.conf", "sshd_config", "ssh_config", "fstab", "hosts", "resolv.conf", "locale.conf", "vconsole.conf", "environment"),
        fileNamePatterns = listOf(Regex(".*rc\\.conf"), Regex(".*\\.conf\\.(d|bak|orig|pacnew|pacsave)")),
        lineComment = "#",
        autoPairs = "[]\"\"{}()",
        wordChars = "_-.",
        indentAfter = Regex("\\{\\s*$"),
        lexerFactory = { IniLexer() }
    )

    val PROPERTIES = Language("properties", "Properties", ext("properties prop lang"), listOf("build.prop", "default.prop", "vendor.prop", "system.prop", "gradle.properties", "local.properties", "gradle-wrapper.properties"),
        lineComment = "#", autoPairs = "\"\"{}", wordChars = "_-.", lexerFactory = { IniLexer(properties = true) })

    val DOTENV = Language("dotenv", ".env", ext("env"), listOf(".env", ".envrc.local", ".flaskenv"),
        fileNamePatterns = listOf(Regex("\\.env\\..+"), Regex(".+\\.env")),
        lineComment = "#", autoPairs = "\"\"''", wordChars = "_", lexerFactory = { IniLexer() })

    val MARKDOWN = Language("markdown", "Markdown", ext("md markdown mdown mkd mkdn mdx rmd qmd"), listOf("README.md"),
        blockComment = "<!--" to "-->", autoPairs = "()[]{}\"\"``", wordChars = "_-", defaultIndent = "  ",
        lexerFactory = { MarkdownLexer }, vocabularyFactory = { LanguageData.markdown() })

    val DIFF = Language("diff", "Diff / Patch", ext("diff patch rej"), autoPairs = "", lexerFactory = { DiffLexer })

    val KDL = Language("kdl", "KDL (niri)", ext("kdl"), lineComment = "//", blockComment = "/*" to "*/", autoPairs = "{}\"\"()",
        indentAfter = Regex("\\{\\s*(//.*)?$"), wordChars = "_-+", lexerFactory = { KdlLexer },
        vocabularyFactory = { LanguageData.niri() })

    val MAKEFILE = Language(
        "makefile", "Makefile", ext("mk mak make mkfile d pro pri qmake"),
        listOf("Makefile", "makefile", "GNUmakefile", "BSDmakefile", "Makefile.am", "Makefile.in", "Justfile", "justfile", ".justfile", "Kbuild", "Kconfig.include", "qmake.conf"),
        fileNamePatterns = listOf(Regex("makefile\\..+"), Regex(".+\\.mk\\..+")),
        lineComment = "#",
        autoPairs = "()[]{}\"\"''",
        indentAfter = Regex("^[^\\s#=][^=]*:(?!=)[^=]*$"),
        defaultIndent = "\t",
        wordChars = "_-.",
        lexerFactory = { MakefileLexer(SHELL.lexer) },
        vocabularyFactory = { LanguageData.makefile() }
    )

    val CMAKE = Language("cmake", "CMake", ext("cmake"), listOf("CMakeLists.txt", "CMakeCache.txt"), lineComment = "#",
        autoPairs = "()\"\"{}", indentAfter = Regex("\\(\\s*$|^\\s*(if|elseif|else|foreach|while|function|macro|block)\\b.*\\)\\s*$", RegexOption.IGNORE_CASE),
        dedentLine = Regex("^\\s*(endif|endforeach|endwhile|endfunction|endmacro|endblock|else|elseif)\\b.*$", RegexOption.IGNORE_CASE),
        caseInsensitive = true, spec = cmakeSpec)

    val DOCKERFILE = Language("dockerfile", "Dockerfile", ext("dockerfile containerfile"), listOf("Dockerfile", "Containerfile"),
        fileNamePatterns = listOf(Regex("(docker|container)file\\..+"), Regex(".+\\.(docker|container)file")),
        lineComment = "#", autoPairs = "()[]{}\"\"''", lexerFactory = { DockerfileLexer(SHELL.lexer) },
        vocabularyFactory = { LanguageData.dockerfile() })

    val NIX = Language("nix", "Nix", ext("nix"), lineComment = "#", blockComment = "/*" to "*/", indentAfter = Regex("([{\\[(]|\\blet|=)\\s*$"),
        defaultIndent = "  ", wordChars = "_-'", spec = nixSpec)

    val HASKELL = Language("haskell", "Haskell", ext("hs lhs hs-boot hsc cabal"), interpreters = listOf("runhaskell", "runghc", "stack"),
        lineComment = "--", blockComment = "{-" to "-}", indentAfter = Regex("(\\bwhere|\\bdo|\\bof|\\blet|=|->)\\s*$"),
        defaultIndent = "  ", wordChars = "_'", significantIndent = true, spec = haskellSpec)

    val OCAML = Language("ocaml", "OCaml / F#", ext("ml mli mll mly fs fsi fsx fsscript re rei"), interpreters = listOf("ocaml", "dotnet"),
        lineComment = "//", blockComment = "(*" to "*)", indentAfter = Regex("(=|->|\\bthen|\\belse|\\bdo|\\bbegin|\\bstruct|\\bsig|\\bwith|[{\\[(])\\s*$"),
        defaultIndent = "  ", wordChars = "_'", spec = mlSpec)

    val ELIXIR = Language("elixir", "Elixir", ext("ex exs eex heex leex"), listOf("mix.lock"), interpreters = listOf("elixir", "iex"),
        lineComment = "#", indentAfter = Regex("(\\bdo|->|\\bfn|[{\\[(])\\s*$"), dedentLine = Regex("^\\s*(end|else|rescue|catch|after)\\s*$"),
        defaultIndent = "  ", wordChars = "_?!", spec = elixirSpec)

    val ERLANG = Language("erlang", "Erlang", ext("erl hrl app.src escript"), listOf("rebar.config", "rebar.lock"),
        interpreters = listOf("escript"), lineComment = "%", indentAfter = Regex("(->|\\bof|\\breceive|\\bbegin|\\btry|\\bif|[{\\[(])\\s*$"),
        dedentLine = Regex("^\\s*(end\\b.*|after\\b.*)$"), spec = erlangSpec)

    val CLOJURE = Language("lisp", "Lisp / Clojure", ext("clj cljs cljc edn lisp lsp cl el scm ss rkt fnl janet hy boot"),
        listOf(".emacs", "project.clj", "deps.edn", "shadow-cljs.edn", "bb.edn"),
        interpreters = listOf("bb", "clojure", "clj", "sbcl", "racket", "guile", "fennel", "emacs"),
        lineComment = ";", blockComment = "#|" to "|#", autoPairs = "()[]{}\"\"", defaultIndent = "  ",
        wordChars = "_-?!*<>=/+.:", lexerFactory = { LispLexer })

    val R = Language("r", "R", ext("r rprofile rscript rd"), listOf(".Rprofile", ".Renviron"), interpreters = listOf("Rscript"),
        lineComment = "#", indentAfter = BRACE_INDENT, defaultIndent = "  ", wordChars = "_.", spec = rSpec)

    val JULIA = Language("julia", "Julia", ext("jl"), interpreters = listOf("julia"), lineComment = "#", blockComment = "#=" to "=#",
        indentAfter = Regex("^\\s*(function|if|elseif|else|for|while|begin|let|struct|mutable struct|module|try|catch|finally|quote|macro|do)\\b(?!.*\\bend\\s*$).*$|\\bdo\\b.*$|[{\\[(]\\s*$"),
        dedentLine = Regex("^\\s*(end|else|elseif\\b.*|catch\\b.*|finally)\\s*$"), wordChars = "_!", spec = juliaSpec)

    val ZIG = Language("zig", "Zig", ext("zig zon"), lineComment = "//", indentAfter = BRACE_INDENT, spec = zigSpec)

    val NIM = Language("nim", "Nim", ext("nim nims nimble"), lineComment = "#", blockComment = "#[" to "]#",
        indentAfter = Regex("(:|=|\\bobject|\\benum|\\btuple|\\bvar|\\blet|\\bconst|\\btype)\\s*(#.*)?$"), defaultIndent = "  ",
        significantIndent = true, spec = nimSpec)

    val D = Language("d", "D", ext("d di"), lineComment = "//", blockComment = "/*" to "*/", indentAfter = BRACE_INDENT, spec = dSpec)

    val PASCAL = Language("pascal", "Pascal / Delphi", ext("pas pp dpr lpr dpk lpk inc"), lineComment = "//", blockComment = "{" to "}",
        autoPairs = "()[]''", indentAfter = Regex("\\b(begin|record|class|interface|try|repeat|case\\b.*\\bof|var|const|type)\\s*$", RegexOption.IGNORE_CASE),
        dedentLine = Regex("^\\s*(end\\b.*|until\\b.*|except|finally)$", RegexOption.IGNORE_CASE),
        defaultIndent = "  ", caseInsensitive = true, spec = pascalSpec)

    val VB = Language("vb", "Visual Basic", ext("vb vbs bas cls frm vba"), lineComment = "'", autoPairs = "()\"\"",
        indentAfter = Regex("(\\bThen|^\\s*(Sub|Function|Private Sub|Public Sub|Private Function|Public Function|Class|Module|For|Do|While|Select|With|Try|Else|ElseIf|Case)\\b.*)\\s*$", RegexOption.IGNORE_CASE),
        dedentLine = Regex("^\\s*(End\\b.*|Next\\b.*|Loop\\b.*|Wend|Else|ElseIf\\b.*|Case\\b.*)$", RegexOption.IGNORE_CASE),
        caseInsensitive = true, spec = vbSpec)

    val ASM = Language("asm", "Assembly", ext("asm s nasm yasm masm a51 inc S"), lineComment = ";", autoPairs = "()[]\"\"''",
        defaultIndent = "\t", wordChars = "_.$%", lexerFactory = { AsmLexer })

    val GLSL = Language("glsl", "GLSL / HLSL / WGSL", ext("glsl vert frag geom comp tesc tese rgen rchit rmiss vsh fsh shader hlsl fx fxh wgsl metal cginc usf ush"),
        lineComment = "//", blockComment = "/*" to "*/", indentAfter = BRACE_INDENT, spec = glslSpec)

    val PROTOBUF = Language("protobuf", "Protocol Buffers", ext("proto proto3 textproto pbtxt"), lineComment = "//", blockComment = "/*" to "*/",
        indentAfter = BRACE_INDENT, spec = protoSpec)

    val GRAPHQL = Language("graphql", "GraphQL", ext("graphql gql graphqls"), lineComment = "#", indentAfter = BRACE_INDENT,
        defaultIndent = "  ", spec = graphqlSpec)

    val HCL = Language("hcl", "Terraform / HCL", ext("tf tfvars hcl nomad tftest"), lineComment = "#", blockComment = "/*" to "*/",
        indentAfter = BRACE_INDENT, defaultIndent = "  ", wordChars = "_-", spec = hclSpec)

    val SOLIDITY = Language("solidity", "Solidity", ext("sol"), lineComment = "//", blockComment = "/*" to "*/",
        indentAfter = BRACE_INDENT, spec = soliditySpec)

    val AWK = Language("awk", "AWK", ext("awk gawk mawk nawk"), interpreters = listOf("awk", "gawk", "mawk", "nawk"),
        lineComment = "#", indentAfter = BRACE_INDENT, spec = awkSpec)

    val TCL = Language("tcl", "Tcl", ext("tcl tk itcl exp"), interpreters = listOf("tclsh", "wish", "expect"),
        lineComment = "#", indentAfter = BRACE_INDENT, spec = tclSpec)

    val VIM = Language("vim", "Vim script", ext("vim vimrc gvimrc exrc"), listOf(".vimrc", ".gvimrc", "_vimrc", ".exrc", "vimrc"),
        lineComment = "\"", autoPairs = "()[]{}''",
        indentAfter = Regex("^\\s*(fu|fun|func|function|if|else|elseif|for|while|try|catch|finally|augroup(?!\\s+END))\\b.*$"),
        dedentLine = Regex("^\\s*(end\\w*|else|elseif\\b.*|catch\\b.*|finally|augroup END)\\s*$"),
        wordChars = "_:#", spec = vimSpec)

    val MESON = Language("meson", "Meson", listOf(), listOf("meson.build", "meson_options.txt", "meson.options"), lineComment = "#",
        indentAfter = Regex("(^\\s*(if|elif|else|foreach)\\b.*|[\\[({])\\s*$"), dedentLine = Regex("^\\s*(endif|endforeach|else|elif\\b.*)\\s*$"),
        spec = mesonSpec)

    val ODIN = Language("odin", "Odin", ext("odin"), lineComment = "//", blockComment = "/*" to "*/", indentAfter = BRACE_INDENT,
        defaultIndent = "\t", spec = odinSpec)

    val PROGUARD = Language("proguard", "ProGuard / R8", ext("pgcfg proguard"), listOf("proguard-rules.pro", "proguard-android.txt", "proguard-android-optimize.txt", "consumer-rules.pro", "proguard.txt", "r8.txt", "proguard-project.txt"),
        fileNamePatterns = listOf(Regex("proguard.*\\.(txt|pro|cfg)"), Regex(".*-rules\\.pro")),
        lineComment = "#", autoPairs = "(){}\"\"", indentAfter = BRACE_INDENT, wordChars = "_$.*<>",
        lexerFactory = { ProguardLexer }, vocabularyFactory = { LanguageData.proguard() })

    val LOG = Language("log", "Лог", ext("log out logcat trace"), fileNamePatterns = listOf(Regex("logcat.*"), Regex(".*\\.log\\.\\d+"), Regex("dmesg.*"), Regex("tombstone_\\d+")),
        autoPairs = "", lexerFactory = { LogLexer })

    val CSV = Language("csv", "CSV", ext("csv"), autoPairs = "\"\"", lexerFactory = { CsvLexer(',') })
    val TSV = Language("tsv", "TSV", ext("tsv tab"), autoPairs = "\"\"", lexerFactory = { CsvLexer('\t') })

    val LATEX = Language("latex", "LaTeX", ext("tex sty cls ltx dtx ins bib bst"), lineComment = "%", autoPairs = "()[]{}$$",
        defaultIndent = "  ", lexerFactory = { LatexLexer })

    val IGNORE = Language("ignore", "Ignore-файл", ext("gitignore dockerignore npmignore hgignore prettierignore eslintignore stignore rgignore fdignore ignore"),
        listOf(".gitignore", ".dockerignore", ".npmignore", ".hgignore", ".prettierignore", ".eslintignore", ".stignore", ".rgignore", ".fdignore", ".ignore", ".gcloudignore", ".vscodeignore", ".slugignore", "CODEOWNERS"),
        lineComment = "#", autoPairs = "[]", lexerFactory = { IgnoreLexer })

    val all: List<Language> = listOf(
        PLAIN, SMALI, JVM_BYTECODE, JAVA, KOTLIN, GROOVY, SCALA, XML, HTML, CSS, SCSS, JSON, YAML, TOML, INI, PROPERTIES, DOTENV,
        MARKDOWN, PYTHON, PINE, JAVASCRIPT, TYPESCRIPT, QML, C, CPP, OBJC, CSHARP, GO, RUST, SWIFT, DART, LUA, RUBY, CRYSTAL,
        PHP, PERL, SHELL, FISH, POWERSHELL, BATCH, SQL, KDL, MAKEFILE, CMAKE, DOCKERFILE, NIX, HASKELL, OCAML,
        ELIXIR, ERLANG, CLOJURE, R, JULIA, ZIG, NIM, D, PASCAL, VB, ASM, GLSL, PROTOBUF, GRAPHQL, HCL, SOLIDITY,
        AWK, TCL, VIM, MESON, ODIN, PROGUARD, LOG, CSV, TSV, LATEX, DIFF, IGNORE
    )

    private val byId = all.associateBy { it.id }
    private val byFileName: Map<String, Language> = HashMap<String, Language>().apply {
        for (l in all) for (f in l.fileNames) {
            putIfAbsent(f, l)
            putIfAbsent(f.lowercase(), l)
        }
    }
    private val byExtension: Map<String, Language> = HashMap<String, Language>().apply {
        for (l in all) for (e in l.extensions) {
            // «S» (ассемблер с препроцессором) — регистр важен, остальные — нет
            putIfAbsent(e, l)
            putIfAbsent(e.lowercase(), l)
        }
    }
    private val byInterpreter: Map<String, Language> = HashMap<String, Language>().apply {
        for (l in all) for (i in l.interpreters) putIfAbsent(i, l)
    }

    fun byId(id: String): Language? = byId[id]

    /** Язык по имени файла, затем по shebang/modeline/содержимому. */
    fun detect(fileName: String, content: String? = null): Language {
        val name = fileName.substringAfterLast('/').substringAfterLast('\\')
        val lower = name.lowercase()
        byFileName[name]?.let { return refine(it, lower, content) }
        byFileName[lower]?.let { return refine(it, lower, content) }
        // Разные суффиксы, но смысл по основному расширению: *.conf.bak, *.json.tmpl
        for (suffix in listOf(".bak", ".orig", ".old", ".tmp", ".tmpl", ".in", ".template", ".example", ".sample", ".dist", ".pacnew", ".pacsave", "~")) {
            if (lower.endsWith(suffix) && lower.length > suffix.length + 1) {
                val base = name.dropLast(suffix.length)
                val inner = detect(base, content)
                if (inner !== PLAIN) return inner
            }
        }
        all.firstOrNull { l -> l.fileNamePatterns.any { it.matches(lower) } }?.let { return refine(it, lower, content) }
        // .kts / .gradle.kts — Kotlin
        if (lower.endsWith(".gradle.kts")) return KOTLIN
        val dot = name.lastIndexOf('.')
        if (dot > 0 || (dot == 0 && name.length > 1)) {
            val extension = name.substring(dot + 1)
            (byExtension[extension] ?: byExtension[extension.lowercase()])?.let { return refine(it, lower, content) }
        }
        if (content != null) sniff(content)?.let { return it }
        return PLAIN
    }

    // Уточнение неоднозначных случаев по содержимому
    private fun refine(lang: Language, lowerName: String, content: String?): Language {
        if (content == null) return lang
        return when {
            // .pro: qmake или ProGuard
            lang === MAKEFILE && lowerName.endsWith(".pro") ->
                if (content.contains("-keep") || content.contains("-dontwarn") || content.contains("-dontobfuscate")) PROGUARD else MAKEFILE
            // .inc: PHP, Pascal или ассемблер
            lowerName.endsWith(".inc") -> when {
                content.contains("<?php") -> PHP
                Regex("(?im)^\\s*(section|global|%define|db|dd)\\b").containsMatchIn(content.take(4000)) -> ASM
                else -> lang
            }
            // .m: Objective-C или MATLAB — без MATLAB остаётся Obj-C
            // .h: C++, если видно классы/пространства имён
            lang === C && lowerName.endsWith(".h") &&
                Regex("\\b(class|namespace|template|public:|private:|std::)\\b").containsMatchIn(content.take(20000)) -> CPP
            // "config" без расширения: JSON (waybar) или ini
            lowerName == "config" && content.trimStart().let { it.startsWith("{") || it.startsWith("[{") || it.startsWith("//") } -> JSON
            lowerName == "config" && sniffShebang(content) != null -> sniffShebang(content)!!
            // .conf от nginx/lua и т.п. — оставляем ini
            else -> lang
        }
    }

    private fun sniffShebang(content: String): Language? {
        if (!content.startsWith("#!")) return null
        val first = content.lineSequence().first()
        val parts = first.removePrefix("#!").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        var exe = parts[0].substringAfterLast('/')
        if (exe == "env") {
            exe = parts.drop(1).firstOrNull { !it.startsWith("-") && !it.contains('=') }?.substringAfterLast('/') ?: return null
        }
        byInterpreter[exe]?.let { return it }
        val base = exe.trimEnd { it.isDigit() || it == '.' }
        return byInterpreter[base] ?: when {
            base.startsWith("python") -> PYTHON
            base.endsWith("sh") -> SHELL
            base.startsWith("node") -> JAVASCRIPT
            base.startsWith("lua") -> LUA
            base.startsWith("perl") -> PERL
            base.startsWith("ruby") -> RUBY
            base.startsWith("php") -> PHP
            base == "make" -> MAKEFILE
            else -> null
        }
    }

    private val modeline = Regex("-\\*-.*?(?:mode:\\s*)?([\\w+-]+)\\s*(?:;.*)?-\\*-|\\bvim?:.*?\\b(?:ft|filetype|syntax)=([\\w+-]+)")

    /** Язык по содержимому файла без понятного имени. */
    fun sniff(content: String): Language? {
        sniffShebang(content)?.let { return it }
        val head = content.take(4096)
        modeline.find(head)?.let { m ->
            val mode = (m.groupValues[1].ifEmpty { m.groupValues[2] }).lowercase()
            val found = byId[mode] ?: all.firstOrNull { l -> l.extensions.contains(mode) || l.name.lowercase() == mode }
                ?: when (mode) {
                    "sh", "bash", "zsh" -> SHELL
                    "js" -> JAVASCRIPT
                    "py" -> PYTHON
                    "c++" -> CPP
                    "conf", "dosini" -> INI
                    "make" -> MAKEFILE
                    else -> null
                }
            if (found != null) return found
        }
        val t = head.trimStart('﻿', ' ', '\t', '\r', '\n')
        return when {
            t.startsWith("<?xml") -> if (t.contains("<svg") || t.contains("<manifest")) XML else XML
            t.startsWith("<!DOCTYPE html", ignoreCase = true) || t.startsWith("<html", ignoreCase = true) -> HTML
            t.startsWith("<?php") -> PHP
            // Скрипт TradingView, сохранённый как .txt
            t.startsWith("//@version=") && Regex("\\b(indicator|strategy|library)\\s*\\(").containsMatchIn(t) -> PINE
            t.startsWith("<") && Regex("^<[A-Za-z][\\w.:-]*[\\s>]").containsMatchIn(t) && t.contains("</") -> XML
            t.startsWith(".class ") -> SMALI
            t.startsWith("diff --git") || t.startsWith("--- ") && t.contains("\n+++ ") || t.startsWith("Index: ") -> DIFF
            (t.startsWith("{") || t.startsWith("[")) && looksLikeJson(t) -> JSON
            Regex("^\\[[\\w .:/-]+]\\s*$", RegexOption.MULTILINE).containsMatchIn(t.lineSequence().firstOrNull { it.isNotBlank() && !it.startsWith("#") && !it.startsWith(";") } ?: "") -> INI
            Regex("^\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\s+\\d+\\s+\\d+ [VDIWEF] ").containsMatchIn(t) -> LOG
            Regex("^-{3}\\s*$", RegexOption.MULTILINE).find(t)?.range?.first == 0 -> YAML
            else -> null
        }
    }

    private fun looksLikeJson(t: String): Boolean {
        val body = t.drop(1).trimStart()
        return body.startsWith("\"") || body.startsWith("{") || body.startsWith("[") || body.startsWith("]") ||
            body.startsWith("}") || body.firstOrNull()?.isDigit() == true || body.startsWith("//")
    }

    /** Отступ файла: табы или N пробелов (по самым частым приращениям), иначе — по языку. */
    fun detectIndent(text: String, language: Language): String {
        var tabs = 0
        var spaceLines = 0
        val deltas = IntArray(9)
        var prev = 0
        var lines = 0
        var i = 0
        val n = text.length
        while (i < n && lines < 5000) {
            val end = text.indexOf('\n', i).let { if (it < 0) n else it }
            if (end > i) {
                val c = text[i]
                if (c == '\t') tabs++
                else if (c == ' ') {
                    var k = i
                    while (k < end && text[k] == ' ') k++
                    val width = k - i
                    // Строки комментариев " * " в /** */ не считаем
                    if (k < end && text[k] != '*') {
                        spaceLines++
                        val d = width - prev
                        if (d in 2..8) deltas[d]++
                        prev = width
                    }
                } else if (!c.isWhitespace()) {
                    prev = 0
                }
            }
            lines++
            i = end + 1
        }
        if (tabs > spaceLines && tabs > 2) return "\t"
        if (spaceLines < 2) return language.defaultIndent
        val best = (2..8).maxByOrNull { deltas[it] } ?: return language.defaultIndent
        if (deltas[best] == 0) return language.defaultIndent
        // 4 часто даёт и шаги по 8, а 2 — по 4: предпочитаем меньший при сопоставимой частоте
        val unit = when {
            best == 4 && deltas[2] * 2 >= deltas[4] && deltas[2] > 0 -> 2
            best == 8 && deltas[4] * 2 >= deltas[8] && deltas[4] > 0 -> 4
            else -> best
        }
        return " ".repeat(unit)
    }

    internal fun closeDedentPattern() = CLOSE_DEDENT
}
