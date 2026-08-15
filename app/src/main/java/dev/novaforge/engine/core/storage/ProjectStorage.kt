package dev.novaforge.engine.core.storage

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import dev.novaforge.engine.core.model.ProjectConfig
import dev.novaforge.engine.core.model.ProjectSummary
import dev.novaforge.engine.core.model.SceneCodec
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

class ProjectStorage(private val context: Context) {
    private val prefs = context.getSharedPreferences("novaforge_storage", Context.MODE_PRIVATE)
    private val resolver get() = context.contentResolver

    val workspaceUri: Uri?
        get() = prefs.getString(KEY_WORKSPACE_URI, null)?.let(Uri::parse)

    fun setWorkspace(uri: Uri) {
        resolver.takePersistableUriPermission(
            uri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        prefs.edit().putString(KEY_WORKSPACE_URI, uri.toString()).apply()
        ensureWorkspace()
    }

    fun ensureWorkspace(): DocumentFile {
        val root = workspaceUri?.let { DocumentFile.fromTreeUri(context, it) }
            ?: error("Workspace not configured")
        require(root.canWrite()) { "NovaForge workspace is not writable" }
        WORKSPACE_DIRS.forEach { root.ensureDirectory(it) }
        return root
    }

    fun listProjects(): List<ProjectSummary> {
        val root = workspaceUri?.let { DocumentFile.fromTreeUri(context, it) } ?: return emptyList()
        val projects = root.findFile("Projects") ?: return emptyList()
        return projects.listFiles().filter { it.isDirectory }.mapNotNull { folder ->
            repairLegacyExtensions(folder)
            val configFile = folder.findFile("project.nova") ?: return@mapNotNull null
            runCatching {
                val config = ProjectConfig.fromJson(JSONObject(readFile(configFile)))
                ProjectSummary(
                    name = config.name,
                    modifiedAt = maxOf(folder.lastModified(), configFile.lastModified()),
                    engineVersion = config.engineVersion,
                    documentUri = folder.uri.toString()
                )
            }.getOrNull()
        }.sortedByDescending { it.modifiedAt }
    }

    fun createProject(config: ProjectConfig): DocumentFile {
        val root = ensureWorkspace()
        val projects = root.findFile("Projects") ?: error("Projects directory missing")
        val safeName = sanitizeName(config.name)
        require(projects.findFile(safeName) == null) { "A project named '$safeName' already exists" }
        val project = projects.createDirectory(safeName) ?: error("Unable to create project")
        try {
            listOf("Scenes", "Scripts", "Blocks", "Resources", "Plugins", ".novaforge").forEach { project.ensureDirectory(it) }
            val assets = project.ensureDirectory("Assets")
            listOf("Sprites", "Audio", "Fonts", "Other").forEach { assets.ensureDirectory(it) }
            writeText(project, "project.nova", config.copy(name = safeName).toJson().toString(2))
            writeText(project, "Scenes/Main.scene", SceneCodec.encode(SceneCodec.defaultScene()))
            writeText(project, "Scripts/Player.lua", DEFAULT_PLAYER_SCRIPT)
            validateProject(project)
            return project
        } catch (t: Throwable) {
            project.delete()
            throw t
        }
    }

    fun projectByName(name: String): DocumentFile {
        val root = ensureWorkspace()
        val projects = root.findFile("Projects") ?: error("Projects directory missing")
        val project = projects.findFile(sanitizeName(name))?.takeIf { it.isDirectory }
            ?: error("Project not found: $name")
        repairLegacyExtensions(project)
        return project
    }

    fun loadConfig(project: DocumentFile): ProjectConfig {
        repairLegacyExtensions(project)
        return ProjectConfig.fromJson(JSONObject(readText(project, "project.nova")))
    }

    fun loadMainScene(project: DocumentFile) = SceneCodec.decode(readText(project, loadConfig(project).mainScene))

    fun saveMainScene(project: DocumentFile, sceneText: String) {
        writeText(project, loadConfig(project).mainScene, sceneText)
    }

    fun readText(project: DocumentFile, relativePath: String): String {
        repairLegacyExtensions(project)
        val file = resolve(project, relativePath, createParents = false)
            ?: error("Missing file: $relativePath")
        return readFile(file)
    }

    fun writeText(project: DocumentFile, relativePath: String, content: String) {
        val segments = safeSegments(relativePath)
        var dir = project
        for (segment in segments.dropLast(1)) dir = dir.ensureDirectory(segment)
        val fileName = segments.last()
        val file = dir.findFile(fileName) ?: createFileExact(dir, fileName, mimeFor(fileName))
        resolver.openOutputStream(file.uri, "wt")!!.bufferedWriter().use { it.write(content) }
        require(dir.findFile(fileName)?.isFile == true) { "Android provider changed the file name for $relativePath" }
    }

    fun importAsset(project: DocumentFile, source: Uri, targetFolder: String): String {
        val fileName = queryDisplayName(source) ?: "asset_${System.currentTimeMillis()}"
        val targetPath = "Assets/${sanitizePathSegment(targetFolder)}/${sanitizePathSegment(fileName)}"
        val segments = safeSegments(targetPath)
        var dir = project
        for (segment in segments.dropLast(1)) dir = dir.ensureDirectory(segment)
        val target = dir.findFile(segments.last()) ?: createFileExact(dir, segments.last(), mimeFor(fileName))
        resolver.openInputStream(source)!!.use { input ->
            resolver.openOutputStream(target.uri, "w")!!.use { output -> input.copyTo(output) }
        }
        return targetPath
    }

    fun importProjectZip(source: Uri): DocumentFile {
        val root = ensureWorkspace()
        val projects = root.findFile("Projects") ?: error("Projects directory missing")
        val displayName = queryDisplayName(source) ?: "ImportedProject.zip"
        var projectName = sanitizeName(displayName.substringBeforeLast('.'))
        var suffix = 2
        while (projects.findFile(projectName) != null) projectName = "${sanitizeName(displayName.substringBeforeLast('.'))}-$suffix".also { suffix++ }
        val destination = projects.createDirectory(projectName) ?: error("Unable to create destination")

        var hasProjectConfig = false
        try {
            resolver.openInputStream(source)!!.use { raw ->
                ZipInputStream(raw.buffered()).use { zip ->
                    var entry: ZipEntry?
                    while (zip.nextEntry.also { entry = it } != null) {
                        val current = entry ?: break
                        val path = normalizeZipPath(current.name)
                        if (path.isEmpty()) continue
                        if (path == "project.nova") hasProjectConfig = true
                        if (current.isDirectory) {
                            ensurePath(destination, path)
                        } else {
                            val segments = safeSegments(path)
                            var dir = destination
                            for (segment in segments.dropLast(1)) dir = dir.ensureDirectory(segment)
                            val file = dir.findFile(segments.last())
                                ?: createFileExact(dir, segments.last(), mimeFor(segments.last()))
                            resolver.openOutputStream(file.uri, "w")!!.use { output -> zip.copyTo(output) }
                        }
                        zip.closeEntry()
                    }
                }
            }
            require(hasProjectConfig) { "ZIP does not contain project.nova at its root" }
            validateProject(destination)
            return destination
        } catch (t: Throwable) {
            destination.delete()
            throw t
        }
    }

    private fun validateProject(project: DocumentFile) {
        repairLegacyExtensions(project)
        require(project.findFile("project.nova")?.isFile == true) { "project.nova was not created correctly" }
        val config = ProjectConfig.fromJson(JSONObject(readText(project, "project.nova")))
        require(resolve(project, config.mainScene, createParents = false)?.isFile == true) {
            "Main scene was not created correctly: ${config.mainScene}"
        }
    }

    /**
     * Older 0.1 builds used JSON/text MIME types for NovaForge custom extensions.
     * Some Android document providers append their preferred extension, producing
     * names such as project.nova.json or Player.lua.txt. Repair those projects in place.
     */
    private fun repairLegacyExtensions(project: DocumentFile) {
        renameIfPresent(project, "project.nova.json", "project.nova")
        project.findFile("Scenes")?.repairSuffixRecursively(".scene.json", ".scene")
        project.findFile("Blocks")?.repairSuffixRecursively(".blocks.json", ".blocks")
        project.findFile("Scripts")?.repairSuffixRecursively(".lua.txt", ".lua")
    }

    private fun renameIfPresent(dir: DocumentFile, oldName: String, newName: String) {
        if (dir.findFile(newName) != null) return
        dir.findFile(oldName)?.renameTo(newName)
    }

    private fun DocumentFile.repairSuffixRecursively(badSuffix: String, goodSuffix: String) {
        listFiles().forEach { child ->
            if (child.isDirectory) {
                child.repairSuffixRecursively(badSuffix, goodSuffix)
            } else {
                val name = child.name ?: return@forEach
                if (name.endsWith(badSuffix, ignoreCase = true)) {
                    val fixed = name.dropLast(badSuffix.length) + goodSuffix
                    if (findFile(fixed) == null) child.renameTo(fixed)
                }
            }
        }
    }

    private fun createFileExact(dir: DocumentFile, fileName: String, mime: String): DocumentFile {
        fun normalize(created: DocumentFile): DocumentFile? {
            if (created.name == fileName) return created
            if (created.renameTo(fileName)) return dir.findFile(fileName) ?: created
            return null
        }

        val first = dir.createFile(mime, fileName) ?: error("Unable to create $fileName")
        normalize(first)?.let { return it }
        first.delete()

        // application/octet-stream has no provider-specific filename extension mapping.
        val fallback = dir.createFile("application/octet-stream", fileName)
            ?: error("Unable to create $fileName")
        normalize(fallback)?.let { return it }
        fallback.delete()
        error("Android storage provider refuses the exact filename '$fileName'")
    }

    private fun readFile(file: DocumentFile): String =
        resolver.openInputStream(file.uri)!!.use { input ->
            BufferedReader(InputStreamReader(input)).readText()
        }

    private fun resolve(root: DocumentFile, relativePath: String, createParents: Boolean): DocumentFile? {
        val segments = safeSegments(relativePath)
        var current = root
        segments.forEachIndexed { index, segment ->
            val existing = current.findFile(segment)
            if (index == segments.lastIndex) return existing
            current = existing ?: if (createParents) current.ensureDirectory(segment) else return null
        }
        return current
    }

    private fun ensurePath(root: DocumentFile, relativePath: String) {
        var current = root
        safeSegments(relativePath).forEach { current = current.ensureDirectory(it) }
    }

    private fun normalizeZipPath(raw: String): String {
        val replaced = raw.replace('\\', '/').trimStart('/')
        val segments = replaced.split('/').filter { it.isNotBlank() }
        require(segments.none { it == ".." || it == "." }) { "Unsafe ZIP path: $raw" }
        require(!raw.startsWith('/') && !Regex("^[A-Za-z]:").containsMatchIn(raw)) { "Unsafe ZIP path: $raw" }
        return segments.joinToString("/")
    }

    private fun safeSegments(path: String): List<String> {
        val normalized = path.replace('\\', '/').trim('/')
        val segments = normalized.split('/').filter { it.isNotBlank() }
        require(segments.isNotEmpty() && segments.none { it == "." || it == ".." }) { "Unsafe path: $path" }
        return segments
    }

    private fun queryDisplayName(uri: Uri): String? {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0)
        }
        return null
    }

    private fun sanitizeName(value: String): String = sanitizePathSegment(value).ifBlank { "NewProject" }

    private fun sanitizePathSegment(value: String): String = value
        .replace(Regex("[\\/:*?\"<>|]"), "-")
        .replace(Regex("\\s+"), " ")
        .trim().take(80)

    private fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase(Locale.US)) {
        "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "webp" -> "image/webp"
        "wav" -> "audio/wav"; "ogg" -> "audio/ogg"; "mp3" -> "audio/mpeg"
        "ttf" -> "font/ttf"; "otf" -> "font/otf"; "json" -> "application/json"
        // Custom extensions must not advertise another file format MIME. Android document
        // providers are allowed to append an extension inferred from MIME (e.g. .json/.txt).
        "nova", "scene", "blocks", "lua" -> "application/octet-stream"
        else -> "application/octet-stream"
    }

    private fun DocumentFile.ensureDirectory(name: String): DocumentFile =
        findFile(name)?.takeIf { it.isDirectory } ?: createDirectory(name) ?: error("Unable to create directory $name")

    companion object {
        private const val KEY_WORKSPACE_URI = "workspace_uri"
        private val WORKSPACE_DIRS = listOf("Projects", "Templates", "Themes", "Exports", "Backups", "Cache", "Logs", "UserData")
        private const val DEFAULT_PLAYER_SCRIPT = """-- NovaForge example script
function ready()
    NovaForge.print("Player iniciado")
end

function update(delta)
    -- self.x = self.x + 120 * delta
end
"""
    }
}
