// Skitter's activity, written in Scala.
//
// **This is a second build system, and that is the whole cost of choosing Scala here.** The Android
// Gradle plugin compiles Java and Kotlin itself and has no Scala support, so the class has to be
// produced outside it and handed over as a jar. `app/build.gradle.kts` runs this build and puts the
// jar on the application's classpath; `./gradlew assembleDebug` is still the one command.
//
// **Nothing in this file is named for your application.** It builds Skitter's activity, which is the
// same class in every Skitter project — so `skitter-activity` is a constant that
// `app/build.gradle.kts` can predict rather than a name to keep in step with anything.

ThisBuild / scalaVersion := "3.8.2"

lazy val root = (project in file("."))
  .settings(
    name := "skitter-activity",

    // A jar named for what it is rather than for a Scala version, since the thing consuming it is
    // Gradle and knows nothing about either.
    crossPaths := false,

    // `android.jar` and SDL's `classes.jar`, handed over by Gradle — which already knows where both
    // are and would otherwise have to be guessed at from `ANDROID_HOME` and a glob over the
    // installed platforms.
    Compile / unmanagedJars ++= sys.env
      .getOrElse("SKITTER_CLASSPATH", "")
      .split(java.io.File.pathSeparator)
      .filter(_.nonEmpty)
      .map(p => Attributed.blank(file(p)))
      .toSeq,
  )
