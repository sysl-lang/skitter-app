package sh.sysl.skitter

import android.content.Intent
import android.content.pm.PackageManager
import android.os.{Build, Bundle, VibrationEffect, Vibrator, VibratorManager}
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
  * '''Two things are here: the system bars, and the requests a program makes of the phone.'''
  * The requests — vibrate, a tick, share some text, is a permission granted — arrive from
  * `sh.sysl.skitter` through `SDL_SendAndroidMessage`, which SDL delivers to `onUnhandledMessage` on
  * the UI thread; a string rides beside the number as an SDL hint, read back with `nativeGetHint`.
  * Neither direction needs a line of JNI on the sysl side beyond the two exported methods below.
  *
  * '''The system bars.''' `SDL_GetWindowSafeArea` answers with Android's
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

  /** The answer to `permission_granted`, defined in sysl by `phone.sysl` under the same naming rule
    * as the one above: `token * 2`, plus one when the permission is granted. The token is the one
    * the question arrived with, so an answer that comes back after the sysl side has stopped
    * waiting is recognised as stale rather than read as the answer to the next question.
    */
  @native def nativePermissionAnswer(answer: Int): Unit

  override def onCreate(savedInstanceState: Bundle): Unit =
    super.onCreate(savedInstanceState)

    // **`skitter.keepAwake`, applied before SDL's thread starts.** SDL suspends the screensaver when
    // video comes up — on Android that is `FLAG_KEEP_SCREEN_ON` — unless `SDL_VIDEO_ALLOW_SCREENSAVER`
    // says otherwise when it does. An environment variable is a hint's default, and `nativeSetenv` is
    // SDL's own way of setting one from this side; it is how SDL applies a manifest's `SDL_ENV.`
    // entries too. A program that changes its mind later calls `keep_awake`, which is
    // `SDL_DisableScreenSaver` and `SDL_EnableScreenSaver`.
    //
    // A missing resource is `false`: an APK built before the property existed lets the screen sleep.
    if !SDLActivity.mBrokenLibraries then
      val id = getResources.getIdentifier("skitter_keep_awake", "bool", getPackageName)
      val keepAwake = id != 0 && getResources.getBoolean(id)

      SDLActivity.nativeSetenv("SDL_VIDEO_ALLOW_SCREENSAVER", if keepAwake then "0" else "1")

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

  /** Every request `sh.sysl.skitter` makes, on the UI thread.
    *
    * '''The numbers are `phone.sysl`'s''' and start at SDL's `COMMAND_USER`, `0x8000`, below which
    * every command is SDL's own and never reaches here. Anything this does not know goes to SDL's
    * handler, which logs it — a program on a newer Skitter than its activity says so in the log
    * rather than doing nothing silently.
    */
  override def onUnhandledMessage(command: Int, param: AnyRef): Boolean =
    val n = param match
      case i: Integer => i.intValue
      case _ => 0

    command match
      case SkitterActivity.Vibrate => vibrate(n); true
      case SkitterActivity.Tick => tick(); true
      case SkitterActivity.Share => share(); true
      case SkitterActivity.CheckPermission => answerPermission(n); true
      case _ => super.onUnhandledMessage(command, param)

  private def vibrator: Option[Vibrator] =
    val v =
      if Build.VERSION.SDK_INT >= Build.VERSION_CODES.S then
        getSystemService(classOf[VibratorManager]).getDefaultVibrator
      else getSystemService(classOf[Vibrator])

    Option(v).filter(_.hasVibrator)

  private def vibrate(ms: Int): Unit =
    if ms > 0 then
      vibrator.foreach(_.vibrate(VibrationEffect.createOneShot(ms.toLong, VibrationEffect.DEFAULT_AMPLITUDE)))

  // **The system's own tick where there is one**, which is tuned to the motor and is what a keyboard
  // or a picker uses; a very short pulse before Android 10, which is the nearest thing.
  private def tick(): Unit =
    vibrator.foreach: v =>
      if Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q then
        v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
      else v.vibrate(VibrationEffect.createOneShot(10, VibrationEffect.DEFAULT_AMPLITUDE))

  private def share(): Unit =
    val text = SDLActivity.nativeGetHint(SkitterActivity.ShareTextHint)

    if text != null then
      val send = new Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, text)

      startActivity(Intent.createChooser(send, null))

  /** `BLUETOOTH_CONNECT` did not exist before Android 12, where `BLUETOOTH` was granted at install —
    * which is why `skitter.permissions`' `bluetooth` asks for both. Asked of an older phone, the one
    * that exists is the answer.
    */
  private def granted(permission: String): Boolean =
    val asked =
      if permission == "android.permission.BLUETOOTH_CONNECT" && Build.VERSION.SDK_INT < Build.VERSION_CODES.S
      then "android.permission.BLUETOOTH"
      else permission

    checkSelfPermission(asked) == PackageManager.PERMISSION_GRANTED

  private def answerPermission(token: Int): Unit =
    val permission = SDLActivity.nativeGetHint(SkitterActivity.PermissionHint)
    val yes = permission != null && granted(permission)

    nativePermissionAnswer(token * 2 + (if yes then 1 else 0))

object SkitterActivity:
  // `phone.sysl`'s command numbers and hint names. The two files are Skitter's, and change together.
  final val Vibrate = 0x8001
  final val Tick = 0x8002
  final val Share = 0x8003
  final val CheckPermission = 0x8004

  final val ShareTextHint = "SKITTER_SHARE_TEXT"
  final val PermissionHint = "SKITTER_PERMISSION"
