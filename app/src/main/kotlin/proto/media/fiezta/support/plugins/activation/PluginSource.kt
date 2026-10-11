package proto.media.fiezta.support.plugins.activation

import proto.media.fiezta.support.plugins.contract.PluginFiles
import proto.media.fiezta.support.plugins.contract.PluginLog
import proto.media.fiezta.support.plugins.contract.PluginStateStore
import proto.media.fiezta.support.plugins.manifest.PluginManifestJson
import proto.media.fiezta.support.plugins.model.ActivationState
import proto.media.fiezta.support.plugins.model.InvalidPluginException
import proto.media.fiezta.support.plugins.model.LoadedManifest
import proto.media.fiezta.support.plugins.model.PluginManifest
import proto.media.fiezta.support.plugins.model.PluginOrigin
import proto.media.fiezta.support.plugins.model.TweakOption
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

class PluginSource(
    private val files: PluginFiles,
    private val store: PluginStateStore,
    private val log: PluginLog,
    private val postToMain: (() -> Unit) -> Unit
) {

    private val scriptCache = ConcurrentHashMap<String, String>()

    @Volatile
    private var runtimeCache: String? = null

    @Volatile
    private var pageBusCache: String? = null

    @Volatile
    private var stackCache: List<PluginManifest>? = null

    @Volatile
    private var originCache: Map<String, PluginOrigin> = emptyMap()

    private val stackListeners = CopyOnWriteArraySet<() -> Unit>()

    fun stack(): List<PluginManifest> {
        stackCache?.let { return it }

        val available = loadDefaults() + loadInstalled()
        val defaultIds = loadDefaults().map { it.manifest.id }
        val availableIds = available.map { it.manifest.id }.toSet()

        val state = store.read()
        val withDefaults = PluginActivationStack.withNewDefaults(state, defaultIds)
        val pruned = withDefaults.copy(order = withDefaults.order.filter { it in availableIds })
        val fallbackIds = available.filter { it.manifest.fallback }.map { it.manifest.id }
        val repaired = PluginActivationStack.withFallbacksLast(pruned, fallbackIds)
        if (repaired != state) {
            writeState(repaired)
        }

        val stack = PluginActivationStack.stack(repaired, available)
        originCache = stack.associate { it.id to winningOrigin(it.id, available) }
        stackCache = stack
        return stack
    }

    fun activate(id: String) {
        writeState(PluginActivationStack.activate(store.read(), id))
    }

    fun deactivate(id: String) {
        if (isFallback(id)) return
        writeState(PluginActivationStack.deactivate(store.read(), id))
    }

    fun restoreDefaults() {
        val defaultIds = loadDefaults().map { it.manifest.id }
        writeState(PluginActivationStack.restoreDefaults(store.read(), defaultIds))
    }

    fun install(from: File) {
        val manifest = PluginManifestJson.parse(File(from, PluginManifest.FILE_NAME).readText(), PluginOrigin.INSTALLED)
        val id = manifest.id
        try {
            files.install(id, manifest.version, from)

            val state = store.read()
            val updated = if (id !in state.order) PluginActivationStack.activate(state, id) else state
            if (updated != state) writeState(updated)
        } catch (e: InvalidPluginException) {
            throw e
        } catch (e: Exception) {
            throw InvalidPluginException("failed to install plugin $id: ${e.message}")
        } finally {
            invalidateCaches()
        }
    }

    fun remove(id: String) {
        if (isFallback(id)) return
        if (files.removeInstalled(id)) {
            writeState(PluginActivationStack.deactivate(store.read(), id))
        } else {
            writeState(PluginActivationStack.removeDefault(store.read(), id))
        }
        invalidateCaches()
    }

    fun isFallback(id: String): Boolean =
        allKnownPlugins().any { it.manifest.id == id && it.manifest.fallback }

    fun addStackListener(listener: () -> Unit) {
        stackListeners.add(listener)
    }

    fun script(pluginId: String, script: String): String =
        scriptCache.getOrPut("$pluginId/$script") {
            if (originCache[pluginId] == PluginOrigin.INSTALLED) {
                files.readInstalled(pluginId, script)
            } else {
                files.readDefault(pluginId, script)
            }
        }

    fun runtime(): String =
        runtimeCache ?: files.readRuntime().also { runtimeCache = it }

    fun pageBus(): String =
        pageBusCache ?: files.readPageBus().also { pageBusCache = it }

    fun allKnownPlugins(): List<LoadedManifest> =
        (loadDefaults() + loadInstalled())
            .groupBy { it.manifest.id }
            .map { (_, candidates) -> candidates.reduce(::pickWinner) }

    fun effectiveOptionKey(pluginId: String, script: String, option: TweakOption?): Pair<String, Boolean> {
        stack()
        return if (originCache[pluginId] == PluginOrigin.INSTALLED) {
            InstalledOptionKey.of(pluginId, script) to false
        } else {
            (option?.key ?: InstalledOptionKey.of(pluginId, script)) to (option?.default ?: true)
        }
    }

    private fun winningOrigin(id: String, available: List<LoadedManifest>): PluginOrigin =
        available.filter { it.manifest.id == id }.reduce(::pickWinner).origin

    private fun pickWinner(best: LoadedManifest, candidate: LoadedManifest): LoadedManifest = when {
        candidate.manifest.version > best.manifest.version -> candidate
        candidate.manifest.version < best.manifest.version -> best
        candidate.origin == PluginOrigin.INSTALLED -> candidate
        else -> best
    }

    private fun writeState(state: ActivationState) {
        store.write(state)
        invalidateCaches()
    }

    private fun loadDefaults(): List<LoadedManifest> {
        val ids = try {
            files.defaultIds()
        } catch (e: Exception) {
            log.warn("failed to read defaults.json", e)
            emptyList()
        }
        return ids.mapNotNull { id ->
            try {
                val text = files.readDefault(id, PluginManifest.FILE_NAME)
                LoadedManifest(PluginManifestJson.parse(text, PluginOrigin.DEFAULT), PluginOrigin.DEFAULT)
            } catch (e: Exception) {
                log.warn("failed to load default plugin $id", e)
                null
            }
        }
    }

    private fun loadInstalled(): List<LoadedManifest> =
        files.verifiedInstalledManifests().mapNotNull { (id, text) ->
            try {
                LoadedManifest(PluginManifestJson.parse(text, PluginOrigin.INSTALLED), PluginOrigin.INSTALLED)
            } catch (e: Exception) {
                log.warn("failed to load installed plugin $id", e)
                null
            }
        }

    private fun invalidateCaches() {
        stackCache = null
        originCache = emptyMap()
        scriptCache.clear()
        val listeners = stackListeners.toList()
        if (listeners.isNotEmpty()) {
            postToMain { listeners.forEach { it() } }
        }
    }
}
