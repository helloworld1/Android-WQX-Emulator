package org.liberty.android.nc1020emu.nor

import android.content.Context
import android.graphics.Bitmap
import androidx.appcompat.app.AlertDialog
import org.liberty.android.nc1020emu.R
import java.io.ByteArrayOutputStream

object NorExportHelper {
    data class PreparedExport(
        val suggestedFileName: String,
        val mimeType: String,
        val bytes: ByteArray
    )

    fun promptAndPrepareExport(
        context: Context,
        entry: NorEntry,
        rawBytes: ByteArray,
        onReady: (PreparedExport) -> Unit
    ) {
        when (entry.category) {
            NorFileCategory.TEXT_DOC -> {
                val options = arrayOf(
                    context.getString(R.string.nor_export_txt_utf8),
                    context.getString(R.string.nor_export_txt_raw)
                )
                AlertDialog.Builder(context)
                    .setTitle(R.string.nor_export_format_title)
                    .setItems(options) { _, which ->
                        if (which == 0) {
                            val utf8Text = WqxTextCodec.decodeWqxDocument(rawBytes)
                            val outName = if (entry.extensionLower == "txt") {
                                entry.name
                            } else {
                                "${entry.name}.txt"
                            }
                            onReady(
                                PreparedExport(
                                    suggestedFileName = outName,
                                    mimeType = "text/plain",
                                    bytes = utf8Text.toByteArray(Charsets.UTF_8)
                                )
                            )
                        } else {
                            onReady(
                                PreparedExport(
                                    suggestedFileName = entry.name,
                                    mimeType = "application/octet-stream",
                                    bytes = rawBytes
                                )
                            )
                        }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }

            NorFileCategory.GVBASIC -> {
                val options = arrayOf(
                    context.getString(R.string.nor_export_bas_source),
                    context.getString(R.string.nor_export_bas_raw)
                )
                AlertDialog.Builder(context)
                    .setTitle(R.string.nor_export_format_title)
                    .setItems(options) { _, which ->
                        if (which == 0) {
                            val src = GvBasicCodec.detokenizeToSource(rawBytes)
                            onReady(
                                PreparedExport(
                                    suggestedFileName = entry.name,
                                    mimeType = "text/plain",
                                    bytes = src.toByteArray(Charsets.UTF_8)
                                )
                            )
                        } else {
                            onReady(
                                PreparedExport(
                                    suggestedFileName = entry.name,
                                    mimeType = "application/octet-stream",
                                    bytes = rawBytes
                                )
                            )
                        }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }

            NorFileCategory.BOOT_SKIN -> {
                val frames = SkiCodec.decodeSkiFrames(rawBytes)
                if (frames.isEmpty()) {
                    onReady(
                        PreparedExport(
                            suggestedFileName = entry.name,
                            mimeType = "application/octet-stream",
                            bytes = rawBytes
                        )
                    )
                    return
                }
                val options = arrayOf(
                    context.getString(R.string.nor_export_ski_png),
                    context.getString(R.string.nor_export_ski_raw)
                )
                AlertDialog.Builder(context)
                    .setTitle(R.string.nor_export_format_title)
                    .setItems(options) { _, which ->
                        if (which == 0) {
                            val crisp = SkiCodec.upscaleCrisp(frames[0].bitmap, scale = 4)
                            val pngOut = ByteArrayOutputStream()
                            crisp.compress(Bitmap.CompressFormat.PNG, 100, pngOut)
                            crisp.recycle()
                            frames.forEach { it.bitmap.recycle() }
                            val base = entry.name.substringBeforeLast('.', entry.name)
                            onReady(
                                PreparedExport(
                                    suggestedFileName = "$base.png",
                                    mimeType = "image/png",
                                    bytes = pngOut.toByteArray()
                                )
                            )
                        } else {
                            frames.forEach { it.bitmap.recycle() }
                            onReady(
                                PreparedExport(
                                    suggestedFileName = entry.name,
                                    mimeType = "application/octet-stream",
                                    bytes = rawBytes
                                )
                            )
                        }
                    }
                    .setOnCancelListener {
                        frames.forEach { it.bitmap.recycle() }
                    }
                    .setNegativeButton(R.string.cancel) { _, _ ->
                        frames.forEach { it.bitmap.recycle() }
                    }
                    .show()
            }

            NorFileCategory.APP_BIN,
            NorFileCategory.SYSTEM_OR_SAVE -> {
                onReady(
                    PreparedExport(
                        suggestedFileName = entry.name,
                        mimeType = "application/octet-stream",
                        bytes = rawBytes
                    )
                )
            }
        }
    }

    fun formatByteSize(bytes: Int): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "%.2f MB".format(bytes / (1024.0 * 1024.0))
    }
}
