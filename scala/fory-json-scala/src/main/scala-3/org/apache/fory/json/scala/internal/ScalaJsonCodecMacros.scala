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

import org.apache.fory.json.ForyJsonException
import org.apache.fory.json.codec.JsonValueCodec
import org.apache.fory.json.meta.JsonAsciiToken
import org.apache.fory.json.reader.{Latin1JsonReader, Utf8JsonReader}
import org.apache.fory.json.scala.ScalaJsonCodec
import org.apache.fory.json.writer.Utf8JsonWriter

import scala.quoted.*

private[scala] object ScalaJsonCodecMacros {
  def derive[T: Type](stringEnum: Boolean)(using quotes: Quotes): Expr[ScalaJsonCodec[T]] = {
    import quotes.reflect.*

    val root = TypeRepr.of[T].dealias.typeSymbol
    val enumRoot = root.flags.is(Flags.Enum)
    if (!enumRoot && !root.flags.is(Flags.Sealed))
      report.errorAndAbort(s"${root.fullName} is not a Scala 3 enum or sealed type")
    if (!enumRoot && !root.flags.is(Flags.Abstract) && !root.flags.is(Flags.Trait))
      report.errorAndAbort(s"${root.fullName} must be an abstract sealed class or sealed trait")

    // Compiler symbols are trusted static schema metadata. JSON input never participates in this
    // traversal and later selects only a logical name from the validated generated table.
    val cases =
      if (enumRoot) root.children.filter(_.flags.is(Flags.Case))
      else {
        val result = scala.collection.mutable.ArrayBuffer.empty[Symbol]
        val visited = scala.collection.mutable.HashSet.empty[Symbol]
        def collect(owner: Symbol): Unit =
          owner.children.foreach { child =>
            if (visited.add(child)) {
              val concrete =
                !child.flags.is(Flags.Abstract) && !child.flags.is(Flags.Trait)
              if (concrete) result += child
              if (child.flags.is(Flags.Sealed)) collect(child)
              else if (!concrete)
                report.errorAndAbort(
                  s"Sealed JSON hierarchy has an open abstract branch ${child.fullName}"
                )
            }
          }
        collect(root)
        result.toList
      }
    if (cases.isEmpty)
      report.errorAndAbort(s"${root.fullName} has no concrete closed cases")

    val rootClass =
      Literal(ClassOfConstant(TypeRepr.of[T].dealias)).asExprOf[Class[?]]
    val caseExpressions = cases.map { child =>
      if (child.flags.is(Flags.Module) || child.primaryConstructor == Symbol.noSymbol) {
        val value =
          if (enumRoot) Select.unique(Ref(root.companionModule), child.name).asExpr
          else {
            val module = if (child.isTerm) child else child.companionModule
            if (module == Symbol.noSymbol)
              report.errorAndAbort(s"Cannot resolve Scala singleton ${child.fullName}")
            Ref(module).asExpr
          }
        val singleton = '{ $value.asInstanceOf[AnyRef] }
        ('{ $singleton.getClass }, singleton)
      } else {
        if (stringEnum)
          report.errorAndAbort(s"String enum representation requires singleton cases: ${child.fullName}")
        (Literal(ClassOfConstant(child.typeRef)).asExprOf[Class[?]], '{ null })
      }
    }
    val classExpressions = caseExpressions.map(_._1)
    val singletonExpressions = caseExpressions.map(_._2)
    val nameExpressions = cases.map(child => Expr(child.name.stripSuffix("$")))

    val tokens = cases.map(child => "\"" + child.name.stripSuffix("$") + "\"")
    val packed = tokens.forall(token =>
      JsonAsciiToken.isLongPackable(token) && token.substring(1, token.length - 1).forall(ch =>
        ch >= ' ' && ch < 0x7f && ch != '"' && ch != '\\'))
    // Bound the generated identity chain; other schemas retain the table-based codec.
    if (stringEnum && cases.size <= 8 && packed) {
      // The generated indices and token table must share compiler order; the factory's runtime
      // class-name sort can differ, especially for nested singleton hierarchies.
      def enumIndex(value: Expr[Object])(using Quotes): Expr[Int] = {
        val unknown = '{ throw new ForyJsonException("Unknown Scala enum value") }
        // Independent comparisons let the JIT select indices without an order-sensitive branch chain.
        val selected = singletonExpressions.zipWithIndex.foldLeft[Expr[Int]](Expr(-1)) {
          case (previous, (singleton, index)) =>
            '{ val prior = $previous; if ($value eq $singleton) ${ Expr(index) } else prior }
        }
        '{ val index = $selected; if (index < 0) $unknown else index }
      }
      def read(reader: Expr[?])(using localQuotes: Quotes): Expr[Object] = {
        import localQuotes.reflect.*
        tokens.zip(singletonExpressions).filter(entry => JsonAsciiToken.isPackable(entry._1))
          .foldRight[Expr[Object]]('{ null }) { case ((token, singleton), next) =>
            val suffixLength = JsonAsciiToken.suffixLength(token.length)
            val arguments = List(
              Expr(JsonAsciiToken.prefix(token)).asTerm,
              Expr(JsonAsciiToken.prefixMask(token.length)).asTerm) ++
              (if (suffixLength == 0) Nil else List(Expr(JsonAsciiToken.suffix(token)).asTerm)) :+
              Expr(token.length).asTerm
            val matched = Select.unique(reader.asTerm, "tryReadNextStringToken" + suffixLength)
              .appliedToArgs(arguments).asExprOf[Boolean]
            '{ if ($matched) $singleton else $next }
          }
      }
      // Use the splice's Quotes so method parameters remain in their defining scope on Scala 3.9+.
      def write(
          value: Expr[Object],
          prefix: Long => Expr[Unit],
          suffix: Long => Expr[Unit],
          length: Int => Expr[Unit]
      )(using Quotes): Expr[Unit] = {
        val unknown = '{ throw new ForyJsonException("Unknown Scala enum value") }
        tokens.zip(singletonExpressions).foldRight[Expr[Unit]](unknown) {
          case ((token, singleton), next) =>
            val output = '{
              ${ prefix(JsonAsciiToken.prefix(token)) }
              ${ suffix(JsonAsciiToken.suffixLong(token)) }
              ${ length(token.length) }
            }
            '{ if ($value eq $singleton) $output else $next }
        }
      }
      return '{
        new DerivedScalaJsonCodec[T](
          $rootClass.asInstanceOf[Class[T]],
          Array[Class[_]](${ Varargs(classExpressions) }*),
          Array[String](${ Varargs(nameExpressions) }*),
          Array[AnyRef](${ Varargs(singletonExpressions) }*),
          true
        ) {
          override protected def stringEnumCodec(
              typeClass: Class[_], values: Array[Object], labels: Array[String]
          ): JsonValueCodec[_] = new ScalaEnumCodec(
            typeClass, Array[AnyRef](${ Varargs(singletonExpressions) }*),
            Array[String](${ Varargs(nameExpressions) }*)) {
            override protected def valueIndex(value: Object): Int = ${ enumIndex('value) }

            override def readLatin1(reader: Latin1JsonReader): Object = {
              val value = ${ read('reader) }
              if (value != null) value else super.readLatin1(reader)
            }

            override def readUtf8(reader: Utf8JsonReader): Object = {
              val value = ${ read('reader) }
              if (value != null) value else super.readUtf8(reader)
            }

            override def writeUtf8(writer: Utf8JsonWriter, value: Object): Unit = {
              if (value == null) writer.writeNull()
              else {
                var prefix = 0L
                var suffix = 0L
                var length = 0
                ${ write('value, p => '{ prefix = ${ Expr(p) } },
                  s => '{ suffix = ${ Expr(s) } }, n => '{ length = ${ Expr(n) } }) }
                // A single call site keeps the packed write within the JIT's inlining budget.
                writer.writeRawValue(prefix, suffix, length)
              }
            }
          }
        }
      }
    }
    '{
      new DerivedScalaJsonCodec[T](
        $rootClass.asInstanceOf[Class[T]],
        Array[Class[_]](${ Varargs(classExpressions) }*),
        Array[String](${ Varargs(nameExpressions) }*),
        Array[AnyRef](${ Varargs(singletonExpressions) }*),
        ${ Expr(stringEnum) }
      )
    }
  }
}
