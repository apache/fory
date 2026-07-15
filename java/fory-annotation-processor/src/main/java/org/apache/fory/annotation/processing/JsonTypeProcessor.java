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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.StandardLocation;

/** Generates precise Android R8 rules for {@code JsonType} models. */
final class JsonTypeProcessor {
  private static final String JSON_PACKAGE = "org.apache.fory.json";
  private static final String JSON_TYPE = JSON_PACKAGE + ".annotation.JsonType";
  private static final String JSON_CODEC = JSON_PACKAGE + ".annotation.JsonCodec";
  private static final String JSON_SUB_TYPES = JSON_PACKAGE + ".annotation.JsonSubTypes";
  private static final String JSON_CREATOR = JSON_PACKAGE + ".annotation.JsonCreator";
  private static final String JSON_PROPERTY = JSON_PACKAGE + ".annotation.JsonProperty";
  private static final String JSON_VALUE = JSON_PACKAGE + ".annotation.JsonValue";
  private static final String JSON_RAW_VALUE = JSON_PACKAGE + ".annotation.JsonRawValue";
  private static final String JSON_BASE64 = JSON_PACKAGE + ".annotation.JsonBase64";
  private static final String BASE64_CODEC = JSON_PACKAGE + ".codec.Base64ByteArrayCodec";
  private static final String JSON_ANY_GETTER = JSON_PACKAGE + ".annotation.JsonAnyGetter";
  private static final String JSON_ANY_SETTER = JSON_PACKAGE + ".annotation.JsonAnySetter";
  private static final String NO_JSON_VALUE_CODEC = JSON_CODEC + "$NoJsonValueCodec";
  private static final String NO_MAP_KEY_CODEC = JSON_CODEC + "$NoMapKeyCodec";
  private static final String R8_PREFIX = "META-INF/proguard/fory-json-";
  private static final String NATIVE_IMAGE_PREFIX =
      "META-INF/native-image/org.apache.fory/fory-json-";
  private static final String[] CODEC_MEMBERS = {
    "value", "elementCodec", "contentCodec", "keyCodec", "valueCodec"
  };

  private final Filer filer;
  private final Messager messager;
  private final Elements elements;
  private final Types types;
  private final GeneratedJsonCodecSourceWriter codecSourceWriter;
  private final Set<String> processedTypes = new HashSet<>();

  JsonTypeProcessor(ProcessingEnvironment environment) {
    filer = environment.getFiler();
    messager = environment.getMessager();
    elements = environment.getElementUtils();
    types = environment.getTypeUtils();
    codecSourceWriter = new GeneratedJsonCodecSourceWriter(environment);
  }

  void process(RoundEnvironment roundEnvironment) {
    TypeElement jsonType = elements.getTypeElement(JSON_TYPE);
    if (jsonType == null) {
      return;
    }
    Deque<TypeElement> pending = new ArrayDeque<>();
    for (Element element : roundEnvironment.getElementsAnnotatedWith(jsonType)) {
      if (element instanceof TypeElement) {
        pending.add((TypeElement) element);
      }
    }
    while (!pending.isEmpty()) {
      TypeElement type = pending.removeFirst();
      String binaryName = elements.getBinaryName(type).toString();
      if (!processedTypes.add(binaryName)) {
        continue;
      }
      try {
        Model model = inspect(type);
        if (hasAnnotation(type, JSON_TYPE)) {
          GeneratedJsonCodecSourceWriter.Result generated = codecSourceWriter.write(type);
          if (generated != null) {
            model.companionBinaryName = generated.companionBinaryName;
            model.companionHasAnySetter = generated.hasAnySetter;
            model.companionHasCreator = generated.hasCreator;
            model.companionHasCreatorFactory = generated.hasCreatorFactory;
            model.companionIsRecord = generated.record;
            for (GeneratedJsonCodecSourceWriter.MemberRule member : generated.r8Members) {
              model.addR8Member(new R8Member(member.ownerBinaryName, member.declaration));
            }
          }
        }
        List<TypeElement> subtypes = classLiteralSubtypes(type, model.binaryFallbackTypes);
        model.sort();
        emitR8(model);
        emitNativeImageProperties(model);
        pending.addAll(subtypes);
      } catch (GeneratedJsonCodecSourceWriter.InvalidJsonTypeException e) {
        messager.printMessage(Diagnostic.Kind.ERROR, e.getMessage(), e.element);
      } catch (InvalidJsonTypeException e) {
        messager.printMessage(Diagnostic.Kind.ERROR, e.getMessage(), e.element);
      } catch (RuntimeException e) {
        messager.printMessage(
            Diagnostic.Kind.ERROR,
            "Failed to generate Fory JSON R8 rules for " + binaryName + ": " + e.getMessage(),
            type);
      }
    }
  }

  private Model inspect(TypeElement target) {
    String binaryName = elements.getBinaryName(target).toString();
    Model model = new Model(target, binaryName);
    DeclaredType targetType = (DeclaredType) target.asType();
    collectAnnotations(target, model.annotationTypes);
    collectCodecAnnotation(annotationMirror(target, JSON_CODEC), model);
    model.annotationOwnerTypes.add(binaryName);

    List<TypeElement> classes = classHierarchy(target);
    Collections.reverse(classes);
    for (TypeElement type : classes) {
      collectAnnotations(type, model.annotationTypes);
      collectCodecAnnotation(annotationMirror(type, JSON_CODEC), model);
      if (hasJsonAnnotations(type)) {
        model.annotationOwnerTypes.add(elements.getBinaryName(type).toString());
      }
      for (VariableElement field : ElementFilter.fieldsIn(type.getEnclosedElements())) {
        collectAnnotations(field, model.annotationTypes);
        collectOccurrenceCodec(field, model);
        if (field.getKind() == ElementKind.ENUM_CONSTANT) {
          model.addR8Member(R8Member.field(field, typeName(field.asType())));
          continue;
        }
        // Runtime validation still needs to see annotated members that are not JSON properties.
        if (isEligibleField(field) || hasJsonAnnotations(field)) {
          model.addR8Member(R8Member.field(field, typeName(field.asType())));
          collectTypeEndpoints(types.asMemberOf(targetType, field), model);
        }
      }
    }

    for (TypeElement owner : allDeclarations(target)) {
      collectAnnotations(owner, model.annotationTypes);
      collectCodecAnnotation(annotationMirror(owner, JSON_CODEC), model);
      if (hasJsonAnnotations(owner)) {
        model.annotationOwnerTypes.add(elements.getBinaryName(owner).toString());
      }
    }
    // Ordinary property rules follow Java's effective method set. An unannotated override
    // suppresses the inherited declaration, while creators remain exact to the target declaration.
    List<ExecutableElement> jsonMethods = jsonMethods(target);
    for (ExecutableElement method : jsonMethods) {
      TypeElement owner = (TypeElement) method.getEnclosingElement();
      ExecutableType resolvedMethod = (ExecutableType) types.asMemberOf(targetType, method);
      model.addR8Member(
          R8Member.method(method, typeName(method.getReturnType()), typeNames(method)));
      collectAnnotations(method, model.annotationTypes);
      collectOccurrenceCodec(method, model);
      collectAnnotations(method.getParameters(), model.annotationTypes);
      collectCodecAnnotations(method.getParameters(), model);
      collectTypeEndpoints(resolvedMethod.getReturnType(), model);
      for (TypeMirror parameterType : resolvedMethod.getParameterTypes()) {
        collectTypeEndpoints(parameterType, model);
      }
      collectAnnotations(owner, model.annotationTypes);
    }
    collectValidationMethods(target, jsonMethods, model);

    for (ExecutableElement constructor :
        ElementFilter.constructorsIn(target.getEnclosedElements())) {
      collectAnnotations(constructor, model.annotationTypes);
      collectAnnotations(constructor.getParameters(), model.annotationTypes);
      collectCodecAnnotations(constructor.getParameters(), model);
      boolean creator = hasAnnotation(constructor, JSON_CREATOR);
      if (creator || isNoArg(constructor) || hasJsonDeclaration(constructor)) {
        model.addR8Member(R8Member.constructor(constructor, typeNames(constructor)));
        for (VariableElement parameter : constructor.getParameters()) {
          collectTypeEndpoints(parameter.asType(), model);
        }
      }
    }
    return model;
  }

  private void collectCodecAnnotations(List<? extends Element> sourceElements, Model model) {
    for (Element element : sourceElements) {
      collectCodecAnnotation(annotationMirror(element, JSON_CODEC), model);
    }
  }

  private void collectOccurrenceCodec(Element element, Model model) {
    collectCodecAnnotation(annotationMirror(element, JSON_CODEC), model);
    if (hasAnnotation(element, JSON_BASE64)) {
      model.codecTypes.add(BASE64_CODEC);
    }
  }

  private void collectCodecAnnotation(AnnotationMirror annotation, Model model) {
    if (annotation == null) {
      return;
    }
    model.annotationTypes.add(JSON_CODEC);
    for (String member : CODEC_MEMBERS) {
      AnnotationValue value = annotationValue(annotation, member);
      if (value == null || !(value.getValue() instanceof TypeMirror)) {
        continue;
      }
      TypeElement codec = asTypeElement((TypeMirror) value.getValue());
      if (codec == null) {
        continue;
      }
      String codecName = elements.getBinaryName(codec).toString();
      if (!codecName.equals(NO_JSON_VALUE_CODEC) && !codecName.equals(NO_MAP_KEY_CODEC)) {
        model.codecTypes.add(codecName);
      }
    }
  }

  private void emitR8(Model model) {
    String resourceName = R8_PREFIX + model.binaryName + ".pro";
    try {
      javax.tools.FileObject file =
          filer.createResource(StandardLocation.CLASS_OUTPUT, "", resourceName, model.target);
      try (Writer writer = file.openWriter()) {
        writer.write(writeR8(model));
      }
    } catch (IOException e) {
      throw new InvalidJsonTypeException("Failed to write Fory JSON R8 rules: " + e, model.target);
    }
  }

  private void emitNativeImageProperties(Model model) {
    if (model.companionBinaryName == null) {
      return;
    }
    // The hosted feature freezes factory instances into the image heap after reachability is
    // known, but GraalVM accepts class-initialization configuration only before analysis starts.
    // Emit the exact generated class here so unreachable model classes remain removable.
    String resourceName =
        NATIVE_IMAGE_PREFIX + model.companionBinaryName + "/native-image.properties";
    try {
      javax.tools.FileObject file =
          filer.createResource(StandardLocation.CLASS_OUTPUT, "", resourceName, model.target);
      try (Writer writer = file.openWriter()) {
        writer.write("Args=--initialize-at-build-time=" + model.companionBinaryName + "$Factory\n");
      }
    } catch (IOException e) {
      throw new InvalidJsonTypeException(
          "Failed to write generated JSON Native Image properties: " + e, model.target);
    }
  }

  private String writeR8(Model model) {
    StringBuilder builder = new StringBuilder(8192);
    builder.append("-keepattributes Signature,RuntimeVisibleAnnotations\n");
    builder.append("-keepattributes RuntimeVisibleParameterAnnotations\n");
    builder.append("-keepattributes AnnotationDefault\n");
    builder.append("-keepattributes MethodParameters\n");
    if (model.hasNestedIdentity()) {
      builder.append("-keepattributes InnerClasses,EnclosingMethod\n");
    }
    builder.append('\n');
    Map<String, List<R8Member>> membersByOwner = new LinkedHashMap<>();
    membersByOwner.put(model.binaryName, new ArrayList<R8Member>());
    for (String annotationOwner : model.annotationOwnerTypes) {
      membersByOwner.putIfAbsent(annotationOwner, new ArrayList<R8Member>());
    }
    for (R8Member member : model.r8Members) {
      membersByOwner
          .computeIfAbsent(member.ownerBinaryName, key -> new ArrayList<R8Member>())
          .add(member);
    }
    for (Map.Entry<String, List<R8Member>> entry : membersByOwner.entrySet()) {
      boolean preserveName =
          model.binaryFallbackTypes.contains(entry.getKey())
              || model.companionBinaryName != null && model.binaryName.equals(entry.getKey());
      builder
          .append("-keep,allowoptimization")
          .append(preserveName ? "" : ",allowobfuscation")
          .append(" class ")
          .append(entry.getKey())
          .append("\n");
      if (entry.getValue().isEmpty()) {
        builder.append('\n');
        continue;
      }
      builder
          .append("-keepclassmembers,allowoptimization class ")
          .append(entry.getKey())
          .append(" {\n");
      for (R8Member member : entry.getValue()) {
        builder.append("  ").append(member.declaration).append("\n");
      }
      builder.append("}\n\n");
    }
    for (String fallback : model.binaryFallbackTypes) {
      if (!membersByOwner.containsKey(fallback)) {
        builder.append("-keep,allowoptimization class ").append(fallback);
        if (model.codecTypes.contains(fallback)) {
          builder.append(" { public <init>(); }");
        }
        builder.append("\n");
      }
    }
    for (String annotationType : model.annotationTypes) {
      builder
          .append("-keep,allowoptimization,allowobfuscation @interface ")
          .append(annotationType)
          .append("\n");
    }
    for (String container : model.containerTypes) {
      builder
          .append("-keep,allowoptimization,allowobfuscation class ")
          .append(container)
          .append(" { public <init>(); }\n");
    }
    for (String codec : model.codecTypes) {
      if (model.binaryFallbackTypes.contains(codec)) {
        continue;
      }
      builder
          .append("-keep,allowoptimization,allowobfuscation class ")
          .append(codec)
          .append(" { public <init>(); }\n");
    }
    if (model.companionBinaryName != null) {
      builder
          .append("-keep,allowoptimization class ")
          .append(model.companionBinaryName)
          .append(" {\n")
          .append("  public <init>();\n")
          .append("  public java.lang.Class type();\n")
          .append("  public org.apache.fory.json.meta.JsonFieldAccessor[] fieldAccessors();\n");
      if (model.companionHasAnySetter) {
        builder.append(
            "  public org.apache.fory.json.meta.JsonAnySetterAccessor anySetterAccessor();\n");
      }
      if (model.companionHasCreator) {
        builder
            .append("  public java.lang.String[] creatorParameterNames();\n")
            .append("  public java.lang.Class[] creatorParameterTypes();\n")
            .append("  public java.lang.Object newInstance(java.lang.Object[]);\n");
      }
      if (model.companionHasCreatorFactory) {
        builder.append("  public java.lang.String creatorFactoryName();\n");
      }
      if (model.companionIsRecord) {
        builder.append("  public boolean isRecord();\n");
      }
      builder.append("}\n");
    }
    return builder.toString();
  }

  private List<TypeElement> classLiteralSubtypes(
      TypeElement target, Set<String> binaryFallbackTypes) {
    AnnotationMirror subtypes = annotationMirror(target, JSON_SUB_TYPES);
    if (subtypes == null) {
      return Collections.emptyList();
    }
    AnnotationValue entriesValue = annotationValue(subtypes, "value");
    if (entriesValue == null || !(entriesValue.getValue() instanceof List<?>)) {
      return Collections.emptyList();
    }
    List<TypeElement> subtypesToProcess = new ArrayList<>();
    for (Object rawEntry : (List<?>) entriesValue.getValue()) {
      Object entryValue =
          rawEntry instanceof AnnotationValue ? ((AnnotationValue) rawEntry).getValue() : null;
      if (!(entryValue instanceof AnnotationMirror)) {
        continue;
      }
      AnnotationMirror entry = (AnnotationMirror) entryValue;
      AnnotationValue classValue = annotationValue(entry, "value");
      if (classValue != null && classValue.getValue() instanceof TypeMirror) {
        TypeMirror subtypeMirror = (TypeMirror) classValue.getValue();
        TypeElement subtype = asTypeElement(subtypeMirror);
        if (subtype != null && !subtype.getQualifiedName().contentEquals("java.lang.Void")) {
          subtypesToProcess.add(subtype);
        }
      }
      AnnotationValue classNameValue = annotationValue(entry, "className");
      if (classNameValue != null) {
        String className = String.valueOf(classNameValue.getValue());
        if (!className.isEmpty()) {
          binaryFallbackTypes.add(className);
        }
      }
    }
    subtypesToProcess.sort(Comparator.comparing(type -> elements.getBinaryName(type).toString()));
    return subtypesToProcess;
  }

  private List<TypeElement> classHierarchy(TypeElement target) {
    List<TypeElement> hierarchy = new ArrayList<>();
    TypeElement current = target;
    while (current != null && !current.getQualifiedName().contentEquals("java.lang.Object")) {
      hierarchy.add(current);
      TypeMirror superclass = current.getSuperclass();
      current = superclass.getKind() == TypeKind.NONE ? null : asTypeElement(superclass);
    }
    return hierarchy;
  }

  private List<ExecutableElement> jsonMethods(TypeElement target) {
    Map<String, ExecutableElement> methods = new LinkedHashMap<>();
    for (ExecutableElement method : ElementFilter.methodsIn(elements.getAllMembers(target))) {
      TypeElement owner = (TypeElement) method.getEnclosingElement();
      if (!isJsonMethod(method, owner.equals(target))) {
        continue;
      }
      String key = elements.getBinaryName(owner) + "#" + methodSignature(method);
      methods.put(key, method);
    }
    List<ExecutableElement> result = new ArrayList<>(methods.values());
    result.sort(Comparator.comparing(this::memberSortKey));
    return result;
  }

  private void collectValidationMethods(
      TypeElement target, List<ExecutableElement> effectiveMethods, Model model) {
    // Runtime validation scans declared class methods, including private and static declarations
    // that property discovery cannot select. R8 must retain those declarations and annotations so
    // release builds reject the same invalid model instead of silently losing the annotation.
    Set<String> effectiveKeys = new HashSet<>();
    for (ExecutableElement method : effectiveMethods) {
      effectiveKeys.add(methodKey(method));
    }
    for (TypeElement owner : classHierarchy(target)) {
      boolean targetDeclaration = owner.equals(target);
      for (ExecutableElement method : ElementFilter.methodsIn(owner.getEnclosedElements())) {
        if (!hasMethodValidationAnnotation(method)
            || !isEffectiveValidationMethod(method, targetDeclaration, effectiveKeys)) {
          continue;
        }
        collectAnnotations(method, model.annotationTypes);
        collectAnnotations(method.getParameters(), model.annotationTypes);
        collectOccurrenceCodec(method, model);
        collectCodecAnnotations(method.getParameters(), model);
        collectTypeEndpoints(method.getReturnType(), model);
        for (VariableElement parameter : method.getParameters()) {
          collectTypeEndpoints(parameter.asType(), model);
        }
        model.addR8Member(
            R8Member.method(method, typeName(method.getReturnType()), typeNames(method)));
      }
    }
  }

  private boolean isEffectiveValidationMethod(
      ExecutableElement method, boolean targetDeclaration, Set<String> effectiveKeys) {
    Set<Modifier> modifiers = method.getModifiers();
    return targetDeclaration
        || !modifiers.contains(Modifier.PUBLIC)
        || modifiers.contains(Modifier.STATIC)
        || effectiveKeys.contains(methodKey(method));
  }

  private boolean hasMethodValidationAnnotation(ExecutableElement method) {
    return hasJsonDeclaration(method);
  }

  private String methodKey(ExecutableElement method) {
    TypeElement owner = (TypeElement) method.getEnclosingElement();
    return elements.getBinaryName(owner) + "#" + methodSignature(method);
  }

  private List<TypeElement> allDeclarations(TypeElement target) {
    LinkedHashMap<String, TypeElement> owners = new LinkedHashMap<>();
    Deque<TypeElement> pending = new ArrayDeque<>();
    pending.add(target);
    while (!pending.isEmpty()) {
      TypeElement type = pending.removeFirst();
      String binaryName = elements.getBinaryName(type).toString();
      if (owners.put(binaryName, type) != null) {
        continue;
      }
      TypeMirror superclass = type.getSuperclass();
      TypeElement superType =
          superclass.getKind() == TypeKind.NONE ? null : asTypeElement(superclass);
      if (superType != null && !superType.getQualifiedName().contentEquals("java.lang.Object")) {
        pending.add(superType);
      }
      for (TypeMirror interfaceType : type.getInterfaces()) {
        TypeElement interfaceElement = asTypeElement(interfaceType);
        if (interfaceElement != null) {
          pending.add(interfaceElement);
        }
      }
    }
    return new ArrayList<>(owners.values());
  }

  private boolean isJsonMethod(ExecutableElement method, boolean targetDeclaration) {
    if (hasAnnotation(method, JSON_PROPERTY)
        || hasAnnotation(method, JSON_ANY_GETTER)
        || hasAnnotation(method, JSON_ANY_SETTER)
        || hasAnnotation(method, JSON_CODEC)
        || hasAnnotation(method, JSON_VALUE)
        || hasAnnotation(method, JSON_RAW_VALUE)
        || hasAnnotation(method, JSON_BASE64)
        || hasJsonAnnotations(method.getParameters())) {
      return true;
    }
    if (targetDeclaration && hasAnnotation(method, JSON_CREATOR)) {
      return true;
    }
    Set<Modifier> modifiers = method.getModifiers();
    if (!modifiers.contains(Modifier.PUBLIC) || modifiers.contains(Modifier.STATIC)) {
      return false;
    }
    String name = method.getSimpleName().toString();
    int parameters = method.getParameters().size();
    String returnType = typeName(method.getReturnType());
    return parameters == 0
            && method.getReturnType().getKind() != TypeKind.VOID
            && !returnType.equals("java.lang.Class")
            && (name.startsWith("get") && name.length() > 3
                || name.startsWith("is")
                    && name.length() > 2
                    && (method.getReturnType().getKind() == TypeKind.BOOLEAN
                        || returnType.equals("java.lang.Boolean")))
        || parameters == 1
            && method.getReturnType().getKind() == TypeKind.VOID
            && !typeName(method.getParameters().get(0).asType()).equals("java.lang.Class")
            && name.startsWith("set")
            && name.length() > 3;
  }

  private boolean isEligibleField(VariableElement field) {
    Set<Modifier> modifiers = field.getModifiers();
    return !modifiers.contains(Modifier.STATIC)
        && !modifiers.contains(Modifier.TRANSIENT)
        && !typeName(field.asType()).equals("java.lang.Class");
  }

  private boolean isNoArg(ExecutableElement constructor) {
    return constructor.getParameters().isEmpty();
  }

  private void collectTypeEndpoints(TypeMirror type, Model model) {
    TypeMirror erased = types.erasure(type);
    TypeElement rawType = asTypeElement(erased);
    if (rawType != null) {
      collectTypeCodec(rawType, model);
      if (isConcreteContainer(rawType)) {
        model.containerTypes.add(elements.getBinaryName(rawType).toString());
      }
    }
    if (type instanceof DeclaredType) {
      for (TypeMirror argument : ((DeclaredType) type).getTypeArguments()) {
        collectTypeEndpoints(argument, model);
      }
    } else if (type.getKind() == TypeKind.ARRAY) {
      collectTypeEndpoints(((ArrayType) type).getComponentType(), model);
    } else if (type.getKind() == TypeKind.WILDCARD) {
      WildcardType wildcard = (WildcardType) type;
      if (wildcard.getExtendsBound() != null) {
        collectTypeEndpoints(wildcard.getExtendsBound(), model);
      }
      if (wildcard.getSuperBound() != null) {
        collectTypeEndpoints(wildcard.getSuperBound(), model);
      }
    }
  }

  private void collectTypeCodec(TypeElement type, Model model) {
    if (type.getKind() == ElementKind.ANNOTATION_TYPE) {
      return;
    }
    // Match JsonSharedRegistry's declaration lookup: a direct declaration hides all inherited
    // declarations; otherwise only the most-specific inherited declarations are inspected.
    // Their owners must remain reflection-visible as well as their selected codec constructors.
    AnnotationMirror direct = annotationMirror(type, JSON_CODEC);
    if (direct != null) {
      collectCodecDeclaration(type, direct, model);
      return;
    }
    List<TypeElement> candidates = new ArrayList<>();
    List<TypeElement> declarations = allDeclarations(type);
    for (int i = 1; i < declarations.size(); i++) {
      TypeElement declaration = declarations.get(i);
      if (annotationMirror(declaration, JSON_CODEC) != null) {
        candidates.add(declaration);
      }
    }
    for (TypeElement candidate : candidates) {
      boolean dominated = false;
      for (TypeElement other : candidates) {
        if (!candidate.equals(other)
            && types.isAssignable(
                types.erasure(other.asType()), types.erasure(candidate.asType()))) {
          dominated = true;
          break;
        }
      }
      if (!dominated) {
        collectCodecDeclaration(candidate, annotationMirror(candidate, JSON_CODEC), model);
      }
    }
  }

  private void collectCodecDeclaration(
      TypeElement declaration, AnnotationMirror annotation, Model model) {
    collectCodecAnnotation(annotation, model);
    model.annotationOwnerTypes.add(elements.getBinaryName(declaration).toString());
  }

  private boolean isConcreteContainer(TypeElement type) {
    if (type.getModifiers().contains(Modifier.ABSTRACT)
        || type.getKind() == ElementKind.INTERFACE) {
      return false;
    }
    TypeElement collection = elements.getTypeElement("java.util.Collection");
    TypeElement map = elements.getTypeElement("java.util.Map");
    return collection != null
            && types.isAssignable(types.erasure(type.asType()), types.erasure(collection.asType()))
        || map != null
            && types.isAssignable(types.erasure(type.asType()), types.erasure(map.asType()));
  }

  private List<String> typeNames(ExecutableElement executable) {
    List<String> names = new ArrayList<>();
    for (VariableElement parameter : executable.getParameters()) {
      names.add(typeName(parameter.asType()));
    }
    return names;
  }

  private String typeName(TypeMirror type) {
    TypeMirror erased = types.erasure(type);
    if (erased.getKind() == TypeKind.ARRAY) {
      return typeName(((ArrayType) erased).getComponentType()) + "[]";
    }
    if (erased.getKind().isPrimitive() || erased.getKind() == TypeKind.VOID) {
      return erased.toString();
    }
    TypeElement element = asTypeElement(erased);
    return element == null ? erased.toString() : elements.getBinaryName(element).toString();
  }

  private TypeElement asTypeElement(TypeMirror type) {
    Element element = types.asElement(type);
    return element instanceof TypeElement ? (TypeElement) element : null;
  }

  private void collectAnnotations(Element element, Set<String> annotationTypes) {
    for (AnnotationMirror annotation : element.getAnnotationMirrors()) {
      Element annotationType = annotation.getAnnotationType().asElement();
      if (annotationType instanceof TypeElement) {
        String name = ((TypeElement) annotationType).getQualifiedName().toString();
        if (name.startsWith(JSON_PACKAGE + ".annotation.")) {
          annotationTypes.add(name);
        }
      }
    }
  }

  private void collectAnnotations(
      List<? extends Element> sourceElements, Set<String> annotationTypes) {
    for (Element element : sourceElements) {
      collectAnnotations(element, annotationTypes);
    }
  }

  private AnnotationMirror annotationMirror(Element element, String annotationName) {
    for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
      Element annotationType = mirror.getAnnotationType().asElement();
      if (annotationType instanceof TypeElement
          && ((TypeElement) annotationType).getQualifiedName().contentEquals(annotationName)) {
        return mirror;
      }
    }
    return null;
  }

  private boolean hasAnnotation(Element element, String annotationName) {
    return annotationMirror(element, annotationName) != null;
  }

  private boolean hasJsonAnnotations(Element element) {
    for (AnnotationMirror annotation : element.getAnnotationMirrors()) {
      Element annotationType = annotation.getAnnotationType().asElement();
      if (annotationType instanceof TypeElement
          && ((TypeElement) annotationType)
              .getQualifiedName()
              .toString()
              .startsWith(JSON_PACKAGE + ".annotation.")) {
        return true;
      }
    }
    return false;
  }

  private boolean hasJsonAnnotations(List<? extends Element> sourceElements) {
    for (Element element : sourceElements) {
      if (hasJsonAnnotations(element)) {
        return true;
      }
    }
    return false;
  }

  private boolean hasJsonDeclaration(ExecutableElement executable) {
    return hasJsonAnnotations(executable) || hasJsonAnnotations(executable.getParameters());
  }

  private AnnotationValue annotationValue(AnnotationMirror annotation, String name) {
    for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry :
        elements.getElementValuesWithDefaults(annotation).entrySet()) {
      if (entry.getKey().getSimpleName().contentEquals(name)) {
        return entry.getValue();
      }
    }
    return null;
  }

  private String memberSortKey(ExecutableElement method) {
    return elements.getBinaryName((TypeElement) method.getEnclosingElement())
        + "#"
        + methodSignature(method);
  }

  private String methodSignature(ExecutableElement method) {
    return method.getSimpleName() + "(" + String.join(",", typeNames(method)) + ")";
  }

  private static final class Model {
    final TypeElement target;
    final String binaryName;
    final List<R8Member> r8Members = new ArrayList<>();
    final Set<String> r8MemberKeys = new HashSet<>();
    final Set<String> annotationTypes = new LinkedHashSet<>();
    final Set<String> containerTypes = new LinkedHashSet<>();
    final Set<String> codecTypes = new LinkedHashSet<>();
    final Set<String> binaryFallbackTypes = new LinkedHashSet<>();
    final Set<String> annotationOwnerTypes = new LinkedHashSet<>();
    String companionBinaryName;
    boolean companionHasAnySetter;
    boolean companionHasCreator;
    boolean companionHasCreatorFactory;
    boolean companionIsRecord;

    Model(TypeElement target, String binaryName) {
      this.target = target;
      this.binaryName = binaryName;
    }

    void addR8Member(R8Member member) {
      if (r8MemberKeys.add(member.ownerBinaryName + "#" + member.declaration)) {
        r8Members.add(member);
      }
    }

    boolean hasNestedIdentity() {
      return binaryName.indexOf('$') >= 0
          || containsNested(binaryFallbackTypes)
          || containsNested(annotationOwnerTypes)
          || containsNested(containerTypes)
          || containsNested(codecTypes)
          || containsNestedMember(r8Members);
    }

    void sort() {
      Collections.sort(r8Members);
      sortSet(annotationTypes);
      sortSet(containerTypes);
      sortSet(codecTypes);
      sortSet(binaryFallbackTypes);
      sortSet(annotationOwnerTypes);
    }

    private static boolean containsNested(Set<String> names) {
      for (String name : names) {
        if (name.indexOf('$') >= 0) {
          return true;
        }
      }
      return false;
    }

    private static boolean containsNestedMember(List<R8Member> members) {
      for (R8Member member : members) {
        if (member.ownerBinaryName.indexOf('$') >= 0 || member.declaration.indexOf('$') >= 0) {
          return true;
        }
      }
      return false;
    }

    private static void sortSet(Set<String> values) {
      List<String> sorted = new ArrayList<>(values);
      Collections.sort(sorted);
      values.clear();
      values.addAll(sorted);
    }
  }

  private static String binaryName(TypeElement type) {
    Deque<String> names = new ArrayDeque<>();
    Element current = type;
    while (current instanceof TypeElement) {
      names.addFirst(current.getSimpleName().toString());
      current = current.getEnclosingElement();
    }
    String packageName =
        current instanceof PackageElement
            ? ((PackageElement) current).getQualifiedName().toString()
            : "";
    return (packageName.isEmpty() ? "" : packageName + ".") + String.join("$", names);
  }

  private static final class R8Member implements Comparable<R8Member> {
    final String ownerBinaryName;
    final String declaration;

    R8Member(String ownerBinaryName, String declaration) {
      this.ownerBinaryName = ownerBinaryName;
      this.declaration = declaration;
    }

    static R8Member field(VariableElement field, String typeName) {
      TypeElement owner = (TypeElement) field.getEnclosingElement();
      return new R8Member(binaryName(owner), typeName + " " + field.getSimpleName() + ";");
    }

    static R8Member method(
        ExecutableElement method, String returnType, List<String> parameterTypes) {
      TypeElement owner = (TypeElement) method.getEnclosingElement();
      return new R8Member(
          binaryName(owner),
          returnType
              + " "
              + method.getSimpleName()
              + "("
              + String.join(",", parameterTypes)
              + ");");
    }

    static R8Member constructor(ExecutableElement constructor, List<String> parameterTypes) {
      TypeElement owner = (TypeElement) constructor.getEnclosingElement();
      return new R8Member(binaryName(owner), "<init>(" + String.join(",", parameterTypes) + ");");
    }

    @Override
    public int compareTo(R8Member other) {
      int owner = ownerBinaryName.compareTo(other.ownerBinaryName);
      return owner != 0 ? owner : declaration.compareTo(other.declaration);
    }
  }

  private static final class InvalidJsonTypeException extends RuntimeException {
    final Element element;

    InvalidJsonTypeException(String message, Element element) {
      super(message);
      this.element = element;
    }
  }
}
