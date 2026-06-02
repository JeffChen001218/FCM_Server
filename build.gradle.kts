plugins {
    kotlin("jvm") version "2.1.10"
    application
}

group = "org.example"
version = "1.0-SNAPSHOT"

val appMainClass = "org.example.MainKt"

application {
    mainClass.set(appMainClass)
}

tasks.jar.configure {
    manifest {
        attributes["Main-Class"] = appMainClass
    }
    from({
        configurations.runtimeClasspath.get()
            .filter { it.exists() }
            .map { file ->
                if (file.isDirectory) {
                    file
                } else {
                    zipTree(file)
                }
            }
    })
    exclude("META-INF/*.RSA", "META-INF/*.DSA", "META-INF/*.SF")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.google.firebase:firebase-admin:9.8.0")
    implementation("com.google.code.gson:gson:2.14.0")
    implementation("com.google.apis:google-api-services-androidpublisher:v3-rev20260528-2.0.0")
    implementation("com.google.api-client:google-api-client:2.9.0")
    implementation("com.google.auth:google-auth-library-oauth2-http:1.47.0")

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(20)
}
