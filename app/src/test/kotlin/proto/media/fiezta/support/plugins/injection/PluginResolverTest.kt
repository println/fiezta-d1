package proto.media.fiezta.support.plugins.injection

import proto.media.fiezta.support.plugins.model.PageStage
import proto.media.fiezta.support.plugins.model.PluginEnv
import proto.media.fiezta.support.plugins.model.PluginManifest
import proto.media.fiezta.support.plugins.model.PluginRule
import proto.media.fiezta.support.plugins.model.Requirement
import proto.media.fiezta.support.plugins.model.Rerun
import proto.media.fiezta.support.plugins.model.ResolvedCore
import proto.media.fiezta.support.plugins.model.ResolvedEvents
import proto.media.fiezta.support.plugins.model.TweakSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PluginResolverTest {

    private val shortsRule = PluginRule(listOf("https://youtube.com/shorts/*"), "shorts.events.js", emptyList())
    private val playlistRule = PluginRule(listOf("https://youtube.com/watch?*list=*"), "playlist.events.js", emptyList())
    private val watchRule = PluginRule(listOf("https://youtube.com/watch?*"), "watch.events.js", emptyList())
    private val tweaksRule = PluginRule(
        listOf("https://youtube.com/*"),
        null,
        listOf(
            TweakSpec("ad-prune.tweak.js", PageStage.COMMITTED, Rerun.LOAD, Requirement.ADBLOCK, PluginEnv.BOTH, null),
            TweakSpec("player-scroll.tweak.js", PageStage.COMMITTED, Rerun.LOAD, null, PluginEnv.CAR, null)
        )
    )
    private val youtube = PluginManifest("youtube", "YouTube", 1, listOf(shortsRule, playlistRule, watchRule, tweaksRule))
    private val generic = PluginManifest(
        "generic", "Generic", 1,
        listOf(PluginRule(listOf("*"), "generic.events.js", emptyList()))
    )

    @Test
    fun coreIsTheFirstMatchingCoreRuleOfEachPlugin() {
        val mobile = PluginRule(listOf("https://m.youtube.com/*"), null, emptyList(), listOf("core.common.js", "core.mobile.js"))
        val desktop = PluginRule(listOf("https://www.youtube.com/*"), null, emptyList(), listOf("core.common.js", "core.desktop.js"))
        val plugin = PluginManifest("youtube", "YouTube", 2, listOf(mobile, desktop, watchRule))
        val resolver = PluginResolver(listOf(plugin, generic))

        assertEquals(listOf(ResolvedCore("youtube", "core.common.js"), ResolvedCore("youtube", "core.mobile.js")), resolver.coresFor("https://m.youtube.com/watch?v=1"))
        assertEquals(listOf(ResolvedCore("youtube", "core.common.js"), ResolvedCore("youtube", "core.desktop.js")), resolver.coresFor("https://www.youtube.com/watch?v=1"))
        assertEquals(emptyList<ResolvedCore>(), resolver.coresFor("https://example.com/"))
    }

    @Test
    fun playlistUrlResolvesPlaylistNotWatch() {
        val resolver = PluginResolver(listOf(youtube, generic))
        val resolved = resolver.eventsFor("https://youtube.com/watch?v=1&list=abc")
        assertEquals(ResolvedEvents("youtube", "playlist.events.js"), resolved)
    }

    @Test
    fun shortUrlResolvesShorts() {
        val resolver = PluginResolver(listOf(youtube, generic))
        val resolved = resolver.eventsFor("https://youtube.com/shorts/abc")
        assertEquals(ResolvedEvents("youtube", "shorts.events.js"), resolved)
    }

    @Test
    fun youtubeHomeFallsBackToGeneric() {
        val resolver = PluginResolver(listOf(youtube, generic))
        val resolved = resolver.eventsFor("https://youtube.com/")
        assertEquals(ResolvedEvents("generic", "generic.events.js"), resolved)
    }

    @Test
    fun otherHostFallsBackToGeneric() {
        val resolver = PluginResolver(listOf(youtube, generic))
        val resolved = resolver.eventsFor("https://example.com/")
        assertEquals(ResolvedEvents("generic", "generic.events.js"), resolved)
    }

    @Test
    fun firstPluginInStackWinsRegardlessOfSpecificity() {
        val resolver = PluginResolver(listOf(generic, youtube))
        val resolved = resolver.eventsFor("https://youtube.com/watch?v=1")
        assertEquals(ResolvedEvents("generic", "generic.events.js"), resolved)
    }

    @Test
    fun noMatchReturnsNull() {
        val resolver = PluginResolver(listOf(youtube))
        assertNull(resolver.eventsFor("https://example.com/"))
    }

    @Test
    fun tweaksForCarEnvironmentIncludesBothTweaks() {
        val resolver = PluginResolver(listOf(youtube))
        val tweaks = resolver.tweaksFor("https://youtube.com/shorts/abc", PluginEnv.CAR)
        assertEquals(2, tweaks.size)
    }

    @Test
    fun tweaksForPhoneEnvironmentIncludesOnlyBothEnvTweak() {
        val resolver = PluginResolver(listOf(youtube))
        val tweaks = resolver.tweaksFor("https://youtube.com/shorts/abc", PluginEnv.PHONE)
        assertEquals(1, tweaks.size)
        assertEquals("ad-prune.tweak.js", tweaks[0].spec.script)
    }

    @Test
    fun tweaksAccumulateAcrossPlugins() {
        val other = PluginManifest(
            "other", "Other", 1,
            listOf(
                PluginRule(
                    listOf("https://youtube.com/*"),
                    null,
                    listOf(TweakSpec("some.tweak.js", PageStage.COMMITTED, Rerun.LOAD, null, PluginEnv.BOTH, null))
                )
            )
        )
        val resolver = PluginResolver(listOf(youtube, other))
        val tweaks = resolver.tweaksFor("https://youtube.com/watch?v=1", PluginEnv.CAR)
        assertEquals(3, tweaks.size)
    }

    @Test
    fun sameTweakFromTwoRulesDoesNotRepeat() {
        val plugin = PluginManifest(
            "youtube", "YouTube", 1,
            listOf(
                PluginRule(
                    listOf("https://youtube.com/watch?*"),
                    null,
                    listOf(TweakSpec("ad-prune.tweak.js", PageStage.COMMITTED, Rerun.LOAD, Requirement.ADBLOCK, PluginEnv.BOTH, null))
                ),
                PluginRule(
                    listOf("https://youtube.com/*"),
                    null,
                    listOf(TweakSpec("ad-prune.tweak.js", PageStage.COMMITTED, Rerun.LOAD, Requirement.ADBLOCK, PluginEnv.BOTH, null))
                )
            )
        )
        val resolver = PluginResolver(listOf(plugin))
        val tweaks = resolver.tweaksFor("https://youtube.com/watch?v=1", PluginEnv.CAR)
        assertEquals(1, tweaks.size)
    }
}
