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

import static org.apache.fory.codegen.Expression.Invoke.inlineInvoke;
import static org.apache.fory.type.TypeUtils.CLASS_TYPE;
import static org.apache.fory.type.TypeUtils.OBJECT_ARRAY_TYPE;
import static org.apache.fory.type.TypeUtils.OBJECT_TYPE;
import static org.apache.fory.type.TypeUtils.PRIMITIVE_BOOLEAN_TYPE;
import static org.apache.fory.type.TypeUtils.PRIMITIVE_BYTE_TYPE;
import static org.apache.fory.type.TypeUtils.PRIMITIVE_CHAR_TYPE;
import static org.apache.fory.type.TypeUtils.PRIMITIVE_DOUBLE_TYPE;
import static org.apache.fory.type.TypeUtils.PRIMITIVE_FLOAT_TYPE;
import static org.apache.fory.type.TypeUtils.PRIMITIVE_INT_TYPE;
import static org.apache.fory.type.TypeUtils.PRIMITIVE_LONG_TYPE;
import static org.apache.fory.type.TypeUtils.PRIMITIVE_SHORT_TYPE;
import static org.apache.fory.type.TypeUtils.PRIMITIVE_VOID_TYPE;
import static org.apache.fory.type.TypeUtils.getRawType;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.apache.fory.Fory;
import org.apache.fory.codegen.CodegenContext;
import org.apache.fory.codegen.Expression;
import org.apache.fory.codegen.Expression.Cast;
import org.apache.fory.codegen.Expression.Inlineable;
import org.apache.fory.codegen.Expression.Invoke;
import org.apache.fory.codegen.Expression.ListExpression;
import org.apache.fory.codegen.Expression.Literal;
import org.apache.fory.codegen.Expression.Reference;
import org.apache.fory.codegen.Expression.StaticInvoke;
import org.apache.fory.collection.Tuple2;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.Platform;
import org.apache.fory.reflect.ObjectCreator;
import org.apache.fory.reflect.ObjectCreators;
import org.apache.fory.reflect.ReflectionUtils;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.resolver.TypeInfo;
import org.apache.fory.resolver.TypeInfoHolder;
import org.apache.fory.type.Descriptor;
import org.apache.fory.util.GraalvmSupport;
import org.apache.fory.util.Preconditions;
import org.apache.fory.util.StringUtils;
import org.apache.fory.util.function.Functions;
import org.apache.fory.util.record.RecordComponent;
import org.apache.fory.util.record.RecordUtils;

/**
 * Base builder for generating code to serialize java bean in row-format or object stream format.
 *
 * <ul>
 *   This builder has following requirements for the class of java bean:
 *   <li>public
 *   <li>For instance inner class, ignore outer class field.
 *   <li>For instance inner class, deserialized outer class field is null
 * </ul>
 */
@SuppressWarnings("UnstableApiUsage")
public abstract class CodecBuilder {
  protected static final String ROOT_OBJECT_NAME = "_f_obj";
  // avoid user class has field with name fory.
  protected static final String FORY_NAME = "_f_fory";
  static TypeRef<Object[]> objectArrayTypeRef = TypeRef.of(Object[].class);
  static TypeRef<MemoryBuffer> bufferTypeRef = TypeRef.of(MemoryBuffer.class);
  static TypeRef<TypeInfo> classInfoTypeRef = TypeRef.of(TypeInfo.class);
  static TypeRef<TypeInfoHolder> classInfoHolderTypeRef = TypeRef.of(TypeInfoHolder.class);

  protected final CodegenContext ctx;
  protected final TypeRef<?> beanType;
  protected final Class<?> beanClass;
  protected final boolean isRecord;
  protected final boolean isInterface;
  private final Set<String> duplicatedFields;
  protected Reference foryRef = Reference.fieldRef(FORY_NAME, TypeRef.of(Fory.class));
  public static final Reference recordComponentDefaultValues =
      new Reference("recordComponentDefaultValues", OBJECT_ARRAY_TYPE);
  protected final Map<String, Reference> fieldMap = new HashMap<>();
  protected boolean recordCtrAccessible;

  public CodecBuilder(CodegenContext ctx, TypeRef<?> beanType) {
    this.ctx = ctx;
    this.beanType = beanType;
    this.beanClass = getRawType(beanType);
    isRecord = RecordUtils.isRecord(beanClass);
    isInterface = beanClass.isInterface();
    if (isRecord) {
      recordCtrAccessible = recordCtrAccessible(beanClass);
    }
    duplicatedFields = Descriptor.getSortedDuplicatedMembers(beanClass).keySet();
    // don't ctx.addImport beanClass, because it maybe causes name collide.
    ctx.reserveName(FORY_NAME);
    ctx.reserveName(ROOT_OBJECT_NAME);
    // Don't import other packages to avoid class conflicts.
    // For example user class named as `Date`/`List`/`MemoryBuffer`
    // Skip Java reserved words since they can't be used as variable names anyway
    // (e.g., Kotlin allows field names like "new" which are valid at bytecode level)
    ReflectionUtils.getFields(beanType.getRawType(), true).stream()
        .map(Field::getName)
        .filter(name -> !CodegenContext.JAVA_RESERVED_WORDS.contains(name))
        .collect(Collectors.toSet())
        .forEach(ctx::reserveName);
  }

  public abstract String codecClassName(Class<?> cls);

  /** Generate codec class code. */
  public abstract String genCode();

  /** Returns an expression that serialize java bean of type {@link CodecBuilder#beanClass}. */
  public abstract Expression buildEncodeExpression();

  protected boolean sourcePublicAccessible(Class<?> cls) {
    return ctx.sourcePublicAccessible(cls);
  }

  protected boolean fieldNullable(Descriptor descriptor) {
    return false;
  }

  protected Expression tryInlineCast(Expression expression, TypeRef<?> targetType) {
    return tryCastIfPublic(expression, targetType, true);
  }

  protected Expression tryCastIfPublic(Expression expression, TypeRef<?> targetType) {
    return tryCastIfPublic(expression, targetType, false);
  }

  protected Expression tryCastIfPublic(
      Expression expression, TypeRef<?> targetType, boolean inline) {
    Class<?> rawType = getRawType(targetType);
    if (inline) {
      if (sourcePublicAccessible(rawType)) {
        return new Cast(expression, targetType);
      } else {
        return new Cast(expression, ReflectionUtils.getPublicSuperType(TypeRef.of(rawType)));
      }
    }
    return tryCastIfPublic(expression, targetType, "castedValue");
  }

  protected Expression tryCastIfPublic(
      Expression expression, TypeRef<?> targetType, String valuePrefix) {
    Class<?> rawType = getRawType(targetType);
    if (sourcePublicAccessible(rawType)
        && !expression.type().wrap().isSubtypeOf(targetType.wrap())) {
      return new Cast(expression, targetType, valuePrefix);
    }
    if (rawType.isArray()) {
      return new Cast(expression, OBJECT_ARRAY_TYPE, valuePrefix);
    }
    return expression;
  }

  protected Reference getRecordCtrHandle() {
    String fieldName = "_record_ctr_";
    Reference fieldRef = fieldMap.get(fieldName);
    if (fieldRef == null) {
      // trigger cache for graalvm
      RecordUtils.getRecordCtrHandle(beanClass);
      StaticInvoke getRecordCtrHandle =
          new StaticInvoke(
              RecordUtils.class,
              "getRecordCtrHandle",
              TypeRef.of(MethodHandle.class),
              beanClassExpr());
      ctx.addField(ctx.type(MethodHandle.class), fieldName, getRecordCtrHandle);
      fieldRef = new Reference(fieldName, TypeRef.of(MethodHandle.class));
      fieldMap.put(fieldName, fieldRef);
    }
    return fieldRef;
  }

  protected Expression buildDefaultComponentsArray() {
    return new StaticInvoke(
        Platform.class, "copyObjectArray", OBJECT_ARRAY_TYPE, recordComponentDefaultValues);
  }

  /** Returns an expression that get field value from <code>bean</code>. */
  protected Expression getFieldValue(Expression inputBeanExpr, Descriptor descriptor) {
    TypeRef<?> fieldType = descriptor.getTypeRef();
    Class<?> rawType = descriptor.getRawType();
    String fieldName = descriptor.getName();
    boolean fieldNullable = fieldNullable(descriptor);
    if (isInterface) {
      return new Invoke(inputBeanExpr, descriptor.getName(), fieldName, fieldType, fieldNullable);
    }
    if (isRecord) {
      return getRecordFieldValue(inputBeanExpr, descriptor);
    }
    if (duplicatedFields.contains(fieldName) || !Modifier.isPublic(beanClass.getModifiers())) {
      return unsafeAccessField(inputBeanExpr, beanClass, descriptor);
    }
    if (!sourcePublicAccessible(rawType)) {
      fieldType = OBJECT_TYPE;
    }
    // public field or non-private non-java field access field directly.
    if (Modifier.isPublic(descriptor.getModifiers())) {
      return new Expression.FieldValue(inputBeanExpr, fieldName, fieldType, fieldNullable, false);
    } else if (descriptor.getReadMethod() != null
        && Modifier.isPublic(descriptor.getReadMethod().getModifiers())) {
      return new Invoke(
          inputBeanExpr, descriptor.getReadMethod().getName(), fieldName, fieldType, fieldNullable);
    } else {
      if (!Modifier.isPrivate(descriptor.getModifiers())) {
        if (AccessorHelper.defineAccessor(descriptor.getField())) {
          return new StaticInvoke(
              AccessorHelper.getAccessorClass(descriptor.getField()),
              fieldName,
              fieldType,
              fieldNullable,
              inputBeanExpr);
        }
      }
      if (descriptor.getReadMethod() != null
          && !Modifier.isPrivate(descriptor.getReadMethod().getModifiers())) {
        if (AccessorHelper.defineAccessor(descriptor.getReadMethod())) {
          return new StaticInvoke(
              AccessorHelper.getAccessorClass(descriptor.getReadMethod()),
              descriptor.getReadMethod().getName(),
              fieldType,
              fieldNullable,
              inputBeanExpr);
        }
      }
      return unsafeAccessField(inputBeanExpr, beanClass, descriptor);
    }
  }

  private Expression getRecordFieldValue(Expression inputBeanExpr, Descriptor descriptor) {
    TypeRef<?> fieldType = descriptor.getTypeRef();
    if (!sourcePublicAccessible(descriptor.getRawType())) {
      fieldType = OBJECT_TYPE;
    }
    String fieldName = descriptor.getName();
    boolean fieldNullable = fieldNullable(descriptor);
    if (Modifier.isPublic(beanClass.getModifiers())) {
      Preconditions.checkNotNull(descriptor.getReadMethod());
      return new Invoke(
          inputBeanExpr, descriptor.getReadMethod().getName(), fieldName, fieldType, fieldNullable);
    } else {
      String key = "_" + fieldName + "_getter_";
      Reference ref = fieldMap.get(key);
      Tuple2<Class<?>, String> methodInfo = Functions.getterMethodInfo(descriptor.getRawType());
      if (ref == null) {
        Class<?> funcInterface = methodInfo.f0;
        TypeRef<?> getterType = TypeRef.of(funcInterface);
        if (GraalvmSupport.isGraalBuildtime()) {
          // generate getter ahead at native image build time.
          Functions.makeGetterFunction(beanClass, fieldName);
        }
        Expression getter =
            new StaticInvoke(
                Functions.class,
                "makeGetterFunction",
                OBJECT_TYPE,
                beanClassExpr(),
                Literal.ofString(fieldName));
        getter = new Cast(getter, getterType);
        ctx.addField(funcInterface, key, getter);
        ref = new Reference(key, getterType);
        fieldMap.put(key, ref);
      }
      if (!fieldType.isPrimitive()) {
        Expression v = inlineInvoke(ref, methodInfo.f1, OBJECT_TYPE, fieldNullable, inputBeanExpr);
        return tryCastIfPublic(v, descriptor.getTypeRef(), fieldName);
      } else {
        return new Invoke(ref, methodInfo.f1, fieldType, fieldNullable, inputBeanExpr);
      }
    }
  }

  /** Returns an expression that get field value> from <code>bean</code> using reflection. */
  private Expression reflectAccessField(
      Expression inputObject, Class<?> cls, Descriptor descriptor) {
    Reference fieldRef = getReflectField(cls, descriptor.getField());
    // boolean fieldNullable = !descriptor.getTypeToken().isPrimitive();
    Invoke getObj =
        new Invoke(fieldRef, "get", OBJECT_TYPE, fieldNullable(descriptor), inputObject);
    return new Cast(getObj, descriptor.getTypeRef(), descriptor.getName());
  }

  /** Returns an expression that get field value> from <code>bean</code> using `Unsafe`. */
  private Expression unsafeAccessField(
      Expression inputObject, Class<?> cls, Descriptor descriptor) {
    String fieldName = descriptor.getName();
    Expression fieldOffsetExpr = getFieldOffset(cls, descriptor);
    boolean fieldNullable = fieldNullable(descriptor);
    if (descriptor.getTypeRef().isPrimitive()) {
      // ex: Platform.UNSAFE.getFloat(obj, fieldOffset)
      Preconditions.checkArgument(!fieldNullable);
      TypeRef<?> returnType = descriptor.getTypeRef();
      String funcName = "get" + StringUtils.capitalize(descriptor.getRawType().toString());
      return new StaticInvoke(
          Platform.class, funcName, returnType, false, inputObject, fieldOffsetExpr);
    } else {
      // ex: Platform.UNSAFE.getObject(obj, fieldOffset)
      StaticInvoke getObj =
          new StaticInvoke(
              Platform.class,
              "getObject",
              OBJECT_TYPE,
              fieldNullable,
              inputObject,
              fieldOffsetExpr);
      return tryCastIfPublic(getObj, descriptor.getTypeRef(), fieldName);
    }
  }

  private Expression getFieldOffset(Class<?> cls, Descriptor descriptor) {
    Field field = descriptor.getField();
    String fieldName = descriptor.getName();
    // Use Field in case the class has duplicate field name as `fieldName`.
    if (GraalvmSupport.isGraalBuildtime()) {
      return getOrCreateField(
          true,
          long.class,
          fieldName + "_offset_",
          () -> {
            Expression classExpr = beanClassExpr(field.getDeclaringClass());
            new Invoke(classExpr, "getDeclaredField", TypeRef.of(Field.class));
            Expression reflectFieldRef = getReflectField(cls, field, false);
            return new StaticInvoke(
                    Platform.class, "objectFieldOffset", PRIMITIVE_LONG_TYPE, reflectFieldRef)
                .inline();
          });
    } else {
      long fieldOffset = ReflectionUtils.getFieldOffset(field);
      Preconditions.checkArgument(fieldOffset != -1);
      return Literal.ofLong(fieldOffset);
    }
  }

  /**
   * Returns an expression that deserialize data as a java bean of type {@link
   * CodecBuilder#beanClass}.
   */
  public abstract Expression buildDecodeExpression();

  /** Returns an expression that set field <code>value</code> to <code>bean</code>. */
  protected Expression setFieldValue(Expression bean, Descriptor d, Expression value) {
    String fieldName = d.getName();
    if (value instanceof Inlineable) {
      ((Inlineable) value).inline();
    }
    if (duplicatedFields.contains(fieldName) || !sourcePublicAccessible(beanClass)) {
      return unsafeSetField(bean, d, value);
    }
    if (!d.isFinalField()
        && Modifier.isPublic(d.getModifiers())
        && Modifier.isPublic(d.getRawType().getModifiers())) {
      if (!d.getRawType().isAssignableFrom(value.type().getRawType())) {
        value = tryInlineCast(value, d.getTypeRef());
      }
      return new Expression.SetField(bean, fieldName, value);
    } else if (d.getWriteMethod() != null && Modifier.isPublic(d.getWriteMethod().getModifiers())) {
      if (!d.getRawType().isAssignableFrom(value.type().getRawType())) {
        value = tryInlineCast(value, d.getTypeRef());
      }
      return new Invoke(bean, d.getWriteMethod().getName(), value);
    } else {
      if (!d.isFinalField() && !Modifier.isPrivate(d.getModifiers())) {
        if (AccessorHelper.defineSetter(d.getField())) {
          Class<?> accessorClass = AccessorHelper.getAccessorClass(d.getField());
          if (!d.getRawType().isAssignableFrom(value.type().getRawType())) {
            value = tryInlineCast(value, d.getTypeRef());
          }
          return new StaticInvoke(
              accessorClass, d.getName(), PRIMITIVE_VOID_TYPE, false, bean, value);
        }
      }
      if (d.getWriteMethod() != null && !Modifier.isPrivate(d.getWriteMethod().getModifiers())) {
        if (AccessorHelper.defineSetter(d.getWriteMethod())) {
          Class<?> accessorClass = AccessorHelper.getAccessorClass(d.getWriteMethod());
          if (!d.getRawType().isAssignableFrom(value.type().getRawType())) {
            value = tryInlineCast(value, d.getTypeRef());
          }
          return new StaticInvoke(
              accessorClass, d.getWriteMethod().getName(), PRIMITIVE_VOID_TYPE, false, bean, value);
        }
      }
      return unsafeSetField(bean, d, value);
    }
  }

  /**
   * Returns an expression that set field <code>value</code> to <code>bean</code> using reflection.
   */
  private Expression reflectSetField(Expression bean, Field field, Expression value) {
    // Class maybe have getter, but don't have setter, so we can't rely on reflectAccessField to
    // populate fieldMap
    Reference fieldRef = getReflectField(getRawType(bean.type()), field);
    Preconditions.checkNotNull(fieldRef);
    return new Invoke(fieldRef, "set", bean, value);
  }

  /**
   * Returns an expression that set field <code>value</code> to <code>bean</code> using `Unsafe`.
   */
  private Expression unsafeSetField(Expression bean, Descriptor descriptor, Expression value) {
    TypeRef<?> fieldType = descriptor.getTypeRef();
    // Use Field in case the class has duplicate field name as `fieldName`.
    Expression fieldOffsetExpr = getFieldOffset(beanClass, descriptor);
    if (descriptor.getTypeRef().isPrimitive()) {
      Preconditions.checkArgument(value.type().equals(fieldType));
      String funcName = "put" + StringUtils.capitalize(getRawType(fieldType).toString());
      return new StaticInvoke(Platform.class, funcName, bean, fieldOffsetExpr, value);
    } else {
      return new StaticInvoke(Platform.class, "putObject", bean, fieldOffsetExpr, value);
    }
  }

  private Reference getReflectField(Class<?> cls, Field field) {
    return getReflectField(cls, field, true);
  }

  private Reference getReflectField(Class<?> cls, Field field, boolean setAccessible) {
    String fieldName = field.getName();
    String fieldRefName;
    if (duplicatedFields.contains(fieldName)) {
      fieldRefName = cls.getName().replaceAll("\\.|\\$", "_") + "_" + fieldName + "_Field";
    } else {
      fieldRefName = fieldName + "_Field";
    }
    return getOrCreateField(
        true,
        Field.class,
        fieldRefName,
        () -> {
          TypeRef<Field> fieldTypeRef = TypeRef.of(Field.class);
          Expression classExpr = beanClassExpr(field.getDeclaringClass());
          Expression fieldExpr;
          if (GraalvmSupport.isGraalBuildtime()) {
            fieldExpr =
                inlineInvoke(
                    classExpr, "getDeclaredField", fieldTypeRef, Literal.ofString(fieldName));
          } else {
            fieldExpr =
                new StaticInvoke(
                    ReflectionUtils.class,
                    "getField",
                    fieldTypeRef,
                    classExpr,
                    Literal.ofString(fieldName));
          }
          if (!setAccessible) {
            return fieldExpr;
          }
          Invoke setAccess = new Invoke(fieldExpr, "setAccessible", Literal.True);
          return new ListExpression(setAccess, fieldExpr);
        });
  }

  protected Reference getOrCreateField(
      boolean isStatic, Class<?> type, String fieldName, Supplier<Expression> value) {
    Reference fieldRef = fieldMap.get(fieldName);
    if (fieldRef == null) {
      fieldName = ctx.newName(fieldName);
      ctx.addField(isStatic, true, ctx.type(type), fieldName, value.get());
      fieldRef = new Reference(fieldName, TypeRef.of(type));
      fieldMap.put(fieldName, fieldRef);
    }
    return fieldRef;
  }

  /** Returns an Expression that create a new java object of type {@link CodecBuilder#beanClass}. */
  protected Expression newBean() {
    // TODO allow default access-level class.
    if (sourcePublicAccessible(beanClass)) {
      return new Expression.NewInstance(beanType);
    } else {
      if (GraalvmSupport.IN_GRAALVM_NATIVE_IMAGE && Platform.JAVA_VERSION >= 25) {
        ObjectCreators.getObjectCreator(beanClass); // trigger cache
        return new Invoke(getObjectCreator(beanClass), "newInstance", OBJECT_TYPE);
      }
      return new StaticInvoke(Platform.class, "newInstance", OBJECT_TYPE, beanClassExpr());
    }
  }

  protected Expression getObjectCreator(Class<?> type) {
    ObjectCreators.getObjectCreator(type); // trigger cache
    return getOrCreateField(
        true,
        ObjectCreator.class,
        ctx.newName("objectCreator_" + type.getSimpleName()),
        () ->
            new StaticInvoke(
                ObjectCreators.class,
                "getObjectCreator",
                TypeRef.of(ObjectCreator.class),
                staticBeanClassExpr()));
  }

  protected void buildRecordComponentDefaultValues() {
    ctx.reserveName(recordComponentDefaultValues.name());
    StaticInvoke expr =
        new StaticInvoke(
            RecordUtils.class,
            "buildRecordComponentDefaultValues",
            OBJECT_ARRAY_TYPE,
            beanClassExpr());
    ctx.addField(Object[].class, recordComponentDefaultValues.name(), expr);
  }

  static boolean recordCtrAccessible(Class<?> cls) {
    // support unexported packages in module
    if (!Modifier.isPublic(cls.getModifiers())) {
      return false;
    }
    for (RecordComponent component : Objects.requireNonNull(RecordUtils.getRecordComponents(cls))) {
      if (!Modifier.isPublic(component.getType().getModifiers())) {
        return false;
      }
    }
    return true;
  }

  protected Expression beanClassExpr(Class<?> cls) {
    if (cls == beanClass) {
      return staticBeanClassExpr();
    }
    if (GraalvmSupport.isGraalBuildtime()) {
      String name = cls.getName().replaceAll("\\.|\\$", "_") + "__class__";
      return getOrCreateField(
          true,
          Class.class,
          name,
          () ->
              new StaticInvoke(
                      ReflectionUtils.class,
                      "loadClass",
                      CLASS_TYPE,
                      Literal.ofString(cls.getName()))
                  .inline());
    }
    throw new UnsupportedOperationException();
  }

  protected Expression beanClassExpr() {
    if (GraalvmSupport.isGraalBuildtime()) {
      return staticBeanClassExpr();
    }
    throw new UnsupportedOperationException();
  }

  protected Expression staticBeanClassExpr() {
    if (sourcePublicAccessible(beanClass)) {
      return Literal.ofClass(beanClass);
    }
    return staticClassFieldExpr(beanClass, "__class__");
  }

  protected Expression staticClassFieldExpr(Class<?> cls, String fieldName) {
    Preconditions.checkArgument(
        !sourcePublicAccessible(cls), "Public class %s should use class literal instead", cls);
    return getOrCreateField(
        true,
        Class.class,
        fieldName,
        () ->
            new StaticInvoke(
                    ReflectionUtils.class, "loadClass", CLASS_TYPE, Literal.ofString(cls.getName()))
                .inline());
  }

  /** Build unsafePut operation. */
  protected Expression unsafePut(Expression base, Expression pos, Expression value) {
    return new StaticInvoke(Platform.class, "putByte", base, pos, value);
  }

  protected Expression unsafePutBoolean(Expression base, Expression pos, Expression value) {
    return new StaticInvoke(Platform.class, "putBoolean", base, pos, value);
  }

  protected Expression unsafePutChar(Expression base, Expression pos, Expression value) {
    return new StaticInvoke(Platform.class, "putChar", base, pos, value);
  }

  protected Expression unsafePutShort(Expression base, Expression pos, Expression value) {
    return new StaticInvoke(Platform.class, "putShort", base, pos, value);
  }

  protected Expression unsafePutInt(Expression base, Expression pos, Expression value) {
    return new StaticInvoke(Platform.class, "putInt", base, pos, value);
  }

  protected Expression unsafePutLong(Expression base, Expression pos, Expression value) {
    return new StaticInvoke(Platform.class, "putLong", base, pos, value);
  }

  protected Expression unsafePutFloat(Expression base, Expression pos, Expression value) {
    return new StaticInvoke(Platform.class, "putFloat", base, pos, value);
  }

  /** Build unsafePutDouble operation. */
  protected Expression unsafePutDouble(Expression base, Expression pos, Expression value) {
    return new StaticInvoke(Platform.class, "putDouble", base, pos, value);
  }

  /** Build unsafeGet operation. */
  protected Expression unsafeGet(Expression base, Expression pos) {
    return new StaticInvoke(Platform.class, "getByte", PRIMITIVE_BYTE_TYPE, base, pos);
  }

  protected Expression unsafeGetBoolean(Expression base, Expression pos) {
    return new StaticInvoke(Platform.class, "getBoolean", PRIMITIVE_BOOLEAN_TYPE, base, pos);
  }

  protected Expression unsafeGetChar(Expression base, Expression pos) {
    StaticInvoke expr = new StaticInvoke(Platform.class, "getChar", PRIMITIVE_CHAR_TYPE, base, pos);
    if (!Platform.IS_LITTLE_ENDIAN) {
      expr = new StaticInvoke(Character.class, "reverseBytes", PRIMITIVE_CHAR_TYPE, expr.inline());
    }
    return expr;
  }

  protected Expression unsafeGetShort(Expression base, Expression pos) {
    StaticInvoke expr =
        new StaticInvoke(Platform.class, "getShort", PRIMITIVE_SHORT_TYPE, base, pos);
    if (!Platform.IS_LITTLE_ENDIAN) {
      expr = new StaticInvoke(Short.class, "reverseBytes", PRIMITIVE_SHORT_TYPE, expr.inline());
    }
    return expr;
  }

  protected Expression unsafeGetInt(Expression base, Expression pos) {
    StaticInvoke expr = new StaticInvoke(Platform.class, "getInt", PRIMITIVE_INT_TYPE, base, pos);
    if (!Platform.IS_LITTLE_ENDIAN) {
      expr = new StaticInvoke(Integer.class, "reverseBytes", PRIMITIVE_INT_TYPE, expr.inline());
    }
    return expr;
  }

  protected Expression unsafeGetLong(Expression base, Expression pos) {
    StaticInvoke expr = new StaticInvoke(Platform.class, "getLong", PRIMITIVE_LONG_TYPE, base, pos);
    if (!Platform.IS_LITTLE_ENDIAN) {
      expr = new StaticInvoke(Long.class, "reverseBytes", PRIMITIVE_LONG_TYPE, expr.inline());
    }
    return expr;
  }

  protected Expression unsafeGetFloat(Expression base, Expression pos) {
    StaticInvoke expr = new StaticInvoke(Platform.class, "getInt", PRIMITIVE_INT_TYPE, base, pos);
    if (!Platform.IS_LITTLE_ENDIAN) {
      expr = new StaticInvoke(Integer.class, "reverseBytes", PRIMITIVE_INT_TYPE, expr.inline());
    }
    return new StaticInvoke(Float.class, "intBitsToFloat", PRIMITIVE_FLOAT_TYPE, expr.inline());
  }

  protected Expression unsafeGetDouble(Expression base, Expression pos) {
    StaticInvoke expr = new StaticInvoke(Platform.class, "getLong", PRIMITIVE_LONG_TYPE, base, pos);
    if (!Platform.IS_LITTLE_ENDIAN) {
      expr = new StaticInvoke(Long.class, "reverseBytes", PRIMITIVE_LONG_TYPE, expr.inline());
    }
    return new StaticInvoke(Double.class, "longBitsToDouble", PRIMITIVE_DOUBLE_TYPE, expr.inline());
  }

  protected Expression readChar(Expression buffer) {
    return new Invoke(buffer, "readChar", PRIMITIVE_CHAR_TYPE);
  }

  protected Expression readInt16(Expression buffer) {
    String func = Platform.IS_LITTLE_ENDIAN ? "_readInt16OnLE" : "_readInt16OnBE";
    return new Invoke(buffer, func, PRIMITIVE_SHORT_TYPE);
  }

  protected Expression readInt32(Expression buffer) {
    String func = Platform.IS_LITTLE_ENDIAN ? "_readInt32OnLE" : "_readInt32OnBE";
    return new Invoke(buffer, func, PRIMITIVE_INT_TYPE);
  }

  public static String readIntFunc() {
    return Platform.IS_LITTLE_ENDIAN ? "_readInt32OnLE" : "_readInt32OnBE";
  }

  protected Expression readVarInt32(Expression buffer) {
    String func = Platform.IS_LITTLE_ENDIAN ? "_readVarInt32OnLE" : "_readVarInt32OnBE";
    return new Invoke(buffer, func, PRIMITIVE_INT_TYPE);
  }

  protected Expression readInt64(Expression buffer) {
    return new Invoke(buffer, readLongFunc(), PRIMITIVE_LONG_TYPE);
  }

  public static String readLongFunc() {
    return Platform.IS_LITTLE_ENDIAN ? "_readInt64OnLE" : "_readInt64OnBE";
  }

  public static String readInt16Func() {
    return Platform.IS_LITTLE_ENDIAN ? "_readInt16OnLE" : "_readInt16OnBE";
  }

  public static String readVarInt32Func() {
    return Platform.IS_LITTLE_ENDIAN ? "_readVarInt32OnLE" : "_readVarInt32OnBE";
  }

  public static String readFloat32Func() {
    return Platform.IS_LITTLE_ENDIAN ? "_readFloat32OnLE" : "_readFloat32OnBE";
  }

  public static String readFloat64Func() {
    return Platform.IS_LITTLE_ENDIAN ? "_readFloat64OnLE" : "_readFloat64OnBE";
  }

  protected Expression readFloat32(Expression buffer) {
    return new Invoke(buffer, readFloat32Func(), PRIMITIVE_FLOAT_TYPE);
  }

  protected Expression readFloat64(Expression buffer) {
    return new Invoke(buffer, readFloat64Func(), PRIMITIVE_DOUBLE_TYPE);
  }
}
