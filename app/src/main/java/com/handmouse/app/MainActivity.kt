package com.handmouse.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.android.material.chip.Chip
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var handOverlay: HandOverlayView
    private lateinit var tvStatus: TextView
    private lateinit var btnConnectPc: Button
    private lateinit var btnFlipCamera: Button
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var handLandmarkerHelper: HandLandmarkerHelper
    private lateinit var hidManager: BluetoothHidManager
    private val mouseController = HandMouseController()
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var isConnected = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val cameraGranted = grants[Manifest.permission.CAMERA] == true ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (cameraGranted) startCamera() else Toast.makeText(this, "Camera permission required", Toast.LENGTH_LONG).show()
        hidManager.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        handOverlay = findViewById(R.id.handOverlay)
        tvStatus = findViewById(R.id.tvStatus)
        btnConnectPc = findViewById(R.id.btnConnectPc)
        btnFlipCamera = findViewById(R.id.btnFlipCamera)

        val chipShowCamera = findViewById<Chip>(R.id.chipShowCamera)
        val chipMousePreview = findViewById<Chip>(R.id.chipMousePreview)
        val chipShowFingers = findViewById<Chip>(R.id.chipShowFingers)
        val chipRigLinesOnly = findViewById<Chip>(R.id.chipRigLinesOnly)
        val seekSensitivity = findViewById<SeekBar>(R.id.seekSensitivity)

        previewView.visibility = View.INVISIBLE

        chipShowCamera.setOnCheckedChangeListener { _, checked ->
            handOverlay.showCamera = checked
            previewView.visibility = if (checked && !chipRigLinesOnly.isChecked) View.VISIBLE else View.INVISIBLE
        }
        chipMousePreview.setOnCheckedChangeListener { _, checked -> handOverlay.showMousePreview = checked }
        chipShowFingers.setOnCheckedChangeListener { _, checked -> handOverlay.showAllFingers = checked }
        chipRigLinesOnly.setOnCheckedChangeListener { _, checked ->
            handOverlay.rigLinesOnly = checked
            previewView.visibility = if (!checked && chipShowCamera.isChecked) View.VISIBLE else View.INVISIBLE
        }
        seekSensitivity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                mouseController.sensitivity = 0.4f + (progress / 10f)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        btnFlipCamera.setOnClickListener {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            mouseController.isFrontCamera = (lensFacing == CameraSelector.LENS_FACING_FRONT)
            startCamera()
        }
        btnConnectPc.setOnClickListener { showBluetoothPicker() }

        cameraExecutor = Executors.newSingleThreadExecutor()
        hidManager = BluetoothHidManager(this) { status, connected ->
            runOnUiThread {
                isConnected = connected
                tvStatus.text = "BT: $status"
                btnConnectPc.text = if (connected) "Disconnect" else "Connect PC"
            }
        }
        handLandmarkerHelper = HandLandmarkerHelper(
            context = this,
            onResults = { result, width, height ->
                val mouseOut = mouseController.process(result)
                hidManager.sendMouseReport(mouseOut.leftClick, mouseOut.rightClick, mouseOut.dx, mouseOut.dy, mouseOut.wheel)
                runOnUiThread {
                    handOverlay.updateFrame(mouseOut, width, height, lensFacing == CameraSelector.LENS_FACING_FRONT)
                }
            },
            onError = { msg -> runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() } }
        )
        requestAppPermissions()
    }

    private fun requestAppPermissions() {
        val perms = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            perms.add(Manifest.permission.BLUETOOTH)
            perms.add(Manifest.permission.BLUETOOTH_ADMIN)
        }
        permissionLauncher.launch(perms.toTypedArray())
    }

    @SuppressLint("MissingPermission")
    private fun showBluetoothPicker() {
        if (isConnected) { hidManager.disconnect(); return }
        val paired = hidManager.getBondedDevices()
        if (paired.isEmpty()) {
            Toast.makeText(this, "First pair your phone & PC in Android Bluetooth Settings, then tap Connect PC.", Toast.LENGTH_LONG).show()
            return
        }
        val names = paired.map { "${it.name ?: "Unknown"} (${it.address})" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Select Paired PC")
            .setItems(names) { _, idx -> hidManager.connect(paired[idx]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also { it.setAnalyzer(cameraExecutor) { imageProxy -> handLandmarkerHelper.detectLiveStream(imageProxy) } }
            val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, selector, preview, imageAnalyzer)
            } catch (e: Exception) {
                Toast.makeText(this, "Camera failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        handLandmarkerHelper.close()
        hidManager.release()
    }
}
