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

package org.apache.fory.builder;

import static org.apache.fory.type.TypeUtils.OBJECT_TYPE;

import java.util.Collections;
import java.util.List;
import org.apache.fory.Fory;
import org.apache.fory.annotation.ForyDebug;
import org.apache.fory.annotation.ForyStruct;
import org.apache.fory.builder.Generated.GeneratedStaticCompatibleSerializer;
import org.apache.fory.codegen.Code;
import org.apache.fory.codegen.CodeGenerator;
import org.apache.fory.codegen.Expression;
import org.apache.fory.codegen.Expression.Invoke;
import org.apache.fory.codegen.Expression.Reference;
import org.apache.fory.config.ForyBuilder;
import org.apache.fory.context.ReadContext;
import org.apache.fory.meta.TypeDef;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.serializer.CompatibleSerializer;
import org.apache.fory.serializer.FieldGroups.SerializationFieldInfo;
import org.apache.fory.type.Descriptor;
import org.apache.fory.util.Preconditions;
import org.apache.fory.util.StringUtils;
import org.apache.fory.util.record.RecordComponent;
import org.apache.fory.util.record.RecordUtils;

/**
 * Builds GraalVM read-only compatible serializers that bind the runtime remote {@link TypeDef} in
 * the serializer constructor.
 *
 * <p>The generated class is keyed by the local Java class, not by a fixed remote schema. Its
 * constructor receives the runtime remote {@link TypeDef}; {@link
 * org.apache.fory.serializer.StaticGeneratedStructSerializer} rebuilds remote read order through
 * the same descriptor-grouper owner used by {@link CompatibleSerializer}.
 *
 * @see ForyBuilder#withMetaShare
 * @see CompatibleSerializer
 */
public final class StaticCompatibleCodecBuilder extends ObjectCodecBuilder {
  private static final int DISPATCH_GROUP_SIZE = 8;
  // Hidden generated serializers cannot use private split helpers because Janino emits private
  // self-invokes against the source binary name, which Lookup#defineHiddenClass cannot resolve.
  private static final String DISPATCH_METHOD_MODIFIERS = "final";

  private final List<Descriptor> localDescriptors;
  private final boolean debug;

  public StaticCompatibleCodecBuilder(TypeRef<?> beanType, Fory fory, TypeDef typeDef) {
    super(beanType, fory, GeneratedStaticCompatibleSerializer.class);
    Preconditions.checkArgument(
        !fory.getConfig().checkClassVersion(),
        "Class version check should be disabled when compatible mode is enabled.");
    localDescriptors = Collections.unmodifiableList(Descriptor.getDescriptors(beanClass));
    debug =
        beanClass.isAnnotationPresent(ForyStruct.class)
            && beanClass.isAnnotationPresent(ForyDebug.class);
  }

  @Override
  protected String codecSuffix() {
    return "StaticCompatible";
  }

  @Override
  public String genCode() {
    ctx.setPackage(CodeGenerator.getPackage(beanClass));
    String className = codecClassName(beanClass);
    ctx.setClassName(className);
    ctx.extendsClasses(ctx.type(parentSerializerClass));
    ctx.reserveName(POJO_CLASS_TYPE_NAME);
    ctx.addImports(List.class, TypeDef.class, Descriptor.class, SerializationFieldInfo.class);
    String constructorCode =
        StringUtils.format(
            ""
                + "super(${typeResolver}, ${cls}, ${typeDef}, Descriptor.getDescriptors(${cls}));\n"
                + "this.${generatedTypeResolver} = (${generatedTypeResolverType}) ${typeResolver};\n",
            "typeResolver",
            CONSTRUCTOR_TYPE_RESOLVER_NAME,
            "cls",
            POJO_CLASS_TYPE_NAME,
            "typeDef",
            "_f_typeDef",
            "generatedTypeResolver",
            TYPE_RESOLVER_NAME,
            "generatedTypeResolverType",
            ctx.type(concreteTypeResolverType));
    ctx.addConstructor(
        constructorCode,
        TypeResolver.class,
        CONSTRUCTOR_TYPE_RESOLVER_NAME,
        Class.class,
        POJO_CLASS_TYPE_NAME,
        TypeDef.class,
        "_f_typeDef");
    ctx.addMethod("getGeneratedDescriptors", "return Descriptor.getDescriptors(type);", List.class);
    ctx.overrideMethod(
        "readCompatible",
        isRecord ? genRecordCompatibleRead() : genObjectCompatibleRead(),
        Object.class,
        ReadContext.class,
        READ_CONTEXT_NAME);
    genDispatchMethods();
    return ctx.genCode();
  }

  @Override
  protected void addCommonImports() {
    super.addCommonImports();
    ctx.addImport(GeneratedStaticCompatibleSerializer.class);
  }

  @Override
  public Expression buildEncodeExpression() {
    throw new IllegalStateException("unreachable");
  }

  @Override
  public Expression buildDecodeExpression() {
    throw new IllegalStateException("unreachable");
  }

  private String genObjectCompatibleRead() {
    ctx.clearExprState();
    Code.ExprCode beanCode = newBean().genCode(ctx);
    String bean = beanCode.value().toString();
    StringBuilder code = new StringBuilder();
    if (StringUtils.isNotBlank(beanCode.code())) {
      code.append(beanCode.code()).append('\n');
    }
    code.append("if (")
        .append(READ_CONTEXT_NAME)
        .append(".hasPreservedRefId()) {\n")
        .append("  ")
        .append(READ_CONTEXT_NAME)
        .append(".reference(")
        .append(bean)
        .append(");\n")
        .append("}\n")
        .append("for (int _f_i = 0; _f_i < remoteFields.size(); _f_i++) {\n")
        .append("  RemoteFieldInfo _f_remoteField = (RemoteFieldInfo) remoteFields.get(_f_i);\n")
        .append("  readMatchedField(")
        .append(READ_CONTEXT_NAME)
        .append(", ")
        .append(bean)
        .append(", _f_remoteField);\n")
        .append("}\n")
        .append("return ")
        .append(bean)
        .append(';');
    return code.toString();
  }

  private String genRecordCompatibleRead() {
    RecordComponent[] components = RecordUtils.getRecordComponents(beanClass);
    StringBuilder code = new StringBuilder();
    String recordValues;
    if (recordCtrAccessible) {
      recordValues = "_f_recordValues";
      code.append("Object[] ")
          .append(recordValues)
          .append(" = new Object[")
          .append(components.length)
          .append("];\n");
      for (int i = 0; i < components.length; i++) {
        String defaultValue = boxedDefaultValue(components[i].getType());
        if (defaultValue != null) {
          code.append(recordValues)
              .append("[")
              .append(i)
              .append("] = ")
              .append(defaultValue)
              .append(";\n");
        }
      }
    } else {
      ctx.clearExprState();
      Code.ExprCode recordValuesCode = buildComponentsArray().genCode(ctx);
      recordValues = recordValuesCode.value().toString();
      if (StringUtils.isNotBlank(recordValuesCode.code())) {
        code.append(recordValuesCode.code()).append('\n');
      }
    }
    code.append("for (int _f_i = 0; _f_i < remoteFields.size(); _f_i++) {\n")
        .append("  RemoteFieldInfo _f_remoteField = (RemoteFieldInfo) remoteFields.get(_f_i);\n")
        .append("  readMatchedRecordField(")
        .append(READ_CONTEXT_NAME)
        .append(", ")
        .append(recordValues)
        .append(", _f_remoteField);\n")
        .append("}\n");
    if (recordCtrAccessible) {
      code.append("return new ")
          .append(ctx.type(beanClass))
          .append("(")
          .append(recordConstructorArgs(components, recordValues))
          .append(");");
    } else {
      ctx.clearExprState();
      Reference values = new Reference(recordValues, objectArrayTypeRef, false);
      Code.ExprCode newRecord =
          new Invoke(
                  getObjectInstantiator(beanClass), "newInstanceWithArguments", OBJECT_TYPE, values)
              .genCode(ctx);
      if (StringUtils.isNotBlank(newRecord.code())) {
        code.append(newRecord.code()).append('\n');
      }
      code.append("return ").append(newRecord.value()).append(';');
    }
    return code.toString();
  }

  private void genDispatchMethods() {
    String valueType =
        ctx.sourcePublicAccessible(beanClass) ? ctx.type(beanClass) : ctx.type(Object.class);
    TypeRef<?> valueTypeRef = ctx.sourcePublicAccessible(beanClass) ? beanType : OBJECT_TYPE;
    int groupCount = (localDescriptors.size() + DISPATCH_GROUP_SIZE - 1) / DISPATCH_GROUP_SIZE;
    if (isRecord) {
      ctx.addMethod(
          DISPATCH_METHOD_MODIFIERS,
          "readMatchedRecordField",
          genDispatchRouter("readMatchedRecordField", groupCount),
          void.class,
          ReadContext.class,
          READ_CONTEXT_NAME,
          Object[].class,
          "_f_recordValues",
          "RemoteFieldInfo",
          "_f_remoteField");
      for (int group = 0; group < groupCount; group++) {
        ctx.addMethod(
            DISPATCH_METHOD_MODIFIERS,
            "readMatchedRecordField" + group,
            genRecordDispatchGroup(group),
            void.class,
            ReadContext.class,
            READ_CONTEXT_NAME,
            Object[].class,
            "_f_recordValues",
            "RemoteFieldInfo",
            "_f_remoteField");
      }
      return;
    }
    ctx.addMethod(
        DISPATCH_METHOD_MODIFIERS,
        "readMatchedField",
        genDispatchRouter("readMatchedField", groupCount),
        void.class,
        ReadContext.class,
        READ_CONTEXT_NAME,
        valueType,
        "_f_value",
        "RemoteFieldInfo",
        "_f_remoteField");
    for (int group = 0; group < groupCount; group++) {
      ctx.addMethod(
          DISPATCH_METHOD_MODIFIERS,
          "readMatchedField" + group,
          genObjectDispatchGroup(group, valueTypeRef),
          void.class,
          ReadContext.class,
          READ_CONTEXT_NAME,
          valueType,
          "_f_value",
          "RemoteFieldInfo",
          "_f_remoteField");
    }
  }

  private String genDispatchRouter(String methodPrefix, int groupCount) {
    StringBuilder code = new StringBuilder();
    for (int group = 0; group < groupCount; group++) {
      int upperBound = Math.min(localDescriptors.size(), (group + 1) * DISPATCH_GROUP_SIZE);
      if (group == 0) {
        code.append("if (_f_remoteField.matchedId >= 0 && _f_remoteField.matchedId < ")
            .append(upperBound)
            .append(") {\n");
      } else {
        code.append("if (_f_remoteField.matchedId < ").append(upperBound).append(") {\n");
      }
      code.append("  ")
          .append(methodPrefix)
          .append(group)
          .append("(")
          .append(READ_CONTEXT_NAME)
          .append(", ");
      code.append(isRecord ? "_f_recordValues" : "_f_value");
      code.append(", _f_remoteField);\n").append("  return;\n").append("}\n");
    }
    appendDebugRemoteRead(code, "before skip", "_f_remoteField", 0);
    code.append("skipField(").append(READ_CONTEXT_NAME).append(", _f_remoteField);\n");
    appendDebugRemoteRead(code, "after skip", "_f_remoteField", 0);
    return code.toString();
  }

  private String genObjectDispatchGroup(int group, TypeRef<?> valueTypeRef) {
    int start = group * DISPATCH_GROUP_SIZE;
    int end = Math.min(localDescriptors.size(), start + DISPATCH_GROUP_SIZE);
    StringBuilder code = new StringBuilder("switch (_f_remoteField.matchedId) {\n");
    for (int i = start; i < end; i++) {
      Descriptor descriptor = localDescriptors.get(i);
      code.append("  case ")
          .append(i)
          .append(": {\n")
          .append(debugRemoteReadCode("before read", "_f_remoteField", 4))
          .append("    if (_f_remoteField.serializationFieldInfo.fieldConverter != null) {\n")
          .append("      Object _f_fieldValue = readFieldConverterSource(")
          .append(READ_CONTEXT_NAME)
          .append(", _f_remoteField);\n")
          .append(debugRemoteReadCode("after read", "_f_remoteField", 6))
          .append(
              "      _f_remoteField.serializationFieldInfo.fieldConverter.set(_f_value, _f_fieldValue);\n")
          .append("    } else {\n")
          .append(
              "      SerializationFieldInfo _f_localField = localFieldInfo(_f_remoteField.matchedId);\n")
          .append("      if (!canReadGeneratedField(_f_remoteField, _f_localField)) {\n")
          .append(debugRemoteReadCode("before skip", "_f_remoteField", 8))
          .append("        skipField(")
          .append(READ_CONTEXT_NAME)
          .append(", _f_remoteField);\n")
          .append(debugRemoteReadCode("after skip", "_f_remoteField", 8))
          .append("        return;\n")
          .append("      }\n")
          .append("      Object _f_fieldValue = readCompatibleFieldValue(")
          .append(READ_CONTEXT_NAME)
          .append(", _f_remoteField, _f_localField);\n")
          .append(debugRemoteReadCode("after read", "_f_remoteField", 6))
          .append(indent(genSetFieldCode(descriptor, valueTypeRef), 6))
          .append('\n')
          .append("    }\n")
          .append("    return;\n")
          .append("  }\n");
    }
    code.append("  default:\n")
        .append(debugRemoteReadCode("before skip", "_f_remoteField", 4))
        .append("    skipField(")
        .append(READ_CONTEXT_NAME)
        .append(", _f_remoteField);\n")
        .append(debugRemoteReadCode("after skip", "_f_remoteField", 4))
        .append("}\n");
    return code.toString();
  }

  private String genRecordDispatchGroup(int group) {
    int start = group * DISPATCH_GROUP_SIZE;
    int end = Math.min(localDescriptors.size(), start + DISPATCH_GROUP_SIZE);
    StringBuilder code = new StringBuilder("switch (_f_remoteField.matchedId) {\n");
    for (int i = start; i < end; i++) {
      Descriptor descriptor = localDescriptors.get(i);
      Integer componentIndex = recordReversedMapping.get(descriptor.getName());
      if (componentIndex == null) {
        continue;
      }
      code.append("  case ")
          .append(i)
          .append(": {\n")
          .append(debugRemoteReadCode("before read", "_f_remoteField", 4))
          .append(
              "    SerializationFieldInfo _f_localField = localFieldInfo(_f_remoteField.matchedId);\n")
          .append("    if (canReadGeneratedField(_f_remoteField, _f_localField)) {\n")
          .append("      _f_recordValues[")
          .append(componentIndex)
          .append("] = readCompatibleFieldValue(")
          .append(READ_CONTEXT_NAME)
          .append(", _f_remoteField, _f_localField);\n")
          .append(debugRemoteReadCode("after read", "_f_remoteField", 6))
          .append("    } else {\n")
          .append(debugRemoteReadCode("before skip", "_f_remoteField", 6))
          .append("      skipField(")
          .append(READ_CONTEXT_NAME)
          .append(", _f_remoteField);\n")
          .append(debugRemoteReadCode("after skip", "_f_remoteField", 6))
          .append("    }\n")
          .append("    return;\n")
          .append("  }\n");
    }
    code.append("  default:\n")
        .append(debugRemoteReadCode("before skip", "_f_remoteField", 4))
        .append("    skipField(")
        .append(READ_CONTEXT_NAME)
        .append(", _f_remoteField);\n")
        .append(debugRemoteReadCode("after skip", "_f_remoteField", 4))
        .append("}\n");
    return code.toString();
  }

  private void appendDebugRemoteRead(
      StringBuilder code, String stage, String remoteField, int indent) {
    if (!debug) {
      return;
    }
    code.append(debugRemoteReadCode(stage, remoteField, indent));
  }

  private String debugRemoteReadCode(String stage, String remoteField, int indent) {
    if (!debug) {
      return "";
    }
    StringBuilder code = new StringBuilder();
    for (int i = 0; i < indent; i++) {
      code.append(' ');
    }
    code.append("if (org.apache.fory.util.Utils.DEBUG_OUTPUT_ENABLED) { debugRemoteReadField(\"")
        .append(stage)
        .append("\", ")
        .append(remoteField)
        .append(", ")
        .append(READ_CONTEXT_NAME)
        .append("); }\n");
    return code.toString();
  }

  private String genSetFieldCode(Descriptor descriptor, TypeRef<?> valueTypeRef) {
    ctx.clearExprState();
    Reference value = new Reference("_f_value", valueTypeRef, false);
    Reference fieldValue = new Reference("_f_fieldValue", OBJECT_TYPE, false);
    Expression setField =
        setFieldValue(value, descriptor, tryInlineCast(fieldValue, descriptor.getTypeRef()));
    Code.ExprCode setCode = setField.genCode(ctx);
    String code = ctx.optimizeMethodCode(setCode.code());
    return code == null ? "" : code;
  }

  private String recordConstructorArgs(RecordComponent[] components, String recordValues) {
    StringBuilder args = new StringBuilder();
    for (int i = 0; i < components.length; i++) {
      if (i > 0) {
        args.append(", ");
      }
      args.append(castRecordComponent(recordValues + "[" + i + "]", components[i].getType()));
    }
    return args.toString();
  }

  private String castRecordComponent(String value, Class<?> type) {
    if (!type.isPrimitive()) {
      return "(" + ctx.type(type) + ") " + value;
    }
    return "(" + ctx.type(boxedType(type)) + ") " + value;
  }

  private String boxedDefaultValue(Class<?> type) {
    if (!type.isPrimitive()) {
      return null;
    }
    if (type == boolean.class) {
      return "false";
    }
    if (type == char.class) {
      return "'\\0'";
    }
    if (type == long.class) {
      return "0L";
    }
    if (type == float.class) {
      return "0.0f";
    }
    if (type == double.class) {
      return "0.0d";
    }
    if (type == byte.class) {
      return "(byte) 0";
    }
    if (type == short.class) {
      return "(short) 0";
    }
    return "0";
  }

  private static Class<?> boxedType(Class<?> type) {
    if (type == boolean.class) {
      return Boolean.class;
    }
    if (type == byte.class) {
      return Byte.class;
    }
    if (type == char.class) {
      return Character.class;
    }
    if (type == short.class) {
      return Short.class;
    }
    if (type == int.class) {
      return Integer.class;
    }
    if (type == long.class) {
      return Long.class;
    }
    if (type == float.class) {
      return Float.class;
    }
    if (type == double.class) {
      return Double.class;
    }
    return type;
  }

  private static String indent(String code, int spaces) {
    String prefix = String.join("", Collections.nCopies(spaces, " "));
    return prefix + code.replace("\n", "\n" + prefix);
  }
}
