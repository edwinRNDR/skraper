/**
 * Copyright (c) 2019-present Mikhael Sokolov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.sokomishalov.skraper.cli

import com.andreapivetta.kolor.cyan
import com.andreapivetta.kolor.green
import com.andreapivetta.kolor.magenta
import com.andreapivetta.kolor.red
import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.dataformat.csv.CsvFactory
import com.fasterxml.jackson.dataformat.csv.CsvSchema
import com.fasterxml.jackson.dataformat.xml.XmlFactory
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.xenomachina.argparser.ArgParser
import com.xenomachina.argparser.mainBody
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import ru.sokomishalov.skraper.cli.model.Args
import ru.sokomishalov.skraper.cli.model.OutputType.*
import ru.sokomishalov.skraper.download
import ru.sokomishalov.skraper.model.Post
import java.io.File
import java.time.LocalDateTime.now
import java.time.format.DateTimeFormatter.ofPattern
import java.util.concurrent.Executors
import kotlin.system.exitProcess
import kotlin.text.Charsets.UTF_8


fun main(args: Array<String>) = mainBody(columns = 100) {
    val parsedArgs = ArgParser(args = args.ifEmpty { arrayOf("--help") }).parseInto(::Args)

    println("${"Skraper".green()} ${"v.0.4.0".magenta()} started")

    val posts = runBlocking {
        parsedArgs.skraper.getPosts(
                path = "/${parsedArgs.path.removePrefix("/")}",
                limit = parsedArgs.amount
        )
    }

    when {
        parsedArgs.onlyMedia -> posts.persistMedia(parsedArgs)
        else -> posts.persistMeta(parsedArgs)
    }
}

private fun List<Post>.persistMedia(parsedArgs: Args) {
    val provider = parsedArgs.skraper.javaClass.simpleName.toString().toLowerCase().replace("skraper", "")
    val requestedPath = parsedArgs.path
    val root = when {
        parsedArgs.output.isFile -> parsedArgs.output.parentFile.absolutePath
        else -> parsedArgs.output.absolutePath
    }
    val targetDir = File("${root}/${provider}/${requestedPath}").apply { mkdirs() }

    runBlocking(context = Executors.newFixedThreadPool(parsedArgs.parallelDownloads).asCoroutineDispatcher()) {
        flatMap { post ->
            post.media.mapIndexed { index, media ->
                async {
                    runCatching {
                        parsedArgs.skraper.download(
                                media = media,
                                destDir = targetDir,
                                filename = when (post.media.size) {
                                    1 -> post.id
                                    else -> "${post.id}_${index + 1}"
                                }
                        )
                    }.onSuccess { path ->
                        println(path)
                    }.onFailure { thr ->
                        println("Cannot download ${media.url} , Reason: ${thr.toString().red()}")
                    }
                }
            }
        }.awaitAll()
    }

    exitProcess(1)
}

private fun List<Post>.persistMeta(parsedArgs: Args) {
    val provider = parsedArgs.skraper.javaClass.simpleName.toString().replace("Skraper", "").toLowerCase()
    val requestedPath = parsedArgs.path

    val content = when (parsedArgs.outputType) {
        LOG -> joinToString("\n") { it.toString() }.also { println(it) }
        JSON -> jsonWrite(this)
        XML -> xmlWrite(this)
        YAML -> yamlWrite(this)
        CSV -> csvWrite(this)
    }

    val fileToWrite = when {
        parsedArgs.output.isFile -> parsedArgs.output
        else -> {
            val root = parsedArgs.output.absolutePath
            val now = now().format(ofPattern("ddMMyyyy'_'hhmmss"))
            val ext = parsedArgs.outputType.extension

            File("${root}/${provider}/${requestedPath}_${now}.${ext}")
        }
    }

    fileToWrite
            .apply { parentFile.mkdirs() }
            .writeText(text = content, charset = UTF_8)

    println(fileToWrite.path.cyan())
}

private fun jsonWrite(posts: List<Post>): String {
    return objectMapper(JsonFactory())
            .writerWithDefaultPrettyPrinter()
            .writeValueAsString(posts)
}

private fun xmlWrite(posts: List<Post>): String {
    return objectMapper(XmlFactory())
            .writerWithDefaultPrettyPrinter()
            .writeValueAsString(posts)
}

private fun yamlWrite(posts: List<Post>): String {
    return objectMapper(YAMLFactory())
            .writerWithDefaultPrettyPrinter()
            .writeValueAsString(posts)
}

private fun csvWrite(posts: List<Post>): String {
    return objectMapper(CsvFactory())
            .registerModule(SimpleModule().apply {
                addSerializer(Post::class.java, object : JsonSerializer<Post>() {
                    override fun serialize(item: Post, jgen: JsonGenerator, serializerProvider: SerializerProvider) {
                        with(jgen) {
                            writeStartObject()
                            writeStringField("ID", item.id)
                            writeStringField("Text", item.text)
                            writeStringField("Published at", item.publishedAt?.toString(10))
                            writeStringField("Rating", item.rating?.toString(10).orEmpty())
                            writeStringField("Comments count", item.commentsCount?.toString(10).orEmpty())
                            writeStringField("Views count", item.viewsCount?.toString(10).orEmpty())
                            writeStringField("Media", item.media.joinToString("   ") { it.url })
                            writeEndObject()
                        }
                    }
                })
            })
            .writer(CsvSchema
                    .builder()
                    .addColumn("ID")
                    .addColumn("Text")
                    .addColumn("Published at")
                    .addColumn("Rating")
                    .addColumn("Comments count")
                    .addColumn("Views count")
                    .addColumn("Media")
                    .build()
                    .withHeader()
            )
            .writeValueAsString(posts)
}

private fun objectMapper(typeFactory: JsonFactory): ObjectMapper {
    return ObjectMapper(typeFactory)
            .registerKotlinModule()
            .registerModule(JavaTimeModule())
            .registerModule(Jdk8Module())
}
