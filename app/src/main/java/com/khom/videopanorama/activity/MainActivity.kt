package com.khom.videopanorama.activity


import android.Manifest.permission
import android.app.Activity
import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Build.VERSION.SDK_INT
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.khom.videopanorama.*
import com.khom.videopanorama.StitcherOutput.Failure
import com.khom.videopanorama.StitcherOutput.Success
import com.khom.videopanorama.worker.GlobalScopeDispIOWorker
import com.media.scopemediapicker.ScopedMediaPicker
import com.squareup.picasso.MemoryPolicy
import com.squareup.picasso.Picasso
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.PublishSubject
import org.bytedeco.javacpp.opencv_stitching
import org.opencv.android.BaseLoaderCallback
import org.opencv.android.LoaderCallbackInterface
import org.opencv.android.OpenCVLoader
import wseemann.media.FFmpegMediaMetadataRetriever
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.*


class MainActivity : AppCompatActivity() {

    private val PERMISSIONS = arrayOf(
        permission.ACCESS_NETWORK_STATE,
        permission.INTERNET,
        permission.CAMERA,
        permission.WRITE_SETTINGS,
        permission.ACCESS_WIFI_STATE,
        permission.WRITE_EXTERNAL_STORAGE,
        permission.READ_PHONE_STATE
    )

    private lateinit var editTextVideoFrames: EditText
    private lateinit var imageView: ImageView
    private lateinit var radioGroup: RadioGroup

    private lateinit var imageStitcher: ImageStitcher
    private lateinit var disposable: Disposable

    private val stitcherInputRelay = PublishSubject.create<StitcherInput>()
    private val scopedMediaPicker by lazy {
        ScopedMediaPicker(
            activity = this@MainActivity,
            requiresCrop = false, // Optional
            allowMultipleImages = false // Optional
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setUpViews()
        setUpStitcher()
        if (!OpenCVLoader.initDebug()) {
            OpenCVLoader.initAsync(OpenCVLoader.OPENCV_VERSION, this, mLoaderCallback)
        } else {
            mLoaderCallback.onManagerConnected(LoaderCallbackInterface.SUCCESS)
        }

        if (!hasPermissions(this, *PERMISSIONS)) {
            val PERMISSION_ALL = 1
            ActivityCompat.requestPermissions(this, PERMISSIONS, PERMISSION_ALL)
        }

        setUpWorkFolderOnStorage()
    }

    private val mLoaderCallback: BaseLoaderCallback = object :
        BaseLoaderCallback(this) {
        override fun onManagerConnected(status: Int) {
            when (status) {
                SUCCESS -> Log.i("OpenCV", "OpenCV loaded successfully")
                else -> super.onManagerConnected(status)
            }
        }
    }

    private fun hasPermissions(context: Context, vararg permissions: String?): Boolean {
        for (permission in permissions) {
            if (ContextCompat.checkSelfPermission(
                    context,
                    permission!!
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }
        }
        return true
    }

    private fun setUpWorkFolderOnStorage() {
        if (SDK_INT >= Build.VERSION_CODES.R)
            if (Environment.isExternalStorageManager()) {
            } else { //request for the permission
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                val uri = Uri.fromParts("package", packageName, null)
                intent.data = uri
                startActivity(intent)
            }

        val folder = File(
            Environment.getExternalStorageDirectory().toString() + "/videoFrames/"
        )

        deleteRecursive(folder)
        folder.mkdir()
    }

    private fun setUpViews() {
        imageView = findViewById(R.id.image)
        radioGroup = findViewById(R.id.radio_group)
        editTextVideoFrames = findViewById(R.id.inputFrames)
        findViewById<View>(R.id.button).setOnClickListener { chooseImages() }
        findViewById<View>(R.id.buttonVideo).setOnClickListener { chooseVideo() }
    }

    @Suppress("DEPRECATION")
    private fun setUpStitcher() {
        imageStitcher = ImageStitcher(FileUtil(applicationContext))
        val dialog = ProgressDialog(this).apply {
            setMessage(getString(R.string.processing_images))
            setCancelable(false)
        }

        disposable = stitcherInputRelay.switchMapSingle {
            imageStitcher.stitchImages(it)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .doOnSubscribe { dialog.show() }
                .doOnSuccess { dialog.dismiss() }
        }
            .subscribe({ processResult(it) }, { processError(it) })
    }

    private fun chooseImages() {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
            .setType(INTENT_IMAGE_TYPE)
            .putExtra(EXTRA_ALLOW_MULTIPLE, true)
        startActivityForResult(intent, CHOOSE_IMAGES)
    }

    private fun deleteRecursive(fileOrDirectory: File) {
        if (fileOrDirectory.isDirectory)
            for (child in fileOrDirectory.listFiles()!!) deleteRecursive(
                child
            )
        fileOrDirectory.delete()
    }

    private fun chooseVideo() {

        scopedMediaPicker.startMediaPicker(
            mediaType = ScopedMediaPicker.MEDIA_TYPE_IMAGE or
                    ScopedMediaPicker.MEDIA_TYPE_VIDEO
        ) { pathList, type ->
            when (type) {
                ScopedMediaPicker.MEDIA_TYPE_VIDEO -> {

                    VisitUploader(
                        "uploadVisit", pathList[0], this@MainActivity
                    ).execute()

                }
            }

        }
    }

    class VisitUploader(
        taskName: String,
        url: String,
        cnxs: MainActivity
    ) :
        GlobalScopeDispIOWorker<Void?, Void?, MutableList<Uri>>(taskName) {
        private val urls: String
        private val cnx: MainActivity
        private var dialog: ProgressDialog
        override fun onPreExecute() {
            dialog.setMessage("Video processing, please wait.");
            dialog.show();
            dialog.setCancelable(false)
            super.onPreExecute()
        }


        override fun doInBackground(vararg params: Void?): MutableList<Uri> {
            return cnx.videoToImages(urls)
        }

        init {
            urls = url
            cnx = cnxs
            dialog = ProgressDialog(cnx)
        }

        override fun onPostExecute(result: MutableList<Uri>?) {
            super.onPostExecute(result)
            Toast.makeText(
                cnx,
                "Video splitting into frames completed",
                Toast.LENGTH_SHORT
            ).show()
            cnx.processImages(result!!)
            dialog.dismiss()
        }
    }


    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        scopedMediaPicker.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }


    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        scopedMediaPicker.onActivityResult(requestCode, resultCode, data)

        if (requestCode == CHOOSE_IMAGES && resultCode == Activity.RESULT_OK && data != null) {
            val clipData = data.clipData
            val images = if (clipData != null) {
                List(clipData.itemCount) { clipData.getItemAt(it).uri }
            } else {
                listOf(data.data!!)
            }
            processImages(images)
        }
    }

    private fun processImages(uris: List<Uri>) {
        imageView.setImageDrawable(null) // reset preview
        val isScansChecked = radioGroup.checkedRadioButtonId == R.id.radio_scan
        val stitchMode =
            if (isScansChecked) opencv_stitching.Stitcher.SCANS else opencv_stitching.Stitcher.PANORAMA
        stitcherInputRelay.onNext(StitcherInput(uris, stitchMode))
    }

    private fun processError(e: Throwable) {
        Log.e(TAG, "", e)
        Toast.makeText(this, e.message + "", Toast.LENGTH_LONG).show()
    }

    private fun processResult(output: StitcherOutput) {
        when (output) {
            is Success -> showImage(output.file)
            is Failure -> processError(output.e)
        }
    }

    private fun showImage(file: File) {
        Picasso.get().load(file)
            .memoryPolicy(MemoryPolicy.NO_STORE, MemoryPolicy.NO_CACHE)
            .into(imageView)
    }

    fun videoToImages(uri: String?): MutableList<Uri> {
        val framesOnSec = editTextVideoFrames.text.toString().toDouble()
        val videoFileUri = Uri.parse(uri)
        val retriever = FFmpegMediaMetadataRetriever()
        retriever.setDataSource(uri)
        val rev: MutableList<Uri> = ArrayList()
        val mp = MediaPlayer.create(this, videoFileUri)
        val videoDuration = mp.duration
        val millis1 = mp.duration
        var i = 0

        while (i <= videoDuration) {
            val bitmap =
                retriever.getFrameAtTime(
                    (i * 1000).toLong(),
                    FFmpegMediaMetadataRetriever.OPTION_CLOSEST
                )

            Log.e("FRAME", "dur - $millis1 i - $i")
            val random = Random()
            val fileName = Environment.getExternalStorageDirectory()
                .toString() + File.separator + "videoFrames" + File.separator + "testimage" + random.nextInt()

            try {
                val bytes = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, bytes)
                val f = File("$fileName.jpg")
                f.createNewFile()
                val fo = FileOutputStream(f)
                fo.write(bytes.toByteArray())
                fo.close()

                rev.add(Uri.fromFile(f))

            } catch (ex: Exception) {
                ex.printStackTrace()
            }
            i += (1000 * framesOnSec).toInt()
        }

        return rev

    }


    override fun onDestroy() {
        super.onDestroy()
        disposable.dispose()
    }

    companion object {
        private const val TAG = "TAG"
        private const val EXTRA_ALLOW_MULTIPLE = "android.intent.extra.ALLOW_MULTIPLE"
        private const val INTENT_IMAGE_TYPE = "image/*"
        private const val INTENT_VIDEO_TYPE = "video/*"
        private const val CHOOSE_IMAGES = 777
        private const val CHOOSE_VIDEOS = 778
    }
}
