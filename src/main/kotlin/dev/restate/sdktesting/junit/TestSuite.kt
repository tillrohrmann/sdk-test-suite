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
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.core.config.Configurator
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilderFactory
import org.apache.logging.log4j.core.config.builder.impl.BuiltConfiguration
import org.junit.platform.engine.Filter
import org.junit.platform.engine.discovery.DiscoverySelectors
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
      printToStdout: Boolean,
      parallel: Boolean
  ): ExecutionResult {
    val reportDir = baseReportDir.resolve(name)
    terminal.println(
        """
              |==== ${bold(name)}
              |🗈 Report directory: $reportDir
          """
            .trimMargin())

    // Prepare Log4j2 configuration
    val log4j2Configuration = prepareLog4j2Config(reportDir, printToStdout)
    Configurator.reconfigure(log4j2Configuration)

    // Apply additional runtime envs
    val restateDeployerConfig = getGlobalConfig().copy(additionalRuntimeEnvs = additionalEnvs)
    registerGlobalConfig(restateDeployerConfig)

    // Prepare launch request
    var builder =
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
            .configurationParameter(
                "junit.jupiter.execution.parallel.mode.classes.default",
                if (parallel) "concurrent" else "same_thread")

    // Disable lifecycle timeout
    if (restateDeployerConfig.retainAfterEnd) {
      builder =
          builder.configurationParameter(
              "junit.jupiter.execution.timeout.lifecycle.method.default", "360m")
    }

    val request = builder.build()

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
    val logTestEventsListener = LogTestEventsToTerminalListener(name, terminal)
    val injectLoggingContextListener = InjectLog4jContextListener(name)

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

    val layout =
        builder
            .newLayout("PatternLayout")
            .addAttribute("pattern", "%-4r %-5p [%X{test_class}][%t] %c{1.2.*} - %m%n")

    val fileAppender =
        builder
            .newAppender("log", "File")
            .addAttribute("fileName", reportDir.resolve("testrunner.log").toString())
            .add(layout)

    val restateLogger =
        builder
            .newLogger("dev.restate", Level.DEBUG)
            .add(builder.newAppenderRef("log"))
            .addAttribute("additivity", false)

    val testContainersLogger =
        builder
            .newLogger("org.testcontainers", Level.INFO)
            .add(builder.newAppenderRef("log"))
            .addAttribute("additivity", false)

    val rootLogger = builder.newRootLogger(Level.WARN).add(builder.newAppenderRef("log"))

    if (printToStdout) {
      val consoleAppender = builder.newAppender("stdout", "Console").add(layout)
      builder.add(consoleAppender)

      rootLogger.add(builder.newAppenderRef("stdout"))
      restateLogger.add(builder.newAppenderRef("stdout"))
    }

    builder.add(fileAppender)
    builder.add(restateLogger)
    builder.add(testContainersLogger)
    builder.add(rootLogger)

    return builder.build()
  }
}
