package pt.cuco.scanner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import pt.cuco.scanner.databinding.ActivityMainBinding
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var pendingCameraUri: Uri? = null

    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = pendingCameraUri
        pendingCameraUri = null
        if (ok && uri != null) {
            openScan(uri)
        } else if (ok) {
            Toast.makeText(this, R.string.toast_no_image, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, R.string.toast_no_photo, Toast.LENGTH_SHORT).show()
        }
    }

    private val requestCameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchCamera()
        else Toast.makeText(this, R.string.toast_camera_denied, Toast.LENGTH_SHORT).show()
    }

    private val pickImage = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) openScan(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        pendingCameraUri = savedInstanceState?.getString(STATE_PENDING_URI)?.let(Uri::parse)

        binding.btnTakePhoto.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
            ) {
                launchCamera()
            } else {
                requestCameraPermission.launch(Manifest.permission.CAMERA)
            }
        }

        binding.btnPickGallery.setOnClickListener {
            pickImage.launch(
                androidx.activity.result.PickVisualMediaRequest(
                    ActivityResultContracts.PickVisualMedia.ImageOnly
                )
            )
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingCameraUri?.let { outState.putString(STATE_PENDING_URI, it.toString()) }
    }

    private fun launchCamera() {
        val picturesDir = File(cacheDir, "pictures").apply { mkdirs() }
        val photoFile = File.createTempFile("cuco_", ".jpg", picturesDir)
        val uri = FileProvider.getUriForFile(
            this,
            "$packageName.fileprovider",
            photoFile,
        )
        pendingCameraUri = uri
        takePicture.launch(uri)
    }

    private fun openScan(imageUri: Uri) {
        val intent = Intent(this, ScanActivity::class.java).apply {
            putExtra(ScanActivity.EXTRA_IMAGE_URI, imageUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(intent)
    }

    companion object {
        private const val STATE_PENDING_URI = "pending_camera_uri"
    }
}
