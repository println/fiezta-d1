package proto.media.fiezta.support.plugins.activation

import proto.media.fiezta.support.plugins.contract.PluginFiles
import proto.media.fiezta.support.plugins.contract.PluginLog
import proto.media.fiezta.support.plugins.contract.PluginStateStore
import proto.media.fiezta.support.plugins.model.ActivationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PluginSourceTest {

    private class FakeFiles(private val installed: Set<String>) : PluginFiles {
        override fun defaultIds(): List<String> = emptyList()
        override fun readDefault(pluginId: String, file: String) = "default:$pluginId/$file"
        override fun readRuntime() = "runtime"
        override fun readPageBus() = "bus"
        override fun verifiedInstalledManifests(): Map<String, String> = emptyMap()
        override fun readInstalled(pluginId: String, file: String) = "installed:$pluginId/$file"
        override fun install(pluginId: String, version: Int, from: File) = Unit
        override fun removeInstalled(pluginId: String) = pluginId in installed
    }

    private class FakeStore(var state: ActivationState) : PluginStateStore {
        override fun read() = state
        override fun write(state: ActivationState) {
            this.state = state
        }
    }

    private val posted = mutableListOf<() -> Unit>()

    private fun source(store: PluginStateStore, installed: Set<String> = emptySet()) =
        PluginSource(FakeFiles(installed), store, PluginLog.SILENT) { posted.add(it) }

    private fun state(vararg order: String) = ActivationState(order.toList(), order.toSet(), emptySet())

    @Test
    fun removingAnInstalledPluginOnlyDeactivatesIt() {
        val store = FakeStore(state("youtube", "generic"))

        source(store, installed = setOf("youtube")).remove("youtube")

        assertEquals(listOf("generic"), store.state.order)
        assertTrue(store.state.removedDefaults.isEmpty())
    }

    @Test
    fun removingADefaultPluginRemembersTheRemoval() {
        val store = FakeStore(state("youtube", "generic"))

        source(store).remove("youtube")

        assertEquals(listOf("generic"), store.state.order)
        assertEquals(setOf("youtube"), store.state.removedDefaults)
    }

    @Test
    fun stackListenersRunThroughTheMainThreadPoster() {
        val store = FakeStore(state())
        val source = source(store)
        var notified = 0
        source.addStackListener { notified++ }

        source.activate("youtube")

        assertEquals(0, notified)
        posted.forEach { it() }
        assertEquals(1, notified)
    }

    @Test
    fun scriptsOfPluginsThatAreNotInstalledComeFromTheDefaults() {
        assertEquals("default:youtube/watch.events.js", source(FakeStore(state())).script("youtube", "watch.events.js"))
    }

    @Test
    fun runtimeIsReadOnce() {
        var reads = 0
        val files = object : PluginFiles by FakeFiles(emptySet()) {
            override fun readRuntime(): String {
                reads++
                return "runtime"
            }
        }
        val source = PluginSource(files, FakeStore(state()), PluginLog.SILENT) { it() }

        source.runtime()
        source.runtime()

        assertEquals(1, reads)
    }
}
