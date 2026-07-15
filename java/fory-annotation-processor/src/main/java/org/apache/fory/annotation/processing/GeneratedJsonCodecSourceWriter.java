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
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.Filer;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.JavaFileObject;

/** Writes the direct JSON object operations owned by one {@code JsonType} model. */
final class GeneratedJsonCodecSourceWriter {
  private static final String JSON_PACKAGE = "org.apache.fory.json";
  private static final String JSON_CODEC = JSON_PACKAGE + ".annotation.JsonCodec";
  private static final String JSON_CREATOR = JSON_PACKAGE + ".annotation.JsonCreator";
  private static final String JSON_PROPERTY = JSON_PACKAGE + ".annotation.JsonProperty";
  private static final String JSON_UNWRAPPED = JSON_PACKAGE + ".annotation.JsonUnwrapped";
  private static final String JSON_VALUE = JSON_PACKAGE + ".annotation.JsonValue";
  private static final String JSON_ANY_GETTER = JSON_PACKAGE + ".annotation.JsonAnyGetter";
  private static final String JSON_ANY_SETTER = JSON_PACKAGE + ".annotation.JsonAnySetter";
  private static final String JSON_SUB_TYPES = JSON_PACKAGE + ".annotation.JsonSubTypes";
  private static final String NO_JSON_VALUE_CODEC = JSON_CODEC + ".NoJsonValueCodec";
  private static final String SUFFIX = "_ForyJsonCodec";
  private static final List<String> ASSIGNABLE_OBJECT_CODEC_EXCLUSIONS =
      Arrays.asList(
          "java.lang.Number",
          "java.lang.CharSequence",
          "java.net.InetAddress",
          "java.net.InetSocketAddress",
          "java.net.URL",
          "java.util.Collection",
          "java.util.Map",
          "java.util.Calendar",
          "java.util.Date",
          "java.time.ZoneId",
          "java.nio.ByteBuffer",
          "java.io.File",
          "java.nio.file.Path");
  private static final List<String> EXACT_OBJECT_CODEC_EXCLUSIONS =
      Arrays.asList(
          "java.lang.Class",
          "java.util.Optional",
          "java.util.concurrent.atomic.AtomicReference",
          "java.util.concurrent.atomic.AtomicReferenceArray");

  private final Filer filer;
  private final Elements elements;
  private final Types types;

  GeneratedJsonCodecSourceWriter(ProcessingEnvironment environment) {
    filer = environment.getFiler();
    elements = environment.getElementUtils();
    types = environment.getTypeUtils();
  }

  Result write(TypeElement target) {
    if (!needsGeneratedCodec(target)) {
      return null;
    }
    Model model = buildModel(target);
    try {
      JavaFileObject file = filer.createSourceFile(model.qualifiedName, target);
      try (Writer writer = file.openWriter()) {
        writer.write(render(model));
      }
    } catch (IOException e) {
      throw invalid(
          "Failed to write generated JSON codec " + model.qualifiedName + ": " + e, target);
    }
    return new Result(
        model.binaryName,
        Collections.unmodifiableList(new ArrayList<>(model.r8Members)),
        model.anySetter != null,
        model.creator != null,
        model.creator != null && model.creator.factory,
        model.creator != null && model.creator.record);
  }

  private boolean needsGeneratedCodec(TypeElement target) {
    boolean record = isRecord(target);
    if (!(target.getKind() == ElementKind.CLASS || record)
        || target.getModifiers().contains(Modifier.ABSTRACT)
        || annotationMirror(target, JSON_SUB_TYPES) != null) {
      return false;
    }
    if (hasCompleteTypeCodec(target)
        || !record && hasEffectiveJsonValue(target)
        || isObjectCodecExcluded(target)) {
      return false;
    }
    if (!isNameable(target)) {
      throw invalid("@JsonType model is not accessible to generated JSON code", target);
    }
    return true;
  }

  private boolean hasEffectiveJsonValue(TypeElement target) {
    for (TypeElement owner : classHierarchy(target)) {
      for (VariableElement field : ElementFilter.fieldsIn(owner.getEnclosedElements())) {
        if (annotationMirror(field, JSON_VALUE) != null) {
          return true;
        }
      }
    }
    for (ExecutableElement method : effectiveMethods(target)) {
      if (method.getModifiers().contains(Modifier.PUBLIC)
          && annotationMirror(method, JSON_VALUE) != null) {
        return true;
      }
    }
    return false;
  }

  private boolean hasCompleteTypeCodec(TypeElement target) {
    AnnotationMirror direct = annotationMirror(target, JSON_CODEC);
    if (direct != null) {
      return selectsValueCodec(direct);
    }
    List<TypeElement> declarations = allDeclarations(target);
    List<TypeElement> candidates = new ArrayList<>();
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
      if (!dominated && selectsValueCodec(annotationMirror(candidate, JSON_CODEC))) {
        return true;
      }
    }
    return false;
  }

  private boolean selectsValueCodec(AnnotationMirror annotation) {
    AnnotationValue value = annotationValue(annotation, "value");
    if (value == null || !(value.getValue() instanceof TypeMirror)) {
      return false;
    }
    TypeElement codec = asTypeElement((TypeMirror) value.getValue());
    return codec != null && !codec.getQualifiedName().contentEquals(NO_JSON_VALUE_CODEC);
  }

  private boolean isObjectCodecExcluded(TypeElement target) {
    if (target.getKind() == ElementKind.ENUM) {
      return true;
    }
    TypeMirror erased = types.erasure(target.asType());
    for (String name : ASSIGNABLE_OBJECT_CODEC_EXCLUSIONS) {
      TypeElement excluded = elements.getTypeElement(name);
      if (excluded != null && types.isAssignable(erased, types.erasure(excluded.asType()))) {
        return true;
      }
    }
    for (String name : EXACT_OBJECT_CODEC_EXCLUSIONS) {
      TypeElement excluded = elements.getTypeElement(name);
      if (excluded != null && types.isSameType(erased, types.erasure(excluded.asType()))) {
        return true;
      }
    }
    return false;
  }

  private Model buildModel(TypeElement target) {
    String packageName = elements.getPackageOf(target).getQualifiedName().toString();
    String targetBinaryName = elements.getBinaryName(target).toString();
    String binarySimpleName =
        targetBinaryName.substring(packageName.isEmpty() ? 0 : packageName.length() + 1);
    String simpleName = GeneratedTypeNames.escapeBinarySimpleName(binarySimpleName) + SUFFIX;
    Model model =
        new Model(
            target,
            packageName,
            targetBinaryName,
            simpleName,
            packageName.isEmpty() ? simpleName : packageName + "." + simpleName,
            packageName.isEmpty() ? simpleName : packageName + "." + simpleName,
            sourceType(target.asType()));
    collectFieldAccessors(model);
    collectMethodAccessors(model);
    model.anySetter = findAnySetter(model);
    if (model.anySetter != null) {
      model.addR8Member(
          model.anySetter.ownerBinaryName,
          "void "
              + model.anySetter.methodName
              + "(java.lang.String,"
              + model.anySetter.memberValueBinaryType
              + ");");
    }
    model.creator = isRecord(target) ? recordCreator(model) : explicitCreator(model);
    if (model.creator != null) {
      model.addR8Member(model.targetBinaryName, model.creator.r8Declaration);
    }
    return model;
  }

  private void collectFieldAccessors(Model model) {
    List<TypeElement> hierarchy = classHierarchy(model.target);
    Collections.reverse(hierarchy);
    for (TypeElement owner : hierarchy) {
      for (VariableElement field : ElementFilter.fieldsIn(owner.getEnclosedElements())) {
        Set<Modifier> modifiers = field.getModifiers();
        if (field.getKind() == ElementKind.ENUM_CONSTANT
            || modifiers.contains(Modifier.STATIC)
            || modifiers.contains(Modifier.TRANSIENT)
            || binaryType(field.asType()).equals("java.lang.Class")
            || !isAccessible(field, model.packageName)
            || !isNameable(owner.asType(), model.packageName)
            || !isNameable(types.erasure(field.asType()), model.packageName)) {
          continue;
        }
        TypeMirror resolved = types.asMemberOf((DeclaredType) model.target.asType(), field);
        model.accessors.add(
            Accessor.forField(
                model.accessors.size(),
                sourceType(owner.asType()),
                field.getSimpleName().toString(),
                sourceType(types.erasure(resolved)),
                sourceType(types.erasure(field.asType())),
                resolved.getKind(),
                !modifiers.contains(Modifier.FINAL)));
        model.addR8Member(
            elements.getBinaryName(owner).toString(),
            binaryType(field.asType()) + " " + field.getSimpleName() + ";");
      }
    }
  }

  private void collectMethodAccessors(Model model) {
    Set<String> seen = new HashSet<>();
    for (ExecutableElement method : effectiveMethods(model.target)) {
      if (!method.getModifiers().contains(Modifier.PUBLIC)
          || method.getModifiers().contains(Modifier.STATIC)
          || annotationMirror(method, JSON_ANY_SETTER) != null
          || method.isVarArgs()
          || !method.getTypeParameters().isEmpty()) {
        continue;
      }
      TypeElement owner = (TypeElement) method.getEnclosingElement();
      if (!isNameable(owner.asType(), model.packageName)) {
        continue;
      }
      ExecutableType resolved =
          (ExecutableType) types.asMemberOf((DeclaredType) model.target.asType(), method);
      String key = methodKey(owner, method);
      if (isGetter(method, resolved)
          && isNameable(types.erasure(resolved.getReturnType()), model.packageName)
          && seen.add(key)) {
        model.accessors.add(
            Accessor.forGetter(
                model.accessors.size(),
                sourceType(owner.asType()),
                method.getSimpleName().toString(),
                sourceType(types.erasure(resolved.getReturnType())),
                resolved.getReturnType().getKind()));
        model.addR8Member(
            elements.getBinaryName(owner).toString(),
            binaryType(method.getReturnType()) + " " + method.getSimpleName() + "();");
      } else if (isSetter(method, resolved)
          && isNameable(types.erasure(resolved.getParameterTypes().get(0)), model.packageName)
          && isNameable(types.erasure(method.getParameters().get(0).asType()), model.packageName)
          && seen.add(key)) {
        model.accessors.add(
            Accessor.forSetter(
                model.accessors.size(),
                sourceType(owner.asType()),
                method.getSimpleName().toString(),
                sourceType(types.erasure(resolved.getParameterTypes().get(0))),
                sourceType(types.erasure(method.getParameters().get(0).asType())),
                resolved.getParameterTypes().get(0).getKind()));
        model.addR8Member(
            elements.getBinaryName(owner).toString(),
            "void "
                + method.getSimpleName()
                + "("
                + binaryType(method.getParameters().get(0).asType())
                + ");");
      }
    }
    if (isRecord(model.target)) {
      for (Element component : recordComponents(model.target)) {
        ExecutableElement accessor = findRecordAccessor(model.target, component);
        if (accessor == null || !seen.add(methodKey(model.target, accessor))) {
          continue;
        }
        TypeMirror type = types.erasure(component.asType());
        if (!isNameable(type, model.packageName)) {
          throw invalid(
              "Record component type is not accessible to generated JSON code", component);
        }
        model.accessors.add(
            Accessor.forGetter(
                model.accessors.size(),
                model.targetType,
                accessor.getSimpleName().toString(),
                sourceType(type),
                type.getKind()));
        model.addR8Member(
            model.targetBinaryName,
            binaryType(accessor.getReturnType()) + " " + accessor.getSimpleName() + "();");
      }
    }
  }

  private AnySetter findAnySetter(Model model) {
    ExecutableElement selected = null;
    for (ExecutableElement method : effectiveMethods(model.target)) {
      if (annotationMirror(method, JSON_ANY_SETTER) == null) {
        continue;
      }
      if (selected != null) {
        throw invalid("At most one effective @JsonAnySetter method is allowed", method);
      }
      selected = method;
    }
    if (selected == null) {
      return null;
    }
    TypeElement owner = (TypeElement) selected.getEnclosingElement();
    ExecutableType resolved =
        (ExecutableType) types.asMemberOf((DeclaredType) model.target.asType(), selected);
    if (!selected.getModifiers().contains(Modifier.PUBLIC)
        || selected.getModifiers().contains(Modifier.STATIC)
        || selected.isVarArgs()
        || !selected.getTypeParameters().isEmpty()
        || resolved.getReturnType().getKind() != TypeKind.VOID
        || resolved.getParameterTypes().size() != 2
        || !binaryType(resolved.getParameterTypes().get(0)).equals("java.lang.String")
        || !binaryType(selected.getParameters().get(0).asType()).equals("java.lang.String")
        || !isNameable(owner.asType(), model.packageName)
        || !isNameable(types.erasure(resolved.getParameterTypes().get(1)), model.packageName)
        || !isNameable(
            types.erasure(selected.getParameters().get(1).asType()), model.packageName)) {
      // Runtime metadata validation owns the diagnostic for malformed annotations.
      return null;
    }
    return new AnySetter(
        sourceType(owner.asType()),
        elements.getBinaryName(owner).toString(),
        selected.getSimpleName().toString(),
        sourceType(types.erasure(selected.getParameters().get(1).asType())),
        binaryType(selected.getParameters().get(1).asType()),
        resolved.getParameterTypes().get(1).getKind());
  }

  private Creator recordCreator(Model model) {
    List<? extends Element> components = recordComponents(model.target);
    List<String> names = new ArrayList<>();
    List<String> sourceTypes = new ArrayList<>();
    List<String> binaryTypes = new ArrayList<>();
    List<TypeKind> kinds = new ArrayList<>();
    for (Element component : components) {
      TypeMirror type = types.erasure(component.asType());
      if (!isNameable(type, model.packageName)) {
        throw invalid("Record component type is not accessible to generated JSON code", component);
      }
      names.add(component.getSimpleName().toString());
      sourceTypes.add(sourceType(type));
      binaryTypes.add(binaryType(type));
      kinds.add(type.getKind());
    }
    for (ExecutableElement method : ElementFilter.methodsIn(model.target.getEnclosedElements())) {
      if (annotationMirror(method, JSON_CREATOR) != null) {
        throw invalid("Records cannot declare @JsonCreator", method);
      }
    }
    boolean valueRecord = hasEffectiveJsonValue(model.target);
    ExecutableElement canonicalConstructor = null;
    for (ExecutableElement constructor :
        ElementFilter.constructorsIn(model.target.getEnclosedElements())) {
      if (annotationMirror(constructor, JSON_CREATOR) != null
          && (!valueRecord || !isRecordValueCreator(constructor, components))) {
        throw invalid("Records cannot declare @JsonCreator", constructor);
      }
      if (isRecordConstructor(constructor, components)) {
        canonicalConstructor = constructor;
      } else {
        rejectRecordParameters(constructor);
      }
    }
    if (canonicalConstructor == null) {
      throw invalid("Cannot find the canonical Record constructor", model.target);
    }
    validateRecordParameters(model.target, components, canonicalConstructor);
    String declaration = "<init>(" + join(binaryTypes) + ");";
    return new Creator(names, sourceTypes, kinds, null, true, declaration, false);
  }

  private boolean isRecordConstructor(
      ExecutableElement constructor, List<? extends Element> components) {
    List<? extends VariableElement> parameters = constructor.getParameters();
    if (parameters.size() != components.size()) {
      return false;
    }
    for (int i = 0; i < parameters.size(); i++) {
      if (!types.isSameType(
          types.erasure(parameters.get(i).asType()), types.erasure(components.get(i).asType()))) {
        return false;
      }
    }
    return true;
  }

  private void rejectRecordParameters(ExecutableElement constructor) {
    for (VariableElement parameter : constructor.getParameters()) {
      if (annotationMirror(parameter, JSON_CODEC) != null
          || annotationMirror(parameter, JSON_PROPERTY) != null
          || annotationMirror(parameter, JSON_UNWRAPPED) != null) {
        throw invalid(
            "JSON property annotations are not supported on non-canonical Record constructor parameters",
            parameter);
      }
    }
  }

  private void validateRecordParameters(
      TypeElement target, List<? extends Element> components, ExecutableElement constructor) {
    List<? extends VariableElement> parameters = constructor.getParameters();
    for (int i = 0; i < components.size(); i++) {
      Element component = components.get(i);
      VariableElement field = findRecordField(target, component);
      ExecutableElement accessor = findRecordAccessor(target, component);
      validateRecordAnnotation(parameters.get(i), field, accessor, JSON_CODEC);
      validateRecordAnnotation(parameters.get(i), field, accessor, JSON_PROPERTY);
      validateRecordAnnotation(parameters.get(i), field, accessor, JSON_UNWRAPPED);
    }
  }

  private void validateRecordAnnotation(
      VariableElement parameter,
      VariableElement field,
      ExecutableElement accessor,
      String annotationName) {
    AnnotationMirror parameterAnnotation = annotationMirror(parameter, annotationName);
    if (parameterAnnotation == null) {
      return;
    }
    AnnotationMirror fieldAnnotation =
        field == null ? null : annotationMirror(field, annotationName);
    AnnotationMirror accessorAnnotation =
        accessor == null ? null : annotationMirror(accessor, annotationName);
    if (sameAnnotation(parameterAnnotation, fieldAnnotation)
        || sameAnnotation(parameterAnnotation, accessorAnnotation)) {
      return;
    }
    throw invalid(
        "Canonical Record constructor parameter @"
            + annotationName.substring(annotationName.lastIndexOf('.') + 1)
            + " must match the corresponding Record field or accessor",
        parameter);
  }

  private boolean sameAnnotation(AnnotationMirror left, AnnotationMirror right) {
    if (right == null || !types.isSameType(left.getAnnotationType(), right.getAnnotationType())) {
      return false;
    }
    for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry :
        elements.getElementValuesWithDefaults(left).entrySet()) {
      AnnotationValue value = annotationValue(right, entry.getKey().getSimpleName().toString());
      if (value == null || !entry.getValue().toString().equals(value.toString())) {
        return false;
      }
    }
    return true;
  }

  private boolean isRecordValueCreator(
      ExecutableElement constructor, List<? extends Element> components) {
    if (!constructor.getModifiers().contains(Modifier.PUBLIC)
        || constructor.isVarArgs()
        || !constructor.getTypeParameters().isEmpty()
        || components.size() != 1
        || constructor.getParameters().size() != 1
        || !binaryType(components.get(0).asType()).equals("java.lang.String")
        || !types.isSameType(
            types.erasure(constructor.getParameters().get(0).asType()),
            types.erasure(components.get(0).asType()))
        || annotationMirror(constructor.getParameters().get(0), JSON_PROPERTY) != null) {
      return false;
    }
    AnnotationMirror creator = annotationMirror(constructor, JSON_CREATOR);
    return stringArray(annotationValue(creator, "value")).isEmpty();
  }

  private Creator explicitCreator(Model model) {
    ExecutableElement creator = null;
    boolean factory = false;
    for (ExecutableElement constructor :
        ElementFilter.constructorsIn(model.target.getEnclosedElements())) {
      if (annotationMirror(constructor, JSON_CREATOR) != null) {
        if (creator != null) {
          throw invalid("Exactly one @JsonCreator constructor or factory is allowed", constructor);
        }
        creator = constructor;
      }
    }
    for (ExecutableElement method : ElementFilter.methodsIn(model.target.getEnclosedElements())) {
      if (annotationMirror(method, JSON_CREATOR) != null) {
        if (creator != null) {
          throw invalid("Exactly one @JsonCreator constructor or factory is allowed", method);
        }
        creator = method;
        factory = true;
      }
    }
    if (creator == null) {
      return null;
    }
    validateCreator(model, creator, factory);
    List<String> names = creatorParameterNames(creator);
    List<String> sourceTypes = new ArrayList<>();
    List<String> binaryTypes = new ArrayList<>();
    List<TypeKind> kinds = new ArrayList<>();
    for (VariableElement parameter : creator.getParameters()) {
      TypeMirror type = types.erasure(parameter.asType());
      if (!isNameable(type, model.packageName)) {
        throw invalid("Creator parameter type is not accessible to generated JSON code", parameter);
      }
      sourceTypes.add(sourceType(type));
      binaryTypes.add(binaryType(type));
      kinds.add(type.getKind());
    }
    String executableName = factory ? creator.getSimpleName().toString() : null;
    String declaration =
        factory
            ? binaryType(creator.getReturnType())
                + " "
                + executableName
                + "("
                + join(binaryTypes)
                + ");"
            : "<init>(" + join(binaryTypes) + ");";
    return new Creator(names, sourceTypes, kinds, executableName, false, declaration, factory);
  }

  private void validateCreator(Model model, ExecutableElement creator, boolean factory) {
    Set<Modifier> modifiers = creator.getModifiers();
    if (!modifiers.contains(Modifier.PUBLIC)
        || creator.isVarArgs()
        || !creator.getTypeParameters().isEmpty()
        || creator.getParameters().isEmpty()) {
      throw invalid(
          "@JsonCreator must be public, non-generic, non-varargs, and have parameters", creator);
    }
    if (!factory
        && model.target.getNestingKind() == NestingKind.MEMBER
        && !model.target.getModifiers().contains(Modifier.STATIC)) {
      throw invalid("A non-static member class must use a static @JsonCreator factory", creator);
    }
    if (factory
        && (!modifiers.contains(Modifier.STATIC)
            || !types.isSameType(
                types.erasure(creator.getReturnType()), types.erasure(model.target.asType())))) {
      throw invalid("@JsonCreator factory must be static and return the exact model type", creator);
    }
  }

  private List<String> creatorParameterNames(ExecutableElement creator) {
    AnnotationMirror annotation = annotationMirror(creator, JSON_CREATOR);
    AnnotationValue value = annotationValue(annotation, "value");
    List<String> names = stringArray(value);
    if (!names.isEmpty()) {
      if (names.size() != creator.getParameters().size()) {
        throw invalid("@JsonCreator property count must match its parameter count", creator);
      }
      Set<String> unique = new HashSet<>();
      for (int i = 0; i < names.size(); i++) {
        VariableElement parameter = creator.getParameters().get(i);
        if (annotationMirror(parameter, JSON_PROPERTY) != null) {
          throw invalid(
              "Property-list @JsonCreator parameters cannot declare @JsonProperty", parameter);
        }
        String name = names.get(i);
        if (name.isEmpty() || !unique.add(name)) {
          throw invalid("@JsonCreator property names must be non-empty and unique", creator);
        }
      }
      return names;
    }
    names = new ArrayList<>();
    Set<String> unique = new HashSet<>();
    for (VariableElement parameter : creator.getParameters()) {
      AnnotationMirror property = annotationMirror(parameter, JSON_PROPERTY);
      AnnotationValue propertyName = property == null ? null : annotationValue(property, "value");
      String name = propertyName == null ? "" : String.valueOf(propertyName.getValue());
      if (name.isEmpty() || !unique.add(name)) {
        throw invalid(
            "Every parameter-local @JsonCreator parameter needs a unique @JsonProperty name",
            parameter);
      }
      names.add(name);
    }
    return names;
  }

  private String render(Model model) {
    StringBuilder source = new StringBuilder(16384);
    source.append("// Generated by Apache Fory. Do not edit.\n");
    if (!model.packageName.isEmpty()) {
      source.append("package ").append(model.packageName).append(";\n\n");
    }
    source
        .append("public final class ")
        .append(model.simpleName)
        .append(" extends org.apache.fory.json.codec.GeneratedJsonCodec<")
        .append(model.targetType)
        .append("> {\n")
        .append("  public ")
        .append(model.simpleName)
        .append("() {}\n\n")
        .append("  @Override\n")
        .append("  public Class<")
        .append(model.targetType)
        .append("> type() {\n")
        .append("    return ")
        .append(model.targetType)
        .append(".class;\n")
        .append("  }\n\n")
        .append("  @Override\n")
        .append("  public org.apache.fory.json.meta.JsonFieldAccessor[] fieldAccessors() {\n")
        .append("    return new org.apache.fory.json.meta.JsonFieldAccessor[] {");
    for (int i = 0; i < model.accessors.size(); i++) {
      if (i != 0) {
        source.append(", ");
      }
      source.append("new Accessor").append(i).append("()");
    }
    source.append("};\n  }\n\n");
    if (model.anySetter != null) {
      source
          .append("  @Override\n")
          .append(
              "  public org.apache.fory.json.meta.JsonAnySetterAccessor anySetterAccessor() {\n")
          .append("    return new AnySetterAccessor();\n")
          .append("  }\n\n");
    }
    if (model.creator != null) {
      renderCreator(source, model);
    }
    source
        .append(
            "  private static java.lang.reflect.Field declaredField(Class<?> owner, String name) {\n")
        .append("    try {\n")
        .append("      return owner.getDeclaredField(name);\n")
        .append("    } catch (Exception e) {\n")
        .append("      throw new ExceptionInInitializerError(e);\n")
        .append("    }\n")
        .append("  }\n\n")
        .append("  private static java.lang.reflect.Method declaredMethod(\n")
        .append("      Class<?> owner, String name, Class<?>... parameterTypes) {\n")
        .append("    try {\n")
        .append("      return owner.getDeclaredMethod(name, parameterTypes);\n")
        .append("    } catch (Exception e) {\n")
        .append("      throw new ExceptionInInitializerError(e);\n")
        .append("    }\n")
        .append("  }\n\n");
    for (Accessor accessor : model.accessors) {
      renderAccessor(source, model, accessor);
    }
    if (model.anySetter != null) {
      renderAnySetter(source, model.anySetter);
    }
    source
        .append("  public static final class Factory\n")
        .append("      implements org.apache.fory.json.codec.GeneratedJsonCodecFactory {\n")
        .append("    public Factory() {}\n\n")
        .append("    @Override\n")
        .append("    public org.apache.fory.json.codec.GeneratedJsonCodec<?> create() {\n")
        .append("      return new ")
        .append(model.simpleName)
        .append("();\n")
        .append("    }\n")
        .append("  }\n")
        .append("}\n");
    return source.toString();
  }

  private void renderCreator(StringBuilder source, Model model) {
    Creator creator = model.creator;
    source
        .append("  @Override\n")
        .append("  public String[] creatorParameterNames() {\n")
        .append("    return new String[] {");
    appendStrings(source, creator.names);
    source
        .append("};\n  }\n\n")
        .append("  @Override\n")
        .append("  public Class<?>[] creatorParameterTypes() {\n")
        .append("    return new Class<?>[] {");
    appendClassLiterals(source, creator.sourceTypes);
    source.append("};\n  }\n\n");
    if (creator.factoryName != null) {
      source
          .append("  @Override\n")
          .append("  public String creatorFactoryName() {\n")
          .append("    return \"")
          .append(escapeJava(creator.factoryName))
          .append("\";\n")
          .append("  }\n\n");
    }
    if (creator.record) {
      source
          .append("  @Override\n")
          .append("  public boolean isRecord() {\n")
          .append("    return true;\n")
          .append("  }\n\n");
    }
    source
        .append("  @Override\n")
        .append("  public ")
        .append(model.targetType)
        .append(" newInstance(Object[] arguments) {\n")
        .append("    try {\n")
        .append("      return ");
    if (creator.factory) {
      source.append(model.targetType).append('.').append(creator.factoryName).append('(');
    } else {
      source.append("new ").append(model.targetType).append('(');
    }
    for (int i = 0; i < creator.sourceTypes.size(); i++) {
      if (i != 0) {
        source.append(", ");
      }
      source.append(argumentExpression(creator.sourceTypes.get(i), creator.kinds.get(i), i));
    }
    source
        .append(");\n")
        .append("    } catch (Throwable throwable) {\n")
        .append("      throw creatorFailure(throwable);\n")
        .append("    }\n")
        .append("  }\n\n");
  }

  private void renderAccessor(StringBuilder source, Model model, Accessor accessor) {
    // The direct expression must be compiled against the same declaring class and erased value
    // type as MEMBER. Using the model subtype or a resolved generic type can select a hidden field
    // or a more-specific overload. A declaring-class method receiver still dispatches virtually,
    // matching Method.invoke and MethodHandle invocation for real overrides.
    source
        .append("  private static final class Accessor")
        .append(accessor.index)
        .append(" extends org.apache.fory.json.meta.JsonFieldAccessor {\n");
    if (accessor.kind == AccessorKind.FIELD) {
      source
          .append("    private static final java.lang.reflect.Field MEMBER =\n")
          .append("        declaredField(")
          .append(accessor.ownerType)
          .append(".class, \"")
          .append(escapeJava(accessor.memberName))
          .append("\");\n\n")
          .append("    @Override\n")
          .append("    public java.lang.reflect.Field field() {\n")
          .append("      return MEMBER;\n")
          .append("    }\n\n");
    } else {
      source
          .append("    private static final java.lang.reflect.Method MEMBER =\n")
          .append("        declaredMethod(")
          .append(accessor.ownerType)
          .append(".class, \"")
          .append(escapeJava(accessor.memberName))
          .append("\"");
      if (accessor.kind == AccessorKind.SETTER) {
        source.append(", ").append(accessor.declaredValueType).append(".class");
      }
      source.append(");\n\n");
      source
          .append("    @Override\n")
          .append("    public java.lang.reflect.Method ")
          .append(accessor.kind == AccessorKind.GETTER ? "getter" : "setter")
          .append("() {\n")
          .append("      return MEMBER;\n")
          .append("    }\n\n");
    }
    if (accessor.readable()) {
      renderGet(source, accessor);
    }
    if (accessor.writable()) {
      renderPut(source, accessor);
    }
    source.append("  }\n\n");
  }

  private void renderGet(StringBuilder source, Accessor accessor) {
    String suffix = primitiveSuffix(accessor.valueKind);
    String method = suffix == null ? "getObject" : "get" + suffix;
    source
        .append("    @Override\n")
        .append("    public ")
        .append(suffix == null ? "Object" : accessor.valueType)
        .append(' ')
        .append(method)
        .append("(Object target) {\n");
    if (accessor.kind == AccessorKind.FIELD) {
      source.append("      return ");
      appendReadCast(source, accessor);
      source
          .append("((")
          .append(accessor.ownerType)
          .append(") target).")
          .append(accessor.memberName)
          .append(";\n");
    } else {
      source.append("      try {\n").append("        return ");
      appendReadCast(source, accessor);
      source
          .append("((")
          .append(accessor.ownerType)
          .append(") target).")
          .append(accessor.memberName)
          .append("();\n")
          .append("      } catch (Throwable throwable) {\n")
          .append("        throw accessException(MEMBER, throwable);\n")
          .append("      }\n");
    }
    source.append("    }\n\n");
    if (suffix != null) {
      source
          .append("    @Override\n")
          .append("    public Object getObject(Object target) {\n")
          .append("      return ")
          .append(method)
          .append("(target);\n")
          .append("    }\n\n");
    }
  }

  private void appendReadCast(StringBuilder source, Accessor accessor) {
    if (!accessor.valueKind.isPrimitive()) {
      source.append('(').append(accessor.valueType).append(") ");
    }
  }

  private void renderPut(StringBuilder source, Accessor accessor) {
    String suffix = primitiveSuffix(accessor.valueKind);
    String method = suffix == null ? "putObject" : "put" + suffix;
    source
        .append("    @Override\n")
        .append("    public void ")
        .append(method)
        .append("(Object target, ")
        .append(suffix == null ? "Object" : accessor.valueType)
        .append(" value) {\n");
    String value = suffix == null ? "(" + accessor.declaredValueType + ") value" : "value";
    if (accessor.kind == AccessorKind.FIELD) {
      source
          .append("      ((")
          .append(accessor.ownerType)
          .append(") target).")
          .append(accessor.memberName)
          .append(" = ")
          .append(value)
          .append(";\n");
    } else {
      source
          .append("      try {\n")
          .append("        ((")
          .append(accessor.ownerType)
          .append(") target).")
          .append(accessor.memberName)
          .append('(')
          .append(value)
          .append(");\n")
          .append("      } catch (Throwable throwable) {\n")
          .append("        throw accessException(MEMBER, throwable);\n")
          .append("      }\n");
    }
    source.append("    }\n\n");
    if (suffix != null) {
      source
          .append("    @Override\n")
          .append("    public void putObject(Object target, Object value) {\n")
          .append("      ")
          .append(method)
          .append("(target, ")
          .append(unboxExpression(accessor.valueType, "value"))
          .append(");\n")
          .append("    }\n\n");
    }
  }

  private void renderAnySetter(StringBuilder source, AnySetter setter) {
    source
        .append("  private static final class AnySetterAccessor\n")
        .append("      extends org.apache.fory.json.meta.JsonAnySetterAccessor {\n")
        .append("    private static final java.lang.reflect.Method MEMBER =\n")
        .append("        declaredMethod(")
        .append(setter.ownerType)
        .append(".class, \"")
        .append(escapeJava(setter.methodName))
        .append("\", String.class, ")
        .append(setter.memberValueType)
        .append(".class);\n\n")
        .append("    @Override\n")
        .append("    public java.lang.reflect.Method setter() {\n")
        .append("      return MEMBER;\n")
        .append("    }\n\n")
        .append("    @Override\n")
        .append("    public void put(Object target, String name, Object value) {\n")
        .append("      try {\n")
        .append("        ((")
        .append(setter.ownerType)
        .append(") target).")
        .append(setter.methodName)
        .append("(name, ")
        .append(
            setter.valueKind.isPrimitive()
                ? unboxExpression(setter.memberValueType, "value")
                : "(" + setter.memberValueType + ") value")
        .append(");\n")
        .append("      } catch (Throwable throwable) {\n")
        .append("        throw accessException(MEMBER, throwable);\n")
        .append("      }\n")
        .append("    }\n")
        .append("  }\n\n");
  }

  private List<ExecutableElement> effectiveMethods(TypeElement target) {
    Map<String, ExecutableElement> methods = new LinkedHashMap<>();
    for (ExecutableElement method : ElementFilter.methodsIn(elements.getAllMembers(target))) {
      TypeElement owner = (TypeElement) method.getEnclosingElement();
      methods.put(methodKey(owner, method), method);
    }
    List<ExecutableElement> result = new ArrayList<>(methods.values());
    result.sort(
        Comparator.comparing(
            method -> methodKey((TypeElement) method.getEnclosingElement(), method)));
    return result;
  }

  private boolean isGetter(ExecutableElement method, ExecutableType resolved) {
    if (!resolved.getParameterTypes().isEmpty()
        || resolved.getReturnType().getKind() == TypeKind.VOID
        || binaryType(resolved.getReturnType()).equals("java.lang.Class")) {
      return false;
    }
    String name = method.getSimpleName().toString();
    return annotationMirror(method, JSON_PROPERTY) != null
        || annotationMirror(method, JSON_ANY_GETTER) != null
        || name.startsWith("get") && name.length() > 3
        || name.startsWith("is")
            && name.length() > 2
            && (resolved.getReturnType().getKind() == TypeKind.BOOLEAN
                || binaryType(resolved.getReturnType()).equals("java.lang.Boolean"));
  }

  private boolean isSetter(ExecutableElement method, ExecutableType resolved) {
    if (resolved.getParameterTypes().size() != 1
        || resolved.getReturnType().getKind() != TypeKind.VOID
        || binaryType(resolved.getParameterTypes().get(0)).equals("java.lang.Class")) {
      return false;
    }
    String name = method.getSimpleName().toString();
    return annotationMirror(method, JSON_PROPERTY) != null
        || name.startsWith("set") && name.length() > 3;
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

  private List<TypeElement> allDeclarations(TypeElement target) {
    LinkedHashMap<String, TypeElement> owners = new LinkedHashMap<>();
    Deque<TypeElement> pending = new ArrayDeque<>();
    pending.add(target);
    while (!pending.isEmpty()) {
      TypeElement type = pending.removeFirst();
      if (owners.put(elements.getBinaryName(type).toString(), type) != null) {
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

  @SuppressWarnings("unchecked")
  private List<? extends Element> recordComponents(TypeElement target) {
    try {
      Method method = TypeElement.class.getMethod("getRecordComponents");
      return (List<? extends Element>) method.invoke(target);
    } catch (ReflectiveOperationException e) {
      throw invalid("Cannot inspect Record components: " + e, target);
    }
  }

  private ExecutableElement findRecordAccessor(TypeElement target, Element component) {
    for (ExecutableElement method : ElementFilter.methodsIn(target.getEnclosedElements())) {
      if (method.getSimpleName().contentEquals(component.getSimpleName())
          && method.getParameters().isEmpty()) {
        return method;
      }
    }
    return null;
  }

  private VariableElement findRecordField(TypeElement target, Element component) {
    for (VariableElement field : ElementFilter.fieldsIn(target.getEnclosedElements())) {
      if (field.getSimpleName().contentEquals(component.getSimpleName())) {
        return field;
      }
    }
    return null;
  }

  private boolean isRecord(TypeElement type) {
    return type.getKind().name().equals("RECORD");
  }

  private boolean isAccessible(Element element, String generatedPackage) {
    Set<Modifier> modifiers = element.getModifiers();
    if (modifiers.contains(Modifier.PUBLIC)) {
      return true;
    }
    if (modifiers.contains(Modifier.PRIVATE)) {
      return false;
    }
    TypeElement owner = (TypeElement) element.getEnclosingElement();
    return elements.getPackageOf(owner).getQualifiedName().contentEquals(generatedPackage);
  }

  private boolean isNameable(TypeMirror type, String generatedPackage) {
    TypeMirror erased = types.erasure(type);
    if (erased.getKind().isPrimitive()) {
      return true;
    }
    if (erased.getKind() == TypeKind.ARRAY) {
      return isNameable(((ArrayType) erased).getComponentType(), generatedPackage);
    }
    TypeElement element = asTypeElement(erased);
    if (element == null || !isNameable(element)) {
      return false;
    }
    if (elements.getPackageOf(element).getQualifiedName().contentEquals(generatedPackage)) {
      return true;
    }
    Element current = element;
    while (current instanceof TypeElement) {
      if (!current.getModifiers().contains(Modifier.PUBLIC)) {
        return false;
      }
      current = current.getEnclosingElement();
    }
    return true;
  }

  private boolean isNameable(TypeElement type) {
    NestingKind nesting = type.getNestingKind();
    if (nesting != NestingKind.TOP_LEVEL && nesting != NestingKind.MEMBER) {
      return false;
    }
    Element current = type;
    while (current instanceof TypeElement) {
      if (current.getModifiers().contains(Modifier.PRIVATE)) {
        return false;
      }
      current = current.getEnclosingElement();
    }
    return true;
  }

  private String sourceType(TypeMirror type) {
    TypeMirror erased = types.erasure(type);
    if (erased.getKind() == TypeKind.ARRAY) {
      return sourceType(((ArrayType) erased).getComponentType()) + "[]";
    }
    if (erased.getKind().isPrimitive() || erased.getKind() == TypeKind.VOID) {
      return erased.toString();
    }
    TypeElement element = asTypeElement(erased);
    return element == null ? erased.toString() : element.getQualifiedName().toString();
  }

  private String binaryType(TypeMirror type) {
    TypeMirror erased = types.erasure(type);
    if (erased.getKind() == TypeKind.ARRAY) {
      return binaryType(((ArrayType) erased).getComponentType()) + "[]";
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

  private AnnotationValue annotationValue(AnnotationMirror annotation, String name) {
    for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry :
        elements.getElementValuesWithDefaults(annotation).entrySet()) {
      if (entry.getKey().getSimpleName().contentEquals(name)) {
        return entry.getValue();
      }
    }
    return null;
  }

  private List<String> stringArray(AnnotationValue value) {
    if (value == null || !(value.getValue() instanceof List<?>)) {
      return Collections.emptyList();
    }
    List<String> strings = new ArrayList<>();
    for (Object entry : (List<?>) value.getValue()) {
      if (entry instanceof AnnotationValue) {
        strings.add(String.valueOf(((AnnotationValue) entry).getValue()));
      }
    }
    return strings;
  }

  private String methodKey(TypeElement owner, ExecutableElement method) {
    StringBuilder key =
        new StringBuilder(elements.getBinaryName(owner).toString())
            .append('#')
            .append(method.getSimpleName())
            .append('(');
    for (VariableElement parameter : method.getParameters()) {
      key.append(binaryType(parameter.asType())).append(';');
    }
    return key.append(')').toString();
  }

  private static String primitiveSuffix(TypeKind kind) {
    switch (kind) {
      case BOOLEAN:
        return "Boolean";
      case BYTE:
        return "Byte";
      case SHORT:
        return "Short";
      case INT:
        return "Int";
      case LONG:
        return "Long";
      case CHAR:
        return "Char";
      case FLOAT:
        return "Float";
      case DOUBLE:
        return "Double";
      default:
        return null;
    }
  }

  private static String argumentExpression(String type, TypeKind kind, int index) {
    String value = "arguments[" + index + "]";
    return kind.isPrimitive() ? unboxExpression(type, value) : "(" + type + ") " + value;
  }

  private static String unboxExpression(String type, String value) {
    if (type.equals("boolean")) {
      return "((Boolean) " + value + ").booleanValue()";
    }
    if (type.equals("byte")) {
      return "((Byte) " + value + ").byteValue()";
    }
    if (type.equals("short")) {
      return "((Short) " + value + ").shortValue()";
    }
    if (type.equals("int")) {
      return "((Integer) " + value + ").intValue()";
    }
    if (type.equals("long")) {
      return "((Long) " + value + ").longValue()";
    }
    if (type.equals("char")) {
      return "((Character) " + value + ").charValue()";
    }
    if (type.equals("float")) {
      return "((Float) " + value + ").floatValue()";
    }
    if (type.equals("double")) {
      return "((Double) " + value + ").doubleValue()";
    }
    throw new IllegalArgumentException("Not a primitive type: " + type);
  }

  private static void appendStrings(StringBuilder builder, List<String> values) {
    for (int i = 0; i < values.size(); i++) {
      if (i != 0) {
        builder.append(", ");
      }
      builder.append('"').append(escapeJava(values.get(i))).append('"');
    }
  }

  private static void appendClassLiterals(StringBuilder builder, List<String> values) {
    for (int i = 0; i < values.size(); i++) {
      if (i != 0) {
        builder.append(", ");
      }
      builder.append(values.get(i)).append(".class");
    }
  }

  private static String escapeJava(String value) {
    StringBuilder builder = new StringBuilder(value.length());
    for (int i = 0; i < value.length(); i++) {
      char ch = value.charAt(i);
      switch (ch) {
        case '\b':
          builder.append("\\b");
          break;
        case '\t':
          builder.append("\\t");
          break;
        case '\n':
          builder.append("\\n");
          break;
        case '\f':
          builder.append("\\f");
          break;
        case '\r':
          builder.append("\\r");
          break;
        case '"':
          builder.append("\\\"");
          break;
        case '\\':
          builder.append("\\\\");
          break;
        default:
          if (Character.isISOControl(ch) || ch == '\u2028' || ch == '\u2029') {
            builder.append("\\u");
            String hex = Integer.toHexString(ch);
            for (int padding = hex.length(); padding < 4; padding++) {
              builder.append('0');
            }
            builder.append(hex);
          } else {
            builder.append(ch);
          }
      }
    }
    return builder.toString();
  }

  private static String join(List<String> values) {
    StringBuilder builder = new StringBuilder();
    for (int i = 0; i < values.size(); i++) {
      if (i != 0) {
        builder.append(',');
      }
      builder.append(values.get(i));
    }
    return builder.toString();
  }

  private InvalidJsonTypeException invalid(String message, Element element) {
    return new InvalidJsonTypeException(message, element);
  }

  static final class Result {
    final String companionBinaryName;
    final List<MemberRule> r8Members;
    final boolean hasAnySetter;
    final boolean hasCreator;
    final boolean hasCreatorFactory;
    final boolean record;

    Result(
        String companionBinaryName,
        List<MemberRule> r8Members,
        boolean hasAnySetter,
        boolean hasCreator,
        boolean hasCreatorFactory,
        boolean record) {
      this.companionBinaryName = companionBinaryName;
      this.r8Members = r8Members;
      this.hasAnySetter = hasAnySetter;
      this.hasCreator = hasCreator;
      this.hasCreatorFactory = hasCreatorFactory;
      this.record = record;
    }
  }

  static final class MemberRule {
    final String ownerBinaryName;
    final String declaration;

    MemberRule(String ownerBinaryName, String declaration) {
      this.ownerBinaryName = ownerBinaryName;
      this.declaration = declaration;
    }
  }

  private static final class Model {
    final TypeElement target;
    final String packageName;
    final String targetBinaryName;
    final String simpleName;
    final String qualifiedName;
    final String binaryName;
    final String targetType;
    final List<Accessor> accessors = new ArrayList<>();
    final List<MemberRule> r8Members = new ArrayList<>();
    final Set<String> r8MemberKeys = new HashSet<>();
    AnySetter anySetter;
    Creator creator;

    Model(
        TypeElement target,
        String packageName,
        String targetBinaryName,
        String simpleName,
        String qualifiedName,
        String binaryName,
        String targetType) {
      this.target = target;
      this.packageName = packageName;
      this.targetBinaryName = targetBinaryName;
      this.simpleName = simpleName;
      this.qualifiedName = qualifiedName;
      this.binaryName = binaryName;
      this.targetType = targetType;
    }

    void addR8Member(String ownerBinaryName, String declaration) {
      if (r8MemberKeys.add(ownerBinaryName + "#" + declaration)) {
        r8Members.add(new MemberRule(ownerBinaryName, declaration));
      }
    }
  }

  private enum AccessorKind {
    FIELD,
    GETTER,
    SETTER
  }

  private static final class Accessor {
    final int index;
    final AccessorKind kind;
    final String ownerType;
    final String memberName;
    final String valueType;
    final String declaredValueType;
    final TypeKind valueKind;
    final boolean mutable;

    private Accessor(
        int index,
        AccessorKind kind,
        String ownerType,
        String memberName,
        String valueType,
        String declaredValueType,
        TypeKind valueKind,
        boolean mutable) {
      this.index = index;
      this.kind = kind;
      this.ownerType = ownerType;
      this.memberName = memberName;
      this.valueType = valueType;
      this.declaredValueType = declaredValueType;
      this.valueKind = valueKind;
      this.mutable = mutable;
    }

    static Accessor forField(
        int index,
        String ownerType,
        String memberName,
        String valueType,
        String declaredValueType,
        TypeKind valueKind,
        boolean mutable) {
      return new Accessor(
          index,
          AccessorKind.FIELD,
          ownerType,
          memberName,
          valueType,
          declaredValueType,
          valueKind,
          mutable);
    }

    static Accessor forGetter(
        int index, String ownerType, String memberName, String valueType, TypeKind valueKind) {
      return new Accessor(
          index,
          AccessorKind.GETTER,
          ownerType,
          memberName,
          valueType,
          valueType,
          valueKind,
          false);
    }

    static Accessor forSetter(
        int index,
        String ownerType,
        String memberName,
        String valueType,
        String declaredValueType,
        TypeKind valueKind) {
      return new Accessor(
          index,
          AccessorKind.SETTER,
          ownerType,
          memberName,
          valueType,
          declaredValueType,
          valueKind,
          true);
    }

    boolean readable() {
      return kind != AccessorKind.SETTER;
    }

    boolean writable() {
      return kind == AccessorKind.SETTER || kind == AccessorKind.FIELD && mutable;
    }
  }

  private static final class AnySetter {
    final String ownerType;
    final String ownerBinaryName;
    final String methodName;
    final String memberValueType;
    final String memberValueBinaryType;
    final TypeKind valueKind;

    AnySetter(
        String ownerType,
        String ownerBinaryName,
        String methodName,
        String memberValueType,
        String memberValueBinaryType,
        TypeKind valueKind) {
      this.ownerType = ownerType;
      this.ownerBinaryName = ownerBinaryName;
      this.methodName = methodName;
      this.memberValueType = memberValueType;
      this.memberValueBinaryType = memberValueBinaryType;
      this.valueKind = valueKind;
    }
  }

  private static final class Creator {
    final List<String> names;
    final List<String> sourceTypes;
    final List<TypeKind> kinds;
    final String factoryName;
    final boolean record;
    final String r8Declaration;
    final boolean factory;

    Creator(
        List<String> names,
        List<String> sourceTypes,
        List<TypeKind> kinds,
        String factoryName,
        boolean record,
        String r8Declaration,
        boolean factory) {
      this.names = names;
      this.sourceTypes = sourceTypes;
      this.kinds = kinds;
      this.factoryName = factoryName;
      this.record = record;
      this.r8Declaration = r8Declaration;
      this.factory = factory;
    }
  }

  static final class InvalidJsonTypeException extends RuntimeException {
    final Element element;

    InvalidJsonTypeException(String message, Element element) {
      super(message);
      this.element = element;
    }
  }
}
