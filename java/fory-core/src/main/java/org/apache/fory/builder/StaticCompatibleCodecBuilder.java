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
import org.apache.fory.codegen.Expression.Literal;
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
  private Expression objectInstantiator;
  private String recordArgsFieldName;

  public StaticCompatibleCodecBuilder(TypeRef<?> beanType, Fory fory, TypeDef typeDef) {
    super(beanType, fory, GeneratedStaticCompatibleSerializer.class);
    Preconditions.checkArgument(
        !fory.getConfig().checkClassVersion(),
        "Class version check should be disabled when compatible mode is enabled.");
    localDescriptors =
        Collections.unmodifiableList(
            typeResolver.normalizeFieldDescriptors(
                beanClass, true, Descriptor.getDescriptors(beanClass)));
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
    if (!isRecord || !recordCtrAccessible) {
      generatedObjectInstantiator();
    }
    if (isRecord && !recordCtrAccessible) {
      recordArgsFieldName(RecordUtils.getRecordComponents(beanClass).length);
    }
    String readCompatibleCode = isRecord ? genRecordCompatibleRead() : genObjectCompatibleRead();
    genDispatchMethods();
    // Read/dispatch generation can add serializer fields and instance-init code. Add the
    // constructor after it because CodegenContext snapshots instance init into constructors.
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
        "readCompatible", readCompatibleCode, Object.class, ReadContext.class, READ_CONTEXT_NAME);
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
    Code.ExprCode beanCode =
        new Invoke(generatedObjectInstantiator(), "newInstance", OBJECT_TYPE).genCode(ctx);
    String bean =
        ctx.sourcePublicAccessible(beanClass)
            ? "((" + ctx.type(beanClass) + ") " + beanCode.value() + ")"
            : beanCode.value().toString();
    StringBuilder code = new StringBuilder();
    code.append("reserveObjectGraphMemory(").append(READ_CONTEXT_NAME).append(");\n");
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
    code.append("reserveObjectGraphMemory(").append(READ_CONTEXT_NAME).append(");\n");
    for (int i = 0; i < components.length; i++) {
      Class<?> componentType = components[i].getType();
      code.append(recordLocalType(componentType))
          .append(" _f_recordValue")
          .append(i)
          .append(" = ")
          .append(defaultValue(componentType))
          .append(";\n");
    }
    code.append("for (int _f_i = 0; _f_i < remoteFields.size(); _f_i++) {\n")
        .append("  RemoteFieldInfo _f_remoteField = (RemoteFieldInfo) remoteFields.get(_f_i);\n")
        .append("  if (_f_remoteField.matchedId == -1) {\n");
    appendDebugRemoteRead(code, "before skip", "_f_remoteField", 4);
    code.append("    skipField(").append(READ_CONTEXT_NAME).append(", _f_remoteField);\n");
    appendDebugRemoteRead(code, "after skip", "_f_remoteField", 4);
    code.append("    continue;\n")
        .append("  }\n")
        .append(indent(genRecordDispatchSwitch(), 2))
        .append('\n')
        .append("}\n");
    if (recordCtrAccessible) {
      code.append("return new ").append(ctx.type(beanClass)).append("(");
      for (int i = 0; i < components.length; i++) {
        if (i > 0) {
          code.append(", ");
        }
        code.append("_f_recordValue").append(i);
      }
      code.append(");");
      return code.toString();
    }
    String recordArgs = recordArgsFieldName(components.length);
    code.append("Object[] _f_recordArgs = this.").append(recordArgs).append(";\n");
    for (int i = 0; i < components.length; i++) {
      code.append("_f_recordArgs[")
          .append(i)
          .append("] = ")
          .append("_f_recordValue")
          .append(i)
          .append(";\n");
    }
    ctx.clearExprState();
    Reference values = new Reference("_f_recordArgs", objectArrayTypeRef, false);
    Code.ExprCode newRecord =
        new Invoke(generatedObjectInstantiator(), "newInstanceWithArguments", OBJECT_TYPE, values)
            .genCode(ctx);
    if (StringUtils.isNotBlank(newRecord.code())) {
      code.append(newRecord.code()).append('\n');
    }
    code.append("Object _f_record = ").append(newRecord.value()).append(";\n");
    for (int i = 0; i < components.length; i++) {
      code.append("_f_recordArgs[").append(i).append("] = null;\n");
    }
    code.append("return _f_record;");
    return code.toString();
  }

  private void genDispatchMethods() {
    if (isRecord) {
      return;
    }
    String valueType =
        ctx.sourcePublicAccessible(beanClass) ? ctx.type(beanClass) : ctx.type(Object.class);
    TypeRef<?> valueTypeRef = ctx.sourcePublicAccessible(beanClass) ? beanType : OBJECT_TYPE;
    int caseCount = localDescriptors.size() * 2;
    int groupCount = (caseCount + DISPATCH_GROUP_SIZE - 1) / DISPATCH_GROUP_SIZE;
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
    code.append("if (_f_remoteField.matchedId == -1) {\n");
    appendDebugRemoteRead(code, "before skip", "_f_remoteField", 2);
    code.append("  skipField(").append(READ_CONTEXT_NAME).append(", _f_remoteField);\n");
    appendDebugRemoteRead(code, "after skip", "_f_remoteField", 2);
    code.append("  return;\n");
    code.append("}\n");
    for (int group = 0; group < groupCount; group++) {
      int upperBound = Math.min(localDescriptors.size() * 2, (group + 1) * DISPATCH_GROUP_SIZE);
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
      code.append("_f_value");
      code.append(", _f_remoteField);\n").append("  return;\n").append("}\n");
    }
    code.append("throw new IllegalStateException(\"Invalid compatible matched id \"")
        .append(" + _f_remoteField.matchedId);\n");
    return code.toString();
  }

  private String genObjectDispatchGroup(int group, TypeRef<?> valueTypeRef) {
    int start = group * DISPATCH_GROUP_SIZE;
    int end = Math.min(localDescriptors.size() * 2, start + DISPATCH_GROUP_SIZE);
    StringBuilder code = new StringBuilder("switch (_f_remoteField.matchedId) {\n");
    for (int matchedId = start; matchedId < end; matchedId++) {
      int localId = matchedId >> 1;
      Descriptor descriptor = localDescriptors.get(localId);
      code.append("  case ").append(matchedId).append(": {\n");
      if ((matchedId & 1) == 0) {
        code.append("    MemoryBuffer ")
            .append(BUFFER_NAME)
            .append(" = ")
            .append(READ_CONTEXT_NAME)
            .append(".getBuffer();\n")
            .append(indent(genDirectReadAndSetFieldCode(descriptor, valueTypeRef), 4))
            .append('\n')
            .append("    return;\n")
            .append("  }\n");
        continue;
      }
      String scalarRead =
          genCompatibleScalarReadExpr(
              descriptor,
              "_f_remoteField.serializationFieldInfo",
              "localFieldInfo(" + localId + ")");
      if (scalarRead != null) {
        code.append(debugRemoteReadCode("before read", "_f_remoteField", 4))
            .append("    ")
            .append(genSetFieldCode(descriptor, valueTypeRef, scalarRead))
            .append('\n')
            .append(debugRemoteReadCode("after read", "_f_remoteField", 4))
            .append("    return;\n")
            .append("  }\n");
        continue;
      }
      code.append(debugRemoteReadCode("before read", "_f_remoteField", 4))
          .append("    if (_f_remoteField.serializationFieldInfo.fieldConverter != null) {\n")
          .append("      Object _f_fieldValue = readFieldConverterSource(")
          .append(READ_CONTEXT_NAME)
          .append(", _f_remoteField);\n")
          .append(debugRemoteReadCode("after read", "_f_remoteField", 6))
          .append(
              "      _f_remoteField.serializationFieldInfo.fieldConverter.set(_f_value, _f_fieldValue);\n")
          .append("    } else {\n")
          .append("      SerializationFieldInfo _f_localField = localFieldInfo(")
          .append(localId)
          .append(");\n")
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
        .append("    throw new IllegalStateException(\"Invalid compatible matched id \"")
        .append(" + _f_remoteField.matchedId);\n")
        .append("}\n");
    return code.toString();
  }

  private String genRecordDispatchSwitch() {
    StringBuilder code = new StringBuilder("switch (_f_remoteField.matchedId) {\n");
    for (int matchedId = 0; matchedId < localDescriptors.size() * 2; matchedId++) {
      int localId = matchedId >> 1;
      Descriptor descriptor = localDescriptors.get(localId);
      Integer componentIndex = recordReversedMapping.get(descriptor.getName());
      if (componentIndex == null) {
        continue;
      }
      code.append("  case ").append(matchedId).append(": {\n");
      if ((matchedId & 1) == 0) {
        code.append("    MemoryBuffer ")
            .append(BUFFER_NAME)
            .append(" = ")
            .append(READ_CONTEXT_NAME)
            .append(".getBuffer();\n")
            .append(
                indent(
                    genDirectReadRecordLocalCode(descriptor, "_f_recordValue" + componentIndex), 4))
            .append('\n')
            .append("    break;\n")
            .append("  }\n");
        continue;
      }
      String scalarRead =
          genCompatibleScalarReadExpr(
              descriptor,
              "_f_remoteField.serializationFieldInfo",
              "localFieldInfo(" + localId + ")");
      if (scalarRead != null) {
        code.append(debugRemoteReadCode("before read", "_f_remoteField", 4))
            .append("    _f_recordValue")
            .append(componentIndex)
            .append(" = ")
            .append(scalarRead)
            .append(";\n")
            .append(debugRemoteReadCode("after read", "_f_remoteField", 4))
            .append("    break;\n")
            .append("  }\n");
        continue;
      }
      code.append(debugRemoteReadCode("before read", "_f_remoteField", 4))
          .append("    SerializationFieldInfo _f_localField = localFieldInfo(")
          .append(localId)
          .append(");\n")
          .append("    Object _f_fieldValue = readCompatibleFieldValue(")
          .append(READ_CONTEXT_NAME)
          .append(", _f_remoteField, _f_localField);\n")
          .append(debugRemoteReadCode("after read", "_f_remoteField", 6))
          .append("    _f_recordValue")
          .append(componentIndex)
          .append(" = ")
          .append(castRecordComponent("_f_fieldValue", descriptor.getRawType()))
          .append(";\n")
          .append("    break;\n")
          .append("  }\n");
    }
    code.append("  default:\n")
        .append("    throw new IllegalStateException(\"Invalid compatible matched id \"")
        .append(" + _f_remoteField.matchedId);\n")
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

  private String genDirectReadAndSetFieldCode(Descriptor descriptor, TypeRef<?> valueTypeRef) {
    ctx.clearExprState();
    Reference value = new Reference("_f_value", valueTypeRef, false);
    Reference buffer = new Reference(BUFFER_NAME, bufferTypeRef, false);
    Expression readAndSet =
        deserializeField(
            buffer,
            descriptor,
            fieldValue ->
                setFieldValue(
                    value, descriptor, tryInlineCast(fieldValue, descriptor.getTypeRef())));
    Code.ExprCode readCode = readAndSet.genCode(ctx);
    String code = ctx.optimizeMethodCode(readCode.code());
    return code == null ? "" : code;
  }

  private String genDirectReadRecordLocalCode(Descriptor descriptor, String recordValue) {
    ctx.clearExprState();
    Reference target = new Reference(recordValue, descriptor.getTypeRef(), false);
    Reference buffer = new Reference(BUFFER_NAME, bufferTypeRef, false);
    Expression fieldValue = deserializeField(buffer, descriptor, value -> value);
    Expression assign =
        new Expression.Assign(target, tryInlineCast(fieldValue, descriptor.getTypeRef()));
    Code.ExprCode readCode = assign.genCode(ctx);
    String code = ctx.optimizeMethodCode(readCode.code());
    return code == null ? "" : code;
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

  private String genSetFieldCode(
      Descriptor descriptor, TypeRef<?> valueTypeRef, String valueExpression) {
    ctx.clearExprState();
    Reference value = new Reference("_f_value", valueTypeRef, false);
    Expression setField =
        setFieldValue(
            value,
            descriptor,
            tryInlineCast(
                new Reference(valueExpression, descriptor.getTypeRef(), false),
                descriptor.getTypeRef()));
    Code.ExprCode setCode = setField.genCode(ctx);
    String code = ctx.optimizeMethodCode(setCode.code());
    return code == null ? "" : code;
  }

  private String genCompatibleScalarReadExpr(
      Descriptor descriptor, String remoteFieldInfo, String localFieldInfo) {
    String helper;
    Class<?> rawType = descriptor.getRawType();
    if (rawType == boolean.class) {
      helper = "readBooleanTarget";
    } else if (rawType == Boolean.class) {
      helper = "readBoxedBooleanTarget";
    } else if (rawType == byte.class) {
      helper = "readByteTarget";
    } else if (rawType == Byte.class) {
      helper = "readBoxedByteTarget";
    } else if (rawType == short.class) {
      helper = "readShortTarget";
    } else if (rawType == Short.class) {
      helper = "readBoxedShortTarget";
    } else if (rawType == int.class) {
      helper = "readIntTarget";
    } else if (rawType == Integer.class) {
      helper = "readBoxedIntTarget";
    } else if (rawType == long.class) {
      helper = "readLongTarget";
    } else if (rawType == Long.class) {
      helper = "readBoxedLongTarget";
    } else if (rawType == float.class) {
      helper = "readFloatTarget";
    } else if (rawType == Float.class) {
      helper = "readBoxedFloatTarget";
    } else if (rawType == double.class) {
      helper = "readDoubleTarget";
    } else if (rawType == Double.class) {
      helper = "readBoxedDoubleTarget";
    } else if (rawType == String.class) {
      helper = "readStringTarget";
    } else if (rawType == java.math.BigDecimal.class) {
      helper = "readDecimalTarget";
    } else if (rawType == org.apache.fory.type.unsigned.UInt8.class) {
      helper = "readUInt8Target";
    } else if (rawType == org.apache.fory.type.unsigned.UInt16.class) {
      helper = "readUInt16Target";
    } else if (rawType == org.apache.fory.type.unsigned.UInt32.class) {
      helper = "readUInt32Target";
    } else if (rawType == org.apache.fory.type.unsigned.UInt64.class) {
      helper = "readUInt64Target";
    } else if (rawType == org.apache.fory.type.Float16.class) {
      helper = "readFloat16Target";
    } else if (rawType == org.apache.fory.type.BFloat16.class) {
      helper = "readBFloat16Target";
    } else {
      return null;
    }
    return "org.apache.fory.serializer.converter.FieldConverters."
        + helper
        + "("
        + READ_CONTEXT_NAME
        + ", "
        + remoteFieldInfo
        + ", "
        + localFieldInfo
        + ")";
  }

  private String castRecordComponent(String value, Class<?> type) {
    if (!type.isPrimitive()) {
      return "(" + ctx.type(type) + ") " + value;
    }
    return "(" + ctx.type(boxedType(type)) + ") " + value;
  }

  private String recordLocalType(Class<?> type) {
    return sourcePublicAccessible(type) ? ctx.type(type) : ctx.type(Object.class);
  }

  private String recordArgsFieldName(int componentCount) {
    if (recordArgsFieldName != null) {
      return recordArgsFieldName;
    }
    String fieldName = "_f_recordArgs";
    ctx.addField(
        false,
        false,
        ctx.type(Object[].class),
        fieldName,
        new Expression.NewArray(Object.class, Literal.ofInt(componentCount)));
    recordArgsFieldName = fieldName;
    return fieldName;
  }

  private Expression generatedObjectInstantiator() {
    if (objectInstantiator == null) {
      objectInstantiator = getObjectInstantiator(beanClass);
    }
    return objectInstantiator;
  }

  private String defaultValue(Class<?> type) {
    if (!type.isPrimitive()) {
      return "null";
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
