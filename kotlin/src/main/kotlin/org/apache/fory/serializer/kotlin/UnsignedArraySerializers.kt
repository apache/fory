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

package org.apache.fory.serializer.kotlin

import org.apache.fory.context.CopyContext
import org.apache.fory.context.ReadContext
import org.apache.fory.context.WriteContext
import org.apache.fory.resolver.TypeResolver
import org.apache.fory.serializer.Serializer
import org.apache.fory.serializer.collection.CollectionLikeSerializer

public abstract class AbstractDelegatingArraySerializer<T, T_Delegate>(
  typeResolver: TypeResolver,
  cls: Class<T>,
  private val delegateClass: Class<T_Delegate>
) : CollectionLikeSerializer<T>(typeResolver, cls, false) {

  @Suppress("UNCHECKED_CAST")
  private fun delegatingSerializer(writeContext: WriteContext): Serializer<T_Delegate> {
    return writeContext.typeResolver.getSerializer(delegateClass) as Serializer<T_Delegate>
  }

  @Suppress("UNCHECKED_CAST")
  private fun delegatingSerializer(readContext: ReadContext): Serializer<T_Delegate> {
    return readContext.typeResolver.getSerializer(delegateClass) as Serializer<T_Delegate>
  }

  protected abstract fun toDelegateClass(value: T): T_Delegate

  protected abstract fun fromDelegateClass(value: T_Delegate): T

  override fun write(writeContext: WriteContext, value: T) {
    delegatingSerializer(writeContext).write(writeContext, toDelegateClass(value))
  }

  override fun read(readContext: ReadContext): T {
    val delegatedValue = delegatingSerializer(readContext).read(readContext)
    return fromDelegateClass(delegatedValue)
  }

  override fun onCollectionWrite(writeContext: WriteContext, value: T): MutableCollection<Any?> {
    throw IllegalStateException("supportCodegenHook is disabled for ${type.name}")
  }

  override fun onCollectionRead(collection: Collection<*>): T {
    throw IllegalStateException("supportCodegenHook is disabled for ${type.name}")
  }

  override fun shareable(): Boolean = true
}

public class UByteArraySerializer(
  typeResolver: TypeResolver,
) :
  AbstractDelegatingArraySerializer<UByteArray, ByteArray>(
    typeResolver,
    UByteArray::class.java,
    ByteArray::class.java
  ) {
  override fun toDelegateClass(value: UByteArray): ByteArray = value.toByteArray()

  override fun fromDelegateClass(value: ByteArray): UByteArray = value.toUByteArray()

  override fun copy(copyContext: CopyContext, value: UByteArray): UByteArray = value.copyOf()
}

public class UShortArraySerializer(
  typeResolver: TypeResolver,
) :
  AbstractDelegatingArraySerializer<UShortArray, ShortArray>(
    typeResolver,
    UShortArray::class.java,
    ShortArray::class.java
  ) {
  override fun toDelegateClass(value: UShortArray): ShortArray = value.toShortArray()

  override fun fromDelegateClass(value: ShortArray): UShortArray = value.toUShortArray()

  override fun copy(copyContext: CopyContext, value: UShortArray): UShortArray = value.copyOf()
}

public class UIntArraySerializer(
  typeResolver: TypeResolver,
) :
  AbstractDelegatingArraySerializer<UIntArray, IntArray>(
    typeResolver,
    UIntArray::class.java,
    IntArray::class.java
  ) {
  override fun toDelegateClass(value: UIntArray): IntArray = value.toIntArray()

  override fun fromDelegateClass(value: IntArray): UIntArray = value.toUIntArray()

  override fun copy(copyContext: CopyContext, value: UIntArray): UIntArray = value.copyOf()
}

public class ULongArraySerializer(
  typeResolver: TypeResolver,
) :
  AbstractDelegatingArraySerializer<ULongArray, LongArray>(
    typeResolver,
    ULongArray::class.java,
    LongArray::class.java
  ) {
  override fun toDelegateClass(value: ULongArray): LongArray = value.toLongArray()

  override fun fromDelegateClass(value: LongArray): ULongArray = value.toULongArray()

  override fun copy(copyContext: CopyContext, value: ULongArray): ULongArray = value.copyOf()
}
