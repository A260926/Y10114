package com.beust.klaxon

import com.beust.klaxon.token.Value
import org.assertj.core.api.Assertions.assertThat
import org.testng.Assert
import org.testng.annotations.Test
import java.io.ByteArrayInputStream
import java.io.Reader
import java.io.StringReader
import java.math.BigInteger
import kotlin.random.Random

/**
 * Centralized boundary tests for the interaction between the [Lexer], the [Parser] state
 * machine, streaming mode, [PathMatcher] and object mapping.
 *
 * Inputs are fed through [ChunkingReader] / [ChunkingInputStream], deterministic random
 * streams (fixed seed) that split surrogate pairs, backslash escapes, UTF-8 multi-byte
 * sequences and CRLF pairs across Reader (and byte) chunk boundaries.
 */
@Test
class ParserContractTest {

    companion object {
        const val CHUNK_SEEDS = "1, 42, 20260930"
    }

    //
    // Helpers
    //

    /** A Reader that hands out between 1 and [maxChunk] chars per read, driven by [random]. */
    private class ChunkingReader(
        private val source: CharSequence,
        seed: Long,
        private val maxChunk: Int = 3
    ) : Reader() {
        private val random = Random(seed)
        private var pos = 0

        override fun read(cbuf: CharArray, off: Int, len: Int): Int {
            if (pos >= source.length) return -1
            val chunk = 1 + random.nextInt(maxChunk.coerceAtMost(len).coerceAtLeast(1))
            val n = minOf(chunk, len, source.length - pos)
            for (i in 0 until n) cbuf[off + i] = source[pos + i]
            pos += n
            return n
        }

        override fun close() {}
    }

    /** An InputStream that hands out between 1 and [maxChunk] bytes per read, driven by [random]. */
    private class ChunkingInputStream(
        bytes: ByteArray,
        seed: Long,
        private val maxChunk: Int = 3
    ) : ByteArrayInputStream(bytes) {
        private val random = Random(seed)

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= count) return -1
            val chunk = 1 + random.nextInt(maxChunk.coerceAtMost(len).coerceAtLeast(1))
            val n = minOf(chunk, len, count - pos)
            System.arraycopy(buf, pos, b, off, n)
            pos += n
            return n
        }
    }

    private val seeds = longArrayOf(1L, 42L, 20260930L)

    /** All ways to feed an input to a full parser, including a UTF-8 byte-splitting stream. */
    private fun fullInputs(json: String): List<Pair<String, () -> Any>> =
        buildList {
            add("direct" to { Parser.default().parse(StringBuilder(json)) })
            seeds.forEach { seed ->
                add("chars@$seed" to {
                    Parser.default().parse(ChunkingReader(json, seed))
                })
                add("utf8@$seed" to {
                    Parser.default().parse(ChunkingInputStream(json.toByteArray(Charsets.UTF_8), seed))
                })
            }
        }

    private fun streamParse(json: String, seed: Long, lexer: Lexer? = null): Any {
        val reader = JsonReader(ChunkingReader(json, seed))
        val usedLexer = lexer ?: reader.lexer
        return Parser.default(emptyList(), usedLexer, streaming = true).parse(reader)
    }

    private fun failureLabel(json: String, label: String, ex: Throwable): String =
        "input=${json.take(60)} via=$label -> ${ex::class.simpleName}: " +
            ex.message?.replace(Regex("\\s+"), " ")

    //
    // Boundary decoding: surrogate pairs, escapes, UTF-8 multi-byte and CRLF
    //

    // A surrogate pair (U+1F600), U+4E2D (3 UTF-8 bytes), escapes and a CRLF between elements.
    private val boundaryJson =
        "[\r\n" +
            "\"emoji-\uD83D\uDE00-end\",\r\n" +
            "\"中-文\",\r\n" +
            "\"esc-\\n\\t\\u00e9\\\"\\\\\\/end\",\r\n" +
            "\"bmp-\\u4e2d-x\",\r\n" +
            "1\r\n" +
            "]"

    private val expectedList = listOf(
        "emoji-\uD83D\uDE00-end",
        "中-文",
        "esc-\n\té\"\\/end",
        "bmp-中-x",
        1
    )

    fun fullDecodingAcrossAllChunkBoundaries() {
        println("[ParserContractTest] decoding chunk seeds (char+utf8): $CHUNK_SEEDS")
        fullInputs(boundaryJson).forEach { (label, parse) ->
            val result = try {
                parse()
            } catch (e: Throwable) {
                throw AssertionError("full parse failed via=$label (seeds=${seeds.toList()})", e)
            }
            assertThat(result).isInstanceOf(JsonArray::class.java)
            @Suppress("UNCHECKED_CAST")
            assertThat((result as JsonArray<Any?>).toList())
                .withFailMessage("decoding mismatch via=$label")
                .isEqualTo(expectedList)
        }
    }

    fun streamingDecodingMatchesFullAcrossChunkBoundaries() {
        val full = Parser.default().parse(StringBuilder(boundaryJson))
        seeds.forEach { seed ->
            println("[ParserContractTest] streaming decoding with chunk seed=$seed")
            val streamed = streamParse(boundaryJson, seed)
            assertThat(streamed)
                .withFailMessage("streaming mismatch at seed=$seed")
                .isEqualTo(full)
        }
    }

    //
    // Stable error positions when boundaries move
    //

    private fun normalizedMessage(ex: Throwable): String =
        (ex.message ?: "").replace(Regex("\\s+"), " ").trim()

    private fun stableFullFailure(json: String) {
        var firstClass: String? = null
        var firstMessage: String? = null
        fullInputs(json).forEach { (label, parse) ->
            val ex = try {
                parse()
                throw AssertionError("expected failure via=$label for input=${json.take(80)}")
            } catch (e: AssertionError) {
                throw e
            } catch (e: Throwable) {
                e
            }
            val cls = ex::class.qualifiedName
            val msg = normalizedMessage(ex)
            if (firstClass == null) {
                firstClass = cls
                firstMessage = msg
            } else {
                assertThat(cls)
                    .withFailMessage(
                        "exception type moved: '$firstClass' vs '$cls' via=$label; " +
                            "seed set=${seeds.toList()}; input=${json.take(80)}"
                    )
                    .isEqualTo(firstClass)
                assertThat(msg)
                    .withFailMessage(
                        "error position/text moved:\n  '$firstMessage'\nvs '$msg' via=$label; " +
                            "seed set=${seeds.toList()}; input=${json.take(80)}"
                    )
                    .isEqualTo(firstMessage)
            }
        }
    }

    fun unterminatedStringHasStablePosition() {
        stableFullFailure("[\"abc")
    }

    fun truncatedUnicodeEscapeHasStablePosition() {
        stableFullFailure("[\"\\u12\"]")
    }

    fun invalidUnicodeHexHasStablePosition() {
        stableFullFailure("[\"\\u12xy\"]")
    }

    fun trailingCommaValueHasStablePosition() {
        stableFullFailure("1,2")
        stableFullFailure("{\"a\":1},{\"b\":2}")
    }

    fun unterminatedAfterEscapeHasStablePosition() {
        stableFullFailure("[\"esc\\n")
    }

    //
    // Consecutive root values: streaming reads them one by one, full mode rejects the trailer
    //

    fun consecutiveRootObjectsReadOneByOneWhenStreaming() {
        val json = "{\"a\":1},{\"b\":2},{\"c\":3}"
        val expected = listOf(
            JsonObject().apply { this["a"] = 1 },
            JsonObject().apply { this["b"] = 2 },
            JsonObject().apply { this["c"] = 3 }
        )
        seeds.forEach { seed ->
            val reader = JsonReader(ChunkingReader(json, seed))
            val actual = (1..3).map {
                Parser.default(emptyList(), reader.lexer, streaming = true).parse(reader)
            }
            assertThat(actual)
                .withFailMessage("consecutive root objects mismatch at seed=$seed")
                .isEqualTo(expected)
        }
    }

    fun consecutiveMixedRootContainersReadOneByOneWhenStreaming() {
        val json = "[1,2] , {\"b\":2} , [3,4]"
        seeds.forEach { seed ->
            val reader = JsonReader(ChunkingReader(json, seed))
            val first = Parser.default(emptyList(), reader.lexer, streaming = true).parse(reader)
            val second = Parser.default(emptyList(), reader.lexer, streaming = true).parse(reader)
            val third = Parser.default(emptyList(), reader.lexer, streaming = true).parse(reader)
            @Suppress("UNCHECKED_CAST")
            assertThat((first as JsonArray<Any?>).toList())
                .withFailMessage("first root mismatch at seed=$seed").isEqualTo(listOf(1, 2))
            assertThat(second)
                .withFailMessage("second root mismatch at seed=$seed")
                .isEqualTo(JsonObject().apply { this["b"] = 2 })
            @Suppress("UNCHECKED_CAST")
            assertThat((third as JsonArray<Any?>).toList())
                .withFailMessage("third root mismatch at seed=$seed").isEqualTo(listOf(3, 4))
        }
    }

    fun fullModeRejectsTrailingRootValue() {
        // Comma-delimited trailer is detected (whitespace-separated trailer is consumed by EOF,
        // which is asserted separately as the current, frozen behavior).
        listOf("1,2", "{\"a\":1},{\"b\":2}", "[1],[2]", "true,false", "\"a\",\"b\"")
            .forEach { json ->
                try {
                    Parser.default().parse(StringBuilder(json))
                    Assert.fail("Expected a KlaxonException for trailing value: $json")
                } catch (e: KlaxonException) {
                    assertThat(e.message).contains("Expected").contains("not")
                }
            }
    }

    fun trailingWhitespaceAndWhitespaceDelimitedValues() {
        // Trailing whitespace after the single root is tolerated.
        assertThat(Parser.default().parse(StringBuilder("1  "))).isEqualTo(1)
        // A whitespace-delimited second root value is now rejected just like a comma-delimited
        // one: the lexer keeps advancing tokens, so the state machine reports a trailing value.
        listOf("1 2", "\"a\" \"b\"").forEach { json ->
            try {
                Parser.default().parse(StringBuilder(json))
                Assert.fail("Expected rejection of trailing root value: $json")
            } catch (e: KlaxonException) {
                assertThat(e.message).contains("Expected").contains("not")
            }
        }
    }

    //
    // PathMatcher: exact event order and paths, not just the final object
    //

    private class RecordingPathMatcher : PathMatcher {
        val events = arrayListOf<Pair<String, Any>>()
        override fun pathMatches(path: String): Boolean = true
        override fun onMatch(path: String, value: Any) {
            events.add(path to value)
        }
    }

    private fun render(events: List<Pair<String, Any>>): String =
        events.joinToString(prefix = "[", postfix = "]") { "${it.first}=${it.second}" }

    private val pathJson = """
        {
            "group": {
                "id": 1,
                "group": { "id": 2, "name": "inner" },
                "sib": { "id": 3 }
            },
            "items": [
                { "id": 10, "peer": { "id": 11 } },
                { "id": 20, "peer": { "id": 21 } }
            ],
            "group2": { "id": 99 },
            "tail": "done"
        }
    """.trimIndent()

    private val expectedEvents = listOf(
        "$.group.id" to 1,
        "$.group.group.id" to 2,
        "$.group.group.name" to "inner",
        "$.group.sib.id" to 3,
        "$.items[0].id" to 10,
        "$.items[0].peer.id" to 11,
        "$.items[1].id" to 20,
        "$.items[1].peer.id" to 21,
        "$.group2.id" to 99,
        "$.tail" to "done"
    )

    fun pathMatcherEmitsExactEventsInFullMode() {
        val matcher = RecordingPathMatcher()
        val result = Parser.default(listOf(matcher)).parse(StringBuilder(pathJson))
        println("[ParserContractTest] path events in order:")
        matcher.events.forEach { println("[ParserContractTest]   ${it.first} = ${it.second}") }
        assertThat(render(matcher.events))
            .withFailMessage("event order/path mismatch")
            .isEqualTo(render(expectedEvents))
        // Reading still continues after a hit and produces the complete object.
        assertThat((result as JsonObject)["tail"]).isEqualTo("done")
        assertThat(result.containsKey("items")).isTrue()
    }

    fun pathMatcherEmitsExactEventsAcrossChunksAndStreaming() {
        seeds.forEach { seed ->
            val matcher = RecordingPathMatcher()
            val full = Parser.default(listOf(matcher)).parse(ChunkingReader(pathJson, seed))
            assertThat(render(matcher.events))
                .withFailMessage("full chunked path events mismatch at seed=$seed")
                .isEqualTo(render(expectedEvents))
            assertThat((full as JsonObject)["tail"]).isEqualTo("done")

            val streamingMatcher = RecordingPathMatcher()
            val reader = JsonReader(ChunkingReader(pathJson, seed))
            val streamed = Parser.default(listOf(streamingMatcher), reader.lexer, streaming = true)
                .parse(reader)
            assertThat(render(streamingMatcher.events))
                .withFailMessage("streaming path events mismatch at seed=$seed")
                .isEqualTo(render(expectedEvents))
            assertThat((streamed as JsonObject)["tail"]).isEqualTo("done")
        }
    }

    fun pathMatcherArrayIndicesAreExact() {
        val matcher = RecordingPathMatcher()
        // foundValue reports object leaves, so wrap each scalar to observe its array index.
        Parser.default(listOf(matcher)).parse(StringBuilder(
            """{"root":{"a":[{"v":10},{"v":20},{"v":30}]}}"""
        ))
        assertThat(render(matcher.events))
            .isEqualTo("[\$.root.a[0].v=10, \$.root.a[1].v=20, \$.root.a[2].v=30]")
    }

    fun pathMatcherEmitsTopLevelObjectLeaves() {
        val matcher = RecordingPathMatcher()
        Parser.default(listOf(matcher)).parse(StringBuilder(
            """{"a":[{"v":10},{"v":20},{"v":30}],"b":4}"""
        ))
        // Bare array elements produce no event (foundValue only reports object leaves);
        // the sibling scalar still fires.
        assertThat(render(matcher.events)).isEqualTo(
            "[\$.a[0].v=10, \$.a[1].v=20, \$.a[2].v=30, \$.b=4]"
        )
    }

    fun pathMatcherMissedSiblingBranchEmitsNothing() {
        val wanted = "\$.target.hit"
        val matcher = object : PathMatcher {
            val hits = arrayListOf<Pair<String, Any>>()
            override fun pathMatches(path: String) = path == wanted
            override fun onMatch(path: String, value: Any) {
                hits.add(path to value)
            }
        }
        val result = Parser.default(listOf(matcher)).parse(StringBuilder(
            """{"other":{"hit":"x","hit2":"y"},"target":{"hit":"z","after":1}}"""
        ))
        assertThat(matcher.hits).isEqualTo(listOf(wanted to "z"))
        // Parsing continued past the match and completed the document.
        assertThat((result as JsonObject)["target"])
            .isEqualTo(JsonObject().apply {
                this["hit"] = "z"
                this["after"] = 1
            })
    }

    //
    // Number classification
    //

    private fun firstToken(json: String): Value<*> {
        val lexer = Lexer(StringReader(json))
        val token = lexer.nextToken()
        assertThat(token is Value<*>)
            .withFailMessage("expected a Value token for '$json' but got $token")
            .isTrue()
        return token as Value<*>
    }

    @Test
    fun integerBoundariesClassifyAsIntLongOrBigInteger() {
        assertThat(firstToken("0").value).isEqualTo(0)
        assertThat(firstToken("0").value!!.javaClass).isEqualTo(Int::class.javaObjectType)
        assertThat(firstToken("-0").value!!.javaClass).isEqualTo(Int::class.javaObjectType)
        assertThat(firstToken("2147483647").value!!.javaClass).isEqualTo(Int::class.javaObjectType)
        assertThat(firstToken("-2147483648").value!!.javaClass).isEqualTo(Int::class.javaObjectType)
        assertThat(firstToken("2147483648").value!!.javaClass).isEqualTo(Long::class.javaObjectType)
        assertThat(firstToken("-2147483649").value!!.javaClass).isEqualTo(Long::class.javaObjectType)
        assertThat(firstToken("9223372036854775807").value!!.javaClass).isEqualTo(Long::class.javaObjectType)
        assertThat(firstToken("-9223372036854775808").value!!.javaClass).isEqualTo(Long::class.javaObjectType)
        assertThat(firstToken("9223372036854775808").value!!.javaClass).isEqualTo(BigInteger::class.java)
        assertThat(firstToken("-9223372036854775809").value!!.javaClass).isEqualTo(BigInteger::class.java)
        val big = firstToken("123456789012345678901234567890").value
        assertThat(big!!.javaClass).isEqualTo(BigInteger::class.java)
        assertThat(big).isEqualTo(BigInteger("123456789012345678901234567890"))
    }

    @Test
    fun negativeZeroAndExponentsClassifyAsDouble() {
        val negZero = firstToken("-0.0").value
        assertThat(negZero!!.javaClass).isEqualTo(java.lang.Double::class.java)
        assertThat(java.lang.Double.doubleToRawLongBits(negZero as Double))
            .isEqualTo(java.lang.Double.doubleToRawLongBits(-0.0))
        assertThat(firstToken("0.0").value!!.javaClass).isEqualTo(java.lang.Double::class.java)
        assertThat(firstToken("1e2").value).isEqualTo(100.0)
        assertThat(firstToken("1E-2").value).isEqualTo(0.01)
        assertThat(firstToken("1.5e3").value).isEqualTo(1500.0)
        // Exponent overflow to the infinities and underflow to zero are Double values.
        assertThat(firstToken("1e400").value).isEqualTo(Double.POSITIVE_INFINITY)
        assertThat(firstToken("-1e400").value).isEqualTo(Double.NEGATIVE_INFINITY)
        assertThat(firstToken("1e-400").value).isEqualTo(0.0)
        // A very long decimal is still a finite Double parse via the double branch.
        val longDecimal = firstToken("0.00000000000000000000000000000000000000001").value
        assertThat(longDecimal!!.javaClass).isEqualTo(java.lang.Double::class.java)
        assertThat(longDecimal as Double).isEqualTo(1e-41)
    }

    @Test
    fun numberLikeBareTextIsRejectedOrClassified() {
        // Leading zeros are tolerated by the numeric regex and re-parsed as an Int.
        assertThat(firstToken("01").value).isEqualTo(1)
        assertThat(firstToken("007").value).isEqualTo(7)
        listOf("+1", "0x10", "1.2.3", "1.", "truee", "NaN", "Infinity", "-").forEach { text ->
            try {
                Parser.default().parse(StringBuilder(text))
                Assert.fail("Expected rejection for bare text: $text")
            } catch (e: KlaxonException) {
                // Lexer-level ("Unexpected character") or parser-level ("Expected ...") error.
                assertThat(e.message!!.let { it.contains("Unexpected character") || it.contains("Expected") })
                    .withFailMessage("unexpected message for '$text': ${e.message}")
                    .isTrue()
            }
        }
    }

    //
    // Object mapping
    //

    data class NullableFields(
        val name: String? = "initial",
        val age: Int? = -1,
        val tags: List<String?>? = listOf("initial")
    )

    fun explicitNullsMapToNullableProperties() {
        // Explicit JSON nulls are passed through to nullable constructor parameters.
        val result = Klaxon().parse<NullableFields>("""{"name":null,"age":null,"tags":null}""")
        assertThat(result).isEqualTo(NullableFields(name = null, age = null, tags = null))
    }

    fun missingFieldsKeepDeclaredDefaultValues() {
        assertThat(Klaxon().parse<NullableFields>("""{"age":30}"""))
            .isEqualTo(NullableFields(name = "initial", age = 30, tags = listOf("initial")))
        assertThat(Klaxon().parse<NullableFields>("""{}"""))
            .isEqualTo(NullableFields())
    }

    data class WithConstructorDefaults(
        val name: String = "n/a",
        val count: Int = 42,
        val ratio: Double = 1.5
    )

    fun missingConstructorParametersUseDefaultValues() {
        assertThat(Klaxon().parse<WithConstructorDefaults>("""{}"""))
            .isEqualTo(WithConstructorDefaults())
        assertThat(Klaxon().parse<WithConstructorDefaults>("""{"count":7}"""))
            .isEqualTo(WithConstructorDefaults(name = "n/a", count = 7, ratio = 1.5))
    }

    data class GenericCollections(
        val strings: List<String>,
        val ints: List<Int>,
        val matrix: List<List<Int>>,
        val names: Set<String>
    )

    fun genericCollectionsAreMaterializedWithElementType() {
        val result = Klaxon().parse<GenericCollections>(
            """{"strings":["a","b"],"ints":[1,2],"matrix":[[1,2],[3]],"names":["x","y"]}"""
        )
        assertThat(result).isEqualTo(
            GenericCollections(
                strings = listOf("a", "b"),
                ints = listOf(1, 2),
                matrix = listOf(listOf(1, 2), listOf(3)),
                names = linkedSetOf("x", "y")
            )
        )
        assertThat(result!!.ints[0].javaClass).isEqualTo(Int::class.javaObjectType)
        assertThat(result.strings[0].javaClass).isEqualTo(String::class.java)
    }

    //
    // No lexer/path state leak: a failed parse must not contaminate a new Parser
    //

    fun newParserAfterFailureHasNoLeftoverState() {
        // Failing full parse on a fresh parser.
        repeat(3) { attempt ->
            try {
                Parser.default().parse(StringBuilder("{\"a\":1},{\"b\":2}"))
                Assert.fail("Attempt $attempt should have failed")
            } catch (expected: KlaxonException) {
                // expected
            }
            val clean = Parser.default().parse(StringBuilder("{\"ok\":true}"))
            assertThat((clean as JsonObject)["ok"]).isEqualTo(true)
        }
    }

    fun streamingParserAfterFailureResumesWithFreshParserOnSameStream() {
        seeds.forEach { seed ->
            // One stream: first root is valid, second root is invalid.
            val reader = JsonReader(ChunkingReader("""{"ok":1},{"bad"}""", seed))
            val first = Parser.default(emptyList(), reader.lexer, streaming = true).parse(reader)
            assertThat(first).isEqualTo(JsonObject().apply { this["ok"] = 1 })
            // The second root is invalid JSON; the failure belongs to that parse alone.
            val failure = try {
                Parser.default(emptyList(), reader.lexer, streaming = true).parse(reader)
                Assert.fail("Second root should fail at seed=$seed")
                error("unreachable")
            } catch (e: RuntimeException) {
                e
            }
            // The malformed second root surfaces as a lexer/state failure on the World
            // (KlaxonException for the bad identifier or an empty-stack NoSuchElementException
            // when the error token produced no value).
            val isParseFailure = failure is KlaxonException ||
                failure is java.util.NoSuchElementException
            assertThat(isParseFailure)
                .withFailMessage("unexpected failure type: $failure")
                .isTrue()
            // A brand new reader/parser pair must parse from a clean slate.
            val freshReader = JsonReader(StringReader("{\"recovered\":2}"))
            val fresh = Parser.default(emptyList(), freshReader.lexer, streaming = true)
                .parse(freshReader)
            assertThat(fresh).isEqualTo(JsonObject().apply { this["recovered"] = 2 })
        }
    }
}
