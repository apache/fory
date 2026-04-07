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

package org.apache.fory.serializer.scala

import org.apache.fory.context.ReadContext
import org.apache.fory.context.WriteContext
import org.apache.fory.reflect.FieldAccessor
import org.apache.fory.serializer.Serializer
import org.apache.fory.serializer.collection.CollectionLikeSerializer
import org.apache.fory.resolver.TypeResolver
import java.util
import org.apache.fory.util.unsafe._JDKAccess

import java.lang.invoke.{MethodHandle, MethodHandles}
import scala.collection.immutable.NumericRange

class RangeSerializer[T <: Range](typeResolver: TypeResolver, cls: Class[T])
  extends CollectionLikeSerializer[T](typeResolver, cls, false) {
  private val rangeClass = cls

  override def write(writeContext: WriteContext, value: T): Unit = {
    val buffer = writeContext.getBuffer
    buffer.writeVarInt32(value.start)
    buffer.writeVarInt32(value.end)
    buffer.writeVarInt32(value.step)
  }
  override def read(readContext: ReadContext): T = {
    val buffer = readContext.getBuffer
    val start = buffer.readVarInt32()
    val end = buffer.readVarInt32()
    val step = buffer.readVarInt32()
    if (rangeClass == classOf[Range.Exclusive]) {
      Range.apply(start, end, step).asInstanceOf[T]
    } else {
      Range.inclusive(start, end, step).asInstanceOf[T]
    }
  }

  override def shareable(): Boolean = true

  override def onCollectionWrite(writeContext: WriteContext, value: T): util.Collection[_] =
    throw new IllegalStateException(s"supportCodegenHook is disabled for ${getType.getName}")

  override def onCollectionRead(collection: util.Collection[_]): T =
    throw new IllegalStateException(s"supportCodegenHook is disabled for ${getType.getName}")
}


private object RangeUtils {
   val lookupCache: ClassValue[MethodHandle] = new ClassValue[MethodHandle]() {
    override protected def computeValue(cls: Class[_]): MethodHandle = {
      val lookup: MethodHandles.Lookup = _JDKAccess._trustedLookup(cls)
      lookup.unreflectConstructor(cls.getDeclaredConstructors()(0))
    }
  }
}


class NumericRangeSerializer[A, T <: NumericRange[A]](typeResolver: TypeResolver, cls: Class[T])
  extends CollectionLikeSerializer[T](typeResolver, cls, false) {
  private val ctr = RangeUtils.lookupCache.get(cls)
  private val getter =
    FieldAccessor.createAccessor(
      cls.getDeclaredFields.find(f => f.getType == classOf[Integral[?]]).get)

  override def write(writeContext: WriteContext, value: T): Unit = {
    val cls = value.start.getClass
    val resolver = writeContext.getTypeResolver
    val classInfo = resolver.getTypeInfo(cls)
    resolver.writeTypeInfo(writeContext, classInfo)
    val serializer = classInfo.getSerializer.asInstanceOf[Serializer[A]]
    serializer.write(writeContext, value.start)
    serializer.write(writeContext, value.end)
    serializer.write(writeContext, value.step)
    writeContext.writeRef(getter.get(value))
  }

  override def read(readContext: ReadContext) = {
    val resolver = readContext.getTypeResolver
    val classInfo = resolver.readTypeInfo(readContext)
    val serializer = classInfo.getSerializer.asInstanceOf[Serializer[A]]
    val start = serializer.read(readContext)
    val end = serializer.read(readContext)
    val step = serializer.read(readContext)
    ctr.invoke(start, end, step, readContext.readRef()).asInstanceOf[T]
  }

  override def shareable(): Boolean = true

  override def onCollectionWrite(writeContext: WriteContext, value: T): util.Collection[_] =
    throw new IllegalStateException(s"supportCodegenHook is disabled for ${getType.getName}")

  override def onCollectionRead(collection: util.Collection[_]): T =
    throw new IllegalStateException(s"supportCodegenHook is disabled for ${getType.getName}")
}
