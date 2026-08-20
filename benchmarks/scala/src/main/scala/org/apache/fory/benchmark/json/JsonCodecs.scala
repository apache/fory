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

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import org.apache.fory.json.ForyJson
import org.apache.fory.json.scala.ForyJsonScala

object JsonCodecs:
  val foryJson: ForyJson = ForyJsonScala.builder().build()

  given JsonValueCodec[MediaContent] = JsonCodecMaker.make[MediaContent]

  val jacksonMapper: ObjectMapper =
    ObjectMapper()
      .registerModule(DefaultScalaModule)
      .setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL)

  def mediaContentEquals(left: MediaContent, right: MediaContent): Boolean =
    left == right
