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

/** A cancellable unary RPC handle returned by generated Scala gRPC clients.
  *
  * `RpcFuture` is transport-free: it has no grpc-java types in its public
  * signature, and it does not define how a generated client stores transport
  * state. Generated gRPC service companions own the grpc-java integration.
  */
trait RpcFuture[+A] {
  /** Return a Scala `Future` view of this RPC result. */
  def asFuture: scala.concurrent.Future[A]

  /** Cancel the underlying RPC if it has not completed. */
  def cancel(): Boolean

  /** Return true when the underlying RPC was cancelled. */
  def isCancelled: Boolean

  /** Return true when the underlying RPC has completed, failed, or cancelled. */
  def isDone: Boolean
}
