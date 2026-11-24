package com.blackstrings

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.json.JSONObject
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var cryptoManager: CryptoManager
    private lateinit var outboxManager: OutboxManager
    private lateinit var mailboxManager: MailboxManager
    private lateinit var torManager: TorManager
    private lateinit var bridge: WebViewBridge

    private var pendingCameraFile: File? = null
    private var pendingMediaKind: String = "file"

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) handlePickedUri(uri, pendingMediaKind)
    }

    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success: Boolean ->
        val f = pendingCameraFile
        if (success && f != null && f.exists()) {
            val payload = JSONObject().apply {
                put("type", "image")
                put("path", f.absolutePath)
                put("name", f.name)
            }.toString()
            dispatchJsEvent("onMediaPicked", payload)
        }
    }

    private val qrLauncher = registerForActivityResult(ScanContract()) { result ->
        val contents = result.contents ?: return@registerForActivityResult
        dispatchJsEvent("onQrScanned", JSONObject().put("value", contents).toString())
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        cryptoManager = CryptoManager(this)
        outboxManager = OutboxManager(this)
        mailboxManager = MailboxManager(this, outboxManager)
        torManager = TorManager(this, cryptoManager, outboxManager, mailboxManager)
        bridge = WebViewBridge(this, torManager, cryptoManager, outboxManager, mailboxManager)

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            addJavascriptInterface(bridge, "BlackstringsBridge")
        }
        setContentView(webView)

        torManager.onUiUpdate = { event ->
            dispatchJsEvent("onNativeEvent", JSONObject().put("event", event).toString())
        }

        webView.loadUrl("file:///android_asset/www/index.html")

        requestRuntimePermissions()
        torManager.startAndSync30s()
        WorkerSync.schedule(this)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                webView.evaluateJavascript("window.handleAndroidBack && window.handleAndroidBack()") { res ->
                    if (res != "true") {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        torManager.wakeForSyncAndSleep(30_000L)
    }

    override fun onDestroy() {
        super.onDestroy()
        torManager.shutdown()
    }

    fun launchMediaPicker(kind: String) {
        pendingMediaKind = kind
        when (kind) {
            "camera" -> {
                val camDir = File(cacheDir, "camera").apply { mkdirs() }
                val photo = File(camDir, "cam_${System.currentTimeMillis()}.jpg")
                pendingCameraFile = photo
                val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", photo)
                cameraLauncher.launch(uri)
            }
            "image" -> filePickerLauncher.launch("image/*")
            "video" -> filePickerLauncher.launch("video/*")
            else -> filePickerLauncher.launch("*/*")
        }
    }

    fun launchQrScanner() {
        val opts = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("Scan Friend's BLACKSTRINGS Onion QR")
            setBeepEnabled(false)
            setOrientationLocked(true)
        }
        qrLauncher.launch(opts)
    }

    private fun handlePickedUri(uri: Uri, kind: String) {
        val name = queryFileName(uri) ?: "file_${System.currentTimeMillis()}"
        val mediaDir = File(filesDir, "media").apply { mkdirs() }
        val dest = File(mediaDir, "${System.currentTimeMillis()}_$name")
        contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
        val payload = JSONObject().apply {
            put("type", if (kind == "camera") "image" else kind)
            put("path", dest.absolutePath)
            put("name", name)
        }.toString()
        dispatchJsEvent("onMediaPicked", payload)
    }

    private fun queryFileName(uri: Uri): String? {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return c.getString(idx)
            }
        }
        return null
    }

    private fun dispatchJsEvent(fnName: String, jsonArg: String) {
        val escaped = JSONObject.quote(jsonArg)
        runOnUiThread {
            webView.evaluateJavascript("window.$fnName && window.$fnName($escaped);", null)
        }
    }

    private fun requestRuntimePermissions() {
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
            needed.add(Manifest.permission.READ_MEDIA_IMAGES)
            needed.add(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            needed.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }
}
