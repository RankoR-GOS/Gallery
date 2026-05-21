plugins {
    `kotlin-dsl`
}

gradlePlugin {
    plugins {
        register("gosStringOverrides") {
            id = "gos-string-overrides"
            implementationClass = "GosStringOverridesPlugin"
        }
    }
}
