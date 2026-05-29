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

@file:OptIn(ExperimentalUnsignedTypes::class)

package org.apache.fory.idl_tests

import addressbook.AddressBook
import addressbook.AddressbookForyModule
import basic.Money
import example.ExampleForyModule
import example.ExampleMessage
import example.ExampleMessageUnion
import graph.Graph
import graph.GraphForyModule
import java.io.File
import java.math.BigDecimal
import org.apache.fory.Fory
import org.apache.fory.kotlin.ForyKotlin
import org.apache.fory.kotlin.register
import org.apache.fory.type.BFloat16Array
import org.apache.fory.type.Float16Array
import tree.TreeForyModule
import tree.TreeNode
import kotlin.time.Duration.Companion.seconds

public fun main() {
  val addressBookFile = System.getenv("DATA_FILE")
  val exampleFile = System.getenv("DATA_FILE_EXAMPLE")
  val exampleUnionFile = System.getenv("DATA_FILE_EXAMPLE_UNION")
  val treeFile = System.getenv("DATA_FILE_TREE")
  val graphFile = System.getenv("DATA_FILE_GRAPH")
  require(
    addressBookFile != null ||
      exampleFile != null ||
      exampleUnionFile != null ||
      treeFile != null ||
      graphFile != null
  ) {
    "DATA_FILE, DATA_FILE_EXAMPLE, DATA_FILE_EXAMPLE_UNION, DATA_FILE_TREE, or DATA_FILE_GRAPH is required"
  }
  val compatible = System.getenv("IDL_COMPATIBLE").toBoolean()
  val fory =
    ForyKotlin.builder()
      .withXlang(true)
      .withCompatible(compatible)
      .build()
  fory.register(AddressbookForyModule)
  fory.register(ExampleForyModule)

  roundTripFile(fory, addressBookFile, AddressBook::class.java)
  roundTripFile(fory, exampleFile, ExampleMessage::class.java)
  roundTripFile(fory, exampleUnionFile, ExampleMessageUnion::class.java)
  if (treeFile != null || graphFile != null) {
    val refFory =
      ForyKotlin.builder()
        .withXlang(true)
        .withCompatible(compatible)
        .withRefTracking(true)
        .build()
    refFory.register(TreeForyModule)
    refFory.register(GraphForyModule)
    roundTripFile(refFory, treeFile, TreeNode::class.java)
    roundTripFile(refFory, graphFile, Graph::class.java)
  }
  runGeneratedSurfaceChecks()
}

private fun <T : Any> roundTripFile(fory: Fory, path: String?, type: Class<T>) {
  if (path == null) {
    return
  }
  val file = File(path)
  val value = fory.deserialize(file.readBytes(), type)
  file.writeBytes(fory.serialize(value))
}

private fun runGeneratedSurfaceChecks() {
  val fory =
    ForyKotlin.builder()
      .withXlang(true)
      .withCompatible(false)
      .build()
  fory.register(ExampleForyModule)

  assertRoundTrip(fory, ExampleMessageUnion.VarintU32Value(UInt.MAX_VALUE))
  assertRoundTrip(fory, ExampleMessageUnion.DurationValue(3.seconds))
  assertRoundTrip(fory, ExampleMessageUnion.VarintU32List(emptyList()))
  assertRoundTrip(fory, ExampleMessageUnion.VarintU32List(listOf(1u, UInt.MAX_VALUE)))
  assertRoundTrip(fory, ExampleMessageUnion.DurationList(emptyList()))
  assertRoundTrip(fory, ExampleMessageUnion.DurationList(listOf(1.seconds, 2.seconds)))
  assertRoundTrip(fory, ExampleMessageUnion.Int8Array(byteArrayOf(-1, 0, 1)))
  assertRoundTrip(fory, ExampleMessageUnion.Uint32Array(uintArrayOf(1u, UInt.MAX_VALUE)))
  assertRoundTrip(fory, ExampleMessageUnion.Float16Array(Float16Array.of(1.0f, -2.0f)))
  assertRoundTrip(fory, ExampleMessageUnion.Bfloat16Array(BFloat16Array.of(3.0f, -4.0f)))
  assertBaseForyExtensionReceivers()
}

private fun assertBaseForyExtensionReceivers() {
  val expected = Money(BigDecimal("12.34"), "USD")

  val direct = ForyKotlin.builder().withXlang(true).build()
  direct.register<Money>(130L)
  val directBytes = direct.serialize(expected)
  require(direct.deserialize(directBytes, Money::class.java) == expected)

  val threadLocal = ForyKotlin.builder().withXlang(true).buildThreadLocalFory()
  threadLocal.register<Money>(130L)
  val threadLocalBytes = threadLocal.serialize(expected)
  require(threadLocal.deserialize(threadLocalBytes, Money::class.java) == expected)

  val pooled = ForyKotlin.builder().withXlang(true).buildThreadSafeForyPool(1)
  pooled.register<Money>(130L)
  val pooledBytes = pooled.serialize(expected)
  require(pooled.deserialize(pooledBytes, Money::class.java) == expected)
}

private fun assertRoundTrip(fory: Fory, value: ExampleMessageUnion) {
  val decoded = fory.deserialize(fory.serialize(value), ExampleMessageUnion::class.java)
  require(generatedSurfaceEquals(decoded, value)) {
    "Kotlin IDL generated union roundtrip failed: $decoded != $value"
  }
}

private fun generatedSurfaceEquals(left: ExampleMessageUnion, right: ExampleMessageUnion): Boolean =
  when {
    left is ExampleMessageUnion.Int8Array && right is ExampleMessageUnion.Int8Array ->
      left.value.contentEquals(right.value)
    left is ExampleMessageUnion.Uint32Array && right is ExampleMessageUnion.Uint32Array ->
      left.value.contentEquals(right.value)
    else -> left == right
  }
