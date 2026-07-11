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

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import org.apache.fory.codegen.CodeGenerator;
import org.apache.fory.json.codec.Latin1ObjectReaderCodec;
import org.apache.fory.json.codec.ObjectCodec;
import org.apache.fory.json.codec.StringObjectWriterCodec;
import org.apache.fory.json.codec.StringWriterCodec;
import org.apache.fory.json.codec.Utf16ObjectReaderCodec;
import org.apache.fory.json.codec.Utf8ObjectReaderCodec;
import org.apache.fory.json.codec.Utf8ObjectWriterCodec;
import org.apache.fory.json.codec.Utf8WriterCodec;
import org.apache.fory.json.meta.JsonFieldInfo;
import org.apache.fory.json.resolver.JsonTypeInfo;
import org.apache.fory.json.resolver.JsonTypeResolver;
import org.apache.fory.util.ExceptionUtils;

/** Owns JSON generated-class scheduling and resolver-local capability installation. */
public final class JsonJITContext {
  private final JsonCodegen codegen;
  private final boolean asyncCompilationEnabled;
  private final Set<Class<?>> stringWriterTasks;
  private final Set<Class<?>> utf8WriterTasks;
  private final Set<Class<?>> latin1ReaderTasks;
  private final Set<Class<?>> utf16ReaderTasks;
  private final Set<Class<?>> utf8ReaderTasks;
  private final Set<Class<?>> failedStringWriters;
  private final Set<Class<?>> failedUtf8Writers;
  private final Set<Class<?>> failedLatin1Readers;
  private final Set<Class<?>> failedUtf16Readers;
  private final Set<Class<?>> failedUtf8Readers;

  public JsonJITContext(JsonCodegen codegen, boolean asyncCompilationEnabled) {
    this.codegen = codegen;
    this.asyncCompilationEnabled = asyncCompilationEnabled;
    stringWriterTasks = ConcurrentHashMap.newKeySet();
    utf8WriterTasks = ConcurrentHashMap.newKeySet();
    latin1ReaderTasks = ConcurrentHashMap.newKeySet();
    utf16ReaderTasks = ConcurrentHashMap.newKeySet();
    utf8ReaderTasks = ConcurrentHashMap.newKeySet();
    failedStringWriters = ConcurrentHashMap.newKeySet();
    failedUtf8Writers = ConcurrentHashMap.newKeySet();
    failedLatin1Readers = ConcurrentHashMap.newKeySet();
    failedUtf16Readers = ConcurrentHashMap.newKeySet();
    failedUtf8Readers = ConcurrentHashMap.newKeySet();
  }

  public boolean asyncCompilationEnabled() {
    return asyncCompilationEnabled;
  }

  public LocalState newLocalState(JsonTypeResolver resolver) {
    return new LocalState(resolver);
  }

  private void requestStringWriter(ObjectCodec codec) {
    request(
        codec, stringWriterTasks, failedStringWriters, () -> codegen.compileStringWriter(codec));
  }

  private void requestUtf8Writer(ObjectCodec codec) {
    request(codec, utf8WriterTasks, failedUtf8Writers, () -> codegen.compileUtf8Writer(codec));
  }

  private void requestLatin1Reader(ObjectCodec codec) {
    request(
        codec, latin1ReaderTasks, failedLatin1Readers, () -> codegen.compileLatin1Reader(codec));
  }

  private void requestUtf16Reader(ObjectCodec codec) {
    request(codec, utf16ReaderTasks, failedUtf16Readers, () -> codegen.compileUtf16Reader(codec));
  }

  private void requestUtf8Reader(ObjectCodec codec) {
    request(codec, utf8ReaderTasks, failedUtf8Readers, () -> codegen.compileUtf8Reader(codec));
  }

  private void request(
      ObjectCodec codec, Set<Class<?>> tasks, Set<Class<?>> failures, Runnable compile) {
    if (!asyncCompilationEnabled) {
      compile.run();
      return;
    }
    Class<?> type = codec.type();
    if (failures.contains(type) || !tasks.add(type)) {
      return;
    }
    ExecutorService service = CodeGenerator.getCompilationService();
    service.execute(
        () -> {
          try {
            compile.run();
          } catch (Throwable t) {
            failures.add(type);
            ExceptionUtils.throwException(t);
          } finally {
            tasks.remove(type);
          }
        });
  }

  /** Resolver-local state. All methods run on the thread which exclusively borrowed that state. */
  public final class LocalState {
    private final JsonTypeResolver resolver;
    private final IdentityHashMap<Class<?>, StringObjectWriterCodec> stringWriters;
    private final IdentityHashMap<Class<?>, Utf8ObjectWriterCodec> utf8Writers;
    private final IdentityHashMap<Class<?>, Latin1ObjectReaderCodec> latin1Readers;
    private final IdentityHashMap<Class<?>, Utf16ObjectReaderCodec> utf16Readers;
    private final IdentityHashMap<Class<?>, Utf8ObjectReaderCodec> utf8Readers;
    private final IdentityHashMap<Class<?>, List<Consumer<StringWriterCodec>>> stringWriterUpdates;
    private final IdentityHashMap<Class<?>, List<Consumer<Utf8WriterCodec>>> utf8WriterUpdates;
    private final IdentityHashMap<Class<?>, List<Consumer<Latin1ObjectReaderCodec>>>
        latin1ReaderUpdates;
    private final IdentityHashMap<Class<?>, List<Consumer<Utf16ObjectReaderCodec>>>
        utf16ReaderUpdates;
    private final IdentityHashMap<Class<?>, List<Consumer<Utf8ObjectReaderCodec>>>
        utf8ReaderUpdates;
    // A generated parent must not execute with interpreted child fields: that transition leaves
    // mixed receiver profiles in C2 after the child is replaced. Build dependencies depth-first;
    // cycle callbacks wire mutually recursive instances before the outer installation returns.
    private ArrayList<Class<?>> installationStack;
    private JsonCodecPath installationPath;

    private LocalState(JsonTypeResolver resolver) {
      this.resolver = resolver;
      stringWriters = new IdentityHashMap<>();
      utf8Writers = new IdentityHashMap<>();
      latin1Readers = new IdentityHashMap<>();
      utf16Readers = new IdentityHashMap<>();
      utf8Readers = new IdentityHashMap<>();
      stringWriterUpdates = new IdentityHashMap<>();
      utf8WriterUpdates = new IdentityHashMap<>();
      latin1ReaderUpdates = new IdentityHashMap<>();
      utf16ReaderUpdates = new IdentityHashMap<>();
      utf8ReaderUpdates = new IdentityHashMap<>();
    }

    public StringObjectWriterCodec installedStringWriter(Class<?> type) {
      return stringWriters.get(type);
    }

    public Utf8ObjectWriterCodec installedUtf8Writer(Class<?> type) {
      return utf8Writers.get(type);
    }

    public Latin1ObjectReaderCodec installedLatin1Reader(Class<?> type) {
      return latin1Readers.get(type);
    }

    public Utf16ObjectReaderCodec installedUtf16Reader(Class<?> type) {
      return utf16Readers.get(type);
    }

    public Utf8ObjectReaderCodec installedUtf8Reader(Class<?> type) {
      return utf8Readers.get(type);
    }

    public StringWriterCodec stringWriter(ObjectCodec codec) {
      Class<?> type = codec.type();
      StringObjectWriterCodec installed = stringWriters.get(type);
      if (installed != null) {
        return installed;
      }
      if (codegen == null || failedStringWriters.contains(type)) {
        return codec;
      }
      JsonCodegen.GeneratedWriterClass generated = codegen.stringWriterClass(type);
      if (generated == null) {
        if (!codegen.canCompileWriter(codec)) {
          failedStringWriters.add(type);
          return codec;
        }
        requestStringWriter(codec);
        generated = codegen.stringWriterClass(type);
        if (generated == null) {
          return codec;
        }
      }
      if (!beginInstallation(JsonCodecPath.STRING_WRITER, type)) {
        return codec;
      }
      try {
        if (!prepareStringWriter(codec)) {
          return codec;
        }
        installed = codegen.newStringWriter(codec, resolver, generated);
        stringWriters.put(type, installed);
        resolver.installStringWriter(type, installed);
        notifyUpdates(stringWriterUpdates.remove(type), installed);
        return installed;
      } finally {
        endInstallation(type);
      }
    }

    public Utf8WriterCodec utf8Writer(ObjectCodec codec) {
      Class<?> type = codec.type();
      Utf8ObjectWriterCodec installed = utf8Writers.get(type);
      if (installed != null) {
        return installed;
      }
      if (codegen == null || failedUtf8Writers.contains(type)) {
        return codec;
      }
      JsonCodegen.GeneratedWriterClass generated = codegen.utf8WriterClass(type);
      if (generated == null) {
        if (!codegen.canCompileWriter(codec)) {
          failedUtf8Writers.add(type);
          return codec;
        }
        requestUtf8Writer(codec);
        generated = codegen.utf8WriterClass(type);
        if (generated == null) {
          return codec;
        }
      }
      if (!beginInstallation(JsonCodecPath.UTF8_WRITER, type)) {
        return codec;
      }
      try {
        if (!prepareUtf8Writer(codec)) {
          return codec;
        }
        installed = codegen.newUtf8Writer(codec, resolver, generated);
        utf8Writers.put(type, installed);
        resolver.installUtf8Writer(type, installed);
        notifyUpdates(utf8WriterUpdates.remove(type), installed);
        return installed;
      } finally {
        endInstallation(type);
      }
    }

    public Latin1ObjectReaderCodec latin1Reader(ObjectCodec codec) {
      Class<?> type = codec.type();
      Latin1ObjectReaderCodec installed = latin1Readers.get(type);
      if (installed != null) {
        return installed;
      }
      if (codegen == null || failedLatin1Readers.contains(type)) {
        return codec;
      }
      JsonCodegen.GeneratedReaderClass generated = codegen.latin1ReaderClass(type);
      if (generated == null) {
        if (!codegen.canCompileReader(codec)) {
          failedLatin1Readers.add(type);
          return codec;
        }
        requestLatin1Reader(codec);
        generated = codegen.latin1ReaderClass(type);
        if (generated == null) {
          return codec;
        }
      }
      if (!beginInstallation(JsonCodecPath.LATIN1_READER, type)) {
        return codec;
      }
      try {
        if (!prepareLatin1Reader(codec)) {
          return codec;
        }
        installed = codegen.newLatin1Reader(codec, resolver, generated);
        latin1Readers.put(type, installed);
        resolver.installLatin1Reader(type, installed);
        notifyUpdates(latin1ReaderUpdates.remove(type), installed);
        return installed;
      } finally {
        endInstallation(type);
      }
    }

    public Utf16ObjectReaderCodec utf16Reader(ObjectCodec codec) {
      Class<?> type = codec.type();
      Utf16ObjectReaderCodec installed = utf16Readers.get(type);
      if (installed != null) {
        return installed;
      }
      if (codegen == null || failedUtf16Readers.contains(type)) {
        return codec;
      }
      JsonCodegen.GeneratedReaderClass generated = codegen.utf16ReaderClass(type);
      if (generated == null) {
        if (!codegen.canCompileReader(codec)) {
          failedUtf16Readers.add(type);
          return codec;
        }
        requestUtf16Reader(codec);
        generated = codegen.utf16ReaderClass(type);
        if (generated == null) {
          return codec;
        }
      }
      if (!beginInstallation(JsonCodecPath.UTF16_READER, type)) {
        return codec;
      }
      try {
        if (!prepareUtf16Reader(codec)) {
          return codec;
        }
        installed = codegen.newUtf16Reader(codec, resolver, generated);
        utf16Readers.put(type, installed);
        resolver.installUtf16Reader(type, installed);
        notifyUpdates(utf16ReaderUpdates.remove(type), installed);
        return installed;
      } finally {
        endInstallation(type);
      }
    }

    public Utf8ObjectReaderCodec utf8Reader(ObjectCodec codec) {
      Class<?> type = codec.type();
      Utf8ObjectReaderCodec installed = utf8Readers.get(type);
      if (installed != null) {
        return installed;
      }
      if (codegen == null || failedUtf8Readers.contains(type)) {
        return codec;
      }
      JsonCodegen.GeneratedReaderClass generated = codegen.utf8ReaderClass(type);
      if (generated == null) {
        if (!codegen.canCompileReader(codec)) {
          failedUtf8Readers.add(type);
          return codec;
        }
        requestUtf8Reader(codec);
        generated = codegen.utf8ReaderClass(type);
        if (generated == null) {
          return codec;
        }
      }
      if (!beginInstallation(JsonCodecPath.UTF8_READER, type)) {
        return codec;
      }
      try {
        if (!prepareUtf8Reader(codec)) {
          return codec;
        }
        installed = codegen.newUtf8Reader(codec, resolver, generated);
        utf8Readers.put(type, installed);
        resolver.installUtf8Reader(type, installed);
        notifyUpdates(utf8ReaderUpdates.remove(type), installed);
        return installed;
      } finally {
        endInstallation(type);
      }
    }

    private boolean prepareStringWriter(ObjectCodec owner) {
      JsonFieldInfo[] fields = owner.writeFields();
      for (int i = 0; i < fields.length; i++) {
        JsonTypeInfo typeInfo = JsonCodegen.writeObjectTypeInfo(fields[i], resolver);
        if (typeInfo == null || typeInfo.rawType() == owner.type()) {
          continue;
        }
        StringWriterCodec capability = typeInfo.stringWriter();
        if (!(capability instanceof ObjectCodec)) {
          continue;
        }
        ObjectCodec dependency = (ObjectCodec) capability;
        Class<?> type = dependency.type();
        if (isInstalling(JsonCodecPath.STRING_WRITER, type)) {
          continue;
        }
        if (stringWriter(dependency) == dependency && !failedStringWriters.contains(type)) {
          return false;
        }
      }
      return true;
    }

    private boolean prepareUtf8Writer(ObjectCodec owner) {
      JsonFieldInfo[] fields = owner.writeFields();
      for (int i = 0; i < fields.length; i++) {
        JsonTypeInfo typeInfo = JsonCodegen.writeObjectTypeInfo(fields[i], resolver);
        if (typeInfo == null || typeInfo.rawType() == owner.type()) {
          continue;
        }
        Utf8WriterCodec capability = typeInfo.utf8Writer();
        if (!(capability instanceof ObjectCodec)) {
          continue;
        }
        ObjectCodec dependency = (ObjectCodec) capability;
        Class<?> type = dependency.type();
        if (isInstalling(JsonCodecPath.UTF8_WRITER, type)) {
          continue;
        }
        if (utf8Writer(dependency) == dependency && !failedUtf8Writers.contains(type)) {
          return false;
        }
      }
      return true;
    }

    private boolean prepareLatin1Reader(ObjectCodec owner) {
      JsonFieldInfo[] fields = owner.readFields();
      for (int i = 0; i < fields.length; i++) {
        JsonTypeInfo typeInfo = JsonCodegen.readObjectTypeInfo(fields[i]);
        if (typeInfo == null || typeInfo.rawType() == owner.type()) {
          continue;
        }
        ObjectCodec dependency = objectLatin1Reader(typeInfo);
        if (dependency == null) {
          continue;
        }
        Class<?> type = dependency.type();
        if (isInstalling(JsonCodecPath.LATIN1_READER, type)) {
          continue;
        }
        if (latin1Reader(dependency) == dependency
            && !failedLatin1Readers.contains(type)) {
          return false;
        }
      }
      return true;
    }

    private boolean prepareUtf16Reader(ObjectCodec owner) {
      JsonFieldInfo[] fields = owner.readFields();
      for (int i = 0; i < fields.length; i++) {
        JsonTypeInfo typeInfo = JsonCodegen.readObjectTypeInfo(fields[i]);
        if (typeInfo == null || typeInfo.rawType() == owner.type()) {
          continue;
        }
        ObjectCodec dependency = objectUtf16Reader(typeInfo);
        if (dependency == null) {
          continue;
        }
        Class<?> type = dependency.type();
        if (isInstalling(JsonCodecPath.UTF16_READER, type)) {
          continue;
        }
        if (utf16Reader(dependency) == dependency
            && !failedUtf16Readers.contains(type)) {
          return false;
        }
      }
      return true;
    }

    private boolean prepareUtf8Reader(ObjectCodec owner) {
      JsonFieldInfo[] fields = owner.readFields();
      for (int i = 0; i < fields.length; i++) {
        JsonTypeInfo typeInfo = JsonCodegen.readObjectTypeInfo(fields[i]);
        if (typeInfo == null || typeInfo.rawType() == owner.type()) {
          continue;
        }
        ObjectCodec dependency = objectUtf8Reader(typeInfo);
        if (dependency == null) {
          continue;
        }
        Class<?> type = dependency.type();
        if (isInstalling(JsonCodecPath.UTF8_READER, type)) {
          continue;
        }
        if (utf8Reader(dependency) == dependency
            && !failedUtf8Readers.contains(type)) {
          return false;
        }
      }
      return true;
    }

    private ObjectCodec objectLatin1Reader(JsonTypeInfo typeInfo) {
      return typeInfo.latin1Reader() instanceof ObjectCodec
          ? (ObjectCodec) typeInfo.latin1Reader()
          : null;
    }

    private ObjectCodec objectUtf16Reader(JsonTypeInfo typeInfo) {
      return typeInfo.utf16Reader() instanceof ObjectCodec
          ? (ObjectCodec) typeInfo.utf16Reader()
          : null;
    }

    private ObjectCodec objectUtf8Reader(JsonTypeInfo typeInfo) {
      return typeInfo.utf8Reader() instanceof ObjectCodec
          ? (ObjectCodec) typeInfo.utf8Reader()
          : null;
    }

    private boolean beginInstallation(JsonCodecPath path, Class<?> type) {
      if (installationStack == null) {
        installationStack = new ArrayList<>(4);
      }
      if (installationStack.isEmpty()) {
        installationPath = path;
      } else if (installationPath != path) {
        throw new IllegalStateException(
            "Nested JSON capability installation changed path from "
                + installationPath
                + " to "
                + path);
      }
      if (installationStack.contains(type)) {
        return false;
      }
      installationStack.add(type);
      return true;
    }

    private boolean isInstalling(JsonCodecPath path, Class<?> type) {
      return installationPath == path
          && installationStack != null
          && installationStack.contains(type);
    }

    private void endInstallation(Class<?> type) {
      int last = installationStack.size() - 1;
      if (installationStack.get(last) != type) {
        throw new IllegalStateException("JSON capability installation order was corrupted");
      }
      installationStack.remove(last);
      if (installationStack.isEmpty()) {
        installationPath = null;
      }
    }

    public void registerStringWriterUpdate(Class<?> type, Consumer<StringWriterCodec> updater) {
      StringObjectWriterCodec installed = stringWriters.get(type);
      if (installed != null) {
        updater.accept(installed);
      } else {
        stringWriterUpdates.computeIfAbsent(type, ignored -> new ArrayList<>()).add(updater);
      }
    }

    public void registerUtf8WriterUpdate(Class<?> type, Consumer<Utf8WriterCodec> updater) {
      Utf8ObjectWriterCodec installed = utf8Writers.get(type);
      if (installed != null) {
        updater.accept(installed);
      } else {
        utf8WriterUpdates.computeIfAbsent(type, ignored -> new ArrayList<>()).add(updater);
      }
    }

    public void registerLatin1ReaderUpdate(
        Class<?> type, Consumer<Latin1ObjectReaderCodec> updater) {
      Latin1ObjectReaderCodec installed = latin1Readers.get(type);
      if (installed != null) {
        updater.accept(installed);
      } else {
        latin1ReaderUpdates.computeIfAbsent(type, ignored -> new ArrayList<>()).add(updater);
      }
    }

    public void registerUtf16ReaderUpdate(Class<?> type, Consumer<Utf16ObjectReaderCodec> updater) {
      Utf16ObjectReaderCodec installed = utf16Readers.get(type);
      if (installed != null) {
        updater.accept(installed);
      } else {
        utf16ReaderUpdates.computeIfAbsent(type, ignored -> new ArrayList<>()).add(updater);
      }
    }

    public void registerUtf8ReaderUpdate(Class<?> type, Consumer<Utf8ObjectReaderCodec> updater) {
      Utf8ObjectReaderCodec installed = utf8Readers.get(type);
      if (installed != null) {
        updater.accept(installed);
      } else {
        utf8ReaderUpdates.computeIfAbsent(type, ignored -> new ArrayList<>()).add(updater);
      }
    }

    private <T> void notifyUpdates(List<Consumer<T>> updates, T value) {
      if (updates == null) {
        return;
      }
      for (int i = 0; i < updates.size(); i++) {
        updates.get(i).accept(value);
      }
    }
  }
}
