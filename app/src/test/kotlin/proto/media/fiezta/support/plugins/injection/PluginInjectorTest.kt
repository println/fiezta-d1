package proto.media.fiezta.support.plugins.injection

import org.junit.Assert.assertEquals
import org.junit.Test
import proto.media.fiezta.support.plugins.activation.PluginSource
import proto.media.fiezta.support.plugins.contract.PluginFiles
import proto.media.fiezta.support.plugins.contract.PluginLog
import proto.media.fiezta.support.plugins.contract.PluginStateStore
import proto.media.fiezta.support.plugins.model.ActivationState
import proto.media.fiezta.support.plugins.model.PageStage
import proto.media.fiezta.support.plugins.model.PluginEnv
import java.io.File

class PluginInjectorTest {

    private val manifest = """
        {
          "id": "site", "name": "Site", "version": 1,
          "rules": [
            { "match": ["https://m.site.com/*"], "core": "core.mobile.js" },
            { "match": ["https://www.site.com/*"], "core": "core.desktop.js" },
            { "match": ["https://*.site.com/*"], "events": "watch.events.js",
              "tweaks": [{ "script": "a.tweak.js", "runAt": "start" }] }
          ]
        }
    """.trimIndent()

    private class FakeFiles(private val manifest: String) : PluginFiles {
        override fun defaultIds() = listOf("site")
        override fun readDefault(pluginId: String, file: String) =
            if (file == "manifest.json") manifest else "script:$file"
        override fun readRuntime() = "runtime"
        override fun readPageBus() = "bus"
        override fun verifiedInstalledManifests(): Map<String, String> = emptyMap()
        override fun readInstalled(pluginId: String, file: String) = ""
        override fun install(pluginId: String, version: Int, from: File) = Unit
        override fun removeInstalled(pluginId: String) = false
    }

    private class MemoryStore : PluginStateStore {
        private var state = ActivationState(emptyList(), emptySet(), emptySet())
        override fun read() = state
        override fun write(state: ActivationState) {
            this.state = state
        }
    }

    private val evaluated = mutableListOf<String>()

    private fun injector(env: PluginEnv): PluginInjector {
        val source = PluginSource(FakeFiles(manifest), MemoryStore(), PluginLog.SILENT) { it() }
        return PluginInjector(
            evaluate = { evaluated += it; true },
            source = source,
            env = env,
            isRequirementMet = { _, _ -> true },
            isOptionEnabled = { _, _, _ -> true },
            onEventsCleared = {},
            log = PluginLog.SILENT
        )
    }

    @Test
    fun busAndCoreComeBeforeTweaksAndEvents() {
        injector(PluginEnv.CAR).onPageStage(PageStage.COMMITTED, "https://m.site.com/watch?v=1")

        assertEquals(
            listOf("bus", "script:core.mobile.js", "runtime", "script:watch.events.js", "script:a.tweak.js"),
            evaluated
        )
    }

    @Test
    fun coreRunsOncePerDocumentAndBusIsNotEvaluatedAgain() {
        val injector = injector(PluginEnv.CAR)
        injector.onPageStage(PageStage.COMMITTED, "https://m.site.com/watch?v=1")
        injector.onPageStage(PageStage.LOADED, "https://m.site.com/watch?v=1")
        injector.onPageStage(PageStage.ROUTE_CHANGED, "https://m.site.com/watch?v=2")

        assertEquals(1, evaluated.count { it == "script:core.mobile.js" })
        assertEquals(1, evaluated.count { it == "bus" })
    }

    @Test
    fun coreReachesThePhoneToo() {
        injector(PluginEnv.PHONE).onPageStage(PageStage.COMMITTED, "https://www.site.com/watch?v=1")

        assertEquals(listOf("bus", "script:core.desktop.js", "script:a.tweak.js"), evaluated)
    }

    @Test
    fun aRouteThatStartsMatchingACoreInjectsItInTheSameDocument() {
        val injector = injector(PluginEnv.PHONE)
        injector.onPageStage(PageStage.COMMITTED, "https://other.com/")
        injector.onPageStage(PageStage.ROUTE_CHANGED, "https://m.site.com/")

        assertEquals(listOf("bus", "script:core.mobile.js"), evaluated.filter { it.startsWith("bus") || it.contains("core") })
    }

    @Test
    fun aNewDocumentInjectsTheCoreAgain() {
        val injector = injector(PluginEnv.PHONE)
        injector.onPageStage(PageStage.COMMITTED, "https://m.site.com/")
        injector.onPageStage(PageStage.COMMITTED, "https://m.site.com/")

        assertEquals(2, evaluated.count { it == "script:core.mobile.js" })
    }
}
