package proto.media.fiezta.shared.plugins

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import proto.media.fiezta.support.plugins.model.PluginManifest
import proto.media.fiezta.support.plugins.model.InvalidPluginException
import proto.media.fiezta.support.plugins.contract.PluginFiles
import proto.media.fiezta.support.plugins.contract.PluginLog
import java.io.File
import java.security.MessageDigest

class AndroidPluginFiles(private val context: Context, private val log: PluginLog) : PluginFiles {

    override fun defaultIds(): List<String> {
        val array = JSONArray(readAsset("$ASSETS_ROOT/defaults.json"))
        return (0 until array.length()).map { array.getString(it) }
    }

    override fun readDefault(pluginId: String, file: String): String = readAsset("$ASSETS_ROOT/$pluginId/$file")

    override fun readRuntime(): String = readAsset("$ASSETS_ROOT/fiezta-runtime.js")

    override fun readPageBus(): String = readAsset("$ASSETS_ROOT/fiezta-page.js")

    override fun readInstalled(pluginId: String, file: String): String =
        File(File(pluginsDir(), pluginId), file).readText()

    override fun verifiedInstalledManifests(): Map<String, String> {
        val dir = pluginsDir()
        if (!dir.exists()) return emptyMap()
        val index = readIndex()
        val filesById = (0 until index.length()).associate { i ->
            val entry = index.getJSONObject(i)
            entry.getString("id") to entry.getJSONObject("files")
        }
        val pluginDirs = dir.listFiles { file -> file.isDirectory && !file.name.startsWith(".") } ?: emptyArray()
        val manifests = LinkedHashMap<String, String>()
        for (pluginDir in pluginDirs) {
            val id = pluginDir.name
            try {
                val expectedFiles = filesById[id]
                if (expectedFiles == null) {
                    log.warn("installed plugin $id has no index entry")
                    continue
                }
                val filesMatch = pluginDir.listFiles { file -> file.isFile }?.all { file ->
                    expectedFiles.has(file.name) && expectedFiles.getString(file.name) == sha256(file)
                } ?: false
                if (!filesMatch) {
                    log.warn("installed plugin $id failed file verification")
                    continue
                }
                manifests[id] = File(pluginDir, PluginManifest.FILE_NAME).readText()
            } catch (e: Exception) {
                log.warn("failed to load installed plugin $id", e)
            }
        }
        return manifests
    }

    override fun install(pluginId: String, version: Int, from: File) {
        val tmpDir = File(pluginsDir(), ".tmp-$pluginId")
        try {
            pluginsDir().mkdirs()
            if (tmpDir.exists()) tmpDir.deleteRecursively()
            from.copyRecursively(tmpDir, overwrite = true)

            val files = tmpDir.listFiles { file -> file.isFile } ?: emptyArray()
            val hashes = JSONObject()
            files.forEach { file -> hashes.put(file.name, sha256(file)) }

            val finalDir = File(pluginsDir(), pluginId)
            finalDir.deleteRecursively()
            if (!tmpDir.renameTo(finalDir)) {
                throw InvalidPluginException("failed to install plugin $pluginId")
            }
            updateIndex(pluginId, version, hashes)
        } finally {
            if (tmpDir.exists()) tmpDir.deleteRecursively()
        }
    }

    override fun removeInstalled(pluginId: String): Boolean {
        val installedDir = File(pluginsDir(), pluginId)
        if (!installedDir.exists()) return false
        installedDir.deleteRecursively()
        writeIndex(indexWithout(pluginId))
        return true
    }

    private fun readAsset(path: String): String =
        context.assets.open(path).bufferedReader().use { it.readText() }

    private fun pluginsDir(): File = File(context.filesDir, "plugins")

    private fun indexFile(): File = File(pluginsDir(), "index.json")

    private fun readIndex(): JSONArray =
        try {
            JSONArray(indexFile().readText())
        } catch (e: Exception) {
            JSONArray()
        }

    private fun writeIndex(index: JSONArray) {
        pluginsDir().mkdirs()
        indexFile().writeText(index.toString())
    }

    private fun indexWithout(pluginId: String): JSONArray {
        val index = readIndex()
        val remaining = JSONArray()
        for (i in 0 until index.length()) {
            val entry = index.getJSONObject(i)
            if (entry.getString("id") != pluginId) remaining.put(entry)
        }
        return remaining
    }

    private fun updateIndex(pluginId: String, version: Int, files: JSONObject) {
        val index = indexWithout(pluginId)
        index.put(
            JSONObject()
                .put("id", pluginId)
                .put("version", version)
                .put("files", files)
                .put("installedAt", System.currentTimeMillis())
        )
        writeIndex(index)
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

    private companion object {
        const val ASSETS_ROOT = "plugins"
    }
}
