package ru.sokomishalov.skraper

import ru.sokomishalov.skraper.internal.ffmpeg.FfmpegCliRunner
import ru.sokomishalov.skraper.internal.net.host
import ru.sokomishalov.skraper.internal.net.path
import ru.sokomishalov.skraper.model.*
import ru.sokomishalov.skraper.provider.youtube.YoutubeSkraper
import java.io.File
import java.io.File.separator
import java.net.URL


/**
 * @param media item to download
 * @param destDir destination file or directory for media
 * @param filename custom destination file name without extension
 * @param ffmpegRunner custom ffmpeg runner (for downloading m3u8 files)
 */
suspend fun Skraper.download(
        media: Media,
        destDir: File,
        filename: String = media.extractFileNameWithoutExtension(),
        ffmpegRunner: suspend (String) -> Unit = { FfmpegCliRunner.run(it) }
): File {

    val (directMediaUrl, extension) = lookForDirectMediaLinkRecursively(media)

    val destFile = File("${destDir.absolutePath}$separator${filename}.${extension}").apply { destDir.mkdirs() }

    return when (extension) {

        // m3u8 download with ffmpeg
        "m3u8" -> {
            val destFileMp4Path = destFile.absolutePath.replace("m3u8", "mp4")
            val cmd = "-i $directMediaUrl -c copy -bsf:a aac_adtstoasc $destFileMp4Path"

            ffmpegRunner(cmd)

            File(destFileMp4Path)
        }

        // otherwise try to download as is
        else -> {
            client.download(url = directMediaUrl, destFile = destFile)
            destFile
        }
    }
}


private suspend fun Skraper.lookForDirectMediaLinkRecursively(media: Media, recursionDepth: Int = 2): Pair<URLString, String> {
    return when {
        // has some possible extension
        media.url
                .path
                .substringAfterLast("/")
                .substringAfterLast(".", "")
                .isNotEmpty() -> media.url to media.extractFileExtension()

        // youtube video
        media.url.host in YoutubeSkraper.HOSTS -> {
            val resolved = YoutubeSkraper(client = client).resolve(media)
            val name = resolved.extractFileNameWithoutExtension()
            val filename = "$name.mp4"

            resolved.url to filename
        }

        // otherwise
        else -> {
            resolve(media).run {
                when {
                    recursionDepth > 0 -> lookForDirectMediaLinkRecursively(media = this, recursionDepth = recursionDepth - 1)
                    else -> url to extractFileExtension()
                }
            }
        }
    }
}

private fun Media.extractFileExtension(): String {
    val filename = URL(url).path

    return when (this) {
        is Image -> filename.substringAfterLast(".", "png")
        is Video -> filename.substringAfterLast(".", "mp4")
        is Audio -> filename.substringAfterLast(".", "mp3")
    }
}

private fun Media.extractFileNameWithoutExtension(): String {
    return URL(url)
            .path
            .substringAfterLast("/")
            .substringBeforeLast(".")
}
