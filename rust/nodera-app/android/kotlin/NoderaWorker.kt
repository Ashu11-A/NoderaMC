package dev.nodera.app

import android.content.Context
import android.util.Log
import dalvik.system.DexClassLoader
import java.io.File

/**
 * Runs the **real Java peer worker** inside this app's process.
 *
 * # Why this exists, and why it is not what we first believed
 *
 * The worker (`dev.nodera.headless.HeadlessPeerMain`) is the same program the desktop app
 * supervises: it owns the node identity, the P2P transport, region validation, the archive lane and
 * the control endpoint on `127.0.0.1:25610`. It was assumed unportable to Android for two reasons,
 * and **both turned out to be wrong when measured**:
 *
 *  * *"Java 21 type-pattern switches compile to `SwitchBootstraps.typeSwitch`, which ART cannot
 *    run."* The real obstacle was the **D8 version**: build-tools 34 ships R8 8.2, which rejects
 *    Java 21 class files outright ("Unsupported class file major version 65"). D8 from build-tools
 *    35 dexes the whole closure without complaint and ART runs it.
 *  * *"rocksdbjni and zstd-jni ship desktop-only natives."* They do, and they are never loaded on
 *    the path the worker actually takes — the dex payload strips every foreign `.so` and the worker
 *    still reaches "online".
 *
 * One genuine incompatibility remained: `Thread.ofVirtual()` exists on ART and **throws when
 * started**, which is why `dev.nodera.core.concurrent.Threads` probes the capability instead of
 * assuming it.
 *
 * # How it is loaded
 *
 * The worker ships as `assets/nodera-worker.jar` — a multidex archive with its resources — and is
 * copied to internal storage on first run because `DexClassLoader` needs a real file path. It is
 * then loaded in a child class loader and its `main` is called on a background thread. In-process
 * rather than as a separate process because Android does not let an app spawn `dalvikvm`, and
 * because the app's own lifetime is exactly the lifetime the worker should have here.
 *
 * # What the app does with it afterwards
 *
 * Nothing special: the worker opens `127.0.0.1:25610` and the Rust side connects to it with the
 * same control-protocol code the desktop uses. That is the point of doing this properly rather than
 * reimplementing a smaller peer in Rust — the phone becomes the same kind of node as a desktop.
 */
object NoderaWorker {

    private const val TAG = "NoderaMC"
    private const val ASSET = "nodera-worker.jar"
    private const val MAIN_CLASS = "dev.nodera.headless.HeadlessPeerMain"

    @Volatile
    private var started = false

    /**
     * Start the worker once. Safe to call again; later calls are no-ops.
     *
     * Never throws: a phone that cannot start the worker must still show its UI and say what is
     * wrong, rather than failing to open.
     */
    @JvmStatic
    @Synchronized
    fun start(context: Context) {
        if (started) return
        started = true
        Thread({ run(context) }, "nodera-worker-boot").apply { isDaemon = true }.start()
    }

    private fun run(context: Context) {
        try {
            val home = File(context.filesDir, "worker").apply { mkdirs() }
            val jar = stageWorkerJar(context)
            val optimised = File(context.codeCacheDir, "nodera-dex").apply { mkdirs() }

            // The worker reads its paths from `user.home` and from NODERA_* environment variables.
            // Environment variables cannot be set for our own process from Java, so the properties
            // are what steer it — every path it derives hangs off `user.home`.
            System.setProperty("user.home", home.absolutePath)
            // ART has no console; slf4j-simple writes to stderr, which logcat captures per-process.
            System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "info")
            // …and also to a file, which is what the app's Activity screen shows. Without it the
            // worker's own account of itself would be visible only over `adb logcat`, i.e. to
            // nobody holding the phone.
            System.setProperty(
                "org.slf4j.simpleLogger.logFile",
                File(home, "worker.log").absolutePath,
            )

            val loader = DexClassLoader(
                jar.absolutePath,
                optimised.absolutePath,
                null,
                NoderaWorker::class.java.classLoader,
            )
            Log.i(TAG, "worker: loading $MAIN_CLASS from ${jar.absolutePath}")
            val main = loader.loadClass(MAIN_CLASS).getMethod("main", Array<String>::class.java)
            // `main` blocks for the life of the worker, which is why this is on its own thread.
            main.invoke(null, arrayOf<String>())
            Log.w(TAG, "worker: main returned — the peer has stopped")
        } catch (failure: Throwable) {
            // Throwable: a missing class or an ART-level error must reach logcat, not vanish into a
            // dead thread. The app keeps running and the UI reports the worker as offline, which is
            // true.
            Log.e(TAG, "worker: could not start", failure)
        }
    }

    /**
     * Copy the worker out of the APK's assets, if the staged copy is not current.
     *
     * Staleness is keyed on the package's own `lastUpdateTime`, written beside the jar. Two things
     * ruled out the obvious alternatives: `AssetManager.openFd` throws for anything the APK
     * compressed ("it is probably compressed" — which this 11 MB jar certainly is), and hashing the
     * asset would mean reading it in full on every cold start to answer a question that only
     * changes when the app is reinstalled.
     *
     * Re-staging on update matters: a new app with the previous worker is the one combination
     * neither side is ever tested against.
     */
    private fun stageWorkerJar(context: Context): File {
        val target = File(context.filesDir, ASSET)
        val stamp = File(context.filesDir, "$ASSET.stamp")
        val installed = context.packageManager
            .getPackageInfo(context.packageName, 0)
            .lastUpdateTime
            .toString()

        if (target.exists() && stamp.exists() && stamp.readText() == installed) {
            return target
        }
        // Writable first — the previous copy is read-only (see below) and could not otherwise be
        // replaced when the app updates.
        target.setWritable(true, true)
        context.assets.open(ASSET).use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        // API 29+ refuses to load a dex file that anyone can write:
        //
        //   java.lang.SecurityException: Writable dex file '…/nodera-worker.jar' is not allowed.
        //
        // The rule exists so code cannot be swapped underneath a running process, which is a good
        // rule; the app complies rather than working around it. `InMemoryDexClassLoader` would also
        // satisfy it, but a class loader built from buffers carries no resources, and the worker
        // reads its VERSION and its SLF4J provider registration out of the jar.
        if (!target.setReadOnly()) {
            Log.w(TAG, "worker: could not mark the staged jar read-only; the loader may refuse it")
        }
        stamp.writeText(installed)
        Log.i(TAG, "worker: staged ${target.length()} bytes to ${target.absolutePath}")
        return target
    }
}
