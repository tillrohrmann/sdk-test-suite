// Copyright (c) 2023 - Restate Software, Inc., Restate GmbH
//
// This file is part of the Restate SDK Test suite tool,
// which is released under the MIT license.
//
// You can find a copy of the license in file LICENSE in the root
// directory of this repository or package, or at
// https://github.com/restatedev/sdk-test-suite/blob/main/LICENSE
package dev.restate.sdktesting.tests

import dev.restate.admin.api.DeploymentApi
import dev.restate.admin.client.ApiClient
import dev.restate.admin.model.RegisterDeploymentRequest
import dev.restate.admin.model.RegisterDeploymentRequestAnyOf
import dev.restate.sdk.client.Client
import dev.restate.sdktesting.contracts.*
import dev.restate.sdktesting.infra.*
import java.net.URL
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.kotlin.await
import org.awaitility.kotlin.matches
import org.awaitility.kotlin.until
import org.awaitility.kotlin.untilAsserted
import org.awaitility.kotlin.untilCallTo
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

@Tag("always-suspending")
class UpgradeWithInFlightInvocation {

  companion object {
    private val UPGRADE_TEST_ENV = "UPGRADETEST_VERSION"

    @RegisterExtension
    val deployerExt: RestateDeployerExtension = RestateDeployerExtension {
      withServiceSpec(
          ServiceSpec.builder("version1")
              .withServices(
                  TestUtilsServiceDefinitions.SERVICE_NAME,
                  ListObjectDefinitions.SERVICE_NAME,
                  AwakeableHolderDefinitions.SERVICE_NAME)
              .withEnv(UPGRADE_TEST_ENV, "v1"))
      withServiceSpec(
          ServiceSpec.builder("version2")
              .skipRegistration()
              .withServices(TestUtilsServiceDefinitions.SERVICE_NAME)
              .withEnv(UPGRADE_TEST_ENV, "v2"))
    }

    fun registerService2(metaURL: URL) {
      val client = DeploymentApi(ApiClient().setHost(metaURL.host).setPort(metaURL.port))
      client.createDeployment(
          RegisterDeploymentRequest(
              RegisterDeploymentRequestAnyOf().uri("http://version2:9080/").force(false)))
    }
  }

  @Test
  fun inFlightInvocation(@InjectClient ingressClient: Client, @InjectMetaURL metaURL: URL) =
      runTest {
        val testUtilsClient = TestUtilsServiceClient.fromClient(ingressClient)
        val awakeableKey = "upgrade"
        val listName = "upgrade-test"

        testUtilsClient
            .send()
            .interpretCommands(
                InterpretRequest(
                    listName,
                    listOf(
                        GetEnvVariable(UPGRADE_TEST_ENV),
                        CreateAwakeableAndAwaitIt(awakeableKey),
                        GetEnvVariable(UPGRADE_TEST_ENV),
                    )))

        // Await until AwakeableHolder has an awakeable
        val awakeableHolderClient = AwakeableHolderClient.fromClient(ingressClient, awakeableKey)
        await until { runBlocking { awakeableHolderClient.hasAwakeable() } }

        // Now register the update
        registerService2(metaURL)

        // Let's wait for at least once returning v2
        await untilCallTo
            {
              runBlocking { testUtilsClient.getEnvVariable(UPGRADE_TEST_ENV) }
            } matches
            { result ->
              result!! == "v2"
            }

        // Now let's resume the awakeable
        awakeableHolderClient.unlock("unlocked")

        // Let's check the list the interpreter appended to contains always v1 env variables
        await untilAsserted
            {
              assertThat(runBlocking { ListObjectClient.fromClient(ingressClient, listName).get() })
                  .containsExactly("v1", "unlocked", "v1")
            }
      }
}
