import java.io.File

plugins {
    java
    `java-library`
    kotlin("jvm") version KOTLIN_VERSION apply true
}

allprojects {
    group = "org.gradle.kotlin.dsl.samples.multiproject"

    version = "1.0"

    repositories {
        mavenCentral()
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

repositories {
    jcenter()
    mavenCentral()
    maven { setUrl("https://plugins.gradle.org/m2") }
}

val test by tasks.getting(Test::class) {
    useTestNG()
    // The contract suite lives in the :klaxon subproject; include its compiled tests so that
    // a root-level `./gradlew test --tests '*ParserContractTest'` selects them as well.
}

gradle.projectsEvaluated {
    val rootTest = tasks.getByName("test")
    val klaxonTest = project(":klaxon").tasks.getByName("test")
    rootTest.dependsOn(klaxonTest)

    // Run the :klaxon test classes from the root :test task too, so that root-level
    // `./gradlew test --tests '*ParserContractTest'` executes the contract suite.
    val klaxonSourceSet = project(":klaxon").extensions
        .getByType(SourceSetContainer::class.java).getByName("test")
    rootTest as Test
    rootTest.testClassesDirs = files(rootTest.testClassesDirs, klaxonSourceSet.output.classesDirs)
    rootTest.classpath = files(rootTest.classpath, klaxonSourceSet.runtimeClasspath)
}

dependencies {
    listOf("stdlib", "reflect").forEach {
        implementation(kotlin(it))
    }
    listOf("test").forEach {
        testImplementation(kotlin(it))
    }
    listOf("org.testng:testng:7.0.0", "org.assertj:assertj-core:3.10.0").forEach {
        testImplementation(it)
    }

    listOf("klaxon", "klaxon-jackson").forEach {
        implementation(project(":$it", "default"))
    }
}

//
//dependencies {
//    // Make the root project archives configuration depend on every subproject
//    subprojects.forEach {
//        archives(it)
//    }
//}

subprojects {
    extra["signing.secretKeyRingFile"] = System.getProperty("user.home") + "/.gnupg/secring.gpg"
}
//
// Mutation check: sequentially introduce five one-line mutations
// (EOF, path pop, unicode escape, number branch, streaming early read),
// require ParserContractTest to fail for each, then restore the source and
// verify the working tree has no leftover changes.
//
data class MutationSpec(
    val name: String,
    val category: String,
    val file: String,
    val oldText: String,
    val newText: String,
    val testMethod: String
)

val mutationSpecs = listOf(
    MutationSpec(
        name = "eof",
        category = "EOF condition",
        file = "klaxon/src/main/kotlin/com/beust/klaxon/KlaxonParser.kt",
        oldText = "        } while (token !is EOF)",
        newText = "        } while (true)",
        testMethod = "trailingWhitespaceAndWhitespaceDelimitedValues"
    ),
    MutationSpec(
        name = "path-pop",
        category = "path stack pop (one level missing)",
        file = "klaxon/src/main/kotlin/com/beust/klaxon/World.kt",
        oldText = "            popStatus()\n            popValue()",
        newText = "            popStatus()",
        testMethod = "pathMatcherEmitsExactEventsInFullMode"
    ),
    MutationSpec(
        name = "unicode-escape",
        category = "unicode escape decoding",
        file = "klaxon/src/main/kotlin/com/beust/klaxon/Lexer.kt",
        oldText = "                                currentValue.append(intValue.toChar())",
        newText = "                                currentValue.append(intValue)",
        testMethod = "fullDecodingAcrossAllChunkBoundaries"
    ),
    MutationSpec(
        name = "number-branch",
        category = "number classification branch",
        file = "klaxon/src/main/kotlin/com/beust/klaxon/Lexer.kt",
        oldText = "            } else if (DOUBLE.matcher(v).matches()) {",
        newText = "            } else if (!DOUBLE.matcher(v).matches()) {",
        testMethod = "negativeZeroAndExponentsClassifyAsDouble"
    ),
    MutationSpec(
        name = "streaming-read-ahead",
        category = "streaming early token consumption",
        file = "klaxon/src/main/kotlin/com/beust/klaxon/KlaxonParser.kt",
        oldText = "        if (lexer.peek() is COMMA) lexer.nextToken()",
        newText = "        lexer.nextToken()",
        testMethod = "consecutiveRootObjectsReadOneByOneWhenStreaming"
    )
)

tasks.register("mutationCheck") {
    group = "verification"
    description = "Runs five one-line mutations and requires ParserContractTest to kill each one."

    doLast {
        val rootDir = layout.projectDirectory.asFile
        val results = mutableListOf<String>()

        fun applyText(file: java.io.File, oldText: String, newText: String) {
            val content = file.readText()
            val occurrences = content.split(oldText).size - 1
            require(occurrences == 1) {
                "Expected exactly one occurrence in ${file.path}, found $occurrences:\n$oldText"
            }
            file.writeText(content.replace(oldText, newText))
        }

        fun runTargetTest(method: String): Boolean {
            val gradleCmd = if (System.getProperty("os.name").toLowerCase().contains("windows")) {
                listOf("cmd", "/c", "gradlew.bat")
            } else {
                listOf("./gradlew")
            }
            val cmd = gradleCmd + listOf(
                ":klaxon:test",
                "--tests", "com.beust.klaxon.ParserContractTest.$method",
                "--rerun-tasks",
                "-q"
            )
            val process = ProcessBuilder(cmd)
                .directory(rootDir)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val exit = process.waitFor()
            if (exit == 0) {
                println(output)
            }
            return exit != 0
        }

        mutationSpecs.forEach { spec ->
            val file = File(rootDir, spec.file)
            val original = file.readText()
            println()
            println("== Mutation [${spec.name}] ${spec.category}")
            try {
                applyText(file, spec.oldText, spec.newText)
                val killed = runTargetTest(spec.testMethod)
                if (killed) {
                    results.add("KILLED  ${spec.name.padEnd(22)} ${spec.category}")
                    println("   -> ParserContractTest.${spec.testMethod} FAILED as expected")
                } else {
                    results.add("SURVIVED ${spec.name.padEnd(22)} ${spec.category}")
                    println("   -> SURVIVED: ${spec.testMethod} stayed green")
                }
            } finally {
                file.writeText(original)
            }
        }

        // The source must be byte-identical to the pre-mutation state.
        val status = ProcessBuilder("git", "status", "--porcelain")
            .directory(rootDir)
            .redirectErrorStream(true)
            .start()
            .inputStream.bufferedReader().readText().trim()
        val productionClean = status.lineSequence()
            .filter { it.isNotBlank() }
            .none { it.substringAfter(' ').trim().startsWith("klaxon/src/main/") }

        println()
        println("=== Mutation summary ===")
        results.forEach { println(it) }
        val survivors = results.count { it.startsWith("SURVIVED") }
        println("Killed: ${results.size - survivors}/${results.size}")
        if (productionClean) {
            println("Working tree production sources restored: no mutation leftovers.")
        } else {
            println("WARNING: production sources still modified:\n$status")
        }
        check(survivors == 0) { "At least one mutation survived ParserContractTest." }
        check(productionClean) { "Mutation leftovers remain under klaxon/src/main." }
    }
}
