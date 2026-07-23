rootProject.name = "build-logic"

// build-logic is an included build: the root's auto-imported `libs` catalog is invisible here.
// Point a build-logic-local catalog at the same file so convention plugins and this build's own
// script consume one source of truth instead of hardcoding coordinates.
dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            from(files("../../gradle/libs.versions.toml"))
        }
    }
}
