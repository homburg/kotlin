/*
 * Copyright 2010-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.jps.build

import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.io.FileUtilRt
import com.intellij.testFramework.TestLoggerFactory
import com.intellij.testFramework.UsefulTestCase
import junit.framework.TestCase
import org.apache.log4j.ConsoleAppender
import org.apache.log4j.Level
import org.apache.log4j.Logger
import org.apache.log4j.PatternLayout
import org.jetbrains.jps.api.CanceledStatus
import org.jetbrains.jps.builders.BuildResult
import org.jetbrains.jps.builders.CompileScopeTestBuilder
import org.jetbrains.jps.builders.JpsBuildTestCase
import org.jetbrains.jps.builders.impl.BuildDataPathsImpl
import org.jetbrains.jps.builders.impl.logging.ProjectBuilderLoggerBase
import org.jetbrains.jps.builders.java.dependencyView.Callbacks
import org.jetbrains.jps.builders.logging.BuildLoggingManager
import org.jetbrains.jps.cmdline.ProjectDescriptor
import org.jetbrains.jps.incremental.BuilderRegistry
import org.jetbrains.jps.incremental.IncProjectBuilder
import org.jetbrains.jps.incremental.ModuleBuildTarget
import org.jetbrains.jps.incremental.messages.BuildMessage
import org.jetbrains.jps.model.JpsElementFactory
import org.jetbrains.jps.model.JpsModuleRootModificationUtil
import org.jetbrains.jps.model.java.JpsJavaDependencyScope
import org.jetbrains.jps.model.java.JpsJavaExtensionService
import org.jetbrains.jps.util.JpsPathUtil
import org.jetbrains.kotlin.incremental.components.LookupTracker
import org.jetbrains.kotlin.jps.build.classFilesComparison.assertEqualDirectories
import org.jetbrains.kotlin.jps.incremental.getKotlinCache
import org.jetbrains.kotlin.test.JetTestUtils
import org.jetbrains.kotlin.utils.Printer
import org.jetbrains.kotlin.utils.keysToMap
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.util.*
import kotlin.properties.Delegates
import kotlin.test.assertEquals
import kotlin.test.assertFalse

public abstract class AbstractIncrementalJpsTest(
        private val allowNoFilesWithSuffixInTestData: Boolean = false,
        private val checkDumpsCaseInsensitively: Boolean = false,
        private val allowNoBuildLogFileInTestData: Boolean = false
) : JpsBuildTestCase() {
    companion object {
        val COMPILATION_FAILED = "COMPILATION FAILED"

        // change to "/tmp" or anything when default is too long (for easier debugging)
        val TEMP_DIRECTORY_TO_USE = File(FileUtilRt.getTempDirectory())

        val DEBUG_LOGGING_ENABLED = System.getProperty("debug.logging.enabled") == "true"
    }

    protected var testDataDir: File by Delegates.notNull()

    protected var workDir: File by Delegates.notNull()

    protected var projectDescriptor: ProjectDescriptor by Delegates.notNull()

    private fun enableDebugLogging() {
        com.intellij.openapi.diagnostic.Logger.setFactory(javaClass<TestLoggerFactory>())
        TestLoggerFactory.dumpLogToStdout("")
        TestLoggerFactory.enableDebugLogging(myTestRootDisposable, "#org")

        val console = ConsoleAppender()
        console.layout = PatternLayout("%d [%p|%c|%C{1}] %m%n");
        console.threshold = Level.ALL
        console.activateOptions()
        Logger.getRootLogger().addAppender(console)
    }

    override fun setUp() {
        super.setUp()
        System.setProperty("kotlin.jps.tests", "true")

        if (DEBUG_LOGGING_ENABLED) {
            enableDebugLogging()
        }
    }

    override fun tearDown() {
        System.clearProperty("kotlin.jps.tests")
        super.tearDown()
    }

    protected open val mockConstantSearch: Callbacks.ConstantAffectionResolver?
        get() = null

    protected open fun createLookupTracker(): LookupTracker = LookupTracker.DO_NOTHING

    protected open fun checkLookups(modifications: List<Modification>, @Suppress("UNUSED_PARAMETER") lookupTracker: LookupTracker) {
    }

    private fun build(scope: CompileScopeTestBuilder = CompileScopeTestBuilder.make().all(), modifications: List<Modification>, checkLookups: Boolean = true): MakeResult {
        val workDirPath = FileUtil.toSystemIndependentName(workDir.absolutePath)
        val logger = MyLogger(workDirPath)
        projectDescriptor = createProjectDescriptor(BuildLoggingManager(logger))

        val lookupTracker = createLookupTracker()
        val dataContainer = JpsElementFactory.getInstance().createSimpleElement(lookupTracker)
        projectDescriptor.project.container.setChild(KotlinBuilder.LOOKUP_TRACKER, dataContainer)

        try {
            val builder = IncProjectBuilder(projectDescriptor, BuilderRegistry.getInstance(), myBuildParams, CanceledStatus.NULL, mockConstantSearch, true)
            val buildResult = BuildResult()
            builder.addMessageHandler(buildResult)
            builder.build(scope.build(), false)

            if (checkLookups) {
                checkLookups(modifications, lookupTracker)
            }

            if (!buildResult.isSuccessful) {
                val errorMessages =
                        buildResult
                                .getMessages(BuildMessage.Kind.ERROR)
                                .map { it.messageText }
                                .map { it.replace("^.+:\\d+:\\s+".toRegex(), "").trim() }
                                .joinToString("\n")
                return MakeResult(logger.log + "$COMPILATION_FAILED\n" + errorMessages + "\n", true, null)
            }
            else {
                return MakeResult(logger.log, false, createMappingsDump(projectDescriptor))
            }
        }
        finally {
            projectDescriptor.dataManager.flush(false)
            projectDescriptor.release()
        }
    }

    private fun initialMake(): MakeResult {
        val makeResult = build(modifications = emptyList())

        val initBuildLogFile = File(testDataDir, "init-build.log")
        if (initBuildLogFile.exists()) {
            UsefulTestCase.assertSameLinesWithFile(initBuildLogFile.absolutePath, makeResult.log)
        }
        else {
            assertFalse(makeResult.makeFailed, "Initial make failed:\n$makeResult")
        }

        return makeResult
    }

    private fun make(modifications: List<Modification>): MakeResult {
        return build(modifications = modifications)
    }

    private fun rebuild(): MakeResult {
        return build(CompileScopeTestBuilder.rebuild().allModules(), emptyList(), checkLookups = false)
    }

    private fun getModificationsToPerform(moduleNames: Collection<String>?): List<List<Modification>> {

        fun getModificationsForIteration(newSuffix: String, deleteSuffix: String): List<Modification> {

            fun getDirPrefix(fileName: String): String {
                val underscore = fileName.indexOf("_")

                if (underscore != -1) {
                    val module = fileName.substring(0, underscore)

                    assert(moduleNames != null) { "File name has module prefix, but multi-module environment is absent" }
                    assert(module in moduleNames!!) { "Module not found for file with prefix: $fileName" }

                    return "$module/src"
                }

                assert(moduleNames == null) { "Test is multi-module, but file has no module prefix: $fileName" }
                return "src"
            }

            val modifications = ArrayList<Modification>()
            for (file in testDataDir.listFiles()!!) {
                val fileName = file.getName()

                if (fileName.endsWith(newSuffix)) {
                    modifications.add(ModifyContent(getDirPrefix(fileName) + "/" + fileName.removeSuffix(newSuffix), file))
                }
                if (fileName.endsWith(deleteSuffix)) {
                    modifications.add(DeleteFile(getDirPrefix(fileName) + "/" + fileName.removeSuffix(deleteSuffix)))
                }
            }
            return modifications
        }

        val haveFilesWithoutNumbers = testDataDir.listFiles { it.getName().matches(".+\\.(new|delete)$".toRegex()) }?.isNotEmpty() ?: false
        val haveFilesWithNumbers = testDataDir.listFiles { it.getName().matches(".+\\.(new|delete)\\.\\d+$".toRegex()) }?.isNotEmpty() ?: false

        if (haveFilesWithoutNumbers && haveFilesWithNumbers) {
            fail("Bad test data format: files ending with both unnumbered and numbered \".new\"/\".delete\" were found")
        }
        if (!haveFilesWithoutNumbers && !haveFilesWithNumbers) {
            if (allowNoFilesWithSuffixInTestData) {
                return listOf(listOf())
            }
            else {
                fail("Bad test data format: no files ending with \".new\" or \".delete\" found")
            }
        }

        if (haveFilesWithoutNumbers) {
            return listOf(getModificationsForIteration(".new", ".delete"))
        }
        else {
            return (1..10)
                    .map { getModificationsForIteration(".new.$it", ".delete.$it") }
                    .filter { it.isNotEmpty() }
        }
    }

    private fun rebuildAndCheckOutput(makeOverallResult: MakeResult) {
        val outDir = File(getAbsolutePath("out"))
        val outAfterMake = File(getAbsolutePath("out-after-make"))

        if (outDir.exists()) {
            FileUtil.copyDir(outDir, outAfterMake)
        }

        val rebuildResult = rebuild()
        assertEquals(rebuildResult.makeFailed, makeOverallResult.makeFailed,
                     "Rebuild failed: ${rebuildResult.makeFailed}, last make failed: ${makeOverallResult.makeFailed}. Rebuild result: $rebuildResult")

        if (!outAfterMake.exists()) {
            assertFalse(outDir.exists())
        }
        else {
            assertEqualDirectories(outDir, outAfterMake, makeOverallResult.makeFailed)
        }

        if (!makeOverallResult.makeFailed) {
            if (checkDumpsCaseInsensitively && rebuildResult.mappingsDump?.toLowerCase() == makeOverallResult.mappingsDump?.toLowerCase()) {
                // do nothing
            }
            else {
                TestCase.assertEquals(rebuildResult.mappingsDump, makeOverallResult.mappingsDump)
            }
        }

        FileUtil.delete(outAfterMake)
    }

    private fun clearCachesRebuildAndCheckOutput(makeOverallResult: MakeResult) {
        FileUtil.delete(BuildDataPathsImpl(myDataStorageRoot).dataStorageRoot!!)

        rebuildAndCheckOutput(makeOverallResult)
    }

    private fun readModuleDependencies(): Map<String, List<DependencyDescriptor>>? {
        val dependenciesTxt = File(testDataDir, "dependencies.txt")
        if (!dependenciesTxt.exists()) return null

        val result = HashMap<String, List<DependencyDescriptor>>()
        for (line in dependenciesTxt.readLines()) {
            val split = line.split("->")
            val module = split[0]
            val dependencies = if (split.size() > 1) split[1] else ""
            val dependencyList = dependencies.split(",").filterNot { it.isEmpty() }
            result[module] = dependencyList.map(::parseDependency)
        }

        return result
    }

    protected open fun doTest(testDataPath: String) {
        testDataDir = File(testDataPath)
        workDir = FileUtilRt.createTempDirectory(TEMP_DIRECTORY_TO_USE, "jps-build", null)

        val moduleNames = configureModules()
        initialMake()

        val otherMakeResults = performModificationsAndMake(moduleNames)

        val buildLogFile = File(testDataDir, "build.log")
        if (buildLogFile.exists() || !allowNoBuildLogFileInTestData) {
            val logs = otherMakeResults.joinToString("\n\n") { it.log }
            UsefulTestCase.assertSameLinesWithFile(buildLogFile.absolutePath, logs)
        }

        val lastMakeResult = otherMakeResults.last()
        rebuildAndCheckOutput(lastMakeResult)
        clearCachesRebuildAndCheckOutput(lastMakeResult)
    }

    private fun createMappingsDump(project: ProjectDescriptor) =
            createKotlinIncrementalCacheDump(project) + "\n\n\n" +
            createCommonMappingsDump(project) + "\n\n\n" +
            createJavaMappingsDump(project)

    private fun createKotlinIncrementalCacheDump(project: ProjectDescriptor): String {
        return StringBuilder {
            for (target in project.allModuleTargets.sortedBy { it.presentableName }) {
                append("<target $target>\n")
                append(project.dataManager.getKotlinCache(target).dump())
                append("</target $target>\n\n\n")
            }
        }.toString()
    }

    private fun createCommonMappingsDump(project: ProjectDescriptor): String {
        val resultBuf = StringBuilder()
        val result = Printer(resultBuf)

        result.println("Begin of SourceToOutputMap")
        result.pushIndent()

        for (target in project.allModuleTargets) {
            result.println(target)
            result.pushIndent()

            val mapping = project.dataManager.getSourceToOutputMap(target)
            mapping.sources.forEach {
                val outputs = mapping.getOutputs(it)!!.sorted()
                if (outputs.isNotEmpty()) {
                    result.println("source $it -> $outputs")
                }
            }

            result.popIndent()
        }

        result.popIndent()
        result.println("End of SourceToOutputMap")

        return resultBuf.toString()
    }

    private fun createJavaMappingsDump(project: ProjectDescriptor): String {
        val byteArrayOutputStream = ByteArrayOutputStream()
        PrintStream(byteArrayOutputStream).use {
            project.dataManager.mappings.toStream(it)
        }
        return byteArrayOutputStream.toString()
    }

    private data class MakeResult(val log: String, val makeFailed: Boolean, val mappingsDump: String?)

    private fun performModificationsAndMake(moduleNames: Set<String>?): List<MakeResult> {
        val results = arrayListOf<MakeResult>()

        val modifications = getModificationsToPerform(moduleNames)
        for (step in modifications) {
            step.forEach { it.perform(workDir) }
            performAdditionalModifications(step)
            if (moduleNames == null) {
                preProcessSources(File(workDir, "src"))
            }
            else {
                moduleNames.forEach { preProcessSources(File(workDir, "$it/src")) }
            }

            results.add(make(step))
        }
        return results
    }

    protected open fun performAdditionalModifications(modifications: List<Modification>) {
    }

    // null means one module
    private fun configureModules(): Set<String>? {
        var moduleNames: Set<String>?
        JpsJavaExtensionService.getInstance().getOrCreateProjectExtension(myProject).outputUrl = JpsPathUtil.pathToUrl(getAbsolutePath("out"))

        val jdk = addJdk("my jdk")
        val moduleDependencies = readModuleDependencies()
        if (moduleDependencies == null) {
            addModule("module", arrayOf(getAbsolutePath("src")), null, null, jdk)

            val srcDir = File(workDir, "src")
            FileUtil.copyDir(testDataDir, srcDir, { it.getName().endsWith(".kt") || it.getName().endsWith(".java") })

            preProcessSources(srcDir)

            moduleNames = null
        }
        else {
            val nameToModule = moduleDependencies.keySet()
                    .keysToMap { addModule(it, arrayOf(getAbsolutePath("$it/src")), null, null, jdk)!! }

            for ((moduleName, dependencies) in moduleDependencies) {
                val module = nameToModule[moduleName]!!

                for (dependency in dependencies) {
                    JpsModuleRootModificationUtil.addDependency(module, nameToModule[dependency.name],
                                                                JpsJavaDependencyScope.COMPILE, dependency.exported)
                }
            }

            for (module in nameToModule.values()) {
                val moduleName = module.name

                val srcDir = File(workDir, "$moduleName/src")
                FileUtil.copyDir(testDataDir, srcDir,
                                 { it.getName().startsWith(moduleName + "_") && (it.getName().endsWith(".kt") || it.getName().endsWith(".java")) })

                preProcessSources(srcDir)
            }

            moduleNames = nameToModule.keySet()
        }
        AbstractKotlinJpsBuildTestCase.addKotlinRuntimeDependency(myProject)
        return moduleNames
    }

    protected open fun preProcessSources(srcDir: File) {
    }

    override fun doGetProjectDir(): File? = workDir

    private class MyLogger(val rootPath: String) : ProjectBuilderLoggerBase() {
        private val logBuf = StringBuilder()
        public val log: String
            get() = logBuf.toString()

        override fun isEnabled(): Boolean = true

        override fun logLine(message: String?) {
            logBuf.append(JetTestUtils.replaceHashWithStar(message!!.removePrefix("$rootPath/"))).append('\n')
        }
    }

    protected abstract class Modification(val path: String) {
        abstract fun perform(workDir: File)

        override fun toString(): String = "${javaClass.simpleName} $path"
    }

    protected class ModifyContent(path: String, val dataFile: File) : Modification(path) {
        override fun perform(workDir: File) {
            val file = File(workDir, path)

            val oldLastModified = file.lastModified()
            file.delete()
            dataFile.copyTo(file)

            val newLastModified = file.lastModified()
            if (newLastModified <= oldLastModified) {
                //Mac OS and some versions of Linux truncate timestamp to nearest second
                file.setLastModified(oldLastModified + 1000)
            }
        }
    }

    protected class DeleteFile(path: String) : Modification(path) {
        override fun perform(workDir: File) {
            val fileToDelete = File(workDir, path)
            if (!fileToDelete.delete()) {
                throw AssertionError("Couldn't delete $fileToDelete")
            }
        }
    }
}

internal val ProjectDescriptor.allModuleTargets: Collection<ModuleBuildTarget>
    get() = buildTargetIndex.allTargets.filterIsInstance<ModuleBuildTarget>()

private class DependencyDescriptor(val name: String, val exported: Boolean)

private fun parseDependency(dependency: String): DependencyDescriptor =
        DependencyDescriptor(dependency.removeSuffix(EXPORTED_SUFFIX), dependency.endsWith(EXPORTED_SUFFIX))

private val EXPORTED_SUFFIX = "[exported]"
