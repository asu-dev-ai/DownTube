package com.downtube.app

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.downtube.app.databinding.ActivityMainBinding
import com.google.android.material.chip.Chip
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity(), QueueAdapter.Host {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: QueueAdapter

    private var selectedQuality: Quality = Quality.BEST
    private var updating = false
    private var lastDetectedLink: String? = null

    private val storeListener = DownloadStore.Listener { runOnUiThread { render() } }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DynamicColors.applyToActivityIfAvailable(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = QueueAdapter(this)
        binding.queueList.layoutManager = LinearLayoutManager(this)
        binding.queueList.adapter = adapter

        buildQualityChips()

        binding.pasteButton.setOnClickListener { pasteFromClipboard() }
        binding.downloadButton.setOnClickListener { startDownload() }
        binding.browseButton.setOnClickListener { openBrowser(cookieMode = false) }
        binding.loginButton.setOnClickListener { openBrowser(cookieMode = true) }
        binding.updateButton.setOnClickListener { updateYtDlp() }
        binding.clearButton.setOnClickListener { DownloadStore.clearFinished() }

        binding.soundsSwitch.isChecked = Prefs.soundsEnabled(this)
        binding.soundsSwitch.setOnCheckedChangeListener { _, checked -> Prefs.setSoundsEnabled(this, checked) }
        binding.vibrateSwitch.isChecked = Prefs.vibrateEnabled(this)
        binding.vibrateSwitch.setOnCheckedChangeListener { _, checked -> Prefs.setVibrateEnabled(this, checked) }

        askNotificationPermission()
        handleSharedIntent(intent)
        render()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleSharedIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        DownloadStore.addListener(storeListener)
        render()
        maybeOfferClipboardLink()
    }

    override fun onStop() {
        DownloadStore.removeListener(storeListener)
        super.onStop()
    }

    // -------------------------------------------------------------- user input

    private fun buildQualityChips() {
        binding.qualityGroup.removeAllViews()
        Quality.entries.forEach { quality ->
            val chip = Chip(this).apply {
                id = View.generateViewId()
                text = quality.label
                isCheckable = true
                isChecked = quality == selectedQuality
                setOnClickListener {
                    selectedQuality = quality
                    binding.qualityGroup.check(id)
                }
            }
            binding.qualityGroup.addView(chip)
        }
    }

    private fun pasteFromClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        val url = URL_PATTERN.find(text)?.value ?: text.trim()
        if (url.isEmpty()) {
            toast(getString(R.string.clipboard_empty))
            return
        }
        binding.urlInput.setText(url)
        binding.urlInput.setSelection(url.length)
    }

    private fun startDownload() {
        val url = binding.urlInput.text?.toString()?.trim().orEmpty()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            toast(getString(R.string.need_link))
            return
        }

        val item = DownloadItem(
            id = DownloadService.newId(),
            url = url,
            quality = selectedQuality,
            playlist = binding.playlistSwitch.isChecked,
        )
        DownloadStore.add(item)
        DownloadService.start(this)
        Feedback.tick(this)

        binding.urlInput.text?.clear()
        toast(getString(R.string.added_to_queue))
    }

    private fun handleSharedIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        val url = URL_PATTERN.find(text)?.value ?: return
        binding.urlInput.setText(url)
        binding.urlInput.setSelection(url.length)
    }

    // ----------------------------------------------------------------- actions

    private fun openBrowser(cookieMode: Boolean) {
        val intent = Intent(this, BrowserActivity::class.java)
        intent.putExtra(BrowserActivity.EXTRA_COOKIES, cookieMode)
        binding.urlInput.text?.toString()?.trim()?.takeIf { it.startsWith("http") }?.let {
            intent.putExtra(BrowserActivity.EXTRA_URL, it)
        }
        startActivity(intent)
    }

    private fun updateYtDlp() {
        if (updating) return
        updating = true
        binding.updateButton.isEnabled = false
        toast(getString(R.string.updating_engine))

        lifecycleScope.launch {
            val message = withContext(Dispatchers.IO) {
                runCatching { Engine.updateYtDlp(applicationContext) }
                    .getOrElse { getString(R.string.update_failed, it.message ?: "unknown error") }
            }
            updating = false
            binding.updateButton.isEnabled = true
            toast(message)
        }
    }

    // ------------------------------------------------------- queue interactions

    override fun onCancel(item: DownloadItem) {
        DownloadService.cancel(this, item.id)
    }

    override fun onRetry(item: DownloadItem) {
        DownloadStore.update(item.id) {
            it.status = DownloadStatus.QUEUED
            it.progress = 0
            it.etaSeconds = 0
            it.message = null
        }
        DownloadService.start(this)
    }

    override fun onOpen(item: DownloadItem) {
        val uri = item.uri ?: return
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .setData(uri)
                .putExtra(PlayerActivity.EXTRA_TITLE, item.outputName ?: item.title),
        )
    }

    override fun onDelete(item: DownloadItem) {
        item.uri?.let { uri -> runCatching { contentResolver.delete(uri, null, null) } }
        DownloadStore.remove(item.id)
    }

    // ------------------------------------------------------------------ render

    private fun render() {
        val items = DownloadStore.all().asReversed()
        adapter.submit(items)

        binding.emptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        binding.queueList.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE

        val pending = DownloadStore.pendingCount()
        binding.downloadsHeader.text = if (pending > 0) {
            getString(R.string.downloads_pending, items.size, pending)
        } else {
            getString(R.string.downloads_count, items.size)
        }
        binding.clearButton.visibility = if (items.any { it.status == DownloadStatus.DONE }) View.VISIBLE else View.GONE
    }

    // ------------------------------------------------------------------ helper

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // -------------------------------------------------- clipboard auto-detect

    /**
     * Copies of links arrive from any app; when one is sitting in the
     * clipboard, offer to play or download it before the user types anything.
     */
    private fun maybeOfferClipboardLink() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        val url = URL_PATTERN.find(text)?.value?.trim() ?: return

        if (url == lastDetectedLink) return
        if (binding.urlInput.text?.isNotEmpty() == true) return

        lastDetectedLink = url
        binding.urlInput.setText(url)
        binding.urlInput.setSelection(url.length)
        showLinkPopup(url)
    }

    private fun showLinkPopup(url: String) {
        val platform = Platform.detect(url)
        val message = if (platform != null) {
            getString(R.string.link_detected_platform, platform.label, url)
        } else {
            getString(R.string.link_detected, url)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.link_detected_title)
            .setMessage(message)
            .setCancelable(true)
            .setPositiveButton(R.string.play) { _, _ -> playFromClipboardLink(url) }
            .setNeutralButton(R.string.download) { _, _ -> startDownload() }
            .setNegativeButton(R.string.close) { dialog, _ -> dialog.dismiss() }
            .show()
    }

    /** Streams the link straight into the built-in player, no download first. */
    private fun playFromClipboardLink(url: String) {
        toast(getString(R.string.preparing_playback))
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    Engine.init(applicationContext)
                    val request = YoutubeDLRequest(url)
                    request.addOption("-f", "best")
                    request.addOption("--no-playlist")
                    YoutubeDL.getInfo(request)
                }
            }

            result.onSuccess { info ->
                val stream = info.url
                if (stream.isNullOrBlank()) {
                    toast(getString(R.string.status_failed))
                    return@onSuccess
                }
                startActivity(
                    Intent(this@MainActivity, PlayerActivity::class.java)
                        .setData(Uri.parse(stream))
                        .putExtra(PlayerActivity.EXTRA_TITLE, info.title ?: url),
                )
            }.onFailure { error ->
                toast(FriendlyError.from(error))
            }
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        val URL_PATTERN = Regex("""https?://\S+""")
    }
}
