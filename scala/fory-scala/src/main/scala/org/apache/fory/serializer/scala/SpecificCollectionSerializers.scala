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

import org.apache.fory.context.{CopyContext, ReadContext, WriteContext}
import org.apache.fory.resolver.TypeResolver
import org.apache.fory.serializer.Serializer
import org.apache.fory.serializer.Serializers.StringBuilderSerializer
import org.apache.fory.serializer.collection.CollectionLikeSerializer
import org.apache.fory.serializer.GraphMemoryEstimates

import java.util
import scala.collection.{Factory, immutable, mutable}
import scala.reflect.ClassTag

/**
 * Native-mode serializers for Scala collection kinds that the generic collection and map
 * serializers would rebuild as a different class. Those serializers write the value's generic
 * factory (`iterableFactory`, `sortedIterableFactory` or `mapFactory`), which builds, for example,
 * a `Map` from an `IntMap` or a `TreeSet` from a `BitSet`.
 */
object SpecificCollectionSerializers {
  private val immutableArraySeqTags: Map[Class[_], ClassTag[_]] = Map(
    classOf[immutable.ArraySeq.ofByte] -> ClassTag.Byte,
    classOf[immutable.ArraySeq.ofShort] -> ClassTag.Short,
    classOf[immutable.ArraySeq.ofChar] -> ClassTag.Char,
    classOf[immutable.ArraySeq.ofInt] -> ClassTag.Int,
    classOf[immutable.ArraySeq.ofLong] -> ClassTag.Long,
    classOf[immutable.ArraySeq.ofFloat] -> ClassTag.Float,
    classOf[immutable.ArraySeq.ofDouble] -> ClassTag.Double,
    classOf[immutable.ArraySeq.ofBoolean] -> ClassTag.Boolean)

  private val mutableArraySeqTags: Map[Class[_], ClassTag[_]] = Map(
    classOf[mutable.ArraySeq.ofByte] -> ClassTag.Byte,
    classOf[mutable.ArraySeq.ofShort] -> ClassTag.Short,
    classOf[mutable.ArraySeq.ofChar] -> ClassTag.Char,
    classOf[mutable.ArraySeq.ofInt] -> ClassTag.Int,
    classOf[mutable.ArraySeq.ofLong] -> ClassTag.Long,
    classOf[mutable.ArraySeq.ofFloat] -> ClassTag.Float,
    classOf[mutable.ArraySeq.ofDouble] -> ClassTag.Double,
    classOf[mutable.ArraySeq.ofBoolean] -> ClassTag.Boolean)

  /** Returns the serializer for `cls`, or null when the generic serializers handle it. */
  def createSerializer(typeResolver: TypeResolver, cls: Class[_]): Serializer[_] = {
    if (cls == classOf[mutable.StringBuilder]) {
      new ScalaStringBuilderSerializer(typeResolver)
    } else if (classOf[immutable.IntMap[_]].isAssignableFrom(cls)) {
      mapSerializer(typeResolver, cls, immutable.IntMap.toFactory[Any](immutable.IntMap))
    } else if (classOf[immutable.LongMap[_]].isAssignableFrom(cls)) {
      mapSerializer(typeResolver, cls, immutable.LongMap.toFactory[Any](immutable.LongMap))
    } else if (classOf[mutable.LongMap[_]].isAssignableFrom(cls)) {
      mapSerializer(typeResolver, cls, mutable.LongMap.toFactory[Any](mutable.LongMap))
    } else if (classOf[immutable.BitSet].isAssignableFrom(cls)) {
      collectionSerializer(typeResolver, cls, immutable.BitSet)
    } else if (classOf[mutable.BitSet].isAssignableFrom(cls)) {
      collectionSerializer(typeResolver, cls, mutable.BitSet)
    } else {
      val immutableTag = immutableArraySeqTags.getOrElse(cls, null)
      if (immutableTag != null) {
        collectionSerializer(
          typeResolver,
          cls,
          immutable.ArraySeq.evidenceIterableFactory(immutableTag.asInstanceOf[ClassTag[Any]]))
      } else {
        val mutableTag = mutableArraySeqTags.getOrElse(cls, null)
        if (mutableTag != null) {
          collectionSerializer(
            typeResolver,
            cls,
            mutable.ArraySeq.evidenceIterableFactory(mutableTag.asInstanceOf[ClassTag[Any]]))
        } else {
          null
        }
      }
    }
  }

  private def collectionSerializer(
      typeResolver: TypeResolver,
      cls: Class[_],
      factory: Factory[_, _]): Serializer[_] = {
    new ScalaSpecificCollectionSerializer[Any, Iterable[Any]](
      typeResolver,
      cls.asInstanceOf[Class[Iterable[Any]]],
      factory.asInstanceOf[Factory[Any, Iterable[Any]]])
  }

  private def mapSerializer(
      typeResolver: TypeResolver,
      cls: Class[_],
      factory: Factory[_, _]): Serializer[_] = {
    new ScalaSpecificMapSerializer[Any, Any, scala.collection.Map[Any, Any]](
      typeResolver,
      cls.asInstanceOf[Class[scala.collection.Map[Any, Any]]],
      factory.asInstanceOf[Factory[(Any, Any), scala.collection.Map[Any, Any]]])
  }
}

/**
 * Serializer for [[scala.collection.mutable.StringBuilder]]. It writes the wrapped
 * `java.lang.StringBuilder` as a string, using the Java `StringBuilder` encoding, instead of
 * writing the characters as a `Seq[Char]`. It extends [[CollectionLikeSerializer]] with the
 * codegen hook disabled because struct fields route every Scala `Iterable` through the collection
 * serializer path.
 */
class ScalaStringBuilderSerializer(typeResolver: TypeResolver)
  extends CollectionLikeSerializer[mutable.StringBuilder](
    typeResolver,
    classOf[mutable.StringBuilder],
    false) {
  private val underlyingSerializer = new StringBuilderSerializer(typeResolver.getConfig)
  private val graphMemoryBytes =
    GraphMemoryEstimates.shallowObjectBytes(classOf[mutable.StringBuilder])

  override def write(writeContext: WriteContext, value: mutable.StringBuilder): Unit =
    underlyingSerializer.write(writeContext, value.underlying)

  override def read(readContext: ReadContext): mutable.StringBuilder = {
    readContext.reserveGraphMemory(graphMemoryBytes)
    new mutable.StringBuilder(underlyingSerializer.read(readContext))
  }

  override def copy(
      copyContext: CopyContext,
      value: mutable.StringBuilder): mutable.StringBuilder =
    new mutable.StringBuilder(new java.lang.StringBuilder(value.underlying))

  override def onCollectionWrite(
      writeContext: WriteContext,
      value: mutable.StringBuilder): util.Collection[_] =
    throw new IllegalStateException(s"supportCodegenHook is disabled for ${getType.getName}")

  override def onCollectionRead(collection: util.Collection[_]): mutable.StringBuilder =
    throw new IllegalStateException(s"supportCodegenHook is disabled for ${getType.getName}")
}
