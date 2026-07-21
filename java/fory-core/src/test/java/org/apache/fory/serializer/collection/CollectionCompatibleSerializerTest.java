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

package org.apache.fory.serializer.collection;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.apache.fory.Fory;
import org.apache.fory.ForyTestBase;
import org.apache.fory.context.MetaReadContext;
import org.apache.fory.context.MetaWriteContext;
import org.apache.fory.util.ClassLoaderUtils;
import org.testng.Assert;
import org.testng.annotations.Test;

public class CollectionCompatibleSerializerTest extends ForyTestBase {
  private static final String PEER_PACKAGE = "org.apache.fory.serializer.collection.compat";

  @Test
  public void testCustomSetCompatible() {
    Fory writer = compatibleFory();
    Fory reader = compatibleFory();
    CustomSet set = new CustomSet("set-marker");
    set.add("a");
    set.add("b");

    SetHolder holder = new SetHolder();
    holder.values = set;

    SetHolder copy = serDe(writer, reader, holder);
    Assert.assertEquals(copy.values.getClass(), CustomSet.class);
    CustomSet copySet = (CustomSet) copy.values;
    Assert.assertEquals(copySet.getMarker(), "set-marker");
    Assert.assertEquals(copySet, set);
  }

  @Test
  public void testCustomMapCompatible() {
    Fory writer = compatibleFory();
    Fory reader = compatibleFory();
    CustomMap map = new CustomMap("map-marker");
    map.put("k1", "v1");
    map.put("k2", "v2");

    MapHolder holder = new MapHolder();
    holder.values = map;

    MapHolder copy = serDe(writer, reader, holder);
    Assert.assertEquals(copy.values.getClass(), CustomMap.class);
    CustomMap copyMap = (CustomMap) copy.values;
    Assert.assertEquals(copyMap.getMarker(), "map-marker");
    Assert.assertEquals(copyMap, map);
  }

  @Test
  public void testDefaultSetEvolution() throws Exception {
    Class<?> writerPeer = compilePeer("DefaultSetPeer", defaultSetSource(true));
    Class<?> readerPeer = compilePeer("DefaultSetPeer", defaultSetSource(false));
    Object writerHolder = writerPeer.getMethod("newHolder").invoke(null);

    Object copy = peerSerDe(writerPeer.getClassLoader(), readerPeer.getClassLoader(), writerHolder);
    Object values = fieldValue(copy, "values");
    Assert.assertEquals(values.getClass().getName(), PEER_PACKAGE + ".DefaultSetPeer$CustomSet");
    Assert.assertEquals(fieldValue(values, "marker"), "set-marker");
    Assert.assertEquals(((Collection<?>) values).size(), 2);
    Assert.assertTrue(((Collection<?>) values).contains("a"));
  }

  @Test
  public void testDefaultMapEvolution() throws Exception {
    Class<?> writerPeer = compilePeer("DefaultMapPeer", defaultMapSource(true));
    Class<?> readerPeer = compilePeer("DefaultMapPeer", defaultMapSource(false));
    Object writerHolder = writerPeer.getMethod("newHolder").invoke(null);

    Object copy = peerSerDe(writerPeer.getClassLoader(), readerPeer.getClassLoader(), writerHolder);
    Object values = fieldValue(copy, "values");
    Assert.assertEquals(values.getClass().getName(), PEER_PACKAGE + ".DefaultMapPeer$CustomMap");
    Assert.assertEquals(fieldValue(values, "marker"), "map-marker");
    Assert.assertEquals(((Map<?, ?>) values).get("k1"), "v1");
    Assert.assertEquals(((Map<?, ?>) values).size(), 2);
  }

  @Test
  public void testChildSetEvolution() throws Exception {
    Class<?> writerPeer = compilePeer("ChildSetPeer", childSetSource(true));
    Class<?> readerPeer = compilePeer("ChildSetPeer", childSetSource(false));
    Object writerHolder = writerPeer.getMethod("newHolder").invoke(null);

    Object copy = peerSerDe(writerPeer.getClassLoader(), readerPeer.getClassLoader(), writerHolder);
    Object values = fieldValue(copy, "values");
    Assert.assertEquals(values.getClass().getName(), PEER_PACKAGE + ".ChildSetPeer$CustomSet");
    Assert.assertEquals(fieldValue(values, "marker"), "child-set-marker");
    Assert.assertTrue(((Collection<?>) values).contains("a"));
  }

  @Test
  public void testChildMapEvolution() throws Exception {
    Class<?> writerPeer = compilePeer("ChildMapPeer", childMapSource(true));
    Class<?> readerPeer = compilePeer("ChildMapPeer", childMapSource(false));
    Object writerHolder = writerPeer.getMethod("newHolder").invoke(null);

    Object copy = peerSerDe(writerPeer.getClassLoader(), readerPeer.getClassLoader(), writerHolder);
    Object values = fieldValue(copy, "values");
    Assert.assertEquals(values.getClass().getName(), PEER_PACKAGE + ".ChildMapPeer$CustomMap");
    Assert.assertEquals(fieldValue(values, "marker"), "child-map-marker");
    Assert.assertEquals(((Map<?, ?>) values).get("k1"), "v1");
  }

  @Test
  public void testObjectStreamEvolution() throws Exception {
    Class<?> writerPeer = compilePeer("StreamPeer", streamSource(true));
    Class<?> readerPeer = compilePeer("StreamPeer", streamSource(false));
    Object writerValue = writerPeer.getMethod("newValue").invoke(null);

    Object copy = peerSerDe(writerPeer.getClassLoader(), readerPeer.getClassLoader(), writerValue);
    Assert.assertEquals(copy.getClass().getName(), PEER_PACKAGE + ".StreamPeer");
    Assert.assertEquals(fieldValue(copy, "marker"), "stream-marker");
  }

  private static Fory compatibleFory() {
    return builder().withRefTracking(true).withCodegen(true).withCompatible(true).build();
  }

  private static Fory peerFory(ClassLoader classLoader) {
    return builder()
        .withClassLoader(classLoader)
        .withRefTracking(true)
        .withCodegen(true)
        .withCompatible(true)
        .withMetaShare(true)
        .withScopedMetaShare(false)
        .build();
  }

  private static Object peerSerDe(
      ClassLoader writerClassLoader, ClassLoader readerClassLoader, Object value) {
    Fory writer = peerFory(writerClassLoader);
    Fory reader = peerFory(readerClassLoader);
    MetaWriteContext metaWriteContext = new MetaWriteContext();
    MetaReadContext metaReadContext = new MetaReadContext();
    setMetaContexts(writer, metaWriteContext, new MetaReadContext());
    byte[] bytes = writer.serialize(value);
    setMetaContexts(reader, new MetaWriteContext(), metaReadContext);
    return reader.deserialize(bytes);
  }

  private static Object fieldValue(Object obj, String name) throws ReflectiveOperationException {
    Field field = obj.getClass().getField(name);
    return field.get(obj);
  }

  private static Class<?> compilePeer(String className, String source) throws Exception {
    Path dir = Files.createTempDirectory("fory-compatible-collection");
    Path sourceFile = dir.resolve(PEER_PACKAGE.replace('.', '/')).resolve(className + ".java");
    Files.createDirectories(sourceFile.getParent());
    Files.write(sourceFile, source.getBytes(StandardCharsets.UTF_8));
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    Assert.assertNotNull(compiler, "Tests require a JDK compiler");
    int result =
        compiler.run(
            null,
            new ByteArrayOutputStream(),
            System.err,
            "-classpath",
            System.getProperty("java.class.path"),
            "-d",
            dir.toString(),
            sourceFile.toString());
    if (result != 0) {
      throw new AssertionError("Couldn't compile code:\n" + source);
    }
    URL[] urls = new URL[] {dir.toUri().toURL()};
    ClassLoader parent = CollectionCompatibleSerializerTest.class.getClassLoader();
    return new ClassLoaderUtils.ChildFirstURLClassLoader(urls, parent)
        .loadClass(PEER_PACKAGE + "." + className);
  }

  private static String defaultSetSource(boolean writer) {
    return sourceHeader("DefaultSetPeer")
        + "  public static class Holder { public Set values; }\n"
        + "  public static class CustomSet extends AbstractSet {\n"
        + "    public LinkedHashSet values = new LinkedHashSet();\n"
        + "    public String marker;\n"
        + (writer ? "    public String writerOnly;\n" : "")
        + "    public Iterator iterator() { return values.iterator(); }\n"
        + "    public int size() { return values.size(); }\n"
        + "    public boolean add(Object value) { return values.add(value); }\n"
        + "  }\n"
        + "  public static Holder newHolder() {\n"
        + "    Holder holder = new Holder();\n"
        + "    CustomSet values = new CustomSet();\n"
        + "    values.marker = \"set-marker\";\n"
        + (writer ? "    values.writerOnly = \"writer-only\";\n" : "")
        + "    values.add(\"a\"); values.add(\"b\"); holder.values = values; return holder;\n"
        + "  }\n"
        + "}\n";
  }

  private static String defaultMapSource(boolean writer) {
    return sourceHeader("DefaultMapPeer")
        + "  public static class Holder { public Map values; }\n"
        + "  public static class CustomMap extends AbstractMap {\n"
        + "    public LinkedHashMap values = new LinkedHashMap();\n"
        + "    public String marker;\n"
        + (writer ? "    public String writerOnly;\n" : "")
        + "    public Object put(Object key, Object value) { return values.put(key, value); }\n"
        + "    public Set entrySet() { return values.entrySet(); }\n"
        + "  }\n"
        + "  public static Holder newHolder() {\n"
        + "    Holder holder = new Holder();\n"
        + "    CustomMap values = new CustomMap();\n"
        + "    values.marker = \"map-marker\";\n"
        + (writer ? "    values.writerOnly = \"writer-only\";\n" : "")
        + "    values.put(\"k1\", \"v1\"); values.put(\"k2\", \"v2\");\n"
        + "    holder.values = values; return holder;\n"
        + "  }\n"
        + "}\n";
  }

  private static String childSetSource(boolean writer) {
    return sourceHeader("ChildSetPeer")
        + "  public static class Holder { public Set values; }\n"
        + "  public static class CustomSet extends HashSet {\n"
        + "    public String marker;\n"
        + (writer ? "    public String writerOnly;\n" : "")
        + "  }\n"
        + "  public static Holder newHolder() {\n"
        + "    Holder holder = new Holder();\n"
        + "    CustomSet values = new CustomSet();\n"
        + "    values.marker = \"child-set-marker\";\n"
        + (writer ? "    values.writerOnly = \"writer-only\";\n" : "")
        + "    values.add(\"a\"); values.add(\"b\"); holder.values = values; return holder;\n"
        + "  }\n"
        + "}\n";
  }

  private static String childMapSource(boolean writer) {
    return sourceHeader("ChildMapPeer")
        + "  public static class Holder { public Map values; }\n"
        + "  public static class CustomMap extends HashMap {\n"
        + "    public String marker;\n"
        + (writer ? "    public String writerOnly;\n" : "")
        + "  }\n"
        + "  public static Holder newHolder() {\n"
        + "    Holder holder = new Holder();\n"
        + "    CustomMap values = new CustomMap();\n"
        + "    values.marker = \"child-map-marker\";\n"
        + (writer ? "    values.writerOnly = \"writer-only\";\n" : "")
        + "    values.put(\"k1\", \"v1\"); values.put(\"k2\", \"v2\");\n"
        + "    holder.values = values; return holder;\n"
        + "  }\n"
        + "}\n";
  }

  private static String streamSource(boolean writer) {
    return sourceHeader("StreamPeer")
        + "  public String marker;\n"
        + (writer ? "  public String writerOnly;\n" : "")
        + "  private void writeObject(ObjectOutputStream out) throws IOException {\n"
        + "    out.defaultWriteObject();\n"
        + "  }\n"
        + "  private void readObject(ObjectInputStream in)\n"
        + "      throws IOException, ClassNotFoundException {\n"
        + "    in.defaultReadObject();\n"
        + "  }\n"
        + "  public static StreamPeer newValue() {\n"
        + "    StreamPeer value = new StreamPeer(); value.marker = \"stream-marker\";\n"
        + (writer ? "    value.writerOnly = \"writer-only\";\n" : "")
        + "    return value;\n"
        + "  }\n"
        + "}\n";
  }

  private static String sourceHeader(String className) {
    return "package "
        + PEER_PACKAGE
        + ";\n"
        + "import java.io.*;\n"
        + "import java.util.*;\n"
        + "public class "
        + className
        + " {\n";
  }

  public static final class SetHolder {
    public Set<String> values;
  }

  public static final class MapHolder {
    public Map<String, String> values;
  }

  public static final class CustomSet extends AbstractSet<String> {
    private LinkedHashSet<String> values = new LinkedHashSet<>();
    private String marker;

    public CustomSet() {}

    private CustomSet(String marker) {
      this.marker = marker;
    }

    public String getMarker() {
      return marker;
    }

    @Override
    public boolean add(String value) {
      return values.add(value);
    }

    @Override
    public Iterator<String> iterator() {
      return values.iterator();
    }

    @Override
    public int size() {
      return values.size();
    }
  }

  public static final class CustomMap extends AbstractMap<String, String> {
    private LinkedHashMap<String, String> values = new LinkedHashMap<>();
    private String marker;

    public CustomMap() {}

    private CustomMap(String marker) {
      this.marker = marker;
    }

    public String getMarker() {
      return marker;
    }

    @Override
    public String put(String key, String value) {
      return values.put(key, value);
    }

    @Override
    public Set<Entry<String, String>> entrySet() {
      return values.entrySet();
    }
  }
}
