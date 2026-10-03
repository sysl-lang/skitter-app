import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.image.BufferedImage
import java.util.Properties
import javax.imageio.ImageIO

plugins {
    id("com.android.application")
}

// **Your two names, read from `gradle.properties` rather than written here.** That is the whole
// point of the arrangement: this file, `CMakeLists.txt`, the manifest and the activity are Skitter's
// machinery and are meant to be left alone, so nothing in any of them says what your application is
// called. If you find yourself editing a name in this file, the name you wanted is one file up.
val applicationIdProp = providers.gradleProperty("skitter.applicationId").get()
val appNameProp = providers.gradleProperty("skitter.appName").get()

// **Optional, and its default is the interesting part.** A project that wants nothing but SDL3 —
// which is most of them — should not have to carry a line saying so, and an older `gradle.properties`
// written before this existed must still configure. `orElse("")` gives both.
val sdlLibrariesProp = providers.gradleProperty("skitter.sdlLibraries").orElse("").get().trim()

// Whether the screen stays on while the app is in front. Anything but `true` is `false`, which is
// also what a `gradle.properties` written before this line existed gets.
val keepAwakeProp = providers.gradleProperty("skitter.keepAwake").orElse("false").get().trim() == "true"

// **The permissions, by the name a person would say rather than the one Android spells.** Each
// friendly name stands for every `<uses-permission>` it takes on every Android this app installs on,
// which is why it is a table rather than a string substitution: `bluetooth` is two lines, because the
// permission was split in Android 12 and `minSdk` is below that. A name with a dot in it is taken as
// Android's own spelling and passed through, so nothing is blocked by the table being short.
//
// The second element is `maxSdkVersion`, for a permission that a later Android replaced.
val permissionTable = mapOf(
    "microphone" to listOf("android.permission.RECORD_AUDIO" to null),
    "camera" to listOf("android.permission.CAMERA" to null),
    "internet" to listOf("android.permission.INTERNET" to null),
    "bluetooth" to listOf(
        "android.permission.BLUETOOTH" to 30,
        "android.permission.BLUETOOTH_CONNECT" to null,
    ),
    "vibrate" to listOf("android.permission.VIBRATE" to null),
)

// **Resolved here, while Gradle is configuring**, so a misspelt name stops the build before anything
// is compiled and says what it would have accepted, rather than producing an APK that is quietly
// missing the permission it was asked for.
val permissionLines: List<String> = providers.gradleProperty("skitter.permissions").orElse("").get()
    .split(" ").filter { it.isNotBlank() }.distinct()
    .flatMap { name ->
        when {
            name.contains('.') -> listOf(name to null)
            else -> permissionTable[name] ?: throw GradleException(
                "skitter.permissions: unknown permission '$name'. The names are " +
                    permissionTable.keys.joinToString(", ") +
                    ", or Android's own spelling with a dot in it, e.g. android.permission.WAKE_LOCK",
            )
        }
    }
    .distinct()
    .map { (name, maxSdk) ->
        val max = if (maxSdk == null) "" else " android:maxSdkVersion=\"$maxSdk\""

        "    <uses-permission android:name=\"$name\"$max />"
    }

// **The launcher icon, from one square PNG.** Empty means no icon at all — no attribute in the
// manifest and no resource in the APK, so Android draws its generic one, exactly as before this line
// existed. A path is relative to the project root, which is where `gradle.properties` is.
//
// **Checked here, while Gradle is configuring**, for the same reason the permissions are: a missing
// file or a picture of the wrong shape stops the build with what was wrong, rather than producing an
// icon squashed out of proportion or blurred up from a thumbnail. 432 px is the floor because it is
// the largest size anything is drawn at — the adaptive layer at xxxhdpi — so nothing is ever enlarged.
val iconMinimum = 432

val iconFile: File? = providers.gradleProperty("skitter.icon").orElse("").get().trim()
    .takeIf { it.isNotEmpty() }
    ?.let { name ->
        val f = rootProject.file(name)
        if (!f.isFile) throw GradleException("skitter.icon: there is no file '$name' (looked for ${f.absolutePath})")

        val image = ImageIO.read(f)
            ?: throw GradleException("skitter.icon: '$name' is not an image Java can read; give it a PNG")

        if (image.width != image.height) {
            throw GradleException(
                "skitter.icon: '$name' is ${image.width}×${image.height}, and an icon has to be square. " +
                    "Crop or pad it to a square, 1024×1024 for preference",
            )
        }

        if (image.width < iconMinimum) {
            throw GradleException(
                "skitter.icon: '$name' is ${image.width}×${image.height}, and the largest icon Android draws " +
                    "is $iconMinimum×$iconMinimum, so it would be enlarged and blurred. Give it at least " +
                    "$iconMinimum px a side, 1024×1024 for preference",
            )
        }

        f
    }

val iconBackgroundProp: String = providers.gradleProperty("skitter.iconBackground").orElse("").get().trim()
    .ifEmpty { "#FFFFFF" }
    .also {
        if (!Regex("#[0-9A-Fa-f]{6}").matches(it)) {
            throw GradleException("skitter.iconBackground: '$it' is not a colour; write it as #RRGGBB, e.g. #1E88E5")
        }
    }

// The two attributes the generated manifest adds to `<application>`, and only where there is an icon.
val iconLines: List<String> = if (iconFile == null) {
    emptyList()
} else {
    listOf(
        "    <application android:icon=\"@mipmap/ic_launcher\" android:roundIcon=\"@mipmap/ic_launcher_round\" />",
    )
}

// **The release signing key, which is deliberately not in this repository.** It is read from
// `~/.android/sysl-signing.properties` — keystore path, password and alias — and where that file is
// absent the release build is simply unsigned, so a fresh clone still builds without it. A key
// committed beside the thing it signs is not a key.
//
// **Losing it costs more than it looks.** Android identifies an app by its signature, so a new key
// means a new identity: an APK signed with a different one will not install over an existing
// install, and everybody who has it has to uninstall first.
val signingProps = Properties().apply {
    val f = File(System.getProperty("user.home"), ".android/sysl-signing.properties")

    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    // **The namespace is the R class's package and has nothing to do with the activity**, which
    // lives in `sh.sysl.skitter` and stays there. Before Skitter these two had to agree with the
    // activity's package, the JNI symbol and the manifest, and keeping four things in step by hand
    // is what put the string `bouncing` into a repository that had nothing to do with bouncing.
    namespace = applicationIdProp
    compileSdk = 36

    // **Pinned, and pinned to the stable one.** AGP downloads whatever it defaults to if this is
    // absent, so leaving it out means the toolchain changes under you when AGP does. `CMakeLists.txt`
    // hands *this* NDK to `sysl build-c` precisely so the two halves of the build cannot end up on
    // different ones — a machine normally has two, because AGP downloads its own.
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = applicationIdProp

        // **The label, defined here rather than in a `strings.xml`.** A resource file would be a
        // second place your application's name lives, and the manifest reads `@string/app_name`
        // either way — so the string is generated from the property and there is no file to forget.
        resValue("string", "app_name", appNameProp)

        // `skitter.keepAwake`, which the activity reads at startup. A resource rather than a manifest
        // entry because the activity is the only thing that reads it.
        resValue("bool", "skitter_keep_awake", keepAwakeProp.toString())

        // **26, and the number is the Scala standard library's rather than SDL's or sysl's.**
        // `scala-library` uses class-file features `d8` will only desugar from 26 up — *"Increase the
        // minSdkVersion to 26 or above"*, which is what it says rather than something inferred — so
        // an APK carrying the Scala runtime starts at Android 8.0. SDL's own floor is 21 and sysl's
        // triple states 24.
        //
        // **The three numbers do not have to agree, and the higher wins.** The `.so` is compiled
        // against `aarch64-linux-android24`'s declarations, which every device from 24 up has, and it
        // is installed only where the APK is — so a native half built for 24 running on 26 is exactly
        // as correct as one built for 26. What is not allowed is the other direction: a `minSdk`
        // *below* the triple's number installs on a device whose Bionic may not have what the `.so`
        // was compiled against.
        minSdk = 26
        targetSdk = 36

        // Yours to bump when you ship. `versionCode` is what Android compares between installs and
        // must only ever go up; `versionName` is shown to a person and can say anything.
        versionCode = 1
        versionName = "0.1.0"

        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_PLATFORM=android-24")

                // **The extra SDL libraries, handed to CMake rather than written there.** A CMake
                // list is semicolon-separated, and `gradle.properties` holds a space-separated one
                // because that is what reads naturally in a config file — so the split happens here,
                // at the one place both conventions are in view. `./fetch-sdl3.sh` reads the same
                // property, so what is downloaded and what is linked cannot disagree.
                val libs = sdlLibrariesProp.split(" ").filter { it.isNotBlank() }

                if (libs.isNotEmpty()) {
                    arguments += "-DSKITTER_SDL_LIBRARIES=${libs.joinToString(";")}"
                }
            }
        }

        // **One ABI, and it is not a limitation worth apologising for.** On an Apple Silicon host the
        // emulator runs `arm64-v8a`, and so does every Android device made since 2015 — so one build
        // covers the emulator and the hardware. `x86_64` matters only on an Intel host or a CI
        // runner, `armeabi-v7a` only for pre-2015 phones; adding either is a line here and a second
        // row in the sysl registry that does not exist yet.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }

    buildFeatures {
        // What unpacks the SDL3 AAR into the prefab layout that `find_package(SDL3 CONFIG)` reads.
        prefab = true

        // **Off by default since AGP 9, and the failure is at configuration time rather than at the
        // resource.** Without it the `resValue` above is refused with *"defaultConfig contains custom
        // resource values, but the feature is disabled"* — which names the symptom and not this line.
        resValues = true
    }

    signingConfigs {
        create("release") {
            signingProps.getProperty("SYSL_KEYSTORE")?.let {
                storeFile = File(it)
                storePassword = signingProps.getProperty("SYSL_KEYSTORE_PASSWORD")
                keyAlias = signingProps.getProperty("SYSL_KEY_ALIAS")
                keyPassword = signingProps.getProperty("SYSL_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            if (signingProps.getProperty("SYSL_KEYSTORE") != null) {
                signingConfig = signingConfigs.getByName("release")
            }

            // **Shrinking is off, and that is a decision rather than a default.** R8 works by
            // reachability, and two things here are reached by neither: the activity is named in the
            // manifest as a string, and `nativeSetSystemBars` is called *from native code* through
            // JNI. Both need keep rules, and getting one wrong produces an app that installs and
            // dies at the first inset.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
    }
}

// **The permissions and the icon reach the APK as a second manifest, merged into the first** — the
// same merger that folds a library's manifest into yours. That is what keeps `AndroidManifest.xml`
// Skitter's: the lines `skitter.permissions` and `skitter.icon` ask for are written to a generated
// file under `build/`, and with both properties empty the file is an empty `<manifest>` and the merged
// result is unchanged.
abstract class GeneratedManifest : DefaultTask() {
    @get:Input
    abstract val lines: ListProperty<String>

    @get:OutputFile
    abstract val manifest: RegularFileProperty

    @TaskAction
    fun write() {
        manifest.get().asFile.writeText(
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">\n" +
                lines.get().joinToString("") { "$it\n" } +
                "</manifest>\n",
        )
    }
}

// **Every launcher icon Android asks for, drawn from the one PNG with nothing but the JDK.**
//
// - `mipmap-<density>/ic_launcher.png`, 48 dp: the picture itself, for a launcher that does not take
//   adaptive icons, and `ic_launcher_round.png`, the same over the background colour, cut to a circle.
// - `mipmap-anydpi-v26/ic_launcher.xml` (and `_round`): the adaptive icon every Android since 8 draws,
//   whose layers are 108 dp squares the launcher masks to its own shape — circle, squircle, teardrop —
//   and may move a little under a finger. Only the middle 66 dp is guaranteed to be seen, so the
//   foreground is the picture scaled into that middle, and the background is the colour.
// - a `<monochrome>` layer for Android 13's themed icons: the foreground's shape, taken from its alpha,
//   which the system tints to match the wallpaper. A logo on a transparent background has a shape; a
//   picture that is opaque to its edges becomes a plain rounded square there.
//
// Downscaling halves the picture repeatedly with bilinear filtering before the last step, and works in
// premultiplied alpha, which is what keeps a 1024 px original sharp at 48 and stops dark fringes
// appearing round a transparent edge.
abstract class LauncherIcon : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val source: RegularFileProperty

    @get:Input
    abstract val background: Property<String>

    @get:OutputDirectory
    abstract val res: DirectoryProperty

    private val densities = listOf("mdpi" to 1.0, "hdpi" to 1.5, "xhdpi" to 2.0, "xxhdpi" to 3.0, "xxxhdpi" to 4.0)

    @TaskAction
    fun generate() {
        val out = res.get().asFile
        out.deleteRecursively()

        val original = ImageIO.read(source.get().asFile)
        val logo = BufferedImage(original.width, original.height, BufferedImage.TYPE_INT_ARGB_PRE)
        logo.createGraphics().apply { drawImage(original, 0, 0, null); dispose() }

        val colour = Color(Integer.parseInt(background.get().substring(1), 16))

        for ((name, scale) in densities) {
            val dir = File(out, "mipmap-$name").apply { mkdirs() }
            val legacy = (48 * scale).toInt()
            val layer = (108 * scale).toInt()
            val safe = (66 * scale).toInt()

            write(scaled(logo, legacy), File(dir, "ic_launcher.png"))
            write(round(scaled(logo, legacy), colour), File(dir, "ic_launcher_round.png"))

            val foreground = centred(scaled(logo, safe), layer)
            write(foreground, File(dir, "ic_launcher_foreground.png"))
            write(monochrome(foreground), File(dir, "ic_launcher_monochrome.png"))
        }

        File(out, "values").mkdirs()
        File(out, "values/ic_launcher_background.xml").writeText(
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                "<resources>\n" +
                "    <color name=\"ic_launcher_background\">${background.get()}</color>\n" +
                "</resources>\n",
        )

        val adaptive = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
            "<adaptive-icon xmlns:android=\"http://schemas.android.com/apk/res/android\">\n" +
            "    <background android:drawable=\"@color/ic_launcher_background\" />\n" +
            "    <foreground android:drawable=\"@mipmap/ic_launcher_foreground\" />\n" +
            "    <monochrome android:drawable=\"@mipmap/ic_launcher_monochrome\" />\n" +
            "</adaptive-icon>\n"

        File(out, "mipmap-anydpi-v26").mkdirs()
        File(out, "mipmap-anydpi-v26/ic_launcher.xml").writeText(adaptive)
        File(out, "mipmap-anydpi-v26/ic_launcher_round.xml").writeText(adaptive)
    }

    private fun canvas(side: Int) =
        BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB_PRE)

    private fun quality(g: Graphics2D) = g.apply {
        setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    }

    private fun scaled(src: BufferedImage, side: Int): BufferedImage {
        var img = src
        var w = src.width

        while (w / 2 >= side) {
            w /= 2
            img = resized(img, w)
        }

        return if (w == side) img else resized(img, side)
    }

    private fun resized(src: BufferedImage, side: Int) = canvas(side).also {
        quality(it.createGraphics()).apply { drawImage(src, 0, 0, side, side, null); dispose() }
    }

    private fun centred(src: BufferedImage, side: Int) = canvas(side).also {
        val at = (side - src.width) / 2

        it.createGraphics().apply { drawImage(src, at, at, null); dispose() }
    }

    private fun round(src: BufferedImage, colour: Color) = canvas(src.width).also {
        val side = src.width.toDouble()

        quality(it.createGraphics()).apply {
            color = Color.WHITE
            fill(Ellipse2D.Double(0.0, 0.0, side, side))
            composite = AlphaComposite.SrcAtop
            color = colour
            fillRect(0, 0, src.width, src.height)
            drawImage(src, 0, 0, null)
            dispose()
        }
    }

    // White wherever the picture is, at the picture's own opacity: the system supplies the colour.
    private fun monochrome(src: BufferedImage) = canvas(src.width).also {
        for (y in 0 until src.height) for (x in 0 until src.width) {
            val a = src.getRGB(x, y) ushr 24

            it.setRGB(x, y, (a shl 24) or 0xFFFFFF)
        }
    }

    private fun write(img: BufferedImage, f: File) {
        val plain = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_ARGB)

        plain.createGraphics().apply { drawImage(img, 0, 0, null); dispose() }
        ImageIO.write(plain, "png", f)
    }
}

androidComponents {
    onVariants { variant ->
        val task = tasks.register<GeneratedManifest>("${variant.name}GeneratedManifest") {
            lines.set(permissionLines + iconLines)
        }

        variant.sources.manifests.addGeneratedManifestFile(task, GeneratedManifest::manifest)

        if (iconFile != null) {
            val icon = tasks.register<LauncherIcon>("${variant.name}LauncherIcon") {
                source.set(iconFile)
                background.set(iconBackgroundProp)
            }

            variant.sources.res?.addGeneratedSourceDirectory(icon, LauncherIcon::res)
        }
    }
}

// **The activity is Scala, which AGP cannot compile — so sbt does, and Gradle takes the jar.**
//
// That is the whole cost of the choice and it is worth stating plainly: the Android Gradle plugin
// builds Java and Kotlin itself and has no Scala support, so this is a second build system in the
// project. What it does *not* cost is anything at run time — the class references nothing from the
// Scala standard library, so no `scala-library` is dexed in and the APK carries two extra classes.
//
// `./gradlew assembleDebug` is still the one command: this task runs sbt, and the jar it writes is
// on the application's classpath below.
//
// **Nothing here is named for your application**, which is what lets the jar's path be a constant.
val activityDir = rootProject.file("activity")
val activityJar = activityDir.resolve("target/skitter-activity.jar")

val compileActivity = tasks.register<Exec>("compileActivity") {
    description = "Compiles Skitter's activity with sbt, since AGP cannot."
    workingDir = activityDir

    // Both jars are handed over rather than looked for on sbt's side. SDL's `classes.jar` lives
    // *inside* the AAR, so it has to be unzipped by somebody, and this is where the AAR's path is
    // already known.
    doFirst {
        // **Every AAR, not the first one.** `firstOrNull()` is fine while there is one; with a
        // second beside it — SDL3_ttf, say — it picks whichever the filesystem lists first, and
        // handing sbt the wrong `classes.jar` leaves `SDLActivity` off the classpath. Scala reports
        // that as a *cyclic reference* on the `extends` clause rather than as a missing type.
        val aars = file("libs").listFiles { f -> f.name.endsWith(".aar") }?.sorted().orEmpty()

        if (aars.isEmpty()) throw GradleException("no AAR in app/libs — run ./fetch-sdl3.sh")

        val jars = aars.map { aar ->
            val into = layout.buildDirectory.dir("aar-classes/${aar.nameWithoutExtension}").get().asFile

            copy {
                from(zipTree(aar)) { include("classes.jar") }
                into(into)
            }

            into.resolve("classes.jar")
        }.filter { it.isFile }

        // `android.jar` from the SDK the rest of the build already requires. The newest installed
        // platform is taken rather than one matching `compileSdk`, because the directory may carry a
        // minor version (`android-36.1`) that the number does not.
        val sdk = System.getenv("ANDROID_HOME")
            ?: System.getenv("ANDROID_SDK_ROOT")
            ?: throw GradleException("ANDROID_HOME is not set")

        val androidJar = File(sdk, "platforms").listFiles()
            ?.map { it.resolve("android.jar") }
            ?.filter { it.isFile }
            ?.maxByOrNull { it.parentFile.name }
            ?: throw GradleException("no android.jar under $sdk/platforms")

        environment(
            "SKITTER_CLASSPATH",
            (listOf(androidJar) + jars).joinToString(File.pathSeparator),
        )
    }

    commandLine("sbt", "-batch", "package")

    // sbt names the jar for the project and version; the build renames it to something Gradle can
    // predict, since a version bump would otherwise silently stop matching.
    doLast {
        val built = activityDir.resolve("target").walkTopDown()
            .firstOrNull { it.name.endsWith(".jar") && it.name.startsWith("skitter-activity") }
            ?: throw GradleException("sbt produced no jar in activity/target")

        if (built != activityJar) built.copyTo(activityJar, overwrite = true)
    }

    inputs.dir(activityDir.resolve("src"))
    inputs.file(activityDir.resolve("build.sbt"))
    outputs.file(activityJar)
}

tasks.withType<com.android.build.gradle.tasks.MergeSourceSetFolders>().configureEach {
    dependsOn(compileActivity)
}

tasks.matching { it.name.startsWith("compile") && it.name.contains("JavaWithJavac") }.configureEach {
    dependsOn(compileActivity)
}

dependencies {
    implementation(files(activityJar) { builtBy(compileActivity) })

    // **The Scala standard library, so the activity can use the language and not only its syntax.**
    // Skitter's own activity needs nothing from it, but an application that grows real Java-side code
    // will, and finding that out at the first `List` is a worse time to find it out.
    implementation("org.scala-lang:scala3-library_3:3.8.2")

    // **The AAR is not in this repository** — `./fetch-sdl3.sh` downloads it, and `.gitignore` keeps
    // it out. It is 16 MB of binaries built against an NDK and an API level somebody else chose, and
    // the org's rule against carrying a prebuilt `.so` in a package is the same argument one level
    // up: what is committed here should be readable, and a `.so` is not.
    implementation(fileTree("libs") { include("*.aar") })
}
