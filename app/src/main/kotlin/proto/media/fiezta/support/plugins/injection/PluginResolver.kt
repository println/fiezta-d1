package proto.media.fiezta.support.plugins.injection

import proto.media.fiezta.support.plugins.manifest.UrlMatch
import proto.media.fiezta.support.plugins.model.PluginEnv
import proto.media.fiezta.support.plugins.model.PluginManifest
import proto.media.fiezta.support.plugins.model.ResolvedCore
import proto.media.fiezta.support.plugins.model.ResolvedEvents
import proto.media.fiezta.support.plugins.model.ResolvedTweak

class PluginResolver(private val stack: List<PluginManifest>) {

    fun eventsFor(url: String): ResolvedEvents? {
        for (plugin in stack) {
            for (rule in plugin.rules) {
                val script = rule.events ?: continue
                if (rule.match.any { UrlMatch.matches(it, url) }) {
                    return ResolvedEvents(plugin.id, script)
                }
            }
        }
        return null
    }

    fun coresFor(url: String): List<ResolvedCore> = stack.flatMap { plugin ->
        val rule = plugin.rules.firstOrNull { it.core.isNotEmpty() && it.match.any { pattern -> UrlMatch.matches(pattern, url) } }
        rule?.core.orEmpty().map { ResolvedCore(plugin.id, it) }
    }

    fun tweaksFor(url: String, env: PluginEnv): List<ResolvedTweak> {
        val seen = mutableSetOf<Pair<String, String>>()
        val result = mutableListOf<ResolvedTweak>()
        for (plugin in stack) {
            for (rule in plugin.rules) {
                if (rule.match.none { UrlMatch.matches(it, url) }) continue
                for (tweak in rule.tweaks) {
                    if (tweak.env != env && tweak.env != PluginEnv.BOTH) continue
                    val key = plugin.id to tweak.script
                    if (!seen.add(key)) continue
                    result += ResolvedTweak(plugin.id, tweak)
                }
            }
        }
        return result
    }
}
