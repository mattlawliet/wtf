import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
	id("net.fabricmc.fabric-loom")
	`maven-publish`
	id("org.jetbrains.kotlin.jvm") version "2.4.0"
}

version = providers.gradleProperty("mod_version").get()
group = providers.gradleProperty("maven_group").get()

repositories {
	maven {
		name = "Modrinth"
		url = uri("https://api.modrinth.com/maven")
		content {
			includeGroup("maven.modrinth")
		}
	}
}

loom {
	splitEnvironmentSourceSets()

	mods {
		register("wtf") {
			sourceSet(sourceSets.main.get())
			sourceSet(sourceSets.getByName("client"))
		}
	}
}

// ShulkerIdentityResolver is pure (maps in, uuid out), so its tests run on a
// plain JVM - no Minecraft bootstrap. Wire the client source set onto the test
// classpath so they can see it.
sourceSets {
	test {
		compileClasspath += sourceSets["client"].output + sourceSets["client"].compileClasspath
		runtimeClasspath += sourceSets["client"].output + sourceSets["client"].runtimeClasspath
	}
}

tasks.test {
	useJUnitPlatform()
	testLogging { events("failed") }
}

dependencies {
	// To change the versions see the gradle.properties file
	minecraft("com.mojang:minecraft:${providers.gradleProperty("minecraft_version").get()}")
	implementation("net.fabricmc:fabric-loader:${providers.gradleProperty("loader_version").get()}")

	// Fabric API. This is technically optional, but you probably want it anyway.
	implementation("net.fabricmc.fabric-api:fabric-api:${providers.gradleProperty("fabric_api_version").get()}")
	implementation("net.fabricmc:fabric-language-kotlin:${providers.gradleProperty("fabric_kotlin_version").get()}")

	testImplementation(kotlin("test"))

	// Sodium has no 26.2 build yet; re-enable when one ships.
	// modRuntimeOnly("maven.modrinth:sodium:mc26.2-fabric")
}

tasks.processResources {
	val version = version
	inputs.property("version", version)

	filesMatching("fabric.mod.json") {
		expand("version" to version)
	}
}

tasks.withType<JavaCompile>().configureEach {
	options.release = 25
}

kotlin {
	compilerOptions {
		jvmTarget = JvmTarget.JVM_25
	}
}

java {
	// Loom will automatically attach sourcesJar to a RemapSourcesJar task and to the "build" task
	// if it is present.
	// If you remove this line, sources will not be generated.
	withSourcesJar()

	sourceCompatibility = JavaVersion.VERSION_25
	targetCompatibility = JavaVersion.VERSION_25
}

tasks.jar {
	val projectName = project.name
	inputs.property("projectName", projectName)

	from("LICENSE") {
		rename { "${it}_$projectName" }
	}
}

// Debug variant: the shipped jar plus a /wtf_debug.flag marker resource, which
// WTFClient looks up on the classpath to default debugMode = true (see
// WTFClient.debugMode). Produces "<name>-<version>_debug.jar" next to the
// normal jar.
val debugMarkerDir = layout.buildDirectory.dir("wtf-debug-marker")

val generateDebugMarker = tasks.register("generateDebugMarker") {
	outputs.dir(debugMarkerDir)
	doLast {
		val dir = debugMarkerDir.get().asFile
		dir.mkdirs()
		File(dir, "wtf_debug.flag").writeText("1")
	}
}

val debugJar = tasks.register<Jar>("debugJar") {
	group = "build"
	dependsOn("jar", generateDebugMarker)
	from(zipTree({ tasks.named("jar").get().outputs.files.singleFile }))
	from(debugMarkerDir)
	archiveFileName.set("${project.name}-${version}_debug.jar")
	destinationDirectory.set(layout.buildDirectory.dir("libs"))
}

tasks.named("build") {
	dependsOn(debugJar)
}

// configure the maven publication
publishing {
	publications {
		register<MavenPublication>("mavenJava") {
			from(components["java"])
		}
	}

	// See https://docs.gradle.org/current/userguide/publishing_maven.html for information on how to set up publishing.
	repositories {
		// Add repositories to publish to here.
		// Notice: This block does NOT have the same function as the block in the top level.
		// The repositories here will be used for publishing your artifact, not for
		// retrieving dependencies.
	}
}
