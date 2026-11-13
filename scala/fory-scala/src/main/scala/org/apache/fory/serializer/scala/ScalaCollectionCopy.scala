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

import org.apache.fory.context.CopyContext
import org.apache.fory.exception.CopyException

import scala.collection.mutable

/** Shared copy implementation for native and cross-language Scala collection serializers. */
private[scala] object ScalaCollectionCopy {
  def copyIterable[A, T <: scala.collection.Iterable[A]](
      copyContext: CopyContext,
      value: T,
      immutable: Boolean,
      newBuilder: (T, Int) => mutable.Builder[A, T],
      newMutableCopy: (T, Int) => scala.collection.Iterable[A]): T = {
    if (immutable) {
      value
    } else if (
        value.isInstanceOf[mutable.IndexedSeq[_]] &&
        !value.isInstanceOf[mutable.Growable[_]]) {
      copyIndexedSeq(copyContext, value, value.asInstanceOf[mutable.IndexedSeq[A]])
    } else if (value.isInstanceOf[mutable.Iterable[_]]) {
      newMutableCopy(value, value.size) match {
        case result: scala.collection.Iterable[_] with mutable.Growable[_] =>
          val copied = result.asInstanceOf[scala.collection.Iterable[A] with mutable.Growable[A]]
          copyContext.reference(value, copied.asInstanceOf[T])
          val iterator = value.iterator
          while (iterator.hasNext) {
            copied.addOne(copyContext.copyObject(iterator.next()).asInstanceOf[A])
          }
          copied.asInstanceOf[T]
        case _ =>
          throw new CopyException(
            s"Mutable Scala collection ${value.getClass.getName} did not produce a growable copy")
      }
    } else {
      copyWithBuilder(copyContext, value, newBuilder(value, value.size))
    }
  }

  def copyMap[K, V, T <: scala.collection.Map[K, V]](
      copyContext: CopyContext,
      value: T,
      immutable: Boolean,
      newBuilder: (T, Int) => mutable.Builder[(K, V), T],
      newMutableCopy: (T, Int) => scala.collection.Map[K, V]): T = {
    if (immutable) {
      value
    } else if (value.isInstanceOf[mutable.Map[_, _]]) {
      newMutableCopy(value, value.size) match {
        case result: scala.collection.Map[_, _] with mutable.Growable[_] =>
          val copied = result.asInstanceOf[scala.collection.Map[K, V] with mutable.Growable[(K, V)]]
          copyContext.reference(value, copied.asInstanceOf[T])
          val iterator = value.iterator
          while (iterator.hasNext) {
            val entry = iterator.next()
            copied.addOne(
              (
                copyContext.copyObject(entry._1).asInstanceOf[K],
                copyContext.copyObject(entry._2).asInstanceOf[V]))
          }
          copied.asInstanceOf[T]
        case _ =>
          throw new CopyException(
            s"Mutable Scala map ${value.getClass.getName} did not produce a growable copy")
      }
    } else {
      copyMapWithBuilder(copyContext, value, newBuilder(value, value.size))
    }
  }

  private def copyWithBuilder[A, T <: scala.collection.Iterable[A]](
      copyContext: CopyContext,
      value: T,
      builder: mutable.Builder[A, T]): T = {
    val iterator = value.iterator
    while (iterator.hasNext) {
      builder.addOne(copyContext.copyObject(iterator.next()).asInstanceOf[A])
    }
    failIfCopiedDuringCycle(copyContext, value)
    val result = builder.result()
    copyContext.reference(value, result)
    result
  }

  private def copyMapWithBuilder[K, V, T <: scala.collection.Map[K, V]](
      copyContext: CopyContext,
      value: T,
      builder: mutable.Builder[(K, V), T]): T = {
    val iterator = value.iterator
    while (iterator.hasNext) {
      val entry = iterator.next()
      builder.addOne(
        (
          copyContext.copyObject(entry._1).asInstanceOf[K],
          copyContext.copyObject(entry._2).asInstanceOf[V]))
    }
    failIfCopiedDuringCycle(copyContext, value)
    val result = builder.result()
    copyContext.reference(value, result)
    result
  }

  private def failIfCopiedDuringCycle(copyContext: CopyContext, value: AnyRef): Unit = {
    if (copyContext.copyTrackingRef() && copyContext.getCopyObject(value) != null) {
      throw new CopyException(
        "Cannot copy cyclic object graph rooted at constructor-owned immutable value " +
          value.getClass.getName +
          " because its copy cannot be referenced before construction completes")
    }
  }

  private def copyIndexedSeq[A, T <: scala.collection.Iterable[A]](
      copyContext: CopyContext,
      value: T,
      indexed: mutable.IndexedSeq[A]): T = {
    val result = indexed match {
      case arraySeq: mutable.ArraySeq[_] =>
        val sourceArray = arraySeq.array.asInstanceOf[AnyRef]
        val array =
          java.lang.reflect.Array.newInstance(sourceArray.getClass.getComponentType, indexed.size)
        mutable.ArraySeq.make(array.asInstanceOf[Array[_]]).asInstanceOf[mutable.IndexedSeq[A]]
      case _ =>
        mutable.ArraySeq.make(new Array[Any](indexed.size)).asInstanceOf[mutable.IndexedSeq[A]]
    }
    val copied = result.asInstanceOf[T]
    copyContext.reference(value, copied)
    var i = 0
    while (i < indexed.size) {
      result.update(i, copyContext.copyObject(indexed(i)).asInstanceOf[A])
      i += 1
    }
    copied
  }
}
