// Copyright (c) 2023 - Restate Software, Inc., Restate GmbH
//
// This file is part of the Restate e2e tests,
// which are released under the MIT license.
//
// You can find a copy of the license in file LICENSE in the root
// directory of this repository or package, or at
// https://github.com/restatedev/e2e/blob/main/LICENSE

package dev.restate.sdktesting.tests

import dev.restate.admin.api.ServiceApi
import dev.restate.admin.client.ApiClient
import dev.restate.admin.model.ModifyServiceRequest
import dev.restate.sdk.client.CallRequestOptions
import dev.restate.sdk.client.Client
import dev.restate.sdk.client.SendResponse.SendStatus
import dev.restate.sdk.common.Target
import dev.restate.sdktesting.contracts.*
import dev.restate.sdktesting.infra.*
import java.net.URL
import java.util.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.kotlin.await
import org.awaitility.kotlin.until
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

class IngressTest {

  companion object {
    @RegisterExtension
    val deployerExt: RestateDeployerExtension = RestateDeployerExtension {
      withServiceSpec(ServiceSpec.DEFAULT)
    }
  }

  @Test
  @Execution(ExecutionMode.CONCURRENT)
  @Timeout(value = 15, unit = TimeUnit.SECONDS)
  @DisplayName("Idempotent invocation to a virtual object")
  fun idempotentInvokeVirtualObject(
      @InjectMetaURL metaURL: URL,
      @InjectClient ingressClient: Client
  ) = runTest {
    // Let's update the idempotency retention time to 3 seconds, to make this test faster
    val adminServiceClient = ServiceApi(ApiClient().setHost(metaURL.host).setPort(metaURL.port))
    adminServiceClient.modifyService(
        CounterDefinitions.SERVICE_NAME, ModifyServiceRequest().idempotencyRetention("3s"))

    val counterRandomName = UUID.randomUUID().toString()
    val myIdempotencyId = UUID.randomUUID().toString()
    val requestOptions = CallRequestOptions().withIdempotency(myIdempotencyId)

    val counterClient = CounterClient.fromClient(ingressClient, counterRandomName)

    // First call updates the value
    val firstResponse = counterClient.getAndAdd(2, requestOptions)
    assertThat(firstResponse)
        .returns(0, CounterUpdateResponse::oldValue)
        .returns(2, CounterUpdateResponse::newValue)

    // Next call returns the same value
    val secondResponse = counterClient.getAndAdd(2, requestOptions)
    assertThat(secondResponse)
        .returns(0L, CounterUpdateResponse::oldValue)
        .returns(2L, CounterUpdateResponse::newValue)

    // Await until the idempotency id is cleaned up and the next idempotency call updates the
    // counter again
    await untilAsserted
        {
          runBlocking {
            assertThat(counterClient.getAndAdd(2, requestOptions))
                .returns(2, CounterUpdateResponse::oldValue)
                .returns(4, CounterUpdateResponse::newValue)
          }
        }

    // State in the counter service is now equal to 4
    assertThat(counterClient.get()).isEqualTo(4L)
  }

  @Test
  @Execution(ExecutionMode.CONCURRENT)
  @Timeout(value = 15, unit = TimeUnit.SECONDS)
  @DisplayName("Idempotent invocation to a service")
  fun idempotentInvokeService(@InjectClient ingressClient: Client) = runTest {
    val counterRandomName = UUID.randomUUID().toString()
    val myIdempotencyId = UUID.randomUUID().toString()
    val requestOptions = CallRequestOptions().withIdempotency(myIdempotencyId)

    val counterClient = CounterClient.fromClient(ingressClient, counterRandomName)
    val proxyCounterClient = ProxyCounterClient.fromClient(ingressClient)

    // Send request twice
    proxyCounterClient.addInBackground(AddRequest(counterRandomName, 2), requestOptions)
    proxyCounterClient.addInBackground(AddRequest(counterRandomName, 2), requestOptions)

    // Wait for get
    await untilAsserted { runBlocking { assertThat(counterClient.get()).isEqualTo(2) } }

    // Without request options this should be executed immediately and return 4
    assertThat(counterClient.getAndAdd(2))
        .returns(2, CounterUpdateResponse::oldValue)
        .returns(4, CounterUpdateResponse::newValue)
  }

  @Test
  @Execution(ExecutionMode.CONCURRENT)
  @Timeout(value = 15, unit = TimeUnit.SECONDS)
  @DisplayName("Idempotent invocation to a virtual object using send")
  fun idempotentInvokeSend(@InjectClient ingressClient: Client) = runTest {
    val counterRandomName = UUID.randomUUID().toString()
    val myIdempotencyId = UUID.randomUUID().toString()
    val requestOptions = CallRequestOptions().withIdempotency(myIdempotencyId)

    val counterClient = CounterClient.fromClient(ingressClient, counterRandomName)

    // Send request twice
    val firstInvocationSendStatus = counterClient.send().add(2, requestOptions)
    assertThat(firstInvocationSendStatus.status).isEqualTo(SendStatus.ACCEPTED)
    val secondInvocationSendStatus = counterClient.send().add(2, requestOptions)
    assertThat(secondInvocationSendStatus.status).isEqualTo(SendStatus.PREVIOUSLY_ACCEPTED)

    // IDs should be the same
    assertThat(firstInvocationSendStatus.invocationId)
        .startsWith("inv")
        .isEqualTo(secondInvocationSendStatus.invocationId)

    // Wait for get
    await untilAsserted { runBlocking { assertThat(counterClient.get()).isEqualTo(2) } }

    // Without request options this should be executed immediately and return 4
    assertThat(counterClient.getAndAdd(2))
        .returns(2, CounterUpdateResponse::oldValue)
        .returns(4, CounterUpdateResponse::newValue)
  }

  @Test
  @Execution(ExecutionMode.CONCURRENT)
  @Timeout(value = 15, unit = TimeUnit.SECONDS)
  @DisplayName("Idempotent send then attach/getOutput")
  fun idempotentSendThenAttach(@InjectClient ingressClient: Client) = runTest {
    val awakeableKey = UUID.randomUUID().toString()
    val myIdempotencyId = UUID.randomUUID().toString()
    val response = "response"

    // Send request
    val echoClient = EchoClient.fromClient(ingressClient)
    val invocationId =
        echoClient
            .send()
            .blockThenEcho(awakeableKey, CallRequestOptions().withIdempotency(myIdempotencyId))
            .invocationId
    val invocationHandle =
        ingressClient.invocationHandle(invocationId, EchoDefinitions.Serde.BLOCKTHENECHO_OUTPUT)

    // Attach to request
    val blockedFut = invocationHandle.attachAsync()

    // Output is not ready yet
    assertThat(invocationHandle.output.isReady).isFalse()

    // Blocked fut should still be blocked
    assertThat(blockedFut).isNotDone

    // Unblock
    val awakeableHolderClient = AwakeableHolderClient.fromClient(ingressClient, awakeableKey)
    await until { runBlocking { awakeableHolderClient.hasAwakeable() } }
    awakeableHolderClient.unlock(response)

    // Attach should be completed
    assertThat(blockedFut.get()).isEqualTo(response)

    // Invoke get output
    assertThat(invocationHandle.output.value).isEqualTo(response)
  }

  @Test
  @Execution(ExecutionMode.CONCURRENT)
  @Timeout(value = 15, unit = TimeUnit.SECONDS)
  @DisplayName("Idempotent send then attach/getOutput")
  fun idempotentSendThenAttachWIthIdempotencyKey(@InjectClient ingressClient: Client) = runTest {
    val awakeableKey = UUID.randomUUID().toString()
    val myIdempotencyId = UUID.randomUUID().toString()
    val response = "response"

    // Send request
    val echoClient = EchoClient.fromClient(ingressClient)
    assertThat(
            echoClient
                .send()
                .blockThenEcho(awakeableKey, CallRequestOptions().withIdempotency(myIdempotencyId))
                .status)
        .isEqualTo(SendStatus.ACCEPTED)

    val invocationHandle =
        ingressClient.idempotentInvocationHandle(
            Target.service(EchoDefinitions.SERVICE_NAME, "blockThenEcho"),
            myIdempotencyId,
            EchoDefinitions.Serde.BLOCKTHENECHO_OUTPUT)

    // Attach to request
    val blockedFut = invocationHandle.attachAsync()

    // Output is not ready yet
    assertThat(invocationHandle.output.isReady).isFalse()

    // Blocked fut should still be blocked
    assertThat(blockedFut).isNotDone

    // Unblock
    val awakeableHolderClient = AwakeableHolderClient.fromClient(ingressClient, awakeableKey)
    await until { runBlocking { awakeableHolderClient.hasAwakeable() } }
    awakeableHolderClient.unlock(response)

    // Attach should be completed
    assertThat(blockedFut.get()).isEqualTo(response)

    // Invoke get output
    assertThat(invocationHandle.output.value).isEqualTo(response)
  }

  @Test
  @Execution(ExecutionMode.CONCURRENT)
  @Timeout(value = 15, unit = TimeUnit.SECONDS)
  fun headersPassThrough(@InjectClient ingressClient: Client) = runTest {
    val headerName = "x-my-custom-header"
    val headerValue = "x-my-custom-value"

    assertThat(
            HeadersPassThroughTestClient.fromClient(ingressClient)
                .echoHeaders(CallRequestOptions().withHeader(headerName, headerValue)))
        .containsEntry(headerName, headerValue)
  }
}
