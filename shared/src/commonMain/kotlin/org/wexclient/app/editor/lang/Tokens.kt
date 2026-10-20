package org.wexclient.app.editor.lang

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import org.wexclient.app.theme.NoctaliaPrimary
import org.wexclient.app.theme.NoctaliaSecondary
import org.wexclient.app.theme.NoctaliaTertiary

/** Типы токенов подсветки (общие для всех языков). */
enum class Tok {
    TEXT,
    KEYWORD,
    CONTROL,        // if/for/return — отдельный оттенок ключевых слов
    MODIFIER,       // public/static/final
    TYPE,
    BUILTIN,
    FUNCTION,
    CONSTANT,
    NUMBER,
    STRING,
    ESCAPE,
    REGEX,
    COMMENT,
    DOC_COMMENT,
    DOC_TAG,        // @param в документации
    ANNOTATION,
    PREPROCESSOR,
    OPERATOR,
    PUNCTUATION,
    BRACKET,
    TAG,
    ATTRIBUTE,
    ENTITY,
    VARIABLE,
    PROPERTY,       // ключи JSON/YAML/INI
    SECTION,        // [section]
    LABEL,
    REGISTER,
    DIRECTIVE,
    OPCODE,
    HEADING,
    EMPHASIS,
    STRONG,
    LINK,
    CODE,
    QUOTE,
    LIST,
    INSERTED,
    DELETED,
    HUNK,
    META,
    INVALID,
    COLUMN_0, COLUMN_1, COLUMN_2, COLUMN_3, COLUMN_4, COLUMN_5
}

/** Токен в пределах одной строки: [start, end). */
class Token(val start: Int, val end: Int, val type: Tok)

/** Цвета подсветки: акценты — из темы, остальное — мягкая палитра под тёмный фон. */
object SyntaxColors {
    fun style(type: Tok): SpanStyle? = when (type) {
        Tok.TEXT, Tok.PUNCTUATION -> null
        Tok.KEYWORD -> SpanStyle(color = NoctaliaPrimary, fontWeight = FontWeight.SemiBold)
        Tok.CONTROL -> SpanStyle(color = Color(0xFFF472B6), fontWeight = FontWeight.SemiBold)
        Tok.MODIFIER -> SpanStyle(color = NoctaliaPrimary.copy(alpha = 0.85f))
        Tok.TYPE -> SpanStyle(color = Color(0xFFC4A7FF))
        Tok.BUILTIN -> SpanStyle(color = Color(0xFF7DD3FC))
        Tok.FUNCTION -> SpanStyle(color = Color(0xFF93C5FD))
        Tok.CONSTANT -> SpanStyle(color = Color(0xFFFCA5A5), fontWeight = FontWeight.SemiBold)
        Tok.NUMBER -> SpanStyle(color = Color(0xFFFDBA74))
        Tok.STRING -> SpanStyle(color = Color(0xFF86EFAC))
        Tok.ESCAPE -> SpanStyle(color = Color(0xFFFDE68A), fontWeight = FontWeight.SemiBold)
        Tok.REGEX -> SpanStyle(color = Color(0xFF5EEAD4))
        Tok.COMMENT -> SpanStyle(color = Color(0xFF8B949E), fontStyle = FontStyle.Italic)
        Tok.DOC_COMMENT -> SpanStyle(color = Color(0xFF7FA17F), fontStyle = FontStyle.Italic)
        Tok.DOC_TAG -> SpanStyle(color = Color(0xFF9FC59F), fontStyle = FontStyle.Italic, fontWeight = FontWeight.SemiBold)
        Tok.ANNOTATION -> SpanStyle(color = Color(0xFFFDE047))
        Tok.PREPROCESSOR -> SpanStyle(color = NoctaliaTertiary)
        Tok.OPERATOR -> SpanStyle(color = Color(0xFFCBD5E1))
        Tok.BRACKET -> SpanStyle(color = Color(0xFFE2E8F0))
        Tok.TAG -> SpanStyle(color = NoctaliaPrimary, fontWeight = FontWeight.SemiBold)
        Tok.ATTRIBUTE -> SpanStyle(color = Color(0xFF93C5FD))
        Tok.ENTITY -> SpanStyle(color = Color(0xFFFDE68A))
        Tok.VARIABLE -> SpanStyle(color = Color(0xFFF9A8D4))
        Tok.PROPERTY -> SpanStyle(color = Color(0xFF93C5FD))
        Tok.SECTION -> SpanStyle(color = NoctaliaPrimary, fontWeight = FontWeight.Bold)
        Tok.LABEL -> SpanStyle(color = Color(0xFF86EFAC), fontWeight = FontWeight.SemiBold)
        Tok.REGISTER -> SpanStyle(color = Color(0xFF67E8F9))
        Tok.DIRECTIVE -> SpanStyle(color = NoctaliaPrimary, fontWeight = FontWeight.Bold)
        Tok.OPCODE -> SpanStyle(color = NoctaliaTertiary, fontWeight = FontWeight.SemiBold)
        Tok.HEADING -> SpanStyle(color = NoctaliaPrimary, fontWeight = FontWeight.Bold)
        Tok.EMPHASIS -> SpanStyle(fontStyle = FontStyle.Italic, color = Color(0xFFE9D5FF))
        Tok.STRONG -> SpanStyle(fontWeight = FontWeight.Bold, color = Color(0xFFF5F5F4))
        Tok.LINK -> SpanStyle(color = Color(0xFF7DD3FC))
        Tok.CODE -> SpanStyle(color = Color(0xFF86EFAC))
        Tok.QUOTE -> SpanStyle(color = Color(0xFF8B949E), fontStyle = FontStyle.Italic)
        Tok.LIST -> SpanStyle(color = NoctaliaSecondary, fontWeight = FontWeight.Bold)
        Tok.INSERTED -> SpanStyle(color = Color(0xFF4ADE80))
        Tok.DELETED -> SpanStyle(color = Color(0xFFF87171))
        Tok.HUNK -> SpanStyle(color = Color(0xFF7DD3FC), fontWeight = FontWeight.SemiBold)
        Tok.META -> SpanStyle(color = NoctaliaTertiary)
        Tok.INVALID -> SpanStyle(color = Color(0xFFF87171))
        Tok.COLUMN_0 -> SpanStyle(color = Color(0xFFE2E8F0))
        Tok.COLUMN_1 -> SpanStyle(color = Color(0xFF93C5FD))
        Tok.COLUMN_2 -> SpanStyle(color = Color(0xFF86EFAC))
        Tok.COLUMN_3 -> SpanStyle(color = Color(0xFFFDBA74))
        Tok.COLUMN_4 -> SpanStyle(color = Color(0xFFC4A7FF))
        Tok.COLUMN_5 -> SpanStyle(color = Color(0xFFF9A8D4))
    }

    /** Цвет для миникарты (без стилей шрифта). */
    fun color(type: Tok, default: Color): Color = style(type)?.color?.takeIf { it != Color.Unspecified } ?: default
}
