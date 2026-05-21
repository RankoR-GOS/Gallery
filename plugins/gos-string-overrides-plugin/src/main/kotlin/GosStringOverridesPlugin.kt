import org.gradle.api.Plugin
import org.gradle.api.Project

class GosStringOverridesPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        project.tasks.register(
            "checkGosStringOverrides",
            CheckGosStringOverridesTask::class.java,
        ) {
            description = "Checks that GOS string overrides cover upstream qualified string resource files."
            group = "verification"
            mainResDirectory.set(project.layout.projectDirectory.dir("src/main/res"))
            gosResDirectory.set(project.layout.projectDirectory.dir("src/gos/res"))
            successMarkerFile.set(
                project.layout.buildDirectory.file("intermediates/gos-string-overrides/check.txt"),
            )
        }
    }
}
