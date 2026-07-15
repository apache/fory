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

package org.apache.fory.json.resolver;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import org.apache.fory.json.ForyJsonException;
import org.apache.fory.json.JsonConfig;
import org.apache.fory.json.annotation.JsonSubTypes.Inclusion;
import org.apache.fory.json.codec.JsonSubTypesInfo;
import org.apache.fory.json.codec.JsonValueCodec;
import org.apache.fory.json.meta.JsonClassNames;
import org.apache.fory.json.meta.JsonCodecFactory;
import org.apache.fory.json.meta.JsonDecodedMetadata;
import org.apache.fory.json.meta.JsonMetadataFormat;
import org.apache.fory.json.meta.JsonTypeMetadata;

/** Android generated metadata source isolated from the ordinary JVM registry. */
final class AndroidJsonSharedRegistry extends JsonSharedRegistry {
  private final JsonTypeMetadataRegistry metadataRegistry = new JsonTypeMetadataRegistry();

  static JsonSharedRegistry newRegistry(JsonConfig config) {
    return new AndroidJsonSharedRegistry(config);
  }

  private AndroidJsonSharedRegistry(JsonConfig config) {
    super(config, null, false);
  }

  @Override
  JsonTypeMetadataRegistry metadataRegistry() {
    return metadataRegistry;
  }

  @Override
  JsonCodecDeclaration codecDeclaration(Class<?> targetType) {
    if (targetType.isAnnotation() || hasIntrinsicCodec(targetType)) {
      return null;
    }
    checkSecure(targetType);
    synchronized (codecDeclarations) {
      JsonCodecDeclaration cached = codecDeclarations.get(targetType);
      if (cached != null) {
        return cached;
      }
      if (typesWithoutCodecDeclaration.contains(targetType)) {
        return null;
      }
      JsonCodecDeclaration resolved = resolveGeneratedDeclaration(targetType);
      if (resolved == null) {
        typesWithoutCodecDeclaration.add(targetType);
      } else {
        codecDeclarations.put(targetType, resolved);
      }
      return resolved;
    }
  }

  @Override
  JsonValueCodec<?> annotationCodec(
      Class<?> targetType,
      Class<? extends JsonValueCodec<?>> codecClass,
      JsonCodecFactory factory) {
    if (factory == null) {
      throw invalidGeneratedDeclaration(targetType, "selected codec has no generated factory");
    }
    checkCustomSecure(targetType);
    synchronized (annotationCodecs) {
      JsonValueCodec<?> codec = annotationCodecs.get(codecClass);
      if (codec != null) {
        return codec;
      }
      validateCodecClass(codecClass);
      codec = factory.create();
      if (codec == null || !codecClass.isInstance(codec)) {
        throw invalidCodecClass(codecClass, "generated factory returned an invalid codec", null);
      }
      annotationCodecs.put(codecClass, codec);
      return codec;
    }
  }

  private JsonCodecDeclaration resolveGeneratedDeclaration(Class<?> targetType) {
    JsonDecodedMetadata decoded =
        metadataRegistry.decodedSection(targetType, JsonTypeMetadata.DECLARATIONS);
    if (decoded.declarationCount() == 0) {
      throw invalidGeneratedDeclaration(targetType, "missing target declaration");
    }
    JsonDecodedMetadata.Declaration target = decoded.declaration(0);
    Class<?> declaredTarget = generatedDeclarationType(targetType, decoded, target.typeToken());
    if (declaredTarget != targetType) {
      throw invalidGeneratedDeclaration(
          targetType, "first declaration resolves to " + declaredTarget.getName());
    }
    if (target.hasCodec()) {
      GeneratedJsonCodecCandidate direct = generatedCandidate(target, targetType);
      direct = resolveGeneratedCodec(targetType, decoded, direct);
      JsonCodecFactory factory = generatedCodecFactory(targetType, decoded, direct);
      return AndroidJsonCodecDeclaration.create(
          direct.codecClass, factory, new Class<?>[] {targetType}, false);
    }
    List<JsonCodecCandidate> candidates = new ArrayList<>();
    Set<Class<?>> origins = Collections.newSetFromMap(new IdentityHashMap<Class<?>, Boolean>());
    for (int i = 1; i < decoded.declarationCount(); i++) {
      JsonDecodedMetadata.Declaration declaration = decoded.declaration(i);
      if (!declaration.hasCodec()) {
        continue;
      }
      Class<?> origin = generatedDeclarationType(targetType, decoded, declaration.typeToken());
      if (!origin.isAssignableFrom(targetType)) {
        throw invalidGeneratedDeclaration(
            targetType, origin.getName() + " is not an ancestor of the target");
      }
      if (!origins.add(origin)) {
        throw invalidGeneratedDeclaration(
            targetType, "duplicate codec declaration for " + origin.getName());
      }
      candidates.add(generatedCandidate(declaration, origin));
    }
    if (candidates.isEmpty()) {
      return null;
    }
    List<JsonCodecCandidate> frontier = mostSpecificDeclarations(candidates);
    for (int i = 0; i < frontier.size(); i++) {
      frontier.set(i, resolveGeneratedCodec(targetType, decoded, frontier.get(i)));
    }
    Collections.sort(frontier, DECLARATION_ORDER);
    Class<? extends JsonValueCodec<?>> codecClass = frontier.get(0).codecClass;
    for (int i = 1; i < frontier.size(); i++) {
      if (frontier.get(i).codecClass != codecClass) {
        throw inheritedCodecConflict(targetType, frontier);
      }
    }
    JsonCodecFactory factory = generatedCodecFactory(targetType, decoded, frontier.get(0));
    Class<?>[] selectedOrigins = new Class<?>[frontier.size()];
    for (int i = 0; i < frontier.size(); i++) {
      selectedOrigins[i] = frontier.get(i).declarationType;
    }
    return AndroidJsonCodecDeclaration.create(codecClass, factory, selectedOrigins, true);
  }

  private static GeneratedJsonCodecCandidate generatedCandidate(
      JsonDecodedMetadata.Declaration declaration, Class<?> origin) {
    return new GeneratedJsonCodecCandidate(
        origin, null, declaration.codecToken(), declaration.codecOperation());
  }

  private GeneratedJsonCodecCandidate resolveGeneratedCodec(
      Class<?> targetType, JsonDecodedMetadata decoded, JsonCodecCandidate candidate) {
    GeneratedJsonCodecCandidate generated = (GeneratedJsonCodecCandidate) candidate;
    Class<?> codec = generatedDeclarationType(targetType, decoded, generated.codecToken);
    if (!JsonValueCodec.class.isAssignableFrom(codec)) {
      throw invalidGeneratedDeclaration(targetType, codec.getName() + " is not a JsonValueCodec");
    }
    @SuppressWarnings("unchecked")
    Class<? extends JsonValueCodec<?>> codecClass = (Class<? extends JsonValueCodec<?>>) codec;
    validateCodecClass(codecClass);
    return new GeneratedJsonCodecCandidate(
        generated.declarationType, codecClass, generated.codecToken, generated.codecOperation);
  }

  private JsonCodecFactory generatedCodecFactory(
      Class<?> targetType, JsonDecodedMetadata decoded, JsonCodecCandidate candidate) {
    GeneratedJsonCodecCandidate generated = (GeneratedJsonCodecCandidate) candidate;
    return metadataRegistry.directOperation(
        targetType,
        JsonTypeMetadata.DECLARATIONS,
        decoded,
        generated.codecOperation,
        JsonMetadataFormat.CODEC_FACTORY,
        JsonCodecFactory.class);
  }

  private Class<?> generatedDeclarationType(
      Class<?> targetType, JsonDecodedMetadata decoded, int tokenIndex) {
    JsonDecodedMetadata.Token token = decoded.token(tokenIndex);
    Class<?> type =
        token.mode() == JsonMetadataFormat.TOKEN_INACCESSIBLE
            ? metadataRegistry.memberType(
                targetType, JsonTypeMetadata.DECLARATIONS, decoded, tokenIndex)
            : metadataRegistry.generatedType(
                targetType, JsonTypeMetadata.DECLARATIONS, decoded, tokenIndex);
    if (token.mode() == JsonMetadataFormat.TOKEN_INACCESSIBLE
        && !type.getName().equals(token.inaccessibleName())) {
      throw invalidGeneratedDeclaration(
          targetType, "binary name mismatch for " + token.inaccessibleName());
    }
    return type;
  }

  @Override
  JsonSubTypesInfo subTypesInfo(Class<?> baseType) {
    if (hasIntrinsicCodec(baseType)) {
      return null;
    }
    checkSecure(baseType);
    synchronized (subTypesCache) {
      JsonSubTypesInfo cached = subTypesCache.get(baseType);
      if (cached != null || subTypesCache.containsKey(baseType)) {
        return cached;
      }
      JsonDecodedMetadata decoded =
          metadataRegistry.decodedSection(baseType, JsonTypeMetadata.SUBTYPES);
      JsonSubTypesInfo resolved =
          decoded.subtypeTableCount() == 0 ? null : buildGeneratedSubTypes(baseType, decoded);
      subTypesCache.put(baseType, resolved);
      return resolved;
    }
  }

  private JsonSubTypesInfo buildGeneratedSubTypes(Class<?> baseType, JsonDecodedMetadata decoded) {
    JsonDecodedMetadata.SubtypeTable table = decoded.subtypeTable(0);
    Inclusion inclusion = generatedInclusion(baseType, table.inclusion());
    validateSubtypeHeader(baseType, inclusion, table.property(), table.entryCount());
    String[] names = new String[table.entryCount()];
    String[] classNames = new String[table.entryCount()];
    Set<String> logicalNames = new HashSet<>();
    Set<Long> logicalHashes = new HashSet<>();
    boolean hasStringEntry = false;
    for (int i = 0; i < table.entryCount(); i++) {
      JsonDecodedMetadata.SubtypeEntry entry = table.entry(i);
      validateSubtypeName(entry.name(), logicalNames, logicalHashes);
      names[i] = entry.name();
      String className = entry.className();
      if (!className.isEmpty()) {
        JsonClassNames.requireBinaryName(className, "@JsonSubTypes className");
        classNames[i] = className;
        hasStringEntry = true;
      } else {
        JsonDecodedMetadata.Token token = decoded.token(entry.typeToken());
        if (token.mode() == JsonMetadataFormat.TOKEN_INACCESSIBLE) {
          className = token.inaccessibleName();
          JsonClassNames.requireBinaryName(className, "generated @JsonSubTypes literal");
          classNames[i] = className;
        }
      }
    }
    validateStringSubtypeMode(baseType, hasStringEntry);
    for (String className : classNames) {
      if (className != null) {
        checkSecureName(className);
      }
    }
    if (hasStringEntry) {
      validateSubtypeBaseLoader(baseType);
    }
    Class<?>[] classes = new Class<?>[table.entryCount()];
    Set<Class<?>> classIdentities = Collections.newSetFromMap(new IdentityHashMap<>());
    Set<String> binaryNames = new HashSet<>();
    for (int i = 0; i < table.entryCount(); i++) {
      JsonDecodedMetadata.SubtypeEntry entry = table.entry(i);
      Class<?> subtype;
      if (!entry.className().isEmpty()) {
        subtype = loadExactClass(entry.className());
      } else {
        JsonDecodedMetadata.Token token = decoded.token(entry.typeToken());
        subtype =
            token.mode() == JsonMetadataFormat.TOKEN_INACCESSIBLE
                ? metadataRegistry.memberType(
                    baseType, JsonTypeMetadata.SUBTYPES, decoded, entry.typeToken())
                : metadataRegistry.generatedType(
                    baseType, JsonTypeMetadata.SUBTYPES, decoded, entry.typeToken());
        if (token.mode() == JsonMetadataFormat.TOKEN_INACCESSIBLE
            && !subtype.getName().equals(token.inaccessibleName())) {
          throw invalidGeneratedSubtypes(
              baseType, "binary name mismatch for " + token.inaccessibleName());
        }
      }
      validateSubtypeClass(baseType, subtype, classIdentities, binaryNames);
      classes[i] = subtype;
    }
    return new JsonSubTypesInfo(inclusion, table.property(), classes, names);
  }

  private static Inclusion generatedInclusion(Class<?> baseType, int inclusion) {
    switch (inclusion) {
      case 0:
        return Inclusion.PROPERTY;
      case 1:
        return Inclusion.WRAPPER_OBJECT;
      case 2:
        return Inclusion.WRAPPER_ARRAY;
      default:
        throw invalidGeneratedSubtypes(baseType, "invalid inclusion " + inclusion);
    }
  }

  static ForyJsonException invalidGeneratedDeclaration(Class<?> targetType, String reason) {
    return new ForyJsonException(
        "Invalid generated JSON declaration metadata for "
            + targetType.getName()
            + ": "
            + reason
            + ". Recompile the model with the matching fory-annotation-processor.");
  }

  private static ForyJsonException invalidGeneratedSubtypes(Class<?> baseType, String reason) {
    return new ForyJsonException(
        "Invalid generated JSON subtype metadata for "
            + baseType.getName()
            + ": "
            + reason
            + ". Recompile the model with the matching fory-annotation-processor.");
  }
}
