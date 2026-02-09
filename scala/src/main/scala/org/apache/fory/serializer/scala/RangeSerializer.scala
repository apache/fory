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

import org.apache.fory.Fory
import org.apache.fory.memory.MemoryBuffer
import org.apache.fory.reflect.FieldAccessor
import org.apache.fory.serializer.Serializer
import org.apache.fory.serializer.collection.CollectionLikeSerializer
import org.apache.fory.util.unsafe._JDKAccess

import java.lang.invoke.{MethodHandle, MethodHandles}
import java.util
import scala.collection.immutable.NumericRange

class RangeSerializer[T <: Range](fory: Fory, cls: Class[T])
  extends CollectionLikeSerializer[T](fory, cls, false) {

  override def write(buffer: MemoryBuffer, value: T): Unit = {
    buffer.writeVarInt32(value.start)
    buffer.writeVarInt32(value.end)
    buffer.writeVarInt32(value.step)
  }
  override def read(buffer: MemoryBuffer): T = {
    val start = buffer.readVarInt32()
    val end = buffer.readVarInt32()
    val step = buffer.readVarInt32()
    if (this.cls == classOf[Range.Exclusive]) {
      Range.apply(start, end, step).asInstanceOf[T]
    } else {
      Range.inclusive(start, end, step).asInstanceOf[T]
    }
  }

  override def onCollectionWrite(memoryBuffer: MemoryBuffer, t:  T): util.Collection[_] = ???

  override def onCollectionRead(collection: util.Collection[_]):  T = ???
}


private object RangeUtils {
   val lookupCache: ClassValue[MethodHandle] = new ClassValue[MethodHandle]() {
    override protected def computeValue(cls: Class[_]): MethodHandle = {
      val lookup: MethodHandles.Lookup = _JDKAccess._trustedLookup(cls)
      lookup.unreflectConstructor(cls.getDeclaredConstructors()(0))
    }
  }
}


class NumericRangeSerializer[A, T <: NumericRange[A]](fory: Fory, cls: Class[T])
  extends CollectionLikeSerializer[T](fory, cls, false) {
  private val ctr = RangeUtils.lookupCache.get(cls)
  private val getter = FieldAccessor.createAccessor(cls.getDeclaredFields.find(f => f.getType == classOf[Integral[?]]).get)

  override def write(buffer: MemoryBuffer, value: T): Unit = {
    val cls = value.start.getClass
    val resolver = fory.getClassResolver
    val classInfo = resolver.getTypeInfo(cls)
    resolver.writeTypeInfo(buffer, classInfo)
    val serializer = classInfo.getSerializer.asInstanceOf[Serializer[A]]
    serializer.write(buffer, value.start)
    serializer.write(buffer, value.end)
    serializer.write(buffer, value.step)
    fory.writeRef(buffer, getter.get(value))
  }

  override def read(buffer: MemoryBuffer) = {
    val resolver = fory.getClassResolver
    val classInfo = resolver.readTypeInfo(buffer)
    val serializer = classInfo.getSerializer.asInstanceOf[Serializer[A]]
    val start = serializer.read(buffer)
    val end = serializer.read(buffer)
    val step = serializer.read(buffer)
    ctr.invoke(start, end, step, fory.readRef(buffer))
  }

  override def onCollectionRead(collection: util.Collection[_]) = ???

  override def onCollectionWrite(memoryBuffer: MemoryBuffer, t: T) = ???
}
