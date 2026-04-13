plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.10.1"
}

group = "com.mobeetest"
version = "0.3.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        local(
            providers.gradleProperty("idePath").orNull
                ?: error("Set idePath in gradle.properties or pass -PidePath=/path/to/Android Studio")
        )
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}
