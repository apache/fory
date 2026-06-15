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

package org.apache.fory.kotlin

import org.apache.fory.BaseFory
import org.apache.fory.Fory
import org.apache.fory.ForyModule
import org.apache.fory.serializer.kotlin.KotlinSerializers

public inline fun <reified T : Any> BaseFory.register() {
  registerKotlin(this, T::class.java, null, null, null, null)
}

public inline fun <reified T : Any> BaseFory.register(typeId: Long) {
  registerKotlin(this, T::class.java, typeId, null, null, null)
}

public inline fun <reified T : Any> BaseFory.register(name: String) {
  registerKotlin(this, T::class.java, null, name, null, null)
}

public inline fun <reified T : Any> BaseFory.register(namespace: String, typeName: String) {
  registerKotlin(this, T::class.java, null, null, namespace, typeName)
}

@PublishedApi
internal fun registerKotlin(
  fory: BaseFory,
  cls: Class<*>,
  typeId: Long?,
  name: String?,
  namespace: String?,
  typeName: String?,
) {
  fory.register(
    ForyModule { runtime: Fory ->
      runtime.register(ForyKotlin)
      when {
        typeId != null -> KotlinSerializers.register(runtime, cls, typeId)
        name != null -> KotlinSerializers.register(runtime, cls, name)
        namespace != null && typeName != null ->
          KotlinSerializers.register(runtime, cls, namespace, typeName)
        else -> KotlinSerializers.register(runtime, cls)
      }
    },
  )
}
