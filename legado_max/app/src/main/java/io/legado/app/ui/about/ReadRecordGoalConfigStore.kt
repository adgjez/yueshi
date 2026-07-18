package io.legado.app.ui.about

import io.legado.app.constant.PreferKey
import io.legado.app.utils.GSON
import io.legado.app.utils.getPrefString
import io.legado.app.utils.fromJsonObject
import splitties.init.appCtx

data class ReadRecordGoalConfig(
    val userName: String? = null,
    val avatar: String? = null,
    val dailyGoalMinutes: Int = 120
)

object ReadRecordGoalConfigStore {
    fun load(): ReadRecordGoalConfig {
        val raw = appCtx.getPrefString(PreferKey.readRecordGoalConfig).orEmpty()
        if (raw.isBlank()) return ReadRecordGoalConfig()
        return GSON.fromJsonObject<ReadRecordGoalConfig>(raw).getOrDefault(ReadRecordGoalConfig())
    }
}
