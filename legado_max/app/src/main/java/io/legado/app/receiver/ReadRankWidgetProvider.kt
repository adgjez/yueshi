package io.legado.app.receiver

import android.appwidget.AppWidgetProvider
import android.content.Context

/**
 * Stub widget provider.
 *
 * Target repo has not migrated the full widget UI subsystem
 * (ReadRecordActivity, widget layouts, ReadRecordWidgetStore, etc.).
 * This stub exists so that ReadRecordDailyHelper can compile and call
 * `updateAll` without unresolved references. The call is a no-op.
 */
class ReadRankWidgetProvider : AppWidgetProvider() {

    companion object {
        @JvmStatic
        fun updateAll(context: Context, force: Boolean = false) {
            // No-op stub: widget rendering not migrated.
        }
    }
}
