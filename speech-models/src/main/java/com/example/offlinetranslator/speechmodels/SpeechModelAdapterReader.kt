package com.example.offlinetranslator.speechmodels

import com.example.offlinetranslator.core.LanguageId
import com.example.offlinetranslator.core.SpeechModelRole
import org.json.JSONArray
import org.json.JSONObject

/** Exposes only bounded scalars and inventoried references to compiled backend decoders. */
class SpeechModelAdapterReader internal constructor(
    val role: SpeechModelRole,
    val languages: Set<LanguageId>,
    val sampleRateHz: Int,
    private val json: JSONObject,
    private val inventory: Map<String, SpeechModelPackage>,
) {
    internal val requiredRefs = mutableListOf<SpeechModelRef>()

    fun fields(vararg names: String) = json.fields(*names)
    fun text(name: String): String = json.text(name)
    fun intRange(name: String, range: IntRange): Int = json.intRange(name, range)
    fun positiveFloat(name: String, max: Float): Float = json.positiveFloat(name, max)

    fun ref(name: String, directory: Boolean = false): SpeechModelRef {
        val value = json.getJSONObject(name)
        value.fields("packageId", "path")
        val result = SpeechModelRef(value.text("packageId"), value.text("path"))
        require(SpeechModelCatalog.safeRelativePath(result.path)) { "Unsafe model reference" }
        val pkg = requireNotNull(inventory[result.packageId]) { "Undeclared reference package" }
        val files = if (directory) pkg.files.filter { it.path.startsWith(result.path + "/") }
            else pkg.files.filter { it.path == result.path }
        require(files.isNotEmpty()) { "Undeclared reference file or directory" }
        requiredRefs += files.map { SpeechModelRef(pkg.id, it.path) }
        return result
    }
}

internal fun JSONObject.fields(vararg allowed: String) {
    require(keys().asSequence().all { it in allowed }) { "Unknown catalog field" }
}

internal fun JSONObject.text(name: String): String {
    val value = get(name)
    require(value is String && value.isNotEmpty()) { "Invalid string: $name" }
    return value
}

internal fun JSONArray.strings(): List<String> = (0 until length()).map {
    val value = get(it)
    require(value is String && value.isNotEmpty()) { "Invalid string array" }
    value
}

internal fun JSONObject.integer(name: String): Long {
    val value = get(name)
    require(value is Byte || value is Short || value is Int || value is Long) { "Invalid integer: $name" }
    return (value as Number).toLong()
}

internal fun JSONObject.intRange(name: String, range: IntRange): Int {
    val value = integer(name)
    require(value in range.first.toLong()..range.last.toLong()) { "Invalid parameter: $name" }
    return value.toInt()
}

internal fun JSONObject.positiveFloat(name: String, max: Float): Float {
    val value = get(name)
    require(value is Number) { "Invalid number: $name" }
    val result = value.toFloat()
    require(result.isFinite() && result > 0f && result <= max) { "Invalid parameter: $name" }
    return result
}
