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

package org.apache.fory.scala

import org.apache.fory.Fory
import org.apache.fory.ForyModule
import org.apache.fory.config.ForyBuilder
import org.apache.fory.serializer.scala.ScalaSerializers

object ForyScala extends ForyModule {
  def builder(): ForyBuilder = Fory.builder().withModule(this)

  override def install(fory: Fory): Unit = ScalaSerializers.registerSerializers(fory)
}
