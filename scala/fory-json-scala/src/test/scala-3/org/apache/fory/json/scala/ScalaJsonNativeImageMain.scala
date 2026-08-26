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

import org.apache.fory.json.ForyJson
import org.apache.fory.json.annotation.{ForyJsonProvider, JsonProperty, JsonSubTypes, JsonType}

@JsonType
case class NativeNode(value: Int, next: Option[NativeNode] = None)

@JsonType
case class NativeMedia(
    @JsonProperty("media_uri") uri: String,
    tags: List[String] = Nil
)

enum NativeResult derives ScalaJsonCodec {
  case Ok(value: String)
  case Error(code: Int)
  case Pending
}

@JsonSubTypes(property = "kind")
sealed trait NativeEvent derives ScalaJsonCodec

final case class NativeMessage(value: String) extends NativeEvent

case object NativeIdle extends NativeEvent

@JsonType
enum NativeColor {
  case Red, Blue
}

@ForyJsonProvider
final class ScalaJsonNativeConfig {
  def json(): ForyJson =
    ForyJsonScala.builder().build()
}

object ScalaJsonNativeImageMain {
  def main(args: Array[String]): Unit = {
    require(classOf[ScalaJsonNativeConfig].isAnnotationPresent(classOf[ForyJsonProvider]))
    val json = ForyJsonScala.builder().build()
    val node = NativeNode(1, Some(NativeNode(2)))
    require(json.fromJson(json.toJson(node), classOf[NativeNode]) == node)
    require(json.fromJson("""{"value":3}""", classOf[NativeNode]) == NativeNode(3))

    val media = NativeMedia("video", List("json", "scala"))
    val mediaText = json.toJson(media)
    require(mediaText.contains("\"media_uri\""))
    require(json.fromJson(mediaText, classOf[NativeMedia]) == media)

    val result: NativeResult = NativeResult.Ok("ready")
    require(json.fromJson(json.toJson(result), classOf[NativeResult]) == result)
    val event: NativeEvent = NativeMessage("sealed")
    require(
      json.fromJson(json.toJson(event, classOf[NativeEvent]), classOf[NativeEvent]) == event
    )
    require(
      json.fromJson(json.toJson(NativeIdle, classOf[NativeEvent]), classOf[NativeEvent]) eq NativeIdle
    )
    require(json.fromJson(json.toJson(NativeColor.Blue), classOf[NativeColor]) == NativeColor.Blue)
    println("Fory Scala JSON native image succeeded")
  }
}
