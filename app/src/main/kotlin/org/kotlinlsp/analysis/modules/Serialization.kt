package org.kotlinlsp.analysis.modules

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.intellij.mock.MockProject
import com.intellij.openapi.project.Project
import org.jetbrains.kotlin.analysis.api.KaPlatformInterface
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreApplicationEnvironment
import org.jetbrains.kotlin.config.JvmTarget
import org.jetbrains.kotlin.config.LanguageVersion
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString

private data class SerializedModule(
    val id: String,
    val dependencies: List<String>,
    val javaVersion: String,
    // SourceModule
    val sourcePath: String? = null,
    val kotlinVersion: String? = null,
    // LibraryModule
    val isJdk: Boolean? = null,
    val libraryRoots: List<String>? = null
)

@OptIn(KaPlatformInterface::class)
fun serializeRootModule(rootModule: Module): String {
    val visited = LinkedHashMap<String, SerializedModule>()
    val stack = ArrayDeque<Module>()
    stack.add(rootModule)

    while (stack.isNotEmpty()) {
        val current = stack.removeLast()
        val id = current.id
        if(visited.containsKey(id)) continue

        visited[id] = when(current) {
            is SourceModule -> SerializedModule(
                id = id,
                sourcePath = current.folderPath,
                kotlinVersion = current.kotlinVersion.versionString,
                javaVersion = current.javaVersion.toString(),
                dependencies = current.dependencies.map { it.id }
            )
            is LibraryModule -> SerializedModule(
                id = id,
                libraryRoots = current.roots.map { it.absolutePathString() },
                isJdk = current.isJdk,
                javaVersion = current.javaVersion.toString(),
                dependencies = current.dependencies.map { it.id }
            )
            else -> throw Exception("Unsupported KaModule!")
        }
        stack.addAll(current.dependencies)
    }

    return GsonBuilder().setPrettyPrinting().create().toJson(visited.values)
}

fun deserializeRootModule(
    data: String,
    appEnvironment: KotlinCoreApplicationEnvironment,
    project: Project
): Module {
    val gson = Gson()
    val modules: List<SerializedModule> = gson.fromJson(data, Array<SerializedModule>::class.java).toList()

    val allIds = modules.map { it.id }.toSet()
    val dependencyIds = modules.flatMap { it.dependencies }.toSet()
    val rootIds = allIds - dependencyIds
    val rootId = rootIds.first()

    val moduleMap = modules.associateBy { it.id }
    val built = mutableMapOf<String, Module>()

    fun build(id: String): Module {
        if (built.containsKey(id)) return built[id]!!
        val serialized = moduleMap[id]!!
        val deps = serialized.dependencies.map { build(it) }
        val module = buildModule(serialized, deps, project, appEnvironment)
        built[id] = module
        return module
    }

    val rootModule = build(rootId)
    return rootModule
}

private fun buildModule(
    it: SerializedModule,
    deps: List<Module>,
    project: Project,
    appEnvironment: KotlinCoreApplicationEnvironment
): Module =
    if(it.sourcePath != null) {
        SourceModule(
            id = it.id,
            kotlinVersion = LanguageVersion.fromVersionString(it.kotlinVersion!!)!!,
            javaVersion = JvmTarget.fromString(it.javaVersion)!!,
            folderPath = it.sourcePath,
            dependencies = deps,
            project = project
        )
    } else {
        LibraryModule(
            id = it.id,
            javaVersion = JvmTarget.fromString(it.javaVersion)!!,
            isJdk = it.isJdk!!,
            roots = it.libraryRoots!!.map { Path(it) },
            dependencies = deps,
            project = project,
            appEnvironment = appEnvironment,
        )
    }
