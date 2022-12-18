/*
 * Copyright (2021) The Delta Lake Project Authors.
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

package io.delta.storage

import java.io.IOException

import scala.util.Random

import org.scalatest.funsuite.AnyFunSuite

class ThreadUtilsSuite extends AnyFunSuite {
  test("runInNewThread") {
    import io.delta.storage.internal.ThreadUtils.runInNewThread

    assert(runInNewThread("thread-name",
      true,
      () => {
        Thread.currentThread().getName
      }) === "thread-name"
    )
    assert(runInNewThread("thread-name",
      true,
      () => {
        Thread.currentThread().isDaemon
      })
    )
    assert(runInNewThread("thread-name",
      false,
      () => {
        Thread.currentThread().isDaemon
      } === false)
    )

    val ioExceptionMessage = "test" + Random.nextInt()
    val ioException = intercept[IOException] {
      runInNewThread("thread-name",
        true,
        () => {
          throw new IOException(ioExceptionMessage)
        })
    }
    assert(ioException.getMessage === ioExceptionMessage)
    assert(ioException.getStackTrace.mkString("\n")
      .contains("... run in separate thread using ThreadUtils"))
    assert(!ioException.getStackTrace.mkString("\n").contains("ThreadUtils.java"))
  }
}
