package org.liberty.android.nc1020emu.nor

enum class NorFileCategory {
    APP_BIN,
    TEXT_DOC,
    GVBASIC,
    BOOT_SKIN,
    SYSTEM_OR_SAVE
}

data class NorStorageInfo(
    val batBlock: Int,
    val rootBlock: Int,
    val totalBlocks: Int,
    val reservedBlocks: Int,
    val usedBlocks: Int,
    val freeBlocks: Int,
    val dirtyBlocks: Int,
    val wearCount: Int,
    val hasOpenFcb: Boolean
) {
    val userTotalBlocks: Int
        get() = (totalBlocks - reservedBlocks).coerceAtLeast(1)

    val availableBlocks: Int
        get() = (freeBlocks + dirtyBlocks).coerceAtLeast(0)

    val userUsedBlocks: Int
        get() = (userTotalBlocks - availableBlocks).coerceAtLeast(0)

    val userTotalBytes: Int
        get() = userTotalBlocks * BLOCK_SIZE

    val userUsedBytes: Int
        get() = userUsedBlocks * BLOCK_SIZE

    val userAvailableBytes: Int
        get() = availableBlocks * BLOCK_SIZE

    companion object {
        const val BLOCK_SIZE = 4096
        const val NOR_IMAGE_SIZE = 1024 * 1024
    }
}

data class NorEntry(
    val name: String,
    val nameGbk: ByteArray,
    val attr: Int,
    val attr2: Int,
    val year: Int,
    val month: Int,
    val day: Int,
    val size: Int,
    val inode: Int,
    val blocks: Int
) {
    val isDirectory: Boolean
        get() = (attr and 0x08) == 0

    val isReadOnly: Boolean
        get() = (attr and 0x04) != 0

    val isHidden: Boolean
        get() = (attr and 0x02) != 0

    val isSystem: Boolean
        get() = (attr and 0x01) != 0

    val extensionLower: String
        get() {
            if (isDirectory) return ""
            val dot = name.lastIndexOf('.')
            return if (dot >= 0 && dot < name.length - 1) {
                name.substring(dot + 1).lowercase()
            } else {
                ""
            }
        }

    val category: NorFileCategory
        get() = categoryForExtension(extensionLower)

    val formattedDate: String
        get() = "%04d-%02d-%02d".format(year, month.coerceIn(1, 12), day.coerceIn(1, 31))

    companion object {
        const val DIR_APPLICATIONS = "应用程序"
        const val DIR_TEXT_FILES = "文本文件"
        const val DIR_GVBASIC = "GVBASIC"
        const val DIR_BOOT_SCREENS = "开机画面"
        const val DIR_PERSONAL_DOCS = "个人文档"

        fun categoryForExtension(extLower: String): NorFileCategory = when (extLower) {
            "bin" -> NorFileCategory.APP_BIN
            "txt", "ebk", "gtx" -> NorFileCategory.TEXT_DOC
            "bas" -> NorFileCategory.GVBASIC
            "ski" -> NorFileCategory.BOOT_SKIN
            else -> NorFileCategory.SYSTEM_OR_SAVE
        }

        fun officialRootFolderForExtension(extLower: String): String? = when (extLower) {
            "bin" -> DIR_APPLICATIONS
            "txt", "ebk", "gtx" -> DIR_TEXT_FILES
            "bas" -> DIR_GVBASIC
            "ski", "png", "jpg", "jpeg", "webp", "bmp" -> DIR_BOOT_SCREENS
            else -> null
        }

        fun isImageExtension(extLower: String): Boolean =
            extLower in setOf("png", "jpg", "jpeg", "webp", "bmp")
    }
}
