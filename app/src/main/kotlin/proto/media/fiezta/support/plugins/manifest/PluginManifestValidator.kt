package proto.media.fiezta.support.plugins.manifest

import proto.media.fiezta.support.plugins.model.PluginManifest
import proto.media.fiezta.support.plugins.model.PluginOrigin
import proto.media.fiezta.support.plugins.voice.VoiceSearchUrl

object PluginManifestValidator {

    private val ID_PATTERN = Regex("^[a-z0-9][a-z0-9_-]{0,31}$")

    fun errors(manifest: PluginManifest, origin: PluginOrigin): List<String> {
        val errors = mutableListOf<String>()

        if (!ID_PATTERN.matches(manifest.id)) {
            errors += "invalid id: ${manifest.id}"
        }
        if (manifest.name.isBlank()) {
            errors += "name must not be blank"
        }
        if (manifest.version < 1) {
            errors += "version must be >= 1"
        }
        if (manifest.rules.isEmpty()) {
            errors += "manifest must have at least one rule"
        }

        if (manifest.fallback && origin == PluginOrigin.INSTALLED) {
            errors += "fallback is not allowed for installed plugins"
        }

        manifest.voice?.let { voice ->
            if ((voice.resource == null) == (voice.searchEngineId == null)) {
                errors += "voice must have exactly one of resource or search"
            }
            voice.resource?.let { resource ->
                if (!VoiceSearchUrl.isValidResource(resource)) {
                    errors += "voice resource must start with / and hold one ${VoiceSearchUrl.SEARCH_TERMS}: $resource"
                }
                if (PluginDomain.of(manifest) == null) {
                    errors += "voice resource needs rules that match a single domain"
                }
            }
            voice.script?.let { script ->
                if (!isValidScriptName(script)) {
                    errors += "invalid voice script name: $script"
                }
                if (voice.resource == null) {
                    errors += "voice script needs a resource"
                }
            }
            voice.searchEngineId?.let { id ->
                if (!ID_PATTERN.matches(id)) {
                    errors += "invalid voice search engine: $id"
                }
            }
        }

        manifest.rules.forEachIndexed { index, rule ->
            if (rule.match.isEmpty()) {
                errors += "rule $index: match must not be empty"
            }
            rule.match.forEach { pattern ->
                if (origin == PluginOrigin.INSTALLED && UrlMatch.isUniversal(pattern)) {
                    errors += "rule $index: universal match not allowed for installed plugins: $pattern"
                }
            }
            if (rule.events == null && rule.tweaks.isEmpty() && rule.core.isEmpty()) {
                errors += "rule $index: must have events, tweaks or core"
            }
            rule.events?.let { script ->
                if (!isValidScriptName(script)) {
                    errors += "rule $index: invalid events script name: $script"
                }
            }
            rule.core.filterNot(::isValidScriptName).forEach { script ->
                errors += "rule $index: invalid core script name: $script"
            }
            rule.tweaks.forEachIndexed { tweakIndex, tweak ->
                if (!isValidScriptName(tweak.script)) {
                    errors += "rule $index tweak $tweakIndex: invalid script name: ${tweak.script}"
                }
            }
        }

        return errors
    }

    private fun isValidScriptName(script: String): Boolean =
        script.isNotBlank() && !script.contains("..") && !script.startsWith("/") && !script.contains("\\")
}
