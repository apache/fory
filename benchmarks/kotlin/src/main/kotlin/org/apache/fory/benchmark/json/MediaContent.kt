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

package org.apache.fory.benchmark.json

import com.squareup.moshi.JsonClass
import kotlinx.serialization.Serializable
import org.apache.fory.json.annotation.JsonType

@JsonType
@Serializable
@JsonClass(generateAdapter = true)
data class MediaContent(val images: List<Image>, val media: Media)

@JsonType
@Serializable
@JsonClass(generateAdapter = true)
data class Media(
  val bitrate: Int,
  val duration: Long,
  val format: String,
  val height: Int,
  val persons: List<String>,
  val player: Player,
  val size: Long,
  val title: String,
  val uri: String,
  val width: Int,
  val copyright: String? = null,
  val hasBitrate: Boolean = false,
)

@JsonType
@Serializable
@JsonClass(generateAdapter = true)
data class Image(
  val height: Int,
  val size: ImageSize,
  val title: String,
  val uri: String,
  val width: Int,
  val media: Media? = null,
)

@Serializable
enum class Player {
  JAVA,
  FLASH
}

@Serializable
enum class ImageSize {
  SMALL,
  LARGE
}
