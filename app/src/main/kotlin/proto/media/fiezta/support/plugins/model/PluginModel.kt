package proto.media.fiezta.support.plugins.model

data class PluginManifest(
    val id: String,
    val name: String,
    val version: Int,
    val rules: List<PluginRule>,
    val voice: VoiceSpec? = null,
    val fallback: Boolean = false
) {

    companion object {
        const val FILE_NAME = "manifest.json"
    }
}

data class VoiceSpec(val resource: String?, val script: String?, val searchEngineId: String?)

data class PluginRule(
    val match: List<String>,
    val events: String?,
    val tweaks: List<TweakSpec>,
    val core: String? = null
)

data class TweakSpec(
    val script: String,
    val runAt: PageStage,
    val rerun: Rerun,
    val requires: Requirement?,
    val env: PluginEnv,
    val option: TweakOption?
)

data class TweakOption(val key: String?, val default: Boolean)

enum class Rerun { LOAD, URL_CHANGE }

enum class PluginOrigin { DEFAULT, INSTALLED }

data class LoadedManifest(val manifest: PluginManifest, val origin: PluginOrigin)

enum class Requirement { ADBLOCK }

enum class PluginEnv { CAR, PHONE, BOTH }

enum class CarEvent(val jsName: String) {
    NEXT_CLICK("nextClick"), PREVIOUS_CLICK("previousClick"),
    NEXT_LONG_PRESS("nextLongPress"), PREVIOUS_LONG_PRESS("previousLongPress"),
    PLAY("play"), PAUSE("pause"), RESTART("restart"), SEEK_TO("seekTo"),
    QUEUE_ITEM("queueItem")
}

data class ResolvedEvents(val pluginId: String, val script: String)

data class ResolvedCore(val pluginId: String, val script: String)

data class ResolvedTweak(val pluginId: String, val spec: TweakSpec)

data class ActivationState(val order: List<String>, val seenDefaults: Set<String>, val removedDefaults: Set<String>)
