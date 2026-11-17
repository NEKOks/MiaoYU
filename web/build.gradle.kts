import org.apache.tools.ant.taskdefs.condition.Os

plugins {
    id("rikkahub.android.library")
}

val webUiDir = rootProject.layout.projectDirectory.dir("web-ui")
val webStaticResourcesDir = layout.projectDirectory.dir("src/main/resources/static")

/**
 * 前端工具链是否就绪（有 pnpm，且已经 pnpm install 过）。
 *
 * 不满足时跳过 buildWebUi：内嵌 Web 页面会没有静态资源，但 APK 照常编译、其余功能不受影响。
 */
val webUiToolchainReady: Boolean = run {
    val pnpmPresent = (System.getenv("PATH") ?: "")
        .split(File.pathSeparator)
        .filter { it.isNotBlank() }
        .any { dir ->
            val names = if (Os.isFamily(Os.FAMILY_WINDOWS)) {
                listOf("pnpm.cmd", "pnpm.exe", "pnpm.bat")
            } else {
                listOf("pnpm")
            }
            names.any { File(dir, it).isFile }
        }
    pnpmPresent && webUiDir.dir("node_modules").asFile.exists()
}

val buildWebUi = tasks.register<Exec>("buildWebUi") {
    group = "build"
    description = "Build web-ui and copy its static output into the web module resources."

    workingDir = webUiDir.asFile
    when {
        Os.isFamily(Os.FAMILY_MAC) -> commandLine("zsh", "-ic", "pnpm run build")
        Os.isFamily(Os.FAMILY_WINDOWS) -> commandLine("cmd", "/c", "pnpm run build")
        else -> commandLine("pnpm", "run", "build")
    }

    onlyIf {
        if (!webUiToolchainReady) {
            logger.warn("web-ui toolchain not ready (pnpm or node_modules missing); skipping :web:buildWebUi")
        }
        webUiToolchainReady
    }

    inputs.files(        webUiDir.file("package.json"),
        webUiDir.file("pnpm-lock.yaml"),
        webUiDir.file("components.json"),
        webUiDir.file("copy.ts"),
        webUiDir.file("react-router.config.ts"),
        webUiDir.file("tsconfig.json"),
        webUiDir.file("vite.config.ts"),
        webUiDir.file("vite-env.d.ts")
    )
    inputs.dir(webUiDir.dir("app"))
    inputs.dir(webUiDir.dir("public"))
    outputs.dir(webStaticResourcesDir)
}

android {
    namespace = "me.rerere.rikkahub.web"

    defaultConfig {
        minSdk = 24
    }
}

tasks.named("preBuild") {
    dependsOn(buildWebUi)
}

dependencies {
    implementation(libs.androidx.core.ktx)

    // ktor server
    implementation(libs.ktor.server.default.headers)
    implementation(libs.ktor.server.conditional.headers)
    implementation(libs.ktor.server.compression)
    implementation(libs.ktor.server.cors)
    api(libs.ktor.server.auth)
    api(libs.ktor.server.core)
    implementation(libs.ktor.server.host.common)
    api(libs.ktor.server.content.negotiation)
    api(libs.ktor.server.status.pages)
    api(libs.ktor.server.sse)
    api(libs.ktor.server.cio)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
