package org.wexclient.app.ui

import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester

/**
 * Попросить фокус и узнать, получилось ли. Поле, появляющееся по ходу анимации, первые кадры ещё не прикреплено:
 * раньше requestFocus() в этом случае бросал исключение, и по нему повторяли попытку. С Compose 1.8 он молча
 * пишет предупреждение и ничего не делает — повтор по исключению сдавался на первом кадре, фокус не ставился.
 */
internal fun FocusRequester.tryRequestFocus(): Boolean =
    runCatching { requestFocus(FocusDirection.Enter) }.getOrDefault(false)
