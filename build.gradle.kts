plugins {
    id("java")
}

group = "co.elastic"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.apache.commons:commons-text:1.14.0")
    implementation("co.elastic.clients:elasticsearch-java:9.4.0")

    val jacksonVersion = "2.18.3"
    // Apache 2.0
    // https://github.com/FasterXML/jackson
    implementation("com.fasterxml.jackson.core", "jackson-core", jacksonVersion)
    implementation("com.fasterxml.jackson.core", "jackson-databind", jacksonVersion)

    val roasterVersion = "2.22.2.Final"
    implementation("org.jboss.forge.roaster", "roaster-api", roasterVersion)
    implementation("org.jboss.forge.roaster", "roaster-jdt", roasterVersion)

    testImplementation("org.testcontainers", "testcontainers", "1.17.3")
    testImplementation("org.testcontainers", "elasticsearch", "1.17.3")
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

tasks.withType<Jar> {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes["Main-Class"] = "org.example.Main"
    }
    from(
        configurations.runtimeClasspath.get()
        .onEach { println("add from dependencies: ${it.name}") }
        .map { if (it.isDirectory) it else zipTree(it) })
}

tasks.test {
    useJUnitPlatform()
}
