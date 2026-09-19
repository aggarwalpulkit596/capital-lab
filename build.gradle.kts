plugins {
    kotlin("jvm") version "2.1.21"
    application
}

repositories { mavenCentral() }

dependencies {
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.postgresql:postgresql:42.7.8")
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
    testImplementation("org.testcontainers:testcontainers-postgresql:2.0.5")
}

kotlin { jvmToolchain(17) }
application { mainClass.set("capital.MainKt") }
tasks.test { useJUnitPlatform { excludeTags("postgres") } }

tasks.register<Test>("integrationTest") {
    description = "Runs real PostgreSQL reservation and HTTP bank recovery tests (Docker required)."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("postgres") }
    systemProperty("lab.test.classpath", sourceSets.test.get().runtimeClasspath.asPath)
    shouldRunAfter(tasks.test)
}

tasks.check { dependsOn("integrationTest") }

tasks.register<JavaExec>("paymentDemo") {
    description = "Runs a persistent PostgreSQL reservation and lost-bank-response demo."
    group = "application"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("capital.payments.PaymentDemoKt")
}

tasks.register<JavaExec>("dashboard") {
    description = "Serves the local interactive capital lab on http://127.0.0.1:8080 (Docker database required)."
    group = "application"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("capital.dashboard.DashboardServerKt")
}
