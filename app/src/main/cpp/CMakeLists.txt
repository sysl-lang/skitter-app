cmake_minimum_required(VERSION 3.22)

# **Nothing in this file is named for your application, and that is deliberate.** The archive, the
# CMake project and the program directory are all constants, so there is nothing here to rename when
# you start a project — your two names are in `gradle.properties` and are read by
# `app/build.gradle.kts`. Leave this file alone unless you are adding a second SDL library.
project(skitter_app C)

# **Gradle owns the link here, so the arrangement is upside down from an ordinary sysl build.**
# picokit's README puts it exactly: "The pico-sdk owns the link … So the arrangement is the other way
# up: `sysl build-c` compiles this program into an archive, and CMake links it." Android is the same
# shape with a different owner — `sysl build-c` writes an archive, this file links it into the
# `libmain.so` that the APK carries and `SDLActivity` loads.

# SDL3, out of the AAR that `./fetch-sdl3.sh` downloads and `prefab` unpacks. `REQUIRED` here means
# the build stops with a sentence rather than at an undefined symbol three minutes later, and a
# version change is one line in `fetch-sdl3.sh` and nothing else.
find_package(SDL3 REQUIRED CONFIG)

# **The one thing CMake has to be told, because getting it wrong is not a link error.** `sysl` is a
# program CMake knows nothing about, so the target it compiles for is stated here rather than
# inferred. Only `arm64-v8a` is built (see `app/build.gradle.kts`), so there is one row to name.
set(SYSL_TARGET aarch64-android)

set(SYSL_ARCHIVE ${CMAKE_CURRENT_BINARY_DIR}/libskitterapp.a)

# Your program. A fixed directory, so that this file never has to change: `program/` beside the
# Gradle build, holding `main.sysl` and `package.hocon`.
set(SYSL_PROGRAM ${CMAKE_CURRENT_SOURCE_DIR}/../../../../program)

# **Skitter and SDL3 are coordinates, so there is nothing to point at.** `program/package.hocon`
# names Skitter and `sysl build-c` fetches it, along with SDL3, which it brings with it because
# imports are transitive as of sysl 0.0.73. There is no `--lib` and no `file(GLOB)` of somebody
# else's sources, which is what a version buys: a build cannot be stale against a coordinate.

# Where SDL's headers and its `.so` actually are, asked of the imported targets rather than written
# down. `sh.sysl.sdl3` reads SDL's own headers for its constants instead of transcribing them, so the
# include path is not optional even though nothing here is C.
#
# **`SDL3::SDL3` is an alias and has neither property.** Asking it for `INTERFACE_INCLUDE_DIRECTORIES`
# answers empty, which is not an error — it is an empty string that goes on to become a corrupt
# command line below. The real targets are `SDL3::SDL3-Headers` and `SDL3::SDL3-shared`.
get_target_property(SDL3_INCLUDE_DIR SDL3::SDL3-Headers INTERFACE_INCLUDE_DIRECTORIES)
get_target_property(SDL3_SO SDL3::SDL3-shared IMPORTED_LOCATION)

# **An empty one of these is not a warning, it is a corrupt command line.** `--link-path` with no
# value takes the next word as its directory, so a blank here turns `-o` into a path and sysl reports
# a missing argument somewhere else entirely.
if(NOT SDL3_INCLUDE_DIR)
    message(FATAL_ERROR "SDL3::SDL3-Headers has no include directory — did prefab unpack the AAR? Run ./fetch-sdl3.sh")
endif()

if(NOT SDL3_SO)
    message(FATAL_ERROR "SDL3::SDL3-shared has no IMPORTED_LOCATION — did prefab unpack the AAR? Run ./fetch-sdl3.sh")
endif()

get_filename_component(SDL3_LIB_DIR ${SDL3_SO} DIRECTORY)

# **`--include-path sdl3=<dir>`, named rather than bare.** The package declares
# `requires { pkg_config { sdl3 = … } }`, and a cross build cannot run pkg-config for another
# machine. A bare `--include-path` adds a search path without answering the requirement, and the
# build stops with the same message it would have without it.
#
# **`ANDROID_NDK_ROOT` is handed over rather than left to be found.** sysl takes the newest NDK it can
# see, and AGP uses the `ndkVersion` pinned in `app/build.gradle.kts` — and a machine normally has
# two, because AGP downloads its own. Passing the one CMake is already using makes them the same by
# construction rather than by coincidence.
add_custom_command(
    OUTPUT ${SYSL_ARCHIVE}
    COMMAND ${CMAKE_COMMAND} -E env ANDROID_NDK_ROOT=${ANDROID_NDK}
            sysl build-c ${SYSL_PROGRAM}
            --target ${SYSL_TARGET}
            --include-path sdl3=${SDL3_INCLUDE_DIR}
            --link-path ${SDL3_LIB_DIR}
            -o ${SYSL_ARCHIVE}
    DEPENDS ${SYSL_PROGRAM}/main.sysl ${SYSL_PROGRAM}/package.hocon
    COMMENT "Compiling your sysl program for ${SYSL_TARGET}"
    VERBATIM)

add_custom_target(sysl_archive DEPENDS ${SYSL_ARCHIVE})

# **The library must be called `main`.** `SDLActivity.getMainSharedObject()` defaults to `libmain.so`
# and `getMainFunction()` to `SDL_main`, and matching both is what keeps the Java half and the native
# half agreeing with nothing configured in between.
#
# One empty translation unit, because CMake will not create a library out of nothing at all and every
# line of this program is sysl.
file(WRITE ${CMAKE_CURRENT_BINARY_DIR}/empty.c "")
add_library(main SHARED ${CMAKE_CURRENT_BINARY_DIR}/empty.c)
add_dependencies(main sysl_archive)

# **`-u SDL_main`, and without it the library builds, links, contains none of the program and dies at
# `dlsym`.** An archive member is pulled in only to resolve a symbol something already needs, and
# nothing in the `.so` calls `SDL_main` — Java looks it up by name at run time, long after the link.
# `-u` says the symbol is needed anyway.
#
# **It is also what carries Skitter's JNI bridge in**, which is worth knowing before adding a second
# `-u` for it. Every sysl module across every dependency compiles into one object in the archive, so
# the member holding `SDL_main` is the member holding `nativeSetSystemBars` too, and pulling in
# either pulls in both.
target_link_options(main PRIVATE -u SDL_main)

target_link_libraries(main PRIVATE ${SYSL_ARCHIVE} SDL3::SDL3 m)
