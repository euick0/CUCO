package pt.cuco.scanner

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import pt.cuco.scanner.databinding.ActivityCameraBinding
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCameraBinding
    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)
        cameraExecutor = Executors.newSingleThreadExecutor()

        binding.captureButton.setOnClickListener { takePhoto() }
        startCamera()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener(
            {
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder()
                    .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                    .build()
                    .also {
                        it.setSurfaceProvider(binding.previewView.surfaceProvider)
                    }
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                    .build()
                imageCapture = capture

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        capture,
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Camera bind failed", e)
                    Toast.makeText(this, R.string.toast_camera_unavailable, Toast.LENGTH_SHORT).show()
                    finish()
                }
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        binding.captureButton.isEnabled = false

        val picturesDir = File(cacheDir, "pictures").apply { mkdirs() }
        val photoFile = File.createTempFile("cuco_", ".jpg", picturesDir)
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        capture.takePicture(
            outputOptions,
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    val uri = FileProvider.getUriForFile(
                        this@CameraActivity,
                        "$packageName.fileprovider",
                        photoFile,
                    )
                    runOnUiThread { finishWithImage(uri) }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.w(TAG, "Image capture failed", exception)
                    runOnUiThread {
                        binding.captureButton.isEnabled = true
                        Toast.makeText(
                            this@CameraActivity,
                            R.string.toast_no_photo,
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            },
        )
    }

    private fun finishWithImage(uri: Uri) {
        val result = Intent().apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                putExtra(EXTRA_IMAGE_URI, uri)
            } else {
                @Suppress("DEPRECATION")
                putExtra(EXTRA_IMAGE_URI, uri)
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        setResult(RESULT_OK, result)
        finish()
    }

    companion object {
        const val EXTRA_IMAGE_URI = "image_uri"
        private const val TAG = "CameraActivity"
    }
}
