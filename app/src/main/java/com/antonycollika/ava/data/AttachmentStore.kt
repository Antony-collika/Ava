package com.antonycollika.ava.data

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

data class MessageAttachment(
    val id: String,
    val mimeType: String,
    val fileName: String
)

/** Copies selected media into app-private storage so message history survives URI permission changes. */
class AttachmentStore(context: Context) {
    private val directory = File(context.filesDir, "message-attachments").apply { mkdirs() }

    fun importUri(resolver: ContentResolver, uri: Uri): MessageAttachment {
        val mime = resolver.getType(uri)?.lowercase()
            ?: throw IllegalArgumentException("Unable to determine the image type.")
        require(mime in SUPPORTED_TYPES) { "Only JPEG, PNG, WebP and GIF images are supported." }
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            ?: "image"
        val id = UUID.randomUUID().toString()
        val destination = File(directory, id)
        try {
            resolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_IMAGE_BYTES) { "Image exceeds the 15 MB limit." }
                        output.write(buffer, 0, count)
                    }
                }
            } ?: throw IllegalArgumentException("Unable to open the selected image.")
        } catch (error: Throwable) {
            destination.delete()
            throw error
        }
        return MessageAttachment(id, mime, name)
    }

    fun importBitmap(bitmap: Bitmap): MessageAttachment {
        val id = UUID.randomUUID().toString()
        val file = File(directory, id)
        FileOutputStream(file).use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 88, output)) { "Unable to save the camera image." }
        }
        return MessageAttachment(id, "image/jpeg", "camera.jpg")
    }

    fun read(attachment: MessageAttachment): ByteArray {
        val file = File(directory, attachment.id)
        require(file.parentFile?.canonicalFile == directory.canonicalFile && file.isFile) {
            "The attached image is no longer available."
        }
        require(file.length() <= MAX_IMAGE_BYTES) { "Attached image exceeds the 15 MB limit." }
        return file.readBytes()
    }

    fun file(attachment: MessageAttachment): File? = File(directory, attachment.id).takeIf { it.isFile }

    companion object {
        const val MAX_IMAGE_BYTES = 15L * 1024 * 1024
        val SUPPORTED_TYPES = setOf("image/jpeg", "image/png", "image/webp", "image/gif")
    }
}
