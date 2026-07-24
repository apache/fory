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

import static java.util.Collections.unmodifiableSet;
import static org.apache.fory.codegen.Code.ExprCode;
import static org.apache.fory.codegen.CodeGenerator.alignIndent;
import static org.apache.fory.codegen.CodeGenerator.indent;
import static org.apache.fory.type.TypeUtils.getArrayType;
import static org.apache.fory.type.TypeUtils.getRawType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.apache.fory.Fory;
import org.apache.fory.codegen.Expression.BaseInvoke;
import org.apache.fory.codegen.Expression.Reference;
import org.apache.fory.collection.Collections;
import org.apache.fory.collection.Tuple2;
import org.apache.fory.logging.Logger;
import org.apache.fory.logging.LoggerFactory;
import org.apache.fory.reflect.ReflectionUtils;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.util.Preconditions;
import org.apache.fory.util.StringUtils;

/**
 * CodegenContext can be an any scope in a class, such as class, method, local and so on.
 *
 * <p>All constructor of generated class will call {@code initialize()} to initialize object. We
 * don't use instance initialize, so user can add init code which depends on used-passed
 * constructor's args.
 */
public class CodegenContext {
  private static final Logger LOG = LoggerFactory.getLogger(CodegenContext.class);

  public static Set<String> JAVA_RESERVED_WORDS;

  static {
    JAVA_RESERVED_WORDS = new HashSet<>();
    JAVA_RESERVED_WORDS.addAll(
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
            "null"));
    JAVA_RESERVED_WORDS = unmodifiableSet(JAVA_RESERVED_WORDS);
  }

  private static Map<String, Map<String, Boolean>> nameConflicts = new ConcurrentHashMap<>();

  Map<String, Long> newValNameIds = new HashMap<>();
  Set<String> valNames = new HashSet<>(JAVA_RESERVED_WORDS);

  /**
   * State used for expression elimination/reuse.
   *
   * <p>Takes the first expression and requests it to generate a Java source code for the expression
   * tree
   *
   * <p>The exprCode's code of subsequent same expression will be null, because the code is already
   * added to current context
   */
  Map<Expression, ExprState> exprState = new HashMap<>();

  String pkg;
  LinkedHashSet<String> imports = new LinkedHashSet<>();
  String className;
  String classModifiers = "public final";
  String[] superClasses;
  String[] interfaces;
  List<FieldInfo> fields = new ArrayList<>();
  private CodegenContext instanceInitCtx;

  /**
   * all initCodes would be placed into a method called initialize(), which will be called by
   * constructor.
   */
  List<String> instanceInitCodes = new ArrayList<>();

  private CodegenContext staticInitCtx;
  List<String> staticInitCodes = new ArrayList<>();

  List<String> constructors = new ArrayList<>();
  LinkedHashMap<String, String> methods = new LinkedHashMap<>();

  public CodegenContext() {}

  public CodegenContext(String pkg, Set<String> valNames, LinkedHashSet<String> imports) {
    this.pkg = pkg;
    this.valNames = valNames;
    this.imports = imports;
  }

  /**
   * Reserve name to avoid name collision for name that not created with {@link
   * CodegenContext#newName(String)}.
   */
  public void reserveName(String name) {
    Preconditions.checkArgument(!valNames.contains(name));
    String s = newName(name);
    Preconditions.checkArgument(s.equals(name));
  }

  public boolean containName(String name) {
    return valNames.contains(name);
  }

  /**
   * If name is a java reserved word, return as if called with name "value".
   *
   * <p>Since we don't pass in TypeToken, no need to consider generics
   */
  public String newName(Class<?> clz) {
    return newName(namePrefix(clz));
  }

  public String newName(Class<?> clz, String suffix) {
    String name = newName(clz);
    return newName(name + suffix);
  }

  /** Returns a term name that is unique within this instance of a `CodegenContext`. */
  public String newName(String name) {
    newValNameIds.putIfAbsent(name, 0L);
    long id = newValNameIds.get(name);
    newValNameIds.put(name, id + 1);
    if (id == 0) {
      if (valNames.add(name)) {
        return name;
      }
    }
    String newName = String.format("%s%s", name, id);
    while (valNames.contains(newName)) {
      id++;
      newValNameIds.put(name, id);
      newName = String.format("%s%s", name, id);
    }
    valNames.add(newName);
    return newName;
  }

  /** Returns two term names that have same suffix to get more readability for generated code. */
  public String[] newNames(Class<?> clz1, String name2) {
    if (clz1.isArray()) {
      return newNames("arr", name2);
    } else {
      return newNames(namePrefix(clz1), name2);
    }
  }

  /**
   * Try to return term names that have same suffixes to get more readability for generated code.
   */
  public String[] newNames(String... names) {
    long id = 0;
    for (String name : names) {
      id = Math.max(id, newValNameIds.getOrDefault(name, 0L));
    }
    for (String name : names) {
      newValNameIds.put(name, id + 1);
    }

    if (id == 0 && !Collections.hasIntersection(valNames, Collections.ofHashSet(names))) {
      valNames.addAll(Arrays.asList(names));
      return names;
    } else {
      String[] newNames = new String[names.length];
      for (int i = 0; i < names.length; i++) {
        newNames[i] = String.format("%s%s", names[i], id);
        while (valNames.contains(newNames[i])) {
          id++;
          newValNameIds.put(newNames[i], id);
          newNames[i] = String.format("%s%s", names[i], id);
        }
      }
      valNames.addAll(Arrays.asList(newNames));
      return newNames;
    }
  }

  public String namePrefix(Class<?> clz) {
    if (clz.isArray()) {
      return "arr";
    } else {
      String type;
      try {
        // getCanonicalName for scala type `A$B$C` may fail
        type = clz.getCanonicalName() != null ? type(clz) : "object";
      } catch (InternalError e) {
        type = "object";
      }
      int index = type.lastIndexOf(".");
      String name;
      if (index >= 0) {
        name = StringUtils.uncapitalize(type.substring(index + 1));
      } else {
        name = StringUtils.uncapitalize(type);
      }
      if (JAVA_RESERVED_WORDS.contains(name)) {
        return "value";
      } else {
        return name;
      }
    }
  }

  /**
   * Get type string.
   *
   * @param clz type
   * @return simple name for class if type's canonical name starts with java.lang or is imported,
   *     return canonical name otherwise.
   */
  public String type(Class<?> clz) {
    if (!sourcePkgLevelAccessible(clz)) {
      clz = Object.class;
    }
    if (clz.isArray()) {
      return getArrayType(clz);
    }
    String type = ReflectionUtils.getLiteralName(clz);
    if (type.startsWith("java.lang")) {
      if (!type.substring("java.lang.".length()).contains(".")) {
        String simpleName = clz.getSimpleName();
        boolean hasPackage = StringUtils.isNotBlank(pkg);
        Map<String, Boolean> packageMap =
            nameConflicts.computeIfAbsent(hasPackage ? pkg : "", p -> new ConcurrentHashMap<>());
        Class<?> c = clz;
        Boolean conflictRes =
            packageMap.computeIfAbsent(
                simpleName,
                sn -> {
                  try {
                    ClassLoader beanClassClassLoader =
                        c.getClassLoader() == null
                            ? Thread.currentThread().getContextClassLoader()
                            : c.getClassLoader();
                    if (beanClassClassLoader == null) {
                      beanClassClassLoader = Fory.class.getClassLoader();
                    }
                    beanClassClassLoader.loadClass(hasPackage ? pkg + "." + sn : sn);
                    return Boolean.TRUE;
                  } catch (ClassNotFoundException e) {
                    return Boolean.FALSE;
                  }
                });
        return conflictRes ? clz.getName() : simpleName;
      }
    }
    if (imports.contains(type)) {
      return clz.getSimpleName();
    } else {
      int index = type.lastIndexOf(".");
      if (index > 0) {
        // This might be package name or qualified name of outer class
        String pkgOrClassName = type.substring(0, index);
        if (imports.contains(pkgOrClassName + ".*")) {
          return clz.getSimpleName();
        }
      }
      return type;
    }
  }

  /** return type name. since janino doesn't generics, we ignore type parameters in typeToken. */
  public String type(TypeRef<?> typeRef) {
    return type(getRawType(typeRef));
  }

  /**
   * Set the generated class's package.
   *
   * @param pkg java package
   */
  public void setPackage(String pkg) {
    this.pkg = pkg;
  }

  public String getPackage() {
    return pkg;
  }

  public Set<String> getValNames() {
    return valNames;
  }

  public LinkedHashSet<String> getImports() {
    return imports;
  }

  /**
   * Import classes.
   *
   * @param classes classes to be imported
   */
  public void addImports(Class<?>... classes) {
    for (Class<?> clz : classes) {
      imports.add(ReflectionUtils.getLiteralName(clz));
    }
  }

  /**
   * Add imports.
   *
   * @param imports import statements
   */
  public void addImports(String... imports) {
    this.imports.addAll(Arrays.asList(imports));
  }

  /**
   * Import class.
   *
   * <p>Import class carefully, otherwise class will conflict. Only java.lang.Class is unique and
   * won't conflict.
   *
   * @param cls class to be imported
   */
  public void addImport(Class<?> cls) {
    this.imports.add(ReflectionUtils.getLiteralName(cls));
  }

  /**
   * Add import.
   *
   * <p>Import class carefully, otherwise class will conflict. Only java.lang.Class is unique and
   * won't conflict.
   *
   * @param im import statement
   */
  public void addImport(String im) {
    this.imports.add(im);
  }

  /**
   * Set class name of class to be generated.
   *
   * @param className class name of class to be generated
   */
  public void setClassName(String className) {
    this.className = className;
  }

  /**
   * Set class modifiers. Default is {@code public final}.
   *
   * @param classModifiers the new class modifiers
   */
  public void setClassModifiers(String classModifiers) {
    this.classModifiers = classModifiers;
  }

  /**
   * Set super classes.
   *
   * @param superClasses super classes
   */
  public void extendsClasses(String... superClasses) {
    this.superClasses = superClasses;
  }

  /**
   * Set implemented interfaces.
   *
   * @param interfaces implemented interfaces
   */
  public void implementsInterfaces(String... interfaces) {
    this.interfaces = interfaces;
  }

  public void addConstructor(String codeBody, Object... params) {
    List<Tuple2<String, String>> parameters = getParameters(params);
    String paramsStr =
        parameters.stream().map(t -> t.f0 + " " + t.f1).collect(Collectors.joining(", "));

    StringBuilder codeBuilder = new StringBuilder(alignIndent(codeBody)).append("\n");
    for (String init : instanceInitCodes) {
      codeBuilder.append(indent(init, 4)).append('\n');
    }
    String constructor =
        StringUtils.format(
            "" + "public ${className}(${paramsStr}) {\n" + "    ${codeBody}" + "}",
            "className",
            className,
            "paramsStr",
            paramsStr,
            "codeBody",
            codeBuilder);
    constructors.add(constructor);
  }

  public void addInitCode(String code) {
    instanceInitCodes.add(code);
  }

  public void addStaticMethod(
      String methodName, String codeBody, Class<?> returnType, Object... params) {
    addMethod("public static", methodName, codeBody, returnType, params);
  }

  public void addMethod(String methodName, String codeBody, Class<?> returnType, Object... params) {
    addMethod("public", methodName, codeBody, returnType, params);
  }

  public void addMethod(
      String modifier, String methodName, String codeBody, Class<?> returnType, Object... params) {
    List<Tuple2<String, String>> parameters = getParameters(params);
    String paramsStr =
        parameters.stream().map(t -> t.f0 + " " + t.f1).collect(Collectors.joining(", "));
    String method =
        StringUtils.format(
            ""
                + "${modifier} ${returnType} ${methodName}(${paramsStr}) {\n"
                + "    ${codeBody}\n"
                + "}\n",
            "modifier",
            modifier,
            "returnType",
            type(returnType),
            "methodName",
            methodName,
            "paramsStr",
            paramsStr,
            "codeBody",
            alignIndent(codeBody));
    String signature = String.format("%s(%s)", methodName, paramsStr);
    if (methods.containsKey(signature)) {
      throw new IllegalStateException(String.format("Duplicated method signature: %s", signature));
    }
    methods.put(signature, method);
  }

  public void overrideMethod(
      String methodName, String codeBody, Class<?> returnType, Object... params) {
    addMethod("@Override public final", methodName, codeBody, returnType, params);
  }

  /**
   * Get parameters.
   *
   * @param args type, value; type, value; type, value; ......
   */
  private List<Tuple2<String, String>> getParameters(Object... args) {
    Preconditions.checkArgument(args.length % 2 == 0);
    List<Tuple2<String, String>> params = new ArrayList<>(0);
    for (int i = 0; i < args.length; i += 2) {
      String type;
      if (args[i] instanceof Class) {
        type = type(((Class<?>) args[i]));
      } else {
        type = args[i].toString();
      }
      params.add(Tuple2.of(type, args[i + 1].toString()));
    }
    return params;
  }

  /** Returns true if class has field with name {@code fieldName}. */
  public boolean hasField(String fieldName) {
    for (FieldInfo field : fields) {
      if (fieldName.equals(field.fieldName)) {
        return true;
      }
    }
    return false;
  }

  private List<FieldInfo> getFieldsInfo(boolean isStatic) {
    return fields.stream().filter(f -> f.isStatic == isStatic).collect(Collectors.toList());
  }

  /**
   * Add a field to class. The init code should be placed in constructor's code
   *
   * @param type type
   * @param fieldName field name
   */
  public void addField(Class<?> type, String fieldName) {
    addField(type(type), fieldName);
  }

  public void addField(String type, String fieldName) {
    addField(false, type, fieldName, null);
  }

  /**
   * Add a field to class.
   *
   * @param type type
   * @param fieldName field name
   * @param initExpr field init expression
   */
  public void addField(Class<?> type, String fieldName, Expression initExpr) {
    addField(type(type), fieldName, initExpr);
  }

  public void addField(String type, String fieldName, Expression initExpr) {
    addField(true, type, fieldName, initExpr);
  }

  /**
   * Add a field to class.
   *
   * @param type type
   * @param fieldName field name
   * @param initExpr field init expression
   */
  public void addField(boolean isFinalField, String type, String fieldName, Expression initExpr) {
    addField(false, isFinalField, type, fieldName, initExpr);
  }

  /**
   * Add a field to class.
   *
   * @param isStatic whether is static field
   * @param type type
   * @param fieldName field name
   * @param initExpr field init expression
   */
  public void addField(
      boolean isStatic, boolean isFinalField, String type, String fieldName, Expression initExpr) {
    fields.add(new FieldInfo(isStatic, isFinalField, type, fieldName));
    if (initExpr != null) {
      CodegenContext ctx;
      List<String> initCodes;
      if (isStatic) {
        if (staticInitCtx == null) {
          staticInitCtx = new CodegenContext(pkg, valNames, imports);
        }
        ctx = staticInitCtx;
        initCodes = staticInitCodes;
        if (initExpr instanceof BaseInvoke) {
          // Add an outer catch in static init block.
          ((BaseInvoke) initExpr).needTryCatch = false;
        }
      } else {
        if (instanceInitCtx == null) {
          instanceInitCtx = new CodegenContext(pkg, valNames, imports);
        }
        ctx = instanceInitCtx;
        initCodes = instanceInitCodes;
      }
      ExprCode exprCode = initExpr.genCode(ctx);
      if (StringUtils.isNotBlank(exprCode.code())) {
        initCodes.add(exprCode.code());
      }
      initCodes.add(String.format("%s = %s;", fieldName, exprCode.value()));
    }
  }

  /** Generate code for class. */
  public String genCode() {
    StringBuilder codeBuilder = new StringBuilder();

    if (StringUtils.isNotBlank(pkg)) {
      codeBuilder.append("package ").append(pkg).append(";\n\n");
    }

    if (!imports.isEmpty()) {
      imports.forEach(clz -> codeBuilder.append("import ").append(clz).append(";\n"));
      codeBuilder.append('\n');
    }

    codeBuilder.append(String.format("%s class %s ", classModifiers, className));
    if (superClasses != null) {
      codeBuilder.append(String.format("extends %s ", String.join(", ", superClasses)));
    }
    if (interfaces != null) {
      codeBuilder.append(String.format("implements %s ", String.join(", ", interfaces)));
    }
    codeBuilder.append("{\n");
    List<FieldInfo> staticFields = getFieldsInfo(true);
    if (!staticFields.isEmpty()) {
      for (FieldInfo field : staticFields) {
        codeBuilder.append("  ").append(addFieldDecl(field)).append("\n");
      }
      codeBuilder.append("  static {\n");
      codeBuilder.append("    try {\n");
      for (String staticInitCode : staticInitCodes) {
        codeBuilder.append(indent(staticInitCode, 6)).append("\n");
      }
      codeBuilder.append("    } catch (Throwable e) {\n");
      codeBuilder.append("      e.printStackTrace();\n");
      codeBuilder.append("      throw new RuntimeException(e);\n");
      codeBuilder.append("    }\n");
      codeBuilder.append("  }\n");
    }
    List<FieldInfo> instanceFields = getFieldsInfo(false);
    if (!instanceFields.isEmpty()) {
      codeBuilder.append('\n');
      for (FieldInfo field : instanceFields) {
        codeBuilder.append(indent(addFieldDecl(field).toString()));
      }
    }

    // constructors
    if (!constructors.isEmpty()) {
      codeBuilder.append('\n');
      constructors.forEach(constructor -> codeBuilder.append(indent(constructor)).append('\n'));
    }

    // methods
    codeBuilder.append('\n');
    methods.values().forEach(method -> codeBuilder.append(indent(method)).append('\n'));

    codeBuilder.append('}');
    return codeBuilder.toString();
  }

  private StringBuilder addFieldDecl(FieldInfo field) {
    StringBuilder declare = new StringBuilder("private ");
    if (field.isStatic) {
      declare.append("static ");
    }
    if (field.isFinal) {
      declare.append("final ");
    }
    declare.append(field.type).append(" ").append(field.fieldName).append(";\n");
    return declare;
  }

  public void clearExprState() {
    exprState.clear();
  }

  /** Optimize method code based current compiled expressions. */
  public String optimizeMethodCode(String code) {
    StringBuilder builder = new StringBuilder();
    for (Map.Entry<Expression, ExprState> entry : exprState.entrySet()) {
      Expression expression = entry.getKey();
      ExprState value = entry.getValue();
      if (expression instanceof Reference) {
        Reference reference = (Reference) expression;
        if (reference.isFieldRef() && value.getAccessCount() > 1) {
          // access only once are not necessary to load into local variable table,
          // which bloat local variable table.
          String type = type(reference.type());
          String cacheVariable =
              StringUtils.format(
                  "${type} ${name} = this.${name};", "type", type, "name", reference.name());
          builder.append(cacheVariable).append('\n');
        }
      }
    }
    if (builder.length() > 0) {
      return builder.append(code).toString();
    } else {
      return code;
    }
  }

  private final Map<Class<?>, Boolean> sourcePublicAccessibleCache = new HashMap<>();
  private final Map<Class<?>, Boolean> sourcePkgLevelAccessibleCache = new HashMap<>();

  /** Returns true if class is public accessible from source. */
  public boolean sourcePublicAccessible(Class<?> clz) {
    return sourcePublicAccessibleCache.computeIfAbsent(clz, CodeGenerator::sourcePublicAccessible);
  }

  /** Returns true if class is package level accessible from source. */
  public boolean sourcePkgLevelAccessible(Class<?> clz) {
    return sourcePkgLevelAccessibleCache.computeIfAbsent(
        clz, CodeGenerator::sourcePkgLevelAccessible);
  }
}
