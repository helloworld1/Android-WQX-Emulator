package org.liberty.android.nc1020emu.nor

import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.navigation.Navigation
import androidx.navigation.ui.NavigationUI
import com.google.android.material.chip.Chip
import org.liberty.android.nc1020emu.MainFragment
import org.liberty.android.nc1020emu.NC1020JNI
import org.liberty.android.nc1020emu.R

class NorFileViewerFragment : Fragment() {
    private var fileBytes: ByteArray = ByteArray(0)
    private lateinit var currentEntry: NorEntry
    private var skiFrames: List<SkiCodec.SkiFrame> = emptyList()
    private var scaledSkiBitmaps: MutableList<android.graphics.Bitmap> = ArrayList()
    private var currentFrameIndex = 0
    private val animHandler = Handler(Looper.getMainLooper())
    private var pendingExportBytes: ByteArray? = null

    private lateinit var skiPreviewContainer: View
    private lateinit var skiImageView: ImageView
    private lateinit var skiFrameLabel: TextView
    private lateinit var viewerTextContent: TextView
    private lateinit var chipDecoded: Chip
    private lateinit var chipHex: Chip

    private val createDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri: Uri? ->
        val bytes = pendingExportBytes
        pendingExportBytes = null
        if (uri != null && bytes != null) {
            writeBytesToUri(uri, bytes)
        }
    }

    private val frameTickRunnable = object : Runnable {
        override fun run() {
            if (!isAdded || scaledSkiBitmaps.size <= 1 || !chipDecoded.isChecked) return
            currentFrameIndex = (currentFrameIndex + 1) % scaledSkiBitmaps.size
            skiImageView.setImageBitmap(scaledSkiBitmaps[currentFrameIndex])
            skiFrameLabel.text = getString(
                R.string.nor_ski_frame_info,
                currentFrameIndex + 1,
                scaledSkiBitmaps.size
            )
            val delay = skiFrames.getOrNull(currentFrameIndex)?.delayMs?.toLong() ?: 300L
            animHandler.postDelayed(this, delay)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.nor_file_viewer_fragment, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        MainFragment.ensureSessionInitialized(requireContext())

        val args = requireArguments()
        val name = args.getString(ARG_NAME, "")
        val inode = args.getInt(ARG_INODE, 0)
        val size = args.getInt(ARG_SIZE, 0)
        val attr = args.getInt(ARG_ATTR, 0xC8)
        val attr2 = args.getInt(ARG_ATTR2, 0xDF)
        val blocks = args.getInt(ARG_BLOCKS, 1)
        val year = args.getInt(ARG_YEAR, 2002)
        val month = args.getInt(ARG_MONTH, 1)
        val day = args.getInt(ARG_DAY, 1)

        currentEntry = NorEntry(
            name = name,
            nameGbk = name.toByteArray(WqxTextCodec.GBK_CHARSET),
            attr = attr,
            attr2 = attr2,
            year = year,
            month = month,
            day = day,
            size = size,
            inode = inode,
            blocks = blocks
        )

        val toolbar: Toolbar = view.findViewById(R.id.toolbar)
        val navController = Navigation.findNavController(view)
        NavigationUI.setupWithNavController(toolbar, navController)
        toolbar.title = currentEntry.name
        toolbar.inflateMenu(R.menu.nor_viewer_menu)
        toolbar.setOnMenuItemClickListener { item: MenuItem ->
            if (item.itemId == R.id.action_viewer_download) {
                NorExportHelper.promptAndPrepareExport(requireContext(), currentEntry, fileBytes) { prep ->
                    pendingExportBytes = prep.bytes
                    createDocumentLauncher.launch(prep.suggestedFileName)
                }
                true
            } else {
                false
            }
        }

        val scrollView: ScrollView = view.findViewById(R.id.viewer_scroll_view)
        val metaBar: View = view.findViewById(R.id.viewer_meta_bar)
        val initTbLeft = toolbar.paddingLeft
        val initTbRight = toolbar.paddingRight
        val initTbBottom = toolbar.paddingBottom
        val initMetaLeft = metaBar.paddingLeft
        val initMetaTop = metaBar.paddingTop
        val initMetaRight = metaBar.paddingRight
        val initMetaBottom = metaBar.paddingBottom
        val initScrollLeft = scrollView.paddingLeft
        val initScrollTop = scrollView.paddingTop
        val initScrollRight = scrollView.paddingRight
        val initScrollBottom = scrollView.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            toolbar.setPadding(initTbLeft + bars.left, bars.top, initTbRight + bars.right, initTbBottom)
            metaBar.setPadding(
                initMetaLeft + bars.left,
                initMetaTop,
                initMetaRight + bars.right,
                initMetaBottom
            )
            scrollView.setPadding(
                initScrollLeft + bars.left,
                initScrollTop,
                initScrollRight + bars.right,
                initScrollBottom + bars.bottom
            )
            insets
        }

        val metaText: TextView = view.findViewById(R.id.viewer_meta_text)
        metaText.text = getString(
            R.string.nor_entry_file_meta,
            NorExportHelper.formatByteSize(currentEntry.size),
            currentEntry.blocks,
            currentEntry.formattedDate
        )

        skiPreviewContainer = view.findViewById(R.id.ski_preview_container)
        skiImageView = view.findViewById(R.id.ski_image_view)
        skiFrameLabel = view.findViewById(R.id.ski_frame_label)
        viewerTextContent = view.findViewById(R.id.viewer_text_content)
        chipDecoded = view.findViewById(R.id.chip_mode_decoded)
        chipHex = view.findViewById(R.id.chip_mode_hex)

        fileBytes = NC1020JNI.norReadFile(inode, size) ?: ByteArray(0)
        if (currentEntry.category == NorFileCategory.BOOT_SKIN) {
            skiFrames = SkiCodec.decodeSkiFrames(fileBytes)
            scaledSkiBitmaps = skiFrames.map { SkiCodec.upscaleCrisp(it.bitmap, scale = 4) }.toMutableList()
        }

        chipDecoded.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) renderContent(hexMode = false)
        }
        chipHex.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) renderContent(hexMode = true)
        }

        renderContent(hexMode = false)
    }

    private fun renderContent(hexMode: Boolean) {
        animHandler.removeCallbacks(frameTickRunnable)
        if (hexMode) {
            skiPreviewContainer.visibility = View.GONE
            viewerTextContent.visibility = View.VISIBLE
            viewerTextContent.typeface = Typeface.MONOSPACE
            viewerTextContent.textSize = 12f
            viewerTextContent.text = WqxTextCodec.formatHexDump(fileBytes)
            return
        }

        when (currentEntry.category) {
            NorFileCategory.TEXT_DOC -> {
                skiPreviewContainer.visibility = View.GONE
                viewerTextContent.visibility = View.VISIBLE
                viewerTextContent.typeface = Typeface.DEFAULT
                viewerTextContent.textSize = 15f
                viewerTextContent.text = WqxTextCodec.decodeGbk(fileBytes)
            }

            NorFileCategory.GVBASIC -> {
                skiPreviewContainer.visibility = View.GONE
                viewerTextContent.visibility = View.VISIBLE
                viewerTextContent.typeface = Typeface.MONOSPACE
                viewerTextContent.textSize = 13.5f
                viewerTextContent.text = GvBasicCodec.detokenizeToSource(fileBytes)
            }

            NorFileCategory.BOOT_SKIN -> {
                if (scaledSkiBitmaps.isNotEmpty()) {
                    skiPreviewContainer.visibility = View.VISIBLE
                    currentFrameIndex = 0
                    skiImageView.setImageBitmap(scaledSkiBitmaps[0])
                    skiFrameLabel.text = getString(
                        R.string.nor_ski_frame_info,
                        1,
                        scaledSkiBitmaps.size
                    )
                    viewerTextContent.visibility = View.GONE
                    if (scaledSkiBitmaps.size > 1) {
                        val delay = skiFrames.getOrNull(0)?.delayMs?.toLong() ?: 300L
                        animHandler.postDelayed(frameTickRunnable, delay)
                    }
                } else {
                    skiPreviewContainer.visibility = View.GONE
                    viewerTextContent.visibility = View.VISIBLE
                    viewerTextContent.typeface = Typeface.MONOSPACE
                    viewerTextContent.textSize = 12f
                    viewerTextContent.text = WqxTextCodec.formatHexDump(fileBytes)
                }
            }

            NorFileCategory.APP_BIN -> {
                skiPreviewContainer.visibility = View.GONE
                viewerTextContent.visibility = View.VISIBLE
                viewerTextContent.typeface = Typeface.MONOSPACE
                viewerTextContent.textSize = 12.5f
                val analysis = BinInspector.analyze(fileBytes)
                val strings = WqxTextCodec.extractReadableStrings(fileBytes, minChars = 3, maxOutputChars = 4000)
                val sb = StringBuilder()
                analysis.summaryLines.forEach { sb.append(it).append('\n') }
                if (strings.isNotEmpty()) {
                    sb.append("\n--- Embedded Strings ---\n").append(strings).append('\n')
                }
                sb.append("\n--- Header Hex Preview ---\n")
                sb.append(WqxTextCodec.formatHexDump(fileBytes, maxBytes = 1024))
                viewerTextContent.text = sb.toString()
            }

            NorFileCategory.SYSTEM_OR_SAVE -> {
                skiPreviewContainer.visibility = View.GONE
                viewerTextContent.visibility = View.VISIBLE
                viewerTextContent.typeface = Typeface.MONOSPACE
                viewerTextContent.textSize = 12.5f
                val strings = WqxTextCodec.extractReadableStrings(fileBytes, minChars = 2, maxOutputChars = 6000)
                val sb = StringBuilder()
                if (strings.isNotEmpty()) {
                    sb.append("--- Readable GB2312 / ASCII Strings ---\n")
                    sb.append(strings).append("\n\n")
                }
                sb.append("--- Hex / GB2312 View ---\n")
                sb.append(WqxTextCodec.formatHexDump(fileBytes, maxBytes = 8192))
                viewerTextContent.text = sb.toString()
            }
        }
    }

    private fun writeBytesToUri(uri: Uri, bytes: ByteArray) {
        try {
            requireContext().contentResolver.openOutputStream(uri, "wt")?.use { out ->
                out.write(bytes)
                out.flush()
            } ?: requireContext().contentResolver.openOutputStream(uri)?.use { out ->
                out.write(bytes)
                out.flush()
            }
            Toast.makeText(requireContext(), R.string.nor_file_saved, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), e.localizedMessage ?: "Export failed", Toast.LENGTH_LONG).show()
        }
    }

    override fun onPause() {
        super.onPause()
        animHandler.removeCallbacks(frameTickRunnable)
    }

    override fun onResume() {
        super.onResume()
        if (currentEntry.category == NorFileCategory.BOOT_SKIN &&
            scaledSkiBitmaps.size > 1 &&
            chipDecoded.isChecked
        ) {
            animHandler.removeCallbacks(frameTickRunnable)
            animHandler.postDelayed(frameTickRunnable, 300L)
        }
    }

    override fun onDestroyView() {
        animHandler.removeCallbacks(frameTickRunnable)
        scaledSkiBitmaps.forEach { it.recycle() }
        scaledSkiBitmaps.clear()
        skiFrames.forEach { it.bitmap.recycle() }
        skiFrames = emptyList()
        super.onDestroyView()
    }

    companion object {
        const val ARG_NAME = "name"
        const val ARG_INODE = "inode"
        const val ARG_SIZE = "size"
        const val ARG_ATTR = "attr"
        const val ARG_ATTR2 = "attr2"
        const val ARG_BLOCKS = "blocks"
        const val ARG_YEAR = "year"
        const val ARG_MONTH = "month"
        const val ARG_DAY = "day"

        fun createBundle(entry: NorEntry): Bundle = Bundle().apply {
            putString(ARG_NAME, entry.name)
            putInt(ARG_INODE, entry.inode)
            putInt(ARG_SIZE, entry.size)
            putInt(ARG_ATTR, entry.attr)
            putInt(ARG_ATTR2, entry.attr2)
            putInt(ARG_BLOCKS, entry.blocks)
            putInt(ARG_YEAR, entry.year)
            putInt(ARG_MONTH, entry.month)
            putInt(ARG_DAY, entry.day)
        }
    }
}
