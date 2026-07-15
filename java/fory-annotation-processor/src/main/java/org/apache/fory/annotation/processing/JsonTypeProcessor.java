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
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;

/** Generates Android codec type-use metadata and precise R8 rules for {@code JsonType} models. */
final class JsonTypeProcessor {
  private static final String JSON_PACKAGE = "org.apache.fory.json";
  private static final String JSON_TYPE = JSON_PACKAGE + ".annotation.JsonType";
  private static final String JSON_CODEC = JSON_PACKAGE + ".annotation.JsonCodec";
  private static final String JSON_SUB_TYPES = JSON_PACKAGE + ".annotation.JsonSubTypes";
  private static final String JSON_CREATOR = JSON_PACKAGE + ".annotation.JsonCreator";
  private static final String JSON_PROPERTY = JSON_PACKAGE + ".annotation.JsonProperty";
  private static final String JSON_ANY_PROPERTY = JSON_PACKAGE + ".annotation.JsonAnyProperty";
  private static final String JSON_ANY_GETTER = JSON_PACKAGE + ".annotation.JsonAnyGetter";
  private static final String JSON_ANY_SETTER = JSON_PACKAGE + ".annotation.JsonAnySetter";
  private static final String GENERATED_META = JSON_PACKAGE + ".meta.GeneratedJsonCodecMeta";
  private static final String JSON_TYPE_USE = JSON_PACKAGE + ".meta.JsonTypeUse";
  private static final String JSON_VALUE_CODEC = JSON_PACKAGE + ".codec.JsonValueCodec";
  private static final String META_SUFFIX = "_ForyJsonCodecMeta";
  private static final String R8_PREFIX = "META-INF/com.android.tools/r8/fory-json-";

  private final Filer filer;
  private final Messager messager;
  private final Elements elements;
  private final Types types;
  private final JavacTypeUseTrees typeUseTrees;
  private final Set<String> processedTypes = new HashSet<>();

  JsonTypeProcessor(ProcessingEnvironment environment, JavacTypeUseTrees typeUseTrees) {
    filer = environment.getFiler();
    messager = environment.getMessager();
    elements = environment.getElementUtils();
    types = environment.getTypeUtils();
    this.typeUseTrees = typeUseTrees;
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
      if (type.getKind().name().equals("RECORD")) {
        continue;
      }
      String binaryName = elements.getBinaryName(type).toString();
      if (!processedTypes.add(binaryName)) {
        continue;
      }
      try {
        Model model = inspect(type);
        List<TypeElement> subtypes = classLiteralSubtypes(type, model.binaryFallbackTypes);
        model.sort();
        if (!model.generatedMembers.isEmpty()) {
          emitMeta(model);
        }
        emitR8(model);
        pending.addAll(subtypes);
      } catch (InvalidJsonTypeException e) {
        messager.printMessage(Diagnostic.Kind.ERROR, e.getMessage(), e.element);
      } catch (RuntimeException e) {
        messager.printMessage(
            Diagnostic.Kind.ERROR,
            "Failed to generate Fory JSON metadata for " + binaryName + ": " + e.getMessage(),
            type);
      }
    }
  }

  private Model inspect(TypeElement target) {
    PackageElement packageElement = elements.getPackageOf(target);
    String packageName =
        packageElement.isUnnamed() ? "" : packageElement.getQualifiedName().toString();
    String binaryName = elements.getBinaryName(target).toString();
    String binarySimpleName =
        binaryName.substring(packageName.isEmpty() ? 0 : packageName.length() + 1);
    Model model = new Model(target, packageName, binaryName, binarySimpleName + META_SUFFIX);
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
        collectCodecAnnotation(annotationMirror(field, JSON_CODEC), model);
        if (field.getKind() == ElementKind.ENUM_CONSTANT) {
          model.addR8Member(R8Member.field(field, typeName(field.asType())));
          continue;
        }
        if (isEligibleField(field)) {
          model.addR8Member(R8Member.field(field, typeName(field.asType())));
          collectTypeEndpoints(field.asType(), model);
          Member generated = generatedField(field, packageName, model);
          if (generated != null) {
            model.generatedMembers.add(generated);
          }
        } else if (hasAnnotation(field, JSON_PROPERTY)
            || hasAnnotation(field, JSON_ANY_PROPERTY)
            || hasAnnotation(field, JSON_CODEC)) {
          throw new InvalidJsonTypeException(
              "JSON annotations are not supported on ineligible field " + field, field);
        }
      }
    }

    for (TypeElement owner : allMethodOwners(target)) {
      collectAnnotations(owner, model.annotationTypes);
      collectCodecAnnotation(annotationMirror(owner, JSON_CODEC), model);
      if (hasJsonAnnotations(owner)) {
        model.annotationOwnerTypes.add(elements.getBinaryName(owner).toString());
      }
    }
    // Generated metadata and ordinary property rules follow Java's effective method set. An
    // unannotated override suppresses the inherited declaration, while creators remain exact to
    // the target declaration below.
    List<ExecutableElement> jsonMethods = jsonMethods(target);
    for (ExecutableElement method : jsonMethods) {
      TypeElement owner = (TypeElement) method.getEnclosingElement();
      validateMethodCodec(method);
      model.addR8Member(
          R8Member.method(method, typeName(method.getReturnType()), typeNames(method)));
      collectAnnotations(method, model.annotationTypes);
      collectCodecAnnotation(annotationMirror(method, JSON_CODEC), model);
      collectAnnotations(method.getParameters(), model.annotationTypes);
      collectTypeEndpoints(method.getReturnType(), model);
      for (VariableElement parameter : method.getParameters()) {
        collectTypeEndpoints(parameter.asType(), model);
      }
      Member generated = generatedExecutable(method, packageName, model);
      if (generated != null) {
        model.generatedMembers.add(generated);
      }
      collectAnnotations(owner, model.annotationTypes);
    }
    collectValidationMethods(target, jsonMethods, model);

    for (ExecutableElement constructor :
        ElementFilter.constructorsIn(target.getEnclosedElements())) {
      collectAnnotations(constructor, model.annotationTypes);
      collectAnnotations(constructor.getParameters(), model.annotationTypes);
      boolean creator = hasAnnotation(constructor, JSON_CREATOR);
      if (creator || isNoArg(constructor)) {
        model.addR8Member(R8Member.constructor(constructor, typeNames(constructor)));
      }
      if (creator) {
        for (VariableElement parameter : constructor.getParameters()) {
          collectTypeEndpoints(parameter.asType(), model);
        }
        Member generated = generatedExecutable(constructor, packageName, model);
        if (generated != null) {
          model.generatedMembers.add(generated);
        }
      }
    }
    return model;
  }

  private Member generatedField(VariableElement field, String packageName, Model model) {
    List<CodecPath> codecs = new ArrayList<>();
    collectCodecs(
        field.asType(),
        typeUseTrees.typeTree(field),
        field,
        packageName,
        model,
        !hasAnnotation(field, JSON_CODEC),
        new ArrayList<Integer>(),
        codecs);
    if (codecs.isEmpty()) {
      return null;
    }
    TypeElement owner = (TypeElement) field.getEnclosingElement();
    return Member.field(
        owner,
        field.getSimpleName().toString(),
        reflectionType(owner.asType(), packageName, model),
        codecs);
  }

  private Member generatedExecutable(
      ExecutableElement executable, String packageName, Model model) {
    List<List<CodecPath>> slots = new ArrayList<>();
    if (executable.getKind() == ElementKind.METHOD) {
      List<CodecPath> returnCodecs = new ArrayList<>();
      collectCodecs(
          executable.getReturnType(),
          typeUseTrees.typeTree(executable),
          executable,
          packageName,
          model,
          !hasAnnotation(executable, JSON_CODEC),
          new ArrayList<Integer>(),
          returnCodecs);
      slots.add(returnCodecs);
    } else {
      slots.add(Collections.<CodecPath>emptyList());
    }
    for (VariableElement parameter : executable.getParameters()) {
      List<CodecPath> parameterCodecs = new ArrayList<>();
      collectCodecs(
          parameter.asType(),
          typeUseTrees.typeTree(parameter),
          parameter,
          packageName,
          model,
          true,
          new ArrayList<Integer>(),
          parameterCodecs);
      slots.add(parameterCodecs);
    }
    boolean hasCodecs = false;
    for (List<CodecPath> slot : slots) {
      hasCodecs |= !slot.isEmpty();
    }
    if (!hasCodecs) {
      return null;
    }
    TypeElement owner = (TypeElement) executable.getEnclosingElement();
    List<ReflectionType> parameterTypes = new ArrayList<>();
    for (VariableElement parameter : executable.getParameters()) {
      parameterTypes.add(reflectionType(parameter.asType(), packageName, model));
    }
    return Member.executable(
        executable.getKind() == ElementKind.CONSTRUCTOR,
        owner,
        executable.getSimpleName().toString(),
        reflectionType(owner.asType(), packageName, model),
        parameterTypes,
        slots);
  }

  private void collectCodecs(
      TypeMirror type,
      Object tree,
      Element source,
      String packageName,
      Model model,
      boolean includeRoot,
      List<Integer> path,
      List<CodecPath> codecs) {
    if (includeRoot) {
      collectCodecNode(type, tree, source, packageName, model, path, codecs);
    } else {
      collectNestedCodecs(type, tree, source, packageName, model, path, codecs);
    }
  }

  private void collectNestedCodecs(
      TypeMirror type,
      Object tree,
      Element source,
      String packageName,
      Model model,
      List<Integer> path,
      List<CodecPath> codecs) {
    JavacTypeUseTrees.Tree treeInfo = typeUseTrees.tree(tree);
    TypeKind kind = type.getKind();
    if (type instanceof DeclaredType) {
      List<? extends TypeMirror> arguments = ((DeclaredType) type).getTypeArguments();
      List<?> argumentTrees = treeInfo.typeArgumentTrees();
      for (int i = 0; i < arguments.size(); i++) {
        List<Integer> childPath = childPath(path, i);
        Object childTree = i < argumentTrees.size() ? argumentTrees.get(i) : null;
        collectCodecNode(
            arguments.get(i), childTree, source, packageName, model, childPath, codecs);
      }
    } else if (kind == TypeKind.ARRAY) {
      collectCodecNode(
          ((ArrayType) type).getComponentType(),
          treeInfo.arrayComponentTree(),
          source,
          packageName,
          model,
          childPath(path, -1),
          codecs);
    } else if (kind == TypeKind.WILDCARD) {
      WildcardType wildcard = (WildcardType) type;
      TypeMirror upper = wildcard.getExtendsBound();
      TypeMirror lower = wildcard.getSuperBound();
      if (upper != null) {
        collectCodecNode(
            upper,
            treeInfo.wildcardBoundTree(),
            source,
            packageName,
            model,
            childPath(path, -2),
            codecs);
      }
      if (lower != null) {
        collectCodecNode(
            lower,
            treeInfo.wildcardBoundTree(),
            source,
            packageName,
            model,
            childPath(path, -3),
            codecs);
      }
    }
  }

  private void collectCodecNode(
      TypeMirror type,
      Object tree,
      Element source,
      String packageName,
      Model model,
      List<Integer> path,
      List<CodecPath> codecs) {
    TypeElement codec = codecClass(type, tree, source);
    if (codec != null) {
      codecs.add(codecPath(codec, path, packageName, model));
    }
    collectNestedCodecs(type, tree, source, packageName, model, path, codecs);
  }

  private TypeElement codecClass(TypeMirror type, Object tree, Element source) {
    AnnotationMirror mirror = annotationMirror(type, JSON_CODEC);
    if (mirror != null) {
      AnnotationValue value = annotationValue(mirror, "value");
      Object rawValue = value == null ? null : value.getValue();
      if (rawValue instanceof TypeMirror) {
        Element codec = types.asElement((TypeMirror) rawValue);
        if (codec instanceof TypeElement) {
          return (TypeElement) codec;
        }
      }
      throw new InvalidJsonTypeException("Cannot resolve @JsonCodec value", source);
    }
    for (Object annotationTree : typeUseTrees.tree(tree).annotations) {
      if (!typeUseTrees.isAnnotation(annotationTree, JSON_CODEC)) {
        continue;
      }
      TypeMirror codecType = typeUseTrees.annotationClassValue(source, annotationTree, "value");
      Element codec = codecType == null ? null : types.asElement(codecType);
      if (codec instanceof TypeElement) {
        return (TypeElement) codec;
      }
      throw new InvalidJsonTypeException("Cannot resolve type-use @JsonCodec value", source);
    }
    return null;
  }

  private CodecPath codecPath(
      TypeElement codec, List<Integer> path, String packageName, Model model) {
    String binaryName = elements.getBinaryName(codec).toString();
    model.codecTypes.add(binaryName);
    model.annotationTypes.add(JSON_CODEC);
    String expression;
    if (isSourceAccessible(codec, packageName)) {
      // R8 rewrites class literals when it obfuscates an accessible codec. A binary-name lookup
      // cannot be rewritten, so only that reflection fallback requires preserving the exact name.
      expression = codec.getQualifiedName() + ".class";
    } else {
      expression = "codecClass(\"" + escape(binaryName) + "\")";
      model.binaryFallbackTypes.add(binaryName);
      model.needsClassLookup = true;
      model.needsCodecLookup = true;
    }
    return new CodecPath(binaryName, expression, path);
  }

  private void collectCodecAnnotation(AnnotationMirror annotation, Model model) {
    if (annotation == null) {
      return;
    }
    AnnotationValue value = annotationValue(annotation, "value");
    if (value == null || !(value.getValue() instanceof TypeMirror)) {
      return;
    }
    TypeElement codec = asTypeElement((TypeMirror) value.getValue());
    if (codec != null) {
      model.codecTypes.add(elements.getBinaryName(codec).toString());
      model.annotationTypes.add(JSON_CODEC);
    }
  }

  private void emitMeta(Model model) {
    String qualifiedName =
        model.packageName.isEmpty()
            ? model.metaSimpleName
            : model.packageName + "." + model.metaSimpleName;
    try {
      JavaFileObject file = filer.createSourceFile(qualifiedName, model.target);
      try (Writer writer = file.openWriter()) {
        writer.write(writeMeta(model));
      }
    } catch (IOException e) {
      throw new InvalidJsonTypeException(
          "Failed to write generated JSON metadata: " + e, model.target);
    }
  }

  private String writeMeta(Model model) {
    StringBuilder builder = new StringBuilder(8192);
    if (!model.packageName.isEmpty()) {
      builder.append("package ").append(model.packageName).append(";\n\n");
    }
    if (model.hasGeneratedField()) {
      builder.append("import java.lang.reflect.Field;\n");
    }
    if (model.hasGeneratedMethod()) {
      builder.append("import java.lang.reflect.Method;\n");
    }
    if (model.hasGeneratedConstructor()) {
      builder.append("import java.lang.reflect.Constructor;\n");
    }
    builder.append("import java.lang.reflect.Member;\n");
    builder.append("import java.util.Collections;\n");
    builder.append("import java.util.LinkedHashMap;\n");
    builder.append("import java.util.Map;\n");
    builder.append("import ").append(GENERATED_META).append(";\n");
    builder.append("import ").append(JSON_TYPE_USE).append(";\n");
    if (model.needsCodecLookup) {
      builder.append("import ").append(JSON_VALUE_CODEC).append(";\n");
    }
    builder.append('\n');
    builder
        .append("public final class ")
        .append(model.metaSimpleName)
        .append(" implements GeneratedJsonCodecMeta {\n");
    builder.append(
        "  private static final Map<Member, JsonTypeUse[]> TYPE_USES = buildTypeUses();\n\n");
    builder.append("  public ").append(model.metaSimpleName).append("() {}\n\n");
    builder.append("  @Override\n");
    builder.append("  public Map<Member, JsonTypeUse[]> typeUses() {\n");
    builder.append("    return TYPE_USES;\n");
    builder.append("  }\n\n");
    builder.append("  private static Map<Member, JsonTypeUse[]> buildTypeUses() {\n");
    builder.append("    try {\n");
    builder.append("      Map<Member, JsonTypeUse[]> values = new LinkedHashMap<>();\n");
    for (int memberIndex = 0; memberIndex < model.generatedMembers.size(); memberIndex++) {
      Member member = model.generatedMembers.get(memberIndex);
      String variable = "member" + memberIndex;
      builder
          .append("      ")
          .append(member.memberType())
          .append(' ')
          .append(variable)
          .append(" = ");
      member.appendLookup(builder);
      builder.append(";\n");
      builder
          .append("      JsonTypeUse[] slots")
          .append(memberIndex)
          .append(" = new JsonTypeUse[")
          .append(member.slots.size())
          .append("];\n");
      for (int slotIndex = 0; slotIndex < member.slots.size(); slotIndex++) {
        List<CodecPath> codecs = member.slots.get(slotIndex);
        for (CodecPath codec : codecs) {
          builder
              .append("      slots")
              .append(memberIndex)
              .append('[')
              .append(slotIndex)
              .append("] = JsonTypeUse.merge(slots")
              .append(memberIndex)
              .append('[')
              .append(slotIndex)
              .append("], JsonTypeUse.generated(");
          member.appendGenericType(builder, variable, slotIndex);
          builder.append(", ").append(codec.expression).append(", \"");
          builder.append(escape(member.source(slotIndex))).append("\"");
          for (int pathStep : codec.path) {
            builder.append(", ").append(pathExpression(pathStep));
          }
          builder.append("), \"").append(escape(member.source(slotIndex))).append("\");\n");
        }
      }
      builder
          .append("      values.put(")
          .append(variable)
          .append(", slots")
          .append(memberIndex)
          .append(");\n");
    }
    builder.append("      return Collections.unmodifiableMap(values);\n");
    builder.append("    } catch (ReflectiveOperationException e) {\n");
    builder.append("      throw new ExceptionInInitializerError(e);\n");
    builder.append("    }\n");
    builder.append("  }\n\n");
    if (model.needsClassLookup) {
      builder.append(
          "  private static Class<?> classForName(String name) throws ClassNotFoundException {\n");
      builder
          .append("    return Class.forName(name, false, ")
          .append(model.metaSimpleName)
          .append(".class.getClassLoader());\n");
      builder.append("  }\n");
    }
    if (model.needsCodecLookup) {
      builder.append("\n  @SuppressWarnings({\"rawtypes\", \"unchecked\"})\n");
      builder
          .append("  private static Class<? extends JsonValueCodec<?>> codecClass(String name) ")
          .append("throws ClassNotFoundException {\n");
      builder.append("    return (Class) classForName(name);\n");
      builder.append("  }\n");
    }
    return builder.append("}\n").toString();
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

  private String writeR8(Model model) {
    StringBuilder builder = new StringBuilder(8192);
    builder.append(
        "-keepattributes Signature,RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations\n");
    builder.append(
        "-keepattributes RuntimeVisibleParameterAnnotations,RuntimeInvisibleParameterAnnotations\n");
    builder.append(
        "-keepattributes RuntimeVisibleTypeAnnotations,RuntimeInvisibleTypeAnnotations,AnnotationDefault\n");
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
          entry.getKey().equals(model.binaryName) && !model.generatedMembers.isEmpty()
              || model.binaryFallbackTypes.contains(entry.getKey());
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
    if (!model.generatedMembers.isEmpty()) {
      builder
          .append("-keep,allowoptimization class ")
          .append(model.qualifiedMetaName())
          .append(" implements ")
          .append(GENERATED_META)
          .append(" {\n")
          .append("  public <init>();\n")
          .append("  public java.util.Map typeUses();\n")
          .append("}\n");
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
      if (!usesJsonMetadata(method, owner.equals(target))) {
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
        validateMethodCodec(method);
        collectAnnotations(method, model.annotationTypes);
        collectAnnotations(method.getParameters(), model.annotationTypes);
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
    return hasAnnotation(method, JSON_CODEC)
        || hasAnnotation(method, JSON_PROPERTY)
        || hasAnnotation(method, JSON_ANY_GETTER)
        || hasAnnotation(method, JSON_ANY_SETTER);
  }

  private String methodKey(ExecutableElement method) {
    TypeElement owner = (TypeElement) method.getEnclosingElement();
    return elements.getBinaryName(owner) + "#" + methodSignature(method);
  }

  private List<TypeElement> allMethodOwners(TypeElement target) {
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

  private boolean usesJsonMetadata(ExecutableElement method, boolean targetDeclaration) {
    if (hasAnnotation(method, JSON_PROPERTY)
        || hasAnnotation(method, JSON_ANY_GETTER)
        || hasAnnotation(method, JSON_ANY_SETTER)
        || hasAnnotation(method, JSON_CODEC)) {
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

  private void validateMethodCodec(ExecutableElement method) {
    if (!hasAnnotation(method, JSON_CODEC)) {
      return;
    }
    Set<Modifier> modifiers = method.getModifiers();
    String name = method.getSimpleName().toString();
    String returnType = typeName(method.getReturnType());
    boolean getterName =
        name.startsWith("get") && name.length() > 3
            || name.startsWith("is")
                && name.length() > 2
                && (method.getReturnType().getKind() == TypeKind.BOOLEAN
                    || returnType.equals("java.lang.Boolean"));
    if (!modifiers.contains(Modifier.PUBLIC)
        || modifiers.contains(Modifier.STATIC)
        || !method.getParameters().isEmpty()
        || method.getReturnType().getKind() == TypeKind.VOID
        || returnType.equals("java.lang.Class")
        || !getterName
        || hasAnnotation(method, JSON_ANY_GETTER)
        || hasAnnotation(method, JSON_ANY_SETTER)) {
      throw new InvalidJsonTypeException(
          "@JsonCodec is not supported on JSON method " + method, method);
    }
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
    if (rawType != null && isConcreteContainer(rawType)) {
      model.containerTypes.add(elements.getBinaryName(rawType).toString());
    }
    AnnotationMirror codec = annotationMirror(type, JSON_CODEC);
    collectCodecAnnotation(codec, model);
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

  private ReflectionType reflectionType(TypeMirror type, String packageName, Model model) {
    TypeMirror erased = types.erasure(type);
    if (erased.getKind().isPrimitive()) {
      return new ReflectionType(typeName(erased) + ".class", typeName(erased));
    }
    if (erased.getKind() == TypeKind.ARRAY) {
      if (isSourceAccessible(erased, packageName)) {
        return new ReflectionType(sourceType(erased) + ".class", typeName(erased));
      }
      String binaryDescriptor = binaryDescriptor(erased);
      model.binaryFallbackTypes.add(binaryDescriptorOwner(erased));
      model.needsClassLookup = true;
      return new ReflectionType(
          "classForName(\"" + escape(binaryDescriptor) + "\")", typeName(erased));
    }
    TypeElement element = asTypeElement(erased);
    if (element != null && isSourceAccessible(element, packageName)) {
      return new ReflectionType(element.getQualifiedName() + ".class", typeName(erased));
    }
    String binaryName =
        element == null ? typeName(erased) : elements.getBinaryName(element).toString();
    model.binaryFallbackTypes.add(binaryName);
    model.needsClassLookup = true;
    return new ReflectionType("classForName(\"" + escape(binaryName) + "\")", typeName(erased));
  }

  private boolean isSourceAccessible(TypeMirror type, String packageName) {
    if (type.getKind().isPrimitive()) {
      return true;
    }
    if (type.getKind() == TypeKind.ARRAY) {
      return isSourceAccessible(((ArrayType) type).getComponentType(), packageName);
    }
    TypeElement element = asTypeElement(types.erasure(type));
    return element != null && isSourceAccessible(element, packageName);
  }

  private boolean isSourceAccessible(TypeElement type, String packageName) {
    boolean samePackage = elements.getPackageOf(type).getQualifiedName().contentEquals(packageName);
    Element current = type;
    while (current instanceof TypeElement) {
      Set<Modifier> modifiers = current.getModifiers();
      if (modifiers.contains(Modifier.PRIVATE)
          || !samePackage && !modifiers.contains(Modifier.PUBLIC)) {
        return false;
      }
      current = current.getEnclosingElement();
    }
    return true;
  }

  private String sourceType(TypeMirror type) {
    if (type.getKind() == TypeKind.ARRAY) {
      return sourceType(((ArrayType) type).getComponentType()) + "[]";
    }
    if (type.getKind().isPrimitive()) {
      return typeName(type);
    }
    TypeElement element = asTypeElement(types.erasure(type));
    return element == null ? type.toString() : element.getQualifiedName().toString();
  }

  private String binaryDescriptor(TypeMirror arrayType) {
    StringBuilder descriptor = new StringBuilder();
    TypeMirror component = arrayType;
    while (component.getKind() == TypeKind.ARRAY) {
      descriptor.append('[');
      component = ((ArrayType) component).getComponentType();
    }
    if (component.getKind().isPrimitive()) {
      descriptor.append(primitiveDescriptor(component.getKind()));
    } else {
      TypeElement element = asTypeElement(types.erasure(component));
      descriptor.append('L').append(elements.getBinaryName(element)).append(';');
    }
    return descriptor.toString();
  }

  private String binaryDescriptorOwner(TypeMirror arrayType) {
    TypeMirror component = arrayType;
    while (component.getKind() == TypeKind.ARRAY) {
      component = ((ArrayType) component).getComponentType();
    }
    TypeElement element = asTypeElement(types.erasure(component));
    return element == null ? typeName(component) : elements.getBinaryName(element).toString();
  }

  private char primitiveDescriptor(TypeKind kind) {
    switch (kind) {
      case BOOLEAN:
        return 'Z';
      case BYTE:
        return 'B';
      case SHORT:
        return 'S';
      case INT:
        return 'I';
      case LONG:
        return 'J';
      case CHAR:
        return 'C';
      case FLOAT:
        return 'F';
      case DOUBLE:
        return 'D';
      default:
        throw new IllegalArgumentException("Not a primitive type: " + kind);
    }
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

  private AnnotationMirror annotationMirror(TypeMirror type, String annotationName) {
    for (AnnotationMirror mirror : type.getAnnotationMirrors()) {
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

  private static List<Integer> childPath(List<Integer> parent, int step) {
    List<Integer> path = new ArrayList<>(parent.size() + 1);
    path.addAll(parent);
    path.add(step);
    return path;
  }

  private static String pathExpression(int step) {
    if (step >= 0) {
      return Integer.toString(step);
    }
    switch (step) {
      case -1:
        return "JsonTypeUse.ARRAY_COMPONENT";
      case -2:
        return "JsonTypeUse.WILDCARD_UPPER_BOUND";
      case -3:
        return "JsonTypeUse.WILDCARD_LOWER_BOUND";
      default:
        throw new IllegalArgumentException("Invalid generated type-use path step " + step);
    }
  }

  private static String escape(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  private static final class Model {
    final TypeElement target;
    final String packageName;
    final String binaryName;
    final String metaSimpleName;
    final List<Member> generatedMembers = new ArrayList<>();
    final List<R8Member> r8Members = new ArrayList<>();
    final Set<String> r8MemberKeys = new HashSet<>();
    final Set<String> annotationTypes = new LinkedHashSet<>();
    final Set<String> containerTypes = new LinkedHashSet<>();
    final Set<String> codecTypes = new LinkedHashSet<>();
    final Set<String> binaryFallbackTypes = new LinkedHashSet<>();
    final Set<String> annotationOwnerTypes = new LinkedHashSet<>();
    boolean needsClassLookup;
    boolean needsCodecLookup;

    Model(TypeElement target, String packageName, String binaryName, String metaSimpleName) {
      this.target = target;
      this.packageName = packageName;
      this.binaryName = binaryName;
      this.metaSimpleName = metaSimpleName;
    }

    String qualifiedMetaName() {
      return packageName.isEmpty() ? metaSimpleName : packageName + "." + metaSimpleName;
    }

    void addR8Member(R8Member member) {
      if (r8MemberKeys.add(member.ownerBinaryName + "#" + member.declaration)) {
        r8Members.add(member);
      }
    }

    boolean hasGeneratedField() {
      for (Member member : generatedMembers) {
        if (member.field) {
          return true;
        }
      }
      return false;
    }

    boolean hasGeneratedMethod() {
      for (Member member : generatedMembers) {
        if (!member.field && !member.constructor) {
          return true;
        }
      }
      return false;
    }

    boolean hasGeneratedConstructor() {
      for (Member member : generatedMembers) {
        if (member.constructor) {
          return true;
        }
      }
      return false;
    }

    boolean hasNestedIdentity() {
      return binaryName.indexOf('$') >= 0
          || qualifiedMetaName().indexOf('$') >= 0
          || containsNested(binaryFallbackTypes)
          || containsNested(annotationOwnerTypes)
          || containsNested(containerTypes)
          || containsNested(codecTypes)
          || containsNestedMember(r8Members);
    }

    void sort() {
      generatedMembers.sort(Comparator.comparing(Member::sortKey));
      Collections.sort(r8Members);
      sortSet(annotationTypes);
      sortSet(containerTypes);
      sortSet(codecTypes);
      sortSet(binaryFallbackTypes);
      sortSet(annotationOwnerTypes);
      for (Member member : generatedMembers) {
        for (List<CodecPath> slot : member.slots) {
          slot.sort(Comparator.comparing(CodecPath::sortKey));
        }
      }
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

  private static final class CodecPath {
    final String codecBinaryName;
    final String expression;
    final List<Integer> path;

    CodecPath(String codecBinaryName, String expression, List<Integer> path) {
      this.codecBinaryName = codecBinaryName;
      this.expression = expression;
      this.path = new ArrayList<>(path);
    }

    String sortKey() {
      return path.toString() + "#" + codecBinaryName;
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
  }

  private static final class ReflectionType {
    final String expression;
    final String r8Name;

    ReflectionType(String expression, String r8Name) {
      this.expression = expression;
      this.r8Name = r8Name;
    }
  }

  private static final class Member {
    final boolean field;
    final boolean constructor;
    final String ownerBinaryName;
    final String name;
    final ReflectionType ownerType;
    final List<ReflectionType> parameterTypes;
    final List<List<CodecPath>> slots;

    private Member(
        boolean field,
        boolean constructor,
        String ownerBinaryName,
        String name,
        ReflectionType ownerType,
        List<ReflectionType> parameterTypes,
        List<List<CodecPath>> slots) {
      this.field = field;
      this.constructor = constructor;
      this.ownerBinaryName = ownerBinaryName;
      this.name = name;
      this.ownerType = ownerType;
      this.parameterTypes = parameterTypes;
      this.slots = slots;
    }

    static Member field(
        TypeElement owner, String name, ReflectionType ownerType, List<CodecPath> codecs) {
      return new Member(
          true,
          false,
          CodecPath.binaryName(owner),
          name,
          ownerType,
          Collections.<ReflectionType>emptyList(),
          Collections.singletonList(codecs));
    }

    static Member executable(
        boolean constructor,
        TypeElement owner,
        String name,
        ReflectionType ownerType,
        List<ReflectionType> parameterTypes,
        List<List<CodecPath>> slots) {
      return new Member(
          false, constructor, CodecPath.binaryName(owner), name, ownerType, parameterTypes, slots);
    }

    String sortKey() {
      StringBuilder key = new StringBuilder(ownerBinaryName).append('#').append(name).append('(');
      for (ReflectionType parameterType : parameterTypes) {
        key.append(parameterType.r8Name).append(',');
      }
      return key.append(')').toString();
    }

    String memberType() {
      return field ? "Field" : constructor ? "Constructor<?>" : "Method";
    }

    void appendLookup(StringBuilder builder) {
      builder.append(ownerType.expression);
      if (field) {
        builder.append(".getDeclaredField(\"").append(escape(name)).append("\")");
        return;
      }
      builder
          .append(constructor ? ".getDeclaredConstructor(" : ".getDeclaredMethod(\"")
          .append(constructor ? "" : escape(name) + "\"");
      for (int i = 0; i < parameterTypes.size(); i++) {
        if (i > 0 || !constructor) {
          builder.append(", ");
        }
        builder.append(parameterTypes.get(i).expression);
      }
      builder.append(')');
    }

    void appendGenericType(StringBuilder builder, String variable, int slot) {
      if (field) {
        builder.append(variable).append(".getGenericType()");
      } else if (slot == 0) {
        builder.append(variable).append(".getGenericReturnType()");
      } else {
        builder
            .append(variable)
            .append(".getGenericParameterTypes()[")
            .append(slot - 1)
            .append(']');
      }
    }

    String source(int slot) {
      if (field) {
        return "field " + ownerBinaryName + "." + name;
      }
      if (slot == 0 && !constructor) {
        return "method return " + ownerBinaryName + "#" + name;
      }
      return (constructor ? "constructor parameter " : "method parameter ")
          + ownerBinaryName
          + (constructor ? "" : "#" + name)
          + "["
          + (slot - 1)
          + "]";
    }
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
      return new R8Member(
          CodecPath.binaryName(owner), typeName + " " + field.getSimpleName() + ";");
    }

    static R8Member method(
        ExecutableElement method, String returnType, List<String> parameterTypes) {
      TypeElement owner = (TypeElement) method.getEnclosingElement();
      return new R8Member(
          CodecPath.binaryName(owner),
          returnType
              + " "
              + method.getSimpleName()
              + "("
              + String.join(",", parameterTypes)
              + ");");
    }

    static R8Member constructor(ExecutableElement constructor, List<String> parameterTypes) {
      TypeElement owner = (TypeElement) constructor.getEnclosingElement();
      return new R8Member(
          CodecPath.binaryName(owner), "<init>(" + String.join(",", parameterTypes) + ");");
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
