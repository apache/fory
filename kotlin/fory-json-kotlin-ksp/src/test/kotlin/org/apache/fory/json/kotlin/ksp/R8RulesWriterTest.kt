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

package org.apache.fory.json.kotlin.ksp

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class R8RulesWriterTest {
  @Test
  fun retainsObjectContract() {
    val model =
      jsonModel(
        members =
          listOf(
            JvmMember(MemberKind.FIELD, "example.Profile", "id", "J"),
            JvmMember(
              MemberKind.METHOD,
              "example.Profile",
              "getName",
              "()Ljava/lang/String;",
            ),
            JvmMember(
              MemberKind.METHOD,
              "example.Profile",
              "putExtra",
              "(Ljava/lang/String;Ljava/lang/Object;)V",
            ),
            JvmMember(MemberKind.METHOD, "example.Profile", "validate", "()V"),
            JvmMember(MemberKind.METHOD, "example.Profile", "<init>", "(JLjava/lang/String;)V"),
            JvmMember(
              MemberKind.METHOD,
              "example.Profile",
              "<init>",
              "(JLjava/lang/String;Lkotlin/jvm/internal/DefaultConstructorMarker;)V",
            ),
            JvmMember(
              MemberKind.METHOD,
              "example.Profile",
              "<init>",
              "(JLjava/lang/String;ILkotlin/jvm/internal/DefaultConstructorMarker;)V",
            ),
          ),
        retainedAnnotations = setOf("org.apache.fory.json.annotation.JsonType", "kotlin.Metadata"),
        retainedTypes = setOf("example.ProfileSubtype"),
      )

    assertEquals(
      "META-INF/proguard/fory-json-example.Profile.pro",
      R8RulesWriter.resourcePath(model),
    )
    assertEquals(
      """-keepattributes Signature,RuntimeVisibleAnnotations
-keepattributes RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault,MethodParameters

-keep,allowoptimization class example.Profile
-keepclassmembers class example.Profile {
  <init>(long,java.lang.String);
  <init>(long,java.lang.String,int,kotlin.jvm.internal.DefaultConstructorMarker);
  <init>(long,java.lang.String,kotlin.jvm.internal.DefaultConstructorMarker);
  java.lang.String getName();
  long id;
  void putExtra(java.lang.String,java.lang.Object);
  void validate();
}

-keep,allowoptimization,allowobfuscation @interface kotlin.Metadata
-keep,allowoptimization,allowobfuscation @interface org.apache.fory.json.annotation.JsonType
-keep,allowoptimization class example.ProfileSubtype
""",
      R8RulesWriter.write(model),
    )

    val factoryRules =
      R8RulesWriter.write(
        jsonModel(
          members =
            listOf(
              JvmMember(
                MemberKind.METHOD,
                "example.Profile\$Companion",
                "create",
                "(J)Lexample/Profile;",
              ),
              JvmMember(
                MemberKind.METHOD,
                "example.Profile",
                "create",
                "(J)Lexample/Profile;",
              ),
            ),
        )
      )
    assertContains(factoryRules, "class example.Profile\$Companion")
    assertContains(factoryRules, "example.Profile create(long);")
  }

  @Test
  fun retainsSingletonContract() {
    val model =
      jsonModel(
        targetBinaryName = "example.Marker",
        members =
          listOf(
            JvmMember(
              MemberKind.FIELD,
              "example.Marker",
              "INSTANCE",
              "Lexample/Marker;",
            )
          ),
        retainedAnnotations = setOf("org.apache.fory.json.annotation.JsonType"),
      )

    assertEquals(
      """-keepattributes Signature,RuntimeVisibleAnnotations
-keepattributes RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault,MethodParameters

-keep,allowoptimization class example.Marker
-keepclassmembers class example.Marker {
  example.Marker INSTANCE;
}

-keep,allowoptimization,allowobfuscation @interface org.apache.fory.json.annotation.JsonType
""",
      R8RulesWriter.write(model),
    )
  }

  @Test
  fun retainsValueContract() {
    val model =
      jsonModel(
        targetBinaryName = "example.UserId",
        members =
          listOf(
            JvmMember(MemberKind.FIELD, "example.UserId", "value", "Lexample/RawId;"),
            JvmMember(MemberKind.METHOD, "example.UserId", "<init>", "(Lexample/RawId;)V"),
            JvmMember(
              MemberKind.METHOD,
              "example.UserId",
              "constructor-impl",
              "(Lexample/RawId;)Lexample/RawId;",
            ),
            JvmMember(
              MemberKind.METHOD,
              "example.UserId",
              "box-impl",
              "(Lexample/RawId;)Lexample/UserId;",
            ),
            JvmMember(
              MemberKind.METHOD,
              "example.UserId",
              "unbox-impl",
              "()Lexample/RawId;",
            ),
            JvmMember(MemberKind.FIELD, "example.RawId", "raw", "J"),
            JvmMember(MemberKind.METHOD, "example.RawId", "<init>", "(J)V"),
            JvmMember(MemberKind.METHOD, "example.RawId", "constructor-impl", "(J)J"),
            JvmMember(MemberKind.METHOD, "example.RawId", "box-impl", "(J)Lexample/RawId;"),
            JvmMember(MemberKind.METHOD, "example.RawId", "unbox-impl", "()J"),
          ),
        retainedAnnotations = setOf("kotlin.Metadata"),
      )

    assertEquals(
      """-keepattributes Signature,RuntimeVisibleAnnotations
-keepattributes RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault,MethodParameters

-keep,allowoptimization class example.UserId
-keepclassmembers class example.UserId {
  <init>(example.RawId);
  example.RawId constructor-impl(example.RawId);
  example.RawId unbox-impl();
  example.RawId value;
  example.UserId box-impl(example.RawId);
}

-keep,allowoptimization,allowobfuscation class example.RawId
-keepclassmembers class example.RawId {
  <init>(long);
  example.RawId box-impl(long);
  long constructor-impl(long);
  long raw;
  long unbox-impl();
}

-keep,allowoptimization,allowobfuscation @interface kotlin.Metadata
""",
      R8RulesWriter.write(model),
    )
  }

  @Test
  fun retainsSealedContract() {
    val model =
      jsonModel(
        targetBinaryName = "example.Shape",
        retainedAnnotations =
          setOf("org.apache.fory.json.annotation.JsonSubTypes", "kotlin.Metadata"),
        retainedTypes = setOf("example.Square", "example.Circle"),
      )

    val rules = R8RulesWriter.write(model)
    assertEquals(
      """-keepattributes Signature,RuntimeVisibleAnnotations
-keepattributes RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault,MethodParameters

-keep,allowoptimization class example.Shape

-keep,allowoptimization,allowobfuscation @interface kotlin.Metadata
-keep,allowoptimization,allowobfuscation @interface org.apache.fory.json.annotation.JsonSubTypes
-keep,allowoptimization class example.Circle
-keep,allowoptimization class example.Square
""",
      rules,
    )
    assertFalse(rules.contains('*'), rules)
  }

  @Test
  fun retainsCodecConstructors() {
    val codecTypes =
      linkedSetOf(
        "example.codec.MapValueCodec",
        "example.codec.WholeValueCodec",
        "example.codec.KeyCodec",
        "example.codec.ContentCodec",
        "example.codec.ElementCodec",
      )
    val direct = jsonModel(codecTypes = codecTypes)
    val mixin =
      jsonModel(
        targetBinaryName = "example.ExternalProfile",
        mixinBinaryName = "mixins.ProfileMixin",
        codecTypes = codecTypes,
      )

    assertEquals(
      """-keepattributes Signature,RuntimeVisibleAnnotations
-keepattributes RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault,MethodParameters

-keep,allowoptimization class example.Profile

-keep,allowoptimization,allowobfuscation class example.codec.ContentCodec
-keepclassmembers class example.codec.ContentCodec {
  public <init>();
}

-keep,allowoptimization,allowobfuscation class example.codec.ElementCodec
-keepclassmembers class example.codec.ElementCodec {
  public <init>();
}

-keep,allowoptimization,allowobfuscation class example.codec.KeyCodec
-keepclassmembers class example.codec.KeyCodec {
  public <init>();
}

-keep,allowoptimization,allowobfuscation class example.codec.MapValueCodec
-keepclassmembers class example.codec.MapValueCodec {
  public <init>();
}

-keep,allowoptimization,allowobfuscation class example.codec.WholeValueCodec
-keepclassmembers class example.codec.WholeValueCodec {
  public <init>();
}

""",
      R8RulesWriter.write(direct),
    )
    assertEquals(
      """-keepattributes Signature,RuntimeVisibleAnnotations
-keepattributes RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault,MethodParameters

-keep,allowoptimization class example.ExternalProfile

-keep,allowoptimization class mixins.ProfileMixin

-keep,allowoptimization,allowobfuscation class example.codec.ContentCodec
-keepclassmembers class example.codec.ContentCodec {
  public <init>();
}

-keep,allowoptimization,allowobfuscation class example.codec.ElementCodec
-keepclassmembers class example.codec.ElementCodec {
  public <init>();
}

-keep,allowoptimization,allowobfuscation class example.codec.KeyCodec
-keepclassmembers class example.codec.KeyCodec {
  public <init>();
}

-keep,allowoptimization,allowobfuscation class example.codec.MapValueCodec
-keepclassmembers class example.codec.MapValueCodec {
  public <init>();
}

-keep,allowoptimization,allowobfuscation class example.codec.WholeValueCodec
-keepclassmembers class example.codec.WholeValueCodec {
  public <init>();
}

""",
      R8RulesWriter.write(mixin),
    )
    listOf(direct, mixin).forEach { model ->
      val rules = R8RulesWriter.write(model)
      codecTypes.forEach { codecType ->
        assertEquals(
          1,
          rules.lineSequence().count {
            it == "-keep,allowoptimization,allowobfuscation class $codecType"
          },
        )
        assertEquals(
          1,
          rules.lineSequence().count { it == "-keepclassmembers class $codecType {" },
        )
      }
      assertEquals(codecTypes.size, rules.lineSequence().count { it == "  public <init>();" })
      assertFalse(rules.contains('*'), rules)
      CODEC_SENTINELS.forEach { assertFalse(rules.contains(it), rules) }
    }
  }

  @Test
  fun retainsNestedCodecMetadata() {
    val rules = R8RulesWriter.write(jsonModel(codecTypes = setOf("example.Codecs\$NestedCodec")))

    assertContains(rules, "-keepattributes InnerClasses,EnclosingMethod")
    assertContains(
      rules,
      "-keepclassmembers class example.Codecs\$NestedCodec {\n" + "  public <init>();\n" + "}",
    )
  }

  @Test
  fun identifiesMixinRequest() {
    val direct = jsonModel(targetBinaryName = "example.Profile")
    val mixin =
      jsonModel(
        targetBinaryName = "example.Profile",
        mixinBinaryName = "mixins.ProfileMixin",
        members =
          listOf(
            JvmMember(MemberKind.METHOD, "example.Profile", "getName", "()Ljava/lang/String;"),
            JvmMember(
              MemberKind.FIELD,
              "mixins.ProfileMixin",
              "renamed",
              "Ljava/lang/String;",
            ),
          ),
      )
    val second = mixin.copy(mixinBinaryName = "mixins.SecondProfileMixin")

    assertEquals(
      "META-INF/proguard/fory-json-mixin-mixins.ProfileMixin.pro",
      R8RulesWriter.resourcePath(mixin),
    )
    assertEquals(
      "META-INF/proguard/fory-json-mixin-mixins.SecondProfileMixin.pro",
      R8RulesWriter.resourcePath(second),
    )
    assertNotEquals(R8RulesWriter.resourcePath(direct), R8RulesWriter.resourcePath(mixin))
    assertNotEquals(R8RulesWriter.resourcePath(mixin), R8RulesWriter.resourcePath(second))
    assertEquals(
      """-keepattributes Signature,RuntimeVisibleAnnotations
-keepattributes RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault,MethodParameters

-keep,allowoptimization class example.Profile
-keepclassmembers class example.Profile {
  java.lang.String getName();
}

-keep,allowoptimization class mixins.ProfileMixin
-keepclassmembers class mixins.ProfileMixin {
  java.lang.String renamed;
}

""",
      R8RulesWriter.write(mixin),
    )
  }

  private companion object {
    val CODEC_SENTINELS =
      setOf(
        "org.apache.fory.json.annotation.JsonCodec\$NoJsonValueCodec",
        "org.apache.fory.json.annotation.JsonCodec\$NoMapKeyCodec",
      )
  }
}
