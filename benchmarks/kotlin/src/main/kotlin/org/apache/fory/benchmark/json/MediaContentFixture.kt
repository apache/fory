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

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

object MediaContentFixture {
  const val SHA256: String = "8faba2f57ab397f319aced5cf1e8411a76785557d4c7d1703ec9d540354310a1"

  fun bytes(): ByteArray {
    val stream =
      checkNotNull(javaClass.classLoader.getResourceAsStream("data/eishay.json")) {
        "Missing data/eishay.json"
      }
    val bytes = stream.use { it.readBytes() }
    check(sha256(bytes) == SHA256) { "Eishay fixture SHA-256 does not match $SHA256" }
    return bytes
  }

  fun text(bytes: ByteArray): String = String(bytes, StandardCharsets.UTF_8)

  fun expected(): MediaContent =
    MediaContent(
      images =
        listOf(
          Image(
            height = 768,
            size = ImageSize.LARGE,
            title = "Javaone Keynote",
            uri = "http://javaone.com/keynote_large.jpg",
            width = 1024,
          ),
          Image(
            height = 240,
            size = ImageSize.SMALL,
            title = "Javaone Keynote",
            uri = "http://javaone.com/keynote_small.jpg",
            width = 320,
          ),
        ),
      media =
        Media(
          bitrate = 262144,
          duration = 18000000,
          format = "video/mpg4",
          height = 480,
          persons = listOf("Bill Gates", "Steve Jobs"),
          player = Player.JAVA,
          size = 58982400,
          title = "Javaone Keynote",
          uri = "http://javaone.com/keynote.mpg",
          width = 640,
        ),
    )

  private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
