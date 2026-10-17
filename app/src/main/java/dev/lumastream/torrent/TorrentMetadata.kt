package dev.lumastream.torrent

import org.libtorrent4j.AddTorrentParams
import org.libtorrent4j.FileStorage
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.Vectors
import org.libtorrent4j.swig.add_torrent_params
import org.libtorrent4j.swig.bdecode_node
import org.libtorrent4j.swig.error_code
import org.libtorrent4j.swig.load_torrent_limits
import java.io.File
import java.io.IOException
import java.util.Locale

internal const val MAX_METADATA_BYTES = 2 * 1024 * 1024
internal const val MAX_PIECES = 262_144
internal const val MAX_PIECE_BYTES = 8 * 1024 * 1024

internal data class VideoFile(val file: TorrentFile, val path: File, val torrentOffset: Long)

internal fun loadTorrent(bytes: ByteArray): AddTorrentParams {
    val buffer = Vectors.bytes2byte_vector(bytes)
    val error = error_code()
    val limits = load_torrent_limits().apply {
        setMax_buffer_size(MAX_METADATA_BYTES)
        setMax_pieces(MAX_PIECES)
        setMax_decode_depth(64)
        setMax_decode_tokens(100_000)
    }
    try {
        // TorrentInfo(byte[]) only parses the info dictionary in 2.1.0-39;
        // this loader also preserves trackers, web seeds, and v2 piece layers.
        val params = add_torrent_params.load_torrent_buffer(buffer, error, limits)
        if (error.value() != 0) {
            params.delete()
            throw IOException("Invalid torrent: ${error.message()}")
        }
        return AddTorrentParams(params)
    } finally {
        buffer.delete()
        error.delete()
        limits.delete()
    }
}

internal fun videoFiles(info: TorrentInfo, directory: File): List<VideoFile> {
    if (!info.isValid || info.numFiles() !in 1..4096 ||
        info.numPieces() !in 1..MAX_PIECES || info.pieceLength() !in 1..MAX_PIECE_BYTES
    ) {
        throw IOException("Torrent exceeds this TV's metadata, file-count, or 8 MiB piece limit")
    }
    // Inspect original metadata, not just libtorrent's sanitized disk paths.
    for (name in arrayOf("name", "name.utf-8")) {
        info.info(name).useNode { if (it.to_bool()) safeComponent(it.string_value_ex()) }
    }
    info.info("files").useNode { files ->
        if (files.to_bool()) {
            if (files.type() != bdecode_node.type_t.list_t) throw IOException("Invalid torrent file list")
            for (i in 0 until files.list_size()) {
                files.list_at(i).useNode { entry ->
                    for (key in arrayOf("path", "path.utf-8")) {
                        entry.dict_find_list_ex(key).useNode { path ->
                            if (path.to_bool()) {
                                for (j in 0 until path.list_size()) safeComponent(path.list_string_value_at_ex(j))
                            }
                        }
                    }
                    rejectSymlink(entry)
                }
            }
        }
    }
    info.info("file tree").useNode { if (it.to_bool()) validateTree(it, 0) }
    val storage = info.files()
    val root = directory.canonicalPath + File.separator
    val paths = HashSet<String>()
    val result = ArrayList<VideoFile>()
    for (index in 0 until storage.numFiles()) {
        val path = storage.filePath(index)
        val flags = storage.fileFlags(index)
        val symlink = try {
            flags.to_int() and FileStorage.FLAG_SYMLINK.to_int() != 0
        } finally {
            flags.delete()
        }
        if (symlink || storage.fileAbsolutePath(index) || path.startsWith('/') || path.contains('\\')) {
            throw IOException("Unsafe torrent path: $path")
        }
        path.split('/').forEach(::safeComponent)
        val target = File(directory, path).canonicalFile
        if (!target.path.startsWith(root) || !paths.add(target.path)) {
            throw IOException("Unsafe or duplicate torrent path: $path")
        }
        val size = storage.fileSize(index)
        val offset = storage.fileOffset(index)
        if (size < 0 || offset < 0 || size > Long.MAX_VALUE - offset) throw IOException("Invalid file size")
        val extension = path.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (!storage.padFileAt(index) && size > 0 && extension in VIDEO_EXTENSIONS) {
            result += VideoFile(TorrentFile(index, path, size), target, offset)
        }
    }
    if (result.isEmpty()) throw IOException("No supported video files found in this torrent")
    return result
}

private val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "mkv", "webm", "ts", "mov", "mpg", "mpeg")

private fun safeComponent(value: String) {
    if (value.isEmpty() || value == "." || value == ".." ||
        value.any { it == '/' || it == '\\' || it == ':' || it.code < 32 }
    ) throw IOException("Unsafe torrent path component")
}

private fun rejectSymlink(entry: bdecode_node) {
    if (entry.dict_find_string_value_ex("attr").contains('l')) throw IOException("Torrent symlinks are not allowed")
    entry.dict_find_ex("symlink path").useNode {
        if (it.to_bool()) throw IOException("Torrent symlinks are not allowed")
    }
}

private fun validateTree(tree: bdecode_node, depth: Int) {
    if (depth > 32 || tree.type() != bdecode_node.type_t.dict_t) throw IOException("Invalid torrent file tree")
    for (i in 0 until tree.dict_size()) {
        val pair = tree.dict_at_node(i)
        try {
            val key = pair.first.string_value_ex()
            val value = pair.second
            if (key.isEmpty()) rejectSymlink(value) else {
                safeComponent(key)
                validateTree(value, depth + 1)
            }
        } finally {
            pair.delete()
        }
    }
}

private inline fun <T> bdecode_node.useNode(block: (bdecode_node) -> T): T = try {
    block(this)
} finally {
    delete()
}
