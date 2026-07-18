package io.legado.app.help.config

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.SearchBook
import java.io.File
import java.net.URI

/**
 * 封面集合管理器（最小桩实现）。
 *
 * 原 source 工程的 CoverCollectionManager 依赖 AppCloudStorage 等云同步子系统，
 * 在 legado_max 工程中未迁移。此桩仅保留 BookCoverImage / CoverDisplayResolver
 * 调用所必需的 API，所有「集合封面」相关功能均退化为不启用（返回 null / false），
 * 应用退化为始终使用书籍原始封面，行为安全且可编译。
 *
 * 如未来需要恢复封面集合功能，请将 source 中的完整实现连同相关子系统一起迁移。
 */
object CoverCollectionManager {

    const val MODE_RANDOM = "random"
    const val MODE_SEQUENCE = "sequence"
    const val MODE_MIXED = "mixed"

    fun isMixedMode(): Boolean = false

    fun selectionKey(): String = "false::"

    fun selectedCollectionCover(book: Book): String? = null

    fun selectedCollectionCover(searchBook: SearchBook): String? = null

    fun selectedCollectionCover(bookKey: String, coverPath: String?): String? = null

    fun String?.isRealCoverPath(): Boolean {
        val value = this?.trim().orEmpty()
        if (value.isBlank() || value.equals("use_default_cover", ignoreCase = true)) {
            return false
        }
        val lowerValue = value.lowercase()
        return when {
            lowerValue.startsWith("http://") ||
                lowerValue.startsWith("https://") ||
                lowerValue.startsWith("content://") ||
                lowerValue.startsWith("android.resource://") ||
                lowerValue.startsWith("file:///android_asset/") -> true
            lowerValue.startsWith("file://") -> runCatching {
                File(URI(value).path.orEmpty()).isFile
            }.getOrDefault(false)
            File(value).isAbsolute -> File(value).isFile
            else -> true
        }
    }
}
