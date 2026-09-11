// SPDX-License-Identifier: GPL-3.0-or-later
//
// 저장할 위치:
// AnkiDroid/src/main/java/com/ichi2/anki/imagesave/PastedImage.kt

package com.ichi2.anki.imagesave

import android.graphics.BitmapFactory
import android.webkit.MimeTypeMap
import timber.log.Timber
import java.io.File

/**
 * 이미지 가리기가 다룰 수 있는 확장자.
 *
 * 클립보드에서 받은 파일 이름은 제공한 앱이 정하는 대로라, `occlusion_123.bin` 처럼
 * 확장자가 형식과 무관하게 붙는 경우가 있습니다. 카드 화면은 WebView 가 내용을 보고
 * 알아서 그리니 멀쩡해 보이지만, 가리기 편집은 파일 이름의 확장자로 이미지를 찾기
 * 때문에 나중에 다시 열면 이미지가 비어 있게 됩니다.
 */
private val IMAGE_EXTENSIONS =
    listOf("jpg", "jpeg", "png", "gif", "webp", "avif", "heic", "heif")

/**
 * 필요하면 파일 이름의 확장자를 실제 형식에 맞게 고칩니다.
 *
 * 내용의 머리 부분만 읽어 형식을 알아내므로 이미지 전체를 메모리에 올리지 않습니다.
 * 형식을 못 알아내거나 이름을 못 바꾸면 원래 경로를 그대로 돌려주고, 호출한 쪽은
 * 지금까지와 똑같이 동작합니다.
 *
 * @return 쓸 수 있는 파일 경로
 */
fun ensureImageExtension(path: String): String {
    val file = File(path)
    val current = file.extension
    if (IMAGE_EXTENSIONS.any { it.equals(current, ignoreCase = true) }) return path

    return try {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        file.inputStream().use { header ->
            BitmapFactory.decodeStream(header, null, options)
        }

        val mimeType = options.outMimeType
        val extension = mimeType?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
        if (extension == null) {
            Timber.i("could not tell the format of %s, leaving the name alone", file.name)
            return path
        }

        val stem = file.nameWithoutExtension.ifEmpty { "pasted" }
        val target = File(file.parentFile, "$stem.$extension")
        if (file.renameTo(target)) {
            Timber.i("renamed the pasted image to %s", target.name)
            target.absolutePath
        } else {
            Timber.w("could not rename the pasted image to %s", target.name)
            path
        }
    } catch (e: Exception) {
        Timber.w(e, "could not inspect the pasted image")
        path
    }
}
