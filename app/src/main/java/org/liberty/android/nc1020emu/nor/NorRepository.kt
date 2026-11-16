package org.liberty.android.nc1020emu.nor

import org.liberty.android.nc1020emu.NC1020JNI

object NorRepository {
    const val ROOT_INODE = 0

    const val FS_OK = 0
    const val FS_ERR_CORRUPT = -1
    const val FS_ERR_NOT_FOUND = -2
    const val FS_ERR_EXISTS = -3
    const val FS_ERR_NO_SPACE = -4
    const val FS_ERR_NAME_INVALID = -5
    const val FS_ERR_NOT_EMPTY = -6
    const val FS_ERR_IS_DIR = -7
    const val FS_ERR_NOT_DIR = -8
    const val FS_ERR_DIR_FULL = -9

    fun getStorageInfo(): NorStorageInfo? {
        val stats = IntArray(9)
        val rc = NC1020JNI.norGetInfo(stats)
        if (rc != FS_OK) return null
        return NorStorageInfo(
            batBlock = stats[0],
            rootBlock = stats[1],
            totalBlocks = stats[2],
            reservedBlocks = stats[3],
            usedBlocks = stats[4],
            freeBlocks = stats[5],
            dirtyBlocks = stats[6],
            wearCount = stats[7],
            hasOpenFcb = stats[8] != 0
        )
    }

    fun listDirectory(dirInode: Int): List<NorEntry>? {
        val packed = NC1020JNI.norListDir(dirInode) ?: return null
        val count = packed.size / 32
        val list = ArrayList<NorEntry>(count)
        for (i in 0 until count) {
            val base = i * 32
            val nameLen = (packed[base + 16].toInt() and 0xFF).coerceIn(0, 16)
            val nameGbk = packed.copyOfRange(base, base + nameLen)
            val nameStr = WqxTextCodec.decodeGbk(nameGbk)
            val attr = packed[base + 17].toInt() and 0xFF
            val attr2 = packed[base + 18].toInt() and 0xFF
            val year = (packed[base + 19].toInt() and 0xFF) or ((packed[base + 20].toInt() and 0xFF) shl 8)
            val month = packed[base + 21].toInt() and 0xFF
            val day = packed[base + 22].toInt() and 0xFF
            val size = (packed[base + 23].toInt() and 0xFF) or
                ((packed[base + 24].toInt() and 0xFF) shl 8) or
                ((packed[base + 25].toInt() and 0xFF) shl 16) or
                ((packed[base + 26].toInt() and 0xFF) shl 24)
            val inode = (packed[base + 27].toInt() and 0xFF) or ((packed[base + 28].toInt() and 0xFF) shl 8)
            val blocks = (packed[base + 29].toInt() and 0xFF) or ((packed[base + 30].toInt() and 0xFF) shl 8)
            list.add(
                NorEntry(
                    name = nameStr,
                    nameGbk = nameGbk,
                    attr = attr,
                    attr2 = attr2,
                    year = year,
                    month = month,
                    day = day,
                    size = size,
                    inode = inode,
                    blocks = blocks
                )
            )
        }
        return list.sortedWith(
            compareByDescending<NorEntry> { it.isDirectory }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        )
    }

    fun readFile(entry: NorEntry): ByteArray? =
        NC1020JNI.norReadFile(entry.inode, entry.size)

    fun createDirectory(parentInode: Int, folderName: String, saveStates: Boolean): Pair<Int, Int> {
        val valid = WqxTextCodec.validateWqxName(folderName)
        if (valid !is WqxTextCodec.NameValidation.Valid) {
            return FS_ERR_NAME_INVALID to 0
        }
        val outIno = IntArray(1)
        val rc = NC1020JNI.norMkdir(parentInode, valid.gbkBytes, saveStates, outIno)
        return rc to outIno[0]
    }

    fun ensureRootSubdirectory(folderName: String, saveStates: Boolean): Pair<Int, Int> {
        val rootEntries = listDirectory(ROOT_INODE) ?: return FS_ERR_CORRUPT to 0
        val existing = rootEntries.firstOrNull { it.isDirectory && it.name == folderName }
        if (existing != null) {
            return FS_OK to existing.inode
        }
        val (rc, ino) = createDirectory(ROOT_INODE, folderName, saveStates)
        if (rc == FS_ERR_EXISTS && ino > 0) {
            return FS_OK to ino
        }
        return rc to ino
    }

    fun writeFile(
        dirInode: Int,
        fileName: String,
        data: ByteArray,
        overwrite: Boolean,
        saveStates: Boolean
    ): Pair<Int, Int> {
        val valid = WqxTextCodec.validateWqxName(fileName)
        if (valid !is WqxTextCodec.NameValidation.Valid) {
            return FS_ERR_NAME_INVALID to 0
        }
        val outIno = IntArray(1)
        val rc = NC1020JNI.norWriteFile(dirInode, valid.gbkBytes, data, overwrite, saveStates, outIno)
        return rc to outIno[0]
    }

    fun deleteEntry(dirInode: Int, targetInode: Int, saveStates: Boolean): Int =
        NC1020JNI.norDelete(dirInode, targetInode, saveStates)

    fun renameEntry(
        dirInode: Int,
        targetInode: Int,
        newName: String,
        saveStates: Boolean
    ): Int {
        val valid = WqxTextCodec.validateWqxName(newName)
        if (valid !is WqxTextCodec.NameValidation.Valid) {
            return FS_ERR_NAME_INVALID
        }
        return NC1020JNI.norRename(dirInode, targetInode, valid.gbkBytes, saveStates)
    }

    fun exportFullImage(): ByteArray? = NC1020JNI.norExportImage()

    fun importFullImage(bytes: ByteArray, saveStates: Boolean): Boolean =
        NC1020JNI.norImportImage(bytes, saveStates)
}
