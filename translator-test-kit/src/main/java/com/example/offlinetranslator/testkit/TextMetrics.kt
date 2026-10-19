package com.example.offlinetranslator.testkit

import java.util.Locale

data class EditScore(val errors: Int, val referenceCount: Int) {
    val rate: Double get() = errors.toDouble() / referenceCount
    operator fun plus(other: EditScore) = EditScore(errors + other.errors, referenceCount + other.referenceCount)
}

object TextMetrics {
    class Prepared internal constructor(internal val words: List<String>,internal val characters: List<Int>)
    fun prepare(reference: String): Prepared {
        val normalized=normalize(reference)
        return Prepared(normalized.split(' ').filter(String::isNotEmpty),codePoints(normalized.replace(" ","")))
    }
    fun words(reference: Prepared,actual: String): EditScore = score(reference.words,normalize(actual).split(' ').filter(String::isNotEmpty))
    fun characters(reference: Prepared,actual: String): EditScore = score(reference.characters,codePoints(normalize(actual).replace(" ","")))
    fun normalize(text: String): String {
        require(text.length<=4096) { "Scoring text exceeds quota" }
        return text.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim().replace(Regex("\\s+"), " ")
    }

    fun words(reference: String, actual: String): EditScore = score(
        normalize(reference).split(' ').filter(String::isNotEmpty),
        normalize(actual).split(' ').filter(String::isNotEmpty),
    )
    fun characters(reference: String, actual: String): EditScore = score(
        codePoints(normalize(reference).replace(" ", "")),
        codePoints(normalize(actual).replace(" ", "")),
    )
    private fun codePoints(text: String): List<Int> {
        val points=ArrayList<Int>(text.length)
        var offset=0
        while(offset<text.length) {
            val point=Character.codePointAt(text,offset)
            points.add(point);offset+=Character.charCount(point)
        }
        return points
    }
    private fun <T> score(reference: List<T>, actual: List<T>): EditScore {
        require(reference.isNotEmpty() && reference.size <= 4096 && actual.size <= 4096)
        val previous = IntArray(actual.size + 1) { it }
        for ((index, expected) in reference.withIndex()) {
            var diagonal = previous[0]
            previous[0] = index + 1
            for (column in actual.indices) {
                val above = previous[column + 1]
                previous[column + 1] = minOf(previous[column] + 1, above + 1, diagonal + if (expected == actual[column]) 0 else 1)
                diagonal = above
            }
        }
        return EditScore(previous.last(), reference.size)
    }
}

object SemanticChecks {
    class Prepared internal constructor(internal val phrases: List<Pair<String,String>>)
    fun prepare(required: List<String>): Prepared {
        require(required.size<=2048)
        return Prepared(required.map {it to " ${normalize(it)} "})
    }
    fun missing(actual: String,required: Prepared): List<String> {
        val normalized=" ${normalize(actual)} "
        return required.phrases.filterNot {it.second in normalized}.map {it.first}
    }
    fun normalize(value: String) = TextMetrics.normalize(value.lowercase(Locale.ROOT)
        .replace("don't", "do not").replace("don’t", "do not").replace('ё', 'е'))
    fun missing(actual: String, required: List<String>): List<String> {
        val normalized = " ${normalize(actual)} "
        return required.filterNot { " ${normalize(it)} " in normalized }
    }
}
