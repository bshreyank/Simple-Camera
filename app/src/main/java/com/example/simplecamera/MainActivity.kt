package com.example.simplecamera

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Range
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.simplecamera.databinding.ActivityMainBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

typealias LumaListener = (luma: Double) -> Unit

class MainActivity : AppCompatActivity() {
    private lateinit var viewBinding: ActivityMainBinding

    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var camera: Camera? = null

    private lateinit var cameraExecutor: ExecutorService

    private val activityResultLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions())
        { permissions ->
            // Handle Permission granted/rejected
            var permissionGranted = true
            permissions.entries.forEach {
                if (it.key in REQUIRED_PERMISSIONS && it.value == false)
                    permissionGranted = false
            }
            if (!permissionGranted) {
                Toast.makeText(baseContext,
                    "Permission request denied",
                    Toast.LENGTH_SHORT).show()
            } else {
                startCamera()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)
        WindowCompat.getInsetsController(window, viewBinding.root).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }

        // Request camera permissions
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissions()
        }

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    private fun takePhoto() {}

    private fun captureVideo() {}

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            // Used to bind the lifecycle of cameras to the lifecycle owner
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Preview
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(viewBinding.viewFinder.surfaceProvider)
                }

            // Select back camera as a default
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                // Unbind use cases before rebinding
                cameraProvider.unbindAll()

                // Bind use cases to camera
                camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview)

                // Set manual camera controls after binding
                setupManualCameraControls()

            } catch(exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun setupManualCameraControls() {
        camera?.let { cam ->
            val camera2CameraControl = Camera2CameraControl.from(cam.cameraControl)
            val camera2CameraInfo = Camera2CameraInfo.from(cam.cameraInfo)

            // Get camera characteristics to check supported features
            val characteristics = camera2CameraInfo.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)

            // Check if manual sensor control is available
            val controlModes = camera2CameraInfo.getCameraCharacteristic(CameraCharacteristics.CONTROL_AVAILABLE_MODES)
            val manualSensorSupported = controlModes?.contains(CameraCharacteristics.CONTROL_MODE_OFF) == true

            if (manualSensorSupported) {
                Log.d(TAG, "Manual sensor control supported")

                // Get ISO range
                val isoRange = camera2CameraInfo.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                isoRange?.let { range ->
                    Log.d(TAG, "ISO range: ${range.lower} - ${range.upper}")
                }

                // Get exposure time range
                val exposureRange = camera2CameraInfo.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                exposureRange?.let { range ->
                    Log.d(TAG, "Exposure time range: ${range.lower} - ${range.upper} nanoseconds")
                }

                // Set manual controls
                setManualExposure(camera2CameraControl, isoRange, exposureRange)
            } else {
                Log.d(TAG, "Manual sensor control not supported, using auto exposure compensation")
                setAutoExposureCompensation(camera2CameraControl)
            }
        }
    }

    private fun setManualExposure(
        camera2CameraControl: Camera2CameraControl,
        isoRange: Range<Int>?,
        exposureRange: Range<Long>?
    ) {
        // Example: Set specific ISO and exposure time values
        val desiredISO = 400 // Adjust this value as needed
        val desiredExposureTime = 10_000_000L // 1ms in nanoseconds, adjust as needed

        // Clamp values to supported ranges
        val clampedISO = isoRange?.let {
            desiredISO.coerceIn(it.lower, it.upper)
        } ?: desiredISO

        val clampedExposureTime = exposureRange?.let {
            desiredExposureTime.coerceIn(it.lower, it.upper)
        } ?: desiredExposureTime

        // Build capture request options
        val captureRequestOptions = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(CaptureRequest.CONTROL_MODE, CameraCharacteristics.CONTROL_MODE_OFF)
            .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, clampedISO)
            .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, clampedExposureTime)
            .build()

        // Apply the capture request options
        camera2CameraControl.setCaptureRequestOptions(captureRequestOptions)

        Log.d(TAG, "Set manual exposure - ISO: $clampedISO, Exposure: ${clampedExposureTime}ns")
    }

    private fun setAutoExposureCompensation(camera2CameraControl: Camera2CameraControl) {
        // Alternative: Use exposure compensation for brightness control
        val exposureCompensation = 2 // Positive values = brighter, negative = darker

        val captureRequestOptions = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, exposureCompensation)
            .build()

        camera2CameraControl.setCaptureRequestOptions(captureRequestOptions)

        Log.d(TAG, "Set auto exposure compensation: $exposureCompensation")
    }

    // Method to dynamically change brightness/ISO during runtime
    fun adjustBrightness(brightnessLevel: BrightnessLevel) {
        camera?.let { cam ->
            val camera2CameraControl = Camera2CameraControl.from(cam.cameraControl)
            val camera2CameraInfo = Camera2CameraInfo.from(cam.cameraInfo)

            val isoRange = camera2CameraInfo.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            val exposureRange = camera2CameraInfo.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)

            when (brightnessLevel) {
                BrightnessLevel.DARK -> {
                    val iso = isoRange?.lower ?: 100
                    val exposure = exposureRange?.lower ?: 500_000L
                    setSpecificExposure(camera2CameraControl, iso, exposure)
                }
                BrightnessLevel.NORMAL -> {
                    val iso = 4000
                    val exposure = 1_000_000L
                    setSpecificExposure(camera2CameraControl, iso, exposure)
                }
                BrightnessLevel.BRIGHT -> {
                    val iso = isoRange?.upper ?: 3200
                    val exposure = 5_000_000L // 5ms
                    setSpecificExposure(camera2CameraControl, iso, exposure)
                }
            }
        }
    }

    private fun setSpecificExposure(camera2CameraControl: Camera2CameraControl, iso: Int, exposureTime: Long) {
        val captureRequestOptions = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(CaptureRequest.CONTROL_MODE, CameraCharacteristics.CONTROL_MODE_OFF)
            .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
            .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureTime)
            .build()

        camera2CameraControl.setCaptureRequestOptions(captureRequestOptions)

        Log.d(TAG, "Applied exposure settings - ISO: $iso, Exposure: ${exposureTime}ns")
    }

    private fun requestPermissions() {
        activityResultLauncher.launch(REQUIRED_PERMISSIONS)
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    enum class BrightnessLevel {
        DARK, NORMAL, BRIGHT
    }

    companion object {
        private const val TAG = "CameraXApp"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private val REQUIRED_PERMISSIONS =
            mutableListOf (
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            ).apply {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }.toTypedArray()
    }
}
