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

package org.apache.fory.resolver;

import static org.apache.fory.Fory.NOT_SUPPORT_XLANG;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import org.apache.fory.Fory;
import org.apache.fory.annotation.Internal;
import org.apache.fory.builder.CodecUtils;
import org.apache.fory.builder.Generated.GeneratedMetaSharedSerializer;
import org.apache.fory.builder.Generated.GeneratedObjectSerializer;
import org.apache.fory.codegen.CodeGenerator;
import org.apache.fory.codegen.Expression;
import org.apache.fory.codegen.Expression.Invoke;
import org.apache.fory.collection.IdentityMap;
import org.apache.fory.collection.LongMap;
import org.apache.fory.collection.ObjectArray;
import org.apache.fory.collection.Tuple2;
import org.apache.fory.config.CompatibleMode;
import org.apache.fory.logging.Logger;
import org.apache.fory.logging.LoggerFactory;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.meta.ClassDef;
import org.apache.fory.meta.ClassSpec;
import org.apache.fory.meta.TypeExtMeta;
import org.apache.fory.reflect.ReflectionUtils;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.serializer.CodegenSerializer.LazyInitBeanSerializer;
import org.apache.fory.serializer.MetaSharedSerializer;
import org.apache.fory.serializer.NonexistentClass;
import org.apache.fory.serializer.NonexistentClass.NonexistentMetaShared;
import org.apache.fory.serializer.NonexistentClass.NonexistentSkip;
import org.apache.fory.serializer.NonexistentClassSerializers;
import org.apache.fory.serializer.NonexistentClassSerializers.NonexistentClassSerializer;
import org.apache.fory.serializer.ObjectSerializer;
import org.apache.fory.serializer.Serializer;
import org.apache.fory.serializer.SerializerFactory;
import org.apache.fory.serializer.Serializers;
import org.apache.fory.type.Descriptor;
import org.apache.fory.type.DescriptorGrouper;
import org.apache.fory.type.GenericType;
import org.apache.fory.type.ScalaTypes;
import org.apache.fory.type.TypeUtils;
import org.apache.fory.util.GraalvmSupport;
import org.apache.fory.util.GraalvmSupport.GraalvmSerializerHolder;
import org.apache.fory.util.Preconditions;
import org.apache.fory.util.function.Functions;

// Internal type dispatcher.
// Do not use this interface outside of fory package
@Internal
@SuppressWarnings({"rawtypes", "unchecked"})
public abstract class TypeResolver {
  private static final Logger LOG = LoggerFactory.getLogger(ClassResolver.class);

  public static final short NO_CLASS_ID = (short) 0;
  static final ClassInfo NIL_CLASS_INFO =
      new ClassInfo(null, null, null, null, false, null, NO_CLASS_ID, NOT_SUPPORT_XLANG);
  // use a lower load factor to minimize hash collision
  static final float foryMapLoadFactor = 0.25f;
  static final int estimatedNumRegistered = 150;
  static final String SET_META__CONTEXT_MSG =
      "Meta context must be set before serialization, "
          + "please set meta context by SerializationContext.setMetaContext";

  final Fory fory;
  final boolean metaContextShareEnabled;
  final MetaStringResolver metaStringResolver;
  // IdentityMap has better lookup performance, when loadFactor is 0.05f, performance is better
  final IdentityMap<Class<?>, ClassInfo> classInfoMap = new IdentityMap<>(64, foryMapLoadFactor);
  final ExtRegistry extRegistry;

  protected TypeResolver(Fory fory) {
    this.fory = fory;
    metaContextShareEnabled = fory.getConfig().isMetaShareEnabled();
    extRegistry = new ExtRegistry();
    metaStringResolver = fory.getMetaStringResolver();
  }

  public abstract void register(Class<?> type);

  public abstract void register(Class<?> type, int id);

  public abstract void register(Class<?> type, String namespace, String typeName);

  public void register(String className) {
    register(loadClass(className));
  }

  public void register(String className, int classId) {
    register(loadClass(className), classId);
  }

  public void register(String className, String namespace, String typeName) {
    register(loadClass(className), namespace, typeName);
  }

  public abstract void registerSerializer(Class<?> type, Serializer<?> serializer);

  public abstract <T> void registerSerializer(
      Class<T> type, Class<? extends Serializer> serializerClass);

  /**
   * Whether to track reference for this type. If false, reference tracing of subclasses may be
   * ignored too.
   */
  public final boolean needToWriteRef(TypeRef<?> typeRef) {
    Object extInfo = typeRef.getExtInfo();
    if (extInfo instanceof TypeExtMeta) {
      TypeExtMeta meta = (TypeExtMeta) extInfo;
      return meta.trackingRef();
    }
    Class<?> cls = typeRef.getRawType();
    if (fory.trackingRef()) {
      ClassInfo classInfo = classInfoMap.get(cls);
      if (classInfo == null || classInfo.serializer == null) {
        // TODO group related logic together for extendability and consistency.
        return !cls.isEnum();
      } else {
        return classInfo.serializer.needToWriteRef();
      }
    }
    return false;
  }

  public final boolean needToWriteClassDef(Serializer serializer) {
    if (fory.getConfig().getCompatibleMode() != CompatibleMode.COMPATIBLE) {
      return false;
    }
    if (GraalvmSupport.isGraalBuildtime() && serializer instanceof GraalvmSerializerHolder) {
      Class<? extends Serializer> serializerClass =
          ((GraalvmSerializerHolder) serializer).getSerializerClass();
      return GeneratedObjectSerializer.class.isAssignableFrom(serializerClass)
          || GeneratedMetaSharedSerializer.class.isAssignableFrom(serializerClass);
    }
    return (serializer instanceof GeneratedObjectSerializer
        // May already switched to MetaSharedSerializer when update class info cache.
        || serializer instanceof GeneratedMetaSharedSerializer
        || serializer instanceof LazyInitBeanSerializer
        || serializer instanceof ObjectSerializer
        || serializer instanceof MetaSharedSerializer);
  }

  public abstract boolean isRegistered(Class<?> cls);

  public abstract boolean isRegisteredById(Class<?> cls);

  public abstract boolean isRegisteredByName(Class<?> cls);

  public abstract boolean isMonomorphic(Class<?> clz);

  public abstract ClassInfo getClassInfo(Class<?> cls);

  public abstract ClassInfo getClassInfo(Class<?> cls, boolean createIfAbsent);

  public abstract ClassInfo getClassInfo(Class<?> cls, ClassInfoHolder classInfoHolder);

  public abstract void writeClassInfo(MemoryBuffer buffer, ClassInfo classInfo);

  /**
   * Native code for ClassResolver.writeClassInfo is too big to inline, so inline it manually.
   *
   * <p>See `already compiled into a big method` in <a
   * href="https://wiki.openjdk.org/display/HotSpot/Server+Compiler+Inlining+Messages">Server+Compiler+Inlining+Messages</a>
   */
  // Note: Thread safe for jit thread to call.
  public Expression writeClassExpr(
      Expression classResolverRef, Expression buffer, Expression classInfo) {
    return new Invoke(classResolverRef, "writeClassInfo", buffer, classInfo);
  }

  public abstract ClassInfo readClassInfo(MemoryBuffer buffer, ClassInfoHolder classInfoHolder);

  public abstract ClassInfo readClassInfo(MemoryBuffer buffer, ClassInfo classInfoCache);

  abstract ClassInfo readSharedClassMeta(MemoryBuffer buffer, MetaContext metaContext);

  public final ClassInfo readSharedClassMeta(MemoryBuffer buffer, Class<?> targetClass) {
    ClassInfo classInfo =
        readSharedClassMeta(buffer, fory.getSerializationContext().getMetaContext());
    Class<?> readClass = classInfo.getCls();
    // replace target class if needed
    if (targetClass != readClass) {
      Tuple2<Class<?>, Class<?>> key = Tuple2.of(readClass, targetClass);
      ClassInfo newClassInfo = extRegistry.transformedClassInfo.get(key);
      if (newClassInfo == null) {
        // similar to create serializer for `NonexistentMetaShared`
        newClassInfo =
            getMetaSharedClassInfo(
                classInfo.classDef.replaceRootClassTo((ClassResolver) this, targetClass),
                targetClass);
        extRegistry.transformedClassInfo.put(key, newClassInfo);
      }
      return newClassInfo;
    }
    return classInfo;
  }

  final ClassInfo readSharedClassMeta(MetaContext metaContext, int index) {
    ClassDef classDef = metaContext.readClassDefs.get(index);
    Tuple2<ClassDef, ClassInfo> classDefTuple = extRegistry.classIdToDef.get(classDef.getId());
    ClassInfo classInfo;
    if (classDefTuple == null || classDefTuple.f1 == null || classDefTuple.f1.serializer == null) {
      classInfo = buildMetaSharedClassInfo(classDefTuple, classDef);
    } else {
      classInfo = classDefTuple.f1;
    }
    metaContext.readClassInfos.set(index, classInfo);
    return classInfo;
  }

  final ClassInfo buildMetaSharedClassInfo(
      Tuple2<ClassDef, ClassInfo> classDefTuple, ClassDef classDef) {
    ClassInfo classInfo;
    if (classDefTuple != null) {
      classDef = classDefTuple.f0;
    }
    Class<?> cls = loadClass(classDef.getClassSpec());
    if (!classDef.hasFieldsMeta()) {
      classInfo = getClassInfo(cls);
    } else {
      classInfo = getMetaSharedClassInfo(classDef, cls);
    }
    // Share serializer for same version class def to avoid too much different meta
    // context take up too much memory.
    putClassDef(classDef, classInfo);
    return classInfo;
  }

  // TODO(chaokunyang) if ClassDef is consistent with class in this process,
  //  use existing serializer instead.
  private ClassInfo getMetaSharedClassInfo(ClassDef classDef, Class<?> clz) {
    if (clz == NonexistentSkip.class) {
      clz = NonexistentMetaShared.class;
    }
    Class<?> cls = clz;
    Short classId = extRegistry.registeredClassIdMap.get(cls);
    ClassInfo classInfo =
        new ClassInfo(this, cls, null, classId == null ? NO_CLASS_ID : classId, NOT_SUPPORT_XLANG);
    classInfo.classDef = classDef;
    if (NonexistentClass.class.isAssignableFrom(TypeUtils.getComponentIfArray(cls))) {
      if (cls == NonexistentMetaShared.class) {
        classInfo.setSerializer(this, new NonexistentClassSerializer(fory, classDef));
        // ensure `NonexistentMetaSharedClass` registered to write fixed-length class def,
        // so we can rewrite it in `NonexistentClassSerializer`.
        if (!fory.isCrossLanguage()) {
          Preconditions.checkNotNull(classId);
        }
      } else {
        classInfo.serializer =
            NonexistentClassSerializers.getSerializer(fory, classDef.getClassName(), cls);
      }
      return classInfo;
    }
    if (clz.isArray() || cls.isEnum()) {
      return getClassInfo(cls);
    }
    Class<? extends Serializer> sc =
        getMetaSharedDeserializerClassFromGraalvmRegistry(cls, classDef);
    if (sc == null) {
      if (GraalvmSupport.isGraalRuntime()) {
        sc = MetaSharedSerializer.class;
        LOG.warn(
            "Can't generate class at runtime in graalvm for class def {}, use {} instead",
            classDef,
            sc);
      } else {
        sc =
            fory.getJITContext()
                .registerSerializerJITCallback(
                    () -> MetaSharedSerializer.class,
                    () -> CodecUtils.loadOrGenMetaSharedCodecClass(fory, cls, classDef),
                    c -> classInfo.setSerializer(this, Serializers.newSerializer(fory, cls, c)));
      }
    }
    if (sc == MetaSharedSerializer.class) {
      classInfo.setSerializer(this, new MetaSharedSerializer(fory, cls, classDef));
    } else {
      classInfo.setSerializer(this, Serializers.newSerializer(fory, cls, sc));
    }
    return classInfo;
  }

  /**
   * Write all new class definitions meta to buffer at last, so that if some class doesn't exist on
   * peer, but one of class which exists on both side are sent in this stream, the definition meta
   * can still be stored in peer, and can be resolved next time when sent only an id.
   */
  public final void writeClassDefs(MemoryBuffer buffer) {
    MetaContext metaContext = fory.getSerializationContext().getMetaContext();
    ObjectArray<ClassDef> writingClassDefs = metaContext.writingClassDefs;
    final int size = writingClassDefs.size;
    buffer.writeVarUint32Small7(size);
    if (buffer.isHeapFullyWriteable()) {
      writeClassDefs(buffer, writingClassDefs, size);
    } else {
      for (int i = 0; i < size; i++) {
        writingClassDefs.get(i).writeClassDef(buffer);
      }
    }
    metaContext.writingClassDefs.size = 0;
  }

  private void writeClassDefs(
      MemoryBuffer buffer, ObjectArray<ClassDef> writingClassDefs, int size) {
    for (int i = 0; i < size; i++) {
      buffer.writeBytes(writingClassDefs.get(i).getEncoded());
      MemoryBuffer memoryBuffer = MemoryBuffer.fromByteArray(writingClassDefs.get(i).getEncoded());
      ClassDef.readClassDef(fory, memoryBuffer, memoryBuffer.readInt64());
    }
  }

  /**
   * Ensure all class definition are read and populated, even there are deserialization exception
   * such as ClassNotFound. So next time a class def written previously identified by an id can be
   * got from the meta context.
   */
  public final void readClassDefs(MemoryBuffer buffer) {
    MetaContext metaContext = fory.getSerializationContext().getMetaContext();
    assert metaContext != null : SET_META__CONTEXT_MSG;
    int numClassDefs = buffer.readVarUint32Small7();
    for (int i = 0; i < numClassDefs; i++) {
      long id = buffer.readInt64();
      Tuple2<ClassDef, ClassInfo> tuple2 = extRegistry.classIdToDef.get(id);
      if (tuple2 != null) {
        ClassDef.skipClassDef(buffer, id);
      } else {
        tuple2 = readClassDef(buffer, id);
      }
      metaContext.readClassDefs.add(tuple2.f0);
      metaContext.readClassInfos.add(tuple2.f1);
    }
  }

  private Tuple2<ClassDef, ClassInfo> readClassDef(MemoryBuffer buffer, long header) {
    ClassDef readClassDef = ClassDef.readClassDef(fory, buffer, header);
    Tuple2<ClassDef, ClassInfo> tuple2 = extRegistry.classIdToDef.get(readClassDef.getId());
    if (tuple2 == null) {
      tuple2 = putClassDef(readClassDef, null);
    }
    return tuple2;
  }

  private Tuple2<ClassDef, ClassInfo> putClassDef(ClassDef classDef, ClassInfo classInfo) {
    Tuple2<ClassDef, ClassInfo> tuple2 = Tuple2.of(classDef, classInfo);
    extRegistry.classIdToDef.put(classDef.getId(), tuple2);
    return tuple2;
  }

  final Class<?> loadClass(ClassSpec classSpec) {
    if (classSpec.type != null) {
      return classSpec.type;
    }
    return loadClass(classSpec.entireClassName, classSpec.isEnum, classSpec.dimension);
  }

  final Class<?> loadClass(String className, boolean isEnum, int arrayDims) {
    return loadClass(className, isEnum, arrayDims, fory.getConfig().deserializeNonexistentClass());
  }

  final Class<?> loadClass(String className) {
    return loadClass(className, false, -1, false);
  }

  final Class<?> loadClass(
      String className, boolean isEnum, int arrayDims, boolean deserializeNonexistentClass) {
    extRegistry.typeChecker.checkType(this, className);
    Class<?> cls = extRegistry.registeredClasses.get(className);
    if (cls != null) {
      return cls;
    }
    try {
      return Class.forName(className, false, fory.getClassLoader());
    } catch (ClassNotFoundException e) {
      try {
        return Class.forName(className, false, Thread.currentThread().getContextClassLoader());
      } catch (ClassNotFoundException ex) {
        String msg =
            String.format(
                "Class %s not found from classloaders [%s, %s]",
                className, fory.getClassLoader(), Thread.currentThread().getContextClassLoader());
        if (deserializeNonexistentClass) {
          LOG.warn(msg);
          return NonexistentClass.getNonexistentClass(
              className, isEnum, arrayDims, metaContextShareEnabled);
        }
        throw new IllegalStateException(msg, ex);
      }
    }
  }

  public abstract <T> Serializer<T> getSerializer(Class<T> cls);

  public abstract Serializer<?> getRawSerializer(Class<?> cls);

  public abstract <T> void setSerializer(Class<T> cls, Serializer<T> serializer);

  public abstract <T> void setSerializerIfAbsent(Class<T> cls, Serializer<T> serializer);

  public abstract ClassInfo nilClassInfo();

  public abstract ClassInfoHolder nilClassInfoHolder();

  public abstract GenericType buildGenericType(TypeRef<?> typeRef);

  public abstract GenericType buildGenericType(Type type);

  public abstract void initialize();

  public abstract ClassDef getTypeDef(Class<?> cls, boolean resolveParent);

  public final boolean isSerializable(Class<?> cls) {
    if (ReflectionUtils.isAbstract(cls) || cls.isInterface()) {
      return false;
    }
    try {
      ClassInfo classInfo = classInfoMap.get(cls);
      Serializer<?> serializer = null;
      if (classInfo != null) {
        serializer = classInfo.serializer;
      }
      getSerializerClass(cls, false);
      if (classInfo != null && serializer == null) {
        classInfo.serializer = null;
      }
      return true;
    } catch (Throwable t) {
      return false;
    }
  }

  public abstract Class<? extends Serializer> getSerializerClass(Class<?> cls);

  public abstract Class<? extends Serializer> getSerializerClass(Class<?> cls, boolean codegen);

  public final boolean isCollection(Class<?> cls) {
    if (Collection.class.isAssignableFrom(cls)) {
      return true;
    }
    if (fory.getConfig().isScalaOptimizationEnabled()) {
      // Scala map is scala iterable too.
      if (ScalaTypes.getScalaMapType().isAssignableFrom(cls)) {
        return false;
      }
      return ScalaTypes.getScalaIterableType().isAssignableFrom(cls);
    } else {
      return false;
    }
  }

  public final boolean isSet(Class<?> cls) {
    if (Set.class.isAssignableFrom(cls)) {
      return true;
    }
    if (fory.getConfig().isScalaOptimizationEnabled()) {
      // Scala map is scala iterable too.
      if (ScalaTypes.getScalaMapType().isAssignableFrom(cls)) {
        return false;
      }
      return ScalaTypes.getScalaSetType().isAssignableFrom(cls);
    } else {
      return false;
    }
  }

  public final boolean isMap(Class<?> cls) {
    if (cls == NonexistentMetaShared.class) {
      return false;
    }
    return Map.class.isAssignableFrom(cls)
        || (fory.getConfig().isScalaOptimizationEnabled()
            && ScalaTypes.getScalaMapType().isAssignableFrom(cls));
  }

  public final DescriptorGrouper createDescriptorGrouper(
      Collection<Descriptor> descriptors, boolean descriptorsGroupedOrdered) {
    return createDescriptorGrouper(descriptors, descriptorsGroupedOrdered, null);
  }

  public abstract DescriptorGrouper createDescriptorGrouper(
      Collection<Descriptor> descriptors,
      boolean descriptorsGroupedOrdered,
      Function<Descriptor, Descriptor> descriptorUpdator);

  public abstract Collection<Descriptor> getFieldDescriptors(Class<?> beanClass, boolean b);

  /**
   * Build a map of nested generic type name to generic type for all fields in the class.
   *
   * @param cls the class to build the map of nested generic type name to generic type for all
   *     fields in the class
   * @return a map of nested generic type name to generic type for all fields in the class
   */
  protected final Map<String, GenericType> buildGenericMap(Class<?> cls) {
    Map<String, GenericType> map = new HashMap<>();
    Map<String, GenericType> map2 = new HashMap<>();
    for (Field field : ReflectionUtils.getFields(cls, true)) {
      Type type = field.getGenericType();
      GenericType genericType = buildGenericType(type);
      buildGenericMap(map, genericType);
      TypeRef<?> typeRef = TypeRef.of(type);
      buildGenericMap(map2, typeRef);
    }
    map.putAll(map2);
    return map;
  }

  private void buildGenericMap(Map<String, GenericType> map, TypeRef<?> typeRef) {
    if (map.containsKey(typeRef.getType().getTypeName())) {
      return;
    }
    map.put(typeRef.getType().getTypeName(), buildGenericType(typeRef));
    Class<?> rawType = typeRef.getRawType();
    if (TypeUtils.isMap(rawType)) {
      Tuple2<TypeRef<?>, TypeRef<?>> kvTypes = TypeUtils.getMapKeyValueType(typeRef);
      buildGenericMap(map, kvTypes.f0);
      buildGenericMap(map, kvTypes.f1);
    } else if (TypeUtils.isCollection(rawType)) {
      TypeRef<?> elementType = TypeUtils.getElementType(typeRef);
      buildGenericMap(map, elementType);
    } else if (rawType.isArray()) {
      TypeRef<?> arrayComponent = TypeUtils.getArrayComponent(typeRef);
      buildGenericMap(map, arrayComponent);
    }
  }

  private void buildGenericMap(Map<String, GenericType> map, GenericType genericType) {
    if (map.containsKey(genericType.getType().getTypeName())) {
      return;
    }
    map.put(genericType.getType().getTypeName(), genericType);
    for (GenericType t : genericType.getTypeParameters()) {
      buildGenericMap(map, t);
    }
  }

  public void setTypeChecker(TypeChecker typeChecker) {
    extRegistry.typeChecker = typeChecker;
  }

  private static final ConcurrentMap<Integer, GraalvmClassRegistry> GRAALVM_REGISTRY =
      new ConcurrentHashMap<>();

  // CHECKSTYLE.OFF:MethodName
  public static void _addGraalvmClassRegistry(int foryConfigHash, ClassResolver classResolver) {
    // CHECKSTYLE.ON:MethodName
    if (GraalvmSupport.isGraalBuildtime()) {
      GraalvmClassRegistry registry =
          GRAALVM_REGISTRY.computeIfAbsent(foryConfigHash, k -> new GraalvmClassRegistry());
      registry.resolvers.add(classResolver);
    }
  }

  static class GraalvmClassRegistry {
    final List<ClassResolver> resolvers;
    final Map<Class<?>, Class<? extends Serializer>> serializerClassMap;
    final Map<Long, Class<? extends Serializer>> deserializerClassMap;

    private GraalvmClassRegistry() {
      resolvers = Collections.synchronizedList(new ArrayList<>());
      serializerClassMap = new ConcurrentHashMap<>();
      deserializerClassMap = new ConcurrentHashMap<>();
    }
  }

  final GraalvmClassRegistry getGraalvmClassRegistry() {
    return GRAALVM_REGISTRY.computeIfAbsent(
        fory.getConfig().getConfigHash(), k -> new GraalvmClassRegistry());
  }

  final Class<? extends Serializer> getGraalvmSerializerClass(Serializer serializer) {
    if (serializer instanceof GraalvmSerializerHolder) {
      return ((GraalvmSerializerHolder) serializer).getSerializerClass();
    }
    return serializer.getClass();
  }

  final Class<? extends Serializer> getSerializerClassFromGraalvmRegistry(Class<?> cls) {
    GraalvmClassRegistry registry = getGraalvmClassRegistry();
    List<ClassResolver> classResolvers = registry.resolvers;
    if (classResolvers.isEmpty()) {
      return null;
    }
    for (ClassResolver classResolver : classResolvers) {
      if (classResolver != this) {
        ClassInfo classInfo = getClassInfo(cls, false);
        if (classInfo != null && classInfo.serializer != null) {
          return classInfo.serializer.getClass();
        }
      }
    }
    Class<? extends Serializer> serializerClass = registry.serializerClassMap.get(cls);
    // noinspection Duplicates
    if (serializerClass != null) {
      return serializerClass;
    }
    if (GraalvmSupport.isGraalRuntime()) {
      // For array types, lambda and proxy, return null to let normal serializer logic handle them
      if (cls.isArray() || Functions.isLambda(cls) || ReflectionUtils.isJdkProxy(cls)) {
        return null;
      }
      // For registered classes (via register() method), return null to let normal logic handle them
      // This includes default registered classes like Throwable, StackTraceElement, Exception, etc.
      if (isRegistered(cls)) {
        return null;
      }
      throw new RuntimeException(String.format("Class %s is not registered", cls));
    }
    return null;
  }

  private Class<? extends Serializer> getMetaSharedDeserializerClassFromGraalvmRegistry(
      Class<?> cls, ClassDef classDef) {
    GraalvmClassRegistry registry = getGraalvmClassRegistry();
    List<ClassResolver> classResolvers = registry.resolvers;
    if (classResolvers.isEmpty()) {
      return null;
    }
    Class<? extends Serializer> deserializerClass =
        registry.deserializerClassMap.get(classDef.getId());
    // noinspection Duplicates
    if (deserializerClass != null) {
      return deserializerClass;
    }
    if (GraalvmSupport.isGraalRuntime()) {
      if (Functions.isLambda(cls) || ReflectionUtils.isJdkProxy(cls)) {
        return null;
      }
      throw new RuntimeException(
          String.format(
              "Class %s is not registered, registered classes: %s",
              cls, registry.deserializerClassMap));
    }
    return null;
  }

  public final Fory getFory() {
    return fory;
  }

  public final MetaStringResolver getMetaStringResolver() {
    return metaStringResolver;
  }

  static class ExtRegistry {
    // Here we set it to 1 because `NO_CLASS_ID` is 0 to avoid calculating it again in
    // `register(Class<?> cls)`.
    short classIdGenerator = 1;
    SerializerFactory serializerFactory;
    final IdentityMap<Class<?>, Short> registeredClassIdMap =
        new IdentityMap<>(estimatedNumRegistered);
    final BiMap<String, Class<?>> registeredClasses = HashBiMap.create(estimatedNumRegistered);
    // cache absClassInfo, support customized serializer for abstract or interface.
    final IdentityMap<Class<?>, ClassInfo> absClassInfo =
        new IdentityMap<>(estimatedNumRegistered, foryMapLoadFactor);
    // avoid potential recursive call for seq codec generation.
    // ex. A->field1: B, B.field1: A
    final Set<Class<?>> getClassCtx = new HashSet<>();
    final Map<Class<?>, FieldResolver> fieldResolverMap = new HashMap<>();
    final LongMap<Tuple2<ClassDef, ClassInfo>> classIdToDef = new LongMap<>();
    final Map<Class<?>, ClassDef> currentLayerClassDef = new HashMap<>();
    // Tuple2<Class, Class>: Tuple2<From Class, To Class>
    final Map<Tuple2<Class<?>, Class<?>>, ClassInfo> transformedClassInfo = new HashMap<>();
    // TODO(chaokunyang) Better to  use soft reference, see ObjectStreamClass.
    final ConcurrentHashMap<Tuple2<Class<?>, Boolean>, SortedMap<Member, Descriptor>>
        descriptorsCache = new ConcurrentHashMap<>();
    TypeChecker typeChecker = (resolver, className) -> true;
    GenericType objectGenericType;
    final IdentityMap<Type, GenericType> genericTypes = new IdentityMap<>();
    final Map<Class, Map<String, GenericType>> classGenericTypes = new HashMap<>();
    final Map<List<ClassLoader>, CodeGenerator> codeGeneratorMap = new HashMap<>();
    final Set<ClassInfo> registeredClassInfos = new HashSet<>();
    boolean ensureSerializersCompiled;
  }
}
