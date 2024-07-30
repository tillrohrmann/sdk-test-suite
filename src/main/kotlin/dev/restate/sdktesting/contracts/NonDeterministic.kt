// Copyright (c) 2024 - Restate Software, Inc., Restate GmbH
//
// This file is part of the Restate e2e tests,
// which are released under the MIT license.
//
// You can find a copy of the license in file LICENSE in the root
// directory of this repository or package, or at
// https://github.com/restatedev/e2e/blob/main/LICENSE
package dev.restate.sdktesting.contracts

import dev.restate.sdk.annotation.*
import dev.restate.sdk.kotlin.ObjectContext

@VirtualObject(name = "NonDeterministic")
interface NonDeterministic {
  @Handler suspend fun leftSleepRightCall(context: ObjectContext)

  @Handler suspend fun callDifferentMethod(context: ObjectContext)

  @Handler suspend fun backgroundInvokeWithDifferentTargets(context: ObjectContext)

  @Handler suspend fun setDifferentKey(context: ObjectContext)
}
