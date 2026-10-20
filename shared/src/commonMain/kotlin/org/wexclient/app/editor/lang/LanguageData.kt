package org.wexclient.app.editor.lang

/** Словари автодополнения: API языков, Android, smali, niri и шаблоны кода. */
object LanguageData {
    private fun vw(kind: ItemKind, s: String, detail: String = "") = words(s).map { VocabWord(it, kind, detail) }

    private fun sn(trigger: String, description: String, body: String) = Snippet(trigger, description, body.trimIndent())

    // ---------------- Smali ----------------

    val smaliDirectives = listOf(
        ".class" to "public Lcom/example/Name;", ".super" to "Ljava/lang/Object;", ".source" to "\"Name.java\"",
        ".implements" to "Ljava/lang/Runnable;", ".field" to "private name:Ljava/lang/String;", ".end field" to "",
        ".method" to "public name()V", ".end method" to "", ".registers" to "N", ".locals" to "N",
        ".param" to "p1, \"name\"", ".end param" to "", ".prologue" to "", ".line" to "N", ".local" to "v0, \"name\":Ljava/lang/String;",
        ".end local" to "v0", ".restart local" to "v0", ".annotation" to "runtime Lannotation;", ".end annotation" to "",
        ".subannotation" to "Lannotation;", ".end subannotation" to "", ".enum" to "Lenum;->NAME:Lenum;",
        ".array-data" to "4", ".end array-data" to "", ".packed-switch" to "0x0", ".end packed-switch" to "",
        ".sparse-switch" to "", ".end sparse-switch" to "", ".catch" to "Ljava/lang/Exception; {:try_start_0 .. :try_end_0} :catch_0",
        ".catchall" to "{:try_start_0 .. :try_end_0} :catchall_0", ".epilogue" to ""
    )

    /** Подсказка формата по имени инструкции. */
    fun smaliOpcodeHint(op: String): String = when {
        op.startsWith("invoke-") && op.endsWith("/range") -> "{v0 .. v2}, Lclass;->method()V"
        op.startsWith("invoke-polymorphic") -> "{v0, v1}, Lclass;->m()V, ()V"
        op.startsWith("invoke-custom") -> "{v0}, call_site_0(...)"
        op.startsWith("invoke-") -> "{v0, v1}, Lclass;->method(I)V"
        op.startsWith("iget") -> "vA, vObj, Lclass;->field:I"
        op.startsWith("iput") -> "vA, vObj, Lclass;->field:I"
        op.startsWith("sget") || op.startsWith("sput") -> "vA, Lclass;->FIELD:I"
        op == "const-string" || op == "const-string/jumbo" -> "vA, \"text\""
        op == "const-class" || op == "check-cast" || op == "new-instance" -> "vA, Lclass;"
        op == "instance-of" -> "vA, vB, Lclass;"
        op == "new-array" -> "vA, vSize, [I"
        op == "filled-new-array" -> "{v0, v1}, [I"
        op == "const/4" -> "vA, 0x1"
        op == "const/16" || op == "const" || op == "const/high16" -> "vA, 0x10"
        op.startsWith("const-wide") -> "vA, 0x0L"
        op.startsWith("move-result") || op == "move-exception" || op.startsWith("return") && op != "return-void" -> "vA"
        op == "return-void" || op == "nop" -> ""
        op.startsWith("move") -> "vA, vB"
        op.startsWith("if-") && op.endsWith("z") -> "vA, :cond_0"
        op.startsWith("if-") -> "vA, vB, :cond_0"
        op.startsWith("goto") -> ":goto_0"
        op == "packed-switch" || op == "sparse-switch" -> "vA, :pswitch_data_0"
        op == "fill-array-data" -> "vA, :array_0"
        op.endsWith("/lit8") || op.endsWith("/lit16") -> "vA, vB, 0x1"
        op.endsWith("/2addr") -> "vA, vB"
        op.startsWith("aget") || op.startsWith("aput") -> "vA, vArray, vIndex"
        op == "array-length" -> "vA, vArray"
        op.startsWith("cmp") || op.contains("-int") || op.contains("-long") || op.contains("-float") || op.contains("-double") -> "vA, vB, vC"
        op == "throw" || op.startsWith("monitor") -> "vA"
        else -> ""
    }

    private val smaliTypes = listOf(
        "Ljava/lang/String;", "Ljava/lang/Object;", "Ljava/lang/Class;", "Ljava/lang/Integer;", "Ljava/lang/Boolean;",
        "Ljava/lang/Long;", "Ljava/lang/CharSequence;", "Ljava/lang/StringBuilder;", "Ljava/lang/System;",
        "Ljava/lang/Exception;", "Ljava/lang/Throwable;", "Ljava/lang/RuntimeException;", "Ljava/lang/Thread;",
        "Ljava/lang/Runnable;", "Ljava/lang/Math;", "Ljava/lang/reflect/Method;", "Ljava/lang/reflect/Field;",
        "Ljava/util/List;", "Ljava/util/ArrayList;", "Ljava/util/Map;", "Ljava/util/HashMap;", "Ljava/util/Set;",
        "Ljava/util/HashSet;", "Ljava/util/Iterator;", "Ljava/util/Arrays;", "Ljava/util/Locale;", "Ljava/io/File;",
        "Ljava/io/InputStream;", "Ljava/io/OutputStream;", "Ljava/net/URL;", "Landroid/content/Context;",
        "Landroid/app/Activity;", "Landroid/app/Application;", "Landroid/app/Service;", "Landroid/os/Bundle;",
        "Landroid/os/Build;", "Landroid/os/Build\$VERSION;", "Landroid/os/Handler;", "Landroid/os/Looper;",
        "Landroid/util/Log;", "Landroid/widget/Toast;", "Landroid/widget/TextView;", "Landroid/widget/Button;",
        "Landroid/view/View;", "Landroid/view/ViewGroup;", "Landroid/view/LayoutInflater;", "Landroid/content/Intent;",
        "Landroid/content/SharedPreferences;", "Landroid/content/SharedPreferences\$Editor;",
        "Landroid/content/pm/PackageManager;", "Landroid/content/pm/PackageInfo;", "Landroid/content/pm/Signature;",
        "Landroid/content/res/Resources;", "Landroid/net/Uri;", "Landroid/provider/Settings\$Secure;",
        "Landroid/webkit/WebView;", "Landroid/app/AlertDialog\$Builder;", "Landroid/graphics/Bitmap;",
        "Landroidx/appcompat/app/AppCompatActivity;", "Lkotlin/jvm/internal/Intrinsics;", "Lkotlin/Unit;"
    )

    private val smaliMembers = listOf(
        "<init>()V" to "конструктор", "<clinit>()V" to "статический инициализатор",
        "toString()Ljava/lang/String;" to "", "equals(Ljava/lang/Object;)Z" to "", "hashCode()I" to "",
        "d(Ljava/lang/String;Ljava/lang/String;)I" to "Log.d", "e(Ljava/lang/String;Ljava/lang/String;)I" to "Log.e",
        "i(Ljava/lang/String;Ljava/lang/String;)I" to "Log.i",
        "makeText(Landroid/content/Context;Ljava/lang/CharSequence;I)Landroid/widget/Toast;" to "Toast.makeText",
        "show()V" to "", "valueOf(Ljava/lang/Object;)Ljava/lang/String;" to "String.valueOf",
        "valueOf(I)Ljava/lang/Integer;" to "Integer.valueOf", "valueOf(Z)Ljava/lang/Boolean;" to "Boolean.valueOf",
        "booleanValue()Z" to "", "intValue()I" to "", "longValue()J" to "",
        "append(Ljava/lang/String;)Ljava/lang/StringBuilder;" to "StringBuilder.append",
        "append(I)Ljava/lang/StringBuilder;" to "", "length()I" to "", "isEmpty()Z" to "",
        "equals(Ljava/lang/Object;)Z" to "", "getApplicationContext()Landroid/content/Context;" to "",
        "getPackageName()Ljava/lang/String;" to "", "getPackageManager()Landroid/content/pm/PackageManager;" to "",
        "startActivity(Landroid/content/Intent;)V" to "", "finish()V" to "",
        "onCreate(Landroid/os/Bundle;)V" to "", "setContentView(I)V" to "",
        "findViewById(I)Landroid/view/View;" to "", "setText(Ljava/lang/CharSequence;)V" to "",
        "setVisibility(I)V" to "", "setOnClickListener(Landroid/view/View\$OnClickListener;)V" to "",
        "getString(I)Ljava/lang/String;" to "", "getSharedPreferences(Ljava/lang/String;I)Landroid/content/SharedPreferences;" to "",
        "getBoolean(Ljava/lang/String;Z)Z" to "", "putBoolean(Ljava/lang/String;Z)Landroid/content/SharedPreferences\$Editor;" to "",
        "apply()V" to "", "commit()Z" to "", "currentTimeMillis()J" to "System.currentTimeMillis",
        "getClass()Ljava/lang/Class;" to "", "getName()Ljava/lang/String;" to "", "forName(Ljava/lang/String;)Ljava/lang/Class;" to "",
        "getDeclaredMethod(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;" to "",
        "invoke(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;" to "", "setAccessible(Z)V" to "",
        "printStackTrace()V" to "", "getMessage()Ljava/lang/String;" to "",
        "checkNotNullParameter(Ljava/lang/Object;Ljava/lang/String;)V" to "Intrinsics",
        "areEqual(Ljava/lang/Object;Ljava/lang/Object;)Z" to "Intrinsics"
    )

    fun smali(): Vocabulary {
        val list = ArrayList<VocabWord>()
        for ((d, hint) in smaliDirectives) list.add(VocabWord(d, ItemKind.DIRECTIVE, hint))
        for (op in SmaliLexer.opcodes.sorted()) list.add(VocabWord(op, ItemKind.OPCODE, smaliOpcodeHint(op)))
        for (f in SmaliLexer.accessFlags) list.add(VocabWord(f, ItemKind.KEYWORD))
        for (t in smaliTypes) list.add(VocabWord(t, ItemKind.TYPE))
        for ((m, d) in smaliMembers) list.add(VocabWord(m, ItemKind.FUNCTION, d))
        for (p in listOf("V", "Z", "B", "S", "C", "I", "J", "F", "D")) list.add(VocabWord(p, ItemKind.TYPE, smaliPrimitive(p)))
        for (k in listOf("true", "false", "null")) list.add(VocabWord(k, ItemKind.CONSTANT))
        return Vocabulary(
            list,
            listOf(
                sn("method", "Новый метод", """
                    .method public ${'$'}{1:name}(${'$'}{2})${'$'}{3:V}
                        .locals ${'$'}{4:0}
                        ${'$'}0
                        return-void
                    .end method
                """),
                sn("smethod", "Статический метод", """
                    .method public static ${'$'}{1:name}(${'$'}{2})${'$'}{3:V}
                        .locals ${'$'}{4:0}
                        ${'$'}0
                        return-void
                    .end method
                """),
                sn("field", "Поле", ".field private ${'$'}{1:name}:${'$'}{2:Ljava/lang/String;}"),
                sn("rtrue", "Вернуть true", """
                    const/4 ${'$'}{1:v0}, 0x1
                    return ${'$'}{1:v0}
                """),
                sn("rfalse", "Вернуть false", """
                    const/4 ${'$'}{1:v0}, 0x0
                    return ${'$'}{1:v0}
                """),
                sn("rvoid", "return-void", "return-void"),
                sn("log", "Log.d(\"WEX\", текст)", """
                    const-string ${'$'}{1:v0}, "${'$'}{2:WEX}"
                    const-string ${'$'}{3:v1}, "${'$'}{4:message}"
                    invoke-static {${'$'}{1:v0}, ${'$'}{3:v1}}, Landroid/util/Log;->d(Ljava/lang/String;Ljava/lang/String;)I
                    ${'$'}0
                """),
                sn("logv", "Log.d значения регистра (String)", """
                    const-string ${'$'}{1:v0}, "${'$'}{2:WEX}"
                    invoke-static {${'$'}{1:v0}, ${'$'}{3:p1}}, Landroid/util/Log;->d(Ljava/lang/String;Ljava/lang/String;)I
                """),
                sn("toast", "Toast с текстом (p0 — Context)", """
                    const-string ${'$'}{1:v0}, "${'$'}{2:Hello}"
                    const/4 ${'$'}{3:v1}, 0x0
                    invoke-static {${'$'}{4:p0}, ${'$'}{1:v0}, ${'$'}{3:v1}}, Landroid/widget/Toast;->makeText(Landroid/content/Context;Ljava/lang/CharSequence;I)Landroid/widget/Toast;
                    move-result-object ${'$'}{1:v0}
                    invoke-virtual {${'$'}{1:v0}}, Landroid/widget/Toast;->show()V
                    ${'$'}0
                """),
                sn("trycatch", "try/catch", """
                    :try_start_${'$'}{1:0}
                    ${'$'}0
                    :try_end_${'$'}{1:0}
                    .catch Ljava/lang/Exception; {:try_start_${'$'}{1:0} .. :try_end_${'$'}{1:0}} :catch_${'$'}{1:0}
                """),
                sn("ifeqz", "if-eqz + метка", """
                    if-eqz ${'$'}{1:v0}, :cond_${'$'}{2:0}
                    ${'$'}0
                    :cond_${'$'}{2:0}
                """),
                sn("sb", "StringBuilder: a + b", """
                    new-instance ${'$'}{1:v0}, Ljava/lang/StringBuilder;
                    invoke-direct {${'$'}{1:v0}}, Ljava/lang/StringBuilder;-><init>()V
                    invoke-virtual {${'$'}{1:v0}, ${'$'}{2:v1}}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                    invoke-virtual {${'$'}{1:v0}}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
                    move-result-object ${'$'}{1:v0}
                """),
                sn("super", "Вызов super.<init>", "invoke-direct {p0}, ${'$'}{1:Ljava/lang/Object;}-><init>()V"),
                sn("annot", "Аннотация", """
                    .annotation ${'$'}{1:runtime} ${'$'}{2:Lannotation;}
                        ${'$'}0
                    .end annotation
                """)
            )
        )
    }

    private fun smaliPrimitive(p: String) = when (p) {
        "V" -> "void"; "Z" -> "boolean"; "B" -> "byte"; "S" -> "short"; "C" -> "char"
        "I" -> "int"; "J" -> "long"; "F" -> "float"; else -> "double"
    }

    // ---------------- Android XML ----------------

    val androidLayoutTags = words(
        "LinearLayout RelativeLayout FrameLayout ConstraintLayout androidx.constraintlayout.widget.ConstraintLayout " +
            "androidx.coordinatorlayout.widget.CoordinatorLayout androidx.recyclerview.widget.RecyclerView " +
            "androidx.cardview.widget.CardView com.google.android.material.card.MaterialCardView " +
            "com.google.android.material.button.MaterialButton com.google.android.material.appbar.AppBarLayout " +
            "com.google.android.material.floatingactionbutton.FloatingActionButton " +
            "com.google.android.material.textfield.TextInputLayout com.google.android.material.textfield.TextInputEditText " +
            "androidx.appcompat.widget.Toolbar androidx.viewpager2.widget.ViewPager2 androidx.core.widget.NestedScrollView " +
            "TextView EditText Button ImageButton ImageView CheckBox RadioButton RadioGroup Switch ToggleButton " +
            "ProgressBar SeekBar RatingBar Spinner ScrollView HorizontalScrollView ListView GridView WebView " +
            "VideoView TableLayout TableRow Space View ViewStub include merge fragment androidx.fragment.app.FragmentContainerView"
    )

    val manifestTags = words(
        "manifest application activity activity-alias service receiver provider uses-permission permission " +
            "permission-group uses-feature uses-sdk uses-library uses-native-library intent-filter action category data " +
            "meta-data queries package intent grant-uri-permission path-permission instrumentation supports-screens " +
            "compatible-screens profileable property layout"
    )

    val resourceTags = words(
        "resources string string-array plurals item color dimen bool integer integer-array array style attr " +
            "declare-styleable public eat-comment drawable fraction enum flag selector shape solid stroke corners " +
            "gradient padding size layer-list vector path group clip-path animated-vector set objectAnimator " +
            "alpha scale translate rotate menu adaptive-icon background foreground monochrome network-security-config " +
            "domain-config domain trust-anchors certificates base-config paths external-path files-path cache-path " +
            "root-path external-files-path xliff:g"
    )

    private val androidAttrs = words(
        "id layout_width layout_height layout_weight layout_gravity layout_margin layout_marginTop layout_marginBottom " +
            "layout_marginStart layout_marginEnd layout_marginLeft layout_marginRight layout_marginHorizontal " +
            "layout_marginVertical padding paddingTop paddingBottom paddingStart paddingEnd paddingLeft paddingRight " +
            "paddingHorizontal paddingVertical orientation gravity background backgroundTint foreground text textSize " +
            "textColor textColorHint textStyle textAlignment textAllCaps textIsSelectable fontFamily hint inputType " +
            "maxLines minLines lines singleLine ellipsize maxLength lineSpacingExtra letterSpacing drawableStart " +
            "drawableEnd drawableTop drawableBottom drawablePadding src srcCompat scaleType adjustViewBounds tint " +
            "contentDescription visibility alpha elevation clickable focusable focusableInTouchMode enabled checked " +
            "onClick minWidth minHeight maxWidth maxHeight layout_below layout_above layout_toStartOf layout_toEndOf " +
            "layout_alignParentTop layout_alignParentBottom layout_alignParentStart layout_alignParentEnd " +
            "layout_centerInParent layout_centerHorizontal layout_centerVertical weightSum divider showDividers " +
            "scrollbars fillViewport clipToPadding clipChildren fitsSystemWindows theme style tag rotation " +
            "translationX translationY scaleX scaleY transitionName stateListAnimator importantForAccessibility " +
            "name label icon roundIcon exported enabled permission process taskAffinity launchMode screenOrientation " +
            "configChanges windowSoftInputMode excludeFromRecents noHistory allowBackup fullBackupContent " +
            "dataExtractionRules supportsRtl usesCleartextTraffic networkSecurityConfig requestLegacyExternalStorage " +
            "extractNativeLibs debuggable largeHeap hardwareAccelerated appComponentFactory authorities " +
            "grantUriPermissions resource value scheme host port path pathPrefix pathPattern mimeType priority " +
            "minSdkVersion targetSdkVersion maxSdkVersion required versionCode versionName compileSdkVersion " +
            "compileSdkVersionCodename installLocation sharedUserId foregroundServiceType directBootAware " +
            "stopWithTask isolatedProcess autoVerify parentActivityName resizeableActivity supportsPictureInPicture " +
            "hasFragileUserData localeConfig enableOnBackInvokedCallback pathData fillColor strokeColor strokeWidth " +
            "viewportWidth viewportHeight width height color radius shape angle startColor endColor centerColor " +
            "drawable state_pressed state_checked state_enabled state_focused state_selected duration interpolator " +
            "fromAlpha toAlpha showAsAction title orderInCategory checkable iconTint"
    )

    private val appAttrs = words(
        "layout_constraintTop_toTopOf layout_constraintTop_toBottomOf layout_constraintBottom_toBottomOf " +
            "layout_constraintBottom_toTopOf layout_constraintStart_toStartOf layout_constraintStart_toEndOf " +
            "layout_constraintEnd_toEndOf layout_constraintEnd_toStartOf layout_constraintHorizontal_bias " +
            "layout_constraintVertical_bias layout_constraintDimensionRatio layout_constraintWidth_percent " +
            "layout_constraintHorizontal_chainStyle layout_constraintGuide_percent layout_goneMarginTop " +
            "layout_behavior layout_scrollFlags cardCornerRadius cardElevation cardBackgroundColor contentPadding " +
            "srcCompat tint backgroundTint icon iconTint iconGravity cornerRadius strokeColor strokeWidth " +
            "navigationIcon title titleTextColor menu layoutManager spanCount itemIconTint itemTextColor " +
            "boxBackgroundMode hintEnabled endIconMode startIconDrawable fabSize showAsAction actionViewClass " +
            "argType nullable destination startDestination navGraph defaultNavHost"
    )

    private val toolsAttrs = words("context ignore targetApi text visibility listitem layout showIn itemCount replace node remove keep discard locale")

    val permissions = words(
        "INTERNET ACCESS_NETWORK_STATE ACCESS_WIFI_STATE CHANGE_WIFI_STATE READ_EXTERNAL_STORAGE " +
            "WRITE_EXTERNAL_STORAGE MANAGE_EXTERNAL_STORAGE READ_MEDIA_IMAGES READ_MEDIA_VIDEO READ_MEDIA_AUDIO " +
            "READ_MEDIA_VISUAL_USER_SELECTED CAMERA RECORD_AUDIO ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION " +
            "ACCESS_BACKGROUND_LOCATION READ_CONTACTS WRITE_CONTACTS GET_ACCOUNTS READ_PHONE_STATE " +
            "READ_PHONE_NUMBERS CALL_PHONE READ_CALL_LOG WRITE_CALL_LOG READ_SMS SEND_SMS RECEIVE_SMS " +
            "RECEIVE_MMS READ_CALENDAR WRITE_CALENDAR BODY_SENSORS ACTIVITY_RECOGNITION BLUETOOTH " +
            "BLUETOOTH_ADMIN BLUETOOTH_CONNECT BLUETOOTH_SCAN BLUETOOTH_ADVERTISE NEARBY_WIFI_DEVICES NFC " +
            "VIBRATE WAKE_LOCK RECEIVE_BOOT_COMPLETED FOREGROUND_SERVICE FOREGROUND_SERVICE_DATA_SYNC " +
            "FOREGROUND_SERVICE_MEDIA_PLAYBACK FOREGROUND_SERVICE_LOCATION FOREGROUND_SERVICE_SPECIAL_USE " +
            "POST_NOTIFICATIONS SYSTEM_ALERT_WINDOW REQUEST_INSTALL_PACKAGES REQUEST_DELETE_PACKAGES " +
            "QUERY_ALL_PACKAGES SCHEDULE_EXACT_ALARM USE_EXACT_ALARM USE_BIOMETRIC USE_FINGERPRINT " +
            "REQUEST_IGNORE_BATTERY_OPTIMIZATIONS PACKAGE_USAGE_STATS BIND_ACCESSIBILITY_SERVICE " +
            "BIND_NOTIFICATION_LISTENER_SERVICE BIND_VPN_SERVICE BIND_DEVICE_ADMIN WRITE_SETTINGS " +
            "WRITE_SECURE_SETTINGS READ_LOGS INSTALL_PACKAGES DELETE_PACKAGES CHANGE_NETWORK_STATE " +
            "MODIFY_AUDIO_SETTINGS ACCESS_NOTIFICATION_POLICY USE_FULL_SCREEN_INTENT GET_TASKS KILL_BACKGROUND_PROCESSES " +
            "EXPAND_STATUS_BAR SET_WALLPAPER DISABLE_KEYGUARD READ_PRIVILEGED_PHONE_STATE INTERACT_ACROSS_USERS"
    ).map { "android.permission.$it" }

    val intentActions = words(
        "MAIN VIEW EDIT SEND SEND_MULTIPLE SENDTO PICK GET_CONTENT OPEN_DOCUMENT CREATE_DOCUMENT DIAL CALL " +
            "BOOT_COMPLETED LOCKED_BOOT_COMPLETED MY_PACKAGE_REPLACED PACKAGE_ADDED PACKAGE_REMOVED " +
            "PACKAGE_REPLACED SCREEN_ON SCREEN_OFF USER_PRESENT BATTERY_CHANGED BATTERY_LOW POWER_CONNECTED " +
            "POWER_DISCONNECTED AIRPLANE_MODE TIME_SET TIMEZONE_CHANGED LOCALE_CHANGED CONFIGURATION_CHANGED " +
            "SEARCH WEB_SEARCH ASSIST APPLICATION_PREFERENCES PROCESS_TEXT QUICK_SETTINGS_TILE_PREFERENCES"
    ).map { "android.intent.action.$it" }

    val intentCategories = words("LAUNCHER DEFAULT BROWSABLE HOME LEANBACK_LAUNCHER INFO ALTERNATIVE SELECTED_ALTERNATIVE OPENABLE MONKEY APP_BROWSER")
        .map { "android.intent.category.$it" }

    /** Значения атрибутов по имени атрибута (без префикса). */
    fun attributeValues(attr: String, tag: String): List<String> = when (attr) {
        "layout_width", "layout_height", "width", "height" -> listOf("match_parent", "wrap_content", "0dp")
        "visibility" -> listOf("visible", "invisible", "gone")
        "orientation" -> listOf("vertical", "horizontal")
        "gravity", "layout_gravity", "foregroundGravity", "iconGravity" -> listOf(
            "center", "center_vertical", "center_horizontal", "top", "bottom", "start", "end", "left", "right",
            "fill", "fill_horizontal", "fill_vertical", "center|start", "center_vertical|end"
        )
        "inputType" -> listOf(
            "text", "textPassword", "textVisiblePassword", "textEmailAddress", "textUri", "textMultiLine",
            "textCapSentences", "textCapWords", "textNoSuggestions", "number", "numberDecimal", "numberSigned",
            "numberPassword", "phone", "datetime", "date", "time", "none"
        )
        "textStyle" -> listOf("normal", "bold", "italic", "bold|italic")
        "textAlignment" -> listOf("center", "textStart", "textEnd", "viewStart", "viewEnd", "gravity", "inherit")
        "ellipsize" -> listOf("end", "start", "middle", "marquee", "none")
        "scaleType" -> listOf("centerCrop", "centerInside", "fitCenter", "fitXY", "fitStart", "fitEnd", "center", "matrix")
        "launchMode" -> listOf("standard", "singleTop", "singleTask", "singleInstance", "singleInstancePerTask")
        "screenOrientation" -> listOf(
            "unspecified", "portrait", "landscape", "fullSensor", "sensor", "nosensor", "locked", "reversePortrait",
            "reverseLandscape", "sensorPortrait", "sensorLandscape", "fullUser", "user", "behind"
        )
        "windowSoftInputMode" -> listOf("adjustResize", "adjustPan", "adjustNothing", "stateHidden", "stateVisible", "stateAlwaysHidden", "adjustResize|stateHidden")
        "configChanges" -> listOf("orientation|screenSize", "orientation|screenSize|keyboardHidden", "uiMode", "locale", "keyboard|keyboardHidden", "screenLayout|smallestScreenSize")
        "installLocation" -> listOf("auto", "internalOnly", "preferExternal")
        "showAsAction" -> listOf("ifRoom", "always", "never", "withText", "collapseActionView")
        "foregroundServiceType" -> listOf("dataSync", "mediaPlayback", "phoneCall", "location", "connectedDevice", "mediaProjection", "camera", "microphone", "health", "remoteMessaging", "shortService", "specialUse", "systemExempted")
        "scrollbars" -> listOf("none", "vertical", "horizontal")
        "showDividers" -> listOf("none", "beginning", "middle", "end")
        "shape" -> listOf("rectangle", "oval", "line", "ring")
        "layout_behavior" -> listOf("@string/appbar_scrolling_view_behavior", "com.google.android.material.behavior.HideBottomViewOnScrollBehavior")
        "layout_scrollFlags" -> listOf("scroll|enterAlways", "scroll|exitUntilCollapsed", "scroll|snap", "noScroll")
        "name" -> when (tag) {
            "uses-permission", "permission", "uses-permission-sdk-23" -> permissions
            "action" -> intentActions
            "category" -> intentCategories
            "uses-feature" -> listOf(
                "android.hardware.camera", "android.hardware.camera.autofocus", "android.hardware.bluetooth_le",
                "android.hardware.location.gps", "android.hardware.nfc", "android.hardware.touchscreen",
                "android.software.leanback", "android.hardware.telephony", "android.hardware.wifi"
            )
            "meta-data" -> listOf("com.google.android.gms.version", "android.app.lib_name", "android.support.FILE_PROVIDER_PATHS", "xposedmodule", "xposeddescription", "xposedminversion", "xposedscope")
            else -> emptyList()
        }
        "scheme" -> listOf("http", "https", "content", "file", "geo", "tel", "mailto", "market", "intent")
        "mimeType" -> listOf("*/*", "text/plain", "image/*", "video/*", "audio/*", "application/pdf", "application/vnd.android.package-archive", "application/zip", "application/json")
        "resource" -> listOf("@xml/file_paths", "@xml/network_security_config")
        "ignore" -> listOf("MissingTranslation", "UnusedResources", "ContentDescription", "HardcodedText", "ProtectedPermissions", "QueryAllPackagesPermission", "ScopedStorage")
        else -> emptyList()
    }

    val resourcePrefixes = listOf(
        "@string/", "@color/", "@drawable/", "@mipmap/", "@dimen/", "@style/", "@layout/", "@id/", "@+id/",
        "@xml/", "@anim/", "@animator/", "@raw/", "@font/", "@menu/", "@array/", "@bool/", "@integer/",
        "@navigation/", "@android:color/", "@android:drawable/", "@android:style/", "@android:string/",
        "@android:id/", "@null", "?attr/", "?android:attr/"
    )

    val themeAttrs = words(
        "colorPrimary colorPrimaryDark colorPrimaryVariant colorOnPrimary colorSecondary colorOnSecondary " +
            "colorAccent colorSurface colorOnSurface colorBackground colorError colorControlNormal " +
            "colorControlActivated colorControlHighlight selectableItemBackground selectableItemBackgroundBorderless " +
            "actionBarSize textAppearanceBodyLarge textAppearanceBodyMedium textAppearanceTitleLarge " +
            "textAppearanceHeadlineSmall textColorPrimary textColorSecondary dividerHorizontal listPreferredItemHeight"
    )

    fun androidXml(): Vocabulary {
        val list = ArrayList<VocabWord>()
        for (t in manifestTags) list.add(VocabWord(t, ItemKind.TAG, "manifest"))
        for (t in androidLayoutTags) list.add(VocabWord(t, ItemKind.TAG, "layout"))
        for (t in resourceTags) list.add(VocabWord(t, ItemKind.TAG, "res"))
        for (a in androidAttrs) list.add(VocabWord("android:$a", ItemKind.ATTRIBUTE))
        for (a in appAttrs) list.add(VocabWord("app:$a", ItemKind.ATTRIBUTE))
        for (a in toolsAttrs) list.add(VocabWord("tools:$a", ItemKind.ATTRIBUTE))
        for (a in listOf(
            "xmlns:android=\"http://schemas.android.com/apk/res/android\"",
            "xmlns:app=\"http://schemas.android.com/apk/res-auto\"",
            "xmlns:tools=\"http://schemas.android.com/tools\"",
            "package", "name", "type", "format", "parent", "translatable", "formatted", "quantity", "value", "id", "version", "encoding"
        )) list.add(VocabWord(a, ItemKind.ATTRIBUTE))
        return Vocabulary(
            list,
            listOf(
                sn("perm", "uses-permission", "<uses-permission android:name=\"android.permission.${'$'}{1:INTERNET}\" />"),
                sn("activity", "activity", """
                    <activity
                        android:name="${'$'}{1:.MainActivity}"
                        android:exported="${'$'}{2:false}" />
                """),
                sn("launcher", "Активность-лаунчер", """
                    <activity
                        android:name="${'$'}{1:.MainActivity}"
                        android:exported="true">
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN" />
                            <category android:name="android.intent.category.LAUNCHER" />
                        </intent-filter>
                    </activity>
                """),
                sn("service", "service", "<service\n    android:name=\"${'$'}{1:.MyService}\"\n    android:exported=\"${'$'}{2:false}\" />"),
                sn("receiver", "receiver + intent-filter", """
                    <receiver
                        android:name="${'$'}{1:.BootReceiver}"
                        android:exported="true">
                        <intent-filter>
                            <action android:name="android.intent.action.${'$'}{2:BOOT_COMPLETED}" />
                        </intent-filter>
                    </receiver>
                """),
                sn("provider", "FileProvider", """
                    <provider
                        android:name="androidx.core.content.FileProvider"
                        android:authorities="${'$'}{1:${'$'}{applicationId}}.fileprovider"
                        android:exported="false"
                        android:grantUriPermissions="true">
                        <meta-data
                            android:name="android.support.FILE_PROVIDER_PATHS"
                            android:resource="@xml/${'$'}{2:file_paths}" />
                    </provider>
                """),
                sn("meta", "meta-data", "<meta-data\n    android:name=\"${'$'}{1:name}\"\n    android:value=\"${'$'}{2:value}\" />"),
                sn("str", "string", "<string name=\"${'$'}{1:name}\">${'$'}{2:text}</string>"),
                sn("color", "color", "<color name=\"${'$'}{1:name}\">${'$'}{2:#FF000000}</color>"),
                sn("tv", "TextView", """
                    <TextView
                        android:id="@+id/${'$'}{1:text}"
                        android:layout_width="${'$'}{2:wrap_content}"
                        android:layout_height="wrap_content"
                        android:text="${'$'}{3:@string/app_name}" />
                """),
                sn("btn", "Button", """
                    <Button
                        android:id="@+id/${'$'}{1:button}"
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:text="${'$'}{2:OK}" />
                """),
                sn("ll", "LinearLayout", """
                    <LinearLayout
                        android:layout_width="match_parent"
                        android:layout_height="${'$'}{1:wrap_content}"
                        android:orientation="${'$'}{2:vertical}">
                        ${'$'}0
                    </LinearLayout>
                """),
                sn("iv", "ImageView", """
                    <ImageView
                        android:id="@+id/${'$'}{1:image}"
                        android:layout_width="${'$'}{2:48dp}"
                        android:layout_height="${'$'}{3:48dp}"
                        android:src="@drawable/${'$'}{4:icon}" />
                """),
                sn("xml", "XML-заголовок", "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n${'$'}0"),
                sn("cdata", "CDATA", "<![CDATA[${'$'}0]]>"),
                sn("comment", "Комментарий", "<!-- ${'$'}0 -->")
            )
        )
    }

    // ---------------- Веб ----------------

    val htmlTags = words(
        "html head body title meta link style script noscript base div span p a img br hr h1 h2 h3 h4 h5 h6 " +
            "ul ol li dl dt dd table thead tbody tfoot tr th td caption colgroup col form input button select option " +
            "optgroup textarea label fieldset legend datalist output progress meter details summary dialog nav " +
            "header footer main section article aside figure figcaption address blockquote pre code kbd samp var " +
            "strong em b i u s small mark sub sup abbr cite q time data del ins video audio source track picture " +
            "canvas svg iframe embed object template slot"
    )

    private val htmlAttrs = words(
        "id class style title lang dir hidden tabindex href src alt type name value placeholder rel target width " +
            "height onclick onchange oninput onsubmit onload disabled checked readonly required autofocus " +
            "autocomplete action method enctype for charset content http-equiv async defer crossorigin integrity " +
            "loading decoding srcset sizes media download role aria-label aria-hidden aria-expanded data- " +
            "colspan rowspan min max step pattern multiple accept controls autoplay loop muted poster preload " +
            "contenteditable draggable spellcheck"
    )

    fun html(): Vocabulary {
        val list = ArrayList<VocabWord>()
        for (t in htmlTags) list.add(VocabWord(t, ItemKind.TAG))
        for (a in htmlAttrs) list.add(VocabWord(a, ItemKind.ATTRIBUTE))
        return Vocabulary(
            list,
            listOf(
                sn("html5", "Каркас HTML5", """
                    <!DOCTYPE html>
                    <html lang="${'$'}{1:ru}">
                    <head>
                        <meta charset="UTF-8">
                        <meta name="viewport" content="width=device-width, initial-scale=1.0">
                        <title>${'$'}{2:Документ}</title>
                    </head>
                    <body>
                        ${'$'}0
                    </body>
                    </html>
                """),
                sn("div", "div.class", "<div class=\"${'$'}{1}\">${'$'}0</div>"),
                sn("a", "Ссылка", "<a href=\"${'$'}{1:#}\">${'$'}{2:текст}</a>"),
                sn("img", "Картинка", "<img src=\"${'$'}{1}\" alt=\"${'$'}{2}\">"),
                sn("script", "Скрипт", "<script src=\"${'$'}{1}\"></script>"),
                sn("style", "Стили", "<style>\n    ${'$'}0\n</style>"),
                sn("link", "CSS-файл", "<link rel=\"stylesheet\" href=\"${'$'}{1:style.css}\">"),
                sn("ul", "Список", "<ul>\n    <li>${'$'}0</li>\n</ul>"),
                sn("table", "Таблица", "<table>\n    <tr>\n        <th>${'$'}1</th>\n    </tr>\n    <tr>\n        <td>${'$'}0</td>\n    </tr>\n</table>"),
                sn("form", "Форма", "<form action=\"${'$'}{1}\" method=\"${'$'}{2:post}\">\n    ${'$'}0\n</form>")
            )
        )
    }

    val cssProperties = words(
        "display position top right bottom left z-index float clear overflow overflow-x overflow-y visibility opacity " +
            "width height min-width min-height max-width max-height margin margin-top margin-right margin-bottom " +
            "margin-left padding padding-top padding-right padding-bottom padding-left box-sizing border border-top " +
            "border-right border-bottom border-left border-width border-style border-color border-radius outline " +
            "background background-color background-image background-size background-position background-repeat " +
            "color font font-family font-size font-weight font-style line-height letter-spacing text-align " +
            "text-decoration text-transform text-overflow white-space word-break word-wrap vertical-align " +
            "flex flex-direction flex-wrap flex-grow flex-shrink flex-basis justify-content align-items align-content " +
            "align-self order gap row-gap column-gap grid grid-template-columns grid-template-rows grid-area " +
            "grid-column grid-row place-items place-content transform transform-origin transition animation " +
            "animation-name animation-duration box-shadow text-shadow cursor pointer-events user-select content " +
            "list-style filter backdrop-filter object-fit aspect-ratio inset resize scroll-behavior will-change " +
            "border-collapse table-layout fill stroke spacing children-border-radius"
    )

    private val cssValues = words(
        "none auto inherit initial unset block inline inline-block flex inline-flex grid inline-grid contents " +
            "absolute relative fixed sticky static center left right top bottom start end space-between " +
            "space-around space-evenly stretch baseline wrap nowrap row column row-reverse column-reverse bold normal " +
            "italic solid dashed dotted double hidden visible scroll pointer default text move not-allowed " +
            "transparent currentColor uppercase lowercase capitalize underline ellipsis break-word cover contain " +
            "repeat no-repeat ease ease-in ease-out ease-in-out linear infinite forwards border-box content-box"
    )

    fun css(): Vocabulary {
        val list = ArrayList<VocabWord>()
        for (p in cssProperties) list.add(VocabWord(p, ItemKind.PROPERTY))
        for (v in cssValues) list.add(VocabWord(v, ItemKind.VALUE))
        for (f in words("rgb rgba hsl hsla var calc min max clamp url linear-gradient radial-gradient translate translateX translateY rotate scale blur")) {
            list.add(VocabWord("$f()", ItemKind.FUNCTION))
        }
        for (a in words("@media @import @keyframes @font-face @supports @layer @container @charset @property")) list.add(VocabWord(a, ItemKind.DIRECTIVE))
        return Vocabulary(
            list,
            listOf(
                sn("media", "@media", "@media (${'$'}{1:max-width}: ${'$'}{2:600px}) {\n    ${'$'}0\n}"),
                sn("flexc", "flex по центру", "display: flex;\nalign-items: center;\njustify-content: center;"),
                sn("grid", "grid", "display: grid;\ngrid-template-columns: ${'$'}{1:repeat(3, 1fr)};\ngap: ${'$'}{2:8px};"),
                sn("kf", "@keyframes", "@keyframes ${'$'}{1:name} {\n    from { ${'$'}2 }\n    to { ${'$'}0 }\n}"),
                sn("var", "CSS-переменная", "var(--${'$'}{1:name})")
            )
        )
    }

    // ---------------- Языки программирования ----------------

    fun kotlin() = Vocabulary(
        vw(ItemKind.FUNCTION, "forEach map filter mapNotNull flatMap first firstOrNull last lastOrNull single any all none count sumOf maxByOrNull minByOrNull sortedBy sortedByDescending groupBy associate associateBy associateWith distinct distinctBy toList toMutableList toSet toMap joinToString reversed take drop chunked windowed zip partition fold reduce indexOf contains isEmpty isNotEmpty isNullOrEmpty isNullOrBlank orEmpty getOrNull getOrElse getOrPut substring substringBefore substringAfter split trim lowercase uppercase replace startsWith endsWith padStart format toInt toIntOrNull toLong toDouble toString use readText writeText readBytes collect emit flow stateOf mutableStateOf remember LaunchedEffect"),
        listOf(
            sn("main", "fun main()", "fun main() {\n    ${'$'}0\n}"),
            sn("fun", "Функция", "fun ${'$'}{1:name}(${'$'}{2}): ${'$'}{3:Unit} {\n    ${'$'}0\n}"),
            sn("sout", "println()", "println(${'$'}0)"),
            sn("soutv", "println(значение)", "println(\"${'$'}{1:value} = ${'$'}${'$'}{1:value}\")"),
            sn("class", "Класс", "class ${'$'}{1:Name}(${'$'}{2}) {\n    ${'$'}0\n}"),
            sn("data", "data class", "data class ${'$'}{1:Name}(val ${'$'}{2:id}: ${'$'}{3:Int})"),
            sn("obj", "object", "object ${'$'}{1:Name} {\n    ${'$'}0\n}"),
            sn("enum", "enum class", "enum class ${'$'}{1:Name} {\n    ${'$'}0\n}"),
            sn("sealed", "sealed interface", "sealed interface ${'$'}{1:State} {\n    ${'$'}0\n}"),
            sn("if", "if", "if (${'$'}{1:condition}) {\n    ${'$'}0\n}"),
            sn("ife", "if/else", "if (${'$'}{1:condition}) {\n    ${'$'}2\n} else {\n    ${'$'}0\n}"),
            sn("when", "when", "when (${'$'}{1:value}) {\n    ${'$'}{2:else} -> ${'$'}0\n}"),
            sn("for", "for in", "for (${'$'}{1:item} in ${'$'}{2:items}) {\n    ${'$'}0\n}"),
            sn("fori", "for по индексу", "for (${'$'}{1:i} in 0 until ${'$'}{2:n}) {\n    ${'$'}0\n}"),
            sn("try", "try/catch", "try {\n    ${'$'}1\n} catch (e: ${'$'}{2:Exception}) {\n    ${'$'}0\n}"),
            sn("lazy", "by lazy", "val ${'$'}{1:name} by lazy { ${'$'}0 }"),
            sn("comp", "@Composable", "@Composable\nfun ${'$'}{1:Name}(modifier: Modifier = Modifier) {\n    ${'$'}0\n}"),
            sn("todo", "TODO()", "TODO(\"${'$'}{1}\")")
        )
    )

    fun java() = Vocabulary(
        vw(ItemKind.FUNCTION, "println printf format valueOf parseInt equals hashCode toString length charAt substring indexOf contains isEmpty size get put add remove stream map filter collect forEach"),
        listOf(
            sn("psvm", "public static void main", "public static void main(String[] args) {\n    ${'$'}0\n}"),
            sn("sout", "System.out.println", "System.out.println(${'$'}0);"),
            sn("serr", "System.err.println", "System.err.println(${'$'}0);"),
            sn("fori", "for по индексу", "for (int ${'$'}{1:i} = 0; ${'$'}{1:i} < ${'$'}{2:n}; ${'$'}{1:i}++) {\n    ${'$'}0\n}"),
            sn("iter", "for-each", "for (${'$'}{1:var} ${'$'}{2:item} : ${'$'}{3:items}) {\n    ${'$'}0\n}"),
            sn("if", "if", "if (${'$'}{1:condition}) {\n    ${'$'}0\n}"),
            sn("try", "try/catch", "try {\n    ${'$'}1\n} catch (${'$'}{2:Exception} e) {\n    ${'$'}0\n}"),
            sn("class", "Класс", "public class ${'$'}{1:Name} {\n    ${'$'}0\n}"),
            sn("log", "Log.d", "Log.d(\"${'$'}{1:TAG}\", ${'$'}0);"),
            sn("override", "@Override метод", "@Override\npublic ${'$'}{1:void} ${'$'}{2:name}(${'$'}{3}) {\n    ${'$'}0\n}")
        )
    )

    fun gradle() = Vocabulary(
        emptyList(),
        listOf(
            sn("impl", "implementation", "implementation(\"${'$'}{1:group:artifact:version}\")"),
            sn("dep", "dependencies { }", "dependencies {\n    ${'$'}0\n}"),
            sn("android", "android { }", "android {\n    namespace = \"${'$'}{1:com.example}\"\n    compileSdk = ${'$'}{2:35}\n    ${'$'}0\n}")
        )
    )

    fun c() = Vocabulary(
        emptyList(),
        listOf(
            sn("main", "int main", "int main(int argc, char **argv) {\n    ${'$'}0\n    return 0;\n}"),
            sn("inc", "#include <>", "#include <${'$'}{1:stdio.h}>"),
            sn("incl", "#include \"\"", "#include \"${'$'}{1:header.h}\""),
            sn("guard", "Защита заголовка", "#ifndef ${'$'}{1:HEADER_H}\n#define ${'$'}{1:HEADER_H}\n\n${'$'}0\n\n#endif // ${'$'}{1:HEADER_H}"),
            sn("for", "for", "for (${'$'}{1:int} ${'$'}{2:i} = 0; ${'$'}{2:i} < ${'$'}{3:n}; ${'$'}{2:i}++) {\n    ${'$'}0\n}"),
            sn("if", "if", "if (${'$'}{1:condition}) {\n    ${'$'}0\n}"),
            sn("struct", "typedef struct", "typedef struct ${'$'}{1:name} {\n    ${'$'}0\n} ${'$'}{1:name}_t;"),
            sn("printf", "printf", "printf(\"${'$'}{1:%d}\\n\", ${'$'}0);"),
            sn("jni", "JNI-функция", "JNIEXPORT ${'$'}{1:jstring} JNICALL\nJava_${'$'}{2:com_example_Native}_${'$'}{3:name}(JNIEnv *env, jobject thiz) {\n    ${'$'}0\n}")
        )
    )

    fun go() = Vocabulary(
        vw(ItemKind.MODULE, "fmt os io bufio strings strconv errors context time sync bytes path filepath sort math json http log flag regexp reflect unicode") +
            vw(ItemKind.FUNCTION, "Println Printf Sprintf Errorf Fprintf ReadFile WriteFile Open Create Exit Getenv Contains Split Join TrimSpace HasPrefix Itoa Atoi New Is As Background WithCancel Now Since Sleep Marshal Unmarshal ListenAndServe HandleFunc"),
        listOf(
            sn("main", "package main", "package main\n\nimport \"fmt\"\n\nfunc main() {\n\t${'$'}0\n}"),
            sn("func", "Функция", "func ${'$'}{1:name}(${'$'}{2}) ${'$'}{3:error} {\n\t${'$'}0\n}"),
            sn("iferr", "if err != nil", "if err != nil {\n\treturn ${'$'}{1:err}\n}"),
            sn("for", "for", "for ${'$'}{1:i} := 0; ${'$'}{1:i} < ${'$'}{2:n}; ${'$'}{1:i}++ {\n\t${'$'}0\n}"),
            sn("forr", "for range", "for ${'$'}{1:_}, ${'$'}{2:v} := range ${'$'}{3:items} {\n\t${'$'}0\n}"),
            sn("struct", "type struct", "type ${'$'}{1:Name} struct {\n\t${'$'}0\n}"),
            sn("pln", "fmt.Println", "fmt.Println(${'$'}0)"),
            sn("pf", "fmt.Printf", "fmt.Printf(\"${'$'}{1:%v}\\n\", ${'$'}0)")
        )
    )

    fun rust() = Vocabulary(
        vw(ItemKind.FUNCTION, "println! print! eprintln! format! vec! panic! assert! assert_eq! todo! unimplemented! unreachable! write! writeln! matches! dbg! unwrap expect unwrap_or unwrap_or_default map and_then ok_or iter into_iter collect clone to_string to_owned as_str len push pop insert remove contains is_empty is_some is_none"),
        listOf(
            sn("main", "fn main", "fn main() {\n    ${'$'}0\n}"),
            sn("fn", "Функция", "fn ${'$'}{1:name}(${'$'}{2}) ${'$'}{3:-> ()} {\n    ${'$'}0\n}"),
            sn("struct", "struct", "#[derive(Debug)]\nstruct ${'$'}{1:Name} {\n    ${'$'}0\n}"),
            sn("impl", "impl", "impl ${'$'}{1:Name} {\n    ${'$'}0\n}"),
            sn("match", "match", "match ${'$'}{1:value} {\n    ${'$'}{2:_} => ${'$'}0,\n}"),
            sn("test", "#[test]", "#[test]\nfn ${'$'}{1:it_works}() {\n    ${'$'}0\n}"),
            sn("pln", "println!", "println!(\"${'$'}{1:{}}\", ${'$'}0);")
        )
    )

    fun javascript() = Vocabulary(
        vw(ItemKind.FUNCTION, "log error warn info then catch finally map filter reduce forEach find findIndex some every includes indexOf push pop shift unshift slice splice join split trim replace replaceAll startsWith endsWith keys values entries assign freeze stringify parse addEventListener removeEventListener querySelector querySelectorAll getElementById createElement appendChild setAttribute classList preventDefault resolve reject all allSettled race json text"),
        listOf(
            sn("log", "console.log", "console.log(${'$'}0);"),
            sn("fn", "function", "function ${'$'}{1:name}(${'$'}{2}) {\n  ${'$'}0\n}"),
            sn("af", "Стрелочная функция", "const ${'$'}{1:name} = (${'$'}{2}) => {\n  ${'$'}0\n};"),
            sn("afn", "async-функция", "async function ${'$'}{1:name}(${'$'}{2}) {\n  ${'$'}0\n}"),
            sn("for", "for", "for (let ${'$'}{1:i} = 0; ${'$'}{1:i} < ${'$'}{2:array}.length; ${'$'}{1:i}++) {\n  ${'$'}0\n}"),
            sn("forof", "for…of", "for (const ${'$'}{1:item} of ${'$'}{2:items}) {\n  ${'$'}0\n}"),
            sn("if", "if", "if (${'$'}{1:condition}) {\n  ${'$'}0\n}"),
            sn("try", "try/catch", "try {\n  ${'$'}1\n} catch (${'$'}{2:e}) {\n  ${'$'}0\n}"),
            sn("class", "class", "class ${'$'}{1:Name} {\n  constructor(${'$'}{2}) {\n    ${'$'}0\n  }\n}"),
            sn("imp", "import", "import ${'$'}{2:name} from '${'$'}{1:module}';"),
            sn("fetch", "fetch JSON", "const ${'$'}{1:res} = await fetch(${'$'}{2:url});\nconst ${'$'}{3:data} = await ${'$'}{1:res}.json();")
        )
    )

    fun qml() = Vocabulary(
        emptyList(),
        listOf(
            sn("item", "Item", "Item {\n    id: ${'$'}{1:root}\n    ${'$'}0\n}"),
            sn("rect", "Rectangle", "Rectangle {\n    width: ${'$'}{1:100}\n    height: ${'$'}{2:100}\n    color: \"${'$'}{3:transparent}\"\n    ${'$'}0\n}"),
            sn("text", "Text", "Text {\n    text: ${'$'}{1:qsTr(\"\")}\n    ${'$'}0\n}"),
            sn("prop", "property", "property ${'$'}{1:int} ${'$'}{2:name}: ${'$'}{3:0}"),
            sn("signal", "signal", "signal ${'$'}{1:clicked}(${'$'}{2})"),
            sn("conn", "Connections", "Connections {\n    target: ${'$'}{1:object}\n    function on${'$'}{2:Signal}() {\n        ${'$'}0\n    }\n}"),
            sn("timer", "Timer", "Timer {\n    interval: ${'$'}{1:1000}\n    running: true\n    repeat: ${'$'}{2:true}\n    onTriggered: ${'$'}0\n}"),
            sn("mouse", "MouseArea", "MouseArea {\n    anchors.fill: parent\n    onClicked: ${'$'}0\n}"),
            sn("fn", "function", "function ${'$'}{1:name}(${'$'}{2}) {\n    ${'$'}0\n}")
        )
    )

    fun python() = Vocabulary(
        vw(ItemKind.MODULE, "os sys re json math time datetime pathlib subprocess shutil argparse logging collections itertools functools typing dataclasses asyncio random hashlib base64 struct zipfile tempfile glob threading socket urllib requests") +
            vw(ItemKind.FUNCTION, "append extend insert remove pop index count sort reverse copy keys values items get update setdefault join split strip lstrip rstrip replace startswith endswith lower upper format encode decode read write readlines close exists makedirs listdir walk dumps loads"),
        listOf(
            sn("main", "if __name__ == \"__main__\"", "def main():\n    ${'$'}0\n\n\nif __name__ == \"__main__\":\n    main()"),
            sn("def", "Функция", "def ${'$'}{1:name}(${'$'}{2}):\n    ${'$'}{0:pass}"),
            sn("class", "Класс", "class ${'$'}{1:Name}:\n    def __init__(self${'$'}{2}):\n        ${'$'}{0:pass}"),
            sn("for", "for", "for ${'$'}{1:item} in ${'$'}{2:items}:\n    ${'$'}{0:pass}"),
            sn("fore", "for enumerate", "for ${'$'}{1:i}, ${'$'}{2:item} in enumerate(${'$'}{3:items}):\n    ${'$'}{0:pass}"),
            sn("if", "if", "if ${'$'}{1:condition}:\n    ${'$'}{0:pass}"),
            sn("try", "try/except", "try:\n    ${'$'}1\nexcept ${'$'}{2:Exception} as e:\n    ${'$'}{0:pass}"),
            sn("with", "with open", "with open(${'$'}{1:path}, \"${'$'}{2:r}\", encoding=\"utf-8\") as ${'$'}{3:f}:\n    ${'$'}0"),
            sn("pr", "print", "print(${'$'}0)"),
            sn("dc", "@dataclass", "@dataclass\nclass ${'$'}{1:Name}:\n    ${'$'}{2:field}: ${'$'}{3:int}")
        )
    )

    // ---------------- Pine Script (TradingView) ----------------

    // Строка «ns.name(args) → тип» → слово с подсказкой-сигнатурой
    private fun pineSigs(kind: ItemKind, block: String): List<VocabWord> = block.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { line ->
        val name = line.takeWhile { it != '(' && it != ' ' }
        VocabWord(name, kind, line.removePrefix(name).trim())
    }

    private fun pineMembers(ns: String, kind: ItemKind, names: String, detail: String) =
        words(names).map { VocabWord("$ns.$it", kind, detail) }

    fun pine(): Vocabulary {
        val fn = ItemKind.FUNCTION
        val words = ArrayList<VocabWord>()
        words += vw(
            ItemKind.MODULE,
            "ta math str array matrix map request strategy input color syminfo timeframe barstate session chart runtime log ticker currency display extend format hline location order plot position scale shape size text xloc yloc line label box table polyline linefill dayofweek alert barmerge font",
            "пространство имён"
        )
        words += pineSigs(fn, """
            indicator(title, shorttitle, overlay, format, precision, scale, max_bars_back, timeframe) → объявление индикатора
            strategy(title, shorttitle, overlay, initial_capital, default_qty_type, default_qty_value, pyramiding, commission_type, commission_value) → объявление стратегии
            library(title, overlay) → объявление библиотеки
            plot(series, title, color, linewidth, style, trackprice, histbase, offset, display) → plot
            plotshape(series, title, style, location, color, offset, text, textcolor, size) → void
            plotchar(series, title, char, location, color, offset, text, textcolor, size) → void
            plotarrow(series, title, colorup, colordown, offset, minheight, maxheight) → void
            plotbar(open, high, low, close, title, color) → void
            plotcandle(open, high, low, close, title, color, wickcolor, bordercolor) → void
            hline(price, title, color, linestyle, linewidth, editable) → hline
            fill(hline1, hline2, color, title) → void
            bgcolor(color, offset, editable, show_last, title) → void
            barcolor(color, offset, editable, show_last, title) → void
            alert(message, freq) → void
            alertcondition(condition, title, message) → void
            input(defval, title, tooltip, inline, group) → input
            nz(source, replacement) → значение без na
            na(x) → bool
            fixnan(source) → последнее не-na значение
            timestamp(year, month, day, hour, minute, second) → int
            time(timeframe, session, timezone) → int
            time_close(timeframe, session, timezone) → int
            year(time, timezone) → int
            month(time, timezone) → int
            dayofmonth(time, timezone) → int
            dayofweek(time, timezone) → int
            hour(time, timezone) → int
            minute(time, timezone) → int
            second(time, timezone) → int
            weekofyear(time, timezone) → int
            max_bars_back(var, num) → void
        """)
        words += pineSigs(ItemKind.VARIABLE, """
            open → series float
            high → series float
            low → series float
            close → series float
            volume → series float
            hl2 → (high + low) / 2
            hlc3 → (high + low + close) / 3
            ohlc4 → (open + high + low + close) / 4
            hlcc4 → (high + low + close + close) / 4
            bar_index → series int, номер бара
            last_bar_index → int, номер последнего бара
            last_bar_time → int
            timenow → текущее время, мс
            time_tradingday → int
        """)
        words += pineSigs(fn, """
            ta.sma(source, length) → простое скользящее среднее
            ta.ema(source, length) → экспоненциальное скользящее среднее
            ta.rma(source, length) → сглаженное среднее (RSI)
            ta.wma(source, length) → взвешенное среднее
            ta.vwma(source, length) → среднее, взвешенное по объёму
            ta.swma(source) → симметрично взвешенное среднее
            ta.hma(source, length) → Hull MA
            ta.alma(series, length, offset, sigma, floor) → Arnaud Legoux MA
            ta.linreg(source, length, offset) → линейная регрессия
            ta.rsi(source, length) → RSI
            ta.macd(source, fastlen, slowlen, siglen) → [macd, signal, hist]
            ta.stoch(source, high, low, length) → стохастик %K
            ta.cci(source, length) → CCI
            ta.mfi(series, length) → Money Flow Index
            ta.atr(length) → Average True Range
            ta.supertrend(factor, atrPeriod) → [supertrend, direction]
            ta.bb(series, length, mult) → [middle, upper, lower]
            ta.bbw(series, length, mult) → ширина полос Боллинджера
            ta.kc(series, length, mult, useTrueRange) → [middle, upper, lower]
            ta.kcw(series, length, mult, useTrueRange) → ширина каналов Кельтнера
            ta.dmi(diLength, adxSmoothing) → [+DI, -DI, ADX]
            ta.sar(start, inc, max) → Parabolic SAR
            ta.vwap(source, anchor, stdev_mult) → VWAP
            ta.crossover(source1, source2) → bool, пересечение снизу вверх
            ta.crossunder(source1, source2) → bool, пересечение сверху вниз
            ta.cross(source1, source2) → bool
            ta.highest(source, length) → максимум за период
            ta.lowest(source, length) → минимум за период
            ta.highestbars(source, length) → смещение до максимума
            ta.lowestbars(source, length) → смещение до минимума
            ta.pivothigh(source, leftbars, rightbars) → цена пивота или na
            ta.pivotlow(source, leftbars, rightbars) → цена пивота или na
            ta.change(source, length) → разница с баром назад
            ta.mom(source, length) → моментум
            ta.roc(source, length) → Rate of Change
            ta.percentrank(source, length) → процентиль
            ta.stdev(source, length, biased) → стандартное отклонение
            ta.variance(source, length, biased) → дисперсия
            ta.dev(source, length) → отклонение от SMA
            ta.cum(source) → накопленная сумма
            ta.barssince(condition) → баров с момента условия
            ta.valuewhen(condition, source, occurrence) → значение при условии
            ta.rising(source, length) → bool, рост N баров
            ta.falling(source, length) → bool, падение N баров
            ta.median(source, length) → медиана
            ta.mode(source, length) → мода
            ta.range(source, length) → max - min
            ta.correlation(source1, source2, length) → корреляция
            ta.cog(source, length) → центр тяжести
            ta.cmo(series, length) → Chande Momentum Oscillator
            ta.tsi(source, short_length, long_length) → True Strength Index
            ta.wpr(length) → Williams %R
            ta.tr(handle_na) → True Range
            ta.max(source) → максимум за всю историю
            ta.min(source) → минимум за всю историю
            ta.pivot_point_levels(type, anchor, developing) → уровни пивотов
            ta.percentile_linear_interpolation(source, length, percentage) → процентиль
            ta.percentile_nearest_rank(source, length, percentage) → процентиль
        """)
        words += pineMembers("ta", ItemKind.VARIABLE, "accdist iii nvi obv pvi pvt wad wvad", "встроенный ряд")
        words += pineSigs(fn, """
            math.abs(number) → модуль
            math.avg(number0, number1, ...) → среднее
            math.ceil(number) → int
            math.floor(number) → int
            math.round(number, precision) → округление
            math.round_to_mintick(number) → округление до тика
            math.max(number0, number1, ...) → максимум
            math.min(number0, number1, ...) → минимум
            math.pow(base, exponent) → степень
            math.sqrt(number) → корень
            math.exp(number) → e^x
            math.log(number) → ln
            math.log10(number) → lg
            math.sign(number) → знак
            math.sum(source, length) → сумма за период
            math.random(min, max, seed) → случайное число
            math.sin(angle) → синус
            math.cos(angle) → косинус
            math.tan(angle) → тангенс
            math.asin(angle) → арксинус
            math.acos(angle) → арккосинус
            math.atan(angle) → арктангенс
            math.todegrees(radians) → градусы
            math.toradians(degrees) → радианы
        """)
        words += pineMembers("math", ItemKind.CONSTANT, "pi e phi rphi", "константа")
        words += pineSigs(fn, """
            str.tostring(value, format) → string
            str.tonumber(string) → float
            str.format(formatString, arg0, arg1, ...) → string
            str.format_time(time, format, timezone) → string
            str.contains(source, str) → bool
            str.startswith(source, str) → bool
            str.endswith(source, str) → bool
            str.length(string) → int
            str.lower(source) → string
            str.upper(source) → string
            str.replace(source, target, replacement, occurrence) → string
            str.replace_all(source, target, replacement) → string
            str.split(string, separator) → array<string>
            str.substring(source, begin_pos, end_pos) → string
            str.pos(source, str) → int
            str.trim(source) → string
            str.repeat(source, repeat, separator) → string
            str.match(source, regex) → string
        """)
        words += pineMembers("array", fn, "new_float new_int new_bool new_string new_color new_line new_label new_box new_table new_linefill", "(size, initial_value) → array")
        words += pineMembers("array", fn, "from push pop shift unshift insert remove get set size clear concat copy slice sort sort_indices indexof lastindexof includes join max min sum avg median mode stdev variance range abs reverse fill first last every some binary_search binary_search_leftmost binary_search_rightmost covariance percentrank standardize", "массив")
        words += pineMembers("matrix", fn, "row col get set rows columns elements_count add_row add_col remove_row remove_col swap_rows swap_columns fill copy submatrix reverse sort concat reshape transpose sum diff mult pow det inv pinv rank trace eigenvalues eigenvectors kron avg max min median mode is_square is_identity is_diagonal is_symmetric", "матрица")
        words += pineMembers("map", fn, "put get remove contains keys values size clear copy put_all", "словарь")
        words += pineSigs(fn, """
            matrix.new(rows, columns, initial_value) → matrix
            map.new() → map<key, value>
            array.new(size, initial_value) → array
            request.security(symbol, timeframe, expression, gaps, lookahead, ignore_invalid_symbol, currency) → данные другого инструмента/ТФ
            request.security_lower_tf(symbol, timeframe, expression, ignore_invalid_symbol, currency) → array
            request.financial(symbol, financial_id, period, gaps) → финансовые данные
            request.economic(country_code, field, gaps) → экономические данные
            request.dividends(ticker, field, gaps, lookahead) → дивиденды
            request.earnings(ticker, field, gaps, lookahead) → отчётность
            request.splits(ticker, field, gaps, lookahead) → сплиты
            request.currency_rate(from, to, ignore_invalid_currency) → курс
            request.seed(source, symbol, expression) → данные из репозитория
            input.int(defval, title, minval, maxval, step, tooltip, inline, group, confirm) → int
            input.float(defval, title, minval, maxval, step, tooltip, inline, group, confirm) → float
            input.bool(defval, title, tooltip, inline, group, confirm) → bool
            input.string(defval, title, options, tooltip, inline, group, confirm) → string
            input.color(defval, title, tooltip, inline, group, confirm) → color
            input.source(defval, title, tooltip, inline, group) → series float
            input.timeframe(defval, title, options, tooltip, inline, group) → string
            input.symbol(defval, title, tooltip, inline, group) → string
            input.session(defval, title, options, tooltip, inline, group) → string
            input.time(defval, title, tooltip, inline, group) → int
            input.price(defval, title, tooltip, inline, group) → float
            input.text_area(defval, title, tooltip, group) → string
            input.enum(defval, title, options, tooltip, inline, group) → enum
            strategy.entry(id, direction, qty, limit, stop, oca_name, oca_type, comment, alert_message) → вход в позицию
            strategy.exit(id, from_entry, qty, qty_percent, profit, limit, loss, stop, trail_price, trail_points, trail_offset, comment) → выход (TP/SL)
            strategy.close(id, comment, qty, qty_percent, alert_message, immediately) → закрыть позицию
            strategy.close_all(comment, alert_message, immediately) → закрыть все
            strategy.cancel(id) → отменить ордер
            strategy.cancel_all() → отменить все ордера
            strategy.order(id, direction, qty, limit, stop, oca_name, oca_type, comment, alert_message) → ордер
            strategy.risk.allow_entry_in(value) → разрешённое направление
            strategy.risk.max_drawdown(value, type, alert_message) → ограничение просадки
            color.new(color, transp) → цвет с прозрачностью
            color.rgb(red, green, blue, transp) → цвет
            color.from_gradient(value, bottom_value, top_value, bottom_color, top_color) → цвет градиента
            color.r(color) → int
            color.g(color) → int
            color.b(color) → int
            color.t(color) → прозрачность
            timeframe.in_seconds(timeframe) → int
            timeframe.from_seconds(seconds) → string
            timeframe.change(timeframe) → bool
            line.new(x1, y1, x2, y2, xloc, extend, color, style, width) → line
            label.new(x, y, text, xloc, yloc, color, style, textcolor, size, textalign, tooltip) → label
            box.new(left, top, right, bottom, border_color, border_width, border_style, extend, xloc, bgcolor, text) → box
            table.new(position, columns, rows, bgcolor, frame_color, frame_width, border_color, border_width) → table
            table.cell(table_id, column, row, text, width, height, text_color, text_halign, text_valign, text_size, bgcolor, tooltip) → void
            polyline.new(points, curved, closed, xloc, line_color, fill_color, line_style, line_width) → polyline
            linefill.new(line1, line2, color) → linefill
            chart.point.new(time, index, price) → chart.point
            chart.point.from_index(index, price) → chart.point
            chart.point.from_time(time, price) → chart.point
            chart.point.now(price) → chart.point
            ticker.new(prefix, ticker, session, adjustment) → string
            ticker.heikinashi(symbol) → string
            ticker.modify(tickerid, session, adjustment) → string
            log.info(message) → запись в журнал Pine
            log.warning(message) → предупреждение в журнал
            log.error(message) → ошибка в журнал
            runtime.error(message) → остановить скрипт с ошибкой
        """)
        words += pineMembers("strategy", ItemKind.VARIABLE, "position_size position_avg_price position_entry_name equity netprofit grossprofit grossloss openprofit opentrades closedtrades wintrades losstrades eventrades initial_capital max_drawdown max_runup account_currency", "состояние стратегии")
        words += pineMembers("strategy", ItemKind.CONSTANT, "long short fixed cash percent_of_equity", "константа")
        words += pineMembers("strategy.commission", ItemKind.CONSTANT, "percent cash_per_contract cash_per_order", "тип комиссии")
        words += pineMembers("strategy.direction", ItemKind.CONSTANT, "long short all", "направление")
        words += pineMembers("strategy.oca", ItemKind.CONSTANT, "cancel reduce none", "группа OCA")
        words += pineMembers("strategy.opentrades", fn, "entry_price entry_bar_index entry_time entry_id entry_comment size profit commission max_runup max_drawdown", "(trade_num)")
        words += pineMembers("strategy.closedtrades", fn, "entry_price exit_price entry_bar_index exit_bar_index entry_time exit_time entry_id exit_id size profit commission max_runup max_drawdown", "(trade_num)")
        words += pineMembers("color", ItemKind.CONSTANT, "aqua black blue fuchsia gray green lime maroon navy olive orange purple red silver teal white yellow", "цвет")
        words += pineMembers("timeframe", ItemKind.VARIABLE, "period multiplier main_period isintraday isdaily isweekly ismonthly isminutes isseconds isdwm isticks", "таймфрейм")
        words += pineMembers("syminfo", ItemKind.VARIABLE, "ticker tickerid basecurrency currency description mintick pointvalue prefix root session timezone type volumetype country industry sector minmove pricescale expiration_date shares_outstanding_total employees", "инструмент")
        words += pineMembers("barstate", ItemKind.VARIABLE, "isfirst islast ishistory isrealtime isnew isconfirmed islastconfirmedhistory", "bool, состояние бара")
        words += pineMembers("session", ItemKind.VARIABLE, "isfirstbar isfirstbar_regular islastbar islastbar_regular ismarket ispremarket ispostmarket", "bool, сессия")
        words += pineMembers("session", ItemKind.CONSTANT, "regular extended", "тип сессии")
        words += pineMembers("chart", ItemKind.VARIABLE, "bg_color fg_color is_standard is_heikinashi is_renko is_linebreak is_kagi is_pnf is_range left_visible_bar_time right_visible_bar_time", "график")
        words += pineMembers("line", fn, "set_xy1 set_xy2 set_x1 set_x2 set_y1 set_y2 set_color set_width set_style set_extend set_xloc get_x1 get_x2 get_y1 get_y2 get_price delete copy", "линия")
        words += pineMembers("line", ItemKind.CONSTANT, "style_solid style_dotted style_dashed style_arrow_left style_arrow_right style_arrow_both", "стиль линии")
        words += pineMembers("label", fn, "set_text set_x set_y set_xy set_xloc set_yloc set_color set_textcolor set_style set_size set_textalign set_tooltip get_text get_x get_y delete copy", "метка")
        words += pineMembers("label", ItemKind.CONSTANT, "style_label_up style_label_down style_label_left style_label_right style_label_center style_label_upper_left style_label_upper_right style_label_lower_left style_label_lower_right style_none style_xcross style_cross style_triangleup style_triangledown style_flag style_circle style_arrowup style_arrowdown style_square style_diamond style_text_outline", "стиль метки")
        words += pineMembers("box", fn, "set_left set_right set_top set_bottom set_lefttop set_rightbottom set_bgcolor set_border_color set_border_width set_border_style set_extend set_text set_text_color set_text_size get_left get_right get_top get_bottom delete copy", "прямоугольник")
        words += pineMembers("table", fn, "cell_set_text cell_set_bgcolor cell_set_text_color cell_set_text_size cell_set_tooltip cell_set_width cell_set_height clear delete merge_cells set_position set_bgcolor set_frame_color set_border_color", "таблица")
        words += pineMembers("position", ItemKind.CONSTANT, "top_left top_center top_right middle_left middle_center middle_right bottom_left bottom_center bottom_right", "позиция таблицы")
        words += pineMembers("plot", ItemKind.CONSTANT, "style_line style_linebr style_stepline style_stepline_diamond style_steplinebr style_histogram style_cross style_area style_areabr style_columns style_circles linestyle_solid linestyle_dashed linestyle_dotted", "стиль графика")
        words += pineMembers("hline", ItemKind.CONSTANT, "style_solid style_dotted style_dashed", "стиль линии уровня")
        words += pineMembers("shape", ItemKind.CONSTANT, "xcross cross triangleup triangledown flag circle arrowup arrowdown labelup labeldown square diamond", "фигура")
        words += pineMembers("location", ItemKind.CONSTANT, "abovebar belowbar top bottom absolute", "расположение")
        words += pineMembers("size", ItemKind.CONSTANT, "auto tiny small normal large huge", "размер")
        words += pineMembers("xloc", ItemKind.CONSTANT, "bar_index bar_time", "ось X")
        words += pineMembers("yloc", ItemKind.CONSTANT, "price abovebar belowbar", "ось Y")
        words += pineMembers("extend", ItemKind.CONSTANT, "none left right both", "продление")
        words += pineMembers("format", ItemKind.CONSTANT, "inherit price volume percent mintick", "формат")
        words += pineMembers("scale", ItemKind.CONSTANT, "right left none", "шкала")
        words += pineMembers("display", ItemKind.CONSTANT, "all none pane data_window status_line price_scale", "отображение")
        words += pineMembers("alert", ItemKind.CONSTANT, "freq_once_per_bar freq_once_per_bar_close freq_all", "частота алерта")
        words += pineMembers("barmerge", ItemKind.CONSTANT, "gaps_on gaps_off lookahead_on lookahead_off", "request.security")
        words += pineMembers("dayofweek", ItemKind.CONSTANT, "sunday monday tuesday wednesday thursday friday saturday", "день недели")
        words += pineMembers("text", ItemKind.CONSTANT, "align_left align_center align_right align_top align_bottom wrap_auto wrap_none format_bold format_italic format_none", "текст")
        words += pineMembers("font", ItemKind.CONSTANT, "family_default family_monospace", "шрифт")
        words += pineMembers("order", ItemKind.CONSTANT, "ascending descending", "порядок сортировки")
        words += pineMembers("currency", ItemKind.CONSTANT, "USD EUR RUB GBP JPY CNY CHF AUD CAD USDT BTC ETH NONE", "валюта")

        val d = '$'
        val snippets = listOf(
            sn("indicator", "Новый индикатор", """
                //@version=6
                indicator("${d}{1:Мой индикатор}", overlay = ${d}{2:true})

                length = input.int(${d}{3:14}, "Длина", minval = 1)
                src = input.source(close, "Источник")

                ${d}{4:ma} = ta.sma(src, length)
                plot(${d}{4:ma}, "${d}{4:ma}", color = color.${d}{5:blue}, linewidth = 2)
                ${d}0
            """),
            sn("strategy", "Новая стратегия", """
                //@version=6
                strategy("${d}{1:Моя стратегия}", overlay = true, initial_capital = ${d}{2:10000}, default_qty_type = strategy.percent_of_equity, default_qty_value = ${d}{3:100})

                fastLen = input.int(${d}{4:9}, "Быстрая EMA", minval = 1)
                slowLen = input.int(${d}{5:21}, "Медленная EMA", minval = 1)
                fast = ta.ema(close, fastLen)
                slow = ta.ema(close, slowLen)

                if ta.crossover(fast, slow)
                    strategy.entry("Long", strategy.long)
                if ta.crossunder(fast, slow)
                    strategy.close("Long")

                plot(fast, "Fast", color.teal)
                plot(slow, "Slow", color.orange)
                ${d}0
            """),
            sn("library", "Новая библиотека", """
                //@version=6
                // @description ${d}{2:Описание библиотеки}
                library("${d}{1:MyLibrary}")

                // @function ${d}{4:Что делает функция}
                // @param x ${d}{5:Аргумент}
                // @returns ${d}{6:Результат}
                export ${d}{3:fn}(float x) =>
                    ${d}{0:x}
            """),
            sn("fn", "Функция", "${d}{1:name}(${d}{2:x}) =>\n    ${d}{0:x}"),
            sn("method", "Метод", "method ${d}{1:name}(${d}{2:Type} this${d}{3}) =>\n    ${d}0"),
            sn("type", "Пользовательский тип", "type ${d}{1:Name}\n    ${d}{2:float} ${d}{3:value} = ${d}{0:na}"),
            sn("enum", "Перечисление", "enum ${d}{1:Mode}\n    ${d}{2:fast} = \"${d}{3:Быстрый}\"\n    ${d}{0:slow} = \"Медленный\""),
            sn("if", "if", "if ${d}{1:condition}\n    ${d}0"),
            sn("ife", "if / else", "if ${d}{1:condition}\n    ${d}2\nelse\n    ${d}0"),
            sn("for", "for … to", "for ${d}{1:i} = ${d}{2:0} to ${d}{3:9}\n    ${d}0"),
            sn("forin", "for … in", "for ${d}{1:item} in ${d}{2:items}\n    ${d}0"),
            sn("while", "while", "while ${d}{1:condition}\n    ${d}0"),
            sn("switch", "switch", "switch ${d}{1:value}\n    ${d}{2:\"a\"} => ${d}{3:1}\n    => ${d}{0:0}"),
            sn("var", "var-переменная", "var ${d}{1:float} ${d}{2:name} = ${d}{0:na}"),
            sn("input", "input.int", "${d}{1:length} = input.int(${d}{2:14}, \"${d}{3:Длина}\", minval = ${d}{0:1})"),
            sn("sec", "request.security", "${d}{1:value} = request.security(syminfo.tickerid, \"${d}{2:D}\", ${d}{0:close})"),
            sn("cross", "Пересечение", "if ta.crossover(${d}{1:fast}, ${d}{2:slow})\n    ${d}0"),
            sn("plot", "plot", "plot(${d}{1:close}, \"${d}{2:title}\", color = color.${d}{3:blue}, linewidth = ${d}{0:2})"),
            sn("alertc", "alertcondition", "alertcondition(${d}{1:condition}, \"${d}{2:Сигнал}\", \"${d}{0:Сработало условие}\")"),
            sn("table", "Таблица на графике", """
                var table ${d}{1:info} = table.new(position.${d}{2:top_right}, 2, 1, bgcolor = color.new(color.black, 70))
                if barstate.islast
                    table.cell(${d}{1:info}, 0, 0, "${d}{3:Цена}", text_color = color.white)
                    table.cell(${d}{1:info}, 1, 0, str.tostring(close, format.mintick), text_color = color.white)
                ${d}0
            """)
        )
        return Vocabulary(words, snippets)
    }

    fun lua() = Vocabulary(
        vw(ItemKind.FUNCTION, "table.insert table.remove table.concat table.sort table.unpack string.format string.sub string.gsub string.find string.match string.gmatch string.rep string.byte string.char string.lower string.upper math.floor math.ceil math.max math.min math.random math.abs os.time os.date os.getenv os.execute io.open io.read io.write vim.api vim.fn vim.keymap.set vim.opt vim.cmd"),
        listOf(
            sn("fn", "function", "function ${'$'}{1:name}(${'$'}{2})\n    ${'$'}0\nend"),
            sn("lfn", "local function", "local function ${'$'}{1:name}(${'$'}{2})\n    ${'$'}0\nend"),
            sn("if", "if", "if ${'$'}{1:condition} then\n    ${'$'}0\nend"),
            sn("for", "for i", "for ${'$'}{1:i} = ${'$'}{2:1}, ${'$'}{3:n} do\n    ${'$'}0\nend"),
            sn("fori", "for ipairs", "for ${'$'}{1:i}, ${'$'}{2:v} in ipairs(${'$'}{3:t}) do\n    ${'$'}0\nend"),
            sn("forp", "for pairs", "for ${'$'}{1:k}, ${'$'}{2:v} in pairs(${'$'}{3:t}) do\n    ${'$'}0\nend"),
            sn("req", "require", "local ${'$'}{1:mod} = require(\"${'$'}{2:module}\")")
        )
    )

    fun shell() = Vocabulary(
        emptyList(),
        listOf(
            sn("shebang", "#!/usr/bin/env bash", "#!/usr/bin/env bash\nset -euo pipefail\n\n${'$'}0"),
            sn("if", "if", "if [[ ${'$'}{1:condition} ]]; then\n    ${'$'}0\nfi"),
            sn("ife", "if/else", "if [[ ${'$'}{1:condition} ]]; then\n    ${'$'}2\nelse\n    ${'$'}0\nfi"),
            sn("for", "for in", "for ${'$'}{1:item} in ${'$'}{2:\"${'$'}@\"}; do\n    ${'$'}0\ndone"),
            sn("fori", "for ((i))", "for ((${'$'}{1:i} = 0; ${'$'}{1:i} < ${'$'}{2:n}; ${'$'}{1:i}++)); do\n    ${'$'}0\ndone"),
            sn("while", "while read", "while IFS= read -r ${'$'}{1:line}; do\n    ${'$'}0\ndone < ${'$'}{2:file}"),
            sn("case", "case", "case \"${'$'}{1:${'$'}1}\" in\n    ${'$'}{2:pattern})\n        ${'$'}0\n        ;;\n    *)\n        ;;\nesac"),
            sn("func", "Функция", "${'$'}{1:name}() {\n    ${'$'}0\n}"),
            sn("die", "die()", "die() {\n    echo \"${'$'}*\" >&2\n    exit 1\n}"),
            sn("here", "here-doc", "cat <<'${'$'}{1:EOF}'\n${'$'}0\n${'$'}{1:EOF}"),
            sn("adb", "adb shell", "adb ${'$'}{1:-s serial }shell ${'$'}0")
        )
    )

    fun sql() = Vocabulary(
        emptyList(),
        listOf(
            sn("sel", "SELECT", "SELECT ${'$'}{1:*}\nFROM ${'$'}{2:table}\nWHERE ${'$'}0;"),
            sn("ins", "INSERT", "INSERT INTO ${'$'}{1:table} (${'$'}{2:columns})\nVALUES (${'$'}0);"),
            sn("upd", "UPDATE", "UPDATE ${'$'}{1:table}\nSET ${'$'}{2:column} = ${'$'}{3:value}\nWHERE ${'$'}0;"),
            sn("del", "DELETE", "DELETE FROM ${'$'}{1:table}\nWHERE ${'$'}0;"),
            sn("ct", "CREATE TABLE", "CREATE TABLE ${'$'}{1:name} (\n    id INTEGER PRIMARY KEY,\n    ${'$'}0\n);"),
            sn("join", "JOIN", "JOIN ${'$'}{1:table} ${'$'}{2:t} ON ${'$'}{2:t}.${'$'}{3:id} = ${'$'}0")
        )
    )

    fun yaml() = Vocabulary(
        vw(ItemKind.CONSTANT, "true false null"),
        listOf(
            sn("gha", "GitHub Actions workflow", """
                name: ${'$'}{1:CI}
                on:
                  push:
                    branches: [${'$'}{2:main}]
                jobs:
                  build:
                    runs-on: ubuntu-latest
                    steps:
                      - uses: actions/checkout@v4
                      - run: ${'$'}0
            """)
        )
    )

    fun markdown() = Vocabulary(
        emptyList(),
        listOf(
            sn("code", "Блок кода", "```${'$'}{1:kotlin}\n${'$'}0\n```"),
            sn("link", "Ссылка", "[${'$'}{1:текст}](${'$'}{2:https://})"),
            sn("img", "Картинка", "![${'$'}{1:alt}](${'$'}{2:path})"),
            sn("table", "Таблица", "| ${'$'}{1:A} | ${'$'}{2:B} |\n|---|---|\n| ${'$'}0 |  |"),
            sn("task", "Задача", "- [ ] ${'$'}0"),
            sn("details", "Спойлер", "<details>\n<summary>${'$'}{1:Подробнее}</summary>\n\n${'$'}0\n\n</details>")
        )
    )

    fun makefile() = Vocabulary(
        vw(ItemKind.FUNCTION, "\$(wildcard) \$(patsubst) \$(subst) \$(filter) \$(filter-out) \$(sort) \$(dir) \$(notdir) \$(basename) \$(addprefix) \$(addsuffix) \$(shell) \$(foreach) \$(if) \$(call) \$(eval) \$(info) \$(warning) \$(error) \$(abspath) \$(realpath)") +
            vw(ItemKind.KEYWORD, ".PHONY include ifeq ifneq ifdef ifndef else endif define endef export override") +
            vw(ItemKind.VARIABLE, "CC CXX CFLAGS CXXFLAGS LDFLAGS LDLIBS CPPFLAGS PREFIX DESTDIR MAKE \$@ \$< \$^ \$?"),
        listOf(
            sn("rule", "Правило", "${'$'}{1:target}: ${'$'}{2:deps}\n\t${'$'}0"),
            sn("phony", ".PHONY", ".PHONY: ${'$'}{1:all clean}"),
            sn("clean", "clean", ".PHONY: clean\nclean:\n\trm -rf ${'$'}{1:build}")
        )
    )

    fun dockerfile() = Vocabulary(
        vw(ItemKind.KEYWORD, "FROM RUN CMD LABEL EXPOSE ENV ADD COPY ENTRYPOINT VOLUME USER WORKDIR ARG ONBUILD STOPSIGNAL HEALTHCHECK SHELL AS"),
        listOf(
            sn("from", "FROM … AS", "FROM ${'$'}{1:debian:stable-slim} AS ${'$'}{2:build}"),
            sn("apt", "RUN apt-get install", "RUN apt-get update && apt-get install -y --no-install-recommends \\\n    ${'$'}0 \\\n    && rm -rf /var/lib/apt/lists/*")
        )
    )

    fun proguard() = Vocabulary(
        vw(ItemKind.DIRECTIVE, "-keep -keepclassmembers -keepclasseswithmembers -keepnames -keepclassmembernames -keepclasseswithmembernames -keepattributes -keeppackagenames -dontwarn -dontnote -dontobfuscate -dontoptimize -dontshrink -dontpreverify -optimizationpasses -allowaccessmodification -repackageclasses -flattenpackagehierarchy -renamesourcefileattribute -adaptclassstrings -adaptresourcefilenames -adaptresourcefilecontents -assumenosideeffects -assumevalues -printmapping -applymapping -printseeds -printusage -printconfiguration -include -injars -outjars -libraryjars -verbose -ignorewarnings -if -checkdiscard -whyareyoukeeping -obfuscationdictionary -classobfuscationdictionary") +
            vw(ItemKind.CONSTANT, "<init> <clinit> <fields> <methods> Signature *Annotation* SourceFile LineNumberTable InnerClasses EnclosingMethod Exceptions RuntimeVisibleAnnotations"),
        listOf(
            sn("keep", "-keep class", "-keep class ${'$'}{1:com.example.**} { *; }"),
            sn("keepm", "-keepclassmembers", "-keepclassmembers class ${'$'}{1:com.example.Model} {\n    ${'$'}{2:<fields>};\n}"),
            sn("nolog", "Убрать Log.*", "-assumenosideeffects class android.util.Log {\n    public static *** d(...);\n    public static *** v(...);\n}")
        )
    )

    /** Узлы конфига niri (KDL). */
    fun niri() = Vocabulary(
        vw(ItemKind.TAG, "input keyboard xkb layout variant options model rules repeat-delay repeat-rate track-layout numlock touchpad tap dwt dwtp natural-scroll accel-speed accel-profile scroll-method click-method tap-button-map disabled-on-external-mouse mouse trackpoint tablet touch warp-mouse-to-focus focus-follows-mouse workspace-auto-back-and-forth " +
            "output mode scale transform position variable-refresh-rate focus-at-startup background-color backdrop-color off " +
            "layout gaps center-focused-column always-center-single-column empty-workspace-above-first default-column-display preset-column-widths preset-window-heights default-column-width proportion fixed focus-ring border shadow tab-indicator insert-hint struts width active-color inactive-color urgent-color active-gradient inactive-gradient " +
            "spawn-at-startup spawn-sh-at-startup prefer-no-csd screenshot-path environment cursor xcursor-theme xcursor-size hide-when-typing hide-after-inactive-ms hotkey-overlay skip-at-startup hide-not-bound " +
            "animations slowdown workspace-switch window-open window-close horizontal-view-movement window-movement window-resize config-notification-open-close screenshot-ui-open overview-open-close spring easing duration-ms curve custom-shader " +
            "window-rule layer-rule match exclude app-id title is-active is-focused is-floating is-urgent at-startup open-on-output open-on-workspace open-maximized open-fullscreen open-floating open-focused block-out-from opacity draw-border-with-background geometry-corner-radius clip-to-geometry min-width max-width min-height max-height default-window-height default-floating-position variable-refresh-rate scroll-factor tiled-state baba-is-float " +
            "binds spawn spawn-sh close-window quit power-off-monitors toggle-overview show-hotkey-overlay screenshot screenshot-screen screenshot-window focus-column-left focus-column-right focus-window-up focus-window-down move-column-left move-column-right move-window-up move-window-down focus-workspace focus-workspace-up focus-workspace-down move-column-to-workspace move-window-to-workspace consume-or-expel-window-left consume-or-expel-window-right maximize-column fullscreen-window toggle-window-floating switch-focus-between-floating-and-tiling set-column-width set-window-height reset-window-height switch-preset-column-width center-column expand-column-to-available-width toggle-column-tabbed-display focus-monitor-left focus-monitor-right move-column-to-monitor-left move-column-to-monitor-right switch-layout do-screen-transition toggle-keyboard-shortcuts-inhibit allow-when-locked allow-inhibiting cooldown-ms repeat hotkey-overlay-title " +
            "workspace open-on-output named include debug gestures hot-corners dnd-edge-view-scroll dnd-edge-workspace-switch clipboard disable-primary overview zoom xwayland-satellite recent-windows switcher") +
            vw(ItemKind.CONSTANT, "#true #false #null true false on off") +
            vw(ItemKind.VALUE, "Mod Super Alt Ctrl Shift Mod+Return Mod+Q Mod+E Mod+T Mod+D Print XF86AudioRaiseVolume XF86AudioLowerVolume XF86AudioMute XF86MonBrightnessUp XF86MonBrightnessDown WheelScrollDown WheelScrollUp"),
        listOf(
            sn("bind", "Сочетание клавиш", "${'$'}{1:Mod+T} hotkey-overlay-title=\"${'$'}{2:Описание}\" { ${'$'}{3:spawn} \"${'$'}{4:alacritty}\"; }"),
            sn("rule", "window-rule", "window-rule {\n    match app-id=r#\"${'$'}{1:^org\\.app$}\"#\n    ${'$'}0\n}"),
            sn("output", "output", "output \"${'$'}{1:eDP-1}\" {\n    mode \"${'$'}{2:1920x1080@60}\"\n    scale ${'$'}{3:1}\n}"),
            sn("startup", "spawn-at-startup", "spawn-at-startup \"${'$'}{1:waybar}\""),
            sn("env", "environment", "environment {\n    ${'$'}{1:QT_QPA_PLATFORM} \"${'$'}{2:wayland}\"\n}")
        )
    )
}
