// SPDX-FileCopyrightText: 2026 Ashish Yadav <mailtoashish693@gmail.com>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.noteeditor

import android.content.ClipDescription
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.EditText
import androidx.activity.result.ActivityResultLauncher
import androidx.annotation.VisibleForTesting
import androidx.core.os.BundleCompat
import androidx.lifecycle.lifecycleScope
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.MediaRegistration
import com.ichi2.anki.NoteEditorFragment
import com.ichi2.anki.R
import com.ichi2.anki.common.annotations.NeedsTest
import com.ichi2.anki.compat.CompatHelper.Companion.getSerializableCompat
import com.ichi2.anki.exception.MediaSizeLimitExceededException
import com.ichi2.anki.launchCatchingTask
import com.ichi2.anki.multimedia.MultimediaActionHandler
import com.ichi2.anki.multimedia.MultimediaActivityExtra
import com.ichi2.anki.multimedia.MultimediaResult
import com.ichi2.anki.multimediacard.fields.EFieldType
import com.ichi2.anki.multimediacard.fields.IField
import com.ichi2.anki.servicelayer.NoteService
import com.ichi2.anki.snackbar.showSnackbar
import com.ichi2.utils.ContentResolverUtil
import com.ichi2.utils.openInputStreamSafe
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File

/**
 * Owns the multimedia capture lifecycle for a [NoteEditorFragment].
 *
 * The [launcher] must already be registered on the fragment (an
 * `ActivityResultLauncher` can only be created during fragment initialisation);
 * its callback forwards to [handleActions] / [handleResult].
 */
internal class NoteEditorMultimediaController(
    private val fragment: NoteEditorFragment,
    private val launcher: ActivityResultLauncher<Intent>,
) {
    private var actionJob: Job? = null

    // Use the same HTML if the same image is pasted multiple times.
    private var pastedImageCache: HashMap<String, String> = HashMap()

    /**
     * Subscribes to the next [MultimediaActionHandler] emitted by the bottom sheet
     * and launches its capture screen against [fieldIndex]. Cancels any previously
     * pending subscription so rapid field switches don't stack.
     */
    fun handleActions(fieldIndex: Int) {
        actionJob?.cancel()
        actionJob =
            fragment.lifecycleScope.launch {
                val note = fragment.getCurrentMultimediaEditableNote()
                if (note.isEmpty) return@launch

                fragment.multimediaViewModel.multimediaAction.first { action ->
                    Timber.i("Selected multimedia action: %s", action)
                    val handler = MultimediaActionHandler.forAction(action)
                    val field = handler.createField().also { note.setField(fieldIndex, it) }
                    val intent =
                        handler.buildIntent(
                            fragment.requireContext(),
                            MultimediaActivityExtra(fieldIndex, field, note),
                        )
                    launcher.launch(intent)
                    true
                }
            }
    }

    /**
     * Handles an image-share intent: extracts the source [Uri], persists URI permission,
     * copies it into the app's cache, and launches the image picker seeded with the
     * cached file.
     */
    @NeedsTest("Test when the user directly passes image to the edit note field")
    suspend fun handleImageIntent(data: Intent) {
        val imageUri =
            if (data.action == Intent.ACTION_SEND) {
                BundleCompat.getParcelable(fragment.requireArguments(), Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                data.data
            }

        if (imageUri == null) {
            Timber.d("NoteEditor:: Image Uri is null")
            fragment.showSnackbar(R.string.something_wrong)
            return
        }

        try {
            fragment
                .requireContext()
                .contentResolver
                .takePersistableUriPermission(imageUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            Timber.d("Persisted URI permission for $imageUri")
        } catch (e: SecurityException) {
            Timber.w(e, "Unable to persist URI permission")
        }

        val cachedImagePath = copyUriToInternalCache(imageUri)
        if (cachedImagePath == null) {
            Timber.w("Failed to cache image")
            fragment.showSnackbar(R.string.something_wrong)
            return
        }
        val cachedUri = Uri.fromFile(File(fragment.requireContext().cacheDir, cachedImagePath))
        launchImagePaste(cachedUri)
    }

    /**
     * Copies a given [Uri] to the app's internal cache directory.
     *
     * This is necessary because URIs provided by other apps (e.g., WhatsApp, gallery apps) via
     * `Intent` are usually content URIs with temporary permissions that are only valid
     * in the originating context (like an Activity). Once passed to other components (like Fragments),
     * these permissions may be lost, resulting in a SecurityException.
     *
     * By caching the file in internal storage and referencing it via a file URI,
     * we ensure persistent access to the image without relying on external content providers.
     *
     * @param uri The [Uri] pointing to the external image content.
     * @return The name of the cached file, or `null` if the operation failed.
     */
    private fun copyUriToInternalCache(uri: Uri): String? {
        return try {
            val context = fragment.requireContext()
            val inputStream = context.contentResolver.openInputStreamSafe(uri) ?: return null

            val fileName = ContentResolverUtil.getFileName(context.contentResolver, uri)
            val cacheDir = context.cacheDir
            val destFile = File(cacheDir, fileName)

            val canonicalCacheDir = cacheDir.canonicalFile
            val canonicalDestFile = destFile.canonicalFile

            if (!canonicalDestFile.path.startsWith(canonicalCacheDir.path)) {
                // Do not log the name: it is attacker-controlled and warn logs are reported to ACRA.
                Timber.w("Rejected path due to directory traversal risk")
                return null
            }

            destFile.outputStream().use { output ->
                inputStream.copyTo(output)
            }

            Timber.d("copyUriToInternalCache() copied to ${destFile.absolutePath}")
            destFile.name
        } catch (e: Exception) {
            Timber.w(e, "Failed to copy URI to internal cache")
            null
        }
    }

    /**
     * Launches the image-picker screen pre-seeded with [imageUri], used by the
     * share/paste path when an image is delivered from another app.
     */
    suspend fun launchImagePaste(imageUri: Uri) {
        val note = fragment.getCurrentMultimediaEditableNote()
        if (note.isEmpty) {
            Timber.w("Note is null, returning")
            return
        }
        val handler = MultimediaActionHandler.ImageFile
        val extra =
            MultimediaActivityExtra(
                index = 0,
                field = handler.createField(),
                note = note,
                imageUri = imageUri.toString(),
            )
        launcher.launch(handler.buildIntent(fragment.requireContext(), extra))
    }

    fun onSaveInstanceState(outState: Bundle) {
        outState.putSerializable(STATE_KEY_IMAGE_CACHE, pastedImageCache)
    }

    fun onRestoreInstanceState(state: Bundle) {
        pastedImageCache =
            state.getSerializableCompat<HashMap<String, String>>(STATE_KEY_IMAGE_CACHE)
                ?: HashMap()
    }

    /**
     * Handles clipboard/drag-and-drop paste of a media [uri] into [editText].
     * Returns `true` if the paste produced a media tag, `false` otherwise.
     */
    fun onPaste(
        editText: EditText,
        uri: Uri,
        description: ClipDescription,
        pasteAsPng: Boolean,
    ): Boolean {
        val context = fragment.requireContext()
        // 콘텐츠 제공자가 파일 이름도 MIME 타입도 알려주지 않는 경우가 있습니다.
        // 그러면 아래 파이프라인이 이름을 못 정해 예외를 던지고, 붙여넣기가 오류
        // 보고서로 끝납니다. 이미지 가리기의 클립보드 불러오기는 제공자에게 아무것도
        // 묻지 않고 내용만 읽어 파일로 만들기 때문에 잘 동작합니다. 같은 방식으로
        // 먼저 파일을 만들어 두고, 그 파일을 넘깁니다.
        val (mediaUri, mediaDescription) = copyIfUnnamed(uri, description)
        val mediaTag =
            MediaRegistration.onPaste(
                context,
                mediaUri,
                mediaDescription,
                pasteAsPng,
                showError = { type -> fragment.showSnackbar(type.toHumanReadableString(context)) },
            ) ?: return false

        fragment.insertStringInField(editText, mediaTag)
        return true
    }

    /**
     * 이름을 알아낼 수 있는 URI 는 그대로 씁니다. 드래그 앤 드롭으로 들어온 이미지는
     * 원래 파일 이름을 그대로 유지합니다.
     *
     * 이름을 못 얻는 URI 는 내용을 읽어 캐시에 파일로 만들고 그 파일을 대신 넘깁니다.
     * 외부 캐시에 두는 이유는 아래 파이프라인이 /data 아래 경로를 거부하기 때문입니다.
     */
    private fun copyIfUnnamed(
        uri: Uri,
        description: ClipDescription,
    ): Pair<Uri, ClipDescription> {
        val context = fragment.requireContext()
        val resolver = context.contentResolver

        // 이름을 받아도 확장자가 없으면 소용이 없습니다. 아래 파이프라인은
        // FileNameAndExtension.fromString 으로 이름을 쪼개는데, 점이 없으면 null 을
        // 돌려주고 "Unable to determine valid filename" 예외가 납니다. 그 예외가
        // 오류 보고서로 이어집니다. 여기서 같은 기준으로 미리 걸러냅니다.
        val existingName =
            try {
                ContentResolverUtil
                    .getFileName(resolver, uri)
                    .takeIf { name -> name.lastIndexOf(".") >= 1 }
            } catch (e: Exception) {
                Timber.i(e, "the provider gave no filename, copying the content instead")
                null
            }
        if (existingName != null) return uri to description
        Timber.i("no usable filename for the pasted media, copying the content instead")

        val directory = context.externalCacheDir ?: return uri to description

        return try {
            // 내용의 머리 부분만 읽어 실제 형식을 알아냅니다.
            val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri).use { header ->
                android.graphics.BitmapFactory.decodeStream(header, null, options)
            }
            val mimeType = options.outMimeType ?: "image/jpeg"
            val extension =
                android.webkit.MimeTypeMap
                    .getSingleton()
                    .getExtensionFromMimeType(mimeType) ?: "jpg"

            val target = File.createTempFile("pasted", ".$extension", directory)
            resolver.openInputStream(uri).use { input ->
                if (input == null) {
                    target.delete()
                    return uri to description
                }
                target.outputStream().use { output -> input.copyTo(output) }
            }
            if (target.length() == 0L) {
                target.delete()
                return uri to description
            }

            Timber.i("copied the pasted media to %s", target.name)
            Uri.fromFile(target) to ClipDescription(description.label, arrayOf(mimeType))
        } catch (e: Exception) {
            Timber.w(e, "could not copy the pasted media, using the original uri")
            uri to description
        }
    }

    /** Imports the captured media into the collection if the result carries any. */
    fun handleResult(result: MultimediaResult.Success) {
        val field = result.field
        if (field.type != EFieldType.TEXT || field.mediaFile != null) {
            performAddMedia(result.fieldIndex, field, skipSizeCheck = false)
        } else {
            Timber.i("field imagePath and audioPath are both null")
        }
    }

    /**
     * Adds a media file to a specific field within the currently edited multimedia note.
     *
     * @param index The index of the field within the note to update.
     * @param field The `IField` object representing the media file and its details.
     * @param skipSizeCheck Whether to bypass the AnkiWeb media size limit check.
     */
    private fun performAddMedia(
        index: Int,
        field: IField,
        skipSizeCheck: Boolean,
    ) {
        fragment.launchCatchingTask {
            try {
                // Import field media before setting formattedValue so media paths
                // reflect the checksum when names collide.
                withCol {
                    NoteService.importMediaToDirectory(this, field, skipSizeCheck = skipSizeCheck)
                }

                val fieldEditText = fragment.editFieldAt(index) ?: return@launchCatchingTask
                val formattedValue = field.formattedValue
                if (field.type === EFieldType.TEXT) {
                    fieldEditText.setText(formattedValue)
                } else if (fieldEditText.text != null) {
                    fragment.insertStringInField(fieldEditText, formattedValue)
                }
                fragment.markMultimediaChanged()
            } catch (e: MediaSizeLimitExceededException) {
                fragment.showLargeMediaFileWarning(
                    e.fileName,
                    e.fileSize,
                    onForceAdd = { performAddMedia(index, field, skipSizeCheck = true) },
                )
            } catch (oomError: OutOfMemoryError) {
                // TODO: a 'retry' flow would be possible here
                throw Exception(oomError)
            }
        }
    }

    companion object {
        @VisibleForTesting
        const val STATE_KEY_IMAGE_CACHE = "imageCache"
    }
}
