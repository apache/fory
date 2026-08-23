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

package org.apache.fory.json.scala.internal

import java.lang.reflect.Modifier

import org.apache.fory.json.JsonCodecFactory
import org.apache.fory.json.codec.JsonValueCodec
import org.apache.fory.json.resolver.JsonTypeResolver
import org.apache.fory.reflect.TypeRef
import org.apache.fory.serializer.GraphMemoryEstimates

import scala.collection.immutable.NumericRange

private[scala] object ScalaTypeCodecFactory extends JsonCodecFactory {
  override def create(
      typeRef: TypeRef[_],
      resolver: JsonTypeResolver,
      runtimeType: Boolean
  ): JsonValueCodec[_] = {
    val rawType = typeRef.getRawType
    val name = rawType.getName
    val enumerationCodec = ScalaEnumerationTypes.createCodec(typeRef)
    if (enumerationCodec != null) return enumerationCodec

    if (isRejected(rawType)) throw ScalaTypeSupport.unsupported(typeRef, "runtime-state or lazy type")
    if (classOf[Range].isAssignableFrom(rawType)) {
      return new ScalaRangeCodec(name.endsWith("$Exclusive"))
    }
    if (classOf[NumericRange[_]].isAssignableFrom(rawType)) {
      return new ScalaNumericRangeCodec(name.endsWith("$Exclusive"), runtimeType)
    }
    if (rawType == classOf[Option[_]] || name == "scala.Some") {
      return new ScalaOptionCodec(name == "scala.Some", runtimeType)
    }
    if (name == "scala.None$") {
      return ScalaNoneCodec
    }
    if (rawType == classOf[Either[_, _]] || name == "scala.util.Left" || name == "scala.util.Right") {
      val branch = if (name == "scala.util.Left") 1 else if (name == "scala.util.Right") 2 else 0
      return new ScalaEitherCodec(branch, runtimeType)
    }
    if (rawType == classOf[BigInt]) return ScalaBigIntCodec
    if (rawType == classOf[BigDecimal]) return ScalaBigDecimalCodec
    if (rawType == classOf[scala.collection.mutable.StringBuilder]) return ScalaStringBuilderCodec
    if (classOf[scala.concurrent.duration.FiniteDuration].isAssignableFrom(rawType)) {
      return new ScalaDurationCodec(true)
    }
    if (rawType == classOf[scala.concurrent.duration.Duration]) {
      return new ScalaDurationCodec(false)
    }
    if (classOf[scala.collection.immutable.BitSet].isAssignableFrom(rawType)) {
      return new ScalaBitSetCodec(false)
    }
    if (classOf[scala.collection.mutable.BitSet].isAssignableFrom(rawType)) {
      return new ScalaBitSetCodec(true)
    }
    val tupleArity = ScalaTupleCodec.arity(rawType)
    if (tupleArity >= 1) return new ScalaTupleCodec(tupleArity, rawType, runtimeType)
    if (name == "scala.Tuple$package$EmptyTuple$") return new ScalaEmptyTupleCodec(rawType)

    if (resolver.isInferredSubtype(rawType)) {
      val derivedCodec = ScalaDerivedCodec.find(rawType)
      if (derivedCodec == null)
        throw ScalaTypeSupport.unsupported(
          typeRef,
          "sealed hierarchy requires derives ScalaJsonCodec or builder register"
        )
      return derivedCodec.create(typeRef, resolver, runtimeType)
    }

    if (classOf[scala.collection.Map[_, _]].isAssignableFrom(rawType)) {
      val selection = mapKind(rawType, runtimeType)
      if (selection == null)
        throw ScalaTypeSupport.unsupported(typeRef, "collection family requires an exact codec")
      return new ScalaMapCodec(
        selection._1,
        GraphMemoryEstimates.shallowObjectBytes(selection._2),
        runtimeType
      )
    }
    if (classOf[List[_]].isAssignableFrom(rawType) || name == "scala.collection.immutable.Nil$") {
      requireDeclaredClass(typeRef, runtimeType)
      return new ScalaListCodec(name.endsWith("$colon$colon"), name.endsWith("Nil$"), runtimeType)
    }
    if (classOf[scala.collection.Iterable[_]].isAssignableFrom(rawType)) {
      val selection = iterableKind(rawType, runtimeType)
      if (selection == null)
        throw ScalaTypeSupport.unsupported(typeRef, "collection family requires an exact codec")
      return new ScalaIterableCodec(
        selection._1,
        GraphMemoryEstimates.shallowObjectBytes(selection._2),
        runtimeType
      )
    }

    val enumFamily = ScalaEnumCodec.familyRoot(rawType)
    val enumRoot = ScalaEnumCodec.enumRoot(rawType)
    if (
      enumFamily != null && enumRoot == null &&
      (enumFamily == rawType || runtimeType)
    ) {
      val derivedCodec = ScalaDerivedCodec.find(enumFamily)
      if (derivedCodec != null) {
        return derivedCodec.create(TypeRef.of(enumFamily), resolver, runtimeType)
      }
    }
    if (enumRoot != null && (enumRoot == rawType || runtimeType)) {
      return ScalaEnumCodec.create(enumRoot, typeRef)
    }
    if (enumFamily != null) {
      throw ScalaTypeSupport.unsupported(
        typeRef,
        "Scala enum with parameters requires an exact derived or custom codec"
      )
    }
    val singleton = ScalaObjectModels.singletonCodec(typeRef, resolver)
    if (singleton != null) return singleton
    val valueClass = ScalaValueClassCodec.create(rawType, runtimeType)
    if (valueClass != null) return valueClass
    if (ScalaObjectModels.isCaseClass(rawType)) {
      return ScalaObjectModels.caseClassCodec(typeRef, resolver)
    }
    if (name.startsWith("scala.")) {
      throw ScalaTypeSupport.unsupported(typeRef, "recognized Scala type has no default JSON schema")
    }
    null
  }

  private def requireDeclaredClass(typeRef: TypeRef[_], runtimeType: Boolean): Unit = {
    val rawType = typeRef.getRawType
    if (!runtimeType && !Modifier.isPublic(rawType.getModifiers)) {
      throw ScalaTypeSupport.unsupported(typeRef, "non-public implementation is write-only")
    }
  }

  private def iterableKind(rawType: Class[_], runtimeWrite: Boolean): (Int, Class[_]) = {
    import ScalaCollectionCodecs._

    if (rawType == classOf[Vector[_]] || runtimeWrite && classOf[Vector[_]].isAssignableFrom(rawType))
      (VectorKind, classOf[Vector[_]])
    else if (
      rawType == classOf[scala.collection.immutable.ArraySeq[_]] ||
      runtimeWrite && classOf[scala.collection.immutable.ArraySeq[_]].isAssignableFrom(rawType)
    ) (ImmutableArraySeqKind, classOf[scala.collection.immutable.ArraySeq[_]])
    else if (rawType == classOf[scala.collection.immutable.Queue[_]])
      (ImmutableQueueKind, classOf[scala.collection.immutable.Queue[_]])
    else if (rawType == classOf[scala.collection.mutable.ArrayBuffer[_]])
      (MutableArrayBufferKind, classOf[scala.collection.mutable.ArrayBuffer[_]])
    else if (rawType == classOf[scala.collection.mutable.ListBuffer[_]])
      (MutableListBufferKind, classOf[scala.collection.mutable.ListBuffer[_]])
    else if (
      rawType == classOf[scala.collection.mutable.ArraySeq[_]] ||
      runtimeWrite && classOf[scala.collection.mutable.ArraySeq[_]].isAssignableFrom(rawType)
    ) (MutableArraySeqKind, classOf[scala.collection.mutable.ArraySeq[_]])
    else if (rawType == classOf[scala.collection.mutable.ArrayDeque[_]])
      (MutableArrayDequeKind, classOf[scala.collection.mutable.ArrayDeque[_]])
    else if (rawType == classOf[scala.collection.mutable.Queue[_]])
      (MutableQueueKind, classOf[scala.collection.mutable.Queue[_]])
    else if (
      rawType == classOf[scala.collection.immutable.HashSet[_]] ||
      runtimeWrite && classOf[scala.collection.immutable.HashSet[_]].isAssignableFrom(rawType)
    ) (ImmutableHashSetKind, classOf[scala.collection.immutable.HashSet[_]])
    else if (
      rawType == classOf[scala.collection.immutable.ListSet[_]] ||
      runtimeWrite && classOf[scala.collection.immutable.ListSet[_]].isAssignableFrom(rawType)
    ) (ImmutableListSetKind, classOf[scala.collection.immutable.ListSet[_]])
    else if (rawType == classOf[scala.collection.mutable.HashSet[_]])
      (MutableHashSetKind, classOf[scala.collection.mutable.HashSet[_]])
    else if (rawType == classOf[scala.collection.mutable.LinkedHashSet[_]])
      (MutableLinkedHashSetKind, classOf[scala.collection.mutable.LinkedHashSet[_]])
    else if (
      rawType == classOf[scala.collection.Set[_]] ||
      rawType == classOf[scala.collection.immutable.Set[_]] ||
      runtimeWrite && ScalaCollectionCodecs.supportedRuntime(rawType) &&
        classOf[scala.collection.immutable.Set[_]].isAssignableFrom(rawType)
    ) (ImmutableHashSetKind, classOf[scala.collection.immutable.HashSet[_]])
    else if (rawType == classOf[scala.collection.mutable.Set[_]])
      (MutableHashSetKind, classOf[scala.collection.mutable.HashSet[_]])
    else if (
      rawType == classOf[scala.collection.immutable.IndexedSeq[_]] ||
      rawType == classOf[scala.collection.IndexedSeq[_]]
    ) (VectorKind, classOf[Vector[_]])
    else if (
      rawType == classOf[scala.collection.mutable.IndexedSeq[_]] ||
      rawType == classOf[scala.collection.mutable.Seq[_]] ||
      rawType == classOf[scala.collection.mutable.Buffer[_]] ||
      rawType == classOf[scala.collection.mutable.Iterable[_]]
    ) (MutableArrayBufferKind, classOf[scala.collection.mutable.ArrayBuffer[_]])
    else if (
      rawType == classOf[scala.collection.Iterable[_]] ||
      rawType == classOf[scala.collection.Seq[_]] ||
      rawType == classOf[scala.collection.LinearSeq[_]] ||
      rawType == classOf[scala.collection.immutable.Iterable[_]] ||
      rawType == classOf[scala.collection.immutable.Seq[_]] ||
      rawType == classOf[scala.collection.immutable.LinearSeq[_]]
    ) (ListKind, classOf[scala.collection.immutable.::[_]])
    else if (runtimeWrite && ScalaCollectionCodecs.supportedRuntime(rawType)) {
      if (classOf[scala.collection.mutable.Set[_]].isAssignableFrom(rawType))
        (MutableHashSetKind, classOf[scala.collection.mutable.HashSet[_]])
      else if (classOf[scala.collection.mutable.Iterable[_]].isAssignableFrom(rawType))
        (MutableArrayBufferKind, classOf[scala.collection.mutable.ArrayBuffer[_]])
      else (ListKind, classOf[scala.collection.immutable.::[_]])
    } else null
  }

  private def mapKind(rawType: Class[_], runtimeWrite: Boolean): (Int, Class[_]) = {
    import ScalaCollectionCodecs._

    if (
      rawType == classOf[scala.collection.immutable.IntMap[_]] ||
      runtimeWrite && classOf[scala.collection.immutable.IntMap[_]].isAssignableFrom(rawType)
    ) (ImmutableIntMapKind, classOf[scala.collection.immutable.IntMap[_]])
    else if (
      rawType == classOf[scala.collection.immutable.LongMap[_]] ||
      runtimeWrite && classOf[scala.collection.immutable.LongMap[_]].isAssignableFrom(rawType)
    ) (ImmutableLongMapKind, classOf[scala.collection.immutable.LongMap[_]])
    else if (rawType == classOf[scala.collection.mutable.LongMap[_]])
      (MutableLongMapKind, classOf[scala.collection.mutable.LongMap[_]])
    else if (
      rawType == classOf[scala.collection.immutable.VectorMap[_, _]] ||
      runtimeWrite && classOf[scala.collection.immutable.VectorMap[_, _]].isAssignableFrom(rawType)
    ) (ImmutableVectorMapKind, classOf[scala.collection.immutable.VectorMap[_, _]])
    else if (
      rawType == classOf[scala.collection.immutable.ListMap[_, _]] ||
      runtimeWrite && classOf[scala.collection.immutable.ListMap[_, _]].isAssignableFrom(rawType)
    ) (ImmutableListMapKind, classOf[scala.collection.immutable.ListMap[_, _]])
    else if (
      rawType == classOf[scala.collection.immutable.HashMap[_, _]] ||
      runtimeWrite && classOf[scala.collection.immutable.HashMap[_, _]].isAssignableFrom(rawType)
    ) (ImmutableHashMapKind, classOf[scala.collection.immutable.HashMap[_, _]])
    else if (rawType == classOf[scala.collection.mutable.LinkedHashMap[_, _]])
      (MutableLinkedHashMapKind, classOf[scala.collection.mutable.LinkedHashMap[_, _]])
    else if (rawType == classOf[scala.collection.mutable.AnyRefMap[_, _]])
      (MutableAnyRefMapKind, classOf[scala.collection.mutable.AnyRefMap[_, _]])
    else if (rawType == classOf[scala.collection.mutable.HashMap[_, _]])
      (MutableHashMapKind, classOf[scala.collection.mutable.HashMap[_, _]])
    else if (rawType == classOf[scala.collection.immutable.SeqMap[_, _]])
      (ImmutableVectorMapKind, classOf[scala.collection.immutable.VectorMap[_, _]])
    else if (rawType == classOf[scala.collection.mutable.SeqMap[_, _]])
      (MutableLinkedHashMapKind, classOf[scala.collection.mutable.LinkedHashMap[_, _]])
    else if (
      rawType == classOf[scala.collection.Map[_, _]] ||
      rawType == classOf[scala.collection.immutable.Map[_, _]] ||
      runtimeWrite && ScalaCollectionCodecs.supportedRuntime(rawType) &&
        classOf[scala.collection.immutable.Map[_, _]].isAssignableFrom(rawType)
    ) (ImmutableHashMapKind, classOf[scala.collection.immutable.HashMap[_, _]])
    else if (rawType == classOf[scala.collection.mutable.Map[_, _]])
      (MutableHashMapKind, classOf[scala.collection.mutable.HashMap[_, _]])
    else if (runtimeWrite && ScalaCollectionCodecs.supportedRuntime(rawType)) {
      if (classOf[scala.collection.mutable.Map[_, _]].isAssignableFrom(rawType))
        (MutableHashMapKind, classOf[scala.collection.mutable.HashMap[_, _]])
      else (ImmutableHashMapKind, classOf[scala.collection.immutable.HashMap[_, _]])
    } else null
  }

  private def isRejected(rawType: Class[_]): Boolean = {
    val name = rawType.getName
    name.startsWith("scala.collection.immutable.LazyList") ||
    name.startsWith("scala.collection.immutable.Stream") ||
    name.startsWith("scala.collection.View") ||
    classOf[Iterator[_]].isAssignableFrom(rawType) ||
    name.startsWith("scala.util.Try") ||
    name.startsWith("scala.util.Success") ||
    name.startsWith("scala.util.Failure") ||
    name.startsWith("scala.concurrent.Future") ||
    name.startsWith("scala.concurrent.Promise") ||
    name.startsWith("scala.concurrent.ExecutionContext") ||
    name.startsWith("scala.concurrent.duration.Deadline") ||
    name.startsWith("scala.util.matching.Regex") ||
    name == "scala.Symbol" ||
    name.startsWith("scala.Function") ||
    name.startsWith("scala.PartialFunction") ||
    name.startsWith("scala.reflect.") ||
    name.startsWith("scala.quoted.") ||
    name.startsWith("scala.math.Numeric") ||
    name.startsWith("scala.math.Integral") ||
    name.startsWith("scala.math.Fractional") ||
    name.startsWith("scala.math.Ordering")
  }
}
