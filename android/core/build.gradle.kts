plugins { kotlin("jvm"); kotlin("plugin.serialization") }
kotlin { jvmToolchain(17); sourceSets.main { kotlin.srcDir(layout.buildDirectory.dir("portable")) } }
val portable by tasks.registering(Exec::class) {
    inputs.dir("../../src/main/kotlin/app/termora")
    inputs.files("../../src/main/kotlin/app/termora/Host.kt", "../scripts/prepare-core.py")
    outputs.dir(layout.buildDirectory.dir("portable"))
    commandLine("python3", "../scripts/prepare-core.py", layout.buildDirectory.dir("portable").get().asFile)
}
tasks.named("compileKotlin") { dependsOn(portable) }
dependencies {
    api(kotlin("reflect"))
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
    api("org.apache.commons:commons-lang3:3.20.0")
    api("commons-codec:commons-codec:1.20.0")
    api("org.slf4j:slf4j-api:2.0.17")
    api("com.github.mwiede:jsch:2.27.3")
    api("org.bouncycastle:bcprov-jdk18on:1.83")
    api("org.bouncycastle:bcpkix-jdk18on:1.83")
    api("com.squareup.okhttp3:okhttp:4.12.0")
    api("commons-net:commons-net:3.12.0")
    api("com.hierynomus:smbj:0.14.0")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testImplementation("org.apache.sshd:sshd-core:2.16.0")
    testImplementation("org.apache.sshd:sshd-sftp:2.16.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
    testImplementation("com.qcloud:cos_api:5.6.260.1")
}
tasks.test { useJUnitPlatform() }
tasks.register<JavaExec>("runProtocolFixture") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("app.termora.android.core.ProtocolFixture")
    args(providers.gradleProperty("fixtureInfo").orNull ?: "/tmp/termora-device-fixture.json")
}

tasks.register("writeFixtureClasspath") {
    dependsOn(tasks.testClasses)
    doLast { layout.buildDirectory.file("fixture-classpath.txt").get().asFile.writeText(sourceSets.test.get().runtimeClasspath.asPath) }
}
