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

package org.apache.fory.json.codec;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.fory.annotation.Internal;
import org.apache.fory.json.ForyJsonException;
import org.apache.fory.json.meta.JsonCreatorFieldInfo;
import org.apache.fory.json.meta.JsonFieldAccessor;
import org.apache.fory.json.meta.JsonFieldInfo;
import org.apache.fory.json.meta.JsonFieldNameHash;
import org.apache.fory.json.resolver.JsonTypeInfo;
import org.apache.fory.json.resolver.JsonTypeResolver;
import org.apache.fory.reflect.TypeRef;

/** Parent-owned resolved metadata for object-valued properties flattened into one JSON object. */
@Internal
public final class JsonUnwrappedInfo {
  public static final int DIRECT = 0;
  public static final int GROUP = 1;
  public static final int ANY = 2;
  public static final int UNKNOWN = -1;
  public static final int SKIP = -2;

  private static final int UNRESOLVED = 0;
  private static final int RESOLVING = 1;
  private static final int RESOLVED = 2;

  private final Declaration[] declarations;
  private final WriteSpec[] writeSpecs;
  private final String[] directReservedNames;
  private int resolutionState;
  private WriteEntry[] writeEntries;
  private Group[] groups;
  private ReadRoute[] readRoutes;
  private RouteTable routeTable;
  private String[] flattenedNames;

  public JsonUnwrappedInfo(
      Declaration[] declarations, WriteSpec[] writeSpecs, String[] directReservedNames) {
    this.declarations = declarations;
    this.writeSpecs = writeSpecs;
    this.directReservedNames = directReservedNames;
  }

  public Declaration[] declarations() {
    return declarations;
  }

  public WriteSpec[] writeSpecs() {
    return writeSpecs;
  }

  public WriteEntry[] writeEntries() {
    requireResolved();
    return writeEntries;
  }

  public Group[] groups() {
    requireResolved();
    return groups;
  }

  public ReadRoute[] readRoutes() {
    requireResolved();
    return readRoutes;
  }

  public int match(long hash) {
    requireResolved();
    return routeTable.match(hash);
  }

  public boolean containsHash(long hash) {
    requireResolved();
    return routeTable.containsHash(hash);
  }

  public String[] flattenedNames() {
    requireResolved();
    return flattenedNames;
  }

  void resolve(ObjectCodec<?> owner, JsonTypeResolver resolver) {
    if (resolutionState == RESOLVED) {
      return;
    }
    if (resolutionState == RESOLVING) {
      throw new ForyJsonException(
          "Recursive @JsonUnwrapped property cycle reaches " + owner.type().getName());
    }
    resolutionState = RESOLVING;
    try {
      for (Declaration declaration : declarations) {
        declaration.resolve(owner, resolver);
      }
      buildResolvedMetadata(owner, resolver);
      resolutionState = RESOLVED;
    } catch (RuntimeException | Error e) {
      resolutionState = UNRESOLVED;
      writeEntries = null;
      groups = null;
      readRoutes = null;
      routeTable = null;
      flattenedNames = null;
      throw e;
    }
  }

  boolean isResolving() {
    return resolutionState == RESOLVING;
  }

  private void buildResolvedMetadata(ObjectCodec<?> owner, JsonTypeResolver resolver) {
    BuildContext context = new BuildContext(owner, resolver);
    context.registerRootNames();
    IdentityHashMap<Declaration, Group> rootGroups = new IdentityHashMap<>();
    for (Declaration declaration : declarations) {
      Group group = context.buildGroup(null, owner, declaration, "", "", true, true);
      rootGroups.put(declaration, group);
    }
    List<WriteEntry> resolvedWrites = new ArrayList<>(writeSpecs.length);
    for (WriteSpec spec : writeSpecs) {
      if (spec.kind == DIRECT) {
        resolvedWrites.add(WriteEntry.direct(spec.field));
      } else if (spec.kind == ANY) {
        resolvedWrites.add(WriteEntry.any());
      } else {
        Group group = rootGroups.get(spec.declaration);
        if (group.writeEnabled) {
          resolvedWrites.add(WriteEntry.group(group));
        }
      }
    }
    writeEntries = resolvedWrites.toArray(new WriteEntry[0]);
    groups = context.groups.toArray(new Group[0]);
    readRoutes = context.routes.toArray(new ReadRoute[0]);
    routeTable = new RouteTable(context.names, context.routeIndexes);
    flattenedNames = context.names.keySet().toArray(new String[0]);
  }

  private void requireResolved() {
    if (resolutionState != RESOLVED) {
      throw new IllegalStateException("JsonUnwrapped metadata has not been resolved");
    }
  }

  /** One merged logical-property declaration produced by {@code ObjectCodecBuilder}. */
  public static final class Declaration {
    private final String javaName;
    private final String prefix;
    private final String suffix;
    private final Type type;
    private final Class<?> rawType;
    private final JsonFieldAccessor writeAccessor;
    private final JsonFieldAccessor readAccessor;
    private final boolean writeEnabled;
    private final boolean readEnabled;
    private final int constructionIndex;
    private ObjectCodec<?> childCodec;

    public Declaration(
        String javaName,
        String prefix,
        String suffix,
        Type type,
        Class<?> rawType,
        JsonFieldAccessor writeAccessor,
        JsonFieldAccessor readAccessor,
        boolean writeEnabled,
        boolean readEnabled,
        int constructionIndex) {
      this.javaName = javaName;
      this.prefix = prefix;
      this.suffix = suffix;
      this.type = type;
      this.rawType = rawType;
      this.writeAccessor = writeAccessor;
      this.readAccessor = readAccessor;
      this.writeEnabled = writeEnabled;
      this.readEnabled = readEnabled;
      this.constructionIndex = constructionIndex;
    }

    public String javaName() {
      return javaName;
    }

    public String prefix() {
      return prefix;
    }

    public String suffix() {
      return suffix;
    }

    public boolean writeEnabled() {
      return writeEnabled;
    }

    public boolean readEnabled() {
      return readEnabled;
    }

    public int constructionIndex() {
      return constructionIndex;
    }

    public JsonFieldAccessor writeAccessor() {
      return writeAccessor;
    }

    public JsonFieldAccessor readAccessor() {
      return readAccessor;
    }

    public ObjectCodec<?> childCodec() {
      return childCodec;
    }

    private void resolve(ObjectCodec<?> owner, JsonTypeResolver resolver) {
      if (!(type instanceof Class)) {
        throw unsupported(owner, "requires an exact raw-class child", type);
      }
      if (rawType == Object.class || rawType.getTypeParameters().length != 0) {
        throw unsupported(owner, "does not support raw generic or Object children", type);
      }
      JsonTypeInfo typeInfo = resolver.getTypeInfo(type, rawType);
      if (!typeInfo.usesDefaultObjectCodec()) {
        throw unsupported(owner, "requires the standard ObjectCodec", type);
      }
      ObjectCodec<?> child = resolver.getObjectCodec(rawType);
      if (child.anyInfo() != null) {
        throw unsupported(owner, "does not support a JSON Any child", type);
      }
      JsonUnwrappedInfo childInfo = child.unwrappedInfo();
      if (childInfo != null) {
        if (childInfo.isResolving()) {
          throw new ForyJsonException(
              "Recursive @JsonUnwrapped property cycle from "
                  + owner.type().getName()
                  + "."
                  + javaName
                  + " to "
                  + child.type().getName());
        }
        childInfo.resolve(child, resolver);
      }
      childCodec = child;
    }

    private static ForyJsonException unsupported(
        ObjectCodec<?> owner, String reason, Type childType) {
      return new ForyJsonException(
          "@JsonUnwrapped property on "
              + owner.type().getName()
              + " "
              + reason
              + ": "
              + childType.getTypeName());
    }
  }

  /** Cold write-order entry before flattened child metadata is resolved. */
  public static final class WriteSpec {
    private final int kind;
    private final JsonFieldInfo field;
    private final Declaration declaration;

    private WriteSpec(int kind, JsonFieldInfo field, Declaration declaration) {
      this.kind = kind;
      this.field = field;
      this.declaration = declaration;
    }

    public static WriteSpec direct(JsonFieldInfo field) {
      return new WriteSpec(DIRECT, field, null);
    }

    public static WriteSpec group(Declaration declaration) {
      return new WriteSpec(GROUP, null, declaration);
    }

    public static WriteSpec any() {
      return new WriteSpec(ANY, null, null);
    }

    public int kind() {
      return kind;
    }

    public JsonFieldInfo field() {
      return field;
    }

    public Declaration declaration() {
      return declaration;
    }
  }

  /** Final root or group write entry. */
  public static final class WriteEntry {
    private final int kind;
    private final JsonFieldInfo field;
    private final Group group;

    private WriteEntry(int kind, JsonFieldInfo field, Group group) {
      this.kind = kind;
      this.field = field;
      this.group = group;
    }

    public static WriteEntry direct(JsonFieldInfo field) {
      return new WriteEntry(DIRECT, field, null);
    }

    public static WriteEntry group(Group group) {
      return new WriteEntry(GROUP, null, group);
    }

    public Group group() {
      return group;
    }

    public static WriteEntry any() {
      return new WriteEntry(ANY, null, null);
    }

    public int kind() {
      return kind;
    }

    public JsonFieldInfo field() {
      return field;
    }
  }

  /** One object boundary in a flattened read/write tree. */
  public static final class Group {
    private final Declaration declaration;
    private final ObjectCodec<?> parentCodec;
    private final ObjectCodec<?> childCodec;
    private final Group parent;
    private final int readIndex;
    private final boolean writeEnabled;
    private final boolean readEnabled;
    private WriteEntry[] writeEntries;

    private Group(
        Declaration declaration,
        ObjectCodec<?> parentCodec,
        Group parent,
        int readIndex,
        boolean writeEnabled,
        boolean readEnabled) {
      this.declaration = declaration;
      this.parentCodec = parentCodec;
      childCodec = declaration.childCodec;
      this.parent = parent;
      this.readIndex = readIndex;
      this.writeEnabled = writeEnabled;
      this.readEnabled = readEnabled;
    }

    public Declaration declaration() {
      return declaration;
    }

    public ObjectCodec<?> parentCodec() {
      return parentCodec;
    }

    public ObjectCodec<?> childCodec() {
      return childCodec;
    }

    public Group parent() {
      return parent;
    }

    public int readIndex() {
      return readIndex;
    }

    public boolean writeEnabled() {
      return writeEnabled;
    }

    public boolean readEnabled() {
      return readEnabled;
    }

    public WriteEntry[] writeEntries() {
      return writeEntries;
    }
  }

  /** One flattened input name and its terminal child construction owner. */
  public static final class ReadRoute {
    private final Group group;
    private final JsonFieldInfo field;
    private final JsonCreatorFieldInfo creatorField;

    private ReadRoute(Group group, JsonFieldInfo field, JsonCreatorFieldInfo creatorField) {
      this.group = group;
      this.field = field;
      this.creatorField = creatorField;
    }

    public Group group() {
      return group;
    }

    public JsonFieldInfo field() {
      return field;
    }

    public JsonCreatorFieldInfo creatorField() {
      return creatorField;
    }
  }

  private final class BuildContext {
    private final ObjectCodec<?> owner;
    private final JsonTypeResolver resolver;
    private final List<Group> groups = new ArrayList<>();
    private final List<ReadRoute> routes = new ArrayList<>();
    private final LinkedHashMap<String, String> names = new LinkedHashMap<>();
    private final Map<Long, String> hashes = new LinkedHashMap<>();
    private final Map<String, Integer> routeIndexes = new LinkedHashMap<>();

    private BuildContext(ObjectCodec<?> owner, JsonTypeResolver resolver) {
      this.owner = owner;
      this.resolver = resolver;
    }

    private void registerRootNames() {
      for (JsonFieldInfo field : owner.writeFields()) {
        registerName(field.name(), "parent." + field.name());
      }
      for (JsonFieldInfo field : owner.readFields()) {
        registerName(field.name(), "parent." + field.name());
      }
      if (owner.creatorInfo() != null) {
        for (JsonCreatorFieldInfo field : owner.creatorInfo().fields()) {
          registerName(field.name(), "parent." + field.name());
        }
      }
      if (directReservedNames != null) {
        for (String name : directReservedNames) {
          registerName(name, "parent." + name);
        }
      }
    }

    private Group buildGroup(
        Group parent,
        ObjectCodec<?> parentCodec,
        Declaration declaration,
        String outerPrefix,
        String outerSuffix,
        boolean ancestorWrites,
        boolean ancestorReads) {
      boolean writes = ancestorWrites && declaration.writeEnabled;
      boolean reads = ancestorReads && declaration.readEnabled;
      int readIndex = reads ? groups.size() : -1;
      Group group = new Group(declaration, parentCodec, parent, readIndex, writes, reads);
      if (reads) {
        groups.add(group);
      }
      String prefix = outerPrefix + declaration.prefix;
      String suffix = declaration.suffix + outerSuffix;
      String path =
          parent == null ? declaration.javaName : groupPath(parent) + "." + declaration.javaName;
      IdentityHashMap<Declaration, Group> childGroups = new IdentityHashMap<>();
      JsonUnwrappedInfo childInfo = declaration.childCodec.unwrappedInfo();
      if (childInfo != null) {
        for (Declaration childDeclaration : childInfo.declarations) {
          Group childGroup =
              buildGroup(
                  group, declaration.childCodec, childDeclaration, prefix, suffix, writes, reads);
          childGroups.put(childDeclaration, childGroup);
        }
      }

      List<WriteEntry> entries = new ArrayList<>();
      if (writes) {
        if (childInfo == null) {
          for (JsonFieldInfo field : declaration.childCodec.writeFields()) {
            entries.add(writeLeaf(field, declaration.childCodec, prefix, suffix, path));
          }
        } else {
          for (WriteSpec spec : childInfo.writeSpecs) {
            if (spec.kind == DIRECT) {
              entries.add(writeLeaf(spec.field, declaration.childCodec, prefix, suffix, path));
            } else if (spec.kind == GROUP) {
              Group childGroup = childGroups.get(spec.declaration);
              if (childGroup.writeEnabled) {
                entries.add(WriteEntry.group(childGroup));
              }
            } else {
              throw new ForyJsonException(
                  "JSON Any child cannot be unwrapped on " + owner.type().getName());
            }
          }
        }
        if (entries.isEmpty()) {
          throw new ForyJsonException(
              "Write-enabled @JsonUnwrapped property expands to no members: "
                  + owner.type().getName()
                  + "."
                  + path);
        }
      }
      group.writeEntries = entries.toArray(new WriteEntry[0]);

      if (reads) {
        int routeStart = routes.size();
        ObjectCodec<?> child = declaration.childCodec;
        if (child.creatorInfo() == null) {
          for (JsonFieldInfo field : child.readFields()) {
            String transformed = prefix + field.name() + suffix;
            String propertyOwner = path + "." + field.name();
            registerName(transformed, propertyOwner);
            JsonFieldInfo copy = field.withName(transformed, TypeRef.of(child.type()));
            copy.resolveTypes(resolver);
            addRoute(transformed, new ReadRoute(group, copy, null));
          }
        } else {
          for (JsonCreatorFieldInfo field : child.creatorInfo().fields()) {
            String transformed = prefix + field.name() + suffix;
            String propertyOwner = path + "." + field.name();
            registerName(transformed, propertyOwner);
            JsonCreatorFieldInfo copy = field.withName(transformed);
            copy.resolveType(resolver);
            addRoute(transformed, new ReadRoute(group, null, copy));
          }
        }
        if (routes.size() == routeStart && !hasReadableDescendant(group)) {
          throw new ForyJsonException(
              "Read-enabled @JsonUnwrapped property expands to no members: "
                  + owner.type().getName()
                  + "."
                  + path);
        }
      }
      return group;
    }

    private WriteEntry writeLeaf(
        JsonFieldInfo field, ObjectCodec<?> child, String prefix, String suffix, String path) {
      String transformed = prefix + field.name() + suffix;
      registerName(transformed, path + "." + field.name());
      JsonFieldInfo copy = field.withName(transformed, TypeRef.of(child.type()));
      copy.resolveTypes(resolver);
      return WriteEntry.direct(copy);
    }

    private boolean hasReadableDescendant(Group ancestor) {
      for (Group candidate : groups) {
        for (Group parent = candidate.parent; parent != null; parent = parent.parent) {
          if (parent == ancestor) {
            return true;
          }
        }
      }
      return false;
    }

    private void addRoute(String name, ReadRoute route) {
      Integer prior = routeIndexes.put(name, routes.size());
      if (prior != null) {
        throw new ForyJsonException("Duplicate flattened JSON input name " + name);
      }
      routes.add(route);
    }

    private void registerName(String name, String propertyOwner) {
      if (name.isEmpty()) {
        throw new ForyJsonException(
            "@JsonUnwrapped produces an empty JSON member name on " + owner.type().getName());
      }
      String priorOwner = names.putIfAbsent(name, propertyOwner);
      if (priorOwner != null && !priorOwner.equals(propertyOwner)) {
        throw new ForyJsonException(
            "Flattened JSON property collision on "
                + owner.type().getName()
                + " for "
                + name
                + ": "
                + priorOwner
                + " and "
                + propertyOwner);
      }
      long hash = JsonFieldNameHash.hash(name);
      String priorName = hashes.putIfAbsent(hash, name);
      if (priorName != null && !priorName.equals(name)) {
        throw new ForyJsonException(
            "JSON property name hash collision between " + priorName + " and " + name);
      }
    }

    private String groupPath(Group group) {
      StringBuilder builder = new StringBuilder();
      appendGroupPath(builder, group);
      return builder.toString();
    }

    private void appendGroupPath(StringBuilder builder, Group group) {
      if (group.parent != null) {
        appendGroupPath(builder, group.parent);
        builder.append('.');
      }
      builder.append(group.declaration.javaName);
    }
  }

  private static final class RouteTable {
    private final long[] hashes;
    private final int[] matches;
    private final int mask;

    private RouteTable(LinkedHashMap<String, String> names, Map<String, Integer> routeIndexes) {
      int size = 1;
      while (size < names.size() * 4) {
        size <<= 1;
      }
      hashes = new long[size];
      matches = new int[size];
      java.util.Arrays.fill(matches, UNKNOWN);
      mask = size - 1;
      for (String name : names.keySet()) {
        long hash = JsonFieldNameHash.hash(name);
        int slot = index(hash, mask);
        while (matches[slot] != UNKNOWN) {
          slot = (slot + 1) & mask;
        }
        hashes[slot] = hash;
        Integer routeIndex = routeIndexes.get(name);
        matches[slot] = routeIndex == null ? SKIP : routeIndex.intValue();
      }
    }

    private int match(long hash) {
      int slot = index(hash, mask);
      while (true) {
        int match = matches[slot];
        if (match == UNKNOWN || hashes[slot] == hash) {
          return match;
        }
        slot = (slot + 1) & mask;
      }
    }

    private boolean containsHash(long hash) {
      return match(hash) != UNKNOWN;
    }

    private static int index(long hash, int mask) {
      return ((int) (hash ^ (hash >>> 32))) & mask;
    }
  }
}
