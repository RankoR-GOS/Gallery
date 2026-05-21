import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import org.w3c.dom.Element
import java.io.File
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory

private val stringResourceTags = setOf(
    "plurals",
    "string",
    "string-array",
)

private data class StringResourceKey(
    val type: String,
    val name: String,
) : Comparable<StringResourceKey> {

    val displayName: String
        get() = "$type/$name"

    override fun compareTo(other: StringResourceKey): Int {
        return compareValuesBy(this, other, StringResourceKey::type, StringResourceKey::name)
    }
}

private data class MissingOverrideFile(
    val mainFile: File,
    val gosFile: File,
    val overlappingResources: Set<StringResourceKey>,
)

private data class MissingOverrideResources(
    val gosFile: File,
    val missingResources: Set<StringResourceKey>,
)

@DisableCachingByDefault(because = "Lightweight local verification task.")
abstract class CheckGosStringOverridesTask : DefaultTask() {

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val mainResDirectory: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val gosResDirectory: DirectoryProperty

    @get:OutputFile
    abstract val successMarkerFile: RegularFileProperty

    @TaskAction
    fun checkGosStringOverrides() {
        val mainResDirectoryFile = mainResDirectory.asFile.get()
        val gosResDirectoryFile = gosResDirectory.asFile.get()
        val defaultGosStringsFile = gosResDirectoryFile.resolve("values/strings.xml")

        if (!defaultGosStringsFile.isFile) {
            throw GradleException(
                "Missing canonical GOS string overrides file: ${defaultGosStringsFile.path}",
            )
        }

        val documentBuilder = newDocumentBuilderFactory().newDocumentBuilder()
        val requiredResources = readStringResources(
            file = defaultGosStringsFile,
            documentBuilder = documentBuilder,
        )
        if (requiredResources.isEmpty()) {
            throw GradleException(
                "No string resources found in canonical GOS overrides file: ${defaultGosStringsFile.path}",
            )
        }

        val missingOverrideFiles = mutableListOf<MissingOverrideFile>()
        val missingOverrideResources = mutableListOf<MissingOverrideResources>()

        findStringsFiles(resDirectory = mainResDirectoryFile).forEach { mainStringsFile ->
            val mainResources = readStringResources(
                file = mainStringsFile,
                documentBuilder = documentBuilder,
            )
            val overlappingResources = mainResources.intersect(requiredResources)
            if (overlappingResources.isEmpty()) {
                return@forEach
            }

            val valuesDirectoryName = mainStringsFile.parentFile.name
            val gosStringsFile = gosResDirectoryFile.resolve("$valuesDirectoryName/strings.xml")
            if (!gosStringsFile.isFile) {
                missingOverrideFiles += MissingOverrideFile(
                    mainFile = mainStringsFile,
                    gosFile = gosStringsFile,
                    overlappingResources = overlappingResources,
                )
                return@forEach
            }

            val gosResources = if (gosStringsFile == defaultGosStringsFile) {
                requiredResources
            } else {
                readStringResources(file = gosStringsFile, documentBuilder = documentBuilder)
            }
            val missingResources = requiredResources.minus(gosResources)
            if (missingResources.isNotEmpty()) {
                missingOverrideResources += MissingOverrideResources(
                    gosFile = gosStringsFile,
                    missingResources = missingResources,
                )
            }
        }

        if (missingOverrideFiles.isNotEmpty() || missingOverrideResources.isNotEmpty()) {
            throw GradleException(
                buildFailureMessage(
                    missingOverrideFiles = missingOverrideFiles,
                    missingOverrideResources = missingOverrideResources,
                ),
            )
        }

        successMarkerFile.asFile.get().apply {
            parentFile.mkdirs()
            writeText("ok\n")
        }
    }
}

private fun findStringsFiles(resDirectory: File): List<File> {
    val entries = requireNotNull(resDirectory.listFiles()) {
        "Could not list files in ${resDirectory.path}"
    }
    val valuesDirectories = entries
        .filter { file ->
            file.isDirectory && (file.name == "values" || file.name.startsWith(prefix = "values-"))
        }
        .sortedBy { file ->
            file.name
        }

    return valuesDirectories
        .map { valuesDirectory ->
            valuesDirectory.resolve("strings.xml")
        }
        .filter { file ->
            file.isFile
        }
}

private fun readStringResources(
    file: File,
    documentBuilder: DocumentBuilder,
): Set<StringResourceKey> {
    val document = documentBuilder.parse(file)
    val resources = sortedSetOf<StringResourceKey>()
    val childNodes = document.documentElement.childNodes

    for (index in 0 until childNodes.length) {
        val element = childNodes.item(index) as? Element ?: continue
        if (element.tagName !in stringResourceTags) {
            continue
        }

        val name = element.getAttribute("name")
        if (name.isBlank()) {
            continue
        }

        resources += StringResourceKey(
            type = element.tagName,
            name = name,
        )
    }

    return resources
}

private fun newDocumentBuilderFactory(): DocumentBuilderFactory {
    return DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = false
        setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
    }
}

private fun buildFailureMessage(
    missingOverrideFiles: List<MissingOverrideFile>,
    missingOverrideResources: List<MissingOverrideResources>,
): String {
    return buildString {
        appendLine("GOS string override check failed.")
        appendLine()
        appendLine(
            "Every upstream values*/strings.xml file that defines a GOS-overridden resource must " +
                    "have a matching src/gos/res values directory with all canonical GOS string overrides.",
        )

        if (missingOverrideFiles.isNotEmpty()) {
            appendLine()
            appendLine("Missing GOS override files:")
            missingOverrideFiles.forEach { missingOverrideFile ->
                appendLine("  ${missingOverrideFile.gosFile.path}")
                appendLine("    required because ${missingOverrideFile.mainFile.path} defines:")
                missingOverrideFile.overlappingResources.forEach { resource ->
                    appendLine("      ${resource.displayName}")
                }
            }
        }

        if (missingOverrideResources.isNotEmpty()) {
            appendLine()
            appendLine("Missing resources in GOS override files:")
            missingOverrideResources.forEach { missingOverrideResource ->
                appendLine("  ${missingOverrideResource.gosFile.path}")
                missingOverrideResource.missingResources.forEach { resource ->
                    appendLine("    ${resource.displayName}")
                }
            }
        }
    }
}
