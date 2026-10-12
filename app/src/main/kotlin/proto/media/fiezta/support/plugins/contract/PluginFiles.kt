package proto.media.fiezta.support.plugins.contract

import java.io.File

interface PluginFiles {

    fun defaultIds(): List<String>

    fun readDefault(pluginId: String, file: String): String

    fun readRuntime(): String

    fun readPageBus(): String

    fun verifiedInstalledManifests(): Map<String, String>

    fun readInstalled(pluginId: String, file: String): String

    fun install(pluginId: String, version: Int, from: File)

    fun removeInstalled(pluginId: String): Boolean
}
