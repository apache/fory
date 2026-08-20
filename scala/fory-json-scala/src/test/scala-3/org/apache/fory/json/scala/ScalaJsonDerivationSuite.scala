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

package org.apache.fory.json.scala

import java.util.concurrent.atomic.AtomicBoolean

import org.apache.fory.json.{ForyJsonException, JsonCodecFactory}
import org.apache.fory.json.codec.{AbstractJsonValueCodec, JsonValueCodec}
import org.apache.fory.json.reader.JsonReader
import org.apache.fory.json.resolver.JsonTypeResolver
import org.apache.fory.json.writer.JsonWriter
import org.scalatest.funsuite.AnyFunSuite
import org.apache.fory.reflect.TypeRef

enum Result derives ScalaJsonCodec {
  case Ok(value: String)
  case Error(code: Int)
  case Pending
}

enum SharedSingletons derives ScalaJsonCodec {
  case First, Second
  case Item(value: Int)
}

enum StatefulResult derives ScalaJsonCodec {
  case Item(value: () => Int)
}

final case class StatefulEnvelope(value: StatefulResult)

enum ExternalResult {
  case Ok(value: String)
  case Error(code: Int)
}

enum Color {
  case Red, Blue
}

enum DisplayColor {
  case Red, Blue

  override def toString: String = "display"
}

final class PendingCodec extends AbstractJsonValueCodec[Result] {
  override def write(writer: JsonWriter, value: Result): Unit = {
    if (value != Result.Pending)
      throw new ForyJsonException("Expected Result.Pending")
    writer.writeString("pending")
  }

  override def read(reader: JsonReader): Result = {
    if (reader.readString() != "pending")
      throw new ForyJsonException("Expected pending")
    Result.Pending
  }
}

final class PendingFactory extends JsonCodecFactory {
  private val first = new AtomicBoolean(true)

  override def factoryKey(): String = getClass.getName

  override def create(
      typeRef: TypeRef[_],
      resolver: JsonTypeResolver,
      runtimeType: Boolean
  ): JsonValueCodec[_] = {
    if (first.getAndSet(false))
      throw new ForyJsonException("First child resolution fails")
    new PendingCodec
  }
}

final class StatefulCodec extends AbstractJsonValueCodec[StatefulResult] {
  override def write(writer: JsonWriter, value: StatefulResult): Unit = {
    value match {
      case StatefulResult.Item(state) => writer.writeInt(state())
    }
  }

  override def read(reader: JsonReader): StatefulResult = {
    val state = reader.readInt()
    StatefulResult.Item(() => state)
  }
}

final class StatefulFactory extends JsonCodecFactory {
  private val first = new AtomicBoolean(true)

  override def factoryKey(): String = getClass.getName

  override def create(
      typeRef: TypeRef[_],
      resolver: JsonTypeResolver,
      runtimeType: Boolean
  ): JsonValueCodec[_] = {
    if (first.getAndSet(false))
      throw new ForyJsonException("First stateful child resolution fails")
    new StatefulCodec
  }
}

class ScalaJsonDerivationSuite extends AnyFunSuite {
  test("EmptyTuple uses an empty JSON array") {
    val json = ForyJsonScala.builder().withCodegen(false).build()
    val emptyType = new TypeRef[EmptyTuple]() {}
    assert(json.toJson(EmptyTuple, emptyType) == "[]")
    assert(json.fromJson("[]", emptyType) == EmptyTuple)
  }

  test("derived enum uses one wrapper-object shape") {
    val json = ForyJsonScala.builder().withAsyncCompilation(false).build()
    val ok: Result = Result.Ok("ready")
    val error: Result = Result.Error(7)
    val pending: Result = Result.Pending

    assert(json.toJson(ok) == "{\"Ok\":{\"value\":\"ready\"}}")
    assert(json.toJson(error) == "{\"Error\":{\"code\":7}}")
    assert(json.toJson(pending) == "{\"Pending\":{}}")
    assertThrows[ForyJsonException](
      json.fromJson("{\"value\":\"raw\"}", classOf[Result.Ok])
    )
    val pendingClass = Result.Pending.getClass.asInstanceOf[Class[Result]]
    assertThrows[ForyJsonException](json.fromJson("{}", pendingClass))
    assert(json.fromJson(json.toJson(ok), classOf[Result]) == ok)
    assert(json.fromJson(json.toJson(error), classOf[Result]) == error)
    assert(json.fromJson(json.toJson(pending), classOf[Result]) == pending)
    assert(json.fromJson("{\"Pending\":{}}", classOf[Result]) eq Result.Pending)
    assertThrows[ForyJsonException](
      json.fromJson("{\"Pending\":{\"extra\":1}}", classOf[Result])
    )
  }

  test("derived enum shares singleton classes") {
    val values = Seq[SharedSingletons](
      SharedSingletons.First,
      SharedSingletons.Second,
      SharedSingletons.Item(3)
    )
    val runtimes = Seq(
      ForyJsonScala.builder().withCodegen(false).build(),
      ForyJsonScala.builder().withAsyncCompilation(false).build()
    )
    runtimes.foreach { json =>
      values.foreach { value =>
        val text = json.toJson(value, classOf[SharedSingletons])
        assert(json.fromJson(text, classOf[SharedSingletons]) == value)
      }
    }
  }

  test("derived child honors exact codec") {
    val pendingClass = Result.Pending.getClass.asInstanceOf[Class[Result]]
    val json =
      ForyJsonScala.builder().registerCodec(pendingClass, new PendingCodec).withCodegen(false).build()

    assert(json.toJson(Result.Pending, classOf[Result]) == "{\"Pending\":\"pending\"}")
    assert(json.fromJson("{\"Pending\":\"pending\"}", classOf[Result]) eq Result.Pending)
    assert(json.toJson(Result.Pending, pendingClass) == "\"pending\"")
  }

  test("derived child rollback") {
    val pendingClass = Result.Pending.getClass.asInstanceOf[Class[Result]]
    val json = ForyJsonScala
      .builder()
      .registerCodec(pendingClass, new PendingFactory)
      .withCodegen(false)
      .withConcurrencyLevel(1)
      .build()

    assertThrows[ForyJsonException](
      json.fromJson("{\"Pending\":\"pending\"}", classOf[Result])
    )
    assert(json.fromJson("{\"Pending\":\"pending\"}", classOf[Result]) eq Result.Pending)
  }

  test("derived stateful child uses exact codec") {
    val item = StatefulResult.Item(() => 7)
    val itemClass = item.getClass.asInstanceOf[Class[StatefulResult]]
    val unsupported = ForyJsonScala.builder().withCodegen(false).build()
    assertThrows[ForyJsonException](
      unsupported.toJson(item, classOf[StatefulResult])
    )

    val json = ForyJsonScala
      .builder()
      .registerCodec(itemClass, new StatefulCodec)
      .withCodegen(false)
      .build()
    assert(json.toJson(item, itemClass) == "7")
    assert(statefulValue(json.fromJson("7", itemClass)) == 7)
    assert(json.toJson(item, classOf[StatefulResult]) == "{\"Item\":7}")
    assert(statefulValue(json.fromJson("{\"Item\":7}", classOf[StatefulResult])) == 7)
    val envelope = StatefulEnvelope(item)
    assert(
      statefulValue(json.fromJson(json.toJson(envelope), classOf[StatefulEnvelope]).value) == 7
    )

    val retry = ForyJsonScala
      .builder()
      .registerCodec(itemClass, new StatefulFactory)
      .withCodegen(false)
      .withConcurrencyLevel(1)
      .build()
    assertThrows[ForyJsonException](
      retry.fromJson("{\"Item\":7}", classOf[StatefulResult])
    )
    assert(statefulValue(retry.fromJson("{\"Item\":7}", classOf[StatefulResult])) == 7)
  }

  private def statefulValue(value: StatefulResult): Int = value match {
    case StatefulResult.Item(state) => state()
  }

  test("third-party enum uses builder registration") {
    val plainJson = ForyJsonScala.builder().withCodegen(false).build()
    assertThrows[ForyJsonException](plainJson.toJson(ExternalResult.Ok("ready")))

    val json =
      ForyJsonScala.builder().register[ExternalResult].withAsyncCompilation(false).build()
    val result: ExternalResult = ExternalResult.Ok("ready")
    assert(json.toJson(result) == "{\"Ok\":{\"value\":\"ready\"}}")
    assert(json.fromJson(json.toJson(result), classOf[ExternalResult]) == result)
  }

  test("parameterless enum uses its declared root") {
    val json = ForyJsonScala.builder().withCodegen(false).build()
    assert(json.toJson(Color.Blue) == "\"Blue\"")
    assert(json.fromJson("\"Blue\"", classOf[Color]) == Color.Blue)
    assert(json.toJson(DisplayColor.Red) == "\"Red\"")
    assert(json.fromJson("\"Blue\"", classOf[DisplayColor]) == DisplayColor.Blue)
  }
}
