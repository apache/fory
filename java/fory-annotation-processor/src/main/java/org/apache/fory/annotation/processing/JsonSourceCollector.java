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

import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import org.apache.fory.json.ForyJsonException;
import org.apache.fory.json.meta.JsonBuiltInTypeCatalog;
import org.apache.fory.json.meta.JsonClassNames;
import org.apache.fory.json.meta.JsonMetadataFormat;
import org.apache.fory.json.meta.JsonTypeMetadata;

/** Extracts configuration-independent JSON facts from one annotated source declaration. */
final class JsonSourceCollector {
  private static final String JSON_CODEC = "org.apache.fory.json.annotation.JsonCodec";
  private static final String JSON_CREATOR = "org.apache.fory.json.annotation.JsonCreator";
  private static final String JSON_IGNORE = "org.apache.fory.json.annotation.JsonIgnore";
  private static final String JSON_PROPERTY = "org.apache.fory.json.annotation.JsonProperty";
  private static final String JSON_ORDER = "org.apache.fory.json.annotation.JsonPropertyOrder";
  private static final String JSON_SUBTYPES = "org.apache.fory.json.annotation.JsonSubTypes";
  private static final String JSON_ANY_FIELD = "org.apache.fory.json.annotation.JsonAnyProperty";
  private static final String JSON_ANY_GETTER = "org.apache.fory.json.annotation.JsonAnyGetter";
  private static final String JSON_ANY_SETTER = "org.apache.fory.json.annotation.JsonAnySetter";

  private final Elements elements;
  private final Types types;
  private final SourceAnnotations annotations;
  private final JavacTypeUseTrees trees;
  private final JvmDescriptors descriptors;
  private final TypeElement target;
  private final String packageName;
  private final SourceAccess access;
  private final Map<JsonSourceModel.Section, Map<TypeParameterElement, Integer>> typeParameters =
      new HashMap<>();

  JsonSourceCollector(ProcessingEnvironment environment, TypeElement target) {
    elements = environment.getElementUtils();
    types = environment.getTypeUtils();
    annotations = new SourceAnnotations(elements);
    trees = new JavacTypeUseTrees(environment);
    descriptors = new JvmDescriptors(elements, types);
    this.target = target;
    PackageElement pkg = elements.getPackageOf(target);
    packageName = pkg.isUnnamed() ? "" : pkg.getQualifiedName().toString();
    access = new SourceAccess(elements, types, packageName);
  }

  JsonSourceModel collect() {
    validateTarget();
    JsonSourceModel.Section declarations =
        new JsonSourceModel.Section(JsonTypeMetadata.DECLARATIONS);
    JsonSourceModel.Section subtypes = new JsonSourceModel.Section(JsonTypeMetadata.SUBTYPES);
    JsonSourceModel.Section object = new JsonSourceModel.Section(JsonTypeMetadata.OBJECT);
    collectDeclarations(declarations);
    collectSubtypes(subtypes);
    if (hasObjectSection()) {
      collectObject(object);
    }
    String targetName = descriptors.binaryName(target);
    String metadataName = JsonTypeMetadata.generatedBinaryName(targetName);
    String simpleName =
        metadataName.substring(packageName.isEmpty() ? 0 : packageName.length() + 1);
    return new JsonSourceModel(packageName, targetName, simpleName, declarations, subtypes, object);
  }

  private void validateTarget() {
    NestingKind nesting = target.getNestingKind();
    if (nesting == NestingKind.LOCAL || nesting == NestingKind.ANONYMOUS) {
      fail("@JsonType local and anonymous declarations are unsupported", target);
    }
    if (nesting == NestingKind.MEMBER
        && !target.getModifiers().contains(javax.lang.model.element.Modifier.STATIC)) {
      fail("@JsonType member declarations must be static", target);
    }
    if (target.getKind() == ElementKind.ANNOTATION_TYPE) {
      fail("@JsonType annotation declarations are unsupported", target);
    }
  }

  private boolean hasObjectSection() {
    Set<javax.lang.model.element.Modifier> modifiers = target.getModifiers();
    return (target.getKind() == ElementKind.CLASS || RecordElements.isRecord(target))
        && target.getKind() != ElementKind.ENUM
        && !modifiers.contains(javax.lang.model.element.Modifier.ABSTRACT)
        && !hasDeclarationCodec()
        && !isTerminalBuiltIn();
  }

  private boolean hasDeclarationCodec() {
    Deque<TypeElement> pending = new ArrayDeque<>();
    Set<String> visited = new HashSet<>();
    pending.add(target);
    while (!pending.isEmpty()) {
      TypeElement declaration = pending.removeFirst();
      if (!visited.add(descriptors.binaryName(declaration))) {
        continue;
      }
      if (annotations.has(declaration, JSON_CODEC)) {
        return true;
      }
      TypeMirror superclass = declaration.getSuperclass();
      if (superclass != null && superclass.getKind() != TypeKind.NONE) {
        Element element = types.asElement(superclass);
        if (element instanceof TypeElement) {
          pending.addLast((TypeElement) element);
        }
      }
      for (TypeMirror interfaceType : declaration.getInterfaces()) {
        Element element = types.asElement(interfaceType);
        if (element instanceof TypeElement) {
          pending.addLast((TypeElement) element);
        }
      }
    }
    return false;
  }

  private boolean isTerminalBuiltIn() {
    TypeMirror erasedTarget = types.erasure(target.asType());
    for (String name : JsonBuiltInTypeCatalog.exactBinaryNames()) {
      TypeElement candidate = elements.getTypeElement(name);
      if (candidate != null && types.isSameType(erasedTarget, types.erasure(candidate.asType()))) {
        return true;
      }
    }
    for (String name : JsonBuiltInTypeCatalog.assignableBinaryNames()) {
      TypeElement candidate = elements.getTypeElement(name);
      if (candidate != null
          && types.isAssignable(erasedTarget, types.erasure(candidate.asType()))) {
        return true;
      }
    }
    return false;
  }

  private void collectDeclarations(JsonSourceModel.Section section) {
    Deque<TypeElement> pending = new ArrayDeque<>();
    Set<String> visited = new HashSet<>();
    pending.add(target);
    while (!pending.isEmpty()) {
      TypeElement declaration = pending.removeFirst();
      String binaryName = descriptors.binaryName(declaration);
      if (!visited.add(binaryName)) {
        continue;
      }
      int typeToken = token(section, declaration.asType());
      int superclass = -1;
      TypeMirror superclassType = declaration.getSuperclass();
      if (superclassType != null
          && superclassType.getKind() != TypeKind.NONE
          && !types.erasure(superclassType).toString().equals("java.lang.Object")) {
        TypeElement superclassElement = (TypeElement) types.asElement(superclassType);
        superclass = token(section, superclassType);
        pending.addLast(superclassElement);
      }
      List<Integer> interfaces = new ArrayList<>();
      for (TypeMirror interfaceType : declaration.getInterfaces()) {
        interfaces.add(token(section, interfaceType));
        pending.addLast((TypeElement) types.asElement(interfaceType));
      }
      AnnotationMirror codec = annotations.find(declaration, JSON_CODEC);
      int codecToken = -1;
      int codecOperation = -1;
      if (codec != null) {
        TypeMirror codecType = requiredCodecType(codec, declaration);
        codecToken = token(section, codecType);
        codecOperation = codecOperation(section, codecType, codecToken, declaration);
      }
      section.facts.add(
          new JsonSourceModel.DeclarationFact(
              JsonMetadataFormat.DECLARATION,
              typeToken,
              declarationKind(declaration),
              modifiers(declaration),
              superclass,
              interfaces,
              codecToken,
              codecOperation));
    }
  }

  private void collectSubtypes(JsonSourceModel.Section section) {
    AnnotationMirror subtype = annotations.find(target, JSON_SUBTYPES);
    if (subtype == null) {
      return;
    }
    List<JsonSourceModel.SubtypeEntry> entries = new ArrayList<>();
    for (AnnotationMirror entry : annotations.annotations(subtype, "value")) {
      TypeMirror literal = annotations.type(entry, "value");
      String className = annotations.string(entry, "className", "");
      String logicalName = annotations.string(entry, "name", "");
      boolean hasLiteral =
          literal != null && !types.erasure(literal).toString().equals("java.lang.Void");
      if (hasLiteral == !className.isEmpty()) {
        fail("@JsonSubTypes.Type must declare exactly one class reference", target);
      }
      int token = -1;
      if (hasLiteral) {
        token = token(section, literal);
      } else {
        try {
          JsonClassNames.requireBinaryName(className, "@JsonSubTypes on " + target);
        } catch (ForyJsonException e) {
          fail(e.getMessage(), target);
        }
      }
      if (logicalName.isEmpty()) {
        fail("@JsonSubTypes.Type name must not be empty", target);
      }
      entries.add(new JsonSourceModel.SubtypeEntry(token, className, logicalName));
    }
    section.facts.add(
        new JsonSourceModel.SubtypesFact(
            JsonMetadataFormat.SUBTYPE_TABLE,
            annotations.enumOrdinal(subtype, "inclusion", 0),
            annotations.string(subtype, "property", ""),
            entries));
  }

  private void collectObject(JsonSourceModel.Section section) {
    List<TypeElement> hierarchy = hierarchy(target);
    validateObjectAnnotations(hierarchy);
    collectGenericHierarchy(section);
    Set<String> fieldNames = new HashSet<>();
    for (int i = hierarchy.size() - 1; i >= 0; i--) {
      TypeElement declaring = hierarchy.get(i);
      for (VariableElement field : ElementFilter.fieldsIn(declaring.getEnclosedElements())) {
        if (!isEligibleField(field)) {
          continue;
        }
        if (!fieldNames.add(field.getSimpleName().toString())) {
          fail("Duplicate JSON field " + field.getSimpleName(), field);
        }
        collectField(section, field, RecordElements.isRecord(target));
      }
    }
    collectMethods(section);
    collectCreators(section);
    collectPropertyOrder(section, hierarchy);
    if (RecordElements.isRecord(target)) {
      collectRecordComponents(section);
    } else {
      collectNoArgConstructor(section);
    }
  }

  private void collectGenericHierarchy(JsonSourceModel.Section section) {
    Deque<TypeElement> pending = new ArrayDeque<>();
    Set<String> visited = new HashSet<>();
    pending.add(target);
    while (!pending.isEmpty()) {
      TypeElement declaration = pending.removeFirst();
      if (!visited.add(descriptors.binaryName(declaration))) {
        continue;
      }
      int superclassNode = -1;
      TypeMirror superclass = declaration.getSuperclass();
      if (superclass != null
          && superclass.getKind() != TypeKind.NONE
          && !types.erasure(superclass).toString().equals("java.lang.Object")) {
        superclassNode =
            typeNode(
                section, superclass, null, declaration, "generic superclass of " + declaration);
        pending.addLast((TypeElement) types.asElement(superclass));
      }
      List<Integer> interfaceNodes = new ArrayList<>();
      for (TypeMirror interfaceType : declaration.getInterfaces()) {
        interfaceNodes.add(
            typeNode(
                section, interfaceType, null, declaration, "generic interface of " + declaration));
        pending.addLast((TypeElement) types.asElement(interfaceType));
      }
      section.facts.add(
          new JsonSourceModel.HierarchyFact(
              JsonMetadataFormat.HIERARCHY,
              token(section, declaration.asType()),
              superclassNode,
              interfaceNodes));
    }
  }

  private void collectField(
      JsonSourceModel.Section section, VariableElement field, boolean record) {
    TypeElement declaring = (TypeElement) field.getEnclosingElement();
    int declaringToken = token(section, declaring.asType());
    int typeNode =
        typeNode(section, field.asType(), trees.variableType(field), field, "field " + field);
    AnnotationMirror ignore = annotations.find(field, JSON_IGNORE);
    int flags = 0;
    boolean ignoreRead = ignore != null && annotations.bool(ignore, "ignoreRead", true);
    boolean ignoreWrite = ignore != null && annotations.bool(ignore, "ignoreWrite", true);
    boolean any = annotations.has(field, JSON_ANY_FIELD);
    boolean finalField = field.getModifiers().contains(javax.lang.model.element.Modifier.FINAL);
    boolean read = (any || record || !finalField) && !ignoreRead;
    boolean write = !ignoreWrite;
    if (ignore != null) {
      flags |= JsonMetadataFormat.HAS_JSON_IGNORE;
    }
    if (ignoreRead) {
      flags |= JsonMetadataFormat.IGNORE_READ;
    }
    if (ignoreWrite) {
      flags |= JsonMetadataFormat.IGNORE_WRITE;
    }
    if (read) {
      flags |= JsonMetadataFormat.READ_ELIGIBLE;
    }
    if (write) {
      flags |= JsonMetadataFormat.WRITE_ELIGIBLE;
    }
    if (any) {
      flags |= JsonMetadataFormat.JSON_ANY_PROPERTY;
    }
    JsonSourceModel.Property property = property(field);
    if (property.present) {
      flags |= JsonMetadataFormat.HAS_JSON_PROPERTY;
    }
    // An input-enabled Any field first reads its existing Map even when JSON output is disabled.
    // A non-final field also needs write access when a new Map must replace a null field value.
    int direction =
        (read && !finalField ? JsonMetadataFormat.READ : 0)
            | (write || any && read ? JsonMetadataFormat.WRITE : 0);
    int operation = -1;
    if (direction != 0) {
      operation =
          operation(
              section,
              JsonMetadataFormat.FIELD_ACCESS,
              direction,
              access.field(field),
              field,
              declaringToken,
              field.getSimpleName().toString(),
              descriptors.field(field),
              field.asType());
    }
    section.facts.add(
        new JsonSourceModel.FieldFact(
            JsonMetadataFormat.FIELD,
            declaringToken,
            field.getSimpleName().toString(),
            descriptors.field(field),
            modifiers(field),
            typeNode,
            flags,
            property,
            operation));
  }

  private void collectMethods(JsonSourceModel.Section section) {
    List<ExecutableElement> methods = new ArrayList<>();
    for (ExecutableElement method : ElementFilter.methodsIn(elements.getAllMembers(target))) {
      Set<javax.lang.model.element.Modifier> modifiers = method.getModifiers();
      if (!modifiers.contains(javax.lang.model.element.Modifier.PUBLIC)) {
        continue;
      }
      boolean anyGetter = annotations.has(method, JSON_ANY_GETTER);
      boolean anySetter = annotations.has(method, JSON_ANY_SETTER);
      boolean jsonProperty = annotations.has(method, JSON_PROPERTY);
      if (anyGetter && anySetter) {
        fail("Conflicting JSON Any method annotations on " + method, method);
      }
      if ((anyGetter || anySetter || jsonProperty)
          && modifiers.contains(javax.lang.model.element.Modifier.STATIC)) {
        fail("JSON method annotations require an instance method: " + method, method);
      }
      if (modifiers.contains(javax.lang.model.element.Modifier.STATIC)) {
        continue;
      }
      if (jsonProperty && (anyGetter || anySetter)) {
        fail("@JsonProperty is not supported on a JSON Any method: " + method, method);
      }
      int shape = methodShape(method, anyGetter, anySetter);
      if (jsonProperty && shape < 0) {
        fail("@JsonProperty requires a JSON getter or setter: " + method, method);
      }
      if (shape < 0) {
        continue;
      }
      methods.add(method);
    }
    Collections.sort(
        methods,
        Comparator.comparing(
            method ->
                descriptors.binaryName((TypeElement) method.getEnclosingElement())
                    + '#'
                    + method.getSimpleName()
                    + descriptors.executable(method)));
    Set<String> seen = new HashSet<>();
    ExecutableElement anyGetter = null;
    ExecutableElement anySetter = null;
    for (ExecutableElement method : methods) {
      String effectiveKey = method.getSimpleName() + descriptors.executable(method);
      if (!seen.add(effectiveKey)) {
        continue;
      }
      if (annotations.has(method, JSON_ANY_GETTER)) {
        if (anyGetter != null) {
          fail("Multiple @JsonAnyGetter methods on " + target, method);
        }
        anyGetter = method;
      }
      if (annotations.has(method, JSON_ANY_SETTER)) {
        if (anySetter != null) {
          fail("Multiple @JsonAnySetter methods on " + target, method);
        }
        anySetter = method;
      }
      collectMethod(section, method);
    }
  }

  private void collectMethod(JsonSourceModel.Section section, ExecutableElement method) {
    TypeElement declaring = (TypeElement) method.getEnclosingElement();
    int declaringToken = token(section, declaring.asType());
    boolean anyGetter = annotations.has(method, JSON_ANY_GETTER);
    boolean anySetter = annotations.has(method, JSON_ANY_SETTER);
    int shape = methodShape(method, anyGetter, anySetter);
    int flags = 0;
    int direction;
    TypeMirror valueType;
    if (anyGetter) {
      flags |= JsonMetadataFormat.JSON_ANY_GETTER | JsonMetadataFormat.WRITE_ELIGIBLE;
      direction = JsonMetadataFormat.WRITE;
      valueType = method.getReturnType();
    } else if (shape == JsonMetadataFormat.GETTER) {
      flags |= JsonMetadataFormat.WRITE_ELIGIBLE;
      direction = JsonMetadataFormat.WRITE;
      valueType = method.getReturnType();
    } else if (shape == JsonMetadataFormat.SETTER) {
      flags |= JsonMetadataFormat.READ_ELIGIBLE;
      direction = JsonMetadataFormat.READ;
      valueType = method.getParameters().get(0).asType();
    } else if (shape == JsonMetadataFormat.ANY_SETTER) {
      flags |= JsonMetadataFormat.JSON_ANY_SETTER | JsonMetadataFormat.READ_ELIGIBLE;
      direction = JsonMetadataFormat.READ;
      valueType = method.getParameters().get(1).asType();
    } else {
      throw new AssertionError("Unknown generated JSON method shape " + shape);
    }
    JsonSourceModel.Property property = property(method);
    if (property.present) {
      flags |= JsonMetadataFormat.HAS_JSON_PROPERTY;
    }
    List<Integer> parameters = new ArrayList<>();
    for (VariableElement parameter : method.getParameters()) {
      parameters.add(
          typeNode(
              section,
              parameter.asType(),
              trees.variableType(parameter),
              parameter,
              "method parameter " + method));
    }
    int returnNode =
        typeNode(
            section,
            method.getReturnType(),
            trees.methodReturnType(method),
            method,
            "method return " + method);
    int operation =
        operation(
            section,
            shape,
            direction,
            access.method(method),
            method,
            declaringToken,
            method.getSimpleName().toString(),
            descriptors.executable(method),
            valueType);
    section.facts.add(
        new JsonSourceModel.MethodFact(
            JsonMetadataFormat.METHOD,
            declaringToken,
            method.getSimpleName().toString(),
            descriptors.executable(method),
            modifiers(method),
            returnNode,
            parameters,
            flags,
            property,
            operation));
  }

  private void collectCreators(JsonSourceModel.Section section) {
    List<ExecutableElement> creators = new ArrayList<>();
    for (ExecutableElement constructor :
        ElementFilter.constructorsIn(target.getEnclosedElements())) {
      if (annotations.has(constructor, JSON_CREATOR)) {
        creators.add(constructor);
      }
    }
    for (ExecutableElement method : ElementFilter.methodsIn(target.getEnclosedElements())) {
      if (annotations.has(method, JSON_CREATOR)) {
        creators.add(method);
      }
    }
    if (RecordElements.isRecord(target) && !creators.isEmpty()) {
      fail("@JsonCreator is not supported on records", creators.get(0));
    }
    if (creators.size() > 1) {
      fail("Multiple @JsonCreator declarations on " + target, creators.get(1));
    }
    if (creators.isEmpty()) {
      if (RecordElements.isRecord(target)) {
        collectRecordConstructor(section);
      }
      return;
    }
    ExecutableElement creator = creators.get(0);
    validateCreator(creator);
    AnnotationMirror annotation = annotations.find(creator, JSON_CREATOR);
    List<Integer> parameterNodes = new ArrayList<>();
    List<JsonSourceModel.Property> properties = new ArrayList<>();
    for (VariableElement parameter : creator.getParameters()) {
      parameterNodes.add(
          typeNode(
              section,
              parameter.asType(),
              trees.variableType(parameter),
              parameter,
              "creator parameter " + creator));
      properties.add(property(parameter));
    }
    int declaringToken = token(section, target.asType());
    int operation =
        operation(
            section,
            JsonMetadataFormat.CREATOR_CALL,
            JsonMetadataFormat.READ,
            access.creator(creator),
            creator,
            declaringToken,
            creator.getSimpleName().toString(),
            descriptors.executable(creator),
            target.asType());
    section.facts.add(
        new JsonSourceModel.CreatorFact(
            JsonMetadataFormat.CREATOR,
            creator.getKind() == ElementKind.CONSTRUCTOR
                ? JsonMetadataFormat.CREATOR_CONSTRUCTOR
                : JsonMetadataFormat.CREATOR_FACTORY,
            declaringToken,
            creator.getSimpleName().toString(),
            descriptors.executable(creator),
            modifiers(creator),
            annotations.strings(annotation, "value"),
            parameterNodes,
            properties,
            operation));
  }

  private void collectPropertyOrder(JsonSourceModel.Section section, List<TypeElement> hierarchy) {
    for (TypeElement declaration : hierarchy) {
      AnnotationMirror order = annotations.find(declaration, JSON_ORDER);
      if (order != null) {
        section.facts.add(
            new JsonSourceModel.OrderFact(
                JsonMetadataFormat.PROPERTY_ORDER,
                token(section, declaration.asType()),
                annotations.strings(order, "value"),
                annotations.bool(order, "alphabetic", false)));
        return;
      }
    }
  }

  private void collectRecordComponents(JsonSourceModel.Section section) {
    int declaringToken = token(section, target.asType());
    Map<String, ExecutableElement> accessors = new HashMap<>();
    for (ExecutableElement method : ElementFilter.methodsIn(target.getEnclosedElements())) {
      if (method.getParameters().isEmpty()) {
        accessors.put(method.getSimpleName().toString(), method);
      }
    }
    for (Element component : RecordElements.components(target)) {
      TypeMirror componentType = component.asType();
      ExecutableElement accessor = accessors.get(component.getSimpleName().toString());
      String descriptor = accessor == null ? "" : descriptors.executable(accessor);
      JsonSourceModel.Property property = property(component);
      int flags = property.present ? JsonMetadataFormat.HAS_JSON_PROPERTY : 0;
      section.facts.add(
          new JsonSourceModel.RecordFact(
              JsonMetadataFormat.RECORD_COMPONENT,
              declaringToken,
              component.getSimpleName().toString(),
              typeNode(
                  section,
                  componentType,
                  trees.variableType(component),
                  component,
                  "record component " + component),
              descriptor,
              flags,
              property));
    }
  }

  private void collectNoArgConstructor(JsonSourceModel.Section section) {
    for (ExecutableElement constructor :
        ElementFilter.constructorsIn(target.getEnclosedElements())) {
      if (constructor.getParameters().isEmpty()) {
        int ownerToken = token(section, target.asType());
        int operation =
            operation(
                section,
                JsonMetadataFormat.NO_ARG_CONSTRUCTOR,
                JsonMetadataFormat.READ,
                access.constructor(constructor),
                constructor,
                ownerToken,
                "<init>",
                descriptors.executable(constructor),
                target.asType());
        section.facts.add(
            new JsonSourceModel.InstantiatorFact(
                JsonMetadataFormat.INSTANTIATOR,
                JsonMetadataFormat.INSTANTIATOR_NO_ARG,
                operation));
        return;
      }
    }
  }

  private void collectRecordConstructor(JsonSourceModel.Section section) {
    List<? extends Element> components = RecordElements.components(target);
    for (ExecutableElement constructor :
        ElementFilter.constructorsIn(target.getEnclosedElements())) {
      if (isCanonicalConstructor(constructor, components)) {
        int ownerToken = token(section, target.asType());
        int operation =
            operation(
                section,
                JsonMetadataFormat.RECORD_CONSTRUCTOR,
                JsonMetadataFormat.READ,
                access.constructor(constructor),
                constructor,
                ownerToken,
                "<init>",
                descriptors.executable(constructor),
                target.asType());
        section.facts.add(
            new JsonSourceModel.InstantiatorFact(
                JsonMetadataFormat.INSTANTIATOR,
                JsonMetadataFormat.INSTANTIATOR_RECORD,
                operation));
        return;
      }
    }
    fail("Missing canonical record constructor for " + target, target);
  }

  private void validateObjectAnnotations(List<TypeElement> hierarchy) {
    boolean record = RecordElements.isRecord(target);
    for (TypeElement declaration : hierarchy) {
      for (VariableElement field : ElementFilter.fieldsIn(declaration.getEnclosedElements())) {
        if (annotations.has(field, JSON_PROPERTY) && !isEligibleField(field)) {
          fail("@JsonProperty is not supported on JSON field: " + field, field);
        }
        if (annotations.has(field, JSON_ANY_FIELD) && !isEligibleField(field)) {
          fail("@JsonAnyProperty is not supported on JSON field: " + field, field);
        }
      }
      for (ExecutableElement method : ElementFilter.methodsIn(declaration.getEnclosedElements())) {
        boolean property = annotations.has(method, JSON_PROPERTY);
        boolean anyGetter = annotations.has(method, JSON_ANY_GETTER);
        boolean anySetter = annotations.has(method, JSON_ANY_SETTER);
        if (!property && !anyGetter && !anySetter) {
          continue;
        }
        if (isOverridden(method)) {
          continue;
        }
        if (anyGetter && anySetter) {
          fail("Conflicting JSON Any method annotations on " + method, method);
        }
        if (property && (anyGetter || anySetter)) {
          fail("@JsonProperty is not supported on a JSON Any method: " + method, method);
        }
        Set<javax.lang.model.element.Modifier> modifiers = method.getModifiers();
        boolean eligible =
            modifiers.contains(javax.lang.model.element.Modifier.PUBLIC)
                && !modifiers.contains(javax.lang.model.element.Modifier.STATIC);
        if (property) {
          if (!eligible || record && !isRecordAccessor(method)) {
            fail("@JsonProperty is not supported on JSON method: " + method, method);
          }
          if (methodShape(method, false, false) < 0) {
            fail("@JsonProperty requires a JSON getter or setter: " + method, method);
          }
        } else if (!eligible) {
          fail(
              (anyGetter ? "Invalid @JsonAnyGetter method " : "Invalid @JsonAnySetter method ")
                  + method,
              method);
        } else {
          methodShape(method, anyGetter, anySetter);
        }
      }
    }
  }

  private boolean isEligibleField(VariableElement field) {
    Set<javax.lang.model.element.Modifier> modifiers = field.getModifiers();
    return !modifiers.contains(javax.lang.model.element.Modifier.STATIC)
        && !modifiers.contains(javax.lang.model.element.Modifier.TRANSIENT)
        && !types.erasure(field.asType()).toString().equals("java.lang.Class");
  }

  private boolean isOverridden(ExecutableElement method) {
    Set<javax.lang.model.element.Modifier> modifiers = method.getModifiers();
    if (method.getEnclosingElement().equals(target)
        || !modifiers.contains(javax.lang.model.element.Modifier.PUBLIC)
        || modifiers.contains(javax.lang.model.element.Modifier.STATIC)) {
      return false;
    }
    for (ExecutableElement candidate : ElementFilter.methodsIn(elements.getAllMembers(target))) {
      if (!candidate.equals(method) && elements.overrides(candidate, method, target)) {
        return true;
      }
    }
    return false;
  }

  private boolean isCanonicalConstructor(
      ExecutableElement constructor, List<? extends Element> components) {
    List<? extends VariableElement> parameters = constructor.getParameters();
    if (parameters.size() != components.size()) {
      return false;
    }
    for (int i = 0; i < parameters.size(); i++) {
      if (!types.isSameType(parameters.get(i).asType(), components.get(i).asType())) {
        return false;
      }
    }
    return true;
  }

  private int typeNode(
      JsonSourceModel.Section section, TypeMirror type, Object tree, Element owner, String source) {
    if (type.getKind() == TypeKind.ERROR) {
      throw new DeferredTypeException("Unresolved type " + type + " from " + source, owner);
    }
    JavacTypeUseTrees.Node treeNode = trees.node(tree);
    int codecToken = -1;
    int codecOperation = -1;
    AnnotationMirror codec = annotations.find(type, JSON_CODEC);
    TypeMirror codecType = codec == null ? null : annotations.type(codec, "value");
    if (codecType == null) {
      for (Object annotationTree : treeNode.annotations) {
        if (trees.annotationMatches(annotationTree, JSON_CODEC)) {
          codecType = trees.annotationTypeValue(owner, annotationTree, "value");
          break;
        }
      }
    }
    if (codecType != null) {
      validateCodec(codecType, owner);
      codecToken = token(section, codecType);
      codecOperation = codecOperation(section, codecType, codecToken, owner);
    }
    String codecSource = codecType == null ? "" : source;
    int id = section.typeNodes.size();
    section.typeNodes.add(null);
    TypeKind kind = type.getKind();
    if (kind.isPrimitive() || kind == TypeKind.VOID) {
      section.typeNodes.set(
          id,
          new JsonSourceModel.TypeNode(
              id,
              JsonMetadataFormat.TYPE_PRIMITIVE,
              primitiveKind(kind),
              -1,
              null,
              null,
              -1,
              codecToken,
              codecOperation,
              codecSource));
      return id;
    }
    if (kind == TypeKind.ARRAY) {
      int component =
          typeNode(
              section,
              ((ArrayType) type).getComponentType(),
              treeNode.arrayComponent(),
              owner,
              source + " component");
      section.typeNodes.set(
          id,
          new JsonSourceModel.TypeNode(
              id,
              JsonMetadataFormat.TYPE_ARRAY,
              component,
              -1,
              null,
              null,
              -1,
              codecToken,
              codecOperation,
              codecSource));
      return id;
    }
    if (kind == TypeKind.TYPEVAR) {
      int key = typeParameter(section, (TypeParameterElement) ((TypeVariable) type).asElement());
      section.typeNodes.set(
          id,
          new JsonSourceModel.TypeNode(
              id,
              JsonMetadataFormat.TYPE_VARIABLE,
              -1,
              -1,
              null,
              null,
              key,
              codecToken,
              codecOperation,
              codecSource));
      return id;
    }
    if (kind == TypeKind.WILDCARD) {
      WildcardType wildcard = (WildcardType) type;
      List<Integer> upper = new ArrayList<>();
      List<Integer> lower = new ArrayList<>();
      if (wildcard.getExtendsBound() != null) {
        upper.add(typeNode(section, wildcard.getExtendsBound(), null, owner, source + " upper"));
      }
      if (wildcard.getSuperBound() != null) {
        lower.add(typeNode(section, wildcard.getSuperBound(), null, owner, source + " lower"));
      }
      section.typeNodes.set(
          id,
          new JsonSourceModel.TypeNode(
              id,
              JsonMetadataFormat.TYPE_WILDCARD,
              -1,
              -1,
              upper,
              lower,
              -1,
              codecToken,
              codecOperation,
              codecSource));
      return id;
    }
    DeclaredType declared = (DeclaredType) type;
    int ownerNode = -1;
    if (declared.getEnclosingType() != null
        && declared.getEnclosingType().getKind() != TypeKind.NONE) {
      ownerNode = typeNode(section, declared.getEnclosingType(), null, owner, source + " owner");
    }
    List<Integer> arguments = new ArrayList<>();
    List<?> argumentTrees = treeNode.typeArguments();
    for (int i = 0; i < declared.getTypeArguments().size(); i++) {
      arguments.add(
          typeNode(
              section,
              declared.getTypeArguments().get(i),
              i < argumentTrees.size() ? argumentTrees.get(i) : null,
              owner,
              source + " argument " + i));
    }
    section.typeNodes.set(
        id,
        new JsonSourceModel.TypeNode(
            id,
            JsonMetadataFormat.TYPE_DECLARED,
            token(section, declared),
            ownerNode,
            arguments,
            null,
            -1,
            codecToken,
            codecOperation,
            codecSource));
    return id;
  }

  private int typeParameter(JsonSourceModel.Section section, TypeParameterElement parameter) {
    Map<TypeParameterElement, Integer> indexes =
        typeParameters.computeIfAbsent(section, ignored -> new HashMap<>());
    Integer existing = indexes.get(parameter);
    if (existing != null) {
      return existing;
    }
    int id = section.typeParameters.size();
    indexes.put(parameter, id);
    section.typeParameters.add(null);
    Element generic = parameter.getGenericElement();
    int ownerKind;
    TypeElement owner;
    String memberName = "";
    String descriptor = "";
    if (generic instanceof TypeElement) {
      ownerKind = JsonMetadataFormat.OWNER_TYPE;
      owner = (TypeElement) generic;
    } else {
      ExecutableElement executable = (ExecutableElement) generic;
      ownerKind =
          executable.getKind() == ElementKind.CONSTRUCTOR
              ? JsonMetadataFormat.OWNER_CONSTRUCTOR
              : JsonMetadataFormat.OWNER_METHOD;
      owner = (TypeElement) executable.getEnclosingElement();
      memberName = executable.getSimpleName().toString();
      descriptor = descriptors.executable(executable);
    }
    List<? extends TypeParameterElement> parameters =
        generic instanceof TypeElement
            ? ((TypeElement) generic).getTypeParameters()
            : ((ExecutableElement) generic).getTypeParameters();
    int parameterIndex = parameters.indexOf(parameter);
    List<Integer> bounds = new ArrayList<>();
    for (TypeMirror bound : parameter.getBounds()) {
      bounds.add(typeNode(section, bound, null, parameter, "type parameter " + parameter));
    }
    section.typeParameters.set(
        id,
        new JsonSourceModel.TypeParameter(
            id,
            ownerKind,
            token(section, owner.asType()),
            memberName,
            descriptor,
            parameterIndex,
            bounds));
    return id;
  }

  private int token(JsonSourceModel.Section section, TypeMirror type) {
    if (type.getKind() == TypeKind.ERROR) {
      throw new DeferredTypeException("Unresolved type " + type, target);
    }
    TypeKind kind = type.getKind();
    String key;
    String binaryName;
    int primitive = -1;
    boolean direct;
    if (kind.isPrimitive() || kind == TypeKind.VOID) {
      primitive = primitiveKind(kind);
      key = "P:" + primitive;
      binaryName = type.toString();
      direct = true;
    } else {
      TypeMirror erased = types.erasure(type);
      Element element = types.asElement(erased);
      if (!(element instanceof TypeElement)) {
        throw new ProcessingException("Cannot resolve class token for " + type, target);
      }
      TypeElement typeElement = (TypeElement) element;
      binaryName = descriptors.binaryName(typeElement);
      key = "L:" + binaryName;
      direct = access.type(erased);
    }
    JsonSourceModel.Token existing = section.tokensByKey.get(key);
    if (existing != null) {
      return existing.id;
    }
    JsonSourceModel.Token token =
        new JsonSourceModel.Token(
            section.tokens.size(),
            type,
            binaryName,
            direct,
            direct && primitive < 0 ? section.directTokenCount++ : -1,
            primitive);
    section.tokens.add(token);
    section.tokensByKey.put(key, token);
    return token.id;
  }

  private int operation(
      JsonSourceModel.Section section,
      int shape,
      int direction,
      boolean direct,
      Element member,
      int ownerToken,
      String name,
      String descriptor,
      TypeMirror valueType) {
    TypeElement owner = (TypeElement) member.getEnclosingElement();
    int id = section.operations.size();
    section.operations.add(
        new JsonSourceModel.Operation(
            id,
            shape,
            direction,
            direct,
            direct ? section.directOperationCount++ : -1,
            member,
            ownerToken,
            name,
            descriptor,
            valueType,
            owner.getQualifiedName().toString()));
    return id;
  }

  private int codecOperation(
      JsonSourceModel.Section section, TypeMirror codecType, int codecToken, Element owner) {
    validateCodec(codecType, owner);
    TypeElement codec = (TypeElement) types.asElement(types.erasure(codecType));
    for (JsonSourceModel.Operation operation : section.operations) {
      if (operation.shape == JsonMetadataFormat.CODEC_FACTORY
          && operation.ownerSourceName.equals(codec.getQualifiedName().toString())) {
        return operation.id;
      }
    }
    ExecutableElement constructor = null;
    for (ExecutableElement candidate : ElementFilter.constructorsIn(codec.getEnclosedElements())) {
      if (candidate.getParameters().isEmpty()
          && candidate.getModifiers().contains(javax.lang.model.element.Modifier.PUBLIC)) {
        constructor = candidate;
        break;
      }
    }
    if (constructor == null) {
      fail("JSON codec must have a public no-argument constructor: " + codec, owner);
    }
    return operation(
        section,
        JsonMetadataFormat.CODEC_FACTORY,
        JsonMetadataFormat.READ | JsonMetadataFormat.WRITE,
        true,
        constructor,
        codecToken,
        "<init>",
        "()V",
        codecType);
  }

  private TypeMirror requiredCodecType(AnnotationMirror codec, Element owner) {
    TypeMirror type = annotations.type(codec, "value");
    if (type == null || type.getKind() == TypeKind.ERROR) {
      throw new DeferredTypeException("Unresolved @JsonCodec value", owner);
    }
    validateCodec(type, owner);
    return type;
  }

  private void validateCodec(TypeMirror codecType, Element owner) {
    TypeElement codec = (TypeElement) types.asElement(types.erasure(codecType));
    if (codec == null) {
      throw new DeferredTypeException("Unresolved @JsonCodec value " + codecType, owner);
    }
    if (!codec.getModifiers().contains(javax.lang.model.element.Modifier.PUBLIC)
        || codec.getModifiers().contains(javax.lang.model.element.Modifier.ABSTRACT)
        || codec.getKind() != ElementKind.CLASS) {
      fail("JSON codec must be a public concrete class: " + codec, owner);
    }
    for (Element current = codec.getEnclosingElement();
        current instanceof TypeElement;
        current = current.getEnclosingElement()) {
      if (!current.getModifiers().contains(javax.lang.model.element.Modifier.PUBLIC)) {
        fail("JSON codec must be enclosed only by public classes: " + codec, owner);
      }
    }
    boolean constructor = false;
    for (ExecutableElement candidate : ElementFilter.constructorsIn(codec.getEnclosedElements())) {
      if (candidate.getParameters().isEmpty()
          && candidate.getModifiers().contains(javax.lang.model.element.Modifier.PUBLIC)) {
        constructor = true;
      }
    }
    if (!constructor) {
      fail("JSON codec must have a public no-argument constructor: " + codec, owner);
    }
  }

  private JsonSourceModel.Property property(Element element) {
    AnnotationMirror property = annotations.find(element, JSON_PROPERTY);
    if (property == null) {
      return new JsonSourceModel.Property(false, "", -1, 0);
    }
    int index = annotations.integer(property, "index", -1);
    if (index < -1) {
      fail("Invalid @JsonProperty index " + index, element);
    }
    return new JsonSourceModel.Property(
        true,
        annotations.string(property, "value", ""),
        index,
        annotations.enumOrdinal(property, "include", 0));
  }

  private int methodShape(ExecutableElement method, boolean anyGetter, boolean anySetter) {
    if (anySetter) {
      if (method.getParameters().size() != 2
          || method.getReturnType().getKind() != TypeKind.VOID
          || method.isVarArgs()
          || !method.getTypeParameters().isEmpty()
          || !types
              .erasure(method.getParameters().get(0).asType())
              .toString()
              .equals("java.lang.String")) {
        fail("Invalid @JsonAnySetter method " + method, method);
      }
      return JsonMetadataFormat.ANY_SETTER;
    }
    if (anyGetter) {
      if (!method.getParameters().isEmpty()
          || method.getReturnType().getKind() == TypeKind.VOID
          || method.isVarArgs()
          || !method.getTypeParameters().isEmpty()
          || !types.isAssignable(
              types.erasure(method.getReturnType()),
              types.erasure(elements.getTypeElement("java.util.Map").asType()))) {
        fail("Invalid @JsonAnyGetter method " + method, method);
      }
      return JsonMetadataFormat.GETTER;
    }
    String name = method.getSimpleName().toString();
    if (RecordElements.isRecord(target) && isRecordAccessor(method)) {
      return JsonMetadataFormat.GETTER;
    }
    if (method.getParameters().isEmpty()
        && method.getReturnType().getKind() != TypeKind.VOID
        && !types.erasure(method.getReturnType()).toString().equals("java.lang.Class")
        && (name.startsWith("get") && name.length() > 3
            || name.startsWith("is")
                && name.length() > 2
                && (method.getReturnType().getKind() == TypeKind.BOOLEAN
                    || types
                        .erasure(method.getReturnType())
                        .toString()
                        .equals("java.lang.Boolean")))) {
      return JsonMetadataFormat.GETTER;
    }
    if (method.getParameters().size() == 1
        && method.getReturnType().getKind() == TypeKind.VOID
        && name.startsWith("set")
        && name.length() > 3) {
      return JsonMetadataFormat.SETTER;
    }
    return -1;
  }

  private boolean isRecordAccessor(ExecutableElement method) {
    if (!method.getParameters().isEmpty() || method.getReturnType().getKind() == TypeKind.VOID) {
      return false;
    }
    String name = method.getSimpleName().toString();
    for (Element component : RecordElements.components(target)) {
      if (component.getSimpleName().contentEquals(name)
          && types.isSameType(
              types.erasure(component.asType()), types.erasure(method.getReturnType()))) {
        return true;
      }
    }
    return false;
  }

  private void validateCreator(ExecutableElement creator) {
    Set<javax.lang.model.element.Modifier> modifiers = creator.getModifiers();
    if (!modifiers.contains(javax.lang.model.element.Modifier.PUBLIC)
        || creator.isVarArgs()
        || creator.getParameters().isEmpty()
        || !creator.getTypeParameters().isEmpty()) {
      fail("Invalid @JsonCreator executable " + creator, creator);
    }
    if (creator.getKind() == ElementKind.METHOD
        && (!modifiers.contains(javax.lang.model.element.Modifier.STATIC)
            || !types.isSameType(
                types.erasure(creator.getReturnType()), types.erasure(target.asType())))) {
      fail("Invalid @JsonCreator factory " + creator, creator);
    }
  }

  private List<TypeElement> hierarchy(TypeElement type) {
    List<TypeElement> result = new ArrayList<>();
    TypeElement current = type;
    while (current != null && !current.getQualifiedName().contentEquals("java.lang.Object")) {
      result.add(current);
      TypeMirror superclass = current.getSuperclass();
      Element next = superclass == null ? null : types.asElement(superclass);
      current = next instanceof TypeElement ? (TypeElement) next : null;
    }
    return result;
  }

  private int declarationKind(TypeElement declaration) {
    if (RecordElements.isRecord(declaration)) {
      return JsonMetadataFormat.DECL_RECORD;
    }
    if (declaration.getKind() == ElementKind.INTERFACE) {
      return JsonMetadataFormat.DECL_INTERFACE;
    }
    if (declaration.getKind() == ElementKind.ENUM) {
      return JsonMetadataFormat.DECL_ENUM;
    }
    if (declaration.getModifiers().contains(javax.lang.model.element.Modifier.ABSTRACT)) {
      return JsonMetadataFormat.DECL_ABSTRACT;
    }
    return JsonMetadataFormat.DECL_CLASS;
  }

  private static int primitiveKind(TypeKind kind) {
    switch (kind) {
      case BOOLEAN:
        return JsonMetadataFormat.BOOLEAN;
      case BYTE:
        return JsonMetadataFormat.BYTE;
      case SHORT:
        return JsonMetadataFormat.SHORT;
      case INT:
        return JsonMetadataFormat.INT;
      case LONG:
        return JsonMetadataFormat.LONG;
      case FLOAT:
        return JsonMetadataFormat.FLOAT;
      case DOUBLE:
        return JsonMetadataFormat.DOUBLE;
      case CHAR:
        return JsonMetadataFormat.CHAR;
      case VOID:
        return JsonMetadataFormat.VOID;
      default:
        throw new IllegalArgumentException("Not a primitive type " + kind);
    }
  }

  private static int modifiers(Element element) {
    int result = 0;
    Set<javax.lang.model.element.Modifier> modifiers = element.getModifiers();
    if (modifiers.contains(javax.lang.model.element.Modifier.PUBLIC)) {
      result |= Modifier.PUBLIC;
    }
    if (modifiers.contains(javax.lang.model.element.Modifier.PROTECTED)) {
      result |= Modifier.PROTECTED;
    }
    if (modifiers.contains(javax.lang.model.element.Modifier.PRIVATE)) {
      result |= Modifier.PRIVATE;
    }
    if (modifiers.contains(javax.lang.model.element.Modifier.STATIC)) {
      result |= Modifier.STATIC;
    }
    if (modifiers.contains(javax.lang.model.element.Modifier.FINAL)) {
      result |= Modifier.FINAL;
    }
    if (modifiers.contains(javax.lang.model.element.Modifier.ABSTRACT)) {
      result |= Modifier.ABSTRACT;
    }
    if (modifiers.contains(javax.lang.model.element.Modifier.TRANSIENT)) {
      result |= Modifier.TRANSIENT;
    }
    if (modifiers.contains(javax.lang.model.element.Modifier.VOLATILE)) {
      result |= Modifier.VOLATILE;
    }
    return result;
  }

  private static void fail(String message, Element element) {
    throw new ProcessingException(message, element);
  }

  static final class DeferredTypeException extends RuntimeException {
    final Element element;

    DeferredTypeException(String message, Element element) {
      super(message);
      this.element = element;
    }
  }
}
