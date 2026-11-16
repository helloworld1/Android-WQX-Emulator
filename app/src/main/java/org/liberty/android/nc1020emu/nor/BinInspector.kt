package org.liberty.android.nc1020emu.nor

object BinInspector {
    data class BinAnalysis(
        val isNativeNc1020Bin: Boolean,
        val hasFtpLinkWrapper: Boolean,
        val isLavaBytecode: Boolean,
        val entryAddress: Int?,
        val loadAddress: Int?,
        val summaryLines: List<String>
    ) {
        fun unwrapIfNeeded(raw: ByteArray): ByteArray =
            if (hasFtpLinkWrapper && raw.size > 16) raw.copyOfRange(16, raw.size) else raw
    }

    fun analyze(bytes: ByteArray): BinAnalysis {
        if (bytes.size >= 3 &&
            bytes[0] == 'L'.code.toByte() &&
            bytes[1] == 'A'.code.toByte() &&
            bytes[2] == 'V'.code.toByte()
        ) {
            return BinAnalysis(
                isNativeNc1020Bin = false,
                hasFtpLinkWrapper = false,
                isLavaBytecode = true,
                entryAddress = null,
                loadAddress = null,
                summaryLines = listOf(
                    "Format: LavaX / Lee (.lav) bytecode",
                    "Note: Not a direct 6502 NC1020 .bin executable (requires a LavaX VM)"
                )
            )
        }

        val nativeAt0 = hasNativeSignature(bytes, 0)
        val nativeAt16 = !nativeAt0 && bytes.size > 26 && hasNativeSignature(bytes, 16)
        val base = if (nativeAt16) 16 else 0

        if (nativeAt0 || nativeAt16) {
            val sigHex = "%02X %02X %02X".format(
                bytes[base].toInt() and 0xFF,
                bytes[base + 1].toInt() and 0xFF,
                bytes[base + 2].toInt() and 0xFF
            )
            val loadAddr = if (bytes.size >= base + 5) {
                (bytes[base + 3].toInt() and 0xFF) or ((bytes[base + 4].toInt() and 0xFF) shl 8)
            } else null
            val entryAddr = if (bytes.size >= base + 10 && (bytes[base + 7].toInt() and 0xFF) == 0x4C) {
                (bytes[base + 8].toInt() and 0xFF) or ((bytes[base + 9].toInt() and 0xFF) shl 8)
            } else null

            val lines = ArrayList<String>()
            lines.add("Signature: Valid WQX 6502 App ($sigHex)")
            if (nativeAt16) {
                lines.add("Wrapper: 16-byte PC transfer header detected (can strip on upload)")
            }
            if (loadAddr != null) {
                lines.add("Header Word (+3): $%04X".format(loadAddr))
            }
            if (entryAddr != null) {
                lines.add("Entry Vector (+7): JMP $%04X".format(entryAddr))
            }
            lines.add("Size: ${bytes.size} bytes (${(bytes.size + 4095) / 4096} × 4 KB blocks)")
            return BinAnalysis(
                isNativeNc1020Bin = true,
                hasFtpLinkWrapper = nativeAt16,
                isLavaBytecode = false,
                entryAddress = entryAddr,
                loadAddress = loadAddr,
                summaryLines = lines
            )
        }

        return BinAnalysis(
            isNativeNc1020Bin = false,
            hasFtpLinkWrapper = false,
            isLavaBytecode = false,
            entryAddress = null,
            loadAddress = null,
            summaryLines = listOf(
                "Signature: Non-standard / raw binary (${bytes.take(8).joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }})",
                "Size: ${bytes.size} bytes (${maxOf(1, (bytes.size + 4095) / 4096)} × 4 KB blocks)"
            )
        )
    }

    private fun hasNativeSignature(bytes: ByteArray, offset: Int): Boolean {
        if (offset + 8 > bytes.size) return false
        val b0 = bytes[offset].toInt() and 0xFF
        val b1 = bytes[offset + 1].toInt() and 0xFF
        val b2 = bytes[offset + 2].toInt() and 0xFF
        return (b0 == 0xAE && b1 == 0xEE && b2 == 0xEA) ||
            (b0 == 0xAA && b1 == 0xA5 && b2 == 0x5A)
    }
}
