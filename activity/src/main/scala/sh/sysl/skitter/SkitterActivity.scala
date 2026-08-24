package sh.sysl.skitter

import android.os.{Build, Bundle}
import android.util.Log
import android.view.{View, WindowInsets}
import org.libsdl.app.SDLActivity

/** Skitter's activity: the Java-side half of an application, and the whole of it.
  *
  * '''You do not subclass this and you do not rename it.''' `AndroidManifest.xml` names it in full,
  * so an application's own `applicationId` can be any string it likes — which is the point of
  * fixing the class here rather than leaving one to be copied into every project. Before Skitter,
  * this file was duplicated per application and renamed to match, and keeping four spellings of one
  * name in step by hand is exactly the sort of thing that goes wrong quietly.
  *
  * '''The system bars are the only code here.''' `SDL_GetWindowSafeArea` answers with Android's
  * insets combined — `systemBars`, `systemGestures`, `mandatorySystemGestures`, `tappableElement`
  * and `displayCutout`, all at once — because it answers ''where can a button go''. For a drawing
  * that is far too conservative: on a gesture-navigation phone the back-gesture strips take 78
  * pixels off each side and the mandatory bottom gesture reaches above the navigation bar, none of
  * which is obscured or untouchable for something only being looked at.
  *
  * SDL exposes the combined rectangle and no way to ask for one kind, so a program that wants the
  * region ''between the bars'' has to read the insets on this side and hand them over. That is what
  * `sh.sysl.skitter.insets` on the sysl side receives.
  *
  * '''Two defaults are taken rather than overridden.''' `getMainSharedObject()` answers `libmain.so`
  * and `getMainFunction()` answers `SDL_main`, which is why `CMakeLists.txt` calls the library
  * `main` and why your `main.sysl` exports that symbol.
  */
class SkitterActivity extends SDLActivity:

  /** Defined in sysl, not here — `sh.sysl.skitter`'s `insets.sysl` exports it.
    *
    * JNI binds a native method by mangling the package and class into
    * `Java_sh_sysl_skitter_SkitterActivity_nativeSetSystemBars`, and that string is what the sysl
    * side `@export`s. '''Renaming this method, this class or this package renames the symbol''' —
    * the link still succeeds, because JNI resolves at run time, and the failure is an
    * `UnsatisfiedLinkError` the first time the insets change. Since both halves are Skitter's, they
    * are renamed together or not at all.
    *
    * '''It must not be `private`, and that is a Scala rule rather than a JNI one.''' A private
    * method reached from an inner class is renamed by the compiler to
    * `sh$sysl$skitter$SkitterActivity$$nativeSetSystemBars` so the inner class can see it — and JNI
    * then looks for a symbol with `_00024` in it that nothing defines. It compiles, links and dies
    * at the first call. The listener below is an inner class, so this is exactly that case.
    */
  @native def nativeSetSystemBars(left: Int, top: Int, right: Int, bottom: Int): Unit

  override def onCreate(savedInstanceState: Bundle): Unit =
    super.onCreate(savedInstanceState)

    // Listening on the decor view rather than on SDL's surface, which already has a listener of its
    // own — `SDLSurface` implements `OnApplyWindowInsetsListener` and is what feeds SDL's own safe
    // area. Taking that one over would break it.
    //
    // The insets are returned unconsumed, so everything below this in the hierarchy still sees them.
    getWindow.getDecorView.setOnApplyWindowInsetsListener(
      new View.OnApplyWindowInsetsListener:
        def onApplyWindowInsets(v: View, insets: WindowInsets): WindowInsets =
          if Build.VERSION.SDK_INT >= Build.VERSION_CODES.R then
            // `systemBars()` alone — the status bar and the navigation bar, and none of the gesture
            // regions. That is the whole difference between this and SDL's safe area.
            val bars = insets.getInsets(WindowInsets.Type.systemBars())

            nativeSetSystemBars(bars.left, bars.top, bars.right, bars.bottom)

            // **Ordinary Scala, and it is here on purpose.** A `List`, a zip, a `map` and an
            // interpolated string all come from the standard library, so this line is what would
            // fail to resolve if `scala3-library` were not a dependency of the application — which
            // makes it a check on the build as much as a log message. It is also why `minSdk` is 26:
            // `d8` will not desugar `scala-library` below that.
            //
            // Rare enough to be free: Android reports insets at startup and on rotation, not
            // per frame.
            val named = List("left", "top", "right", "bottom")
              .zip(List(bars.left, bars.top, bars.right, bars.bottom))
              .map((side, px) => s"$side=$px")
              .mkString(" ")

            Log.i("skitter", s"system bars: $named")

          insets
    )
