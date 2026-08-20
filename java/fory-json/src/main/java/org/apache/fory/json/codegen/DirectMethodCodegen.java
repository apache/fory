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

package org.apache.fory.json.codegen;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.apache.fory.codegen.JaninoUtils.DirectInvocation;
import org.apache.fory.json.meta.JsonCreatorInfo;

/** Cold source-placeholder and direct-bytecode metadata for non-source-nameable JVM members. */
final class DirectMethodCodegen {
  private static final Set<String> JAVA_KEYWORDS =
      new HashSet<>(
          Arrays.asList(
              "abstract",
              "assert",
              "boolean",
              "break",
              "byte",
              "case",
              "catch",
              "char",
              "class",
              "const",
              "continue",
              "default",
              "do",
              "double",
              "else",
              "enum",
              "extends",
              "final",
              "finally",
              "float",
              "for",
              "goto",
              "if",
              "implements",
              "import",
              "instanceof",
              "int",
              "interface",
              "long",
              "native",
              "new",
              "package",
              "private",
              "protected",
              "public",
              "return",
              "short",
              "static",
              "strictfp",
              "super",
              "switch",
              "synchronized",
              "this",
              "throw",
              "throws",
              "transient",
              "try",
              "void",
              "volatile",
              "while",
              "true",
              "false",
              "null",
              "_"));

  private DirectMethodCodegen() {}

  static boolean sourceNameable(Method method) {
    String name = method.getName();
    if (name.isEmpty()
        || JAVA_KEYWORDS.contains(name)
        || !Character.isJavaIdentifierStart(name.charAt(0))) {
      return false;
    }
    for (int i = 1; i < name.length(); i++) {
      if (!Character.isJavaIdentifierPart(name.charAt(i))) {
        return false;
      }
    }
    return true;
  }

  static String getterName(Method getter) {
    return bridgeName("get", getter);
  }

  static String setterName(Method setter) {
    return bridgeName("set", setter);
  }

  static String fullCreatorName(Executable executable) {
    return bridgeName("create", executable);
  }

  static String defaultCreatorName(Constructor<?> constructor) {
    return bridgeName("defaults", constructor);
  }

  static String valueOperationName(Method method) {
    return bridgeName("value", method);
  }

  static DirectInvocation getterInvocation(Method getter) {
    return DirectInvocation.method(
        getterName(getter),
        getter.getReturnType(),
        new Class<?>[] {getter.getDeclaringClass()},
        getter,
        0);
  }

  static DirectInvocation setterInvocation(Method setter) {
    return DirectInvocation.method(
        setterName(setter),
        void.class,
        new Class<?>[] {setter.getDeclaringClass(), setter.getParameterTypes()[0]},
        setter,
        0,
        1);
  }

  static DirectInvocation anySetterInvocation(Method setter) {
    Class<?>[] targetParameters = setter.getParameterTypes();
    Class<?>[] bridgeParameters = new Class<?>[targetParameters.length + 1];
    bridgeParameters[0] = setter.getDeclaringClass();
    System.arraycopy(targetParameters, 0, bridgeParameters, 1, targetParameters.length);
    int[] arguments = new int[targetParameters.length];
    for (int i = 0; i < arguments.length; i++) {
      arguments[i] = i + 1;
    }
    return DirectInvocation.method(
        setterName(setter), void.class, bridgeParameters, setter, 0, arguments);
  }

  static DirectInvocation valueOperationInvocation(Method method) {
    Class<?>[] targetParameters = method.getParameterTypes();
    if (Modifier.isStatic(method.getModifiers())) {
      int[] arguments = new int[targetParameters.length];
      for (int i = 0; i < arguments.length; i++) {
        arguments[i] = i;
      }
      return DirectInvocation.method(
          valueOperationName(method),
          method.getReturnType(),
          targetParameters,
          method,
          -1,
          arguments);
    }
    Class<?>[] bridgeParameters = new Class<?>[targetParameters.length + 1];
    bridgeParameters[0] = method.getDeclaringClass();
    System.arraycopy(targetParameters, 0, bridgeParameters, 1, targetParameters.length);
    int[] arguments = new int[targetParameters.length];
    for (int i = 0; i < arguments.length; i++) {
      arguments[i] = i + 1;
    }
    return DirectInvocation.method(
        valueOperationName(method), method.getReturnType(), bridgeParameters, method, 0, arguments);
  }

  static DirectInvocation constructorInvocation(
      String name, Class<?>[] bridgeParameters, Constructor<?> constructor, int[] targetArguments) {
    return DirectInvocation.constructor(name, bridgeParameters, constructor, targetArguments);
  }

  static boolean requiresFullCreatorBridge(JsonCreatorInfo creator) {
    Executable executable = creator.executable();
    Executable target = creator.invocationExecutable();
    return creator.defaultConstructor() != null
        || executable != target
        || !java.lang.reflect.Modifier.isPublic(target.getModifiers())
        || target instanceof Method && !sourceNameable((Method) target);
  }

  static DirectInvocation fullCreatorInvocation(JsonCreatorInfo creator) {
    Executable executable = creator.executable();
    Executable target = creator.invocationExecutable();
    Class<?>[] parameters = executable.getParameterTypes();
    int[] arguments = invocationArguments(parameters.length, target.getParameterCount());
    if (target instanceof Constructor) {
      return constructorInvocation(
          fullCreatorName(target), parameters, (Constructor<?>) target, arguments);
    }
    Method method = (Method) target;
    return DirectInvocation.method(
        fullCreatorName(target), method.getReturnType(), parameters, method, -1, arguments);
  }

  static DirectInvocation defaultCreatorInvocation(JsonCreatorInfo creator) {
    Constructor<?> target = creator.defaultConstructor();
    Class<?>[] logical = creator.executable().getParameterTypes();
    Class<?>[] parameters = Arrays.copyOf(logical, logical.length + creator.defaultMaskCount());
    Arrays.fill(parameters, logical.length, parameters.length, int.class);
    return constructorInvocation(
        defaultCreatorName(target),
        parameters,
        target,
        invocationArguments(parameters.length, target.getParameterCount()));
  }

  private static int[] invocationArguments(int supplied, int targetCount) {
    if (targetCount < supplied || targetCount > supplied + 1) {
      throw new IllegalArgumentException("Invalid generated creator invocation shape");
    }
    int[] arguments = new int[targetCount];
    for (int i = 0; i < supplied; i++) {
      arguments[i] = i;
    }
    if (targetCount != supplied) {
      arguments[targetCount - 1] = -1;
    }
    return arguments;
  }

  private static String bridgeName(String role, Executable executable) {
    StringBuilder identity =
        new StringBuilder(role)
            .append(':')
            .append(executable.getDeclaringClass().getName())
            .append(':')
            .append(executable instanceof Constructor ? "<init>" : executable.getName())
            .append('(');
    for (Class<?> type : executable.getParameterTypes()) {
      identity.append(type.getName()).append(';');
    }
    if (executable instanceof Method) {
      identity.append(')').append(((Method) executable).getReturnType().getName());
    }
    byte[] digest;
    try {
      digest =
          MessageDigest.getInstance("SHA-256")
              .digest(identity.toString().getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new ExceptionInInitializerError(e);
    }
    StringBuilder name = new StringBuilder("fory_").append(role).append('_');
    for (int i = 0; i < 12; i++) {
      int value = digest[i] & 0xff;
      name.append(Character.forDigit(value >>> 4, 16));
      name.append(Character.forDigit(value & 15, 16));
    }
    return name.toString();
  }
}
