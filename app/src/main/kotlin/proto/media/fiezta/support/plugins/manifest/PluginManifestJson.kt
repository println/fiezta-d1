package proto.media.fiezta.support.plugins.manifest

import proto.media.fiezta.support.plugins.model.InvalidPluginException
import proto.media.fiezta.support.plugins.model.PageStage
import proto.media.fiezta.support.plugins.model.PluginEnv
import proto.media.fiezta.support.plugins.model.PluginManifest
import proto.media.fiezta.support.plugins.model.PluginOrigin
import proto.media.fiezta.support.plugins.model.PluginRule
import proto.media.fiezta.support.plugins.model.Requirement
import proto.media.fiezta.support.plugins.model.Rerun
import proto.media.fiezta.support.plugins.model.TweakOption
import proto.media.fiezta.support.plugins.model.TweakSpec
import proto.media.fiezta.support.plugins.model.VoiceSpec
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

object PluginManifestJson {

    fun parse(text: String, origin: PluginOrigin): PluginManifest {
        val manifest = try {
            parseUnchecked(text)
        } catch (e: JSONException) {
            throw InvalidPluginException("malformed manifest: ${e.message}")
        }
        val errors = PluginManifestValidator.errors(manifest, origin)
        if (errors.isNotEmpty()) {
            throw InvalidPluginException(errors.joinToString("; "))
        }
        return manifest
    }

    private fun parseUnchecked(text: String): PluginManifest {
        val json = JSONObject(text)
        if (!json.has("id") || !json.has("name") || !json.has("version") || !json.has("rules")) {
            throw InvalidPluginException("manifest missing a required field")
        }
        return PluginManifest(
            id = json.getString("id"),
            name = json.getString("name"),
            version = json.getInt("version"),
            rules = jsonObjects(json.getJSONArray("rules")).map(::parseRule),
            voice = if (json.has("voice")) parseVoice(json.getJSONObject("voice")) else null,
            fallback = json.optBoolean("fallback", false)
        )
    }

    private fun parseVoice(json: JSONObject): VoiceSpec = VoiceSpec(
        resource = if (json.has("resource")) json.getString("resource") else null,
        script = if (json.has("script")) json.getString("script") else null,
        searchEngineId = if (json.has("search")) json.getString("search") else null
    )

    private fun parseRule(json: JSONObject): PluginRule = PluginRule(
        match = jsonStrings(json.getJSONArray("match")),
        events = if (json.has("events")) json.getString("events") else null,
        tweaks = if (json.has("tweaks")) jsonObjects(json.getJSONArray("tweaks")).map(::parseTweak) else emptyList(),
        core = if (json.has("core")) parseCore(json.get("core")) else emptyList()
    )

    private fun parseCore(value: Any): List<String> = when (value) {
        is String -> listOf(value)
        is JSONArray -> jsonStrings(value)
        else -> throw InvalidPluginException("invalid core: $value")
    }

    private fun parseTweak(json: JSONObject): TweakSpec {
        if (!json.has("script")) {
            throw InvalidPluginException("tweak missing script")
        }
        return TweakSpec(
            script = json.getString("script"),
            runAt = if (json.has("runAt")) parseRunAt(json.getString("runAt")) else PageStage.LOADED,
            rerun = if (json.has("rerun")) parseRerun(json.getString("rerun")) else Rerun.LOAD,
            requires = if (json.has("requires")) parseRequirement(json.getString("requires")) else null,
            env = if (json.has("env")) parseEnv(json.getString("env")) else PluginEnv.BOTH,
            option = if (json.has("option")) parseOption(json.getJSONObject("option")) else null
        )
    }

    private fun parseRunAt(value: String): PageStage = when (value) {
        "start" -> PageStage.COMMITTED
        "ready" -> PageStage.LOADED
        else -> PageStage.fromJs(value) ?: throw InvalidPluginException("invalid runAt: $value")
    }

    private fun parseRerun(value: String): Rerun = when (value) {
        "load" -> Rerun.LOAD
        "urlChange" -> Rerun.URL_CHANGE
        else -> throw InvalidPluginException("invalid rerun: $value")
    }

    private fun parseRequirement(value: String): Requirement = when (value) {
        "adblock" -> Requirement.ADBLOCK
        else -> throw InvalidPluginException("invalid requires: $value")
    }

    private fun parseEnv(value: String): PluginEnv = when (value) {
        "car" -> PluginEnv.CAR
        "phone" -> PluginEnv.PHONE
        "both" -> PluginEnv.BOTH
        else -> throw InvalidPluginException("invalid env: $value")
    }

    private fun parseOption(json: JSONObject): TweakOption {
        if (!json.has("default")) {
            throw InvalidPluginException("option missing default")
        }
        return TweakOption(
            key = if (json.has("key")) json.getString("key") else null,
            default = json.getBoolean("default")
        )
    }

    private fun jsonObjects(array: JSONArray): List<JSONObject> =
        (0 until array.length()).map { array.getJSONObject(it) }

    private fun jsonStrings(array: JSONArray): List<String> =
        (0 until array.length()).map { array.getString(it) }
}
