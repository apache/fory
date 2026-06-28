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
import org.apache.fory.serializer.collection.{CollectionLikeSerializer, MapLikeSerializer}

import java.util
import scala.collection.mutable
import scala.collection.{immutable => simmutable}

abstract class AbstractScalaXlangCollectionSerializer[A, T <: scala.collection.Iterable[A]](
    typeResolver: TypeResolver,
    cls: Class[T])
  extends CollectionLikeSerializer[T](typeResolver, cls) {

  override def onCollectionWrite(writeContext: WriteContext, value: T): util.Collection[_] = {
    writeContext.getBuffer.writeVarUInt32Small7(value.size)
    if (ScalaXlangCollectionShape.hasOptionElement(writeContext)) {
      new XlangOptionCollectionAdapter[A](value)
    } else {
      new XlangCollectionAdapter[A](value)
    }
  }

  override def newCollection(readContext: ReadContext): util.Collection[_] = {
    val buffer = readContext.getBuffer
    val numElements = readCollectionSize(buffer)
    setNumElements(numElements)
    if (numElements != 0) {
      buffer.checkReadableBytes(numElements)
    }
    val builder = newBuilder(numElements)
    if (ScalaXlangCollectionShape.hasOptionElement(readContext)) {
      new XlangOptionCollectionBuilder[A, T](builder)
    } else {
      new XlangCollectionBuilder[A, T](builder)
    }
  }

  protected def newBuilder(numElements: Int): mutable.Builder[A, T]

  override def onCollectionRead(collection: util.Collection[_]): T = {
    collection.asInstanceOf[XlangBuilderResult[T]].result()
  }

  override def copy(copyContext: CopyContext, value: T): T = {
    if (isImmutable) {
      value
    } else if (
        value.isInstanceOf[mutable.IndexedSeq[_]] &&
        !value.isInstanceOf[mutable.Growable[_]]) {
      copyIndexedSeq(
        copyContext,
        value,
        value.asInstanceOf[mutable.IndexedSeq[A]])
    } else if (value.isInstanceOf[mutable.Iterable[_]]) {
      newMutableCopy(value, value.size) match {
        case result: mutable.Iterable[_] with mutable.Growable[_] =>
          val growable = result.asInstanceOf[mutable.Iterable[A] with mutable.Growable[A]]
          copyContext.reference(value, growable.asInstanceOf[T])
          copyElements(copyContext, value, growable)
          growable.asInstanceOf[T]
        case _ =>
          copyWithBuilder(copyContext, value, value.iterableFactory.newBuilder[A])
      }
    } else {
      copyWithBuilder(copyContext, value, newBuilder(value.size))
    }
  }

  protected def newMutableCopy(value: T, numElements: Int): scala.collection.Iterable[A] = {
    val builder = value.iterableFactory.newBuilder[A]
    builder.sizeHint(numElements)
    builder.result()
  }

  private def copyElements(
      copyContext: CopyContext,
      value: T,
      result: mutable.Growable[A]): Unit = {
    val iterator = value.iterator
    while (iterator.hasNext) {
      result.addOne(copyContext.copyObject(iterator.next()).asInstanceOf[A])
    }
  }

  private def copyWithBuilder(
      copyContext: CopyContext,
      value: T,
      builder: mutable.Builder[A, _ <: scala.collection.Iterable[A]]): T = {
    val iterator = value.iterator
    while (iterator.hasNext) {
      builder.addOne(copyContext.copyObject(iterator.next()).asInstanceOf[A])
    }
    val result = builder.result().asInstanceOf[T]
    copyContext.reference(value, result)
    result
  }

  private def copyIndexedSeq(
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

class ScalaXlangSeqSerializer[A, T <: scala.collection.Seq[A]](
    typeResolver: TypeResolver,
    cls: Class[T])
  extends AbstractScalaXlangCollectionSerializer[A, T](typeResolver, cls) {
  override protected def newBuilder(numElements: Int): mutable.Builder[A, T] = {
    ScalaXlangCollectionShape.seqBuilder[A, T](cls, numElements)
  }

}

class ScalaXlangSetSerializer[A, T <: scala.collection.Set[A]](
    typeResolver: TypeResolver,
    cls: Class[T])
  extends AbstractScalaXlangCollectionSerializer[A, T](typeResolver, cls) {
  override protected def newBuilder(numElements: Int): mutable.Builder[A, T] = {
    ScalaXlangCollectionShape.setBuilder[A, T](cls, numElements)
  }

}

class ScalaXlangCollectionSerializer[A, T <: scala.collection.Iterable[A]](
    typeResolver: TypeResolver,
    cls: Class[T])
  extends AbstractScalaXlangCollectionSerializer[A, T](typeResolver, cls) {
  override protected def newBuilder(numElements: Int): mutable.Builder[A, T] = {
    ScalaXlangCollectionShape.iterableBuilder[A, T](cls, numElements)
  }

}

private object ScalaXlangCollectionShape {
  def hasOptionElement(writeContext: WriteContext): Boolean = {
    val genericType = writeContext.getGenerics.nextGenericType(writeContext.getDepth)
    genericType != null && isExplicitNullable(genericType.getTypeParameter0)
  }

  def hasOptionElement(readContext: ReadContext): Boolean = {
    val genericType = readContext.getGenerics.nextGenericType(readContext.getDepth)
    genericType != null && isExplicitNullable(genericType.getTypeParameter0)
  }

  def hasOptionKey(writeContext: WriteContext): Boolean = {
    val genericType = writeContext.getGenerics.nextGenericType(writeContext.getDepth)
    genericType != null &&
    genericType.getTypeParametersCount >= 2 &&
    isExplicitNullable(genericType.getTypeParameter0)
  }

  def hasOptionValue(writeContext: WriteContext): Boolean = {
    val genericType = writeContext.getGenerics.nextGenericType(writeContext.getDepth)
    genericType != null &&
    genericType.getTypeParametersCount >= 2 &&
    isExplicitNullable(genericType.getTypeParameter1)
  }

  def hasOptionKey(readContext: ReadContext): Boolean = {
    val genericType = readContext.getGenerics.nextGenericType(readContext.getDepth)
    genericType != null &&
    genericType.getTypeParametersCount >= 2 &&
    isExplicitNullable(genericType.getTypeParameter0)
  }

  def hasOptionValue(readContext: ReadContext): Boolean = {
    val genericType = readContext.getGenerics.nextGenericType(readContext.getDepth)
    genericType != null &&
    genericType.getTypeParametersCount >= 2 &&
    isExplicitNullable(genericType.getTypeParameter1)
  }

  def seqBuilder[A, T](declared: Class[_], size: Int): mutable.Builder[A, T] = {
    val builder =
      if (accepts(declared, classOf[mutable.ArrayBuffer[_]])) {
        mutable.ArrayBuffer.newBuilder[A]
      } else if (accepts(declared, classOf[simmutable.Vector[_]])) {
        simmutable.Vector.newBuilder[A]
      } else if (accepts(declared, classOf[simmutable.List[_]])) {
        simmutable.List.newBuilder[A]
      } else {
        unsupported("sequence", declared)
      }
    builder.sizeHint(size)
    builder.asInstanceOf[mutable.Builder[A, T]]
  }

  def iterableBuilder[A, T](declared: Class[_], size: Int): mutable.Builder[A, T] = {
    val builder =
      if (accepts(declared, classOf[mutable.ArrayBuffer[_]])) {
        mutable.ArrayBuffer.newBuilder[A]
      } else if (accepts(declared, classOf[simmutable.List[_]])) {
        simmutable.List.newBuilder[A]
      } else {
        unsupported("iterable", declared)
      }
    builder.sizeHint(size)
    builder.asInstanceOf[mutable.Builder[A, T]]
  }

  def setBuilder[A, T](declared: Class[_], size: Int): mutable.Builder[A, T] = {
    val builder =
      if (accepts(declared, classOf[mutable.HashSet[_]])) {
        mutable.HashSet.newBuilder[A]
      } else if (accepts(declared, classOf[simmutable.HashSet[_]])) {
        simmutable.HashSet.newBuilder[A]
      } else if (accepts(declared, classOf[simmutable.Set[_]])) {
        simmutable.Set.newBuilder[A]
      } else {
        unsupported("set", declared)
      }
    builder.sizeHint(size)
    builder.asInstanceOf[mutable.Builder[A, T]]
  }

  def mapBuilder[K, V, T](declared: Class[_], size: Int): mutable.Builder[(K, V), T] = {
    val builder =
      if (accepts(declared, classOf[mutable.HashMap[_, _]])) {
        mutable.HashMap.newBuilder[K, V]
      } else if (accepts(declared, classOf[simmutable.HashMap[_, _]])) {
        simmutable.HashMap.newBuilder[K, V]
      } else if (accepts(declared, classOf[simmutable.Map[_, _]])) {
        simmutable.Map.newBuilder[K, V]
      } else {
        unsupported("map", declared)
      }
    builder.sizeHint(size)
    builder.asInstanceOf[mutable.Builder[(K, V), T]]
  }

  private def isExplicitNullable(genericType: org.apache.fory.`type`.GenericType): Boolean =
    genericType != null &&
      genericType.getTypeRef.getTypeExtMeta != null &&
      genericType.getTypeRef.getTypeExtMeta.nullableWrapper()

  private def accepts(declared: Class[_], result: Class[_]): Boolean =
    declared.isAssignableFrom(result)

  private def unsupported(kind: String, declared: Class[_]): Nothing = {
    throw new IllegalArgumentException(
      "Scala xlang " + kind + " serializer cannot rebuild declared type " +
        declared.getName +
        ". Use a supported immutable collection type or a mutable collection interface.")
  }
}

private final class XlangCollectionAdapter[A](coll: scala.collection.Iterable[A])
  extends util.AbstractCollection[A] {
  override def iterator(): util.Iterator[A] = new util.Iterator[A] {
    private val it = coll.iterator

    override def hasNext: Boolean = it.hasNext

    override def next(): A = it.next()
  }

  override def size(): Int = coll.size
}

private final class XlangOptionCollectionAdapter[A](coll: scala.collection.Iterable[A])
  extends util.AbstractCollection[Any] {
  override def iterator(): util.Iterator[Any] = new util.Iterator[Any] {
    private val it = coll.iterator

    override def hasNext: Boolean = it.hasNext

    override def next(): Any = {
      val value = it.next()
      if (value == null) null else value.asInstanceOf[Option[_]].getOrElse(null)
    }
  }

  override def size(): Int = coll.size
}

private trait XlangBuilderResult[T] {
  def result(): T
}

private final class XlangCollectionBuilder[A, T](val builder: mutable.Builder[A, T])
  extends util.AbstractCollection[A]
  with XlangBuilderResult[T] {
  override def add(e: A): Boolean = {
    builder.addOne(e)
    true
  }

  override def iterator(): util.Iterator[A] =
    throw new UnsupportedOperationException("Scala xlang collection builder is write-only")

  override def size(): Int =
    throw new UnsupportedOperationException("Scala xlang collection builder is write-only")

  override def result(): T = builder.result()
}

private final class XlangOptionCollectionBuilder[A, T](val builder: mutable.Builder[A, T])
  extends util.AbstractCollection[Any]
  with XlangBuilderResult[T] {
  override def add(e: Any): Boolean = {
    builder.addOne(Option(e).asInstanceOf[A])
    true
  }

  override def iterator(): util.Iterator[Any] =
    throw new UnsupportedOperationException("Scala xlang collection builder is write-only")

  override def size(): Int =
    throw new UnsupportedOperationException("Scala xlang collection builder is write-only")

  override def result(): T = builder.result()
}

abstract class AbstractScalaXlangMapSerializer[K, V, T <: scala.collection.Map[K, V]](
    typeResolver: TypeResolver,
    cls: Class[T])
  extends MapLikeSerializer[T](typeResolver, cls) {

  override def onMapWrite(writeContext: WriteContext, value: T): util.Map[_, _] = {
    writeContext.getBuffer.writeVarUInt32Small7(value.size)
    val optionKey = ScalaXlangCollectionShape.hasOptionKey(writeContext)
    val optionValue = ScalaXlangCollectionShape.hasOptionValue(writeContext)
    if (optionKey || optionValue) {
      new XlangOptionMapAdapter[K, V](value, optionKey, optionValue)
    } else {
      new XlangMapAdapter[K, V](value)
    }
  }

  override def newMap(readContext: ReadContext): util.Map[_, _] = {
    val buffer = readContext.getBuffer
    val numElements = readMapSize(buffer)
    setNumElements(numElements)
    if (numElements != 0) {
      buffer.checkReadableBytes(numElements)
    }
    val builder =
      ScalaXlangCollectionShape.mapBuilder[K, V, T](cls, numElements)
    val optionKey = ScalaXlangCollectionShape.hasOptionKey(readContext)
    val optionValue = ScalaXlangCollectionShape.hasOptionValue(readContext)
    if (optionKey || optionValue) {
      new XlangOptionMapBuilder[K, V, T](builder, optionKey, optionValue)
    } else {
      new XlangMapBuilder[K, V, T](builder)
    }
  }

  override def onMapRead(map: util.Map[_, _]): T = {
    map.asInstanceOf[XlangBuilderResult[T]].result()
  }

  override def onMapCopy(map: util.Map[_, _]): T = onMapRead(map)

  override def copy(copyContext: CopyContext, value: T): T = {
    if (isImmutable) {
      value
    } else if (value.isInstanceOf[mutable.Map[_, _]]) {
      newMutableMapCopy(value, value.size) match {
        case result: mutable.Map[_, _] =>
          val mutableResult = result.asInstanceOf[mutable.Map[K, V]]
          copyContext.reference(value, mutableResult.asInstanceOf[T])
          copyEntries(copyContext, value, mutableResult)
          mutableResult.asInstanceOf[T]
        case _ =>
          copyWithBuilder(copyContext, value, value.mapFactory.newBuilder[K, V])
      }
    } else {
      copyWithBuilder(copyContext, value, ScalaXlangCollectionShape.mapBuilder[K, V, T](cls, value.size))
    }
  }

  private def newMutableMapCopy(value: T, numElements: Int): scala.collection.Map[K, V] = {
    val builder = value.mapFactory.newBuilder[K, V]
    builder.sizeHint(numElements)
    builder.result()
  }

  private def copyEntries(
      copyContext: CopyContext,
      value: T,
      result: mutable.Map[K, V]): Unit = {
    val iterator = value.iterator
    while (iterator.hasNext) {
      val entry = iterator.next()
      result.addOne(
        (
          copyContext.copyObject(entry._1).asInstanceOf[K],
          copyContext.copyObject(entry._2).asInstanceOf[V]))
    }
  }

  private def copyWithBuilder(
      copyContext: CopyContext,
      value: T,
      builder: mutable.Builder[(K, V), _ <: scala.collection.Map[K, V]])
      : T = {
    val iterator = value.iterator
    while (iterator.hasNext) {
      val entry = iterator.next()
      builder.addOne(
        (
          copyContext.copyObject(entry._1).asInstanceOf[K],
          copyContext.copyObject(entry._2).asInstanceOf[V]))
    }
    val result = builder.result().asInstanceOf[T]
    copyContext.reference(value, result)
    result
  }
}

class ScalaXlangMapSerializer[K, V, T <: scala.collection.Map[K, V]](
    typeResolver: TypeResolver,
    cls: Class[T])
  extends AbstractScalaXlangMapSerializer[K, V, T](typeResolver, cls)

private final class XlangMapAdapter[K, V](map: scala.collection.Map[K, V])
  extends util.AbstractMap[K, V] {
  override def entrySet(): util.Set[util.Map.Entry[K, V]] =
    new util.AbstractSet[util.Map.Entry[K, V]] {
      override def size(): Int = map.size

      override def iterator(): util.Iterator[util.Map.Entry[K, V]] =
        new util.Iterator[util.Map.Entry[K, V]] {
          private val it = map.iterator

          override def hasNext: Boolean = it.hasNext

          override def next(): util.Map.Entry[K, V] = {
            val entry = it.next()
            new org.apache.fory.collection.MapEntry[K, V](entry._1, entry._2)
          }
        }
    }
}

private final class XlangOptionMapAdapter[K, V](
    map: scala.collection.Map[K, V],
    optionKey: Boolean,
    optionValue: Boolean)
  extends util.AbstractMap[Any, Any] {
  override def entrySet(): util.Set[util.Map.Entry[Any, Any]] =
    new util.AbstractSet[util.Map.Entry[Any, Any]] {
      override def size(): Int = map.size

      override def iterator(): util.Iterator[util.Map.Entry[Any, Any]] =
        new util.Iterator[util.Map.Entry[Any, Any]] {
          private val it = map.iterator

          override def hasNext: Boolean = it.hasNext

          override def next(): util.Map.Entry[Any, Any] = {
            val entry = it.next()
            new org.apache.fory.collection.MapEntry[Any, Any](
              unwrap(entry._1, optionKey),
              unwrap(entry._2, optionValue))
          }
        }
    }

  private def unwrap(value: Any, option: Boolean): Any = {
    if (option && value != null) value.asInstanceOf[Option[_]].getOrElse(null) else value
  }
}

private final class XlangMapBuilder[K, V, T](val builder: mutable.Builder[(K, V), T])
  extends util.AbstractMap[K, V]
  with XlangBuilderResult[T] {
  override def entrySet(): util.Set[util.Map.Entry[K, V]] =
    throw new UnsupportedOperationException("Scala xlang map builder is write-only")

  override def put(key: K, value: V): V = {
    builder.addOne((key, value))
    value
  }

  override def result(): T = builder.result()
}

private final class XlangOptionMapBuilder[K, V, T](
    val builder: mutable.Builder[(K, V), T],
    optionKey: Boolean,
    optionValue: Boolean)
  extends util.AbstractMap[Any, Any]
  with XlangBuilderResult[T] {
  override def entrySet(): util.Set[util.Map.Entry[Any, Any]] =
    throw new UnsupportedOperationException("Scala xlang map builder is write-only")

  override def put(key: Any, value: Any): Any = {
    builder.addOne((wrap(key, optionKey).asInstanceOf[K], wrap(value, optionValue).asInstanceOf[V]))
    value
  }

  private def wrap(value: Any, option: Boolean): Any =
    if (option) Option(value) else value

  override def result(): T = builder.result()
}

final class ScalaOptionSerializer(typeResolver: TypeResolver, cls: Class[_])
  extends Serializer[Option[Any]](typeResolver.getConfig, cls.asInstanceOf[Class[Option[Any]]]) {
  override def write(writeContext: WriteContext, value: Option[Any]): Unit = {
    writeContext.writeRef(value.orNull)
  }

  override def read(readContext: ReadContext): Option[Any] = {
    Option(readContext.readRef())
  }

  override def copy(copyContext: CopyContext, value: Option[Any]): Option[Any] = {
    value.map(copyContext.copyObject(_))
  }
}
