package com.khom.videopanorama

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.os.Environment.DIRECTORY_PICTURES
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import org.opencv.BuildConfig
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

class FileUtil(private val context: Context) {
    val KOEF_BLUR_CAMERA_2 = 4000000

    @Throws(IOException::class)
    fun urisToFiles(uris: List<Uri>): List<File> {
        val files = ArrayList<File>(uris.size)
        for (uri in uris) {
            val file = createTempFile(requireTemporaryDirectory())
            writeUriToFile(uri, file)

            if (checkBlurPhoto(
                    BitmapFactory.decodeFile((file.absolutePath)),
                    KOEF_BLUR_CAMERA_2
                ) == null
            ) files.add(file)
        }
        return files
    }

    private fun changeSizeImage(bitmap: Bitmap?): Bitmap? {
        if (bitmap == null) {
            return null
        }
        return if (bitmap.height > bitmap.width) {
            Bitmap.createScaledBitmap(bitmap, 720, 1280, false)
        } else {
            Bitmap.createScaledBitmap(bitmap, 1280, 720, false)
        }
    }

    private fun checkBlurPhoto(
        imageFull: Bitmap,
        koefBlur: Int
    ): CheckPhotoResult? {
        val image: Bitmap =
            changeSizeImage(imageFull)
                ?: return null
        try {
            if (image != null) {
//                BitmapFactory.Options opt = new BitmapFactory.Options();
//                opt.inDither = true;
//                opt.inPreferredConfig = Bitmap.Config.ARGB_8888;
                val l = CvType.CV_8UC1
                val matImage = org.opencv.core.Mat()
                Utils.bitmapToMat(image, matImage)
                val mGrey = org.opencv.core.Mat()
                Imgproc.cvtColor(matImage, mGrey, Imgproc.COLOR_BGR2GRAY)
                val dst2 = org.opencv.core.Mat()
                Utils.bitmapToMat(image, dst2)
                val laplacianImage = org.opencv.core.Mat()
                dst2.convertTo(laplacianImage, l)
                Imgproc.Laplacian(mGrey, laplacianImage, CvType.CV_8U)
                val laplacianImage8bit = org.opencv.core.Mat()
                laplacianImage.convertTo(laplacianImage8bit, l)
                System.gc()
                val bmp = Bitmap.createBitmap(
                    laplacianImage8bit.cols(),
                    laplacianImage8bit.rows(), Bitmap.Config.ARGB_8888
                )
                Utils.matToBitmap(laplacianImage8bit, bmp)
                val pixels = IntArray(bmp!!.height * bmp.width)
                bmp.getPixels(
                    pixels, 0, bmp.width, 0, 0, bmp.width,
                    bmp.height
                )
                if (bmp != null) if (!bmp.isRecycled) {
                    bmp.recycle()
                }
                var maxLap = -16777216
                for (i in pixels.indices) {
                    if (pixels[i] > maxLap) {
                        maxLap = pixels[i]
                    }
                }
                var soglia = -6118750
                soglia += 6118750
                maxLap += 6118750
                if (BuildConfig.DEBUG) {
                    val pixelsImage = (imageFull.width * imageFull.height).toDouble() / 1024000
                    Log.d(
                        "LOGCALCBLUR",
                        "\n result:"
                                + "\nimageFull.w=" + imageFull.width + ", imageFull.h=" + imageFull.height + ", imageFull.ByteCount=" + imageFull.byteCount
                                + "\nimage.w=" + image.width + ", image.h=" + image.height
                                + "\nmaxLap= " + maxLap + ", sogliap= " + soglia
                                + "\n" + (if (maxLap <= soglia) "is blur" else "no blur")
                                + "\npixelsImage " + pixelsImage
                    )
                }
                return if (maxLap <= soglia || maxLap < koefBlur) { //4000000){//6118000) {
                    CheckPhotoResult(
                        "blured"
                    )
                } else {
                    null
                }
            } else {
                return null
            }
        } catch (e: NullPointerException) {
            return null
        } catch (e: OutOfMemoryError) {
            return null
        }
    }

    class CheckPhotoResult {
        var error: String
        var bitmapError: Bitmap? = null
        var dublicate: Bitmap? = null

        constructor(error: String) {
            this.error = error
        }

        constructor(error: String, bitmapError: Bitmap?) {
            this.error = error
            this.bitmapError = bitmapError
        }
    }


    fun createResultFile(): File {
        val pictures = context.getExternalFilesDir(DIRECTORY_PICTURES)!!
        return createTempFile(File(pictures, RESULT_DIRECTORY_NAME))
    }

    fun cleanUpWorkingDirectory() {
        requireTemporaryDirectory().remove()
    }

    @Throws(IOException::class)
    private fun createTempFile(root: File): File {
        root.mkdirs() // make sure that the directory exists
        val date = SimpleDateFormat(DATE_FORMAT_TEMPLATE, Locale.getDefault()).format(Date())
        val filePrefix = IMAGE_NAME_TEMPLATE.format(date)
        return File.createTempFile(filePrefix, JPG_EXTENSION, root)
    }

    @Throws(IOException::class)
    private fun writeUriToFile(target: Uri, destination: File) {
        val inputStream = context.contentResolver.openInputStream(target)!!
        val outputStream = FileOutputStream(destination)
        inputStream.use { input ->
            outputStream.use { out ->
                input.copyTo(out)
            }
        }
    }

    private fun requireTemporaryDirectory(): File {
        // don't need read/write permission for this directory starting from android 19
        val pictures = context.getExternalFilesDir(DIRECTORY_PICTURES)!!
        return File(pictures, TEMPORARY_DIRECTORY_NAME)
    }

    // there is no build in function for deleting folders <3
    private fun File.remove() {
        if (isDirectory) {
            val entries = listFiles()
            if (entries != null) {
                for (entry in entries) {
                    entry.remove()
                }
            }
        }
        delete()
    }

    fun getPath(context: Context, uri: Uri): String? {

        // DocumentProvider
        if (DocumentsContract.isDocumentUri(context, uri)) {
            // ExternalStorageProvider
            if (isExternalStorageDocument(uri)) {
                val docId = DocumentsContract.getDocumentId(uri)
                val split = docId.split(":".toRegex()).toTypedArray()
                val type = split[0]
                if ("primary".equals(type, ignoreCase = true)) {
                    return Environment.getExternalStorageDirectory().toString() + "/" + split[1]
                }

                // TODO handle non-primary volumes
            } else if (isDownloadsDocument(uri)) {
                val id = DocumentsContract.getDocumentId(uri)
                val contentUri = ContentUris.withAppendedId(
                    Uri.parse("content://downloads/public_downloads"), java.lang.Long.valueOf(id)
                )
                return getDataColumn(context, contentUri, null, null)
            } else if (isMediaDocument(uri)) {
                val docId = DocumentsContract.getDocumentId(uri)
                val split = docId.split(":".toRegex()).toTypedArray()
                val type = split[0]
                var contentUri: Uri? = null
                if ("image" == type) {
                    contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                } else if ("video" == type) {
                    contentUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                } else if ("audio" == type) {
                    contentUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                }
                val selection = "_id=?"
                val selectionArgs = arrayOf(
                    split[1]
                )
                return getDataColumn(context, contentUri, selection, selectionArgs)
            }
        } else if ("content".equals(uri.scheme, ignoreCase = true)) {
            return getDataColumn(context, uri, null, null)
        } else if ("file".equals(uri.scheme, ignoreCase = true)) {
            return uri.path
        }
        return null
    }


    /**
     * Get the value of the data column for this Uri. This is useful for
     * MediaStore Uris, and other file-based ContentProviders.
     *
     * @param context The context.
     * @param uri The Uri to query.
     * @param selection (Optional) Filter used in the query.
     * @param selectionArgs (Optional) Selection arguments used in the query.
     * @return The value of the _data column, which is typically a file path.
     */
    fun getDataColumn(
        context: Context, uri: Uri?, selection: String?,
        selectionArgs: Array<String>?
    ): String? {
        var cursor: Cursor? = null
        val column = "_data"
        val projection = arrayOf(
            column
        )
        try {
            cursor = context.getContentResolver().query(
                uri!!, projection, selection, selectionArgs,
                null
            )
            if (cursor != null && cursor.moveToFirst()) {
                val column_index: Int = cursor.getColumnIndexOrThrow(column)
                return cursor.getString(column_index)
            }
        } finally {
            if (cursor != null) cursor.close()
        }
        return null
    }


    /**
     * @param uri The Uri to check.
     * @return Whether the Uri authority is ExternalStorageProvider.
     */
    fun isExternalStorageDocument(uri: Uri): Boolean {
        return "com.android.externalstorage.documents" == uri.authority
    }

    /**
     * @param uri The Uri to check.
     * @return Whether the Uri authority is DownloadsProvider.
     */
    fun isDownloadsDocument(uri: Uri): Boolean {
        return "com.android.providers.downloads.documents" == uri.authority
    }

    /**
     * @param uri The Uri to check.
     * @return Whether the Uri authority is MediaProvider.
     */
    fun isMediaDocument(uri: Uri): Boolean {
        return "com.android.providers.media.documents" == uri.authority
    }

    companion object {
        private const val TEMPORARY_DIRECTORY_NAME = "Temporary"
        private const val RESULT_DIRECTORY_NAME = "Results"
        private const val DATE_FORMAT_TEMPLATE = "yyyyMMdd_HHmmss"
        private const val IMAGE_NAME_TEMPLATE = "IMG_%s_"
        private const val JPG_EXTENSION = ".jpg"
    }
}
