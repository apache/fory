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

package org.apache.fory.scala.internal

import org.apache.fory.annotation.{ForyCase, ForyField, ForyStruct, ForyUnion, ForyUnknownCase}
import org.apache.fory.meta.{TypeDef => ForyTypeDef, TypeExtMeta}
import org.apache.fory.resolver.TypeResolver
import org.apache.fory.scala.ForySerializer
import org.apache.fory.serializer.{
  FieldGroups,
  Serializer,
  StaticGeneratedStructSerializer,
  UnionSerializer
}
import org.apache.fory.serializer.converter.FieldConverters
import org.apache.fory.`type`.{Descriptor, ScalaTypes, Types}

import java.lang.reflect.Modifier
import scala.quoted.*

object ForySerializerMacros {
  def derive[T: Type](using q: Quotes): Expr[ForySerializer[T]] = {
    import q.reflect.*
    val symbol = TypeRepr.of[T].typeSymbol
    if hasAnnotation[ForyUnion](symbol) then deriveUnion[T](symbol)
    else deriveStruct[T](symbol)
  }

  private def deriveStruct[T: Type](using q: Quotes)(
      owner: q.reflect.Symbol): Expr[ForySerializer[T]] = {
    import q.reflect.*

    final case class FieldMeta(
        symbol: Symbol,
        name: String,
        index: Int,
        fieldId: Int,
        sourceType: TypeRepr,
        wireType: TypeRepr,
        option: Boolean,
        nullable: Boolean,
        trackingRef: Boolean,
        hasTrackingRefMetadata: Boolean,
        constructorOwned: Boolean,
        hasDefault: Boolean,
        directReadable: Boolean,
        directWritable: Boolean) {
      def usesFieldAccessor: Boolean =
        !constructorOwned && (!directReadable || !directWritable)
    }

    if !hasAnnotation[ForyStruct](owner) then {
      report.errorAndAbort(
        s"${owner.fullName} must be annotated with @ForyStruct to derive ForySerializer")
    }
    val ownerClassName = owner.fullName.replace("$.", "$")

    def optionElement(tpe: TypeRepr): Option[TypeRepr] = {
      peelAnnotations(tpe)._1.dealias match {
        case AppliedType(base, List(arg)) if base.typeSymbol.fullName == "scala.Option" =>
          Some(arg)
        case _ => None
      }
    }

    def peelAnnotations(tpe: TypeRepr): (TypeRepr, List[Term]) = {
      tpe match {
        case AnnotatedType(underlying, annotation) =>
          val (base, annotations) = peelAnnotations(underlying)
          (base, annotation :: annotations)
        case other =>
          other.dealias match {
            case AnnotatedType(underlying, annotation) =>
              val (base, annotations) = peelAnnotations(underlying)
              (base, annotation :: annotations)
            case dealiased => (dealiased, Nil)
          }
      }
    }

    def boxedIfPrimitive(tpe: TypeRepr): TypeRepr = {
      val (base, annotations) = peelAnnotations(tpe)
      val boxed =
        if base =:= TypeRepr.of[Boolean] then TypeRepr.of[java.lang.Boolean]
        else if base =:= TypeRepr.of[Byte] then TypeRepr.of[java.lang.Byte]
        else if base =:= TypeRepr.of[Short] then TypeRepr.of[java.lang.Short]
        else if base =:= TypeRepr.of[Int] then TypeRepr.of[java.lang.Integer]
        else if base =:= TypeRepr.of[Long] then TypeRepr.of[java.lang.Long]
        else if base =:= TypeRepr.of[Float] then TypeRepr.of[java.lang.Float]
        else if base =:= TypeRepr.of[Double] then TypeRepr.of[java.lang.Double]
        else base
      annotations.foldRight(boxed)((annotation, current) => AnnotatedType(current, annotation))
    }

    def classFor(tpe: TypeRepr): Expr[Class[?]] = {
      val normalized = peelAnnotations(tpe.widen)._1.dealias
      val fullName = normalized.typeSymbol.fullName
      if normalized =:= TypeRepr.of[Boolean] then '{ java.lang.Boolean.TYPE }
      else if normalized =:= TypeRepr.of[Byte] then '{ java.lang.Byte.TYPE }
      else if normalized =:= TypeRepr.of[Short] then '{ java.lang.Short.TYPE }
      else if normalized =:= TypeRepr.of[Int] then '{ java.lang.Integer.TYPE }
      else if normalized =:= TypeRepr.of[Long] then '{ java.lang.Long.TYPE }
      else if normalized =:= TypeRepr.of[Float] then '{ java.lang.Float.TYPE }
      else if normalized =:= TypeRepr.of[Double] then '{ java.lang.Double.TYPE }
      else if normalized =:= TypeRepr.of[Char] then '{ java.lang.Character.TYPE }
      else if normalized =:= TypeRepr.of[String] ||
          normalized.typeSymbol == TypeRepr.of[String].typeSymbol ||
          fullName == "scala.Predef.String" ||
          fullName == "scala.Predef$.String" ||
          fullName.endsWith("Predef.String") ||
          fullName.endsWith("Predef$.String")
      then '{ classOf[String] }
      else if fullName == "scala.Array" then {
        '{ Class.forName(${ Expr(arrayClassName(normalized)) }) }
      } else '{ Class.forName(${ Expr(fullName.replace("$.", "$")) }) }
    }

    def arrayClassName(tpe: TypeRepr): String = {
      tpe.dealias match {
        case AppliedType(arrayType, List(componentType))
            if arrayType.typeSymbol.fullName == "scala.Array" =>
          "[" + arrayComponentDescriptor(componentType)
        case _ =>
          report.errorAndAbort(s"Expected Scala Array type, got ${tpe.show}")
      }
    }

    def arrayComponentDescriptor(tpe: TypeRepr): String = {
      val normalized = peelAnnotations(tpe.widen)._1.dealias
      if normalized =:= TypeRepr.of[Boolean] then "Z"
      else if normalized =:= TypeRepr.of[Byte] then "B"
      else if normalized =:= TypeRepr.of[Short] then "S"
      else if normalized =:= TypeRepr.of[Int] then "I"
      else if normalized =:= TypeRepr.of[Long] then "J"
      else if normalized =:= TypeRepr.of[Float] then "F"
      else if normalized =:= TypeRepr.of[Double] then "D"
      else if normalized =:= TypeRepr.of[Char] then "C"
      else if normalized.typeSymbol.fullName == "scala.Array" then arrayClassName(normalized)
      else "L" + normalized.typeSymbol.fullName.replace("$.", "$") + ";"
    }

    val constructorFields = {
      val params = owner.primaryConstructor.paramSymss.flatten.filter(_.isValDef)
      if params.nonEmpty then params else owner.caseFields
    }
    val constructorFieldSet = constructorFields.toSet
    val bodyFields = {
      val candidates = owner.fieldMembers.filter { field =>
        !constructorFieldSet.contains(field) &&
        !field.flags.is(Flags.Synthetic) &&
        !field.name.contains("$") &&
        (!field.flags.is(Flags.Private) || annotationIntArg[ForyField](field, "id").nonEmpty)
      }
      val selected =
        if constructorFields.isEmpty then candidates
        else candidates.filter(field => annotationIntArg[ForyField](field, "id").nonEmpty)
      selected
    }
    val serializableFields = constructorFields ++ bodyFields

    def declaredType(symbol: Symbol): TypeRepr = {
      symbol.tree match {
        case ValDef(_, tpt, _) => tpt.tpe
        case _ => symbol.termRef.widen
      }
    }

    val fields = serializableFields.zipWithIndex.map { (field, index) =>
      val sourceType = declaredType(field)
      val (wireType, option, nullable) = optionElement(sourceType) match {
        case Some(inner) => (boxedIfPrimitive(inner), true, true)
        case None => (sourceType, false, false)
      }
      val refTracking = refAnnotation(field).orElse(topLevelTypeRefTracking(sourceType))
      val constructorOwned = constructorFieldSet.contains(field)
      val privateField = field.flags.is(Flags.Private)
      FieldMeta(
        field,
        field.name,
        index,
        annotationIntArg[ForyField](field, "id").getOrElse(-1),
        sourceType,
        wireType,
        option,
        nullable,
        refTracking.getOrElse(false),
        refTracking.nonEmpty,
        constructorOwned,
        field.flags.is(Flags.HasDefault),
        !privateField,
        constructorOwned || (field.flags.is(Flags.Mutable) && !privateField))
    }
    val hasNestedCompatibleStructFields =
      fields.exists(field => hasNestedCompatibleStruct(field.sourceType))

    def generatedType(tpe: TypeRepr): Expr[Descriptor.GeneratedType] = {
      val (outer, outerAnnotations) = peelAnnotations(tpe)
      val option = optionElement(outer)
      val fieldSource = option.map(boxedIfPrimitive).getOrElse(outer)
      val (base, baseAnnotations) = peelAnnotations(fieldSource)
      val optionInnerAnnotations = option.toList.flatMap(inner => peelAnnotations(inner)._2)
      val annotations = outerAnnotations ++ baseAnnotations ++ optionInnerAnnotations
      val argumentSource = fieldSource
      def appliedType(tpe: TypeRepr): Option[(TypeRepr, List[TypeRepr])] = {
        val directArgs = tpe.typeArgs
        if directArgs.nonEmpty then {
          tpe match {
            case AppliedType(typeConstructor, _) => Some((typeConstructor, directArgs))
            case other =>
              other.dealias match {
                case AppliedType(typeConstructor, _) => Some((typeConstructor, directArgs))
                case _ => Some((tpe, directArgs))
              }
          }
        } else {
          tpe match {
            case AppliedType(typeConstructor, typeArgs) => Some((typeConstructor, typeArgs))
            case other =>
              other.dealias match {
                case AppliedType(typeConstructor, typeArgs) => Some((typeConstructor, typeArgs))
                case _ => None
              }
          }
        }
      }
      val component = appliedType(argumentSource) match {
        case Some((arrayType, List(componentType)))
            if arrayType.typeSymbol.fullName == "scala.Array" =>
          Some(generatedType(componentType))
        case _ => None
      }
      val args = appliedType(argumentSource) match {
        case Some((arrayType, List(_))) if arrayType.typeSymbol.fullName == "scala.Array" =>
          Nil
        case Some((_, typeArgs)) => typeArgs
        case _ => Nil
      }
      val argExprs = args.map(generatedType)
      val argList: Expr[java.util.List[Descriptor.GeneratedType]] =
        '{
          import scala.jdk.CollectionConverters.*
          java.util.Collections.unmodifiableList(${ Expr.ofList(argExprs) }.asJava)
        }
      val componentExpr: Expr[Descriptor.GeneratedType] =
        component.getOrElse('{ null.asInstanceOf[Descriptor.GeneratedType] })
      val typeId =
        annotations
          .flatMap(typeIdForAnnotation)
          .headOption
          .orElse(option.map(inner => wireTypeId(peelAnnotations(boxedIfPrimitive(inner))._1)))
          .orElse {
            // The owning field supplies the union schema, so field TypeMeta uses
            // UNION. TYPED_UNION/NAMED_UNION are reserved for root or dynamic Any
            // values without static field schema.
            if hasAnnotation[ForyUnion](base.typeSymbol) then Some(Types.UNION) else None
          }
          .orElse {
            if isScalaEnumType(base) then Some(Types.ENUM) else None
          }
          .getOrElse(Types.UNKNOWN)
      val rawClass = classFor(base)
      val typeExtMeta = generatedTypeExtMeta(
        typeId,
        nullable = option.nonEmpty,
        trackingRef = refTrackingFromAnnotations(annotations).getOrElse(false),
        hasTrackingRefMetadata = refTrackingFromAnnotations(annotations).nonEmpty,
        nullableWrapper = option.nonEmpty,
        rawClass = Some(rawClass))
      '{ Descriptor.generatedType($rawClass, $typeExtMeta, $argList, $componentExpr) }
    }

    def wireTypeId(tpe: TypeRepr): Int = {
      val normalized = peelAnnotations(tpe.widen)._1.dealias
      if normalized =:= TypeRepr.of[Boolean] then Types.BOOL
      else if normalized =:= TypeRepr.of[Byte] then Types.INT8
      else if normalized =:= TypeRepr.of[Short] then Types.INT16
      else if normalized =:= TypeRepr.of[Int] then Types.INT32
      else if normalized =:= TypeRepr.of[Long] then Types.INT64
      else if normalized =:= TypeRepr.of[Float] then Types.FLOAT32
      else if normalized =:= TypeRepr.of[Double] then Types.FLOAT64
      else {
        val fullName = normalized.typeSymbol.fullName
        if normalized =:= TypeRepr.of[String] ||
            normalized.typeSymbol == TypeRepr.of[String].typeSymbol ||
            fullName == "scala.Predef.String" ||
            fullName == "scala.Predef$.String" ||
            fullName.endsWith("Predef.String") ||
            fullName.endsWith("Predef$.String")
        then Types.STRING
        else Types.UNKNOWN
      }
    }

    def descriptor(field: FieldMeta): Expr[Descriptor] = {
      '{
        new Descriptor(
          ${ generatedType(field.sourceType) },
          ${ Expr(field.sourceType.show) },
          ${ Expr(field.name) },
          ${ Expr(Modifier.PRIVATE | Modifier.FINAL) },
          ${ Expr(ownerClassName) },
          true,
          ${ Expr(field.fieldId) },
          ${ Expr(field.nullable) },
          ${ Expr(field.trackingRef) },
          ${ Expr(field.hasTrackingRefMetadata) },
          ForyField.Dynamic.AUTO,
          false
        )
      }
    }

    val descriptorsExpr: Expr[java.util.List[Descriptor]] = {
      val exprs = fields.map(descriptor)
      '{
        import scala.jdk.CollectionConverters.*
        java.util.Collections.unmodifiableList(${ Expr.ofList(exprs) }.asJava)
      }
    }

    def selectValue(
        valueExpr: Expr[T],
        field: FieldMeta,
        fieldAccessorsExpr: Expr[Array[org.apache.fory.reflect.FieldAccessor]]): Expr[Any] =
      if field.usesFieldAccessor then {
        '{ $fieldAccessorsExpr(${ Expr(field.index) }).getObject($valueExpr) }
      } else {
        Select.unique(valueExpr.asTerm, field.name).asExpr
      }

    def writeDispatch(
        valueExpr: Expr[T],
        fieldIdExpr: Expr[Int],
        fieldInfoExpr: Expr[FieldGroups.SerializationFieldInfo],
        writeContextExpr: Expr[org.apache.fory.context.WriteContext],
        resolverExpr: Expr[TypeResolver],
        fieldAccessorsExpr: Expr[Array[org.apache.fory.reflect.FieldAccessor]]): Expr[Unit] = {
      fields.foldRight(
        '{
          throw new IllegalStateException("Unknown generated Scala field id " + $fieldIdExpr)
        }: Expr[Unit]) { (field, next) =>
        val fieldValue = selectValue(valueExpr, field, fieldAccessorsExpr)
        val wireValue =
          if field.option then '{ $fieldValue.asInstanceOf[Option[Any]].orNull }
          else fieldValue
        '{
          if $fieldIdExpr == ${ Expr(field.index) } then {
            StaticGeneratedStructSerializer.writeFieldValue(
              $resolverExpr,
              $writeContextExpr,
              $fieldInfoExpr,
              $wireValue)
          } else {
            $next
          }
        }
      }
    }

    def decodeValue(raw: Expr[Any], field: FieldMeta): Expr[Any] = {
      if field.option then {
        field.sourceType.asType match {
          case '[a] => '{ Option($raw).asInstanceOf[a] }
        }
      } else {
        field.sourceType.asType match {
          case '[a] => '{ $raw.asInstanceOf[a] }
        }
      }
    }

    def valueArg(valuesExpr: Expr[Array[Any]], field: FieldMeta): Expr[Any] =
      decodeValue('{ $valuesExpr(${ Expr(field.index) }) }, field)

    def localDefault(field: FieldMeta): Term = {
      if field.option then {
        field.sourceType.asType match {
          case '[a] => '{ None.asInstanceOf[a] }.asTerm
        }
      } else {
        val normalized = peelAnnotations(field.sourceType.widen)._1.dealias
        if normalized =:= TypeRepr.of[Boolean] then '{ false }.asTerm
        else if normalized =:= TypeRepr.of[Byte] then '{ 0.toByte }.asTerm
        else if normalized =:= TypeRepr.of[Short] then '{ 0.toShort }.asTerm
        else if normalized =:= TypeRepr.of[Int] then '{ 0 }.asTerm
        else if normalized =:= TypeRepr.of[Long] then '{ 0L }.asTerm
        else if normalized =:= TypeRepr.of[Float] then '{ 0.0f }.asTerm
        else if normalized =:= TypeRepr.of[Double] then '{ 0.0d }.asTerm
        else if normalized =:= TypeRepr.of[Char] then '{ 0.toChar }.asTerm
        else {
          field.sourceType.asType match {
            case '[a] => '{ null.asInstanceOf[a] }.asTerm
          }
        }
      }
    }

    def assignRawValue(
        objExpr: Expr[T],
        field: FieldMeta,
        raw: Expr[Any],
        fieldAccessorsExpr: Expr[Array[org.apache.fory.reflect.FieldAccessor]]): Expr[Unit] =
      if field.usesFieldAccessor then {
        val decoded = decodeValue(raw, field)
        '{
          $fieldAccessorsExpr(${ Expr(field.index) })
            .putObject($objExpr, $decoded.asInstanceOf[AnyRef])
        }
      } else {
        Assign(Select.unique(objExpr.asTerm, field.name), decodeValue(raw, field).asTerm)
          .asExprOf[Unit]
      }

    def assignLocalValue(local: Symbol, field: FieldMeta, raw: Expr[Any]): Term =
      Assign(Ref(local), decodeValue(raw, field).asTerm)

    def assignLocalSource(local: Symbol, field: FieldMeta, raw: Expr[Any]): Expr[Unit] =
      field.sourceType.asType match {
        case '[a] => Assign(Ref(local), '{ $raw.asInstanceOf[a] }.asTerm).asExprOf[Unit]
      }

    def assignValueById(
        objExpr: Expr[T],
        fieldIdExpr: Expr[Int],
        raw: Expr[Any],
        fieldAccessorsExpr: Expr[Array[org.apache.fory.reflect.FieldAccessor]]): Expr[Unit] = {
      val cases = fields.map { field =>
        CaseDef(
          Literal(IntConstant(field.index)),
          None,
          assignRawValue(objExpr, field, raw, fieldAccessorsExpr).asTerm)
      } :+ CaseDef(
        Wildcard(),
        None,
        '{
          throw new IllegalStateException("Unknown generated Scala field id " + $fieldIdExpr)
        }.asTerm)
      Match(fieldIdExpr.asTerm, cases).asExprOf[Unit]
    }

    def compatibleScalarValue(
        field: FieldMeta,
        fieldInfoExpr: Expr[FieldGroups.SerializationFieldInfo],
        remoteFieldExpr: Expr[StaticGeneratedStructSerializer.RemoteFieldInfo],
        readContextExpr: Expr[org.apache.fory.context.ReadContext]): Option[Expr[Any]] = {
      val targetType = optionElement(field.sourceType).getOrElse(field.sourceType)
      val normalized = peelAnnotations(targetType.widen)._1.dealias
      val boxed = field.option
      normalized.asType match {
        case '[Boolean] =>
          Some(
            if boxed then
              '{
                FieldConverters.readBoxedBooleanTarget(
                  $readContextExpr,
                  $remoteFieldExpr.serializationFieldInfo,
                  $fieldInfoExpr)
              }.asExprOf[Any]
            else
              '{
                FieldConverters.readBooleanTarget(
                  $readContextExpr,
                  $remoteFieldExpr.serializationFieldInfo,
                  $fieldInfoExpr)
              }.asExprOf[Any])
        case '[java.lang.Boolean] =>
          Some(
            '{
              FieldConverters.readBoxedBooleanTarget(
                $readContextExpr,
                $remoteFieldExpr.serializationFieldInfo,
                $fieldInfoExpr)
            }.asExprOf[Any])
        case '[Byte] =>
          Some(
            if boxed then
              '{
                FieldConverters.readBoxedByteTarget(
                  $readContextExpr,
                  $remoteFieldExpr.serializationFieldInfo,
                  $fieldInfoExpr)
              }.asExprOf[Any]
            else
              '{
                FieldConverters.readByteTarget(
                  $readContextExpr,
                  $remoteFieldExpr.serializationFieldInfo,
                  $fieldInfoExpr)
              }.asExprOf[Any])
        case '[java.lang.Byte] =>
          Some(
            '{
              FieldConverters.readBoxedByteTarget(
                $readContextExpr,
                $remoteFieldExpr.serializationFieldInfo,
                $fieldInfoExpr)
            }.asExprOf[Any])
        case '[Short] =>
          Some(
            if boxed then
              '{
                FieldConverters.readBoxedShortTarget(
                  $readContextExpr,
                  $remoteFieldExpr.serializationFieldInfo,
                  $fieldInfoExpr)
              }.asExprOf[Any]
            else
              '{
                FieldConverters.readShortTarget(
                  $readContextExpr,
                  $remoteFieldExpr.serializationFieldInfo,
                  $fieldInfoExpr)
              }.asExprOf[Any])
        case '[java.lang.Short] =>
          Some(
            '{
              FieldConverters.readBoxedShortTarget(
                $readContextExpr,
                $remoteFieldExpr.serializationFieldInfo,
                $fieldInfoExpr)
            }.asExprOf[Any])
        case '[Int] =>
          Some(
            if boxed then
              '{
                FieldConverters.readBoxedIntTarget(
                  $readContextExpr,
                  $remoteFieldExpr.serializationFieldInfo,
                  $fieldInfoExpr)
              }.asExprOf[Any]
            else
              '{
                FieldConverters.readIntTarget(
                  $readContextExpr,
                  $remoteFieldExpr.serializationFieldInfo,
                  $fieldInfoExpr)
              }.asExprOf[Any])
        case '[java.lang.Integer] =>
          Some(
            '{
              FieldConverters.readBoxedIntTarget(
                $readContextExpr,
                $remoteFieldExpr.serializationFieldInfo,
                $fieldInfoExpr)
            }.asExprOf[Any])
        case '[Long] =>
          Some(
            if boxed then
              '{
                FieldConverters.readBoxedLongTarget(
                  $readContextExpr,
                  $remoteFieldExpr.serializationFieldInfo,
                  $fieldInfoExpr)
              }.asExprOf[Any]
            else
              '{
                FieldConverters.readLongTarget(
                  $readContextExpr,
                  $remoteFieldExpr.serializationFieldInfo,
                  $fieldInfoExpr)
              }.asExprOf[Any])
        case '[java.lang.Long] =>
          Some(
            '{
              FieldConverters.readBoxedLongTarget(
                $readContextExpr,
                $remoteFieldExpr.serializationFieldInfo,
                $fieldInfoExpr)
            }.asExprOf[Any])
        case '[Float] =>
          Some(
            if boxed then
              '{
                FieldConverters.readBoxedFloatTarget(
                  $readContextExpr,
                  $remoteFieldExpr.serializationFieldInfo,
                  $fieldInfoExpr)
              }.asExprOf[Any]
            else
              '{
                FieldConverters.readFloatTarget(
                  $readContextExpr,
                  $remoteFieldExpr.serializationFieldInfo,
                  $fieldInfoExpr)
              }.asExprOf[Any])
        case '[java.lang.Float] =>
          Some(
            '{
              FieldConverters.readBoxedFloatTarget(
                $readContextExpr,
                $remoteFieldExpr.serializationFieldInfo,
                $fieldInfoExpr)
            }.asExprOf[Any])
        case '[Double] =>
          Some(
            if boxed then
              '{
                FieldConverters.readBoxedDoubleTarget(
                  $readContextExpr,
                  $remoteFieldExpr.serializationFieldInfo,
                  $fieldInfoExpr)
              }.asExprOf[Any]
            else
              '{
                FieldConverters.readDoubleTarget(
                  $readContextExpr,
                  $remoteFieldExpr.serializationFieldInfo,
                  $fieldInfoExpr)
              }.asExprOf[Any])
        case '[java.lang.Double] =>
          Some(
            '{
              FieldConverters.readBoxedDoubleTarget(
                $readContextExpr,
                $remoteFieldExpr.serializationFieldInfo,
                $fieldInfoExpr)
            }.asExprOf[Any])
        case '[String] =>
          Some(
            '{
              FieldConverters.readStringTarget(
                $readContextExpr,
                $remoteFieldExpr.serializationFieldInfo,
                $fieldInfoExpr)
            }.asExprOf[Any])
        case '[java.math.BigDecimal] =>
          Some(
            '{
              FieldConverters.readDecimalTarget(
                $readContextExpr,
                $remoteFieldExpr.serializationFieldInfo,
                $fieldInfoExpr)
            }.asExprOf[Any])
        case '[org.apache.fory.`type`.unsigned.UInt8] =>
          Some(
            '{
              FieldConverters.readUInt8Target(
                $readContextExpr,
                $remoteFieldExpr.serializationFieldInfo,
                $fieldInfoExpr)
            }.asExprOf[Any])
        case '[org.apache.fory.`type`.unsigned.UInt16] =>
          Some(
            '{
              FieldConverters.readUInt16Target(
                $readContextExpr,
                $remoteFieldExpr.serializationFieldInfo,
                $fieldInfoExpr)
            }.asExprOf[Any])
        case '[org.apache.fory.`type`.unsigned.UInt32] =>
          Some(
            '{
              FieldConverters.readUInt32Target(
                $readContextExpr,
                $remoteFieldExpr.serializationFieldInfo,
                $fieldInfoExpr)
            }.asExprOf[Any])
        case '[org.apache.fory.`type`.unsigned.UInt64] =>
          Some(
            '{
              FieldConverters.readUInt64Target(
                $readContextExpr,
                $remoteFieldExpr.serializationFieldInfo,
                $fieldInfoExpr)
            }.asExprOf[Any])
        case '[org.apache.fory.`type`.Float16] =>
          Some(
            '{
              FieldConverters.readFloat16Target(
                $readContextExpr,
                $remoteFieldExpr.serializationFieldInfo,
                $fieldInfoExpr)
            }.asExprOf[Any])
        case '[org.apache.fory.`type`.BFloat16] =>
          Some(
            '{
              FieldConverters.readBFloat16Target(
                $readContextExpr,
                $remoteFieldExpr.serializationFieldInfo,
                $fieldInfoExpr)
            }.asExprOf[Any])
        case _ => None
      }
    }

    def compatibleValue(
        serializerExpr: Expr[StaticGeneratedStructSerializer[T]],
        field: FieldMeta,
        fieldInfoExpr: Expr[FieldGroups.SerializationFieldInfo],
        remoteFieldExpr: Expr[StaticGeneratedStructSerializer.RemoteFieldInfo],
        readContextExpr: Expr[org.apache.fory.context.ReadContext]): Expr[Any] =
      compatibleScalarValue(field, fieldInfoExpr, remoteFieldExpr, readContextExpr).getOrElse {
        '{
          $serializerExpr.readCompatibleFieldValue(
            $readContextExpr,
            $remoteFieldExpr,
            $fieldInfoExpr)
        }
      }

    def readAndAssignDispatch(
        objExpr: Expr[T],
        fieldIdExpr: Expr[Int],
        fieldInfoExpr: Expr[FieldGroups.SerializationFieldInfo],
        readContextExpr: Expr[org.apache.fory.context.ReadContext],
        resolverExpr: Expr[TypeResolver],
        fieldAccessorsExpr: Expr[Array[org.apache.fory.reflect.FieldAccessor]]): Expr[Unit] = {
      '{
        val fieldValue =
          StaticGeneratedStructSerializer.readFieldValue($resolverExpr, $readContextExpr, $fieldInfoExpr)
        ${ assignValueById(objExpr, fieldIdExpr, 'fieldValue, fieldAccessorsExpr) }
      }
    }

    def compatibleAssignByMatchedId(
        serializerExpr: Expr[StaticGeneratedStructSerializer[T]],
        resolverExpr: Expr[TypeResolver],
        fieldsByIdExpr: Expr[Array[FieldGroups.SerializationFieldInfo]],
        objExpr: Expr[T],
        matchedIdExpr: Expr[Int],
        remoteFieldExpr: Expr[StaticGeneratedStructSerializer.RemoteFieldInfo],
        readContextExpr: Expr[org.apache.fory.context.ReadContext],
        fieldAccessorsExpr: Expr[Array[org.apache.fory.reflect.FieldAccessor]]): Expr[Unit] = {
      val fieldCases = fields.flatMap { field =>
        val fieldInfoExpr = '{ $fieldsByIdExpr(${ Expr(field.index) }) }
        val directValue =
          '{
            StaticGeneratedStructSerializer.readFieldValue(
              $resolverExpr,
              $readContextExpr,
              $fieldInfoExpr)
          }
        val compatibleFieldValue =
          compatibleValue(serializerExpr, field, fieldInfoExpr, remoteFieldExpr, readContextExpr)
        List(
          CaseDef(
            Literal(IntConstant(field.index * 2)),
            None,
            assignRawValue(objExpr, field, directValue, fieldAccessorsExpr).asTerm),
          CaseDef(
            Literal(IntConstant(field.index * 2 + 1)),
            None,
            assignRawValue(objExpr, field, compatibleFieldValue, fieldAccessorsExpr).asTerm))
      }
      val skipCase =
        CaseDef(
          Literal(IntConstant(-1)),
          None,
          '{ $serializerExpr.skipField($readContextExpr, $remoteFieldExpr) }.asTerm)
      val invalidCase =
        CaseDef(
          Wildcard(),
          None,
          '{
            throw new IllegalStateException("Invalid compatible matched id " + $matchedIdExpr)
          }.asTerm)
      Match(matchedIdExpr.asTerm, fieldCases :+ skipCase :+ invalidCase).asExprOf[Unit]
    }

    def compatibleLocalsByMatchedId(
        serializerExpr: Expr[StaticGeneratedStructSerializer[T]],
        resolverExpr: Expr[TypeResolver],
        fieldsByIdExpr: Expr[Array[FieldGroups.SerializationFieldInfo]],
        localFields: Seq[(FieldMeta, Symbol)],
        markPresent: FieldMeta => Term,
        matchedIdExpr: Expr[Int],
        remoteFieldExpr: Expr[StaticGeneratedStructSerializer.RemoteFieldInfo],
        readContextExpr: Expr[org.apache.fory.context.ReadContext]): Expr[Unit] = {
      val fieldCases = localFields.flatMap { (field, local) =>
        val fieldInfoExpr = '{ $fieldsByIdExpr(${ Expr(field.index) }) }
        val directValue =
          '{
            StaticGeneratedStructSerializer.readFieldValue(
              $resolverExpr,
              $readContextExpr,
              $fieldInfoExpr)
          }
        val compatibleFieldValue =
          compatibleValue(serializerExpr, field, fieldInfoExpr, remoteFieldExpr, readContextExpr)
        List(
          CaseDef(
            Literal(IntConstant(field.index * 2)),
            None,
            Block(
              assignLocalValue(local, field, directValue) :: markPresent(field) :: Nil,
              '{ () }.asTerm)),
          CaseDef(
            Literal(IntConstant(field.index * 2 + 1)),
            None,
            Block(
              assignLocalValue(local, field, compatibleFieldValue) :: markPresent(field) :: Nil,
              '{ () }.asTerm)))
      }.toList
      val skipCase =
        CaseDef(
          Literal(IntConstant(-1)),
          None,
          '{ $serializerExpr.skipField($readContextExpr, $remoteFieldExpr) }.asTerm)
      val invalidCase =
        CaseDef(
          Wildcard(),
          None,
          '{
            throw new IllegalStateException("Invalid compatible matched id " + $matchedIdExpr)
          }.asTerm)
      Match(matchedIdExpr.asTerm, fieldCases :+ skipCase :+ invalidCase).asExprOf[Unit]
    }

    def assignPostConstruction(
        obj: Term,
        field: FieldMeta,
        value: Term,
        fieldAccessorsExpr: Expr[Array[org.apache.fory.reflect.FieldAccessor]]): Term =
      if field.usesFieldAccessor then {
        '{
          $fieldAccessorsExpr(${ Expr(field.index) })
            .putObject(${ obj.asExprOf[T] }, ${ value.asExpr }.asInstanceOf[AnyRef])
        }.asTerm
      } else {
        Assign(Select.unique(obj, field.name), value)
      }

    def constructFromLocals(
        localFields: Seq[(FieldMeta, Symbol)],
        instantiatorExpr: Expr[org.apache.fory.reflect.ObjectInstantiator[T]],
        fieldAccessorsExpr: Expr[Array[org.apache.fory.reflect.FieldAccessor]]): Expr[T] = {
      val constructorOwned = fields.filter(_.constructorOwned)
      val postConstruction = fields.filterNot(_.constructorOwned)
      val localById = localFields.map { (field, local) => field.index -> local }.toMap
      def localRef(field: FieldMeta): Term = Ref(localById(field.index))
      if postConstruction.isEmpty then {
        val args = constructorOwned.map(localRef)
        Apply(Select(New(TypeTree.of[T]), owner.primaryConstructor), args).asExprOf[T]
      } else if constructorOwned.isEmpty then {
        val obj =
          Symbol.newVal(Symbol.spliceOwner, "obj", TypeRepr.of[T], Flags.EmptyFlags, Symbol.noSymbol)
        val construct = '{ $instantiatorExpr.newInstance() }.asTerm
        val assignments = postConstruction.map { field =>
          assignPostConstruction(Ref(obj), field, localRef(field), fieldAccessorsExpr)
        }
        Block(ValDef(obj, Some(construct)) :: assignments, Ref(obj)).asExprOf[T]
      } else {
        val obj =
          Symbol.newVal(Symbol.spliceOwner, "obj", TypeRepr.of[T], Flags.EmptyFlags, Symbol.noSymbol)
        val args = constructorOwned.map(localRef)
        val construct = Apply(Select(New(TypeTree.of[T]), owner.primaryConstructor), args)
        val assignments = postConstruction.map { field =>
          assignPostConstruction(Ref(obj), field, localRef(field), fieldAccessorsExpr)
        }
        Block(ValDef(obj, Some(construct)) :: assignments, Ref(obj)).asExprOf[T]
      }
    }

    def constructFromValues(valuesExpr: Expr[Array[Any]]): Expr[T] = {
      val constructorOwned = fields.filter(_.constructorOwned)
      val postConstruction = fields.filterNot(_.constructorOwned)
      if postConstruction.isEmpty then {
        val args = constructorOwned.map { field =>
          valueArg(valuesExpr, field).asTerm
        }
        Apply(Select(New(TypeTree.of[T]), owner.primaryConstructor), args).asExprOf[T]
      } else if constructorOwned.isEmpty then {
        val obj = Symbol.newVal(Symbol.spliceOwner, "obj", TypeRepr.of[T], Flags.EmptyFlags, Symbol.noSymbol)
        val construct = Apply(Select(New(TypeTree.of[T]), owner.primaryConstructor), Nil)
        val assignments = postConstruction.map { field =>
          Assign(Select.unique(Ref(obj), field.name), valueArg(valuesExpr, field).asTerm)
        }
        Block(ValDef(obj, Some(construct)) :: assignments, Ref(obj)).asExprOf[T]
      } else {
        val obj = Symbol.newVal(Symbol.spliceOwner, "obj", TypeRepr.of[T], Flags.EmptyFlags, Symbol.noSymbol)
        val args = constructorOwned.map { field =>
          valueArg(valuesExpr, field).asTerm
        }
        val construct = Apply(Select(New(TypeTree.of[T]), owner.primaryConstructor), args)
        val assignments = postConstruction.map { field =>
          Assign(Select.unique(Ref(obj), field.name), valueArg(valuesExpr, field).asTerm)
        }
        Block(ValDef(obj, Some(construct)) :: assignments, Ref(obj)).asExprOf[T]
      }
    }

    def copiedValueArg(
        valueExpr: Expr[T],
        field: FieldMeta,
        copyContextExpr: Expr[org.apache.fory.context.CopyContext],
        fieldsByIdExpr: Expr[Array[FieldGroups.SerializationFieldInfo]],
        fieldAccessorsExpr: Expr[Array[org.apache.fory.reflect.FieldAccessor]]): Expr[Any] = {
      val selected = selectValue(valueExpr, field, fieldAccessorsExpr)
      val wireValue =
        if field.option then '{ $selected.asInstanceOf[Option[Any]].orNull }
        else selected
      val copied =
        '{
          StaticGeneratedStructSerializer.copyFieldValue(
            $copyContextExpr,
            $wireValue,
            $fieldsByIdExpr(${ Expr(field.index) }))
        }
      decodeValue(copied, field)
    }

    def failIfCopiedDuringConstructorArgCopy(
        valueExpr: Expr[T],
        copyContextExpr: Expr[org.apache.fory.context.CopyContext]): Expr[Unit] = {
      '{
        if $copyContextExpr.copyTrackingRef() &&
            $copyContextExpr.getCopyObject($valueExpr) != null
        then {
          throw new org.apache.fory.exception.CopyException(
            "Cannot copy cyclic object graph rooted at constructor-owned immutable value " +
              $valueExpr.getClass.getName +
              " because its copy cannot be referenced before construction completes")
        }
      }
    }

    def referenceCopy(
        copyContextExpr: Expr[org.apache.fory.context.CopyContext],
        sourceExpr: Expr[T],
        copiedExpr: Expr[T]): Term =
      '{ $copyContextExpr.reference($sourceExpr, $copiedExpr) }.asTerm

    def copyValue(
        valueExpr: Expr[T],
        copyContextExpr: Expr[org.apache.fory.context.CopyContext],
        fieldsByIdExpr: Expr[Array[FieldGroups.SerializationFieldInfo]],
        instantiatorExpr: Expr[org.apache.fory.reflect.ObjectInstantiator[T]],
        fieldAccessorsExpr: Expr[Array[org.apache.fory.reflect.FieldAccessor]]): Expr[T] = {
      val constructorOwned = fields.filter(_.constructorOwned)
      val postConstruction = fields.filterNot(_.constructorOwned)

      def copyBody(): Expr[T] =
        if postConstruction.isEmpty then {
          val obj = Symbol.newVal(Symbol.spliceOwner, "obj", TypeRepr.of[T], Flags.EmptyFlags, Symbol.noSymbol)
          val args = constructorOwned.map { field =>
            copiedValueArg(valueExpr, field, copyContextExpr, fieldsByIdExpr, fieldAccessorsExpr).asTerm
          }
          val cycleCheck = failIfCopiedDuringConstructorArgCopy(
            valueExpr,
            copyContextExpr).asTerm
          val construct = Apply(Select(New(TypeTree.of[T]), owner.primaryConstructor), args)
          Block(
            cycleCheck ::
              ValDef(obj, Some(construct)) ::
              referenceCopy(copyContextExpr, valueExpr, Ref(obj).asExprOf[T]) ::
              Nil,
            Ref(obj)).asExprOf[T]
        } else if constructorOwned.isEmpty then {
          val obj = Symbol.newVal(Symbol.spliceOwner, "obj", TypeRepr.of[T], Flags.EmptyFlags, Symbol.noSymbol)
          val construct = '{ $instantiatorExpr.newInstance() }.asTerm
          val assignments = postConstruction.map { field =>
            val copied =
              copiedValueArg(valueExpr, field, copyContextExpr, fieldsByIdExpr, fieldAccessorsExpr)
            assignPostConstruction(Ref(obj), field, copied.asTerm, fieldAccessorsExpr)
          }
          Block(
            ValDef(obj, Some(construct)) ::
              referenceCopy(copyContextExpr, valueExpr, Ref(obj).asExprOf[T]) ::
              assignments,
            Ref(obj)).asExprOf[T]
        } else {
          val obj = Symbol.newVal(Symbol.spliceOwner, "obj", TypeRepr.of[T], Flags.EmptyFlags, Symbol.noSymbol)
          val args = constructorOwned.map { field =>
            copiedValueArg(valueExpr, field, copyContextExpr, fieldsByIdExpr, fieldAccessorsExpr).asTerm
          }
          val cycleCheck = failIfCopiedDuringConstructorArgCopy(
            valueExpr,
            copyContextExpr).asTerm
          val construct = Apply(Select(New(TypeTree.of[T]), owner.primaryConstructor), args)
          val assignments = postConstruction.map { field =>
            val copied =
              copiedValueArg(valueExpr, field, copyContextExpr, fieldsByIdExpr, fieldAccessorsExpr)
            assignPostConstruction(Ref(obj), field, copied.asTerm, fieldAccessorsExpr)
          }
          Block(
            cycleCheck ::
              ValDef(obj, Some(construct)) ::
              referenceCopy(copyContextExpr, valueExpr, Ref(obj).asExprOf[T]) ::
              assignments,
            Ref(obj)).asExprOf[T]
        }

      copyBody()
    }

    def constructRead(valuesExpr: Expr[Array[Any]], readContextExpr: Expr[org.apache.fory.context.ReadContext]): Expr[T] = {
      val constructorOwned = fields.filter(_.constructorOwned)
      if constructorOwned.isEmpty then {
        report.errorAndAbort(
          s"${owner.fullName} cycle-owned generated classes must use generated mutable read paths")
      } else constructFromValues(valuesExpr)
    }

    def compatibleConstructRead(
        serializerExpr: Expr[StaticGeneratedStructSerializer[T]],
        resolverExpr: Expr[TypeResolver],
        fieldsByIdExpr: Expr[Array[FieldGroups.SerializationFieldInfo]],
        readContextExpr: Expr[org.apache.fory.context.ReadContext],
        instantiatorExpr: Expr[org.apache.fory.reflect.ObjectInstantiator[T]],
        fieldAccessorsExpr: Expr[Array[org.apache.fory.reflect.FieldAccessor]]): Expr[T] = {
      val localFields = fields.map { field =>
        val local = Symbol.newVal(
          Symbol.spliceOwner,
          s"${field.name}Value",
          field.sourceType,
          Flags.Mutable,
          Symbol.noSymbol)
        field -> local
      }
      val localDefs = localFields.map { (field, local) =>
        ValDef(local, Some(localDefault(field)))
      }
      val defaultedFields = fields.filter(field => field.constructorOwned && field.hasDefault)
      val defaultIndexByField = defaultedFields.zipWithIndex.map { (field, index) =>
        field.index -> index
      }.toMap
      val defaultMaskSym =
        if defaultedFields.nonEmpty && defaultedFields.size <= 64 then {
          Some(
            Symbol.newVal(
              Symbol.spliceOwner,
              "missingDefaultMask",
              TypeRepr.of[Long],
              Flags.Mutable,
              Symbol.noSymbol))
        } else None
      val defaultArraySym =
        if defaultedFields.size > 64 then {
          Some(
            Symbol.newVal(
              Symbol.spliceOwner,
              "missingDefaultMask",
              TypeRepr.of[Array[Boolean]],
              Flags.EmptyFlags,
              Symbol.noSymbol))
        } else None
      def clearDefault(field: FieldMeta): Term =
        defaultIndexByField.get(field.index) match {
          case Some(defaultIndex) =>
            defaultMaskSym match {
              case Some(mask) =>
                val bit = 1L << defaultIndex
                Assign(Ref(mask), '{ ${ Ref(mask).asExprOf[Long] } & ${ Expr(~bit) } }.asTerm)
              case None =>
                val mask = defaultArraySym.get
                '{
                  ${ Ref(mask).asExprOf[Array[Boolean]] }.update(${ Expr(defaultIndex) }, false)
                }.asTerm
            }
          case None =>
            '{ () }.asTerm
        }
      def applyDefault(field: FieldMeta, local: Symbol, defaultIndex: Int): Term = {
        val defaultValue =
          '{
            org.apache.fory.util.DefaultValueUtils.getScalaDefaultValue(
              Class.forName(${ Expr(ownerClassName) }),
              ${ Expr(field.name) })
          }
        val assignDefault = assignLocalSource(local, field, defaultValue)
        defaultMaskSym match {
          case Some(mask) =>
            val bit = 1L << defaultIndex
            '{
              if ((${ Ref(mask).asExprOf[Long] } & ${ Expr(bit) }) != 0L) {
                $assignDefault
              }
            }.asTerm
          case None =>
            val mask = defaultArraySym.get
            '{
              if (${ Ref(mask).asExprOf[Array[Boolean]] }(${ Expr(defaultIndex) })) {
                $assignDefault
              }
            }.asTerm
        }
      }
      val defaultAssignments = defaultedFields.zipWithIndex.map { (field, defaultIndex) =>
        applyDefault(field, localFields.find(_._1.index == field.index).get._2, defaultIndex)
      }
      val readLoop =
        '{
          val remoteFields = $serializerExpr.getRemoteFields()
          var i = 0
          while i < remoteFields.size() do {
            val remoteField = remoteFields.get(i)
            val matchedId = remoteField.matchedId
            ${
              compatibleLocalsByMatchedId(
                serializerExpr,
                resolverExpr,
                fieldsByIdExpr,
                localFields,
                clearDefault,
                'matchedId,
                'remoteField,
                readContextExpr)
            }
            i += 1
          }
        }
      val maskDefs =
        defaultMaskSym.map { mask =>
          val initial =
            if defaultedFields.size == 64 then -1L
            else (1L << defaultedFields.size) - 1L
          ValDef(mask, Some(Literal(LongConstant(initial))))
        }.toList ++
          defaultArraySym.map { mask =>
            ValDef(mask, Some('{ Array.fill[Boolean](${ Expr(defaultedFields.size) })(true) }.asTerm))
          }.toList
      Block(
        localDefs ++ maskDefs,
        Block(
          readLoop.asTerm :: defaultAssignments.toList,
          constructFromLocals(localFields, instantiatorExpr, fieldAccessorsExpr).asTerm))
        .asExprOf[T]
    }

    def readSchemaConsistentBody(
        serializerExpr: Expr[StaticGeneratedStructSerializer[T]],
        resolverExpr: Expr[TypeResolver],
        descriptorsExpr: Expr[java.util.List[Descriptor]],
        classVersionHashExpr: Expr[Int],
        allFieldsExpr: Expr[Array[FieldGroups.SerializationFieldInfo]],
        allFieldIdsExpr: Expr[Array[Int]],
        readContextExpr: Expr[org.apache.fory.context.ReadContext],
        instantiatorExpr: Expr[org.apache.fory.reflect.ObjectInstantiator[T]],
        fieldAccessorsExpr: Expr[Array[org.apache.fory.reflect.FieldAccessor]]): Expr[T] = {
      val constructorOwned = fields.filter(_.constructorOwned)
      if constructorOwned.isEmpty then {
        '{
          val buffer = $readContextExpr.getBuffer
          if $resolverExpr.checkClassVersion() then {
            $serializerExpr.checkClassVersion(buffer.readInt32(), $classVersionHashExpr)
          }
          val obj = $instantiatorExpr.newInstance()
          $readContextExpr.reference(obj)
          var i = 0
          while i < $allFieldsExpr.length do {
            val fieldInfo = $allFieldsExpr(i)
            val fieldId = $allFieldIdsExpr(i)
            ${
              readAndAssignDispatch(
                'obj,
                'fieldId,
                'fieldInfo,
                readContextExpr,
                resolverExpr,
                fieldAccessorsExpr)
            }
            i += 1
          }
          obj
        }
      } else {
        '{
          val buffer = $readContextExpr.getBuffer
          if $resolverExpr.checkClassVersion() then {
            $serializerExpr.checkClassVersion(buffer.readInt32(), $classVersionHashExpr)
          }
          val values = new Array[Any]($descriptorsExpr.size())
          var i = 0
          while i < $allFieldsExpr.length do {
            val fieldInfo = $allFieldsExpr(i)
            values($allFieldIdsExpr(i)) =
              StaticGeneratedStructSerializer.readFieldValue($resolverExpr, $readContextExpr, fieldInfo)
            i += 1
          }
          ${ constructRead('values, readContextExpr) }
        }
      }
    }

    def readCompatibleBody(
        serializerExpr: Expr[StaticGeneratedStructSerializer[T]],
        resolverExpr: Expr[TypeResolver],
        descriptorsExpr: Expr[java.util.List[Descriptor]],
        fieldsByIdExpr: Expr[Array[FieldGroups.SerializationFieldInfo]],
        sameSchemaCompatibleExpr: Expr[Boolean],
        readContextExpr: Expr[org.apache.fory.context.ReadContext],
        instantiatorExpr: Expr[org.apache.fory.reflect.ObjectInstantiator[T]],
        fieldAccessorsExpr: Expr[Array[org.apache.fory.reflect.FieldAccessor]]): Expr[T] = {
      val constructorOwned = fields.filter(_.constructorOwned)
      if constructorOwned.isEmpty then {
        '{
          if $sameSchemaCompatibleExpr then {
            $serializerExpr.read($readContextExpr)
          } else {
            val obj = $instantiatorExpr.newInstance()
            $readContextExpr.reference(obj)
            val remoteFields = $serializerExpr.getRemoteFields()
            var i = 0
            while i < remoteFields.size() do {
              val remoteField = remoteFields.get(i)
              val matchedId = remoteField.matchedId
              ${ compatibleAssignByMatchedId(
                  serializerExpr,
                  resolverExpr,
                  fieldsByIdExpr,
                  'obj,
                  'matchedId,
                  'remoteField,
                  readContextExpr,
                  fieldAccessorsExpr) }
              i += 1
            }
            obj
          }
        }
      } else {
        '{
          if $sameSchemaCompatibleExpr then {
            $serializerExpr.read($readContextExpr)
          } else {
            ${
              compatibleConstructRead(
                serializerExpr,
                resolverExpr,
                fieldsByIdExpr,
                readContextExpr,
                instantiatorExpr,
                fieldAccessorsExpr)
            }
          }
        }
      }
    }

    def fieldAccessors(clsExpr: Expr[Class[T]])
        : Expr[Array[org.apache.fory.reflect.FieldAccessor]] = {
      val accessorsSym =
        Symbol.newVal(
          Symbol.spliceOwner,
          "accessors",
          TypeRepr.of[Array[org.apache.fory.reflect.FieldAccessor]],
          Flags.EmptyFlags,
          Symbol.noSymbol)
      val assignments = fields.filter(_.usesFieldAccessor).map { field =>
        '{
          ${ Ref(accessorsSym).asExprOf[Array[org.apache.fory.reflect.FieldAccessor]] }(
            ${ Expr(field.index) }) =
            org.apache.fory.reflect.FieldAccessor.createAccessor(
              Class
                .forName(${ Expr(ownerClassName) }, false, $clsExpr.getClassLoader)
                .getDeclaredField(${ Expr(field.name) }))
        }.asTerm
      }
      Block(
        ValDef(
          accessorsSym,
          Some(
            '{
              new Array[org.apache.fory.reflect.FieldAccessor](${ Expr(fields.size) })
            }.asTerm))
          :: assignments,
        Ref(accessorsSym))
        .asExprOf[Array[org.apache.fory.reflect.FieldAccessor]]
    }

    val classExpr: Expr[Class[T]] =
      '{ Class.forName(${ Expr(ownerClassName) }).asInstanceOf[Class[T]] }

    '{
      new ForySerializer[T] {
        private val descriptors: java.util.List[Descriptor] = $descriptorsExpr

        override def createSerializer(
            resolver: TypeResolver,
            remoteTypeDef: ForyTypeDef): Serializer[T] = {
          val cls = $classExpr
          new StaticGeneratedStructSerializer[T](resolver, cls, remoteTypeDef, descriptors) {
            private val generatedSerializer: StaticGeneratedStructSerializer[T] = this
            private val fieldGroups: FieldGroups =
              buildLocalFieldGroups(descriptors)
            private val allFields: Array[FieldGroups.SerializationFieldInfo] =
              fieldGroups.allFields
            private val allFieldIds: Array[Int] = localFieldIds(allFields, descriptors)
            private val fieldsById: Array[FieldGroups.SerializationFieldInfo] = {
              val result = new Array[FieldGroups.SerializationFieldInfo](descriptors.size())
              var i = 0
              while i < allFields.length do {
                result(allFieldIds(i)) = allFields(i)
                i += 1
              }
              result
            }
            private val generatedObjectInstantiator
                : org.apache.fory.reflect.ObjectInstantiator[T] =
              resolver.getObjectInstantiator(cls)
            private val generatedFieldAccessors
                : Array[org.apache.fory.reflect.FieldAccessor] =
              ${ fieldAccessors('cls) }
            private val classVersionHash: Int =
              if resolver.checkClassVersion() then computeClassVersionHash(descriptors) else 0
            private val sameSchemaCompatible: Boolean =
              remoteTypeDef != null &&
                !${ Expr(hasNestedCompatibleStructFields) } &&
                remoteTypeDef.getId == ForyTypeDef.buildTypeDef(resolver, cls).getId

            override def getGeneratedDescriptors(): java.util.List[Descriptor] = descriptors

            override def copySerializer(
                typeResolver: TypeResolver,
                typeClass: Class[?],
                typeDef: ForyTypeDef): StaticGeneratedStructSerializer[T] =
              createSerializer(typeResolver, typeDef).asInstanceOf[StaticGeneratedStructSerializer[T]]

            override def write(
                writeContext: org.apache.fory.context.WriteContext,
                value: T): Unit = {
              val buffer = writeContext.getBuffer
              if resolver.checkClassVersion() then {
                buffer.writeInt32(classVersionHash)
              }
              var i = 0
              while i < allFields.length do {
                val fieldInfo = allFields(i)
                val fieldId = allFieldIds(i)
                ${
                  writeDispatch(
                    'value,
                    'fieldId,
                    'fieldInfo,
                    'writeContext,
                    'resolver,
                    'generatedFieldAccessors)
                }
                i += 1
              }
            }

            override def read(readContext: org.apache.fory.context.ReadContext): T = {
              if remoteTypeDef != null && !sameSchemaCompatible then readCompatible(readContext)
              else readSchemaConsistent(readContext)
            }

            private def readSchemaConsistent(
                readContext: org.apache.fory.context.ReadContext): T =
              ${
                readSchemaConsistentBody(
                  'generatedSerializer,
                  'resolver,
                  'descriptors,
                  'classVersionHash,
                  'allFields,
                  'allFieldIds,
                  'readContext,
                  'generatedObjectInstantiator,
                  'generatedFieldAccessors)
              }

            override def readCompatible(readContext: org.apache.fory.context.ReadContext): T = {
              ${
                readCompatibleBody(
                  'generatedSerializer,
                  'resolver,
                  'descriptors,
                  'fieldsById,
                  'sameSchemaCompatible,
                  'readContext,
                  'generatedObjectInstantiator,
                  'generatedFieldAccessors)
              }
            }

            override def copy(
                copyContext: org.apache.fory.context.CopyContext,
                value: T): T =
              ${
                copyValue(
                  'value,
                  'copyContext,
                  'fieldsById,
                  'generatedObjectInstantiator,
                  'generatedFieldAccessors)
              }
          }
        }
      }
    }
  }

  private def deriveUnion[T: Type](using q: Quotes)(
      owner: q.reflect.Symbol): Expr[ForySerializer[T]] = {
    import q.reflect.*

    final case class CaseMeta(
        symbol: Symbol,
        id: Option[Int],
        payloadType: TypeRepr,
        option: Boolean,
        trackingRef: Boolean,
        hasTrackingRefMetadata: Boolean,
        payloadName: String,
        unknown: Boolean,
        fieldIndex: Int)

    def peelAnnotations(tpe: TypeRepr): (TypeRepr, List[Term]) = {
      tpe match {
        case AnnotatedType(underlying, annotation) =>
          val (base, annotations) = peelAnnotations(underlying)
          (base, annotation :: annotations)
        case other =>
          other.dealias match {
            case AnnotatedType(underlying, annotation) =>
              val (base, annotations) = peelAnnotations(underlying)
              (base, annotation :: annotations)
            case dealiased => (dealiased, Nil)
          }
      }
    }

    def optionElement(tpe: TypeRepr): Option[TypeRepr] = {
      peelAnnotations(tpe)._1.dealias match {
        case AppliedType(base, List(arg)) if base.typeSymbol.fullName == "scala.Option" =>
          Some(arg)
        case _ => None
      }
    }

    def payloadMeta(child: Symbol, unknown: Boolean): (TypeRepr, String) = {
      val params = child.primaryConstructor.paramSymss.flatten
      if unknown then {
        if params.size != 1 then {
          report.errorAndAbort(
            s"${child.fullName} is the unknown union case and must have (value: UnknownCase)")
        }
        val tpe = params.head.tree match {
          case ValDef(_, tpt, _) => tpt.tpe
          case _ => params.head.termRef.widen
        }
        if !(tpe =:= TypeRepr.of[org.apache.fory.`type`.union.UnknownCase]) then {
          report.errorAndAbort(
            s"${child.fullName} is the unknown union case and must have (value: UnknownCase)")
        }
        (tpe, params.head.name)
      } else {
        if params.size != 1 then {
          report.errorAndAbort(s"${child.fullName} must have exactly one payload parameter")
        }
        val tpe = params.head.tree match {
          case ValDef(_, tpt, _) => tpt.tpe
          case _ => params.head.termRef.widen
        }
        (tpe, params.head.name)
      }
    }

    val rawCases = owner.children.filter(_.flags.is(Flags.Case)).map { child =>
      val unknown = hasAnnotation[ForyUnknownCase](child)
      val id =
        if unknown then {
          if annotationIntArg[ForyCase](child, "id").nonEmpty then {
            report.errorAndAbort(
              s"${child.fullName} unknown case must use @ForyUnknownCase without @ForyCase")
          }
          if child.name != "Unknown" then {
            report.errorAndAbort(s"${child.fullName} unknown case must be named Unknown")
          }
          None
        } else {
          Some(annotationIntArg[ForyCase](child, "id").getOrElse {
            report.errorAndAbort(s"${child.fullName} must be annotated with @ForyCase")
          })
        }
      if !unknown && id.exists(_ < 0) then {
        report.errorAndAbort(s"${child.fullName} @ForyCase id must be non-negative")
      }
      val (tpe, payloadName) = payloadMeta(child, unknown)
      val refTracking = topLevelTypeRefTracking(tpe)
      CaseMeta(
        child,
        id,
        tpe,
        optionElement(tpe).nonEmpty,
        refTracking.getOrElse(false),
        refTracking.nonEmpty,
        payloadName,
        unknown,
        -1)
    }
    var nextFieldIndex = 0
    val cases = rawCases.map { unionCase =>
      if unionCase.unknown then unionCase
      else {
        val indexed = unionCase.copy(fieldIndex = nextFieldIndex)
        nextFieldIndex += 1
        indexed
      }
    }
    val knownCases = cases.filterNot(_.unknown)
    if cases.count(_.unknown) > 1 then {
      report.errorAndAbort(s"${owner.fullName} must define exactly one @ForyUnknownCase unknown case")
    }
    if knownCases.isEmpty then {
      report.errorAndAbort(
        s"${owner.fullName} must define at least one non-Unknown case; Unknown is a forward-compatibility carrier and cannot be the default")
    }
    val unknown =
      cases.find(_.unknown).getOrElse(
        report.errorAndAbort(s"${owner.fullName} must define @ForyUnknownCase unknown case"))

    def knownCaseId(unionCase: CaseMeta): Int =
      unionCase.id.getOrElse(report.errorAndAbort("unknown union carrier has no schema case id"))

    if knownCases.groupBy(knownCaseId).exists(_._2.size > 1) then {
      report.errorAndAbort(s"${owner.fullName} has duplicate @ForyCase ids")
    }

    def boxedIfPrimitive(tpe: TypeRepr): TypeRepr = {
      val (base, annotations) = peelAnnotations(tpe)
      val boxed =
        if base =:= TypeRepr.of[Boolean] then TypeRepr.of[java.lang.Boolean]
        else if base =:= TypeRepr.of[Byte] then TypeRepr.of[java.lang.Byte]
        else if base =:= TypeRepr.of[Short] then TypeRepr.of[java.lang.Short]
        else if base =:= TypeRepr.of[Int] then TypeRepr.of[java.lang.Integer]
        else if base =:= TypeRepr.of[Long] then TypeRepr.of[java.lang.Long]
        else if base =:= TypeRepr.of[Float] then TypeRepr.of[java.lang.Float]
        else if base =:= TypeRepr.of[Double] then TypeRepr.of[java.lang.Double]
        else base
      annotations.foldRight(boxed)((annotation, current) => AnnotatedType(current, annotation))
    }

    def classFor(tpe: TypeRepr): Expr[Class[?]] = {
      val normalized = peelAnnotations(tpe.widen)._1.dealias
      val fullName = normalized.typeSymbol.fullName
      if normalized =:= TypeRepr.of[Boolean] then '{ java.lang.Boolean.TYPE }
      else if normalized =:= TypeRepr.of[Byte] then '{ java.lang.Byte.TYPE }
      else if normalized =:= TypeRepr.of[Short] then '{ java.lang.Short.TYPE }
      else if normalized =:= TypeRepr.of[Int] then '{ java.lang.Integer.TYPE }
      else if normalized =:= TypeRepr.of[Long] then '{ java.lang.Long.TYPE }
      else if normalized =:= TypeRepr.of[Float] then '{ java.lang.Float.TYPE }
      else if normalized =:= TypeRepr.of[Double] then '{ java.lang.Double.TYPE }
      else if normalized =:= TypeRepr.of[Char] then '{ java.lang.Character.TYPE }
      else if normalized =:= TypeRepr.of[String] ||
          normalized.typeSymbol == TypeRepr.of[String].typeSymbol ||
          fullName == "scala.Predef.String" ||
          fullName == "scala.Predef$.String" ||
          fullName.endsWith("Predef.String") ||
          fullName.endsWith("Predef$.String")
      then '{ classOf[String] }
      else if fullName == "scala.Array" then {
        '{ Class.forName(${ Expr(arrayClassName(normalized)) }) }
      } else '{ Class.forName(${ Expr(fullName.replace("$.", "$")) }) }
    }

    def arrayClassName(tpe: TypeRepr): String = {
      tpe.dealias match {
        case AppliedType(arrayType, List(componentType))
            if arrayType.typeSymbol.fullName == "scala.Array" =>
          "[" + arrayComponentDescriptor(componentType)
        case _ =>
          report.errorAndAbort(s"Expected Scala Array type, got ${tpe.show}")
      }
    }

    def arrayComponentDescriptor(tpe: TypeRepr): String = {
      val normalized = peelAnnotations(tpe.widen)._1.dealias
      if normalized =:= TypeRepr.of[Boolean] then "Z"
      else if normalized =:= TypeRepr.of[Byte] then "B"
      else if normalized =:= TypeRepr.of[Short] then "S"
      else if normalized =:= TypeRepr.of[Int] then "I"
      else if normalized =:= TypeRepr.of[Long] then "J"
      else if normalized =:= TypeRepr.of[Float] then "F"
      else if normalized =:= TypeRepr.of[Double] then "D"
      else if normalized =:= TypeRepr.of[Char] then "C"
      else if normalized.typeSymbol.fullName == "scala.Array" then arrayClassName(normalized)
      else "L" + normalized.typeSymbol.fullName.replace("$.", "$") + ";"
    }

    def generatedType(tpe: TypeRepr): Expr[Descriptor.GeneratedType] = {
      val (outer, outerAnnotations) = peelAnnotations(tpe)
      val option = optionElement(outer)
      val fieldSource = option.map(boxedIfPrimitive).getOrElse(outer)
      val (base, baseAnnotations) = peelAnnotations(fieldSource)
      val optionInnerAnnotations = option.toList.flatMap(inner => peelAnnotations(inner)._2)
      val annotations = outerAnnotations ++ baseAnnotations ++ optionInnerAnnotations
      val argumentSource = fieldSource
      def appliedType(tpe: TypeRepr): Option[(TypeRepr, List[TypeRepr])] = {
        val directArgs = tpe.typeArgs
        if directArgs.nonEmpty then {
          tpe match {
            case AppliedType(typeConstructor, _) => Some((typeConstructor, directArgs))
            case other =>
              other.dealias match {
                case AppliedType(typeConstructor, _) => Some((typeConstructor, directArgs))
                case _ => Some((tpe, directArgs))
              }
          }
        } else {
          tpe match {
            case AppliedType(typeConstructor, typeArgs) => Some((typeConstructor, typeArgs))
            case other =>
              other.dealias match {
                case AppliedType(typeConstructor, typeArgs) => Some((typeConstructor, typeArgs))
                case _ => None
              }
          }
        }
      }
      val component = appliedType(argumentSource) match {
        case Some((arrayType, List(componentType)))
            if arrayType.typeSymbol.fullName == "scala.Array" =>
          Some(generatedType(componentType))
        case _ => None
      }
      val args = appliedType(argumentSource) match {
        case Some((arrayType, List(_))) if arrayType.typeSymbol.fullName == "scala.Array" =>
          Nil
        case Some((_, typeArgs)) => typeArgs
        case _ => Nil
      }
      val argExprs = args.map(generatedType)
      val argList: Expr[java.util.List[Descriptor.GeneratedType]] =
        '{
          import scala.jdk.CollectionConverters.*
          java.util.Collections.unmodifiableList(${ Expr.ofList(argExprs) }.asJava)
        }
      val componentExpr: Expr[Descriptor.GeneratedType] =
        component.getOrElse('{ null.asInstanceOf[Descriptor.GeneratedType] })
      val typeId =
        annotations
          .flatMap(typeIdForAnnotation)
          .headOption
          .orElse(option.map(inner => wireTypeId(peelAnnotations(boxedIfPrimitive(inner))._1)))
          .orElse {
            // The owning field supplies the union schema, so field TypeMeta uses
            // UNION. TYPED_UNION/NAMED_UNION are reserved for root or dynamic Any
            // values without static field schema.
            if hasAnnotation[ForyUnion](base.typeSymbol) then Some(Types.UNION) else None
          }
          .orElse {
            if isScalaEnumType(base) then Some(Types.ENUM) else None
          }
          .getOrElse(Types.UNKNOWN)
      val rawClass = classFor(base)
      val typeExtMeta = generatedTypeExtMeta(
        typeId,
        nullable = option.nonEmpty,
        trackingRef = refTrackingFromAnnotations(annotations).getOrElse(false),
        hasTrackingRefMetadata = refTrackingFromAnnotations(annotations).nonEmpty,
        nullableWrapper = option.nonEmpty,
        rawClass = Some(rawClass))
      '{ Descriptor.generatedType($rawClass, $typeExtMeta, $argList, $componentExpr) }
    }

    def wireTypeId(tpe: TypeRepr): Int = {
      val normalized = peelAnnotations(tpe.widen)._1.dealias
      if normalized =:= TypeRepr.of[Boolean] then Types.BOOL
      else if normalized =:= TypeRepr.of[Byte] then Types.INT8
      else if normalized =:= TypeRepr.of[Short] then Types.INT16
      else if normalized =:= TypeRepr.of[Int] then Types.INT32
      else if normalized =:= TypeRepr.of[Long] then Types.INT64
      else if normalized =:= TypeRepr.of[Float] then Types.FLOAT32
      else if normalized =:= TypeRepr.of[Double] then Types.FLOAT64
      else {
        val fullName = normalized.typeSymbol.fullName
        if normalized =:= TypeRepr.of[String] ||
            normalized.typeSymbol == TypeRepr.of[String].typeSymbol ||
            fullName == "scala.Predef.String" ||
            fullName == "scala.Predef$.String" ||
            fullName.endsWith("Predef.String") ||
            fullName.endsWith("Predef$.String")
        then Types.STRING
        else Types.UNKNOWN
      }
    }

    def caseDescriptor(unionCase: CaseMeta): Expr[Descriptor] = {
      '{
        new Descriptor(
          ${ generatedType(unionCase.payloadType) },
          ${ Expr(unionCase.payloadType.show) },
          ${ Expr(unionCase.symbol.name + ".value") },
          ${ Expr(Modifier.PRIVATE | Modifier.FINAL) },
          ${ Expr(owner.fullName.replace("$.", "$")) },
          true,
          ${ Expr(knownCaseId(unionCase)) },
          ${ Expr(unionCase.option) },
          ${ Expr(unionCase.trackingRef) },
          ${ Expr(unionCase.hasTrackingRefMetadata) },
          ForyField.Dynamic.AUTO,
          false
        )
      }
    }

    val caseDescriptorsExpr: Expr[java.util.List[Descriptor]] =
      '{
        import scala.jdk.CollectionConverters.*
        java.util.Collections.unmodifiableList(${ Expr.ofList(knownCases.map(caseDescriptor)) }.asJava)
      }

    def wirePayload(payloadExpr: Expr[Any], unionCase: CaseMeta): Expr[Any] =
      if unionCase.option then '{ $payloadExpr.asInstanceOf[Option[Any]].orNull }
      else payloadExpr

    def decodePayload(payloadExpr: Expr[Any], unionCase: CaseMeta): Expr[Any] = {
      optionElement(unionCase.payloadType) match {
        case Some(inner) =>
          inner.asType match {
            case '[p] =>
              unionCase.payloadType.asType match {
                case '[a] =>
                  '{
                    val rawPayload = $payloadExpr
                    if rawPayload == null then None.asInstanceOf[a]
                    else Option(${ coercePayload[p]('rawPayload, inner) }).asInstanceOf[a]
                  }
              }
          }
        case None =>
          unionCase.payloadType.asType match {
            case '[p] => coercePayload[p](payloadExpr, unionCase.payloadType)
          }
      }
    }

    def writeDispatch(
        valueExpr: Expr[T],
        writeContextExpr: Expr[org.apache.fory.context.WriteContext],
        resolverExpr: Expr[TypeResolver],
        caseFieldInfosExpr: Expr[Array[FieldGroups.SerializationFieldInfo]]): Expr[Unit] = {
      cases.foldRight(
        '{
          throw new IllegalStateException("Unknown Scala union case " + $valueExpr)
        }: Expr[Unit]) { (unionCase, next) =>
        unionCase.symbol.typeRef.asType match {
          case '[c] =>
            if unionCase.unknown then {
              val payload =
                Select.unique(
                  '{ $valueExpr.asInstanceOf[c] }.asTerm,
                  unionCase.payloadName).asExprOf[org.apache.fory.`type`.union.UnknownCase]
              '{
                if $valueExpr.isInstanceOf[c] then {
                  UnionSerializer.writeUnknownValue($writeContextExpr, $payload)
                } else {
                  $next
                }
              }
            } else {
              val payload =
                Select.unique(
                  '{ $valueExpr.asInstanceOf[c] }.asTerm,
                  unionCase.payloadName).asExpr
              val payloadValue = wirePayload(payload, unionCase)
              '{
                if $valueExpr.isInstanceOf[c] then {
                  UnionSerializer.writeCaseValue(
                    $resolverExpr,
                    $writeContextExpr,
                    $caseFieldInfosExpr(${ Expr(unionCase.fieldIndex) }),
                    $payloadValue,
                    ${ Expr(knownCaseId(unionCase)) })
                } else {
                  $next
                }
              }
            }
        }
      }
    }

    def construct(unionCase: CaseMeta, args: List[Term]): Expr[T] = {
      Apply(Select(New(TypeTree.ref(unionCase.symbol)), unionCase.symbol.primaryConstructor), args)
        .asExprOf[T]
    }

    def readDispatch(
        caseIdExpr: Expr[Int],
        readContextExpr: Expr[org.apache.fory.context.ReadContext],
        resolverExpr: Expr[TypeResolver],
        caseFieldInfosExpr: Expr[Array[FieldGroups.SerializationFieldInfo]]): Expr[T] = {
      val unknownPayload = '{ UnionSerializer.readUnknownValue($readContextExpr, $caseIdExpr) }
      val unknownExpr = construct(unknown, List(unknownPayload.asTerm))
      val dispatch = knownCases.foldRight(unknownExpr) { (unionCase, next) =>
        val rawPayload =
          '{
            UnionSerializer.readCaseValue(
              $resolverExpr,
              $readContextExpr,
              $caseFieldInfosExpr(${ Expr(unionCase.fieldIndex) }))
          }
        val payload = decodePayload(rawPayload, unionCase)
        val current = construct(unionCase, List(payload.asTerm))
        '{
          if $caseIdExpr == ${ Expr(knownCaseId(unionCase)) } then $current else $next
        }
      }
      dispatch
    }

    def copyDispatch(
        valueExpr: Expr[T],
        copyContextExpr: Expr[org.apache.fory.context.CopyContext],
        caseFieldInfosExpr: Expr[Array[FieldGroups.SerializationFieldInfo]]): Expr[T] = {
      def failIfCopiedDuringPayloadCopy(): Expr[Unit] = {
        '{
          if $copyContextExpr.copyTrackingRef() &&
              $copyContextExpr.getCopyObject($valueExpr) != null
          then {
            throw new org.apache.fory.exception.CopyException(
              "Cannot copy cyclic object graph rooted at constructor-owned immutable value " +
                $valueExpr.getClass.getName +
                " because its copy cannot be referenced before construction completes")
          }
        }
      }

      cases.foldRight(
        '{
          throw new IllegalStateException("Unknown Scala union case " + $valueExpr)
        }: Expr[T]) { (unionCase, next) =>
        unionCase.symbol.typeRef.asType match {
          case '[c] =>
            val payload =
              Select.unique(
                '{ $valueExpr.asInstanceOf[c] }.asTerm,
                unionCase.payloadName).asExpr
            if unionCase.unknown then {
              val unknownPayload =
                payload.asExprOf[org.apache.fory.`type`.union.UnknownCase]
              val copiedPayload =
                '{ UnionSerializer.copyUnknownValue($copyContextExpr, $unknownPayload) }
              '{
                if $valueExpr.isInstanceOf[c] then {
                  ${ construct(unknown, List(copiedPayload.asTerm)) }
                } else $next
              }
            } else {
              val payloadValue = wirePayload(payload, unionCase)
              '{
                if $valueExpr.isInstanceOf[c] then {
                  val copiedPayload =
                    UnionSerializer.copyCaseValue(
                      $copyContextExpr,
                      $caseFieldInfosExpr(${ Expr(unionCase.fieldIndex) }),
                      $payloadValue)
                  ${ failIfCopiedDuringPayloadCopy() }
                  ${ construct(unionCase, List(decodePayload('copiedPayload, unionCase).asTerm)) }
                } else $next
              }
            }
        }
      }
    }

    def coercePayload[P: Type](
        payloadExpr: Expr[Any],
        payloadType: TypeRepr): Expr[P] = {
      val rawTypeName = payloadType.dealias match {
        case AppliedType(base, _) => base.typeSymbol.fullName
        case other => other.typeSymbol.fullName
      }
      val renderedType = payloadType.show
      if rawTypeName == "scala.collection.immutable.List" ||
          rawTypeName == "scala.collection.Seq" ||
          rawTypeName == "scala.collection.immutable.Seq" ||
          renderedType.startsWith("scala.List[") ||
          renderedType.startsWith("List[")
      then {
          '{
            $payloadExpr match {
              case value: scala.collection.immutable.List[?] => value.asInstanceOf[P]
              case value: java.util.List[?] =>
                import scala.jdk.CollectionConverters.*
                value.asScala.toList.asInstanceOf[P]
              case value => value.asInstanceOf[P]
            }
          }
      } else if rawTypeName == "scala.collection.immutable.Set" ||
          rawTypeName == "scala.collection.Set" ||
          renderedType.startsWith("scala.Set[") ||
          renderedType.startsWith("Set[")
      then {
          '{
            $payloadExpr match {
              case value: scala.collection.immutable.Set[?] => value.asInstanceOf[P]
              case value: java.util.Set[?] =>
                import scala.jdk.CollectionConverters.*
                value.asScala.toSet.asInstanceOf[P]
              case value => value.asInstanceOf[P]
            }
          }
      } else if rawTypeName == "scala.collection.immutable.Map" ||
          rawTypeName == "scala.collection.Map" ||
          renderedType.startsWith("scala.Map[") ||
          renderedType.startsWith("Map[")
      then {
          '{
            $payloadExpr match {
              case value: scala.collection.immutable.Map[?, ?] => value.asInstanceOf[P]
              case value: java.util.Map[?, ?] =>
                import scala.jdk.CollectionConverters.*
                value.asScala.toMap.asInstanceOf[P]
              case value => value.asInstanceOf[P]
            }
          }
      } else '{ $payloadExpr.asInstanceOf[P] }
    }

    val ownerClassName = owner.fullName.replace("$.", "$")
    val classExpr: Expr[Class[T]] =
      '{ Class.forName(${ Expr(ownerClassName) }).asInstanceOf[Class[T]] }
    val caseClassesExpr: Expr[Array[Class[_]]] = {
      val caseClassExprs = cases.map { unionCase =>
        '{ Class.forName(${ Expr(unionCase.symbol.fullName.replace("$.", "$")) }) }
      }
      '{ Array[Class[_]](${ Varargs(caseClassExprs) }*) }
    }

    '{
      new ForySerializer[T] {
        override def isUnion: Boolean = true

        override private[scala] def handledRuntimeClasses(cls: Class[T]): Array[Class[_]] =
          $caseClassesExpr

        override def createSerializer(
            resolver: TypeResolver,
            remoteTypeDef: ForyTypeDef): Serializer[T] = {
          new Serializer[T](resolver.getConfig, $classExpr) {
            private val caseFieldInfos: Array[FieldGroups.SerializationFieldInfo] = {
              val descriptors = $caseDescriptorsExpr
              val result = new Array[FieldGroups.SerializationFieldInfo](descriptors.size())
              var i = 0
              while i < descriptors.size() do {
                result(i) = new FieldGroups.SerializationFieldInfo(resolver, descriptors.get(i))
                i += 1
              }
              result
            }

            override def write(
                writeContext: org.apache.fory.context.WriteContext,
                value: T): Unit = {
              ${ writeDispatch('value, 'writeContext, 'resolver, 'caseFieldInfos) }
            }

            override def read(readContext: org.apache.fory.context.ReadContext): T = {
              val buffer = readContext.getBuffer
              val caseId = buffer.readVarUInt32()
              ${ readDispatch('caseId, 'readContext, 'resolver, 'caseFieldInfos) }
            }

            override def copy(copyContext: org.apache.fory.context.CopyContext, value: T): T = {
              val copied = ${ copyDispatch('value, 'copyContext, 'caseFieldInfos) }
              copyContext.reference(value, copied)
              copied
            }
          }
        }
      }
    }
  }

  private def annotationIntArg[A: Type](using q: Quotes)(
      symbol: q.reflect.Symbol,
      name: String): Option[Int] = {
    import q.reflect.*
    symbol.annotations
      .find(_.tpe <:< TypeRepr.of[A])
      .flatMap {
        case Apply(_, args) =>
          args.collectFirst {
            case NamedArg(`name`, Literal(IntConstant(value))) => value
            case Literal(IntConstant(value)) => value
          }
        case _ => None
      }
  }

  private def hasAnnotation[A: Type](using q: Quotes)(symbol: q.reflect.Symbol): Boolean = {
    import q.reflect.*
    symbol.annotations.exists(_.tpe <:< TypeRepr.of[A])
  }

  private def refAnnotation(using q: Quotes)(symbol: q.reflect.Symbol): Option[Boolean] = {
    symbol.annotations.find(isRefAnnotation).map(refAnnotationEnabled)
  }

  private def topLevelTypeRefTracking(using q: Quotes)(
      tpe: q.reflect.TypeRepr): Option[Boolean] = {
    import q.reflect.*

    def peelAnnotations(tpe: TypeRepr): (TypeRepr, List[Term]) = {
      tpe match {
        case AnnotatedType(underlying, annotation) =>
          val (base, annotations) = peelAnnotations(underlying)
          (base, annotation :: annotations)
        case other =>
          other.dealias match {
            case AnnotatedType(underlying, annotation) =>
              val (base, annotations) = peelAnnotations(underlying)
              (base, annotation :: annotations)
            case dealiased => (dealiased, Nil)
          }
      }
    }

    val (base, annotations) = peelAnnotations(tpe)
    base.dealias match {
      case AppliedType(optionType, List(inner))
          if optionType.typeSymbol.fullName == "scala.Option" =>
        refTrackingFromAnnotations(peelAnnotations(inner)._2)
      case _ => refTrackingFromAnnotations(annotations)
    }
  }

  private def generatedTypeExtMeta(using q: Quotes)(
      typeId: Int,
      nullable: Boolean,
      trackingRef: Boolean,
      hasTrackingRefMetadata: Boolean,
      nullableWrapper: Boolean = false,
      rawClass: Option[Expr[Class[?]]] = None): Expr[TypeExtMeta] = {
    if typeId == Types.UNKNOWN && rawClass.nonEmpty then {
      val raw = rawClass.get
      '{
        val resolvedTypeId =
          if ScalaTypes.isScalaEnumType($raw) then Types.ENUM else Types.UNKNOWN
        if resolvedTypeId == Types.UNKNOWN &&
            !${ Expr(nullable) } &&
            !${ Expr(hasTrackingRefMetadata) } &&
            !${ Expr(nullableWrapper) } then {
          null.asInstanceOf[TypeExtMeta]
        } else {
          TypeExtMeta.of(
            resolvedTypeId,
            ${ Expr(nullable) },
            ${ Expr(trackingRef) },
            ${ Expr(nullableWrapper) })
        }
      }
    } else if typeId == Types.UNKNOWN && !nullable && !hasTrackingRefMetadata && !nullableWrapper then {
      '{ null.asInstanceOf[TypeExtMeta] }
    } else {
      '{
        TypeExtMeta.of(
          ${ Expr(typeId) },
          ${ Expr(nullable) },
          ${ Expr(trackingRef) },
          ${ Expr(nullableWrapper) })
      }
    }
  }

  private def isScalaEnumType(using q: Quotes)(tpe: q.reflect.TypeRepr): Boolean = {
    import q.reflect.*
    tpe.typeSymbol.flags.is(Flags.Enum) ||
    tpe.baseClasses.exists(_.fullName == "scala.reflect.Enum")
  }

  private def hasNestedCompatibleStruct(using q: Quotes)(tpe: q.reflect.TypeRepr): Boolean = {
    import q.reflect.*

    def peel(tpe: TypeRepr): TypeRepr = {
      tpe match {
        case AnnotatedType(underlying, _) => peel(underlying)
        case other =>
          other.dealias match {
            case AnnotatedType(underlying, _) => peel(underlying)
            case dealiased => dealiased
          }
      }
    }

    def evolutionValue(annotation: Term): Option[String] = {
      annotation match {
        case Apply(_, args) =>
          args.collectFirst {
            case NamedArg("evolution", Select(_, name)) => name
            case NamedArg("evolution", term) =>
              val rendered = term.show
              if rendered.endsWith(".ENABLED") then "ENABLED"
              else if rendered.endsWith(".DISABLED") then "DISABLED"
              else "INHERIT"
          }
        case _ => None
      }
    }

    def evolvingValue(annotation: Term): Option[Boolean] = {
      annotation match {
        case Apply(_, args) =>
          args.collectFirst {
            case NamedArg("evolving", Literal(BooleanConstant(value))) => value
          }
        case _ => None
      }
    }

    def compatibleStruct(symbol: Symbol): Boolean = {
      symbol.annotations.find(_.tpe <:< TypeRepr.of[ForyStruct]) match {
        case Some(annotation) =>
          val evolution = evolutionValue(annotation).getOrElse("INHERIT")
          if evolution == "DISABLED" then false
          else evolvingValue(annotation).getOrElse(true) || evolution == "ENABLED"
        case None => false
      }
    }

    def loop(tpe: TypeRepr): Boolean = {
      val base = peel(tpe.widen)
      compatibleStruct(base.typeSymbol) ||
      (base match {
        case AppliedType(_, args) => args.exists(loop)
        case _ => false
      })
    }

    loop(tpe)
  }

  private def typeIdForAnnotation(using q: Quotes)(annotation: q.reflect.Term): Option[Int] = {
    import q.reflect.*
    val annotationName = annotation.tpe.typeSymbol.fullName
    annotationName match {
      case "org.apache.fory.annotation.Int8Type" => Some(Types.INT8)
      case "org.apache.fory.annotation.UInt8Type" => Some(Types.UINT8)
      case "org.apache.fory.annotation.UInt16Type" => Some(Types.UINT16)
      case "org.apache.fory.annotation.Float16Type" => Some(Types.FLOAT16)
      case "org.apache.fory.annotation.BFloat16Type" => Some(Types.BFLOAT16)
      case "org.apache.fory.annotation.Int32Type" =>
        Some(if annotationEncoding(annotation).contains("FIXED") then Types.INT32 else Types.VARINT32)
      case "org.apache.fory.annotation.UInt32Type" =>
        Some(if annotationEncoding(annotation).contains("FIXED") then Types.UINT32 else Types.VAR_UINT32)
      case "org.apache.fory.annotation.Int64Type" =>
        annotationEncoding(annotation) match {
          case Some("FIXED") => Some(Types.INT64)
          case Some("TAGGED") => Some(Types.TAGGED_INT64)
          case _ => Some(Types.VARINT64)
        }
      case "org.apache.fory.annotation.UInt64Type" =>
        annotationEncoding(annotation) match {
          case Some("FIXED") => Some(Types.UINT64)
          case Some("TAGGED") => Some(Types.TAGGED_UINT64)
          case _ => Some(Types.VAR_UINT64)
        }
      case _ => None
    }
  }

  private def annotationEncoding(using q: Quotes)(annotation: q.reflect.Term): Option[String] = {
    import q.reflect.*
    annotation match {
      case Apply(_, args) => args.collectFirst {
          case NamedArg("encoding", Select(_, name)) => name
          case NamedArg("encoding", Ident(name)) => name
        }
      case _ => None
    }
  }

  private def isRefAnnotation(using q: Quotes)(annotation: q.reflect.Term): Boolean = {
    annotation.tpe.typeSymbol.fullName == "org.apache.fory.annotation.Ref"
  }

  private def refTrackingFromAnnotations(using q: Quotes)(
      annotations: Iterable[q.reflect.Term]): Option[Boolean] = {
    annotations.find(isRefAnnotation).map(refAnnotationEnabled)
  }

  private def refAnnotationEnabled(using q: Quotes)(annotation: q.reflect.Term): Boolean = {
    import q.reflect.*
    annotation match {
      case Apply(_, args) =>
        args.collectFirst {
          case NamedArg("enable", Literal(BooleanConstant(value))) => value
          case Literal(BooleanConstant(value)) => value
        }.getOrElse(true)
      case _ => true
    }
  }
}
