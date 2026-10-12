package proto.media.fiezta.support.plugins.manifest

import proto.media.fiezta.support.plugins.model.PageStage
import proto.media.fiezta.support.plugins.model.PluginEnv
import proto.media.fiezta.support.plugins.model.PluginManifest
import proto.media.fiezta.support.plugins.model.PluginOrigin
import proto.media.fiezta.support.plugins.model.PluginRule
import proto.media.fiezta.support.plugins.model.Requirement
import proto.media.fiezta.support.plugins.model.Rerun
import proto.media.fiezta.support.plugins.model.TweakOption
import proto.media.fiezta.support.plugins.model.TweakSpec
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginManifestValidatorTest {

    private fun manifest(
        id: String = "youtube",
        version: Int = 1,
        rules: List<PluginRule> = listOf(PluginRule(listOf("https://youtube.com/*"), "events.js", emptyList()))
    ) = PluginManifest(id, "YouTube", version, rules)

    @Test
    fun ruleWithOnlyACoreIsValid() {
        val rules = listOf(PluginRule(listOf("https://youtube.com/*"), null, emptyList(), listOf("core.js")))
        assertTrue(PluginManifestValidator.errors(manifest(rules = rules), PluginOrigin.INSTALLED).isEmpty())
    }

    @Test
    fun coreScriptNameCannotEscapeThePluginFolder() {
        val rules = listOf(PluginRule(listOf("https://youtube.com/*"), null, emptyList(), listOf("../core.js")))
        assertTrue(PluginManifestValidator.errors(manifest(rules = rules), PluginOrigin.INSTALLED).isNotEmpty())
    }

    @Test
    fun youtubeManifestIsValid() {
        val rules = listOf(
            PluginRule(
                listOf("https://youtube.com/shorts/*", "https://*.youtube.com/shorts/*"),
                "shorts.events.js",
                emptyList()
            ),
            PluginRule(
                listOf("https://youtube.com/watch?*", "https://*.youtube.com/watch?*"),
                "watch.events.js",
                emptyList()
            ),
            PluginRule(
                listOf("https://youtube.com/*", "https://*.youtube.com/*"),
                null,
                listOf(
                    TweakSpec("ad-prune.tweak.js", PageStage.COMMITTED, Rerun.LOAD, Requirement.ADBLOCK, PluginEnv.BOTH, null),
                    TweakSpec(
                        "player-scroll.tweak.js", PageStage.COMMITTED, Rerun.LOAD, null, PluginEnv.CAR,
                        TweakOption("pref_youtube_scroll_to_player", true)
                    )
                )
            )
        )
        assertTrue(PluginManifestValidator.errors(manifest(rules = rules), PluginOrigin.DEFAULT).isEmpty())
    }

    @Test
    fun invalidIdWithPathTraversal() {
        assertTrue(PluginManifestValidator.errors(manifest(id = "../x"), PluginOrigin.DEFAULT).isNotEmpty())
    }

    @Test
    fun invalidIdWithUppercase() {
        assertTrue(PluginManifestValidator.errors(manifest(id = "YouTube"), PluginOrigin.DEFAULT).isNotEmpty())
    }

    @Test
    fun invalidIdWhenBlank() {
        assertTrue(PluginManifestValidator.errors(manifest(id = ""), PluginOrigin.DEFAULT).isNotEmpty())
    }

    @Test
    fun invalidIdWhenTooLong() {
        assertTrue(PluginManifestValidator.errors(manifest(id = "a".repeat(33)), PluginOrigin.DEFAULT).isNotEmpty())
    }

    @Test
    fun invalidVersionZero() {
        assertTrue(PluginManifestValidator.errors(manifest(version = 0), PluginOrigin.DEFAULT).isNotEmpty())
    }

    @Test
    fun invalidRuleWithoutEventsOrTweaks() {
        val rules = listOf(PluginRule(listOf("https://youtube.com/*"), null, emptyList()))
        assertTrue(PluginManifestValidator.errors(manifest(rules = rules), PluginOrigin.DEFAULT).isNotEmpty())
    }

    @Test
    fun invalidRuleWithEmptyMatch() {
        val rules = listOf(PluginRule(emptyList(), "events.js", emptyList()))
        assertTrue(PluginManifestValidator.errors(manifest(rules = rules), PluginOrigin.DEFAULT).isNotEmpty())
    }

    @Test
    fun universalMatchInvalidForInstalled() {
        val rules = listOf(PluginRule(listOf("*"), "events.js", emptyList()))
        assertTrue(PluginManifestValidator.errors(manifest(rules = rules), PluginOrigin.INSTALLED).isNotEmpty())
    }

    @Test
    fun universalMatchValidForDefault() {
        val rules = listOf(PluginRule(listOf("*"), "events.js", emptyList()))
        assertTrue(PluginManifestValidator.errors(manifest(rules = rules), PluginOrigin.DEFAULT).isEmpty())
    }

    @Test
    fun invalidScriptWithParentDirectory() {
        val rules = listOf(PluginRule(listOf("https://youtube.com/*"), "../events.js", emptyList()))
        assertTrue(PluginManifestValidator.errors(manifest(rules = rules), PluginOrigin.DEFAULT).isNotEmpty())
    }

    @Test
    fun invalidScriptWithLeadingSlash() {
        val rules = listOf(PluginRule(listOf("https://youtube.com/*"), "/events.js", emptyList()))
        assertTrue(PluginManifestValidator.errors(manifest(rules = rules), PluginOrigin.DEFAULT).isNotEmpty())
    }

    @Test
    fun invalidScriptWithBackslash() {
        val rules = listOf(PluginRule(listOf("https://youtube.com/*"), "sub\\events.js", emptyList()))
        assertTrue(PluginManifestValidator.errors(manifest(rules = rules), PluginOrigin.DEFAULT).isNotEmpty())
    }
}
