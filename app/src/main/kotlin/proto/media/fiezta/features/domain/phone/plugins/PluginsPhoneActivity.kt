package proto.media.fiezta.features.domain.phone.plugins

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import proto.media.fiezta.R
import proto.media.fiezta.features.domain.core.bookmarks.BookmarkMonogram
import proto.media.fiezta.features.domain.core.settings.SettingsUtils
import proto.media.fiezta.support.plugins.model.PluginManifest
import proto.media.fiezta.support.plugins.model.PluginOrigin
import proto.media.fiezta.support.plugins.manifest.PluginPlatforms
import proto.media.fiezta.support.plugins.model.InvalidPluginException
import proto.media.fiezta.shared.plugins.AppPlugins
import proto.media.fiezta.support.plugins.activation.PluginSource
import proto.media.fiezta.shared.plugins.PluginZipInstaller
import java.io.File
import java.security.MessageDigest

class PluginsPhoneActivity : AppCompatActivity() {

    private lateinit var source: PluginSource
    private lateinit var listContainer: LinearLayout
    private var query = ""

    private val openZip = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { reviewZip(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_plugins)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        source = AppPlugins.source(this)
        listContainer = findViewById(R.id.plugins_list)
        findViewById<EditText>(R.id.plugins_search).doAfterTextChanged {
            query = it?.toString().orEmpty().trim()
            refresh()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, MENU_INSTALL, Menu.NONE, R.string.plugins_install)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(Menu.NONE, MENU_VOICE_LISTENER, Menu.NONE, R.string.voice_listener_title)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(Menu.NONE, MENU_FEEDBACK, Menu.NONE, R.string.plugins_feedback)
            .setCheckable(true)
            .setChecked(SettingsUtils.isPluginFeedbackEnabled(this))
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(Menu.NONE, MENU_RESTORE_DEFAULTS, Menu.NONE, R.string.plugins_restore_defaults)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> {
            finish()
            true
        }
        MENU_INSTALL -> {
            openZip.launch(arrayOf("application/zip"))
            true
        }
        MENU_VOICE_LISTENER -> {
            startActivity(Intent(this, VoiceListenerPhoneActivity::class.java))
            true
        }
        MENU_FEEDBACK -> {
            item.isChecked = !item.isChecked
            SettingsUtils.setPluginFeedbackEnabled(this, item.isChecked)
            true
        }
        MENU_RESTORE_DEFAULTS -> {
            confirmRestoreDefaults()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun refresh() {
        listContainer.removeAllViews()

        val active = source.stack()
        val activeIds = active.map { it.id }.toSet()
        val inactive = source.allKnownPlugins()
            .map { it.manifest }
            .filter { it.id !in activeIds }

        if (active.isEmpty() && inactive.isEmpty()) {
            listContainer.addView(sectionLabel(getString(R.string.plugins_empty)))
            return
        }

        val matchingActive = active.withIndex().filter { matchesQuery(it.value) }
        if (matchingActive.isNotEmpty()) {
            listContainer.addView(sectionLabel(getString(R.string.plugins_active_section)))
            matchingActive.forEach { (index, manifest) -> addPlugin(manifest, position = index + 1) }
        }
        val matchingInactive = inactive.filter(::matchesQuery)
        if (matchingInactive.isNotEmpty()) {
            listContainer.addView(sectionLabel(getString(R.string.plugins_inactive_section)))
            matchingInactive.forEach { manifest -> addPlugin(manifest, position = null) }
        }
    }

    private fun matchesQuery(manifest: PluginManifest): Boolean =
        manifest.name.contains(query, ignoreCase = true)

    private fun sectionLabel(text: String): TextView =
        (layoutInflater.inflate(R.layout.item_plugin_section, listContainer, false) as TextView).apply {
            this.text = text
        }

    private fun addPlugin(manifest: PluginManifest, position: Int?) {
        val row = layoutInflater.inflate(R.layout.item_plugin, listContainer, false)
        bindMonogram(row.findViewById(R.id.plugin_monogram), manifest.name)
        row.findViewById<TextView>(R.id.plugin_name).text =
            if (position != null) "$position. ${manifest.name}" else manifest.name

        val originText = if (isInstalled(manifest.id)) R.string.plugins_origin_installed else R.string.plugins_origin_default
        row.findViewById<TextView>(R.id.plugin_origin).text = "${getString(originText)} · v${manifest.version}"

        row.bindPlatforms(PluginPlatforms.of(manifest))

        row.setOnClickListener { startActivity(PluginDetailsPhoneActivity.intent(this, manifest.id)) }
        listContainer.addView(row)
    }

    private fun bindMonogram(view: TextView, name: String) {
        val initials = BookmarkMonogram.of(name, null)
        val palette = resources.getIntArray(R.array.car_favorite_monogram_palette)
        view.text = initials
        view.background = GradientDrawable().apply {
            setColor(palette[BookmarkMonogram.paletteIndex(initials, palette.size)])
            cornerRadius = dp(10).toFloat()
        }
    }

    private fun confirmRestoreDefaults() {
        AlertDialog.Builder(this)
            .setTitle(R.string.plugins_restore_defaults)
            .setMessage(R.string.plugins_restore_defaults_message)
            .setPositiveButton(R.string.plugins_restore_defaults) { _, _ ->
                source.restoreDefaults()
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun isInstalled(id: String): Boolean =
        source.allKnownPlugins().any { it.manifest.id == id && it.origin == PluginOrigin.INSTALLED }

    private fun reviewZip(uri: Uri) {
        val extracted = try {
            PluginZipInstaller.extract(this, uri)
        } catch (e: InvalidPluginException) {
            Toast.makeText(this, getString(R.string.plugins_install_failed, e.message), Toast.LENGTH_LONG).show()
            return
        }
        val manifestText = File(extracted, "manifest.json").readText()

        val body = ScrollView(this).apply {
            val pad = dp(16)
            setPadding(pad, pad, pad, pad)
            addView(TextView(this@PluginsPhoneActivity).apply {
                text = buildString {
                    append(manifestText)
                    append("\n\n")
                    append(getString(R.string.plugins_review_files))
                    append(":\n")
                    extracted.listFiles { file -> file.isFile }?.sortedBy { it.name }?.forEach { file ->
                        append(file.name)
                        append(" — ")
                        append(sha256(file))
                        append('\n')
                    }
                }
                setTextIsSelectable(true)
                textSize = 12f
            })
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.plugins_review_title)
            .setView(body)
            .setPositiveButton(R.string.plugins_review_install) { _, _ ->
                try {
                    source.install(extracted)
                    refresh()
                } catch (e: InvalidPluginException) {
                    Toast.makeText(this, getString(R.string.plugins_install_failed, e.message), Toast.LENGTH_LONG).show()
                } finally {
                    extracted.deleteRecursively()
                }
            }
            .setNegativeButton(R.string.plugins_review_cancel) { _, _ -> extracted.deleteRecursively() }
            .setOnCancelListener { extracted.deleteRecursively() }
            .show()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var read = input.read(buffer)
            while (read >= 0) {
                digest.update(buffer, 0, read)
                read = input.read(buffer)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    companion object {
        private const val MENU_INSTALL = 1
        private const val MENU_RESTORE_DEFAULTS = 2
        private const val MENU_VOICE_LISTENER = 3
        private const val MENU_FEEDBACK = 4
    }
}
