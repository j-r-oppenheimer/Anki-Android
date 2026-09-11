// SPDX-License-Identifier: GPL-3.0-or-later
//
// 저장할 위치:
// AnkiDroid/src/main/java/com/ichi2/anki/imagesave/CardImageSaver.kt
//
// 리뷰어에서 이미지를 길게 누르면 저장할지 공유할지 묻는 대화상자를 띄웁니다.
//
// 카드는 로컬 HTTP 서버로 서빙되므로 이미지 주소는 http://127.0.0.1:포트/파일명 형태입니다.
// 주소를 내려받는 게 아니라, 경로에서 파일명을 뽑아 collection.media 안의 실제 파일을 씁니다.

package com.ichi2.anki.imagesave

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.WebView
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import com.google.android.material.color.MaterialColors
import com.ichi2.anki.R
import com.ichi2.anki.common.storage.CollectionHelper
import com.ichi2.anki.common.utils.android.showThemedToast
import com.ichi2.utils.AssetHelper.guessMimeType
import com.ichi2.utils.withFileNameSafe
import timber.log.Timber
import java.io.File

/** 공유할 때 파일을 잠깐 복사해 두는 캐시 폴더 이름. */
private const val SHARE_CACHE_DIR = "shared_images"

object CardImageSaver {
    /**
     * @return 이미지를 처리했으면 true. false면 WebView의 기본 롱프레스 동작(텍스트 선택 등)이
     *         그대로 진행되므로 기존 동작을 방해하지 않습니다.
     */
    fun handleLongPress(
        context: Context,
        webView: WebView,
    ): Boolean {
        val result =
            try {
                webView.hitTestResult
            } catch (e: Exception) {
                Timber.w(e, "cannot obtain hit test result")
                return false
            }

        val isImage =
            result.type == WebView.HitTestResult.IMAGE_TYPE ||
                result.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
        if (!isImage) return false

        val extra = result.extra ?: return false
        val file = resolveMediaFile(context, extra)

        if (file == null) {
            Timber.d("long press image not found in media dir: %s", extra)
            return false
        }

        showActionDialog(context, file)
        return true
    }

    /** 저장할지 공유할지 묻습니다. 실수로 길게 눌렀을 때 바로 저장되지 않도록. */
    private fun showActionDialog(
        context: Context,
        file: File,
    ) {
        val actions =
            arrayOf(
                context.getString(R.string.image_action_save),
                context.getString(R.string.image_action_share),
            )

        val dialog =
            AlertDialog
                .Builder(context)
                .setTitle(R.string.image_action_title)
                .setItems(actions) { _, which ->
                    if (which == 0) save(context, file) else share(context, file)
                }.setNegativeButton(android.R.string.cancel, null)
                .create()

        // Material3 다이얼로그의 버튼 글자색은 colorPrimary 를 따라갑니다. 테마에 따라
        // 다이얼로그 배경과 거의 같은 색이 될 수 있어서, 강조색으로 직접 칠해둡니다.
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(
                MaterialColors.getColor(
                    dialog.context,
                    androidx.appcompat.R.attr.colorAccent,
                    Color.GRAY,
                ),
            )
        }
        dialog.show()
    }

    /** 이미지 주소를 collection.media 안의 실제 파일로 바꿉니다. */
    private fun resolveMediaFile(
        context: Context,
        url: String,
    ): File? {
        // 카드에 base64로 직접 박힌 이미지는 파일이 없으므로 건너뜁니다.
        if (url.startsWith("data:")) return null

        val uri = Uri.parse(url)
        val path = uri.path ?: return null

        val file =
            if (uri.scheme == "file") {
                File(path)
            } else {
                // ViewerResourceHandler 가 쓰는 것과 같은 방식으로 안전하게 해석합니다.
                CollectionHelper.getMediaDirectory(context).withFileNameSafe(path)
            }

        return if (file.exists()) file else null
    }

    private fun save(
        context: Context,
        file: File,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            showThemedToast(context, R.string.image_save_needs_android_10, false)
            return
        }
        val saved = saveToPictures(context, file)
        showThemedToast(
            context,
            if (saved) R.string.image_saved else R.string.image_save_failed,
            true,
        )
    }

    /**
     * Pictures/AnkiDroid 폴더에 복사합니다. Android 10 이상에서는 권한이 필요 없습니다.
     *
     * RELATIVE_PATH 가 API 29부터라 어노테이션이 필요합니다. 호출부에 버전 가드가 있어도
     * lint 는 함수 경계를 넘어 추적하지 않으므로, 없으면 lintVitalRelease 가 실패합니다.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveToPictures(
        context: Context,
        source: File,
    ): Boolean =
        try {
            val values =
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, source.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, guessMimeType(source.name))
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/AnkiDroid",
                    )
                }

            val target =
                context.contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    values,
                )

            if (target == null) {
                false
            } else {
                context.contentResolver.openOutputStream(target)?.use { output ->
                    source.inputStream().use { input -> input.copyTo(output) }
                }
                true
            }
        } catch (e: Exception) {
            Timber.w(e, "failed to save card image")
            false
        }

    /**
     * 다른 앱으로 보냅니다.
     *
     * collection.media 를 직접 넘기지 않고 캐시에 복사한 뒤 그 파일을 공유합니다.
     * 캐시 폴더는 AnkiDroid 의 FileProvider 경로(filepaths.xml)에 이미 등록돼 있고,
     * 받는 앱이 컬렉션 폴더를 들여다볼 일도 없어집니다.
     */
    private fun share(
        context: Context,
        source: File,
    ) {
        try {
            val dir = File(context.cacheDir, SHARE_CACHE_DIR)
            dir.mkdirs()
            val copy = File(dir, source.name)
            source.copyTo(copy, overwrite = true)

            val uri =
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.apkgfileprovider",
                    copy,
                )

            val send =
                Intent(Intent.ACTION_SEND).apply {
                    type = guessMimeType(source.name)
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

            context.startActivity(
                Intent.createChooser(send, context.getString(R.string.image_action_share)),
            )
        } catch (e: Exception) {
            Timber.w(e, "failed to share card image")
            showThemedToast(context, R.string.image_share_failed, false)
        }
    }
}
