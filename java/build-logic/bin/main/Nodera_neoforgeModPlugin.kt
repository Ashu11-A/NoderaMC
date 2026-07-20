/**
 * Precompiled [nodera.neoforge-mod.gradle.kts][Nodera_neoforge_mod_gradle] script plugin.
 *
 * @see Nodera_neoforge_mod_gradle
 */
public
class Nodera_neoforgeModPlugin : org.gradle.api.Plugin<org.gradle.api.Project> {
    override fun apply(target: org.gradle.api.Project) {
        try {
            Class
                .forName("Nodera_neoforge_mod_gradle")
                .getDeclaredConstructor(org.gradle.api.Project::class.java, org.gradle.api.Project::class.java)
                .newInstance(target, target)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.targetException
        }
    }
}
