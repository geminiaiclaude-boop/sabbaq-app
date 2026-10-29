package app.sabbaq.core

import app.sabbaq.core.engine.ScreenSampler
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The server's automatic learning must never propose a word the reader already knows: its
 * reserved list has to equal the app's built-in vocabulary. A word added to the reader without
 * the migration (or the other way round) fails here.
 */
class LearningServerContractTest {
    private val migrations = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "supabase/migrations") }
        .first { it.isDirectory }

    @Test fun reservedWordsEqualTheBuiltInVocabulary() {
        val sql = migrations.listFiles()!!
            .filter { it.name.endsWith(".sql") && it.readText().contains("function private.reserved_words()") }
            .maxBy { it.name }
            .readText()
        val array = sql.substringAfter("function private.reserved_words()").substringAfter("select array[").substringBefore("]::text[]")
        val server = Regex("'((?:[^']|'')*)'").findAll(array).map { it.groupValues[1].replace("''", "'") }.toSet()
        assertEquals(ScreenSampler.vocabulary(), server)
    }
}
