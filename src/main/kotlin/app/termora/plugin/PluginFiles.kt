package app.termora.plugin

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.util.zip.ZipInputStream

internal object PluginFiles {
    fun validateId(id: String) {
        require(id.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*"))) { "Invalid plugin ID" }
    }

    fun directory(root: Path, id: String): Path {
        validateId(id)
        Files.createDirectories(root)
        val directory = root.toRealPath().resolve(id)
        require(!Files.isSymbolicLink(directory)) { "Plugin directory must not be a symbolic link" }
        return directory
    }

    fun extract(input: InputStream, directory: Path) {
        Files.createDirectories(directory)
        val root = directory.toRealPath()
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name
                require(name.isNotEmpty() && '\\' !in name && ':' !in name && '\u0000' !in name) {
                    "Invalid plugin archive path"
                }
                val relative = Path.of(name)
                val target = root.resolve(relative).normalize()
                require(!relative.isAbsolute && target.startsWith(root) && target != root) {
                    "Plugin archive path escapes the extraction directory"
                }
                var parent = root
                for (part in root.relativize(target)) {
                    parent = parent.resolve(part)
                    require(!Files.isSymbolicLink(parent)) { "Plugin archive path contains a symbolic link" }
                }
                if (entry.isDirectory) {
                    Files.createDirectories(target)
                } else {
                    Files.createDirectories(target.parent)
                    Files.newOutputStream(target, CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { zip.copyTo(it) }
                }
                entry = zip.nextEntry
            }
        }
    }
}
