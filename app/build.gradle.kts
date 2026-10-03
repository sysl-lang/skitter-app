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
val iconAttributes: List<String> = if (iconFile == null) {
    emptyList()
} else {
    listOf("android:icon=\"@mipmap/ic_launcher\"", "android:roundIcon=\"@mipmap/ic_launcher_round\"")
}

// **Which way up the app is held, as a lock rather than a request.** Each name is two things, and
// both are needed:
//
// - `android:screenOrientation` on the activity, which holds from the moment Android starts it — so
//   a portrait app launched from a sideways phone does not come up sideways and then turn;
// - `SDL_ORIENTATIONS`, which SDL reads when the program creates its window and passes straight to
//   `setRequestedOrientation`, **replacing whatever the manifest said**. On its own the manifest
//   attribute would hold only until `create_window`, and the program's own `orient()` would then
//   decide. So the hint is handed over as `<meta-data android:name="SDL_ENV.SDL_ORIENTATIONS">`,
//   which SDL's Java half turns into an environment variable before the program starts — and an
//   environment variable outranks a `set_hint` at normal priority, so `orient()` cannot undo the
//   line and the two halves cannot disagree.
//
// `any`, the default, writes neither and leaves the decision to the program, as before.
val orientationTable = mapOf(
    "any" to null,
    "portrait" to ("portrait" to "Portrait"),
    "landscape" to ("landscape" to "LandscapeLeft"),
    "sensorPortrait" to ("sensorPortrait" to "Portrait PortraitUpsideDown"),
    "sensorLandscape" to ("sensorLandscape" to "LandscapeLeft LandscapeRight"),
)

val orientationProp: String = providers.gradleProperty("skitter.orientation").orElse("").get().trim()
    .ifEmpty { "any" }

val orientation: Pair<String, String>? = orientationTable[orientationProp]
    ?: if (orientationProp in orientationTable) null else throw GradleException(
        "skitter.orientation: '$orientationProp' is not an orientation. The names are " +
            orientationTable.keys.joinToString(", "),
    )

// **The colour of the status and navigation bars, and the shade of the icons drawn on them.** Empty
// keeps the look the template has always had: no theme of Skitter's, and nothing in the manifest.
//
// A colour generates a style, `SkitterBars`, whose parent is `AppTheme` — so the checked-in theme is
// untouched and still decides everything else — and the generated manifest points `<application>` at
// it. The style paints both bars that colour, and picks dark icons on a light colour and light icons
// on a dark one, by which of black or white has the greater contrast against it.
//
// **On Android 15 and later the colour is ignored**, because an app targeting 35 or above is drawn
// edge to edge whether it asks or not and its bars are transparent: what shows through them is
// whatever the program draws there. The icon shade still applies, so on those phones the line means
// "the colour my program draws behind the bars" and keeps the clock and battery readable against it.
val barColorProp: String = providers.gradleProperty("skitter.barColor").orElse("").get().trim()
    .also {
        if (it.isNotEmpty() && !Regex("#[0-9A-Fa-f]{6}").matches(it)) {
            throw GradleException("skitter.barColor: '$it' is not a colour; write it as #RRGGBB, e.g. #1E88E5")
        }
    }

// WCAG's relative luminance; 0.179 is where black and white have equal contrast against a colour.
val barIconsDark: Boolean = barColorProp.isNotEmpty() && run {
    val rgb = Integer.parseInt(barColorProp.substring(1), 16)

    fun linear(c: Int): Double = (c / 255.0).let { if (it <= 0.03928) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4) }

    val l = 0.2126 * linear(rgb shr 16 and 0xFF) + 0.7152 * linear(rgb shr 8 and 0xFF) + 0.0722 * linear(rgb and 0xFF)

    l > 0.179
}

// **The version, read from `program/package.hocon`** — the line `__VERSION__` reads, so the screen
// and the system's app info cannot disagree. Only the package's own `version` starts a line; a
// dependency's sits inside its braces, after the name.
val programVersion: String = Regex("""^\s*version\s*=\s*"([^"]+)"""", RegexOption.MULTILINE)
    .find(rootProject.file("program/package.hocon").readText())
    ?.groupValues?.get(1)
    ?: throw GradleException("program/package.hocon declares no version, and the app's version is read from it")

// **`versionCode` is derived from the same line**: `MAJOR.MINOR.PATCH` becomes
// `MAJOR × 1 000 000 + MINOR × 1 000 + PATCH`, so 0.1.0 is 1000 and 1.2.3 is 1002003. A second line to
// bump by hand is a second number to forget, and Android refuses an update whose code has not gone up.
// With every part below 1000 the order of the codes is the order of the versions, so bumping the
// version is bumping the code; anything that would break that — a part of 1000 or more, a suffix such
// as `-rc1`, which would share its release's code — stops the build rather than ship a code that does
// not go up.
val programVersionCode: Int = run {
    val parts = Regex("""(\d+)\.(\d+)\.(\d+)""").matchEntire(programVersion)?.groupValues?.drop(1)?.map { it.toInt() }
        ?: throw GradleException(
            "program/package.hocon: version '$programVersion' is not MAJOR.MINOR.PATCH, and Android's " +
                "versionCode is derived from those three numbers",
        )

    val (major, minor, patch) = parts

    if (minor >= 1000 || patch >= 1000 || major > 2099) {
        throw GradleException(
            "program/package.hocon: version '$programVersion' cannot become a versionCode that keeps going " +
                "up — MINOR and PATCH must be below 1000 and MAJOR at most 2099",
        )
    }

    major * 1_000_000 + minor * 1_000 + patch
}

// **Everything the generated manifest adds inside `<application>`, as one element** — a manifest
// holds one `<application>`, so the icon, the theme and the orientation each contribute to it rather
// than writing their own.
val applicationAttributes: List<String> = iconAttributes +
    (if (barColorProp.isEmpty()) emptyList() else listOf("android:theme=\"@style/SkitterBars\"", "tools:replace=\"android:theme\""))

val applicationChildren: List<String> = if (orientation == null) {
    emptyList()
} else {
    listOf(
        "        <meta-data android:name=\"SDL_ENV.SDL_ORIENTATIONS\" android:value=\"${orientation.second}\" />",
        "        <activity android:name=\"sh.sysl.skitter.SkitterActivity\" android:screenOrientation=\"${orientation.first}\" />",
    )
}

val applicationLines: List<String> = when {
    applicationAttributes.isEmpty() && applicationChildren.isEmpty() -> emptyList()
    applicationChildren.isEmpty() -> listOf("    <application ${applicationAttributes.joinToString(" ")} />")
    else -> listOf("    <application ${applicationAttributes.joinToString(" ")}>") + applicationChildren + "    </application>"
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

        // **Both from `program/package.hocon`'s `version`**, which is the one number to bump when you
        // ship — see `programVersionCode` above for how the code is derived and why it only goes up.
        versionCode = programVersionCode
        versionName = programVersion

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
// Skitter's: what `skitter.permissions`, `skitter.icon`, `skitter.orientation` and `skitter.barColor`
// ask for is written to a generated file under `build/`, and with all of them at their defaults the
// file is an empty `<manifest>` and the merged result is unchanged.
abstract class GeneratedManifest : DefaultTask() {
    @get:Input
    abstract val lines: ListProperty<String>

    @get:OutputFile
    abstract val manifest: RegularFileProperty

    @TaskAction
    fun write() {
        manifest.get().asFile.writeText(
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\"\n" +
                "    xmlns:tools=\"http://schemas.android.com/tools\">\n" +
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

// **The `SkitterBars` style `skitter.barColor` asks for, written under `build/`** so the checked-in
// `values/styles.xml` stays Skitter's. Its parent is `AppTheme`, so it changes the bars and nothing
// else. The navigation bar's icon shade is an attribute from Android 8.1, so the style is written
// twice: once for 8.0, once with that line for 8.1 and up.
abstract class BarTheme : DefaultTask() {
    @get:Input
    abstract val colour: Property<String>

    @get:Input
    abstract val darkIcons: Property<Boolean>

    @get:OutputDirectory
    abstract val res: DirectoryProperty

    @TaskAction
    fun generate() {
        val out = res.get().asFile
        out.deleteRecursively()

        fun style(navigationIcons: Boolean): String {
            val c = colour.get()
            val dark = darkIcons.get()
            val nav = if (navigationIcons) "        <item name=\"android:windowLightNavigationBar\">$dark</item>\n" else ""

            return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                "<resources>\n" +
                "    <style name=\"SkitterBars\" parent=\"@style/AppTheme\">\n" +
                "        <item name=\"android:windowDrawsSystemBarBackgrounds\">true</item>\n" +
                "        <item name=\"android:statusBarColor\">$c</item>\n" +
                "        <item name=\"android:navigationBarColor\">$c</item>\n" +
                "        <item name=\"android:windowLightStatusBar\">$dark</item>\n" +
                nav +
                "    </style>\n" +
                "</resources>\n"
        }

        File(out, "values").mkdirs()
        File(out, "values/skitter_bars.xml").writeText(style(false))
        File(out, "values-v27").mkdirs()
        File(out, "values-v27/skitter_bars.xml").writeText(style(true))
    }
}

androidComponents {
    onVariants { variant ->
        val task = tasks.register<GeneratedManifest>("${variant.name}GeneratedManifest") {
            lines.set(permissionLines + applicationLines)
        }

        variant.sources.manifests.addGeneratedManifestFile(task, GeneratedManifest::manifest)

        if (iconFile != null) {
            val icon = tasks.register<LauncherIcon>("${variant.name}LauncherIcon") {
                source.set(iconFile)
                background.set(iconBackgroundProp)
            }

            variant.sources.res?.addGeneratedSourceDirectory(icon, LauncherIcon::res)
        }

        if (barColorProp.isNotEmpty()) {
            val bars = tasks.register<BarTheme>("${variant.name}BarTheme") {
                colour.set(barColorProp)
                darkIcons.set(barIconsDark)
            }

            variant.sources.res?.addGeneratedSourceDirectory(bars, BarTheme::res)
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
