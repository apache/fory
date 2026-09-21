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

import org.apache.fory.json.meta.JsonAsciiToken
import org.apache.fory.json.scala.ScalaJsonCodec

import scala.reflect.macros.blackbox

private[scala] object ScalaJsonCodecMacros {
  def derived[T: c.WeakTypeTag](c: blackbox.Context): c.Expr[ScalaJsonCodec[T]] =
    derive[T](c)(false)

  def stringEnum[T: c.WeakTypeTag](c: blackbox.Context): c.Expr[ScalaJsonCodec[T]] =
    derive[T](c)(true)

  private def derive[T: c.WeakTypeTag](c: blackbox.Context)(
      stringEnum: Boolean
  ): c.Expr[ScalaJsonCodec[T]] = {
    import c.universe._
    val rootType = weakTypeOf[T].dealias
    val root = rootType.typeSymbol
    if (!root.isClass || !root.asClass.isSealed || !root.asClass.isAbstract)
      c.abort(c.enclosingPosition, s"${root.fullName} must be an abstract sealed class or sealed trait")

    val cases = scala.collection.mutable.ArrayBuffer.empty[ClassSymbol]
    val visited = scala.collection.mutable.HashSet.empty[Symbol]
    def collect(owner: ClassSymbol): Unit =
      owner.knownDirectSubclasses.toList.sortBy(_.fullName).foreach { child =>
        val cls = child.asClass
        if (visited.add(cls)) {
          if (!cls.isAbstract) cases += cls
          if (cls.isSealed) collect(cls)
          else if (cls.isAbstract)
            c.abort(c.enclosingPosition, s"Sealed JSON hierarchy has an open abstract branch ${cls.fullName}")
        }
      }
    collect(root.asClass)
    if (cases.isEmpty)
      c.abort(c.enclosingPosition, s"${root.fullName} has no concrete closed cases")
    val entries = cases.map { child =>
      if (child.isModuleClass) {
        val value = c.internal.gen.mkAttributedRef(child.module)
        (q"$value.getClass", q"$value.asInstanceOf[AnyRef]")
      } else {
        if (stringEnum)
          c.abort(c.enclosingPosition, s"String enum representation requires singleton cases: ${child.fullName}")
        (q"classOf[${child.toType}]", q"null")
      }
    }
    val classes = entries.map(_._1).toList
    val singletons = entries.map(_._2).toList
    val names = cases.map(child => Literal(Constant(child.name.decodedName.toString.stripSuffix("$")))).toList
    val tokens = cases.map(child => "\"" + child.name.decodedName.toString.stripSuffix("$") + "\"")
    val packed = tokens.forall(token =>
      JsonAsciiToken.isLongPackable(token) && token.substring(1, token.length - 1).forall(ch =>
        ch >= ' ' && ch < 0x7f && ch != '"' && ch != '\\'))
    // Bound the generated identity chain; other schemas retain the table-based codec.
    if (stringEnum && cases.size <= 8 && packed) {
      val unknown = q"throw new _root_.org.apache.fory.json.ForyJsonException(${"Unknown Scala enum value"})"
      // Independent comparisons let the JIT select indices without an order-sensitive branch chain.
      val indexChecks = singletons.zipWithIndex.map {
        case (singleton, index) => q"if (value eq $singleton) index = $index"
      }
      val valueIndex = q"{ var index = -1; ..$indexChecks; if (index < 0) $unknown; index }"
      def read: Tree = {
        tokens.zip(singletons).filter(entry => JsonAsciiToken.isPackable(entry._1))
          .foldRight[Tree](q"null") { case ((token, singleton), next) =>
            val suffixLength = JsonAsciiToken.suffixLength(token.length)
            val method = TermName("tryReadNextStringToken" + suffixLength)
            val prefix = JsonAsciiToken.prefix(token)
            val mask = JsonAsciiToken.prefixMask(token.length)
            val matched =
              if (suffixLength == 0) q"reader.$method($prefix, $mask, ${token.length})"
              else q"reader.$method($prefix, $mask, ${JsonAsciiToken.suffix(token)}, ${token.length})"
            q"if ($matched) $singleton else $next"
          }
      }
      // The generated indices and token table must share compiler order; the factory's runtime
      // class-name sort can differ, especially for nested singleton hierarchies.
      val write = tokens.zip(singletons).foldRight[Tree](unknown) {
        case ((token, singleton), next) =>
          val output = q"""{
            prefix = ${JsonAsciiToken.prefix(token)}
            suffix = ${JsonAsciiToken.suffixLong(token)}
            length = ${token.length}
          }"""
          q"if (value eq $singleton) $output else $next"
      }
      return c.Expr[ScalaJsonCodec[T]](
        q"""new _root_.org.apache.fory.json.scala.internal.DerivedScalaJsonCodec[$rootType](
          classOf[$rootType], _root_.scala.Array[_root_.java.lang.Class[_]](..$classes),
          _root_.scala.Array[_root_.java.lang.String](..$names),
          _root_.scala.Array[_root_.scala.AnyRef](..$singletons), true) {
          override protected def stringEnumCodec(
              typeClass: _root_.java.lang.Class[_],
              values: _root_.scala.Array[_root_.java.lang.Object],
              labels: _root_.scala.Array[_root_.java.lang.String]
          ): _root_.org.apache.fory.json.codec.JsonValueCodec[_] =
            new _root_.org.apache.fory.json.scala.internal.ScalaEnumCodec(
              typeClass, _root_.scala.Array[_root_.scala.AnyRef](..$singletons),
              _root_.scala.Array[_root_.java.lang.String](..$names)) {
              override protected def valueIndex(value: _root_.java.lang.Object): Int = $valueIndex

              override def readLatin1(
                  reader: _root_.org.apache.fory.json.reader.Latin1JsonReader
              ): _root_.java.lang.Object = {
                val value = $read
                if (value != null) value else super.readLatin1(reader)
              }

              override def readUtf8(
                  reader: _root_.org.apache.fory.json.reader.Utf8JsonReader
              ): _root_.java.lang.Object = {
                val value = $read
                if (value != null) value else super.readUtf8(reader)
              }

              override def writeUtf8(
                  writer: _root_.org.apache.fory.json.writer.Utf8JsonWriter,
                  value: _root_.java.lang.Object
              ): Unit = {
                if (value == null) writer.writeNull()
                else {
                  var prefix = 0L
                  var suffix = 0L
                  var length = 0
                  $write
                  // One packed-write call avoids duplicating the JIT's inlining work per case.
                  writer.writeRawValue(prefix, suffix, length)
                }
              }
            }
        }"""
      )
    }
    c.Expr[ScalaJsonCodec[T]](
      q"""new _root_.org.apache.fory.json.scala.internal.DerivedScalaJsonCodec[$rootType](
        classOf[$rootType], _root_.scala.Array[_root_.java.lang.Class[_]](..$classes),
        _root_.scala.Array[_root_.java.lang.String](..$names),
        _root_.scala.Array[_root_.scala.AnyRef](..$singletons), $stringEnum)"""
    )
  }
}
