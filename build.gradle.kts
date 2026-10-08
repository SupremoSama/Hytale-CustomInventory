import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardOpenOption

plugins {
    java
}

group = "com.supremosan"
version = "0.1.0"

// Use an installed official server binary. No shared-source build or modification is needed.
val installedServer = providers.gradleProperty("hytaleServerJar")
    .orElse(providers.environmentVariable("HYTALE_SERVER_JAR"))
    .orElse(providers.environmentVariable("APPDATA").map {
        "$it/Hytale/install/pre-release/package/game/latest/Server/HytaleServer.jar"
    })
val engineFiles = files(installedServer)

allprojects {
    apply(plugin = "java")
    group = "com.supremosan"
    version = "0.1.0"
    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(25))
        withSourcesJar()
    }
    dependencies {
        add("compileOnly", engineFiles)
    }
    tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }
}

findProject(":example-extension")?.run {
    dependencies { add("compileOnly", rootProject) }
    tasks.named<Jar>("jar") { archiveBaseName.set("CustomInventoryExample") }
}

dependencies { testImplementation(engineFiles) }

val assetsZip = providers.gradleProperty("hytaleAssetsZip")
    .orElse(providers.environmentVariable("HYTALE_ASSETS_ZIP"))
    .orElse(installedServer.map { file(it).parentFile.parentFile.resolve("Assets.zip").absolutePath })
val serverRunDirectory = providers.gradleProperty("serverRunDir").orElse("run").map { file(it) }
val serverAuthMode = providers.gradleProperty("serverAuthMode").orElse("authenticated")
val serverBind = providers.gradleProperty("serverBind").orElse("127.0.0.1:5520")

val prepareRunServer = tasks.register("prepareRunServer") {
    group = "hytale"
    description = "Builds and installs CustomInventory into the development server."
    dependsOn(tasks.jar)
    doLast {
        check(file(installedServer.get()).isFile) { "Server JAR not found. Set -PhytaleServerJar=<path>." }
        check(file(assetsZip.get()).isFile) { "Assets.zip not found. Set -PhytaleAssetsZip=<path>." }
        val runDirectory = serverRunDirectory.get()
        copy {
            from(tasks.jar.get().archiveFile)
            into(runDirectory.resolve("mods"))
            rename { "CustomInventory-dev.jar" }
        }
        // Avoid the installed server's first-run permission-writer failure. Preserve existing files.
        val permissions = runDirectory.resolve("permissions.json").toPath()
        if (!Files.exists(permissions)) {
            Files.writeString(permissions, "{\"users\":{},\"groups\":{}}\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)
        }
    }
}

tasks.register<JavaExec>("runServer") {
    group = "hytale"
    description = "Runs the local Hytale server with CustomInventory and an interactive console."
    dependsOn(prepareRunServer)
    mainClass.set("com.hypixel.hytale.Main")
    classpath = engineFiles
    javaLauncher.set(javaToolchains.launcherFor(java.toolchain))
    workingDir(serverRunDirectory)
    standardInput = System.`in`
    maxHeapSize = "4G"
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    providers.gradleProperty("inventoryGestureTrace").orNull?.let {
        systemProperty("custominventory.traceGestures", it)
    }
    argumentProviders.add(org.gradle.process.CommandLineArgumentProvider {
        val arguments = mutableListOf("--assets", file(assetsZip.get()).absolutePath,
            "--allow-op", "--disable-sentry", "--auth-mode", serverAuthMode.get(),
            "--bind", serverBind.get())
        providers.gradleProperty("serverBootCommand").orNull?.let {
            arguments.addAll(listOf("--boot-command", it))
        }
        arguments
    })
}
