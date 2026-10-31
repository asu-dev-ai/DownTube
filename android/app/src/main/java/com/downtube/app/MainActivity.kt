package com.downtube.app

import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.downtube.app.databinding.ActivityMainBinding
import com.downtube.app.databinding.ItemDownloadBinding
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var job: Job? = null
    private var processId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.qualitySpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            Quality.entries.map { it.label },
        )

        binding.pasteButton.setOnClickListener { pasteFromClipboard() }
        binding.downloadButton.setOnClickListener { startDownload() }
        binding.cancelButton.setOnClickListener { cancelDownload() }

        handleSharedIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleSharedIntent(intent)
    }

    private fun handleSharedIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        val url = URL_PATTERN.find(text)?.value ?: return
        binding.urlInput.setText(url)
        binding.urlInput.setSelection(url.length)
    }

    private fun pasteFromClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        val url = URL_PATTERN.find(text)?.value ?: text.trim()
        if (url.isEmpty()) {
            toast("Clipboard is empty")
            return
        }
        binding.urlInput.setText(url)
        binding.urlInput.setSelection(url.length)
    }

    private fun startDownload() {
        if (job?.isActive == true) return

        val url = binding.urlInput.text?.toString()?.trim().orEmpty()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            toast("Paste a link that starts with http")
            return
        }

        val quality = Quality.entries.getOrElse(binding.qualitySpinner.selectedItemPosition) { Quality.BEST }
        val id = UUID.randomUUID().toString()
        processId = id

        setBusy(true)
        showStatus("Preparing…")

        job = lifecycleScope.launch {
            try {
                val (uri, name) = withContext(Dispatchers.IO) {
                    val workDir = File(cacheDir, "work").apply { mkdirs() }
                    Engine.init(applicationContext)

                    val before = workDir.listFiles().orEmpty().map { it.name }.toSet()
                    Engine.run(url, quality, workDir, id) { progress, eta ->
                        runOnUiThread { showProgress(progress, eta) }
                    }

                    val produced = workDir.listFiles()
                        ?.firstOrNull { it.isFile && it.name !in before }
                        ?: throw IllegalStateException("nothing was downloaded")

                    val saved = MediaSaver.publish(applicationContext, produced)
                    val savedName = produced.name
                    produced.delete()
                    saved to savedName
                }

                showStatus("Saved to Downloads/downtube")
                addDownloadRow(name, uri)
            } catch (e: YoutubeDL.CanceledException) {
                showStatus("Cancelled")
            } catch (e: CancellationException) {
                showStatus("Cancelled")
                throw e
            } catch (e: InterruptedException) {
                showStatus("Cancelled")
            } catch (e: Throwable) {
                showStatus("Failed: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                processId = null
                setBusy(false)
            }
        }
    }

    private fun cancelDownload() {
        val id = processId ?: return
        processId = null
        showStatus("Cancelling…")
        lifecycleScope.launch(Dispatchers.IO) {
            YoutubeDL.destroyProcessById(id)
        }
    }

    private fun showProgress(progress: Float, etaSeconds: Long) {
        binding.progressBar.isIndeterminate = progress <= 0f
        binding.progressBar.progress = progress.coerceIn(0f, 100f).toInt()
        val eta = if (etaSeconds > 0) " · ETA ${formatEta(etaSeconds)}" else ""
        showStatus("Downloading ${progress.toInt()}%$eta")
    }

    private fun showStatus(text: String) {
        binding.statusText.text = text
    }

    private fun setBusy(busy: Boolean) {
        binding.downloadButton.isEnabled = !busy
        binding.pasteButton.isEnabled = !busy
        binding.qualitySpinner.isEnabled = !busy
        binding.cancelButton.visibility = if (busy) View.VISIBLE else View.GONE
        binding.progressBar.visibility = if (busy) View.VISIBLE else View.GONE
        if (busy) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun addDownloadRow(name: String, uri: Uri) {
        val row = ItemDownloadBinding.inflate(LayoutInflater.from(this), binding.downloadsContainer, false)
        row.downloadName.text = name
        row.openButton.setOnClickListener {
            openFile(uri, MediaSaver.mimeType(name.substringAfterLast('.', "")))
        }
        binding.downloadsContainer.addView(row.root, 0)
    }

    private fun openFile(uri: Uri, mimeType: String) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            toast("No app on this phone can open that file")
        }
    }

    private fun formatEta(seconds: Long): String {
        val minutes = seconds / 60
        val rest = seconds % 60
        return "$minutes:${rest.toString().padStart(2, '0')}"
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        val URL_PATTERN = Regex("""https?://\S+""")
    }
}
