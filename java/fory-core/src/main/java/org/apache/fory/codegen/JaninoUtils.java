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

package org.apache.fory.codegen;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.apache.fory.annotation.Internal;
import org.apache.fory.collection.Tuple2;
import org.apache.fory.logging.Logger;
import org.apache.fory.logging.LoggerFactory;
import org.apache.fory.platform.AndroidSupport;
import org.apache.fory.reflect.ReflectionUtils;
import org.apache.fory.util.StringUtils;
import org.codehaus.commons.compiler.util.reflect.ByteArrayClassLoader;
import org.codehaus.commons.compiler.util.resource.MapResourceCreator;
import org.codehaus.commons.compiler.util.resource.MapResourceFinder;
import org.codehaus.commons.compiler.util.resource.Resource;
import org.codehaus.janino.ClassLoaderIClassLoader;
import org.codehaus.janino.Compiler;
import org.codehaus.janino.MethodDescriptor;
import org.codehaus.janino.util.ClassFile;

/** A util to compile code to bytecode and create classloader to load generated class. */
public class JaninoUtils {
  private static final Logger LOG = LoggerFactory.getLogger(JaninoUtils.class);

  /**
   * One verified direct JVM invocation used to replace a source placeholder after Janino has
   * compiled the containing class.
   *
   * <p>The generated-codec bridge is an instance method with a source-nameable signature; the
   * target member need not be source-nameable. Parameter index {@code -1} supplies a JVM null
   * constant. All other indexes select one bridge parameter.
   */
  @Internal
  public static final class DirectInvocation {
    private final String bridgeName;
    private final Class<?> returnType;
    private final Class<?>[] parameterTypes;
    private final Executable target;
    private final int receiverIndex;
    private final int[] argumentIndexes;

    private DirectInvocation(
        String bridgeName,
        Class<?> returnType,
        Class<?>[] parameterTypes,
        Executable target,
        int receiverIndex,
        int[] argumentIndexes) {
      this.bridgeName = bridgeName;
      this.returnType = returnType;
      this.parameterTypes = parameterTypes.clone();
      this.target = target;
      this.receiverIndex = receiverIndex;
      this.argumentIndexes = argumentIndexes.clone();
      validateDirectInvocation(this);
    }

    /** Creates a bridge which invokes one exact constructor. */
    public static DirectInvocation constructor(
        String bridgeName,
        Class<?>[] parameterTypes,
        Constructor<?> target,
        int... argumentIndexes) {
      return new DirectInvocation(
          bridgeName, target.getDeclaringClass(), parameterTypes, target, -1, argumentIndexes);
    }

    /** Creates a bridge which invokes one exact method. */
    public static DirectInvocation method(
        String bridgeName,
        Class<?> returnType,
        Class<?>[] parameterTypes,
        Method target,
        int receiverIndex,
        int... argumentIndexes) {
      return new DirectInvocation(
          bridgeName, returnType, parameterTypes, target, receiverIndex, argumentIndexes);
    }

    public String bridgeName() {
      return bridgeName;
    }

    public String descriptor() {
      return methodDescriptor(returnType, parameterTypes);
    }

    /** Returns whether two bridge declarations invoke the same exact verified target shape. */
    public boolean sameTarget(DirectInvocation other) {
      return other != null
          && returnType == other.returnType
          && target.equals(other.target)
          && receiverIndex == other.receiverIndex
          && Arrays.equals(parameterTypes, other.parameterTypes)
          && Arrays.equals(argumentIndexes, other.argumentIndexes);
    }
  }

  public static Class<?> compileClass(
      ClassLoader loader, String pkg, String className, String code) {
    ByteArrayClassLoader classLoader = compile(loader, new CompileUnit(pkg, className, code));
    try {
      return classLoader.loadClass(StringUtils.isBlank(pkg) ? className : pkg + "." + className);
    } catch (ClassNotFoundException e) {
      throw new RuntimeException(e);
    }
  }

  public static ByteArrayClassLoader compile(
      ClassLoader parentClassLoader, CompileUnit... compileUnits) {
    final Map<String, byte[]> classes = toBytecode(parentClassLoader, compileUnits);
    // Set up a class loader that finds and defined the generated classes.
    return new ByteArrayClassLoader(classes, parentClassLoader);
  }

  public static Map<String, byte[]> toBytecode(
      ClassLoader parentClassLoader, CompileUnit... compileUnits) {
    return toBytecode(parentClassLoader, CodeGenerator.getCodeDir(), compileUnits);
  }

  public static Map<String, byte[]> toBytecode(
      ClassLoader parentClassLoader, String codeDir, CompileUnit... compileUnits) {
    if (AndroidSupport.IS_ANDROID) {
      throw new UnsupportedOperationException(
          "Fory runtime code generation is unsupported on Android; "
              + "interpreter serializers must be used.");
    }
    MapResourceFinder sourceFinder = new MapResourceFinder();
    for (CompileUnit unit : compileUnits) {
      String stubFileName = unit.pkg.replace(".", "/") + "/" + unit.mainClassName + ".java";
      sourceFinder.addResource(stubFileName, unit.getCode());

      if (StringUtils.isNotBlank(codeDir)) {
        Path path = Paths.get(codeDir, stubFileName).toAbsolutePath();
        try {
          path.getParent().toFile().mkdirs();
          if (CodeGenerator.deleteCodeOnExit()) {
            path.toFile().deleteOnExit();
          } else {
            LOG.info("Write generate class {} to file {}", stubFileName, path);
          }
          Files.write(path, unit.getCode().getBytes());
        } catch (IOException e) {
          throw new RuntimeException(String.format("Write code file %s failed", path), e);
        }
      }
    }

    long startTime = System.nanoTime();
    // Storage for generated bytecode
    final Map<String, byte[]> classes = new HashMap<>();
    // Set up the compiler.
    ClassLoaderIClassLoader classLoader = new ClassLoaderIClassLoader(parentClassLoader);
    Compiler compiler = new Compiler(sourceFinder, classLoader);
    compiler.setClassFileCreator(new MapResourceCreator(classes));
    compiler.setClassFileFinder(new MapResourceFinder(classes));

    // set debug flag to get source file names and line numbers for debug and stacktrace.
    // this is also the default behaviour for javac.
    compiler.setDebugSource(true);
    compiler.setDebugLines(true);

    // Compile all sources
    try {
      compiler.compile(sourceFinder.resources().toArray(new Resource[0]));
      long durationMs = (System.nanoTime() - startTime) / 1000_000;
      String classNames =
          Arrays.stream(compileUnits)
              .map(unit -> unit.mainClassName)
              .collect(Collectors.joining(", ", "[", "]"));
      LOG.info("Compile {} take {} ms", classNames, durationMs);
    } catch (Exception e) {
      StringBuilder msgBuilder = new StringBuilder("Compile error: \n");
      for (int i = 0; i < compileUnits.length; i++) {
        CompileUnit unit = compileUnits[i];
        if (i != 0) {
          msgBuilder.append('\n');
        }
        String qualifiedName = unit.pkg + "." + unit.mainClassName;
        msgBuilder.append(qualifiedName).append(":\n");
        msgBuilder.append(CodeFormatter.format(unit.getCode()));
      }
      throw new CodegenException(msgBuilder.toString(), e);
    }
    // See https://github.com/janino-compiler/janino/issues/173
    ReflectionUtils.setObjectFieldValue(classLoader, "classLoader", null);
    ReflectionUtils.setObjectFieldValue(classLoader, "loadedIClasses", new HashMap<>());
    return classes;
  }

  /** Replaces verified source placeholders with straight-line direct JVM invocations. */
  @Internal
  public static byte[] installDirectInvocations(
      byte[] classBytes, DirectInvocation... invocations) {
    if (invocations.length == 0) {
      return classBytes;
    }
    try {
      ClassFile classFile = new ClassFile(new ByteArrayInputStream(classBytes));
      ReflectionUtils.setObjectFieldValue(
          classFile, "methodInfos", new ArrayList<>(classFile.methodInfos));
      for (DirectInvocation invocation : invocations) {
        installDirectInvocation(classFile, invocation);
      }
      return classFile.toByteArray();
    } catch (IOException e) {
      throw new CodegenException("Cannot rewrite generated direct invocation", e);
    }
  }

  private static void installDirectInvocation(ClassFile classFile, DirectInvocation invocation) {
    String descriptor = invocation.descriptor();
    ClassFile.MethodInfo sourceMethod = null;
    for (ClassFile.MethodInfo method : classFile.methodInfos) {
      if (method.getName().equals(invocation.bridgeName())
          && method.getDescriptor().equals(descriptor)) {
        if (sourceMethod != null) {
          throw new CodegenException(
              "Duplicate generated direct invocation " + invocation.bridgeName());
        }
        sourceMethod = method;
      }
    }
    if (sourceMethod == null) {
      throw new CodegenException(
          "Missing generated direct invocation "
              + invocation.bridgeName()
              + descriptor
              + " in "
              + classFile.getThisClassName());
    }
    if (Modifier.isStatic(sourceMethod.getAccessFlags())) {
      throw new CodegenException(
          "Generated direct invocation bridge must be an instance method "
              + invocation.bridgeName()
              + descriptor);
    }
    classFile.methodInfos.remove(sourceMethod);
    ClassFile.MethodInfo method =
        classFile.addMethodInfo(
            sourceMethod.getAccessFlags(),
            invocation.bridgeName(),
            new MethodDescriptor(descriptor));
    byte[] code = directInvocationCode(classFile, invocation);
    int maxLocals = parameterSlots(invocation.parameterTypes) + 1;
    int targetSlots = parameterSlots(invocation.target.getParameterTypes());
    int invocationStack =
        invocation.target instanceof Constructor
            ? targetSlots + 2
            : targetSlots + (Modifier.isStatic(invocation.target.getModifiers()) ? 0 : 1);
    int maxStack = Math.max(invocationStack, slots(invocation.returnType));
    short codeName = classFile.addConstantUtf8Info("Code");
    method.addAttribute(
        new ClassFile.CodeAttribute(
            codeName,
            (short) maxStack,
            (short) maxLocals,
            code,
            new ClassFile.CodeAttribute.ExceptionTableEntry[0],
            new ClassFile.AttributeInfo[0]));
  }

  private static byte[] directInvocationCode(ClassFile classFile, DirectInvocation invocation) {
    ByteArrayOutputStream code = new ByteArrayOutputStream();
    Executable target = invocation.target;
    if (target instanceof Constructor) {
      short owner = classFile.addConstantClassInfo(typeDescriptor(target.getDeclaringClass()));
      writeOpcodeIndex(code, 0xbb, owner); // new
      code.write(0x59); // dup
    } else if (!Modifier.isStatic(target.getModifiers())) {
      writeLoad(
          code,
          invocation.parameterTypes[invocation.receiverIndex],
          localIndex(invocation, invocation.receiverIndex));
    }
    Class<?>[] targetParameters = target.getParameterTypes();
    for (int i = 0; i < targetParameters.length; i++) {
      int source = invocation.argumentIndexes[i];
      if (source < 0) {
        code.write(0x01); // aconst_null
      } else {
        writeLoad(code, invocation.parameterTypes[source], localIndex(invocation, source));
      }
    }
    Class<?> ownerType = target.getDeclaringClass();
    String ownerDescriptor = typeDescriptor(ownerType);
    String targetName = target instanceof Constructor ? "<init>" : target.getName();
    String targetDescriptor =
        methodDescriptor(
            target instanceof Method ? ((Method) target).getReturnType() : void.class,
            targetParameters);
    short reference;
    int opcode;
    if (target instanceof Constructor) {
      reference = classFile.addConstantMethodrefInfo(ownerDescriptor, targetName, targetDescriptor);
      opcode = 0xb7; // invokespecial
    } else if (Modifier.isStatic(target.getModifiers())) {
      reference =
          ownerType.isInterface()
              ? classFile.addConstantInterfaceMethodrefInfo(
                  ownerDescriptor, targetName, targetDescriptor)
              : classFile.addConstantMethodrefInfo(ownerDescriptor, targetName, targetDescriptor);
      opcode = 0xb8; // invokestatic
    } else if (ownerType.isInterface()) {
      reference =
          classFile.addConstantInterfaceMethodrefInfo(
              ownerDescriptor, targetName, targetDescriptor);
      opcode = 0xb9; // invokeinterface
    } else {
      reference = classFile.addConstantMethodrefInfo(ownerDescriptor, targetName, targetDescriptor);
      opcode = 0xb6; // invokevirtual
    }
    writeOpcodeIndex(code, opcode, reference);
    if (opcode == 0xb9) {
      code.write(parameterSlots(targetParameters) + 1);
      code.write(0);
    }
    writeReturn(code, invocation.returnType);
    return code.toByteArray();
  }

  private static void validateDirectInvocation(DirectInvocation invocation) {
    if (invocation.bridgeName.isEmpty()) {
      throw new IllegalArgumentException("Direct invocation bridge name is empty");
    }
    Class<?>[] targetParameters = invocation.target.getParameterTypes();
    if (targetParameters.length != invocation.argumentIndexes.length) {
      throw new IllegalArgumentException("Direct invocation argument shape does not match target");
    }
    if (invocation.target instanceof Constructor) {
      if (invocation.returnType != invocation.target.getDeclaringClass()
          || invocation.receiverIndex != -1) {
        throw new IllegalArgumentException("Invalid direct constructor bridge shape");
      }
    } else {
      Method method = (Method) invocation.target;
      if (invocation.returnType != method.getReturnType()) {
        throw new IllegalArgumentException(
            "Direct method bridge return type does not match target");
      }
      if (Modifier.isStatic(method.getModifiers())) {
        if (invocation.receiverIndex != -1) {
          throw new IllegalArgumentException("Static direct method cannot have a receiver");
        }
      } else if (invocation.receiverIndex < 0
          || invocation.receiverIndex >= invocation.parameterTypes.length
          || !method
              .getDeclaringClass()
              .isAssignableFrom(invocation.parameterTypes[invocation.receiverIndex])) {
        throw new IllegalArgumentException("Invalid direct method receiver");
      }
    }
    for (int i = 0; i < targetParameters.length; i++) {
      int source = invocation.argumentIndexes[i];
      if (source < 0) {
        if (targetParameters[i].isPrimitive()) {
          throw new IllegalArgumentException("Null cannot supply a primitive direct argument");
        }
      } else if (source >= invocation.parameterTypes.length
          || invocation.parameterTypes[source] != targetParameters[i]) {
        throw new IllegalArgumentException("Direct invocation argument type does not match target");
      }
    }
  }

  private static int localIndex(DirectInvocation invocation, int parameterIndex) {
    // Direct placeholders are generated-codec instance methods. Target staticness affects only the
    // invocation opcode; local slot zero always remains the generated-codec receiver.
    int index = 1;
    for (int i = 0; i < parameterIndex; i++) {
      index += slots(invocation.parameterTypes[i]);
    }
    return index;
  }

  private static int parameterSlots(Class<?>[] types) {
    int slots = 0;
    for (Class<?> type : types) {
      slots += slots(type);
    }
    return slots;
  }

  private static int slots(Class<?> type) {
    return type == long.class || type == double.class ? 2 : 1;
  }

  private static void writeLoad(ByteArrayOutputStream code, Class<?> type, int index) {
    int opcode;
    if (!type.isPrimitive()) {
      opcode = 0x19; // aload
    } else if (type == long.class) {
      opcode = 0x16; // lload
    } else if (type == float.class) {
      opcode = 0x17; // fload
    } else if (type == double.class) {
      opcode = 0x18; // dload
    } else {
      opcode = 0x15; // iload
    }
    int compactBase;
    switch (opcode) {
      case 0x15:
        compactBase = 0x1a;
        break;
      case 0x16:
        compactBase = 0x1e;
        break;
      case 0x17:
        compactBase = 0x22;
        break;
      case 0x18:
        compactBase = 0x26;
        break;
      default:
        compactBase = 0x2a;
    }
    if (index <= 3) {
      code.write(compactBase + index);
    } else if (index <= 255) {
      code.write(opcode);
      code.write(index);
    } else {
      code.write(0xc4); // wide
      code.write(opcode);
      writeShort(code, index);
    }
  }

  private static void writeOpcodeIndex(ByteArrayOutputStream code, int opcode, short index) {
    code.write(opcode);
    writeShort(code, index & 0xffff);
  }

  private static void writeShort(ByteArrayOutputStream code, int value) {
    code.write(value >>> 8);
    code.write(value);
  }

  private static void writeReturn(ByteArrayOutputStream code, Class<?> type) {
    if (type == void.class) {
      code.write(0xb1);
    } else if (!type.isPrimitive()) {
      code.write(0xb0);
    } else if (type == long.class) {
      code.write(0xad);
    } else if (type == float.class) {
      code.write(0xae);
    } else if (type == double.class) {
      code.write(0xaf);
    } else {
      code.write(0xac);
    }
  }

  private static String methodDescriptor(Class<?> returnType, Class<?>[] parameterTypes) {
    StringBuilder descriptor = new StringBuilder("(");
    for (Class<?> parameterType : parameterTypes) {
      descriptor.append(typeDescriptor(parameterType));
    }
    return descriptor.append(')').append(typeDescriptor(returnType)).toString();
  }

  private static String typeDescriptor(Class<?> type) {
    if (type.isPrimitive()) {
      if (type == void.class) {
        return "V";
      }
      if (type == boolean.class) {
        return "Z";
      }
      if (type == byte.class) {
        return "B";
      }
      if (type == char.class) {
        return "C";
      }
      if (type == short.class) {
        return "S";
      }
      if (type == int.class) {
        return "I";
      }
      if (type == long.class) {
        return "J";
      }
      if (type == float.class) {
        return "F";
      }
      if (type == double.class) {
        return "D";
      }
      throw new AssertionError(type);
    }
    if (type.isArray()) {
      return type.getName().replace('.', '/');
    }
    return 'L' + type.getName().replace('.', '/') + ';';
  }

  public static class CodeStats {
    public final Map<String, Integer> methodsSize;
    public final int constPoolSize;

    public CodeStats(Map<String, Integer> methodsSize, int constPoolSize) {
      this.methodsSize = methodsSize;
      this.constPoolSize = constPoolSize;
    }

    @Override
    public String toString() {
      return "CodeStats{" + "methodsSize=" + methodsSize + ", constPoolSize=" + constPoolSize + '}';
    }
  }

  public static CodeStats getClassStats(Class<?> cls) {
    try (InputStream stream =
            cls.getResourceAsStream(ReflectionUtils.getClassNameWithoutPackage(cls) + ".class");
        BufferedInputStream bis = new BufferedInputStream(Objects.requireNonNull(stream))) {
      byte[] bytecodes = new byte[stream.available()];
      bis.read(bytecodes);
      return getClassStats(bytecodes);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public static CodeStats getClassStats(byte[] classBytes) {
    try {
      ClassFile classFile = new ClassFile(new ByteArrayInputStream(classBytes));
      int constPoolSize = classFile.getConstantPoolSize();
      Class<?> codeAttrClass =
          Compiler.class
              .getClassLoader()
              .loadClass("org.codehaus.janino.util.ClassFile$CodeAttribute");
      Field codeAttrField = codeAttrClass.getDeclaredField("code");
      codeAttrField.setAccessible(true);
      Map<String, Integer> methodSizes = new LinkedHashMap<>();
      classFile.methodInfos.stream()
          .flatMap(
              m ->
                  Arrays.stream(m.getAttributes())
                      .filter(attr -> attr.getClass() == codeAttrClass)
                      .map(
                          attr -> {
                            try {
                              Object codeAttr = codeAttrField.get(attr);
                              int length = ((byte[]) codeAttr).length;
                              if (length > CodeGenerator.DEFAULT_JVM_HUGE_METHOD_LIMIT) {
                                LOG.info(
                                    "Generated method too long to be JIT compiled:"
                                        + " class {} method {} size {}",
                                    classFile.getThisClassName(),
                                    m.getName(),
                                    length);
                                // } else if (length > CodeGenerator.DEFAULT_JVM_INLINE_METHOD_LIMIT
                                //     && !"<init>".equals(m.getName())) {
                                //   LOG.info(
                                //       "Generated method too long to be JIT inlined:"
                                //           + " class {} method {} size {}",
                                //       classFile.getThisClassName(),
                                //       m.getName(),
                                //       length);
                              }
                              return Tuple2.of(m.getName(), length);
                            } catch (IllegalAccessException e) {
                              throw new RuntimeException(e);
                            }
                          }))
          .sorted(Comparator.comparingInt(a -> -a.f1))
          .forEach(e -> methodSizes.put(e.f0, e.f1));
      return new CodeStats(methodSizes, constPoolSize);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
