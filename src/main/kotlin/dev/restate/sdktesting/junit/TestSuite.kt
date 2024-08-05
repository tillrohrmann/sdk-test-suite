// Copyright (c) 2023 - Restate Software, Inc., Restate GmbH
//
// This file is part of the Restate SDK Test suite tool,
// which is released under the MIT license.
//
// You can find a copy of the license in file LICENSE in the root
// directory of this repository or package, or at
// https://github.com/restatedev/sdk-test-suite/blob/main/LICENSE
package dev.restate.sdktesting.junit

import com.github.ajalt.mordant.rendering.TextStyles.bold
import com.github.ajalt.mordant.terminal.Terminal
import dev.restate.sdktesting.infra.BaseRestateDeployerExtension
import dev.restate.sdktesting.infra.getGlobalConfig
import dev.restate.sdktesting.infra.registerGlobalConfig
import java.io.PrintWriter
import java.nio.file.Path
import kotlin.jvm.optionals.getOrNull
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.ThreadContext
import org.apache.logging.log4j.core.config.Configurator
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilderFactory
import org.apache.logging.log4j.core.config.builder.impl.BuiltConfiguration
import org.junit.platform.engine.Filter
import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.engine.discovery.DiscoverySelectors
import org.junit.platform.engine.support.descriptor.ClassSource
import org.junit.platform.engine.support.descriptor.MethodSource
import org.junit.platform.launcher.*
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.launcher.core.LauncherFactory
import org.junit.platform.reporting.legacy.xml.LegacyXmlReportGeneratingListener

class TestSuite(
    val name: String,
    val additionalEnvs: Map<String, String>,
    val junitIncludeTags: String
) {
  fun runTests(
      terminal: Terminal,
      baseReportDir: Path,
      filters: List<Filter<*>>,
      printToStdout: Boolean
  ): ExecutionResult {
    val reportDir = baseReportDir.resolve(name)
    terminal.println(
        """
              |==== ${bold(name)}
              |🗈 Report directory: $reportDir
          """
            .trimMargin())

    // Apply additional runtime envs
    registerGlobalConfig(getGlobalConfig().copy(additionalRuntimeEnvs = additionalEnvs))

    // Prepare Log4j2 configuration
    Configurator.reconfigure(prepareLog4j2Config(reportDir, printToStdout))

    // Prepare launch request
    val request =
        LauncherDiscoveryRequestBuilder.request()
            .selectors(DiscoverySelectors.selectPackage("dev.restate.sdktesting.tests"))
            .filters(TagFilter.includeTags(junitIncludeTags))
            .filters(*filters.toTypedArray())
            // Redirect STDOUT/STDERR
            .configurationParameter(LauncherConstants.CAPTURE_STDOUT_PROPERTY_NAME, "true")
            .configurationParameter(LauncherConstants.CAPTURE_STDERR_PROPERTY_NAME, "true")
            // Config option used by RestateDeployer extensions
            .configurationParameter(
                BaseRestateDeployerExtension.REPORT_DIR_PROPERTY_NAME, reportDir.toString())
            .build()

    // Configure listeners
    val errWriter = PrintWriter(System.err)
    val executionResultCollector = ExecutionResultCollector(name)
    // TODO replace this with our own xml writer
    val xmlReportListener = LegacyXmlReportGeneratingListener(reportDir, errWriter)
    val redirectStdoutAndStderrListener =
        RedirectStdoutAndStderrListener(
            reportDir.resolve("testrunner.stdout"),
            reportDir.resolve("testrunner.stderr"),
            errWriter)
    val logTestEventsListener =
        object : TestExecutionListener {
          @Volatile var testPlan: TestPlan? = null

          override fun testPlanExecutionStarted(testPlan: TestPlan) {
            this.testPlan = testPlan
          }

          override fun executionFinished(
              testIdentifier: TestIdentifier,
              testExecutionResult: TestExecutionResult
          ) {
            if (testIdentifier.isTest) {
              val name = describeTestIdentifier(name, testPlan!!, testIdentifier)
              when (testExecutionResult.status!!) {
                TestExecutionResult.Status.SUCCESSFUL -> terminal.println("✅ $name")
                TestExecutionResult.Status.ABORTED -> terminal.println("❌ $name")
                TestExecutionResult.Status.FAILED -> {
                  terminal.println("❌ $name")
                }
              }
            }
            if (testIdentifier.source.getOrNull() is ClassSource) {
              val name = describeTestIdentifier(name, testPlan!!, testIdentifier)
              when (testExecutionResult.status!!) {
                TestExecutionResult.Status.ABORTED -> terminal.println("❌ $name init")
                TestExecutionResult.Status.FAILED -> {
                  terminal.println("❌ $name init")
                }
                else -> {}
              }
            }
          }
        }
    val injectLoggingContextListener =
        object : TestExecutionListener {
          val TEST_NAME = "test"

          override fun executionStarted(testIdentifier: TestIdentifier) {
            val displayName =
                when (val source = testIdentifier.source.getOrNull()) {
                  is ClassSource -> source.className
                  is MethodSource -> "${source.className}#${source.methodName}"
                  else -> null
                }
            if (displayName != null) {
              ThreadContext.put(TEST_NAME, displayName)
            }
          }

          override fun executionFinished(
              testIdentifier: TestIdentifier,
              testExecutionResult: TestExecutionResult
          ) {
            ThreadContext.remove(TEST_NAME)
          }
        }

    // Launch
    LauncherFactory.openSession().use { session ->
      val launcher = session.launcher
      launcher.registerTestExecutionListeners(
          executionResultCollector,
          logTestEventsListener,
          xmlReportListener,
          redirectStdoutAndStderrListener,
          injectLoggingContextListener)
      launcher.execute(request)
    }

    val report = executionResultCollector.results

    report.printShortSummary(terminal)

    return report
  }

  private fun prepareLog4j2Config(reportDir: Path, printToStdout: Boolean): BuiltConfiguration {
    val builder = ConfigurationBuilderFactory.newConfigurationBuilder()

    val layout = builder.newLayout("PatternLayout")
    layout.addAttribute("pattern", "%-4r %-5p [%t]%notEmpty{[%X{test}]} %c{1.2.*} - %m%n")

    val fileAppender = builder.newAppender("log", "File")
    fileAppender.addAttribute("fileName", reportDir.resolve("testrunner.log").toString())
    fileAppender.add(layout)

    val rootLogger = builder.newRootLogger(Level.INFO)
    rootLogger.add(builder.newAppenderRef("log"))

    val testContainersLogger = builder.newLogger("org.testcontainers", Level.INFO)
    testContainersLogger.add(builder.newAppenderRef("log"))
    testContainersLogger.addAttribute("additivity", false)

    val restateLogger = builder.newLogger("dev.restate", Level.DEBUG)
    restateLogger.add(builder.newAppenderRef("log"))
    restateLogger.addAttribute("additivity", false)

    if (printToStdout) {
      val consoleAppender = builder.newAppender("stdout", "Console")
      consoleAppender.add(layout)
      builder.add(consoleAppender)

      rootLogger.add(builder.newAppenderRef("stdout"))
      testContainersLogger.add(builder.newAppenderRef("stdout"))
      restateLogger.add(builder.newAppenderRef("stdout"))
    }

    builder.add(fileAppender)
    builder.add(rootLogger)
    builder.add(testContainersLogger)
    builder.add(restateLogger)

    return builder.build()
  }
}
