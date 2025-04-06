package com.surendramaran.yolov8tflite

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.os.Debug
import android.os.Environment
import android.os.StatFs
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.surendramaran.yolov8tflite.Constants.LABELS_PATH
import com.surendramaran.yolov8tflite.Constants.MODEL_PATH
import com.surendramaran.yolov8tflite.databinding.ActivityMainBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), Detector.DetectorListener {
    private lateinit var binding: ActivityMainBinding
    private val isFrontCamera = false

    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private lateinit var detector: Detector
    private val memoryInfo = ActivityManager.MemoryInfo()
    private val logPerformanceInfo = LogPerformanceInfo()

    private lateinit var cameraExecutor: ExecutorService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        Log.d("AppPerformanceInfo", "Just executed: setContentView in onCreate")
        logPerformanceInfo.getAllPerformanceInfo(
            memoryInfo,
            activityManager,
            context = applicationContext
        )

        detector = Detector(baseContext, MODEL_PATH, LABELS_PATH, this)
        detector.setup(logPerformanceInfo, memoryInfo, activityManager, applicationContext)

        if (allPermissionsGranted()) {
            startCamera(logPerformanceInfo, memoryInfo, activityManager, applicationContext)
            Log.d("AppPerformanceInfo", "Just executed: startCamera in onCreate")
            logPerformanceInfo.getAllPerformanceInfo(
                memoryInfo,
                activityManager,
                context = applicationContext
            )
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    private fun startCamera(
        logPerformanceInfo: LogPerformanceInfo,
        memoryInfo: ActivityManager.MemoryInfo,
        activityManager: ActivityManager,
        applicationContext: Context
    ) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases(logPerformanceInfo, memoryInfo, activityManager, applicationContext)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases(
        logPerformanceInfo: LogPerformanceInfo,
        memoryInfo: ActivityManager.MemoryInfo,
        activityManager: ActivityManager,
        applicationContext: Context
    ) {
        val cameraProvider =
            cameraProvider ?: throw IllegalStateException("Camera initialization failed.")

        val rotation = binding.viewFinder.display.rotation

        val cameraSelector = CameraSelector
            .Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build()

        preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(rotation)
            .build()

        imageAnalyzer = ImageAnalysis.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetRotation(binding.viewFinder.display.rotation)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
        Log.d("AppPerformanceInfo", "Just executed: build Preview and image analysis builder")
        logPerformanceInfo.getAllPerformanceInfo(
            memoryInfo,
            activityManager,
            context = applicationContext
        )

        imageAnalyzer?.setAnalyzer(cameraExecutor) { imageProxy ->
            val bitmapBuffer =
                Bitmap.createBitmap(
                    imageProxy.width,
                    imageProxy.height,
                    Bitmap.Config.ARGB_8888
                )
            imageProxy.use { bitmapBuffer.copyPixelsFromBuffer(imageProxy.planes[0].buffer) }
            imageProxy.close()

            val matrix = Matrix().apply {
                postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())

                if (isFrontCamera) {
                    postScale(
                        -1f,
                        1f,
                        imageProxy.width.toFloat(),
                        imageProxy.height.toFloat()
                    )
                }
            }

            val rotatedBitmap = Bitmap.createBitmap(
                bitmapBuffer, 0, 0, bitmapBuffer.width, bitmapBuffer.height,
                matrix, true
            )

            detector.detect(rotatedBitmap, logPerformanceInfo, memoryInfo, activityManager, applicationContext)
        }

        cameraProvider.unbindAll()

        try {
            camera = cameraProvider.bindToLifecycle(
                this,
                cameraSelector,
                preview,
                imageAnalyzer
            )

            preview?.setSurfaceProvider(binding.viewFinder.surfaceProvider)
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        if (it[Manifest.permission.CAMERA] == true) {
            startCamera(logPerformanceInfo, memoryInfo, activityManager, applicationContext)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        detector.clear()
        cameraExecutor.shutdown()
    }

    override fun onResume() {
        super.onResume()
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        if (allPermissionsGranted()) {
            startCamera(logPerformanceInfo, memoryInfo, activityManager, applicationContext)
            Log.d("AppPerformanceInfo", "Just executed: startCamera in onCreate")
            logPerformanceInfo.getAllPerformanceInfo(memoryInfo, activityManager, context = applicationContext)
        } else {
            requestPermissionLauncher.launch(REQUIRED_PERMISSIONS)
        }
    }

    companion object {
        private const val TAG = "Camera"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = mutableListOf(
            Manifest.permission.CAMERA
        ).toTypedArray()
    }

    override fun onEmptyDetect() {
        binding.overlay.invalidate()
    }

    override fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long, activityManager: ActivityManager) {
        runOnUiThread {
            binding.inferenceTime.text = "${inferenceTime}ms"
            binding.overlay.apply {
                setResults(boundingBoxes)
                invalidate()
            }
            Log.d("AppPerformanceInfo", "Just executed: onDetect object in UiThread")
            logPerformanceInfo.getAllPerformanceInfo(memoryInfo, activityManager, context = applicationContext)
        }
    }
}

class LogPerformanceInfo() {

    internal fun getAllPerformanceInfo(
        memoryInfo: ActivityManager.MemoryInfo,
        activityManager: ActivityManager,
        context: Context
    ) {
        activityManager.getMemoryInfo(memoryInfo)
        getMemoryInfo()
        printAdditionalAppMemoryUsage(context)
        printAppStorageUsage(context)
    }

    internal fun getMemoryInfo() {
        val runtime = Runtime.getRuntime()
        val totalMemory = runtime.totalMemory()
        val freeMemory = runtime.freeMemory()
        val maxMemory = runtime.maxMemory()

        Log.d("AppPerformanceInfo", "Total Memory: $totalMemory bytes")
        Log.d("AppPerformanceInfo", "Free Memory: $freeMemory bytes")
        Log.d("AppPerformanceInfo", "Max Memory: $maxMemory bytes")

        val heapSize: Long = Debug.getNativeHeapAllocatedSize()
        Log.d("AppPerformanceInfo", "Current allocated heap size: $heapSize bytes")
    }

    internal fun printAdditionalAppMemoryUsage(context: Context) {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)

        val totalMemory = memoryInfo.totalMem
        val appMemoryInfo = Debug.MemoryInfo()
        Debug.getMemoryInfo(appMemoryInfo)

        val dalvikPss = appMemoryInfo.dalvikPss * 1024L
        val nativePss = appMemoryInfo.nativePss * 1024L
        val otherPss = appMemoryInfo.otherPss * 1024L
        val totalPss = dalvikPss + nativePss + otherPss

        Log.d("AppPerformanceInfo", "Total Device Memory: ${formatSize(totalMemory)}")
        Log.d("AppPerformanceInfo", "App Dalvik Memory: ${formatSize(dalvikPss)}")
        Log.d("AppPerformanceInfo", "App Native Memory: ${formatSize(nativePss)}")
        Log.d("AppPerformanceInfo", "App Other Memory: ${formatSize(otherPss)}")
        Log.d("AppPerformanceInfo", "App Total PSS: ${formatSize(totalPss)}")
    }

    private fun printAppStorageUsage(context: Context) {
        val appDataDir =
            context.applicationContext.dataDir // switched minSdk from 21 to 24 to get rid of error
        val appDataStatFs = StatFs(appDataDir.path)
        val appDataAvailableBlocks = appDataStatFs.availableBlocksLong * appDataStatFs.blockSizeLong
        val appDataTotalBlocks = appDataStatFs.blockCountLong * appDataStatFs.blockSizeLong

        Log.d("AppPerformanceInfo", "App Data Total Storage: ${formatSize(appDataTotalBlocks)}")
        Log.d(
            "AppPerformanceInfo",
            "App Data Available Storage: ${formatSize(appDataAvailableBlocks)}"
        )

        val internalStorage = Environment.getDataDirectory()
        val internalStatFs = StatFs(internalStorage.path)
        val internalAvailableBlocks =
            internalStatFs.availableBlocksLong * internalStatFs.blockSizeLong
        val internalTotalBlocks = internalStatFs.blockCountLong * internalStatFs.blockSizeLong

        Log.d(
            "AppPerformanceInfo",
            "Device Internal Storage Total: ${formatSize(internalTotalBlocks)}"
        )
        Log.d(
            "AppPerformanceInfo",
            "Device Internal Storage Available: ${formatSize(internalAvailableBlocks)}"
        )
    }

    private fun formatSize(size: Long): String {
        val kb = 1024.0
        val mb = kb * kb
        val gb = mb * kb

        return when {
            size >= gb -> String.format("%.2f GB", size / gb)
            size >= mb -> String.format("%.2f MB", size / mb)
            size >= kb -> String.format("%.2f KB", size / kb)
            else -> "$size bytes"
        }
    }
}
