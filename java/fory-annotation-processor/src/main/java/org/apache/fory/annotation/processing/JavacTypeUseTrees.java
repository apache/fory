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

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.type.TypeMirror;

/**
 * Reflection-only access to javac type trees without linking compiler APIs into Java 8 bytecode.
 */
final class JavacTypeUseTrees {
  private final Object trees;

  JavacTypeUseTrees(ProcessingEnvironment environment) {
    Object instance = null;
    try {
      ClassLoader loader = environment.getClass().getClassLoader();
      Class<?> treesClass =
          Class.forName(
              "com.sun.source.util.Trees",
              false,
              loader == null ? ClassLoader.getSystemClassLoader() : loader);
      instance =
          treesClass.getMethod("instance", ProcessingEnvironment.class).invoke(null, environment);
    } catch (ReflectiveOperationException | IllegalArgumentException ignored) {
      // TypeMirror annotations remain the source on compilers without javac's public tree API.
    }
    trees = instance;
  }

  Object variableType(Element element) {
    Object leaf = leaf(element);
    return isInstance("com.sun.source.tree.VariableTree", leaf) ? invoke(leaf, "getType") : null;
  }

  Object methodReturnType(ExecutableElement method) {
    Object leaf = leaf(method);
    return isInstance("com.sun.source.tree.MethodTree", leaf)
        ? invoke(leaf, "getReturnType")
        : null;
  }

  Node node(Object tree) {
    List<?> annotations = Collections.emptyList();
    Object current = tree;
    while (isInstance("com.sun.source.tree.AnnotatedTypeTree", current)) {
      annotations = list(invoke(current, "getAnnotations"));
      current = invoke(current, "getUnderlyingType");
    }
    return new Node(annotations, current);
  }

  boolean annotationMatches(Object annotationTree, String annotationName) {
    Object annotationType = invoke(annotationTree, "getAnnotationType");
    if (annotationType == null) {
      return false;
    }
    String treeName = annotationType.toString();
    int index = annotationName.lastIndexOf('.');
    String simpleName = index < 0 ? annotationName : annotationName.substring(index + 1);
    return treeName.equals(annotationName) || treeName.equals(simpleName);
  }

  String annotationValue(Object annotationTree, String name, Object defaultValue) {
    for (Object argument : list(invoke(annotationTree, "getArguments"))) {
      Object valueTree = argument;
      if (isInstance("com.sun.source.tree.AssignmentTree", argument)) {
        Object variable = invoke(argument, "getVariable");
        if (variable == null || !variable.toString().equals(name)) {
          continue;
        }
        valueTree = invoke(argument, "getExpression");
      } else if (!name.equals("value")) {
        continue;
      }
      return valueTree.toString();
    }
    return String.valueOf(defaultValue);
  }

  TypeMirror annotationTypeValue(Element owner, Object annotationTree, String name) {
    Object expression = annotationExpression(annotationTree, name);
    if (isInstance("com.sun.source.tree.MemberSelectTree", expression)
        && "class".equals(String.valueOf(invoke(expression, "getIdentifier")))) {
      expression = invoke(expression, "getExpression");
    }
    if (expression == null || trees == null) {
      return null;
    }
    Object ownerPath = invoke(trees, "getPath", new Class<?>[] {Element.class}, owner);
    try {
      ClassLoader loader = trees.getClass().getClassLoader();
      Class<?> pathType = Class.forName("com.sun.source.util.TreePath", false, loader);
      Class<?> treeType = Class.forName("com.sun.source.tree.Tree", false, loader);
      Object expressionPath =
          pathType.getMethod("getPath", pathType, treeType).invoke(null, ownerPath, expression);
      Object value = invoke(trees, "getTypeMirror", new Class<?>[] {pathType}, expressionPath);
      return value instanceof TypeMirror ? (TypeMirror) value : null;
    } catch (ReflectiveOperationException | RuntimeException e) {
      return null;
    }
  }

  private Object annotationExpression(Object annotationTree, String name) {
    for (Object argument : list(invoke(annotationTree, "getArguments"))) {
      if (isInstance("com.sun.source.tree.AssignmentTree", argument)) {
        Object variable = invoke(argument, "getVariable");
        if (variable == null || !variable.toString().equals(name)) {
          continue;
        }
        return invoke(argument, "getExpression");
      }
      if (name.equals("value")) {
        return argument;
      }
    }
    return null;
  }

  private Object leaf(Element element) {
    if (trees == null) {
      return null;
    }
    Object path = invoke(trees, "getPath", new Class<?>[] {Element.class}, element);
    return path == null ? null : invoke(path, "getLeaf");
  }

  static boolean isInstance(String className, Object value) {
    return value != null && hasType(value.getClass(), className);
  }

  static Object invoke(Object target, String name) {
    return invoke(target, name, new Class<?>[0]);
  }

  static Object invoke(Object target, String name, Class<?>[] parameterTypes, Object... arguments) {
    if (target == null) {
      return null;
    }
    try {
      Method method = target.getClass().getMethod(name, parameterTypes);
      return method.invoke(target, arguments);
    } catch (ReflectiveOperationException e) {
      return null;
    }
  }

  static List<?> list(Object value) {
    return value instanceof List<?> ? (List<?>) value : Collections.emptyList();
  }

  private static boolean hasType(Class<?> type, String className) {
    if (type == null) {
      return false;
    }
    if (type.getName().equals(className)) {
      return true;
    }
    for (Class<?> interfaceType : type.getInterfaces()) {
      if (hasType(interfaceType, className)) {
        return true;
      }
    }
    return hasType(type.getSuperclass(), className);
  }

  static final class Node {
    final List<?> annotations;
    final Object tree;

    Node(List<?> annotations, Object tree) {
      this.annotations = annotations;
      this.tree = tree;
    }

    Object arrayComponent() {
      return isInstance("com.sun.source.tree.ArrayTypeTree", tree) ? invoke(tree, "getType") : null;
    }

    List<?> typeArguments() {
      return isInstance("com.sun.source.tree.ParameterizedTypeTree", tree)
          ? list(invoke(tree, "getTypeArguments"))
          : Collections.emptyList();
    }
  }
}
