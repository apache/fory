/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.fory.scala.rpc

import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.Future

class RpcHandleTest extends AnyFunSuite {
  test("rpc future keeps cancellation separate from future view") {
    final class TestFuture extends RpcFuture[Int] {
      private var cancelled = false

      override def asFuture: Future[Int] = Future.successful(1)

      override def cancel(): Boolean = {
        cancelled = true
        true
      }

      override def isCancelled: Boolean = cancelled

      override def isDone: Boolean = cancelled
    }

    val handle = new TestFuture
    assert(handle.asFuture.isCompleted)
    assert(!handle.isCancelled)
    assert(handle.cancel())
    assert(handle.isCancelled)
    assert(handle.isDone)
  }

  test("rpc iterator close delegates to cancel") {
    final class TestIterator extends RpcIterator[Int] {
      private var closed = false

      override def hasNext: Boolean = false

      override def next(): Int = throw new NoSuchElementException

      override def cancel(): Unit = {
        closed = true
      }

      override def isClosed: Boolean = closed
    }

    val iterator = new TestIterator
    assert(!iterator.isClosed)
    iterator.close()
    assert(iterator.isClosed)
  }
}
