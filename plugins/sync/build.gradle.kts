plugins {
    alias(libs.plugins.kotlin.jvm)
}


project.version = "0.0.6"


dependencies {
    testImplementation(kotlin("test"))
    testImplementation(project(":"))
    compileOnly(project(":"))
}

apply(from = "$rootDir/plugins/common.gradle.kts")
