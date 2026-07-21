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

package org.apache.fory.annotation.processing;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;

public final class ForyStructProcessor extends AbstractProcessor {
  private static final int REFERENCE_BYTES = 4;
  // Source classes are not loadable as Class<?> during annotation processing; generated struct
  // estimates add compile-time field widths to the shared JVM object-base component.
  private static final int JVM_OBJECT_BASE_BYTES = REFERENCE_BYTES + REFERENCE_BYTES;
  private static final String PROCESSOR_PACKAGE = ForyStructProcessor.class.getPackage().getName();
  private static final String PROCESSOR_SUFFIX = ".annotation.processing";
  private static final String FORY_PACKAGE =
      PROCESSOR_PACKAGE.substring(0, PROCESSOR_PACKAGE.length() - PROCESSOR_SUFFIX.length());
  private static final String ANNOTATION_PACKAGE = FORY_PACKAGE + ".annotation";
  private static final String COLLECTION_PACKAGE = FORY_PACKAGE + ".collection";
  private static final String ARRAY_TYPE = annotationClass("ArrayType");
  private static final String BFLOAT16_TYPE = annotationClass("BFloat16Type");
  private static final String EXPOSE = annotationClass("Expose");
  private static final String FLOAT16_TYPE = annotationClass("Float16Type");
  private static final String FORY_DEBUG = annotationClass("ForyDebug");
  private static final String FORY_FIELD = annotationClass("ForyField");
  private static final String FORY_STRUCT = annotationClass("ForyStruct");
  private static final String JSON_TYPE = "org.apache.fory.json.annotation.JsonType";
  private static final String IGNORE = annotationClass("Ignore");
  private static final String INT32_TYPE = annotationClass("Int32Type");
  private static final String INT64_TYPE = annotationClass("Int64Type");
  private static final String INT8_TYPE = annotationClass("Int8Type");
  private static final String KOTLIN_METADATA = "kotlin.Metadata";
  private static final String NULLABLE = annotationClass("Nullable");
  private static final String REF = annotationClass("Ref");
  private static final String UINT16_TYPE = annotationClass("UInt16Type");
  private static final String UINT32_TYPE = annotationClass("UInt32Type");
  private static final String UINT64_TYPE = annotationClass("UInt64Type");
  private static final String UINT8_TYPE = annotationClass("UInt8Type");
  private static final Set<String> SUPPORTED_ANNOTATIONS = supportedAnnotations();

  private final Set<String> processedStructs = new HashSet<>();
  private final Map<String, TypeElement> generatedTypes = new HashMap<>();
  private Messager messager;
  private Filer filer;
  private Elements elements;
  private javax.lang.model.util.Types types;
  private JavacTypeUseTrees typeUseTrees;
  private JsonTypeProcessor jsonTypeProcessor;

  private static Set<String> supportedAnnotations() {
    Set<String> annotations = new HashSet<>();
    annotations.add(FORY_STRUCT);
    annotations.add(FORY_DEBUG);
    annotations.add(JSON_TYPE);
    return Collections.unmodifiableSet(annotations);
  }

  private static String annotationClass(String simpleName) {
    return ANNOTATION_PACKAGE + "." + simpleName;
  }

  private static String collectionClass(String simpleName) {
    return COLLECTION_PACKAGE + "." + simpleName;
  }

  private static String foryClass(String relativeName) {
    return FORY_PACKAGE + "." + relativeName;
  }

  private enum SerializerMode {
    XLANG("_ForySerializer"),
    NATIVE("_ForyNativeSerializer");

    final String serializerSuffix;

    SerializerMode(String serializerSuffix) {
      this.serializerSuffix = serializerSuffix;
    }
  }

  @Override
  public Set<String> getSupportedAnnotationTypes() {
    return SUPPORTED_ANNOTATIONS;
  }

  @Override
  public synchronized void init(ProcessingEnvironment processingEnv) {
    super.init(processingEnv);
    messager = processingEnv.getMessager();
    filer = processingEnv.getFiler();
    elements = processingEnv.getElementUtils();
    types = processingEnv.getTypeUtils();
    typeUseTrees = new JavacTypeUseTrees(processingEnv);
    jsonTypeProcessor = new JsonTypeProcessor(processingEnv);
  }

  @Override
  public SourceVersion getSupportedSourceVersion() {
    return SourceVersion.latestSupported();
  }

  @Override
  public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
    TypeElement foryStruct = elements.getTypeElement(FORY_STRUCT);
    if (foryStruct != null) {
      for (Element element : roundEnv.getElementsAnnotatedWith(foryStruct)) {
        if (!(element instanceof TypeElement)) {
          continue;
        }
        TypeElement type = (TypeElement) element;
        if (isKotlinClass(type)) {
          continue;
        }
        String binaryName = elements.getBinaryName(type).toString();
        if (!processedStructs.add(binaryName)) {
          continue;
        }
        try {
          List<SourceStruct> structs = buildStructs(type);
          for (SourceStruct struct : structs) {
            emit(struct, type);
          }
          emitR8Rules(type, structs);
        } catch (InvalidStructException e) {
          messager.printMessage(Diagnostic.Kind.ERROR, e.getMessage(), e.element);
        } catch (RuntimeException e) {
          messager.printMessage(
              Diagnostic.Kind.ERROR,
              "Failed to generate Fory static serializer for " + binaryName + ": " + e.getMessage(),
              type);
        }
      }
    }
    jsonTypeProcessor.process(roundEnv);
    return true;
  }

  private List<SourceStruct> buildStructs(TypeElement type) {
    List<SourceStruct> structs = new ArrayList<>(2);
    structs.add(buildStruct(type, SerializerMode.XLANG));
    structs.add(buildStruct(type, SerializerMode.NATIVE));
    return structs;
  }

  private SourceStruct buildStruct(TypeElement type, SerializerMode mode) {
    if (type.getModifiers().contains(Modifier.PRIVATE)) {
      throw new InvalidStructException("@ForyStruct classes must not be private", type);
    }
    NestingKind nestingKind = type.getNestingKind();
    if (nestingKind == NestingKind.LOCAL || nestingKind == NestingKind.ANONYMOUS) {
      throw new InvalidStructException(
          "@ForyStruct local and anonymous classes are unsupported", type);
    }
    if (nestingKind == NestingKind.MEMBER && !type.getModifiers().contains(Modifier.STATIC)) {
      throw new InvalidStructException("@ForyStruct member classes must be static", type);
    }

    PackageElement packageElement = elements.getPackageOf(type);
    String packageName =
        packageElement.isUnnamed() ? "" : packageElement.getQualifiedName().toString();
    String binaryName = elements.getBinaryName(type).toString();
    String serializerName = generatedSerializerName(binaryName, packageName, mode);
    String qualifiedSerializerName =
        packageName.isEmpty() ? serializerName : packageName + "." + serializerName;
    TypeElement existing = elements.getTypeElement(qualifiedSerializerName);
    if (existing != null && !existing.equals(type)) {
      throw new InvalidStructException(
          "Generated serializer name collides with existing type " + qualifiedSerializerName, type);
    }
    TypeElement previous = generatedTypes.put(qualifiedSerializerName, type);
    if (previous != null && !previous.equals(type)) {
      throw new InvalidStructException(
          "Generated serializer name "
              + qualifiedSerializerName
              + " is ambiguous for "
              + elements.getBinaryName(previous)
              + " and "
              + binaryName,
          type);
    }

    boolean record = isRecord(type);
    List<VariableElement> fields = record ? recordComponentFields(type) : serializableFields(type);
    List<SourceField> sourceFields = new ArrayList<>(fields.size());
    List<SourceField> recordConstructorFields = new ArrayList<>();
    Map<Integer, VariableElement> fieldIds = new HashMap<>();
    if (record) {
      int serializedId = 0;
      for (VariableElement field : fields) {
        boolean serialized = isSerializableRecordField(field, type);
        int id = serialized ? serializedId++ : -1;
        SourceField sourceField = buildField(id, type, packageName, field, true, serialized, mode);
        recordConstructorFields.add(sourceField);
        if (serialized) {
          validateForyFieldId(binaryName, fieldIds, field);
          sourceFields.add(sourceField);
        }
      }
    } else {
      for (int i = 0; i < fields.size(); i++) {
        VariableElement field = fields.get(i);
        validateForyFieldId(binaryName, fieldIds, field);
        SourceField sourceField = buildField(i, type, packageName, field, false, true, mode);
        sourceFields.add(sourceField);
        recordConstructorFields.add(sourceField);
      }
    }
    return new SourceStruct(
        packageName,
        canonicalName(type.asType()),
        binaryName,
        serializerName,
        record,
        isForyDebugEnabled(type),
        graphMemoryBytes(type),
        sourceFields,
        recordConstructorFields);
  }

  private int graphMemoryBytes(TypeElement type) {
    int bytes = JVM_OBJECT_BASE_BYTES;
    for (TypeElement current : hierarchy(type)) {
      for (VariableElement field : ElementFilter.fieldsIn(current.getEnclosedElements())) {
        if (!field.getModifiers().contains(Modifier.STATIC)) {
          bytes = Math.addExact(bytes, fieldGraphMemoryBytes(field.asType()));
        }
      }
    }
    return bytes;
  }

  private int fieldGraphMemoryBytes(TypeMirror type) {
    TypeKind kind = type.getKind();
    if (!kind.isPrimitive()) {
      return REFERENCE_BYTES;
    }
    switch (kind) {
      case BOOLEAN:
      case BYTE:
        return 1;
      case CHAR:
      case SHORT:
        return 2;
      case INT:
      case FLOAT:
        return 4;
      case LONG:
      case DOUBLE:
        return 8;
      default:
        return 0;
    }
  }

  private boolean isForyDebugEnabled(TypeElement type) {
    return annotationMirror(type, FORY_DEBUG) != null;
  }

  private void validateForyFieldId(
      String binaryName, Map<Integer, VariableElement> fieldIds, VariableElement field) {
    ForyFieldMeta foryField = foryField(field);
    if (foryField.hasForyField && foryField.id >= 0) {
      VariableElement previousField = fieldIds.put(foryField.id, field);
      if (previousField != null) {
        throw new InvalidStructException(
            "Duplicate @ForyField id " + foryField.id + " in " + binaryName, field);
      }
    }
  }

  private void emit(SourceStruct struct, TypeElement originatingType) {
    try {
      JavaFileObject file =
          filer.createSourceFile(struct.qualifiedSerializerName(), originatingType);
      try (Writer writer = file.openWriter()) {
        writer.write(new StaticSerializerSourceWriter(struct).write());
      }
    } catch (IOException e) {
      throw new InvalidStructException(
          "Failed to write generated serializer: " + e, originatingType);
    }
  }

  private void emitR8Rules(TypeElement originatingType, List<SourceStruct> structs) {
    if (structs.isEmpty()) {
      return;
    }
    String resourceName =
        "META-INF/proguard/fory-static-generated-"
            + escapedResourceName(structs.get(0).targetBinaryName)
            + ".pro";
    try {
      javax.tools.FileObject ruleFile =
          filer.createResource(StandardLocation.CLASS_OUTPUT, "", resourceName, originatingType);
      try (Writer writer = ruleFile.openWriter()) {
        writer.write(writeR8Rules(structs));
      }
    } catch (IOException e) {
      throw new InvalidStructException(
          "Failed to write Fory static generated serializer R8 rules: " + e, originatingType);
    }
  }

  private String writeR8Rules(List<SourceStruct> structs) {
    StringBuilder builder = new StringBuilder(1024);
    String targetType = structs.get(0).targetBinaryName;
    // Android release app R8 cannot see instrumentation-test or dynamic registration references.
    // Keep the struct class itself so the generated serializer and user registration target remain
    // installable even when the app has no other code roots.
    builder.append("-keep,allowoptimization class ").append(targetType).append(" { *; }\n\n");
    for (SourceStruct struct : structs) {
      builder.append("-if class ").append(targetType).append("\n");
      builder
          .append("-keep,allowoptimization class ")
          .append(struct.qualifiedSerializerName())
          .append(" {\n");
      builder.append("  public <init>();\n");
      builder
          .append("  public <init>(")
          .append(foryClass("resolver.TypeResolver"))
          .append(", java.lang.Class);\n");
      builder.append(
          "  public <init>("
              + foryClass("resolver.TypeResolver")
              + ", java.lang.Class, "
              + foryClass("meta.TypeDef")
              + ");\n");
      builder.append("}\n\n");
    }
    return builder.toString();
  }

  private static String escapedResourceName(String targetBinaryName) {
    return GeneratedTypeNames.escapeBinaryName(targetBinaryName);
  }

  private static String generatedSerializerName(
      String targetBinaryName, String packageName, SerializerMode mode) {
    String binarySimpleName =
        targetBinaryName.substring(packageName.isEmpty() ? 0 : packageName.length() + 1);
    return GeneratedTypeNames.escapeBinarySimpleName(binarySimpleName) + mode.serializerSuffix;
  }

  private boolean isKotlinClass(TypeElement type) {
    return annotationMirror(type, KOTLIN_METADATA) != null;
  }

  private SourceField buildField(
      int id,
      TypeElement owner,
      String generatedPackage,
      VariableElement field,
      boolean record,
      boolean serialized,
      SerializerMode mode) {
    Set<Modifier> modifiers = field.getModifiers();
    ForyFieldMeta foryField = foryField(field);
    Object fieldTypeTree = typeTree(field);
    boolean nullable = fieldNullable(field.asType(), fieldTypeTree, mode, field);
    boolean trackingRef = fieldTrackingRef(field, fieldTypeTree);
    boolean hasTrackingRefMetadata = fieldHasTrackingRefMetadata(field, fieldTypeTree);
    SourceTypeNode typeNode = buildFieldTypeNode(field.asType(), fieldTypeTree, nullable, field);
    String erasedType = canonicalName(types.erasure(field.asType()));
    String declaringClass =
        elements.getBinaryName((TypeElement) field.getEnclosingElement()).toString();

    SourceField.AccessKind readKind;
    SourceField.AccessKind writeKind;
    String readAccess;
    String writeAccess;
    if (record) {
      readKind = SourceField.AccessKind.METHOD;
      writeKind = SourceField.AccessKind.METHOD;
      readAccess = field.getSimpleName().toString();
      writeAccess = null;
    } else if (isAccessibleFromGenerated(field, generatedPackage)
        && !modifiers.contains(Modifier.FINAL)) {
      readKind = SourceField.AccessKind.FIELD;
      writeKind = SourceField.AccessKind.FIELD;
      readAccess = field.getSimpleName().toString();
      writeAccess = readAccess;
    } else {
      ExecutableElement getter = findGetter(owner, field, generatedPackage);
      ExecutableElement setter = findSetter(owner, field, generatedPackage);
      if (getter != null) {
        readKind = SourceField.AccessKind.METHOD;
        readAccess = getter.getSimpleName().toString();
      } else if (isAccessibleFromGenerated(field, generatedPackage)) {
        readKind = SourceField.AccessKind.FIELD;
        readAccess = field.getSimpleName().toString();
      } else {
        readKind = SourceField.AccessKind.ACCESSOR;
        readAccess = null;
      }
      if (!modifiers.contains(Modifier.FINAL) && setter != null) {
        writeKind = SourceField.AccessKind.METHOD;
        writeAccess = setter.getSimpleName().toString();
      } else if (!modifiers.contains(Modifier.FINAL)
          && isAccessibleFromGenerated(field, generatedPackage)) {
        writeKind = SourceField.AccessKind.FIELD;
        writeAccess = field.getSimpleName().toString();
      } else {
        writeKind = SourceField.AccessKind.ACCESSOR;
        writeAccess = null;
      }
    }
    return new SourceField(
        id,
        field.getSimpleName().toString(),
        erasedType,
        typeNode,
        reflectionModifiers(modifiers),
        declaringClass,
        serialized,
        hasAnnotation(field, ARRAY_TYPE),
        readKind,
        readAccess,
        writeKind,
        writeAccess,
        foryField.hasForyField,
        foryField.id,
        nullable,
        trackingRef,
        hasTrackingRefMetadata,
        foryField.dynamic);
  }

  private boolean fieldNullable(
      TypeMirror type, Object tree, SerializerMode mode, VariableElement field) {
    if (type.getKind().isPrimitive()) {
      return false;
    }
    if (typeUseAnnotation(type, typeUseTrees.tree(tree).annotations, NULLABLE, field) != null) {
      return true;
    }
    if (mode == SerializerMode.NATIVE) {
      return true;
    }
    return isOptionalType(type);
  }

  private boolean fieldTrackingRef(VariableElement field, Object tree) {
    TypeUseAnnotation ref =
        typeUseAnnotation(field.asType(), typeUseTrees.tree(tree).annotations, REF, field);
    if (ref == null) {
      AnnotationMirror fieldRef = annotationMirror(field, REF);
      ref = fieldRef == null ? null : new TypeUseAnnotation(fieldRef, null);
    }
    return ref != null && booleanValue(ref, "enable", true);
  }

  private boolean fieldHasTrackingRefMetadata(VariableElement field, Object tree) {
    if (hasAnnotation(field, REF)) {
      return true;
    }
    return typeUseAnnotation(field.asType(), typeUseTrees.tree(tree).annotations, REF, field)
        != null;
  }

  private boolean isOptionalType(TypeMirror type) {
    String erasedType = canonicalName(types.erasure(type));
    return erasedType.equals("java.util.Optional")
        || erasedType.equals("java.util.OptionalInt")
        || erasedType.equals("java.util.OptionalLong")
        || erasedType.equals("java.util.OptionalDouble");
  }

  private List<VariableElement> serializableFields(TypeElement type) {
    List<TypeElement> hierarchy = hierarchy(type);
    List<VariableElement> fields = new ArrayList<>();
    for (int i = hierarchy.size() - 1; i >= 0; i--) {
      TypeElement current = hierarchy.get(i);
      List<VariableElement> declaredFields = ElementFilter.fieldsIn(current.getEnclosedElements());
      boolean haveExpose = false;
      boolean haveIgnore = false;
      for (VariableElement field : declaredFields) {
        haveExpose |= hasAnnotation(field, EXPOSE);
        haveIgnore |= hasAnnotation(field, IGNORE);
        if (haveExpose && haveIgnore) {
          throw new InvalidStructException(
              "Fields of a class must not mix @Expose and @Ignore", field);
        }
      }
      for (VariableElement field : declaredFields) {
        Set<Modifier> modifiers = field.getModifiers();
        if (modifiers.contains(Modifier.STATIC) || modifiers.contains(Modifier.TRANSIENT)) {
          continue;
        }
        if (haveExpose) {
          if (hasAnnotation(field, EXPOSE)) {
            fields.add(field);
          }
        } else if (!hasAnnotation(field, IGNORE)) {
          fields.add(field);
        }
      }
    }
    return fields;
  }

  private List<VariableElement> recordComponentFields(TypeElement type) {
    Map<String, VariableElement> fieldsByName = new LinkedHashMap<>();
    for (VariableElement field : ElementFilter.fieldsIn(type.getEnclosedElements())) {
      fieldsByName.put(field.getSimpleName().toString(), field);
    }
    List<VariableElement> fields = new ArrayList<>();
    for (Element component : recordComponents(type)) {
      VariableElement field = fieldsByName.get(component.getSimpleName().toString());
      if (field != null) {
        fields.add(field);
      }
    }
    return fields;
  }

  private List<Element> recordComponents(TypeElement type) {
    // Keep the processor artifact compilable on JDK 11 while still using record components
    // when a newer compiler model provides them.
    Object components;
    try {
      components = TypeElement.class.getMethod("getRecordComponents").invoke(type);
    } catch (NoSuchMethodException e) {
      throw new InvalidStructException(
          "Record @ForyStruct processing requires a compiler with record component support", type);
    } catch (ReflectiveOperationException e) {
      throw new InvalidStructException("Failed to inspect record components: " + e, type);
    }
    if (!(components instanceof List<?>)) {
      throw new InvalidStructException("Unexpected record component model for " + type, type);
    }
    List<?> componentList = (List<?>) components;
    List<Element> componentElements = new ArrayList<>(componentList.size());
    for (Object component : componentList) {
      if (!(component instanceof Element)) {
        throw new InvalidStructException("Unexpected record component model for " + type, type);
      }
      componentElements.add((Element) component);
    }
    return componentElements;
  }

  private boolean isSerializableRecordField(VariableElement field, TypeElement owner) {
    if (field.getModifiers().contains(Modifier.TRANSIENT)) {
      return false;
    }
    if (hasAnnotation(field, IGNORE)) {
      return false;
    }
    ExecutableElement accessor = findRecordAccessor(owner, field);
    return accessor == null || !hasAnnotation(accessor, IGNORE);
  }

  private ExecutableElement findRecordAccessor(TypeElement owner, VariableElement field) {
    String name = field.getSimpleName().toString();
    for (ExecutableElement method : ElementFilter.methodsIn(owner.getEnclosedElements())) {
      if (method.getSimpleName().contentEquals(name) && method.getParameters().isEmpty()) {
        return method;
      }
    }
    return null;
  }

  private List<TypeElement> hierarchy(TypeElement type) {
    List<TypeElement> hierarchy = new ArrayList<>();
    TypeElement current = type;
    while (current != null && !current.getQualifiedName().contentEquals("java.lang.Object")) {
      hierarchy.add(current);
      TypeMirror superclass = current.getSuperclass();
      if (superclass == null || superclass.getKind() == TypeKind.NONE) {
        break;
      }
      Element element = types.asElement(superclass);
      current = element instanceof TypeElement ? (TypeElement) element : null;
    }
    return hierarchy;
  }

  private ExecutableElement findGetter(
      TypeElement owner, VariableElement field, String generatedPackage) {
    String name = field.getSimpleName().toString();
    String suffix = Character.toUpperCase(name.charAt(0)) + name.substring(1);
    List<String> candidates = new ArrayList<>();
    candidates.add("get" + suffix);
    if (field.asType().getKind() == TypeKind.BOOLEAN) {
      candidates.add("is" + suffix);
    }
    for (ExecutableElement method : methods(owner)) {
      if (!candidates.contains(method.getSimpleName().toString())) {
        continue;
      }
      if (!method.getParameters().isEmpty() || method.getReturnType().getKind() == TypeKind.VOID) {
        continue;
      }
      if (!isAccessibleFromGenerated(method, generatedPackage)) {
        continue;
      }
      if (types.isAssignable(method.getReturnType(), field.asType())) {
        return method;
      }
    }
    return null;
  }

  private ExecutableElement findSetter(
      TypeElement owner, VariableElement field, String generatedPackage) {
    String name = field.getSimpleName().toString();
    String suffix = Character.toUpperCase(name.charAt(0)) + name.substring(1);
    String setterName = "set" + suffix;
    for (ExecutableElement method : methods(owner)) {
      if (!method.getSimpleName().contentEquals(setterName)) {
        continue;
      }
      if (method.getParameters().size() != 1 || method.getReturnType().getKind() != TypeKind.VOID) {
        continue;
      }
      if (!isAccessibleFromGenerated(method, generatedPackage)) {
        continue;
      }
      if (types.isAssignable(field.asType(), method.getParameters().get(0).asType())) {
        return method;
      }
    }
    return null;
  }

  private List<ExecutableElement> methods(TypeElement owner) {
    List<ExecutableElement> methods = new ArrayList<>();
    for (TypeElement type : hierarchy(owner)) {
      methods.addAll(ElementFilter.methodsIn(type.getEnclosedElements()));
    }
    return methods;
  }

  private boolean isAccessibleFromGenerated(Element element, String generatedPackage) {
    Set<Modifier> modifiers = element.getModifiers();
    if (modifiers.contains(Modifier.PUBLIC)) {
      return true;
    }
    if (modifiers.contains(Modifier.PRIVATE)) {
      return false;
    }
    return elements.getPackageOf(element).getQualifiedName().contentEquals(generatedPackage);
  }

  private boolean isRecord(TypeElement type) {
    return type.getKind().name().equals("RECORD");
  }

  private int reflectionModifiers(Set<Modifier> modifiers) {
    int value = 0;
    if (modifiers.contains(Modifier.PUBLIC)) {
      value |= java.lang.reflect.Modifier.PUBLIC;
    }
    if (modifiers.contains(Modifier.PROTECTED)) {
      value |= java.lang.reflect.Modifier.PROTECTED;
    }
    if (modifiers.contains(Modifier.PRIVATE)) {
      value |= java.lang.reflect.Modifier.PRIVATE;
    }
    if (modifiers.contains(Modifier.STATIC)) {
      value |= java.lang.reflect.Modifier.STATIC;
    }
    if (modifiers.contains(Modifier.FINAL)) {
      value |= java.lang.reflect.Modifier.FINAL;
    }
    if (modifiers.contains(Modifier.TRANSIENT)) {
      value |= java.lang.reflect.Modifier.TRANSIENT;
    }
    if (modifiers.contains(Modifier.VOLATILE)) {
      value |= java.lang.reflect.Modifier.VOLATILE;
    }
    return value;
  }

  private SourceTypeNode buildFieldTypeNode(
      TypeMirror type, Object tree, boolean nullable, Element errorElement) {
    return buildTypeNode(type, tree, Boolean.toString(nullable), errorElement, false);
  }

  private Object typeTree(VariableElement field) {
    return typeUseTrees.typeTree(field);
  }

  private SourceTypeNode buildTypeNode(TypeMirror type) {
    return buildTypeNode(type, null, "true", null, false);
  }

  private SourceTypeNode buildTypeNode(
      TypeMirror type,
      Object tree,
      String typeExtNullable,
      Element errorElement,
      boolean arrayComponent) {
    TypeKind kind = type.getKind();
    JavacTypeUseTrees.Tree treeInfo = typeUseTrees.tree(tree);
    if (kind == TypeKind.TYPEVAR) {
      TypeVariable typeVariable = (TypeVariable) type;
      return buildTypeNode(
          typeVariable.getUpperBound(), null, typeExtNullable, errorElement, arrayComponent);
    }
    if (kind == TypeKind.WILDCARD) {
      WildcardType wildcard = (WildcardType) type;
      TypeMirror bound = wildcard.getExtendsBound();
      return buildTypeNode(
          bound == null ? elements.getTypeElement("java.lang.Object").asType() : bound,
          null,
          typeExtNullable,
          errorElement,
          arrayComponent);
    }
    List<SourceTypeNode> arguments = new ArrayList<>();
    SourceTypeNode componentType = null;
    if (kind == TypeKind.ARRAY) {
      TypeMirror componentMirror = ((ArrayType) type).getComponentType();
      componentType =
          buildTypeNode(
              componentMirror,
              treeInfo.arrayComponentTree(),
              nestedNullable(componentMirror),
              errorElement,
              true);
    } else if (type instanceof DeclaredType) {
      List<?> argumentTrees = treeInfo.typeArgumentTrees();
      int index = 0;
      for (TypeMirror argument : ((DeclaredType) type).getTypeArguments()) {
        Object argumentTree = index < argumentTrees.size() ? argumentTrees.get(index) : null;
        arguments.add(
            buildTypeNode(argument, argumentTree, nestedNullable(argument), errorElement, false));
        index++;
      }
    }
    String rawType = canonicalName(types.erasure(type));
    String extMeta =
        typeExtMetaExpression(
            type, rawType, treeInfo.annotations, typeExtNullable, errorElement, arrayComponent);
    boolean primitive = kind.isPrimitive();
    boolean nestedStruct = isCompatibleForyStructType(type);
    return new SourceTypeNode(
        rawType, typeName(type), extMeta, arguments, componentType, primitive, nestedStruct);
  }

  private boolean isCompatibleForyStructType(TypeMirror type) {
    TypeMirror erased = types.erasure(type);
    Element element = types.asElement(erased);
    if (!(element instanceof TypeElement)) {
      return false;
    }
    AnnotationMirror mirror = annotationMirror(element, FORY_STRUCT);
    if (mirror == null) {
      return false;
    }
    Map<? extends ExecutableElement, ? extends AnnotationValue> values =
        elements.getElementValuesWithDefaults(mirror);
    boolean evolving = true;
    String evolution = "INHERIT";
    for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry :
        values.entrySet()) {
      String name = entry.getKey().getSimpleName().toString();
      Object value = entry.getValue().getValue();
      if (name.equals("evolving")) {
        evolving = (Boolean) value;
      } else if (name.equals("evolution")) {
        evolution = enumConstant(String.valueOf(value));
      }
    }
    if ("DISABLED".equals(evolution)) {
      return false;
    }
    return evolving || "ENABLED".equals(evolution);
  }

  private String typeExtMetaExpression(
      TypeMirror type,
      String rawType,
      List<?> treeAnnotations,
      String nullable,
      Element errorElement,
      boolean arrayComponent) {
    String typeId = scalarTypeId(type, rawType, treeAnnotations, errorElement, arrayComponent);
    TypeUseAnnotation nullableAnnotation =
        typeUseAnnotation(type, treeAnnotations, NULLABLE, errorElement);
    TypeUseAnnotation ref = typeUseAnnotation(type, treeAnnotations, REF, errorElement);
    if (typeId == null && nullableAnnotation == null && ref == null) {
      return null;
    }
    return "meta("
        + (typeId == null ? "Types.UNKNOWN" : typeId)
        + ", "
        + (nullableAnnotation == null ? nullable : "true")
        + ", "
        + (ref != null && booleanValue(ref, "enable", true))
        + ")";
  }

  private String nestedNullable(TypeMirror type) {
    return Boolean.toString(!type.getKind().isPrimitive());
  }

  private String scalarTypeId(
      TypeMirror type,
      String rawType,
      List<?> treeAnnotations,
      Element errorElement,
      boolean arrayComponent) {
    if (hasTypeAnnotation(type, treeAnnotations, INT8_TYPE, errorElement)) {
      validateScalarCarrier(
          "@Int8Type",
          rawType,
          errorElement,
          "byte",
          "java.lang.Byte",
          "byte[]",
          collectionClass("Int8List"));
      return rawType.equals("byte[]") ? "Types.INT8_ARRAY" : "Types.INT8";
    }
    if (hasTypeAnnotation(type, treeAnnotations, UINT8_TYPE, errorElement)) {
      validateScalarCarrier(
          "@UInt8Type",
          rawType,
          errorElement,
          arrayComponent
              ? new String[] {"byte"}
              : new String[] {"int", "java.lang.Integer", "byte[]", collectionClass("UInt8List")});
      return rawType.equals("byte[]") ? "Types.UINT8_ARRAY" : "Types.UINT8";
    }
    if (hasTypeAnnotation(type, treeAnnotations, UINT16_TYPE, errorElement)) {
      validateScalarCarrier(
          "@UInt16Type",
          rawType,
          errorElement,
          arrayComponent
              ? new String[] {"short"}
              : new String[] {
                "int", "java.lang.Integer", "short[]", collectionClass("UInt16List")
              });
      return rawType.equals("short[]") ? "Types.UINT16_ARRAY" : "Types.UINT16";
    }
    TypeUseAnnotation uint32 = typeUseAnnotation(type, treeAnnotations, UINT32_TYPE, errorElement);
    if (uint32 != null) {
      validateScalarCarrier(
          "@UInt32Type",
          rawType,
          errorElement,
          arrayComponent
              ? new String[] {"int"}
              : new String[] {"long", "java.lang.Long", "int[]", collectionClass("UInt32List")});
      String encoding = int32Encoding(uint32);
      if (rawType.equals("int[]")) {
        return "Types.UINT32_ARRAY";
      }
      return "FIXED".equals(encoding) ? "Types.UINT32" : "Types.VAR_UINT32";
    }
    TypeUseAnnotation uint64 = typeUseAnnotation(type, treeAnnotations, UINT64_TYPE, errorElement);
    if (uint64 != null) {
      validateScalarCarrier(
          "@UInt64Type",
          rawType,
          errorElement,
          arrayComponent
              ? new String[] {"long"}
              : new String[] {"long", "java.lang.Long", "long[]", collectionClass("UInt64List")});
      String encoding = int64Encoding(uint64);
      if (rawType.equals("long[]")) {
        return "Types.UINT64_ARRAY";
      }
      if ("FIXED".equals(encoding)) {
        return "Types.UINT64";
      }
      return "TAGGED".equals(encoding) ? "Types.TAGGED_UINT64" : "Types.VAR_UINT64";
    }
    TypeUseAnnotation int32 = typeUseAnnotation(type, treeAnnotations, INT32_TYPE, errorElement);
    if (int32 != null) {
      validateScalarCarrier(
          "@Int32Type",
          rawType,
          errorElement,
          "int",
          "java.lang.Integer",
          collectionClass("Int32List"));
      String encoding = int32Encoding(int32);
      return "FIXED".equals(encoding) ? "Types.INT32" : "Types.VARINT32";
    }
    TypeUseAnnotation int64 = typeUseAnnotation(type, treeAnnotations, INT64_TYPE, errorElement);
    if (int64 != null) {
      validateScalarCarrier(
          "@Int64Type",
          rawType,
          errorElement,
          "long",
          "java.lang.Long",
          collectionClass("Int64List"));
      String encoding = int64Encoding(int64);
      if ("FIXED".equals(encoding)) {
        return "Types.INT64";
      }
      return "TAGGED".equals(encoding) ? "Types.TAGGED_INT64" : "Types.VARINT64";
    }
    if (hasTypeAnnotation(type, treeAnnotations, FLOAT16_TYPE, errorElement)) {
      validateScalarCarrier(
          "@Float16Type",
          rawType,
          errorElement,
          arrayComponent ? new String[] {"short"} : new String[] {"short[]"});
      return "Types.FLOAT16_ARRAY";
    }
    if (hasTypeAnnotation(type, treeAnnotations, BFLOAT16_TYPE, errorElement)) {
      validateScalarCarrier(
          "@BFloat16Type",
          rawType,
          errorElement,
          arrayComponent ? new String[] {"short"} : new String[] {"short[]"});
      return "Types.BFLOAT16_ARRAY";
    }
    return null;
  }

  private void validateScalarCarrier(
      String annotationName, String rawType, Element errorElement, String... allowedTypes) {
    for (String allowedType : allowedTypes) {
      if (rawType.equals(allowedType)) {
        return;
      }
    }
    throw new InvalidStructException(
        annotationName + " is not compatible with field type " + rawType, errorElement);
  }

  private boolean hasTypeAnnotation(
      TypeMirror type, List<?> treeAnnotations, String annotationName, Element source) {
    return typeUseAnnotation(type, treeAnnotations, annotationName, source) != null;
  }

  private TypeUseAnnotation typeUseAnnotation(
      TypeMirror type, List<?> treeAnnotations, String annotationName, Element source) {
    AnnotationMirror mirror = typeAnnotationMirror(type, annotationName);
    if (mirror != null) {
      return new TypeUseAnnotation(mirror, null);
    }
    for (Object annotationTree : treeAnnotations) {
      if (typeUseTrees.isAnnotation(source, annotationTree, annotationName)) {
        return new TypeUseAnnotation(null, annotationTree);
      }
    }
    return null;
  }

  private AnnotationMirror typeAnnotationMirror(TypeMirror type, String annotationName) {
    AnnotationMirror mirror = annotationMirror(type, annotationName);
    if (mirror != null || type.getKind() != TypeKind.ARRAY) {
      return mirror;
    }
    TypeMirror componentType = ((ArrayType) type).getComponentType();
    if (!componentType.getKind().isPrimitive()) {
      return null;
    }
    return annotationMirror(componentType, annotationName);
  }

  private AnnotationMirror annotationMirror(TypeMirror type, String annotationName) {
    for (AnnotationMirror mirror : type.getAnnotationMirrors()) {
      Element element = mirror.getAnnotationType().asElement();
      if (element instanceof TypeElement
          && ((TypeElement) element).getQualifiedName().contentEquals(annotationName)) {
        return mirror;
      }
    }
    return null;
  }

  private AnnotationMirror annotationMirror(Element element, String annotationName) {
    for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
      Element annotationElement = mirror.getAnnotationType().asElement();
      if (annotationElement instanceof TypeElement
          && ((TypeElement) annotationElement).getQualifiedName().contentEquals(annotationName)) {
        return mirror;
      }
    }
    return null;
  }

  private boolean hasAnnotation(Element element, String annotationName) {
    return annotationMirror(element, annotationName) != null;
  }

  private boolean booleanValue(TypeUseAnnotation annotation, String name, boolean defaultValue) {
    if (annotation == null) {
      return defaultValue;
    }
    if (annotation.mirror == null) {
      return Boolean.parseBoolean(
          typeUseTrees.annotationValue(annotation.tree, name, defaultValue));
    }
    AnnotationMirror mirror = annotation.mirror;
    for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry :
        mirror.getElementValues().entrySet()) {
      if (entry.getKey().getSimpleName().contentEquals(name)) {
        return (Boolean) entry.getValue().getValue();
      }
    }
    return defaultValue;
  }

  private String int32Encoding(TypeUseAnnotation annotation) {
    return enumValue(annotation, "encoding", "VARINT");
  }

  private String int64Encoding(TypeUseAnnotation annotation) {
    return enumValue(annotation, "encoding", "VARINT");
  }

  private String enumValue(TypeUseAnnotation annotation, String name, String defaultValue) {
    if (annotation == null) {
      return defaultValue;
    }
    if (annotation.mirror == null) {
      return enumConstant(typeUseTrees.annotationValue(annotation.tree, name, defaultValue));
    }
    AnnotationMirror mirror = annotation.mirror;
    for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry :
        mirror.getElementValues().entrySet()) {
      if (entry.getKey().getSimpleName().contentEquals(name)) {
        return String.valueOf(entry.getValue().getValue());
      }
    }
    return defaultValue;
  }

  private String enumConstant(String value) {
    int index = value.lastIndexOf('.');
    return index < 0 ? value : value.substring(index + 1);
  }

  private static final class TypeUseAnnotation {
    final AnnotationMirror mirror;
    final Object tree;

    TypeUseAnnotation(AnnotationMirror mirror, Object tree) {
      this.mirror = mirror;
      this.tree = tree;
    }
  }

  private ForyFieldMeta foryField(VariableElement field) {
    AnnotationMirror mirror = annotationMirror(field, FORY_FIELD);
    if (mirror == null) {
      return ForyFieldMeta.NONE;
    }
    Map<? extends ExecutableElement, ? extends AnnotationValue> values =
        elements.getElementValuesWithDefaults(mirror);
    int id = -1;
    String dynamic = "AUTO";
    for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry :
        values.entrySet()) {
      String name = entry.getKey().getSimpleName().toString();
      Object value = entry.getValue().getValue();
      if ("id".equals(name)) {
        id = ((Number) value).intValue();
      } else if ("dynamic".equals(name)) {
        dynamic = String.valueOf(value);
      }
    }
    if (id < -1) {
      throw new InvalidStructException(
          "@ForyField id must be -1 (no tag ID) or a non-negative tag ID", field);
    }
    return new ForyFieldMeta(true, id, dynamic);
  }

  private String canonicalName(TypeMirror type) {
    if (type.getKind().isPrimitive()) {
      return primitiveName(type.getKind());
    }
    if (type.getKind() == TypeKind.ARRAY) {
      return canonicalName(((ArrayType) type).getComponentType()) + "[]";
    }
    TypeMirror erased = types.erasure(type);
    Element element = types.asElement(erased);
    if (element instanceof TypeElement) {
      return ((TypeElement) element).getQualifiedName().toString();
    }
    return erased.toString().toLowerCase(Locale.ROOT);
  }

  private String typeName(TypeMirror type) {
    TypeKind kind = type.getKind();
    if (kind.isPrimitive()) {
      return primitiveName(kind);
    }
    if (kind == TypeKind.ARRAY) {
      return typeName(((ArrayType) type).getComponentType()) + "[]";
    }
    if (kind == TypeKind.TYPEVAR) {
      return typeName(((TypeVariable) type).getUpperBound());
    }
    if (kind == TypeKind.WILDCARD) {
      TypeMirror bound = ((WildcardType) type).getExtendsBound();
      return bound == null ? Object.class.getName() : typeName(bound);
    }
    TypeMirror erased = types.erasure(type);
    Element element = types.asElement(erased);
    String rawType =
        element instanceof TypeElement
            ? ((TypeElement) element).getQualifiedName().toString()
            : erased.toString();
    if (!(type instanceof DeclaredType)) {
      return rawType;
    }
    List<? extends TypeMirror> arguments = ((DeclaredType) type).getTypeArguments();
    if (arguments.isEmpty()) {
      return rawType;
    }
    StringBuilder builder = new StringBuilder(rawType).append("<");
    for (int i = 0; i < arguments.size(); i++) {
      if (i > 0) {
        builder.append(", ");
      }
      builder.append(typeName(arguments.get(i)));
    }
    return builder.append(">").toString();
  }

  private String primitiveName(TypeKind kind) {
    switch (kind) {
      case BOOLEAN:
        return "boolean";
      case BYTE:
        return "byte";
      case CHAR:
        return "char";
      case SHORT:
        return "short";
      case INT:
        return "int";
      case LONG:
        return "long";
      case FLOAT:
        return "float";
      case DOUBLE:
        return "double";
      case VOID:
        return "void";
      default:
        throw new IllegalArgumentException("Not a primitive kind: " + kind);
    }
  }

  private static final class InvalidStructException extends RuntimeException {
    final Element element;

    InvalidStructException(String message, Element element) {
      super(message);
      this.element = element;
    }
  }

  private static final class ForyFieldMeta {
    static final ForyFieldMeta NONE = new ForyFieldMeta(false, -1, "AUTO");

    final boolean hasForyField;
    final int id;
    final String dynamic;

    ForyFieldMeta(boolean hasForyField, int id, String dynamic) {
      this.hasForyField = hasForyField;
      this.id = id;
      this.dynamic = dynamic;
    }
  }
}
