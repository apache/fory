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

case class MediaContent(media: Media, images: List[Image])

case class Media(
    uri: String = null,
    title: String = null,
    width: Int = 0,
    height: Int = 0,
    format: String = null,
    duration: Long = 0L,
    size: Long = 0L,
    bitrate: Int = 0,
    hasBitrate: Boolean = false,
    persons: List[String],
    player: String = null,
    copyright: String = null,
)

case class Image(
    uri: String = null,
    title: String = null,
    width: Int = 0,
    height: Int = 0,
    size: String = null,
    media: Media = null,
)
