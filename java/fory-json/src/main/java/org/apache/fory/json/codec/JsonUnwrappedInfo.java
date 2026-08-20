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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.fory.annotation.Internal;
import org.apache.fory.json.ForyJsonException;
import org.apache.fory.json.meta.JsonCreatorFieldInfo;
import org.apache.fory.json.meta.JsonFieldAccessor;
import org.apache.fory.json.meta.JsonFieldInfo;
import org.apache.fory.json.meta.JsonFieldNameHash;
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
  private WriteEntry[] writeSteps;
  private int[] writeDepths;
  private int[] writeEnds;
  private int maxWriteDepth;
  private JsonFieldInfo[] writeFields;
  private Group[] groups;
  private ObjectCodec<?>[] groupCodecs;
  private int[] groupParents;
  private int[] groupEnds;
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

  public WriteEntry[] writeSteps() {
    requireResolved();
    return writeSteps;
  }

  public int[] writeDepths() {
    requireResolved();
    return writeDepths;
  }

  public int[] writeEnds() {
    requireResolved();
    return writeEnds;
  }

  public int maxWriteDepth() {
    requireResolved();
    return maxWriteDepth;
  }

  public JsonFieldInfo[] writeFields() {
    requireResolved();
    return writeFields;
  }

  public Group[] groups() {
    requireResolved();
    return groups;
  }

  public ObjectCodec<?>[] groupCodecs() {
    requireResolved();
    return groupCodecs;
  }

  public int[] groupParents() {
    requireResolved();
    return groupParents;
  }

  public int[] groupEnds() {
    requireResolved();
    return groupEnds;
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
    ArrayDeque<ResolveFrame> frames = new ArrayDeque<>();
    frames.addLast(new ResolveFrame(this, owner));
    try {
      while (!frames.isEmpty()) {
        ResolveFrame frame = frames.peekLast();
        JsonUnwrappedInfo info = frame.info;
        if (!frame.entered) {
          if (info.resolutionState == RESOLVED) {
            frames.removeLast();
            continue;
          }
          if (info.resolutionState == RESOLVING) {
            throw new ForyJsonException(
                "Recursive @JsonUnwrapped property cycle reaches " + frame.owner.type().getName());
          }
          info.resolutionState = RESOLVING;
          for (Declaration declaration : info.declarations) {
            declaration.resolve(frame.owner, resolver);
          }
          frame.entered = true;
        }
        boolean descended = false;
        while (frame.declarationIndex < info.declarations.length) {
          Declaration declaration = info.declarations[frame.declarationIndex++];
          JsonUnwrappedInfo child = declaration.childCodec.unwrappedInfo();
          if (child == null || child.resolutionState == RESOLVED) {
            continue;
          }
          if (child.resolutionState == RESOLVING) {
            throw new ForyJsonException(
                "Recursive @JsonUnwrapped property cycle from "
                    + frame.owner.type().getName()
                    + "."
                    + declaration.javaName
                    + " to "
                    + declaration.childCodec.type().getName());
          }
          frames.addLast(new ResolveFrame(child, declaration.childCodec));
          descended = true;
          break;
        }
        if (descended) {
          continue;
        }
        info.buildResolvedMetadata(frame.owner, resolver);
        info.resolutionState = RESOLVED;
        frames.removeLast();
      }
    } catch (RuntimeException | Error e) {
      for (ResolveFrame frame : frames) {
        if (frame.info.resolutionState == RESOLVING) {
          frame.info.clearResolution();
        }
      }
      throw e;
    }
  }

  private void clearResolution() {
    resolutionState = UNRESOLVED;
    writeEntries = null;
    writeSteps = null;
    writeDepths = null;
    writeEnds = null;
    maxWriteDepth = 0;
    writeFields = null;
    groups = null;
    groupCodecs = null;
    groupParents = null;
    groupEnds = null;
    readRoutes = null;
    routeTable = null;
    flattenedNames = null;
  }

  private void buildResolvedMetadata(ObjectCodec<?> owner, JsonTypeResolver resolver) {
    BuildContext context = new BuildContext(owner, resolver);
    context.registerRootNames();
    IdentityHashMap<Declaration, Group> rootGroups = new IdentityHashMap<>();
    for (Declaration declaration : declarations) {
      Group group = context.buildRootGroup(declaration);
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
    buildWriteSteps();
    groups = context.groups.toArray(new Group[0]);
    groupCodecs = new ObjectCodec<?>[groups.length];
    groupParents = new int[groups.length];
    groupEnds = new int[groups.length];
    for (int i = 0; i < groups.length; i++) {
      Group group = groups[i];
      groupCodecs[i] = group.childCodec;
      groupParents[i] = group.parent == null ? -1 : group.parent.readIndex;
      groupEnds[i] = i;
    }
    for (int i = groups.length - 1; i >= 0; i--) {
      int parent = groupParents[i];
      if (parent >= 0 && groupEnds[parent] < groupEnds[i]) {
        groupEnds[parent] = groupEnds[i];
      }
    }
    readRoutes = context.routes.toArray(new ReadRoute[0]);
    routeTable = new RouteTable(context.names, context.routeIndexes);
    flattenedNames = context.names.keySet().toArray(new String[0]);
  }

  private void buildWriteSteps() {
    List<WriteEntry> steps = new ArrayList<>();
    List<Integer> depths = new ArrayList<>();
    List<Integer> ends = new ArrayList<>();
    List<JsonFieldInfo> fields = new ArrayList<>();
    ArrayDeque<WriteWalk> walk = new ArrayDeque<>();
    walk.addLast(new WriteWalk(writeEntries, 0, -1));
    int deepest = 0;
    while (!walk.isEmpty()) {
      WriteWalk frame = walk.peekLast();
      if (frame.index == frame.entries.length) {
        if (frame.groupStep >= 0) {
          ends.set(frame.groupStep, steps.size());
        }
        walk.removeLast();
        continue;
      }
      WriteEntry entry = frame.entries[frame.index++];
      int stepIndex = steps.size();
      steps.add(entry);
      depths.add(frame.depth);
      ends.add(-1);
      deepest = Math.max(deepest, frame.depth);
      if (entry.kind == DIRECT) {
        fields.add(entry.field);
      } else if (entry.kind == GROUP) {
        walk.addLast(new WriteWalk(entry.group.writeEntries, frame.depth + 1, stepIndex));
      }
    }
    writeSteps = steps.toArray(new WriteEntry[0]);
    writeDepths = new int[depths.size()];
    writeEnds = new int[ends.size()];
    for (int i = 0; i < depths.size(); i++) {
      writeDepths[i] = depths.get(i);
      writeEnds[i] = ends.get(i);
    }
    maxWriteDepth = deepest;
    writeFields = fields.toArray(new JsonFieldInfo[0]);
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
      ObjectCodec<?> child = resolver.getUnwrappedObjectCodec(rawType);
      if (child == null) {
        throw unsupported(owner, "requires the standard ObjectCodec", type);
      }
      if (child.anyInfo() != null) {
        throw unsupported(owner, "does not support a JSON Any child", type);
      }
      child.resolveDirectTypes(resolver);
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

  private static final class ResolveFrame {
    private final JsonUnwrappedInfo info;
    private final ObjectCodec<?> owner;
    private int declarationIndex;
    private boolean entered;

    private ResolveFrame(JsonUnwrappedInfo info, ObjectCodec<?> owner) {
      this.info = info;
      this.owner = owner;
    }
  }

  private static final class WriteWalk {
    private final WriteEntry[] entries;
    private final int depth;
    private final int groupStep;
    private int index;

    private WriteWalk(WriteEntry[] entries, int depth, int groupStep) {
      this.entries = entries;
      this.depth = depth;
      this.groupStep = groupStep;
    }
  }

  private final class BuildContext {
    private final ObjectCodec<?> owner;
    private final JsonTypeResolver resolver;
    private final List<Group> groups = new ArrayList<>();
    private final List<ReadRoute> routes = new ArrayList<>();
    private final StringBuilder prefix = new StringBuilder();
    private final ArrayDeque<String> suffixes = new ArrayDeque<>();
    private int suffixLength;
    // Keep collision owners structural. Full Java property paths are diagnostic-only and can be
    // quadratic in a deep tree if eagerly retained for every flattened name.
    private final LinkedHashMap<String, NameOwner> names = new LinkedHashMap<>();
    private final Map<Long, String> hashes = new LinkedHashMap<>();
    private final Map<String, Integer> routeIndexes = new LinkedHashMap<>();

    private BuildContext(ObjectCodec<?> owner, JsonTypeResolver resolver) {
      this.owner = owner;
      this.resolver = resolver;
    }

    private void registerRootNames() {
      for (JsonFieldInfo field : owner.writeFields()) {
        registerName(field.name(), null, field.name());
      }
      for (JsonFieldInfo field : owner.readFields()) {
        registerName(field.name(), null, field.name());
      }
      if (owner.creatorInfo() != null) {
        for (JsonCreatorFieldInfo field : owner.creatorInfo().fields()) {
          registerName(field.name(), null, field.name());
        }
      }
      if (directReservedNames != null) {
        for (String name : directReservedNames) {
          registerName(name, null, name);
        }
      }
    }

    private Group buildRootGroup(Declaration declaration) {
      BuildFrame root = newBuildFrame(null, owner, declaration, true, true);
      ArrayDeque<BuildFrame> frames = new ArrayDeque<>();
      frames.addLast(root);
      while (!frames.isEmpty()) {
        BuildFrame frame = frames.peekLast();
        if (frame.childInfo != null && frame.childIndex < frame.childInfo.declarations.length) {
          Declaration childDeclaration = frame.childInfo.declarations[frame.childIndex++];
          BuildFrame child =
              newBuildFrame(
                  frame,
                  frame.group.childCodec,
                  childDeclaration,
                  frame.group.writeEnabled,
                  frame.group.readEnabled);
          frame.childGroups.put(childDeclaration, child.group);
          frames.addLast(child);
          continue;
        }
        finishGroup(frame);
        leaveFrame(frame);
        frames.removeLast();
      }
      return root.group;
    }

    private BuildFrame newBuildFrame(
        BuildFrame parent,
        ObjectCodec<?> parentCodec,
        Declaration declaration,
        boolean ancestorWrites,
        boolean ancestorReads) {
      boolean writes = ancestorWrites && declaration.writeEnabled;
      boolean reads = ancestorReads && declaration.readEnabled;
      int readIndex = reads ? groups.size() : -1;
      Group group =
          new Group(
              declaration,
              parentCodec,
              parent == null ? null : parent.group,
              readIndex,
              writes,
              reads);
      if (reads) {
        groups.add(group);
      }
      int prefixStart = prefix.length();
      int suffixStart = suffixLength;
      prefix.append(declaration.prefix);
      suffixes.addLast(declaration.suffix);
      suffixLength += declaration.suffix.length();
      // Frames retain shared-state offsets; cumulative strings would retain quadratic data.
      return new BuildFrame(
          group, declaration.childCodec.unwrappedInfo(), routes.size(), prefixStart, suffixStart);
    }

    private void leaveFrame(BuildFrame frame) {
      prefix.setLength(frame.prefixStart);
      suffixes.removeLast();
      suffixLength = frame.suffixStart;
    }

    private void finishGroup(BuildFrame frame) {
      Group group = frame.group;
      Declaration declaration = group.declaration;
      JsonUnwrappedInfo childInfo = frame.childInfo;
      List<WriteEntry> entries = new ArrayList<>();
      if (group.writeEnabled) {
        if (childInfo == null) {
          for (JsonFieldInfo field : declaration.childCodec.writeFields()) {
            entries.add(writeLeaf(frame, field));
          }
        } else {
          for (WriteSpec spec : childInfo.writeSpecs) {
            if (spec.kind == DIRECT) {
              entries.add(writeLeaf(frame, spec.field));
            } else if (spec.kind == GROUP) {
              Group childGroup = frame.childGroups.get(spec.declaration);
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
                  + groupPath(group));
        }
      }
      group.writeEntries = entries.toArray(new WriteEntry[0]);

      if (group.readEnabled) {
        ObjectCodec<?> child = declaration.childCodec;
        if (child.creatorInfo() == null) {
          for (JsonFieldInfo field : child.readFields()) {
            String transformed = transformedName(field.name());
            registerName(transformed, group, field.name());
            JsonFieldInfo copy = field.withName(transformed, TypeRef.of(child.type()));
            copy.resolveTypes(resolver);
            addRoute(transformed, new ReadRoute(group, copy, null));
          }
        } else {
          for (JsonCreatorFieldInfo field : child.creatorInfo().fields()) {
            String transformed = transformedName(field.name());
            registerName(transformed, group, field.name());
            JsonCreatorFieldInfo copy = field.withName(transformed);
            copy.resolveType(resolver);
            addRoute(transformed, new ReadRoute(group, null, copy));
          }
        }
        if (routes.size() == frame.routeStart) {
          throw new ForyJsonException(
              "Read-enabled @JsonUnwrapped property expands to no members: "
                  + owner.type().getName()
                  + "."
                  + groupPath(group));
        }
      }
    }

    private WriteEntry writeLeaf(BuildFrame frame, JsonFieldInfo field) {
      Group group = frame.group;
      String transformed = transformedName(field.name());
      registerName(transformed, group, field.name());
      JsonFieldInfo copy = field.withName(transformed, TypeRef.of(group.childCodec.type()));
      copy.resolveTypes(resolver);
      return WriteEntry.direct(copy);
    }

    private String transformedName(String name) {
      if (prefix.length() == 0 && suffixLength == 0) {
        return name;
      }
      StringBuilder transformed =
          new StringBuilder(prefix.length() + name.length() + suffixLength)
              .append(prefix)
              .append(name);
      Iterator<String> suffix = suffixes.descendingIterator();
      while (suffix.hasNext()) {
        transformed.append(suffix.next());
      }
      return transformed.toString();
    }

    private String groupPath(Group group) {
      ArrayDeque<Group> ancestors = new ArrayDeque<>();
      for (Group current = group; current != null; current = current.parent) {
        ancestors.addFirst(current);
      }
      StringBuilder builder = new StringBuilder();
      for (Group ancestor : ancestors) {
        if (builder.length() != 0) {
          builder.append('.');
        }
        builder.append(ancestor.declaration.javaName);
      }
      return builder.toString();
    }

    private void addRoute(String name, ReadRoute route) {
      Integer prior = routeIndexes.put(name, routes.size());
      if (prior != null) {
        throw new ForyJsonException("Duplicate flattened JSON input name " + name);
      }
      routes.add(route);
    }

    private void registerName(String name, Group group, String propertyName) {
      if (name.isEmpty()) {
        throw new ForyJsonException(
            "@JsonUnwrapped produces an empty JSON member name on " + owner.type().getName());
      }
      NameOwner propertyOwner = new NameOwner(group, propertyName);
      NameOwner priorOwner = names.putIfAbsent(name, propertyOwner);
      if (priorOwner != null && !priorOwner.sameProperty(propertyOwner)) {
        throw new ForyJsonException(
            "Flattened JSON property collision on "
                + owner.type().getName()
                + " for "
                + name
                + ": "
                + propertyOwner(priorOwner)
                + " and "
                + propertyOwner(propertyOwner));
      }
      long hash = JsonFieldNameHash.hash(name);
      String priorName = hashes.putIfAbsent(hash, name);
      if (priorName != null && !priorName.equals(name)) {
        throw new ForyJsonException(
            "JSON property name hash collision between " + priorName + " and " + name);
      }
    }

    private String propertyOwner(NameOwner nameOwner) {
      return nameOwner.group == null
          ? "parent." + nameOwner.propertyName
          : groupPath(nameOwner.group) + "." + nameOwner.propertyName;
    }

    private final class NameOwner {
      private final Group group;
      private final String propertyName;

      private NameOwner(Group group, String propertyName) {
        this.group = group;
        this.propertyName = propertyName;
      }

      private boolean sameProperty(NameOwner other) {
        return group == other.group && propertyName.equals(other.propertyName);
      }
    }

    private final class BuildFrame {
      private final Group group;
      private final JsonUnwrappedInfo childInfo;
      private final int routeStart;
      private final int prefixStart;
      private final int suffixStart;
      private final IdentityHashMap<Declaration, Group> childGroups = new IdentityHashMap<>();
      private int childIndex;

      private BuildFrame(
          Group group,
          JsonUnwrappedInfo childInfo,
          int routeStart,
          int prefixStart,
          int suffixStart) {
        this.group = group;
        this.childInfo = childInfo;
        this.routeStart = routeStart;
        this.prefixStart = prefixStart;
        this.suffixStart = suffixStart;
      }
    }
  }

  private static final class RouteTable {
    private final long[] hashes;
    private final int[] matches;
    private final int mask;

    private RouteTable(Map<String, ?> names, Map<String, Integer> routeIndexes) {
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
