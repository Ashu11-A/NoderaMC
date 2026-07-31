import groovy.json.JsonSlurper
import java.net.URI
import java.util.Properties

plugins {
    id("nodera.java-library")
}

// core is Minecraft-free (Task 0 §4.1: depends on nothing but the JDK).
dependencies {
    // Deliberately empty: Task 2 acceptance #5 (ArchUnit enforces in testkit). core is the JDK and
    // nothing else. A comment here used to describe a fastutil dependency over this empty block;
    // fastutil was never used by core OR by engine, and is now gone from both.
}

// The product version reaches running Java code as a generated resource rather than as a source
// constant: `NoderaConstants.PRODUCT_VERSION` is published on the wire and shown in peer lists, and
// a hand-edited constant is the field that silently disagrees with the jar it ships in. Expansion is
// scoped to the one file so no other resource is exposed to template syntax.
tasks.named<ProcessResources>("processResources") {
    val noderaVersion = project.extra["noderaVersion"] as String
    inputs.property("noderaVersion", noderaVersion)
    filesMatching("dev/nodera/core/nodera-version.properties") {
        expand(mapOf("noderaVersion" to noderaVersion))
    }
}

// The project's own tracker and rendezvous services, compiled into the jar.
//
// The list is one file, maintained by pull request, and it does not live in this tree: it is
// `index.json` on the orphan `services` branch (`git show services:README.md`). The companion app
// compiles the same file in (`app/build.rs`); until the Java side did too, `NoderaSettings.defaults()`
// and the headless worker both fell back to `127.0.0.1` — a tracker that on a player's machine is
// that machine, and on a phone is the phone. A release that reaches for loopback announces into a
// network of one.
//
// Generated rather than hand-mirrored, and generated into the flat `<kind> <route>` form the worker
// already parses for the app's synchronised list: two representations of one list are two things to
// keep in step, and the JSON is parsed once here by a real parser instead of at runtime by a
// hand-rolled one. Every name below — the branch, the file, the URL, the cache directory — comes
// from `/layout.properties`; nothing here may guess any of them.
val layoutManifest = Properties().apply {
    rootProject.layout.projectDirectory.file("layout.properties").asFile.inputStream().use { load(it) }
}

fun layoutValue(key: String): String =
    layoutManifest.getProperty(key)?.trim() ?: error("layout.properties must declare $key")

// Captured as plain values at configuration time so the execution-time resolution below closes over
// strings and files rather than over the project — which is what keeps the configuration cache.
val repositoryRoot = rootProject.layout.projectDirectory.asFile
val servicesBranch = layoutValue("services.branch")
val servicesRemote = layoutValue("services.remote")
val servicesIndexName = layoutValue("services.index")
val servicesRawUrl = layoutValue("services.rawBase") + servicesIndexName
val servicesCacheFile = File(File(repositoryRoot, layoutValue("dir.services")), servicesIndexName)

// Which commit the branch is at, if it is here at all. This is the task's input: it changes exactly
// when the published list changes, so the generated resource is rebuilt then and cached otherwise.
// A tree with no ref reports an empty string and the task falls through to the network or the cache.
val servicesRefId: String = providers.exec {
    workingDir = repositoryRoot
    commandLine("git", "rev-parse", "--verify", "--quiet", "$servicesBranch^{commit}")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim() }.orElse("").get()

val generateOfficialServices = tasks.register("generateOfficialServices") {
    val target = layout.buildDirectory.file(
        "generated/official-services/dev/nodera/core/official-services.list"
    )
    // Not `inputs.file`: the source is a git ref, not a path in the working tree. The ref id stands
    // in for it, and the raw URL stands in for a tree that has no ref — in which case there is
    // nothing to fingerprint and the task simply runs.
    inputs.property("servicesRef", servicesRefId)
    inputs.property("servicesRawUrl", servicesRawUrl)
    outputs.file(target)
    doLast {
        // The same five steps as `scripts/services.py` and `app/build.rs`, in the same order: the
        // local ref, the fetched remote ref, a shallow fetch, the URL, the cache. Three resolvers
        // that disagreed about which list a build compiled in would be a defect nobody could see.
        fun git(vararg arguments: String): String? {
            val process = ProcessBuilder(listOf("git") + arguments)
                .directory(repositoryRoot)
                .redirectErrorStream(false)
                .start()
            val body = process.inputStream.bufferedReader().use { it.readText() }
            return if (process.waitFor() == 0) body else null
        }

        fun gitShow(ref: String): String? =
            git("show", "$ref:$servicesIndexName")?.takeIf { it.isNotBlank() }

        val body = gitShow(servicesBranch)
            ?: gitShow("$servicesRemote/$servicesBranch")
            // A shallow clone and every `actions/checkout` arrive with no such ref. Without this
            // step each of them falls through to the web server, and a build that could have read
            // the branch reads an HTTP response instead.
            ?: (git("fetch", "--depth", "1", "--quiet", servicesRemote, servicesBranch)
                ?.let { gitShow("FETCH_HEAD") })
            ?: runCatching {
                val connection = URI(servicesRawUrl).toURL().openConnection()
                connection.connectTimeout = 15_000
                connection.readTimeout = 15_000
                connection.getInputStream().bufferedReader().use { it.readText() }
            }.getOrNull()
            ?: servicesCacheFile.takeIf { it.isFile }?.readText()
            ?: error(
                "cannot resolve $servicesIndexName from the '$servicesBranch' branch: no local " +
                    "ref, no $servicesRemote/$servicesBranch, no network and no cache at " +
                    "$servicesCacheFile. Run:\n    git fetch $servicesRemote " +
                    "$servicesBranch:$servicesBranch"
            )
        servicesCacheFile.parentFile.mkdirs()
        servicesCacheFile.writeText(body)

        val index = JsonSlurper().parseText(body) as Map<*, *>
        val services = (index["services"] as? List<*>).orEmpty()
        val lines = StringBuilder()
        lines.append("# Generated from ").append(servicesIndexName)
            .append(" on the '").append(servicesBranch)
            .append("' branch — do not edit. Change the source list instead.\n")
        for (service in services) {
            val entry = service as? Map<*, *> ?: continue
            val kind = (entry["kind"] as? String)?.trim().orEmpty()
            if (kind != "tracker" && kind != "rendezvous") {
                continue
            }
            for (endpoint in (entry["endpoints"] as? List<*>).orEmpty()) {
                val route = (endpoint as? String)?.trim().orEmpty()
                if (route.isNotEmpty()) {
                    lines.append(kind).append(' ').append(route).append('\n')
                }
            }
        }
        val file = target.get().asFile
        file.parentFile.mkdirs()
        file.writeText(lines.toString())
    }
}

sourceSets.named("main") {
    resources.srcDir(generateOfficialServices.map { layout.buildDirectory.dir("generated/official-services") })
}
