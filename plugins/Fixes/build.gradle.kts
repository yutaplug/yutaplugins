import org.gradle.kotlin.dsl.support.serviceOf

version = "1.1.4"
description = "Fixes essential features of Discord 126.21. Each fix can be turned on or off in the plugin settings."

android {
    namespace = "com.github.yutaplug.fixes"
}

aliucord {
    author("TsyQax", 0L, hyperlink = false)
    author("secp192k1", 0L, hyperlink = false)
    author("zt64", 0L, hyperlink = false)
    author("pilotbellyt-spec", 0L, hyperlink = false)
    author("js6pak", 0L, hyperlink = false)
    changelog.set(
        """
        # 1.1.4
        * GitHub updater: new "Ask to restart after updating" setting. Choose a dialog, a bar at the bottom of the screen like Aliucord's own updater, or no prompt at all

        # 1.1.3
        * GitHub updater: new "Show a popup after updating" setting, to turn off the "Successfully updated" popup shown after restarting

        # 1.1.2
        * Leaderboard messages: new fix that shows the server gaming leaderboard's messages (a new week, a new 1st place, the weekly winner) instead of "Original message was deleted"
        * Darker images in search: new fix for images and videos in search results looking darker when searching in DMs

        # 1.1.1
        * Delete server: new fix for deleting a server failing with "You are unauthorized to perform this request" when you have 2FA

        # 1.1.0
        * Videos loading forever: new fix for videos that never start playing (often tall, high-resolution screen recordings). When the phone's first video decoder can't play a video, the others are tried, like on newer Discord
        * Voice and video calls: soundboard. Sounds played by others in your voice channel are heard, and the soundboard button in the call controls opens a picker of sounds to play, with search and a preview button on each sound: Discord's default sounds, the server's own, and every other server's (marked, since those need Nitro)
        * Voice and video calls: voice channel statuses are shown in the channel list and under the channel name in calls, with custom emojis. Tap the channel name at the top of the call screen to set yours; the emoji button adds emojis
        * Yourself in voice channels: new fix that shows you in a voice channel's member list when you're in it on another device, like newer Discord
        * GitHub updater: changelogs show the date each version was released
        * Markdown: ```diff code blocks are highlighted like on desktop, with added lines in green and removed lines in red

        # 1.0.14
        * Voice and video calls: new fix that makes voice calls, server voice channels, camera video and screen sharing work again, with the end-to-end encryption (DAVE) Discord now requires. Type /davevoice in a call to see its status

        # 1.0.13
        * GitHub updater: new "Update from the popup" option. Turn it off and tapping the updates popup opens the Updater, so you can read the changelogs and choose what to install

        # 1.0.12
        * Attachment previews: new fix for the preview of a file you add to a message (like a .zip) sometimes showing an image you added before

        # 1.0.11
        * New badges: turned off for now, since it could log you out of Discord. It will come back once it's safe
        * GitHub updater: update messages are now a popup at the top of the app instead of a bar at the bottom, so they don't cover the chat or the navigation bar. Tap it to update, or swipe it away
        * GitHub updater: new options to turn the popups off, or to also get a phone notification
        * GitHub updater: ignore plugins you don't want updates for, from their update or under Ignored
        * GitHub updater: checks again if GitHub couldn't be reached right after starting

        # 1.0.10
        * GitHub updater: no longer replaces a plugin you installed by hand before restarting with an older version from GitHub. It now shows that a restart is needed instead
        * GitHub updater: plugin updates show when the new version was released
        * GitHub updater: tap a plugin under Up to date to see its changelog

        # 1.0.9
        * Screen refresh: now on by default. It also stops the second reload Discord started right after the fix put your language back, which is why it didn't work before

        # 1.0.8
        * GitHub updater: replaces the Raw GitHub updater, and Aliucord's own updater for both plugins and Aliucord itself. Updates come straight from GitHub at the latest commit, so new versions show up right away, and each download is checked before it replaces the old version, which fixes plugins asking to restart over and over
        * GitHub updater: a new Updater screen, a message with an Update button when updates are found at startup, a restart prompt after updating, and a message saying what was updated after the restart, with its changelog
        * GitHub updater: Aliucord's changelog now shows what changed, from the Aliucord documentation site

        # 1.0.7
        * New badges: new fix that shows newer Discord badges on profiles, like Account Age, which Discord didn't send to Aliucord
        * Paste images: new fix that lets you paste images from the clipboard into the chat box as attachments
        * Screen refresh: off by default for now, since it doesn't work for everyone yet. If you were asked to, you can turn it on and save a log from the plugin settings

        # 1.0.6
        * Screen refresh: new fix for the app reloading and jumping back to the start of the chat or settings after you close an image, a video or a settings page, on some phones
        * Message links: links to a channel, thread or forum post now open in Aliucord instead of the browser
        * Message links: jumping to a message in a forum post or thread that isn't loaded yet, from search, a link or a reminder, now opens it instead of the server's default channel

        # 1.0.5
        * Slow uploads and downloads: new fix for uploads and media loading very slowly on networks without IPv6, where the app first waited for IPv6 connections to time out

        # 1.0.4
        * Markdown: rewritten from scratch
        * Markdown: code blocks no longer lose their background, also after an edit, and fit inside quotes and lists. One-line ```code``` shows as a box too
        * Markdown: numbered lists now render after a line of text
        * Markdown: each heading can have its own color, set in Markdown appearance
        * Markdown: reply previews show a heading's text instead of a big heading
        * Markdown: the settings explain what each part of the fix does

        # 1.0.3
        * Added Plugin menus: menus and popups from plugins no longer stop opening after you open and close another screen, like settings or an image
        * Added Message menu in search: holding a message in search opens its menu instead of jumping to it
        * Added Server long press: the menu from holding a server or folder no longer closes by itself when you let go
        * Markdown: the reset dialog in the settings no longer stops opening after you open and close another screen, like settings or an image

        # 1.0.2
        * Markdown: numbered lists (1. 2. 3.) now render, and dashes after a numbered item continue its numbering like on current Discord

        # 1.0.1
        * Added Message width (from zt64's MessageWidthFix): embeds and stickers use the full chat width
        * Added Server settings (from pilotbellyt-spec's ServerSettingsFix): the bans list and audit log load again, and invite durations read properly
        * Added Mentioned users (from js6pak's ValidUser): mentions of users that show as invalid-user are fetched
        * Added GIF sticker animation: GIF stickers follow the sticker animation setting
        * Heading 3 is now normal text size by default, like current Discord
        * MessageWidthFix, ServerSettingsFix and ValidUser are uninstalled automatically, since Fixes replaces them

        # 1.0.0
        * Initial release
        """.trimIndent(),
    )
}

// The voice fix (voice/) uses BouncyCastle's MLS implementation (MIT) for DAVE's group key exchange. It's bundled
// into the plugin, shrunk by R8 to what the plugin reaches (no obfuscation or optimisation: bcmls decodes messages
// through reflection, and the plugin's own classes are kept exactly as compiled).
val bundled: Configuration by configurations.creating

dependencies {
    val bcmls = "org.bouncycastle:bcmls-jdk18on:1.87"
    compileOnly(bcmls)
    bundled(bcmls)
}

// bcmls' LifeTime uses java.time (Android 8+ only). Fixes ships a Kotlin replacement of that one class
// (src/main/kotlin/org/bouncycastle/mls/TreeKEM/LifeTime.kt), so BouncyCastle's own copy is left out of the bundle.
val bcJar by tasks.registering(Jar::class) {
    // locals only: the lambda must not capture the script (configuration cache)
    val archives = project.serviceOf<ArchiveOperations>()
    val jars = bundled.elements
    from(jars.map { files -> files.map { archives.zipTree(it.asFile) } }) {
        exclude("META-INF/**", "**/module-info.class", "org/bouncycastle/mls/TreeKEM/LifeTime.class")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    destinationDirectory.set(layout.buildDirectory.dir("intermediates/bundle"))
    archiveFileName.set("bouncycastle.jar")
}

val pluginClassesJar by tasks.registering(Jar::class) {
    dependsOn("compileDebugKotlin")
    from(layout.buildDirectory.dir("tmp/kotlin-classes/debug"))
    destinationDirectory.set(layout.buildDirectory.dir("intermediates/bundle"))
    archiveFileName.set("plugin-classes.jar")
}

val bundleDir = layout.buildDirectory.dir("intermediates/bundle/classes")

val shrinkBundle by tasks.registering(JavaExec::class) {
    dependsOn(bcJar, pluginClassesJar)
    val sdkDir = android.sdkDirectory
    val rules = file("bundle-rules.pro")
    val d8 = File(sdkDir, "build-tools").listFiles { f -> File(f, "lib/d8.jar").exists() }
        ?.maxByOrNull { it.name }
        ?.let { File(it, "lib/d8.jar") }
        ?: error("No Android build-tools with lib/d8.jar in $sdkDir")
    val androidJar = File(sdkDir, "platforms").listFiles()!!
        .map { File(it, "android.jar") }
        .filter { it.exists() }
        .minByOrNull { it.path }!!
    // Only the Kotlin stdlib as library classes: Discord's and Aliucord's jars overlap, which R8 refuses. What stays
    // unresolved is Discord/Aliucord code that exists at runtime (bundle-rules.pro ignores those warnings).
    val compileJars = configurations.getByName("debugCompileClasspath").incoming.artifactView {
        attributes { attribute(Attribute.of("artifactType", String::class.java), "jar") }
        lenient(true)
    }.files.filter { it.name.startsWith("kotlin-stdlib") }

    // plain values only below: lambdas here must not capture script objects (configuration cache)
    val outDir = bundleDir.get().asFile
    val bcFile = bcJar.get().archiveFile.get().asFile
    val classesFile = pluginClassesJar.get().archiveFile.get().asFile
    val classpathJars = compileJars.files.map { it.path }
    val rulesPath = rules.path
    val androidJarPath = androidJar.path

    inputs.file(rules)
    inputs.files(bcFile, classesFile)
    outputs.dir(outDir)

    classpath = files(d8)
    mainClass.set("com.android.tools.r8.R8")
    doFirst {
        outDir.deleteRecursively()
        outDir.mkdirs()
    }
    args(
        listOf("--classfile", "--output", outDir.path, "--pg-conf", rulesPath, "--lib", androidJarPath) +
            classpathJars.flatMap { listOf("--classpath", it) } +
            listOf(classesFile.path, bcFile.path),
    )
}

afterEvaluate {
    tasks.named("compileDex") {
        dependsOn(shrinkBundle)
        (this as com.aliucord.gradle.task.CompileDexTask).input.setFrom(bundleDir)
    }
}
