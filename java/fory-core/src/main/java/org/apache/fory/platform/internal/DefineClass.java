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

package org.apache.fory.platform.internal;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodType;
import java.lang.reflect.Array;
import java.security.ProtectionDomain;
import org.apache.fory.annotation.Internal;
import org.apache.fory.platform.AndroidSupport;
import org.apache.fory.platform.JdkVersion;
import org.apache.fory.util.Preconditions;

/** A class to define bytecode as a class. */
@Internal
public class DefineClass {
  private static volatile MethodHandle classloaderDefineClassHandle;
  private static volatile HiddenClassDefiner hiddenClassDefiner;

  public static Class<?> defineClass(
      String className,
      Class<?> neighbor,
      ClassLoader loader,
      ProtectionDomain domain,
      byte[] bytecodes) {
    if (AndroidSupport.IS_ANDROID) {
      throw new UnsupportedOperationException(
          "Runtime bytecode loading is unsupported on Android.");
    }
    Preconditions.checkNotNull(loader);
    Preconditions.checkArgument(JdkVersion.MAJOR_VERSION >= 8);
    if (neighbor != null && JdkVersion.MAJOR_VERSION >= 9) {
      // classes in bytecode must be in same package as lookup class.
      MethodHandles.Lookup lookup = MethodHandles.lookup();
      _JDKAccess.addReads(_JDKAccess.getModule(DefineClass.class), _JDKAccess.getModule(neighbor));
      lookup = _Lookup.privateLookupIn(neighbor, lookup);
      return _Lookup.defineClass(lookup, bytecodes);
    }
    if (classloaderDefineClassHandle == null) {
      MethodHandles.Lookup lookup = _JDKAccess._trustedLookup(ClassLoader.class);
      try {
        classloaderDefineClassHandle =
            lookup.findVirtual(
                ClassLoader.class,
                "defineClass",
                MethodType.methodType(
                    Class.class,
                    String.class,
                    byte[].class,
                    int.class,
                    int.class,
                    ProtectionDomain.class));
      } catch (NoSuchMethodException | IllegalAccessException e) {
        throw new RuntimeException(e);
      }
    }
    try {
      return (Class<?>)
          classloaderDefineClassHandle.invokeWithArguments(
              loader, className, bytecodes, 0, bytecodes.length, domain);
    } catch (Throwable e) {
      throw new RuntimeException(e);
    }
  }

  public static Class<?> defineHiddenNestmate(Class<?> neighbor, byte[] bytecodes) {
    Preconditions.checkNotNull(neighbor);
    Preconditions.checkNotNull(bytecodes);
    if (JdkVersion.MAJOR_VERSION < 15) {
      throw hiddenClassUnsupported(null);
    }
    try {
      Lookup lookup = _Lookup.privateLookupIn(neighbor, MethodHandles.lookup());
      return hiddenClassDefiner().define(lookup, bytecodes);
    } catch (Throwable e) {
      throw hiddenClassFailure(neighbor, e);
    }
  }

  private static HiddenClassDefiner hiddenClassDefiner() {
    HiddenClassDefiner definer = hiddenClassDefiner;
    if (definer == null) {
      synchronized (DefineClass.class) {
        definer = hiddenClassDefiner;
        if (definer == null) {
          definer = loadHiddenClassDefiner();
          hiddenClassDefiner = definer;
        }
      }
    }
    return definer;
  }

  private static HiddenClassDefiner loadHiddenClassDefiner() {
    try {
      // Keep JDK15+ hidden-class symbols out of the root constant pool so Java 8 through Java 14
      // can load this class; defineHiddenNestmate is the only path that needs them.
      Class<?> optionType = Class.forName("java.lang.invoke.MethodHandles$Lookup$ClassOption");
      Object nestmate = Enum.valueOf(optionType.asSubclass(Enum.class), "NESTMATE");
      Object options = Array.newInstance(optionType, 1);
      Array.set(options, 0, nestmate);
      MethodHandle defineHiddenClass =
          MethodHandles.publicLookup()
              .findVirtual(
                  Lookup.class,
                  "defineHiddenClass",
                  MethodType.methodType(
                      Lookup.class, byte[].class, boolean.class, options.getClass()))
              .asFixedArity();
      return new HiddenClassDefiner(defineHiddenClass, options);
    } catch (ReflectiveOperationException | RuntimeException e) {
      throw hiddenClassUnsupported(e);
    }
  }

  private static UnsupportedOperationException hiddenClassUnsupported(Throwable cause) {
    return new UnsupportedOperationException(
        "Hidden nestmate class definition requires JDK15+ Lookup#defineHiddenClass", cause);
  }

  private static RuntimeException hiddenClassFailure(Class<?> neighbor, Throwable cause) {
    if (cause instanceof RuntimeException) {
      return (RuntimeException) cause;
    }
    if (cause instanceof Error) {
      throw (Error) cause;
    }
    String message = "Cannot define hidden nestmate for " + neighbor.getName() + ".";
    if (JdkVersion.MAJOR_VERSION >= 25) {
      message += " " + _JDKAccess.jdk25AccessMessage();
    }
    return new IllegalStateException(message, cause);
  }

  private static final class HiddenClassDefiner {
    private final MethodHandle defineHiddenClass;
    private final Object nestmateOptions;

    private HiddenClassDefiner(MethodHandle defineHiddenClass, Object nestmateOptions) {
      this.defineHiddenClass = defineHiddenClass;
      this.nestmateOptions = nestmateOptions;
    }

    private Class<?> define(Lookup lookup, byte[] bytecodes) throws Throwable {
      return ((Lookup) defineHiddenClass.invoke(lookup, bytecodes, true, nestmateOptions))
          .lookupClass();
    }
  }
}
