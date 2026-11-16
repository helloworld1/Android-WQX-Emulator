package org.liberty.android.nc1020emu.nor

import android.content.DialogInterface
import android.content.SharedPreferences
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.Toolbar
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.navigation.Navigation
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.liberty.android.nc1020emu.MainFragment
import org.liberty.android.nc1020emu.R
import java.io.ByteArrayOutputStream

class NorBrowserFragment : Fragment() {

    private data class DirCrumb(val inode: Int, val name: String)

    private data class FolderTargetOption(
        val label: String,
        val existingInode: Int?,
        val createRootFolderName: String?
    )

    private val dirStack = ArrayList<DirCrumb>().apply {
        add(DirCrumb(NorRepository.ROOT_INODE, "/"))
    }

    private lateinit var preferences: SharedPreferences
    private lateinit var toolbar: Toolbar
    private lateinit var pathText: TextView
    private lateinit var storageProgress: LinearProgressIndicator
    private lateinit var storageText: TextView
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var fabUpload: ExtendedFloatingActionButton
    private lateinit var adapter: NorEntryAdapter

    private var pendingExportBytes: ByteArray? = null
    private var pendingFlsExport: Boolean = false

    private val saveStatesEnabled: Boolean
        get() = preferences.getBoolean(MainFragment.SAVE_STATES_KEY, true)

    private val currentDir: DirCrumb
        get() = dirStack.lastOrNull() ?: DirCrumb(NorRepository.ROOT_INODE, "/")

    private val uploadFilePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null && isAdded) {
            handlePickedUploadUri(uri)
        }
    }

    private val importFlsPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null && isAdded) {
            handleImportFlsUri(uri)
        }
    }

    private val exportDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri: Uri? ->
        val bytes = pendingExportBytes
        val wasFls = pendingFlsExport
        pendingExportBytes = null
        pendingFlsExport = false
        if (uri != null && bytes != null && isAdded) {
            writeBytesToUri(uri, bytes, if (wasFls) R.string.nor_fls_exported else R.string.nor_file_saved)
        }
    }

    private val backPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (dirStack.size > 1) {
                dirStack.removeAt(dirStack.lastIndex)
                refreshDirectory()
            } else {
                isEnabled = false
                Navigation.findNavController(requireView()).navigateUp()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            val savedInodes = savedInstanceState.getIntArray(STATE_STACK_INODES)
            val savedNames = savedInstanceState.getStringArrayList(STATE_STACK_NAMES)
            if (savedInodes != null && savedNames != null &&
                savedInodes.isNotEmpty() && savedInodes.size == savedNames.size
            ) {
                dirStack.clear()
                for (i in savedInodes.indices) {
                    dirStack.add(DirCrumb(savedInodes[i], savedNames[i]))
                }
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(this, backPressedCallback)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putIntArray(STATE_STACK_INODES, dirStack.map { it.inode }.toIntArray())
        outState.putStringArrayList(STATE_STACK_NAMES, ArrayList(dirStack.map { it.name }))
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.nor_browser_fragment, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        MainFragment.ensureSessionInitialized(requireContext())
        preferences = PreferenceManager.getDefaultSharedPreferences(requireContext())

        toolbar = view.findViewById(R.id.toolbar)
        pathText = view.findViewById(R.id.path_text)
        storageProgress = view.findViewById(R.id.storage_progress)
        storageText = view.findViewById(R.id.storage_text)
        recyclerView = view.findViewById(R.id.nor_recycler_view)
        emptyText = view.findViewById(R.id.empty_text)
        fabUpload = view.findViewById(R.id.fab_upload)

        toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        toolbar.setNavigationOnClickListener {
            if (dirStack.size > 1) {
                dirStack.removeAt(dirStack.lastIndex)
                refreshDirectory()
            } else {
                Navigation.findNavController(view).navigateUp()
            }
        }
        toolbar.inflateMenu(R.menu.nor_browser_menu)
        toolbar.setOnMenuItemClickListener { item: MenuItem ->
            when (item.itemId) {
                R.id.action_new_folder -> {
                    showCreateFolderDialog()
                    true
                }
                R.id.action_create_standard_dirs -> {
                    createStandardDirectories()
                    true
                }
                R.id.action_export_fls -> {
                    val fls = NorRepository.exportFullImage()
                    if (fls != null) {
                        pendingExportBytes = fls
                        pendingFlsExport = true
                        exportDocumentLauncher.launch("nc1020_nor_backup.nor")
                    } else {
                        showError(NorRepository.FS_ERR_CORRUPT)
                    }
                    true
                }
                R.id.action_import_fls -> {
                    AlertDialog.Builder(requireContext())
                        .setTitle(R.string.nor_import_fls_confirm_title)
                        .setMessage(R.string.nor_import_fls_confirm_msg)
                        .setPositiveButton(R.string.yes) { _, _ ->
                            importFlsPicker.launch(arrayOf("*/*"))
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                    true
                }
                else -> false
            }
        }

        val headerContainer: View = view.findViewById(R.id.header_container)
        val initTbLeft = toolbar.paddingLeft
        val initTbRight = toolbar.paddingRight
        val initTbBottom = toolbar.paddingBottom
        val initHdrLeft = headerContainer.paddingLeft
        val initHdrTop = headerContainer.paddingTop
        val initHdrRight = headerContainer.paddingRight
        val initHdrBottom = headerContainer.paddingBottom
        val initRvLeft = recyclerView.paddingLeft
        val initRvTop = recyclerView.paddingTop
        val initRvRight = recyclerView.paddingRight
        val initRvBottom = recyclerView.paddingBottom
        val fabLp = fabUpload.layoutParams as CoordinatorLayout.LayoutParams
        val initFabRight = fabLp.rightMargin
        val initFabBottom = fabLp.bottomMargin

        ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            toolbar.setPadding(initTbLeft + bars.left, bars.top, initTbRight + bars.right, initTbBottom)
            headerContainer.setPadding(
                initHdrLeft + bars.left,
                initHdrTop,
                initHdrRight + bars.right,
                initHdrBottom
            )
            recyclerView.setPadding(
                initRvLeft + bars.left,
                initRvTop,
                initRvRight + bars.right,
                initRvBottom + bars.bottom
            )
            fabUpload.layoutParams = (fabUpload.layoutParams as CoordinatorLayout.LayoutParams).apply {
                rightMargin = initFabRight + bars.right
                bottomMargin = initFabBottom + bars.bottom
            }
            insets
        }

        adapter = NorEntryAdapter(
            onParentClicked = {
                if (dirStack.size > 1) {
                    dirStack.removeAt(dirStack.lastIndex)
                    refreshDirectory()
                }
            },
            onEntryClicked = { entry ->
                if (entry.isDirectory) {
                    dirStack.add(DirCrumb(entry.inode, entry.name))
                    refreshDirectory()
                } else {
                    openFileViewer(entry)
                }
            },
            onEntryMenuAction = { entry, action ->
                when (action) {
                    RowAction.VIEW -> openFileViewer(entry)
                    RowAction.DOWNLOAD -> startDownloadFile(entry)
                    RowAction.RENAME -> showRenameDialog(entry)
                    RowAction.DELETE -> showDeleteDialog(entry)
                }
            }
        )

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.addItemDecoration(
            DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL)
        )
        recyclerView.adapter = adapter

        fabUpload.setOnClickListener {
            uploadFilePicker.launch(arrayOf("*/*"))
        }

        refreshDirectory()
    }

    override fun onResume() {
        super.onResume()
        refreshDirectory()
    }

    private fun refreshDirectory() {
        if (!isAdded) return
        backPressedCallback.isEnabled = dirStack.size > 1

        val info = NorRepository.getStorageInfo()
        if (info == null) {
            storageText.setText(R.string.nor_err_corrupt)
            storageProgress.progress = 0
            adapter.submit(showParent = false, entries = emptyList())
            emptyText.visibility = View.VISIBLE
            return
        }

        val usedKb = info.userUsedBytes / 1024
        val totalKb = info.userTotalBytes / 1024
        val freeKb = info.userAvailableBytes / 1024
        storageProgress.max = info.userTotalBlocks.coerceAtLeast(1)
        storageProgress.progress = info.userUsedBlocks.coerceIn(0, storageProgress.max)
        storageText.text = getString(
            R.string.nor_storage_summary,
            usedKb,
            totalKb,
            freeKb,
            info.availableBlocks
        )

        var entries = NorRepository.listDirectory(currentDir.inode)
        if (entries == null && dirStack.size > 1) {
            dirStack.clear()
            dirStack.add(DirCrumb(NorRepository.ROOT_INODE, "/"))
            backPressedCallback.isEnabled = false
            entries = NorRepository.listDirectory(NorRepository.ROOT_INODE)
        }
        val safeEntries = entries ?: emptyList()

        pathText.text = buildCurrentPathString()
        val showParent = dirStack.size > 1
        adapter.submit(showParent = showParent, entries = safeEntries)
        emptyText.visibility = if (!showParent && safeEntries.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun buildCurrentPathString(): String {
        if (dirStack.size <= 1) return "/"
        return "/" + dirStack.drop(1).joinToString("/") { it.name }
    }

    private fun openFileViewer(entry: NorEntry) {
        Navigation.findNavController(requireView()).navigate(
            R.id.action_norBrowserFragment_to_norFileViewerFragment,
            NorFileViewerFragment.createBundle(entry)
        )
    }

    private fun startDownloadFile(entry: NorEntry) {
        val rawBytes = NorRepository.readFile(entry)
        if (rawBytes == null) {
            showError(NorRepository.FS_ERR_NOT_FOUND)
            return
        }
        NorExportHelper.promptAndPrepareExport(requireContext(), entry, rawBytes) { prep ->
            pendingExportBytes = prep.bytes
            pendingFlsExport = false
            exportDocumentLauncher.launch(prep.suggestedFileName)
        }
    }

    private fun createStandardDirectories() {
        guardOpenFcbIfNeeded {
            val stdDirs = listOf(
                NorEntry.DIR_APPLICATIONS,
                NorEntry.DIR_TEXT_FILES,
                NorEntry.DIR_GVBASIC,
                NorEntry.DIR_BOOT_SCREENS,
                NorEntry.DIR_PERSONAL_DOCS
            )
            for (folder in stdDirs) {
                NorRepository.ensureRootSubdirectory(folder, saveStatesEnabled)
            }
            Toast.makeText(requireContext(), R.string.nor_standard_dirs_created, Toast.LENGTH_SHORT).show()
            refreshDirectory()
        }
    }

    private fun showCreateFolderDialog() {
        showSingleNameDialog(
            titleRes = R.string.nor_new_folder,
            positiveRes = R.string.nor_create,
            initialName = ""
        ) { folderName ->
            guardOpenFcbIfNeeded {
                val (rc, _) = NorRepository.createDirectory(currentDir.inode, folderName, saveStatesEnabled)
                if (rc == NorRepository.FS_OK) {
                    refreshDirectory()
                } else {
                    showError(rc)
                }
            }
        }
    }

    private fun showRenameDialog(entry: NorEntry) {
        showSingleNameDialog(
            titleRes = R.string.nor_rename,
            positiveRes = R.string.nor_save,
            initialName = entry.name
        ) { newName ->
            guardOpenFcbIfNeeded {
                val rc = NorRepository.renameEntry(currentDir.inode, entry.inode, newName, saveStatesEnabled)
                if (rc == NorRepository.FS_OK) {
                    refreshDirectory()
                } else {
                    showError(rc)
                }
            }
        }
    }

    private fun showSingleNameDialog(
        titleRes: Int,
        positiveRes: Int,
        initialName: String,
        onConfirmed: (String) -> Unit
    ) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_nor_upload, null)
        val nameLayout: TextInputLayout = dialogView.findViewById(R.id.upload_name_layout)
        val nameInput: TextInputEditText = dialogView.findViewById(R.id.upload_name_input)
        val byteCounter: TextView = dialogView.findViewById(R.id.upload_byte_counter)
        dialogView.findViewById<View>(R.id.upload_folder_spinner).visibility = View.GONE
        // Hide the "Target WQX folder" label right above the spinner
        val parentLinear = nameLayout.parent as ViewGroup
        if (parentLinear.childCount > 2) {
            parentLinear.getChildAt(2).visibility = View.GONE
        }

        nameInput.setText(initialName)
        nameInput.setSelection(initialName.length)

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(titleRes)
            .setView(dialogView)
            .setPositiveButton(positiveRes, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            val posBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            fun updateState() {
                val text = nameInput.text?.toString().orEmpty()
                val validation = WqxTextCodec.validateWqxName(text)
                val byteLen = WqxTextCodec.gbkByteLength(text)
                byteCounter.text = getString(R.string.nor_byte_counter, byteLen)
                posBtn.isEnabled = validation is WqxTextCodec.NameValidation.Valid
                nameLayout.error = if (text.isNotEmpty() && validation is WqxTextCodec.NameValidation.Invalid) {
                    getString(R.string.nor_err_name_invalid)
                } else {
                    null
                }
            }
            nameInput.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) = updateState()
            })
            updateState()
            posBtn.setOnClickListener {
                val text = nameInput.text?.toString().orEmpty().trim()
                if (WqxTextCodec.validateWqxName(text) is WqxTextCodec.NameValidation.Valid) {
                    dialog.dismiss()
                    onConfirmed(text)
                }
            }
        }
        dialog.show()
    }

    private fun showDeleteDialog(entry: NorEntry) {
        val msg = if (entry.isSystem || entry.isReadOnly) {
            getString(R.string.nor_delete_system_msg, entry.name)
        } else {
            getString(R.string.nor_delete_msg, entry.name)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.nor_delete_title)
            .setMessage(msg)
            .setPositiveButton(R.string.nor_delete) { _, _ ->
                guardOpenFcbIfNeeded {
                    val rc = NorRepository.deleteEntry(currentDir.inode, entry.inode, saveStatesEnabled)
                    if (rc == NorRepository.FS_OK) {
                        refreshDirectory()
                    } else {
                        showError(rc)
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun handleImportFlsUri(uri: Uri) {
        val bytes = readUriBytesBounded(uri, maxBytes = NorStorageInfo.NOR_IMAGE_SIZE + 16)
        if (bytes == null || bytes.size != NorStorageInfo.NOR_IMAGE_SIZE) {
            Toast.makeText(requireContext(), R.string.nor_fls_invalid, Toast.LENGTH_LONG).show()
            return
        }
        val ok = NorRepository.importFullImage(bytes, saveStatesEnabled)
        if (ok) {
            dirStack.clear()
            dirStack.add(DirCrumb(NorRepository.ROOT_INODE, "/"))
            Toast.makeText(requireContext(), R.string.nor_fls_imported, Toast.LENGTH_SHORT).show()
            refreshDirectory()
        } else {
            Toast.makeText(requireContext(), R.string.nor_fls_invalid, Toast.LENGTH_LONG).show()
        }
    }

    private fun handlePickedUploadUri(uri: Uri) {
        val rawDisplayName = queryDisplayName(uri) ?: uri.lastPathSegment ?: "file"
        val dot = rawDisplayName.lastIndexOf('.')
        val extLower = if (dot >= 0 && dot < rawDisplayName.length - 1) {
            rawDisplayName.substring(dot + 1).lowercase()
        } else {
            ""
        }
        val isImageImport = NorEntry.isImageExtension(extLower)

        var decodedSourceBitmap: Bitmap? = null
        val rawBytes: ByteArray
        if (isImageImport) {
            decodedSourceBitmap = decodeSampledBitmapFromUri(uri, reqWidth = 640, reqHeight = 320)
            if (decodedSourceBitmap == null) {
                Toast.makeText(requireContext(), R.string.nor_err_image_decode, Toast.LENGTH_LONG).show()
                return
            }
            rawBytes = ByteArray(0)
        } else {
            val loaded = readUriBytesBounded(uri, maxBytes = 900 * 1024)
            if (loaded == null) {
                Toast.makeText(requireContext(), R.string.nor_err_file_too_large, Toast.LENGTH_LONG).show()
                return
            }
            rawBytes = loaded
        }

        showUploadDialog(
            rawDisplayName = rawDisplayName,
            extLower = extLower,
            isImageImport = isImageImport,
            rawBytes = rawBytes,
            sourceBitmap = decodedSourceBitmap
        )
    }

    private fun showUploadDialog(
        rawDisplayName: String,
        extLower: String,
        isImageImport: Boolean,
        rawBytes: ByteArray,
        sourceBitmap: Bitmap?
    ) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_nor_upload, null)
        val nameLayout: TextInputLayout = dialogView.findViewById(R.id.upload_name_layout)
        val nameInput: TextInputEditText = dialogView.findViewById(R.id.upload_name_input)
        val byteCounter: TextView = dialogView.findViewById(R.id.upload_byte_counter)
        val folderSpinner: Spinner = dialogView.findViewById(R.id.upload_folder_spinner)
        val infoText: TextView = dialogView.findViewById(R.id.upload_info_text)
        val skiPreview: ImageView = dialogView.findViewById(R.id.upload_ski_preview)
        val optPrimary: CheckBox = dialogView.findViewById(R.id.upload_opt_primary)
        val optSecondary: CheckBox = dialogView.findViewById(R.id.upload_opt_secondary)
        val optTertiary: CheckBox = dialogView.findViewById(R.id.upload_opt_tertiary)

        val proposedName = WqxTextCodec.proposeWqxFileName(
            rawDisplayName,
            forceExtension = if (isImageImport) "ski" else null
        )
        nameInput.setText(proposedName)
        nameInput.setSelection(proposedName.length)

        val folderOptions = buildUploadFolderOptions(extLower)
        folderSpinner.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            folderOptions.map { it.label }
        )
        folderSpinner.setSelection(0)

        var currentPreviewScaled: Bitmap? = null
        var cachedSkiBytes: ByteArray? = null

        fun refreshSkiPreview() {
            val src = sourceBitmap ?: return
            val (skiData, rawBmp) = SkiCodec.convertImageToSki(
                source = src,
                cropCenter = optSecondary.isChecked,
                dither = optPrimary.isChecked,
                invert = optTertiary.isChecked
            )
            cachedSkiBytes = skiData
            val scaled = SkiCodec.upscaleCrisp(rawBmp, scale = 4)
            rawBmp.recycle()
            val prev = currentPreviewScaled
            currentPreviewScaled = scaled
            skiPreview.setImageBitmap(scaled)
            prev?.recycle()
        }

        val effectiveCategory = if (isImageImport) {
            NorFileCategory.BOOT_SKIN
        } else {
            NorEntry.categoryForExtension(extLower)
        }

        val binAnalysis = if (effectiveCategory == NorFileCategory.APP_BIN) {
            BinInspector.analyze(rawBytes)
        } else null

        when {
            isImageImport -> {
                skiPreview.visibility = View.VISIBLE
                optPrimary.visibility = View.VISIBLE
                optPrimary.setText(R.string.nor_opt_ski_dither)
                optPrimary.isChecked = true

                optSecondary.visibility = View.VISIBLE
                optSecondary.setText(R.string.nor_opt_ski_crop)
                optSecondary.isChecked = true

                optTertiary.visibility = View.VISIBLE
                optTertiary.setText(R.string.nor_opt_ski_invert)
                optTertiary.isChecked = false

                val listener = { _: View -> refreshSkiPreview() }
                optPrimary.setOnClickListener(listener)
                optSecondary.setOnClickListener(listener)
                optTertiary.setOnClickListener(listener)
                refreshSkiPreview()
            }

            effectiveCategory == NorFileCategory.TEXT_DOC -> {
                if (!WqxTextCodec.hasWqxBookHeader(rawBytes)) {
                    optPrimary.visibility = View.VISIBLE
                    optPrimary.setText(R.string.nor_opt_convert_utf8)
                    optPrimary.isChecked = true
                }
            }

            effectiveCategory == NorFileCategory.GVBASIC -> {
                val alreadyTokenized = GvBasicCodec.isTokenizedBas(rawBytes)
                if (!alreadyTokenized) {
                    optPrimary.visibility = View.VISIBLE
                    optPrimary.setText(R.string.nor_opt_tokenize_bas)
                    optPrimary.isChecked = true
                }
            }

            effectiveCategory == NorFileCategory.APP_BIN && binAnalysis != null -> {
                infoText.visibility = View.VISIBLE
                infoText.text = binAnalysis.summaryLines.joinToString("\n")
                if (binAnalysis.hasFtpLinkWrapper) {
                    optPrimary.visibility = View.VISIBLE
                    optPrimary.setText(R.string.nor_opt_strip_bin_header)
                    optPrimary.isChecked = true
                }
            }
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.nor_upload_title)
            .setView(dialogView)
            .setPositiveButton(R.string.nor_upload, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setOnDismissListener {
            currentPreviewScaled?.recycle()
            currentPreviewScaled = null
            sourceBitmap?.recycle()
        }

        dialog.setOnShowListener {
            val posBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            fun updateNameValidation() {
                val text = nameInput.text?.toString().orEmpty()
                val validation = WqxTextCodec.validateWqxName(text)
                val byteLen = WqxTextCodec.gbkByteLength(text)
                byteCounter.text = getString(R.string.nor_byte_counter, byteLen)
                posBtn.isEnabled = validation is WqxTextCodec.NameValidation.Valid
                nameLayout.error = if (text.isNotEmpty() && validation is WqxTextCodec.NameValidation.Invalid) {
                    getString(R.string.nor_err_name_invalid)
                } else {
                    null
                }
            }
            nameInput.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) = updateNameValidation()
            })
            updateNameValidation()

            posBtn.setOnClickListener {
                val targetName = nameInput.text?.toString().orEmpty().trim()
                if (WqxTextCodec.validateWqxName(targetName) !is WqxTextCodec.NameValidation.Valid) {
                    return@setOnClickListener
                }
                val selectedFolder = folderOptions[folderSpinner.selectedItemPosition]

                val finalPayload: ByteArray = when {
                    isImageImport -> cachedSkiBytes ?: return@setOnClickListener
                    effectiveCategory == NorFileCategory.TEXT_DOC -> {
                        val formatForWqx = optPrimary.visibility == View.VISIBLE && optPrimary.isChecked
                        WqxTextCodec.prepareTextForUpload(rawBytes, formatForWqx).gbkBytes
                    }
                    effectiveCategory == NorFileCategory.GVBASIC -> {
                        when (val res = GvBasicCodec.prepareBasForUpload(rawBytes, optPrimary.isChecked)) {
                            is GvBasicCodec.BasUploadResult.Success -> res.tokenizedBytes
                            is GvBasicCodec.BasUploadResult.Error -> {
                                Toast.makeText(requireContext(), res.message, Toast.LENGTH_LONG).show()
                                return@setOnClickListener
                            }
                        }
                    }
                    effectiveCategory == NorFileCategory.APP_BIN && binAnalysis?.hasFtpLinkWrapper == true -> {
                        if (optPrimary.isChecked) binAnalysis.unwrapIfNeeded(rawBytes) else rawBytes
                    }
                    else -> rawBytes
                }

                dialog.dismiss()
                executeUpload(selectedFolder, targetName, finalPayload)
            }
        }
        dialog.show()
    }

    private fun buildUploadFolderOptions(extLower: String): List<FolderTargetOption> {
        val options = ArrayList<FolderTargetOption>()
        val officialFolderName = NorEntry.officialRootFolderForExtension(extLower)
        val rootEntries = NorRepository.listDirectory(NorRepository.ROOT_INODE).orEmpty()
        val rootDirsByName = rootEntries.filter { it.isDirectory }.associateBy { it.name }

        if (officialFolderName != null) {
            val existing = rootDirsByName[officialFolderName]
            options.add(
                FolderTargetOption(
                    label = getString(R.string.nor_folder_official_suffix, "/$officialFolderName"),
                    existingInode = existing?.inode,
                    createRootFolderName = officialFolderName
                )
            )
        }

        val currentPath = buildCurrentPathString()
        val currentAlreadyListed = officialFolderName != null &&
            dirStack.size == 2 &&
            currentDir.name == officialFolderName
        if (!currentAlreadyListed) {
            val curOption = FolderTargetOption(
                label = getString(R.string.nor_folder_current_suffix, currentPath),
                existingInode = currentDir.inode,
                createRootFolderName = null
            )
            if (officialFolderName == null) {
                options.add(0, curOption)
            } else {
                options.add(curOption)
            }
        }

        if (currentDir.inode != NorRepository.ROOT_INODE) {
            options.add(
                FolderTargetOption(
                    label = "/",
                    existingInode = NorRepository.ROOT_INODE,
                    createRootFolderName = null
                )
            )
        }

        for (dir in rootEntries.filter { it.isDirectory }) {
            if (dir.name == officialFolderName) continue
            if (dirStack.size == 2 && dir.inode == currentDir.inode) continue
            options.add(
                FolderTargetOption(
                    label = "/${dir.name}",
                    existingInode = dir.inode,
                    createRootFolderName = null
                )
            )
        }
        return options
    }

    private fun executeUpload(
        folderOption: FolderTargetOption,
        fileName: String,
        payload: ByteArray
    ) {
        guardOpenFcbIfNeeded {
            val targetDirInode: Int
            val targetFolderPath: String
            if (folderOption.createRootFolderName != null) {
                val (rc, ino) = NorRepository.ensureRootSubdirectory(
                    folderOption.createRootFolderName,
                    saveStatesEnabled
                )
                if (rc != NorRepository.FS_OK) {
                    showError(rc)
                    return@guardOpenFcbIfNeeded
                }
                targetDirInode = ino
                targetFolderPath = "/${folderOption.createRootFolderName}"
            } else {
                targetDirInode = folderOption.existingInode ?: currentDir.inode
                targetFolderPath = folderOption.label.substringBefore(" (").substringBefore("（")
            }

            val (rc, _) = NorRepository.writeFile(
                dirInode = targetDirInode,
                fileName = fileName,
                data = payload,
                overwrite = false,
                saveStates = saveStatesEnabled
            )
            if (rc == NorRepository.FS_OK) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.nor_file_uploaded, "$targetFolderPath/$fileName".replace("//", "/")),
                    Toast.LENGTH_SHORT
                ).show()
                refreshDirectory()
            } else if (rc == NorRepository.FS_ERR_EXISTS) {
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.nor_overwrite_title)
                    .setMessage(getString(R.string.nor_overwrite_msg, fileName, targetFolderPath))
                    .setPositiveButton(R.string.nor_overwrite) { _, _ ->
                        val (owRc, _) = NorRepository.writeFile(
                            dirInode = targetDirInode,
                            fileName = fileName,
                            data = payload,
                            overwrite = true,
                            saveStates = saveStatesEnabled
                        )
                        if (owRc == NorRepository.FS_OK) {
                            Toast.makeText(
                                requireContext(),
                                getString(R.string.nor_file_uploaded, "$targetFolderPath/$fileName".replace("//", "/")),
                                Toast.LENGTH_SHORT
                            ).show()
                            refreshDirectory()
                        } else {
                            showError(owRc)
                        }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            } else {
                showError(rc)
            }
        }
    }

    private fun guardOpenFcbIfNeeded(action: () -> Unit) {
        val info = NorRepository.getStorageInfo()
        if (info?.hasOpenFcb == true) {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.nor_file_browser)
                .setMessage(R.string.nor_open_fcb_warn)
                .setPositiveButton(R.string.yes) { _: DialogInterface?, _: Int -> action() }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } else {
            action()
        }
    }

    private fun showError(statusCode: Int) {
        val msgRes = when (statusCode) {
            NorRepository.FS_ERR_NO_SPACE -> R.string.nor_err_no_space
            NorRepository.FS_ERR_NAME_INVALID -> R.string.nor_err_name_invalid
            NorRepository.FS_ERR_EXISTS -> R.string.nor_err_exists
            NorRepository.FS_ERR_NOT_EMPTY -> R.string.nor_err_not_empty
            NorRepository.FS_ERR_DIR_FULL -> R.string.nor_err_dir_full
            else -> R.string.nor_err_corrupt
        }
        Toast.makeText(requireContext(), msgRes, Toast.LENGTH_LONG).show()
    }

    private fun queryDisplayName(uri: Uri): String? {
        var cursor: Cursor? = null
        return try {
            cursor = requireContext().contentResolver.query(uri, null, null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) cursor.getString(idx) else null
            } else null
        } catch (_: Exception) {
            null
        } finally {
            cursor?.close()
        }
    }

    private fun readUriBytesBounded(uri: Uri, maxBytes: Int): ByteArray? {
        return try {
            requireContext().contentResolver.openInputStream(uri)?.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                var total = 0
                var n: Int
                while (input.read(buf).also { n = it } != -1) {
                    total += n
                    if (total > maxBytes) return null
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeSampledBitmapFromUri(uri: Uri, reqWidth: Int, reqHeight: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            requireContext().contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sampleSize = 1
            while (bounds.outWidth / (sampleSize * 2) >= reqWidth &&
                bounds.outHeight / (sampleSize * 2) >= reqHeight
            ) {
                sampleSize *= 2
            }
            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            requireContext().contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, decodeOpts)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun writeBytesToUri(uri: Uri, bytes: ByteArray, successToastRes: Int) {
        try {
            requireContext().contentResolver.openOutputStream(uri, "wt")?.use { out ->
                out.write(bytes)
                out.flush()
            } ?: requireContext().contentResolver.openOutputStream(uri)?.use { out ->
                out.write(bytes)
                out.flush()
            }
            Toast.makeText(requireContext(), successToastRes, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), e.localizedMessage ?: "Export failed", Toast.LENGTH_LONG).show()
        }
    }

    private enum class RowAction {
        VIEW,
        DOWNLOAD,
        RENAME,
        DELETE
    }

    private inner class NorEntryAdapter(
        private val onParentClicked: () -> Unit,
        private val onEntryClicked: (NorEntry) -> Unit,
        private val onEntryMenuAction: (NorEntry, RowAction) -> Unit
    ) : RecyclerView.Adapter<NorEntryAdapter.EntryViewHolder>() {

        private var showParentRow = false
        private var items: List<NorEntry> = emptyList()

        fun submit(showParent: Boolean, entries: List<NorEntry>) {
            showParentRow = showParent
            items = entries
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = items.size + (if (showParentRow) 1 else 0)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EntryViewHolder {
            val row = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_nor_entry, parent, false)
            return EntryViewHolder(row)
        }

        override fun onBindViewHolder(holder: EntryViewHolder, position: Int) {
            if (showParentRow && position == 0) {
                holder.bindParentRow()
                return
            }
            val entry = items[position - (if (showParentRow) 1 else 0)]
            holder.bindEntry(entry)
        }

        inner class EntryViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val iconView: ImageView = itemView.findViewById(R.id.entry_icon)
            private val nameView: TextView = itemView.findViewById(R.id.entry_name)
            private val badgeView: TextView = itemView.findViewById(R.id.entry_badge)
            private val metaView: TextView = itemView.findViewById(R.id.entry_meta)
            private val moreBtn: ImageButton = itemView.findViewById(R.id.entry_more_button)

            fun bindParentRow() {
                iconView.setImageResource(R.drawable.ic_nor_folder)
                nameView.setText(R.string.nor_parent_folder)
                badgeView.visibility = View.GONE
                metaView.text = if (dirStack.size <= 2) "/" else "/" + dirStack.drop(1).dropLast(1).joinToString("/") { it.name }
                moreBtn.visibility = View.INVISIBLE
                itemView.setOnClickListener { onParentClicked() }
            }

            fun bindEntry(entry: NorEntry) {
                iconView.setImageResource(
                    if (entry.isDirectory) R.drawable.ic_nor_folder else R.drawable.ic_nor_file
                )
                nameView.text = entry.name

                val badgeLabel = buildBadgeLabel(entry)
                if (badgeLabel != null) {
                    badgeView.visibility = View.VISIBLE
                    badgeView.text = badgeLabel
                } else {
                    badgeView.visibility = View.GONE
                }

                metaView.text = if (entry.isDirectory) {
                    getString(R.string.nor_entry_folder_meta, entry.formattedDate)
                } else {
                    getString(
                        R.string.nor_entry_file_meta,
                        NorExportHelper.formatByteSize(entry.size),
                        entry.blocks,
                        entry.formattedDate
                    )
                }

                moreBtn.visibility = View.VISIBLE
                moreBtn.setOnClickListener { anchor ->
                    val popup = PopupMenu(anchor.context, anchor)
                    if (!entry.isDirectory) {
                        popup.menu.add(0, MENU_VIEW, 0, R.string.nor_view)
                        popup.menu.add(0, MENU_DOWNLOAD, 1, R.string.nor_download)
                    }
                    popup.menu.add(0, MENU_RENAME, 2, R.string.nor_rename)
                    popup.menu.add(0, MENU_DELETE, 3, R.string.nor_delete)
                    popup.setOnMenuItemClickListener { item ->
                        when (item.itemId) {
                            MENU_VIEW -> onEntryMenuAction(entry, RowAction.VIEW)
                            MENU_DOWNLOAD -> onEntryMenuAction(entry, RowAction.DOWNLOAD)
                            MENU_RENAME -> onEntryMenuAction(entry, RowAction.RENAME)
                            MENU_DELETE -> onEntryMenuAction(entry, RowAction.DELETE)
                        }
                        true
                    }
                    popup.show()
                }

                itemView.setOnClickListener { onEntryClicked(entry) }
            }

            private fun buildBadgeLabel(entry: NorEntry): String? {
                val tags = ArrayList<String>(2)
                if (!entry.isDirectory && entry.extensionLower.isNotEmpty()) {
                    tags.add(entry.extensionLower.uppercase())
                }
                if (entry.isSystem) tags.add("SYS")
                else if (entry.isReadOnly) tags.add("RO")
                return if (tags.isEmpty()) null else tags.joinToString(" · ")
            }
        }
    }

    companion object {
        private const val STATE_STACK_INODES = "nor_stack_inodes"
        private const val STATE_STACK_NAMES = "nor_stack_names"
        private const val MENU_VIEW = 1
        private const val MENU_DOWNLOAD = 2
        private const val MENU_RENAME = 3
        private const val MENU_DELETE = 4
    }
}
