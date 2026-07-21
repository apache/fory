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
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;

/** Java 8-compatible access to javac type-use trees omitted from some {@code TypeMirror}s. */
final class JavacTypeUseTrees {
  private final Object trees;
  private final Types types;

  JavacTypeUseTrees(ProcessingEnvironment processingEnv) {
    types = processingEnv.getTypeUtils();
    Object javacTrees;
    try {
      ClassLoader javacLoader = processingEnv.getClass().getClassLoader();
      Class<?> treesClass =
          Class.forName(
              "com.sun.source.util.Trees",
              false,
              javacLoader == null ? ClassLoader.getSystemClassLoader() : javacLoader);
      javacTrees =
          treesClass.getMethod("instance", ProcessingEnvironment.class).invoke(null, processingEnv);
    } catch (ReflectiveOperationException | IllegalArgumentException e) {
      javacTrees = null;
    }
    trees = javacTrees;
  }

  Object typeTree(Element element) {
    if (trees == null) {
      return null;
    }
    Object path = invoke(trees, "getPath", new Class<?>[] {Element.class}, element);
    if (path == null) {
      return null;
    }
    Object leaf = invoke(path, "getLeaf");
    if (isInstance("com.sun.source.tree.VariableTree", leaf)) {
      return invoke(leaf, "getType");
    }
    if (isInstance("com.sun.source.tree.MethodTree", leaf)) {
      return invoke(leaf, "getReturnType");
    }
    return null;
  }

  Tree tree(Object tree) {
    List<?> annotations = Collections.emptyList();
    Object current = tree;
    while (isInstance("com.sun.source.tree.AnnotatedTypeTree", current)) {
      annotations = listValue(invoke(current, "getAnnotations"));
      current = invoke(current, "getUnderlyingType");
    }
    return new Tree(annotations, current);
  }

  boolean isAnnotation(Element owner, Object annotationTree, String annotationName) {
    Object annotationType = invoke(annotationTree, "getAnnotationType");
    if (annotationType == null) {
      return false;
    }
    TypeMirror mirror = treeType(owner, annotationType);
    Element element = mirror == null ? null : types.asElement(mirror);
    if (element instanceof TypeElement) {
      return ((TypeElement) element).getQualifiedName().contentEquals(annotationName);
    }
    // A fully qualified tree remains unambiguous if javac cannot provide a symbol. Never match a
    // simple name here: an imported third-party annotation may use the same name as a Fory one.
    return annotationType.toString().equals(annotationName);
  }

  String annotationValue(Object annotationTree, String name, Object defaultValue) {
    for (Object argument : listValue(invoke(annotationTree, "getArguments"))) {
      Object valueTree = argument;
      if (isInstance("com.sun.source.tree.AssignmentTree", argument)) {
        Object variable = invoke(argument, "getVariable");
        if (variable == null || !variable.toString().equals(name)) {
          continue;
        }
        valueTree = invoke(argument, "getExpression");
      }
      return valueTree.toString();
    }
    return String.valueOf(defaultValue);
  }

  private TypeMirror treeType(Element owner, Object tree) {
    if (trees == null || owner == null || tree == null) {
      return null;
    }
    Object ownerPath = invoke(trees, "getPath", new Class<?>[] {Element.class}, owner);
    Object compilationUnit = invoke(ownerPath, "getCompilationUnit");
    if (compilationUnit == null) {
      return null;
    }
    try {
      Class<?> compilationUnitClass =
          Class.forName(
              "com.sun.source.tree.CompilationUnitTree", false, trees.getClass().getClassLoader());
      Class<?> treeClass =
          Class.forName("com.sun.source.tree.Tree", false, trees.getClass().getClassLoader());
      Class<?> treePathClass =
          Class.forName("com.sun.source.util.TreePath", false, trees.getClass().getClassLoader());
      Object valuePath =
          treePathClass
              .getMethod("getPath", compilationUnitClass, treeClass)
              .invoke(null, compilationUnit, tree);
      Object type = invoke(trees, "getTypeMirror", new Class<?>[] {treePathClass}, valuePath);
      return type instanceof TypeMirror ? (TypeMirror) type : null;
    } catch (ReflectiveOperationException | LinkageError e) {
      return null;
    }
  }

  static final class Tree {
    final List<?> annotations;
    private final Object tree;

    Tree(List<?> annotations, Object tree) {
      this.annotations = annotations;
      this.tree = tree;
    }

    Object arrayComponentTree() {
      if (isInstance("com.sun.source.tree.ArrayTypeTree", tree)) {
        return invoke(tree, "getType");
      }
      return null;
    }

    List<?> typeArgumentTrees() {
      if (isInstance("com.sun.source.tree.ParameterizedTypeTree", tree)) {
        return listValue(invoke(tree, "getTypeArguments"));
      }
      return Collections.emptyList();
    }
  }

  private static boolean isInstance(String className, Object value) {
    return value != null && hasType(value.getClass(), className);
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

  private static Object invoke(Object target, String methodName) {
    return invoke(target, methodName, new Class<?>[0]);
  }

  private static Object invoke(
      Object target, String methodName, Class<?>[] parameterTypes, Object... args) {
    if (target == null) {
      return null;
    }
    try {
      Method method = target.getClass().getMethod(methodName, parameterTypes);
      return method.invoke(target, args);
    } catch (ReflectiveOperationException e) {
      return null;
    }
  }

  private static List<?> listValue(Object value) {
    return value instanceof List<?> ? (List<?>) value : Collections.emptyList();
  }
}
