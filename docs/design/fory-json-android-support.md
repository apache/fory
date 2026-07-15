# Fory JSON Android Support Design

Status: Implemented

## Summary

Fory JSON supports Android API 26 and later with Android Gradle Plugin 8.0 or later release builds using R8 full mode. Android applications opt a Java type into compile-time metadata generation with a new `@JsonType` annotation and run the existing interpreted Fory JSON codecs at runtime. The annotation processor generates all facts that Android cannot safely discover after R8 shrinking, including declaration annotations, nested type-use annotations, generic type structure, members, constructors, subtype declarations, and direct member access code.

The processor does not generate a complete JSON codec. A complete codec would embed choices owned by a particular `ForyJsonBuilder`, such as field or JavaBean discovery, naming, null inclusion, registered exact codecs, and generated-code policy. Instead, the existing `ObjectCodecBuilder` remains the only owner of property normalization and builds the same `ObjectCodec` model from either JVM reflection metadata or Android generated metadata.

The generated companion contains configuration-independent `JsonFieldAccessor` implementations and narrow constructor and any-setter invokers. Members whose complete Java expression is source-accessible use direct typed Java access. Source-inaccessible fields, getters, setters, and Any setters use cached exact-shape API 26 method handles in their existing owners. Source-inaccessible no-argument and record constructors use Core's cached `Constructor` owner; source-inaccessible `@JsonCreator` constructors and static factories use cached `Constructor` or `Method` invokers. Each reflective member is resolved once on the cold metadata-loading path and precisely retained by generated R8 rules. Construction reuses a static empty array or the caller-owned fixed argument array, so it adds no successful-path carrier allocation. No Android hot path performs member discovery, creates an argument array for ordinary access, performs a metadata lookup, or checks the platform.

In this design, “runtime reflection serialization” means that Android uses Fory JSON's existing interpreted runtime codec pipeline rather than ahead-of-time complete codecs. Compile-time metadata replaces reflection for structure and annotations, generated access thunks replace ordinary member reflection, and cold reflection only resolves precisely retained members that cannot be named in generated Java source. Field, getter, setter, and Any access then uses typed method handles; construction and creators use cached exact `Constructor` or `Method` objects with caller-owned argument arrays.

Non-Android JVM metadata discovery, runtime code generation, asynchronous compilation, candidate ordering, and hot codec loops retain their current paths. The JVM path does not look for `@JsonType` companions.

## Motivation

Current Fory JSON object support discovers Java structure and annotations at runtime. That model is not complete on Android because R8 can remove or rename members and because Android reflection does not provide every Java annotation/type API used by the JVM implementation. Two current guards explicitly disable `@JsonCodec` discovery on Android: declaration discovery in `JsonSharedRegistry` and type-use discovery in `JsonTypeUse`. Runtime JSON code generation is also unsuitable for Android, even though `ForyJsonBuilder` enables it by default.

Fory Core already establishes the correct Android direction: annotation processing, deterministic generated-class lookup, Android-safe type metadata, precise generated consumer rules, and typed field access. Fory JSON must extend those patterns without creating a second property model or freezing builder-dependent decisions at compilation time.

## Goals

- Support the complete Fory JSON object feature set on Android API 26 and later, except Java platform features unavailable on that API level.
- Support R8 full mode with minification, optimization, shrinking, and obfuscation enabled and without application-authored broad keep rules.
- Preserve all `ForyJsonBuilder` configurations and exact codec registrations.
- Extract declaration-level and every nested type-use `@JsonCodec` occurrence in the annotation processor. Android runtime annotation reflection is never its source of truth.
- Generate allocation-free hot member access where Java access rules allow direct access.
- Preserve source-inaccessible member support in the existing Core field/construction and Fory JSON method/creator/Any owners rather than adding parallel access abstractions.
- Keep `ObjectCodecBuilder`, `JsonTypeResolver`, `JsonTypeInfo`, and `ObjectCodec` as the existing semantic and runtime owners.
- Introduce no non-Android JVM startup or steady-state performance regression.
- Fail deterministically and actionably when metadata is absent, stale, malformed, or removed by a shrinker.
- Keep generated artifacts deterministic and incrementally replaceable. Because companion lookup is name-based, `@JsonType` is also an explicit R8 retention declaration for that type and its companion.

## Non-goals

- Generating complete reader and writer codecs during annotation processing.
- Runtime bytecode generation on Android.
- Reflective fallback for an Android object type missing generated metadata.
- Broad package keep rules, runtime classpath scans, `ServiceLoader`, generated global indexes, or a process-global registry of application classes.
- Android support for a third-party object type that cannot be annotated and has no exact custom codec. Such a type must be handled with `registerCodec`.
- Default object mapping for an application class whose stateful superclass is supplied only through incomplete Android boot-class stubs. Use an exact, type-use, or declaration codec for that hierarchy.
- Kotlin source processing. This design is for Java annotation processing. Complete Kotlin support requires a Kotlin Symbol Processing frontend that emits the same runtime metadata ABI.
- Backporting Java records to Android API 26. Record support follows the Android platform level described below.

## Required User API

### `@JsonType`

Add the following marker in `fory-json`:

```java
package org.apache.fory.json.annotation;

@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface JsonType {}
```

`@JsonType` is the shared platform-metadata marker introduced by the GraalVM native-image support.
It is not inherited and has no ordinary JVM JSON effect. Its runtime retention remains the GraalVM
feature's source of reachable reflection metadata. The Android annotation processor independently
discovers the same annotation during compilation and generates the Android metadata companion;
Android runtime code never reads `@JsonType`, JSON structure, or `@JsonCodec` values from runtime
annotations.

Eligible declarations are top-level classes and static nested classes, including private nested declarations and declarations inside a private enclosing chain. The generated companion does not need a source reference to an inaccessible target: it records the exact binary name, and the stable base constructor resolves that name without initialization through the companion/target defining loader and compares the resulting `Class` identity with the requested target. The processor rejects local classes, anonymous classes, and a non-static annotated member because those declarations do not have the required stable binary identity or have an enclosing-instance construction dependency. An enclosing class itself need not be static when Java permits it to declare the static annotated member.

Classes, records, interfaces, abstract classes, and enums may carry `@JsonType`. Interfaces and abstract classes can own `@JsonSubTypes` or a declaration codec even though they cannot build a default `ObjectCodec`; enums can own a declaration codec that precedes the built-in enum path. Annotation declarations are rejected, matching the current codec-declaration rules. A concrete class with neither a supported construction path nor a complete codec remains a runtime error when the builder actually selects default object mapping.

`@JsonCodec` retains its existing `RUNTIME` retention because the non-Android JVM path continues to discover it reflectively. The processor reads the same annotation through `AnnotationMirror`; Android runtime code never reads it from the class file.

On Android, every object type reached through default object serialization must satisfy one of these conditions:

1. it is annotated with `@JsonType` and has a matching generated companion;
2. an exact codec is registered with `ForyJsonBuilder.registerCodec`;
3. a complete applicable `@JsonCodec` was captured in the enclosing generated metadata; or
4. the type is handled by a built-in codec.

An object reachable only at runtime, including a subtype, must independently meet the same rule. Exact TYPE_USE codecs are embedded by the enclosing annotated owner. Declaration-level `@JsonCodec` and `@JsonSubTypes` discovery for a raw type require that raw declaration's own companion. An enclosing owner cannot make an unannotated raw type's declaration annotation discoverable. There is no reflective fallback.

The metadata requirement also applies to application/user-library enums and subclasses or implementations of container/date/other built-in families, even when the final representation is a built-in codec. Their companion is how Android proves that no declaration codec overrides the built-in. Only exact intrinsic built-ins owned by `JsonSharedRegistry`—platform classes and explicitly registered exact library built-ins—bypass companion lookup. A third-party type that cannot be annotated uses an exact registered codec.

### Build configuration

Android Java users apply the Fory JSON Gradle plugin to every Android application and Android
library module and add matching versions of the runtime and processor:

```gradle
buildscript {
  repositories {
    google()
    mavenCentral()
  }
  dependencies {
    classpath "org.apache.fory:fory-json-gradle-plugin:<version>"
  }
}

apply plugin: "com.android.application" // Or com.android.library.
apply plugin: "org.apache.fory.json"

dependencies {
  implementation("org.apache.fory:fory-json:<version>")
  annotationProcessor("org.apache.fory:fory-annotation-processor:<version>")
}
```

The processor has a compile dependency on `fory-json`; neither `fory-json` nor the processor owns an
Android variant. The `fory-json-gradle-plugin` module owns only Android build integration and uses
public Android Gradle Plugin APIs. A version mismatch is diagnosed by the generated ABI check at
runtime and should also be prevented by dependency-management tests.

No application ProGuard file is required for annotated types. The processor writes one exact
per-type rule carrier under `META-INF/fory-json/r8/`. Android Gradle Plugin does not preserve or
consume annotation-processor `CLASS_OUTPUT` resources as final R8 rules for every Android ownership
path, so the Gradle plugin is required. For an application variant, it scans the public
`ScopedArtifacts.Scope.ALL` class graph and adds one canonical merged file to the variant's public
ProGuard inputs. This includes application classes, same-build Android-library classes, and JAR
dependencies. For an Android library variant, it transforms the public AAR artifact, removes the
private carriers from `classes.jar`, and appends their exact contents to the standard root
`proguard.txt` consumer rules. A Java library keeps carriers in its JAR until a consuming Android
application collects them. The application-module, Java-library JAR, same-build Android-library,
and published AAR paths are release-build acceptance fixtures. This follows Android's
[library optimization guidance](https://developer.android.com/topic/performance/app-optimization/library-optimization)
and is verified using
[R8 full mode](https://developer.android.com/topic/performance/app-optimization/full-mode).

## Ownership Model

| Concern                   | Owner                                                                | Required behavior                                                                                  |
| ------------------------- | -------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------- |
| User opt-in               | `@JsonType`                                                          | Marks one Java declaration for metadata generation only.                                           |
| Source extraction         | `ForyJsonProcessor`                                                  | Reads source declarations, generic types, all JSON annotations, and nested `@JsonCodec` type uses. |
| Generated artifact        | One metadata companion per `@JsonType`                               | Stores independently lazy configuration-independent sections and singleton accessors/invokers.     |
| Generated ABI             | `JsonTypeMetadata` and related metadata value types in `fory-json`   | Defines only the data and call shapes consumed by the runtime.                                     |
| Android companion lookup  | `JsonTypeMetadataRegistry` owned by `JsonSharedRegistry`             | Loads, validates, and caches metadata for one `ForyJson` registry.                                 |
| Property semantics        | Existing `ObjectCodecBuilder`                                        | Merges members and applies the active builder configuration exactly once.                          |
| Type and codec resolution | Existing `JsonTypeResolver` and `JsonSharedRegistry`                 | Preserve precedence, recursion, security, subtype, and five capability slots.                      |
| Runtime codec execution   | Existing `ObjectCodec`, readers, and writers                         | Uses the already-selected accessors and contains no metadata/platform branch.                      |
| Source-inaccessible field | Existing Fory Core typed `FieldAccessor` through `JsonFieldAccessor` | Resolves the field once and invokes cached typed API 26 method handles.                            |
| Inaccessible methods      | Existing JSON accessor and Any owners                                | Resolve exact members once and invoke cached fixed-shape API 26 method handles.                    |
| Inaccessible construction | Core `ObjectInstantiators` and JSON `JsonCreatorInvoker`             | Cache the exact `Constructor` or creator `Method` and reuse owner-supplied argument arrays.        |
| R8 rule text              | `ForyJsonProcessor`                                                  | Emits one exact per-type carrier without knowing an Android variant.                               |
| Android R8 publication    | `fory-json-gradle-plugin`                                            | Collects application rules and publishes standard AAR consumer rules through public variant APIs.  |
| R8 retention              | Per-type exact generated rule                                        | Retains only convention names, cold-resolved members, constructors, and inaccessible type tokens.  |

There is one semantic pipeline. JVM reflection and Android generated metadata are two cold input collectors for the same `ObjectCodecBuilder` normalization and codec construction. They are not separate JSON implementations.

## Module and Processor Design

### Processor placement

Extend `fory-annotation-processor` with an independent `ForyJsonProcessor`. Do not add JSON behavior to `ForyStructProcessor`: the two annotations have different generated ABIs, reachability rules, and diagnostics. Both processors may share a narrowly extracted source type-use scanner.

Add `ForyJsonProcessor` to `META-INF/services/javax.annotation.processing.Processor`. Register it independently in `META-INF/gradle/incremental.annotation.processors` as `aggregating`. One annotated declaration still produces one source file and one exact R8-rule carrier with that declaration as an originating element, and the processor emits no global registry or aggregate resource. Aggregating classification is required because a closed companion embeds declarations, and for default-object types also members, generic declarations, and annotations from unannotated ancestors; a private or annotation-only ancestor change might not otherwise recompile the annotated child.

The existing type-use extraction logic used by `ForyStructProcessor` is the starting point, including the javac tree fallback needed to retain nested Java 8 type-use annotations. The shared scanner must remain annotation-processing-only and must not leak compiler types into `fory-json`.

The processor artifact keeps Java 8 class-file compatibility and does not directly link `ElementKind.RECORD`, `RecordComponentElement`, or newer compiler-model methods. Extract the version-neutral record adapter already established by `ForyStructProcessor`: detect `ElementKind` by its name, invoke `TypeElement.getRecordComponents` reflectively only on a compiler that exposes it, and return ordinary Java 8 `Element`/`ExecutableElement` views to both processors. Loading the processor and compiling non-record models remains supported on the processor's minimum JDK; record processing requires JDK 17 or later and fails with a focused compiler diagnostic if the compiler model is unavailable.

### Processing rounds

For each `@JsonType` declaration, the processor performs these steps:

1. Validate the declaration form and generated-name uniqueness.
2. Extract the declaration-codec and subtype sections without traversing object members.
3. Use the same closed terminal-family definitions as `JsonSharedRegistry` to decide whether the type can ever reach default object mapping after exact/type-use/declaration/subtype resolution. Only a reachable default-object type traverses the superclass chain base-first and public method/interface model with the same eligibility rules as `ObjectCodecBuilder`.
4. For such a type, extract fields, public getters/setters, Any members, creators, record components, property order, and all related annotations.
5. Extract complete generic source types and nested type-use `@JsonCodec` occurrences for fields, method returns, method parameters, constructor parameters, and record components.
6. Validate facts that are invalid under every builder configuration, including invalid annotation targets, inaccessible codec constructors, conflicts inside one declared type tree, and unsupported declaration forms. Cross-member field/getter/setter conflicts remain runtime validation after the active builder selects its member model.
7. Generate the metadata companion and its precise R8 rule carrier.

If a referenced source type is still incomplete in the current round, processing is deferred to a later round. An unresolved required symbol in the final round is a compilation error, not partially generated metadata.

The terminal-family decision is not a second codec resolver. `JsonSharedRegistry` remains the semantic owner of the exact intrinsic and assignable built-in families; the processor consumes the same versioned binary-name catalog and uses `Types.isAssignable` only to answer whether an object-member section is reachable. Tests enumerate the runtime and processor classifications together. An enum, interface, abstract declaration, unsupported terminal type, or built-in-family subtype therefore emits only the declaration/subtype sections it can use. A normal concrete child still embeds inherited members, including members from an otherwise terminal or unannotated ancestor, when that child's own resolution can reach default object mapping. A runtime request for an omitted object section is an ABI/processor defect and fails rather than reflecting.

Candidate extraction must reproduce the current reflection model exactly:

- fields are traversed from the oldest superclass to the concrete class;
- static, transient, synthetic, and `Class`-typed fields are excluded;
- two eligible fields with the same Java name in one hierarchy remain an error;
- JavaBean discovery uses all public inherited and interface methods, matching `Class.getMethods()`;
- an unannotated overriding method removes an inherited annotated method from the effective declaration set where the current builder does so;
- both collectors preserve the current semantic phases—base-to-derived fields followed by the effective public method set—and merge the same logical members. After merging, `ObjectCodecBuilder` canonicalizes the base logical-property order by Java logical name before applying explicit property order, indexes, alphabetic remainder, and Any placement. This intentionally replaces unspecified reflection encounter order on the JVM so Android, JVM interpreted, and JVM generated output are deterministic. It is a documented breaking change for users who relied on field/method encounter order; explicit `@JsonPropertyOrder` remains the user-owned order contract. The sort runs only during cold codec construction.

### Deterministic generated names

For a binary name such as `a.b.Outer$Inner`, generate a companion in package `a.b`, for example `Outer_Inner_ForyJsonMetadata`. Use the same collision-free escaping rules as the Core serializer processor for `$`, `_`, and non-identifier Unicode. The name mapping is a versioned function shared by the processor and runtime.

The runtime derives exactly one name. After security-checking the known target class, it calls `Class.forName(name, false, targetType.getClassLoader())`; loading without initialization prevents generated static code from running before validation. The target's defining loader is the companion owner because both classes are generated into the same application or library artifact. The class loader captured by `ForyJsonBuilder` remains exclusively responsible for `JsonSubTypes.Type.className` resolution. There is no resource scan or fallback name.

The registry then validates that the loaded class is a concrete `JsonTypeMetadata` and validates its public `(Class)` constructor without initialization. The generated class has no non-constant class initializer. Its constructor only calls `JsonTypeMetadata(target, EXPECTED_BINARY_NAME, ABI_VERSION)`. The base constructor resolves that name without initialization through the generated class's defining loader and validates exact target identity and ABI, so a same-name class from another loader is rejected without requiring generated source to name a private target. A section request initializes only its inert fact/name holder. The existing semantic owner later requests selected indexes from bounded direct-literal switches or generated operation subclasses. A direct class literal can be resolved during ART/JVM verification, but a class literal does not initialize its class. The registry applies the selected occurrence's existing validation before initializing an operation or executing user code.

### Generated ABI version

`JsonTypeMetadata` exposes a compile-time constant ABI version and permanent integer constants `DECLARATIONS`, `SUBTYPES`, and `OBJECT`. Its protected constructor receives the requested target, generated target binary name, and generated version, and validates all three before metadata is accessed. Each companion implements permanent public `Object metadata(int section)`, `Class<?> metadataType(int section, int index)`, and `Object metadataOperation(int section, int index)` bootstrap methods. One private fact/name holder exists per section. Bounded switch chunks return direct class literals without a class per token. Each generated field accessor, method accessor, invoker, or instantiator subclass owns its own static `INSTANCE`, and the indexed operation switch reads only the selected subclass's singleton; both valid directions of one field reference the same field-accessor index. Handle-backed operations are constructed by the selected runtime owner. Asking only for declaration state does not initialize subtype or object-member sections, and reading facts constructs no accessor, invoker, or instantiator. Invalid section/index values are rejected before executing the selected branch.

After an exact version check, the registry invokes `metadata(section)` and casts the result to the permanent `JsonTypeMetadataData` transport. It contains only compact versioned `byte[][]` fact chunks and binary-name strings for source-inaccessible tokens. The decoder validates framing, counts, tags, indexes, declaration keys, and structural annotation rules without loading a token or constructing an operation. Facts identify types and operations by stable section-local indexes; directly nameable types are not compared by their original binary-name strings because R8 may rename them.

The existing declaration, subtype, or `ObjectCodecBuilder` owner first selects the candidate occurrences required by the active builder. It then requests only their type and operation indexes. A selected direct type comes from `metadataType`; a selected source-inaccessible type is loaded by exact binary name through the role's existing loader. The owning resolver applies the same role-specific order as the JVM path before it requests the corresponding operation. A complete subtype table remains table-eager because the current subtype owner validates the table atomically, but an unselected declaration codec, JavaBean member, creator, Any member, or field direction triggers no checker call, class initialization, operation construction, or candidate publication. A source-inaccessible string token additionally is not loaded before selection and its name-level check. Direct symbolic class references may be loaded or resolved by the verifier, which is not treated as a lazy-security boundary. Two builders can therefore select disjoint candidates from the same companion without one builder failing on the other's unused metadata.

The permanent transport is deliberately narrow: `JsonTypeMetadataData` has a public constructor taking `byte[][] facts` and `String[][] inaccessibleTypeNames`, with final accessors only for those arrays. `metadataType` returns a JDK `Class`, and `metadataOperation` returns one stable generated thunk base. The registry verifies the selected token's fact-declared role, identity, assignability, and thunk shape before attaching it to decoded metadata. Empty chunks use stable shared empties. No map, annotation proxy, reflective member, selected class array, operation array, or decoded metadata object crosses this ABI.

Large companions use deterministic bounded switch chunks and fact holders. Generation caps each chunk using conservative budgets for method bytecode, JVM constant-pool entries, fields, referenced types/methods, and generated classes; no direct type gets a dedicated holder class, and no generated operation gets a second holder beyond its required subclass. These budgets are generator invariants, not estimates left to javac. Stress verification compiles the result with javac and then processes it through the minimum and current D8/R8 toolchains, recording generated class count, final DEX type count, and APK size, so passing a source-size check alone is insufficient.

This neutral transport is required for R8, not only for lazy JVM linking. Full-mode R8 analyzes every kept companion method and would report a removed old payload class before a runtime `abiVersion()` check could run. A stale companion instead references only the permanent bootstrap/transport/thunk ABI; the runtime rejects its version before decoding its fact stream or initializing an unrequested holder. `JsonTypeMetadataRegistry` instructs the user to align `fory-json` and `fory-annotation-processor` and recompile the annotated model.

Breaking the compact fact format and decoded internal metadata is allowed. Its version rejects stale generated code; no old decoder or metadata shim is retained.

One generated ABI surface is permanently stable so mismatch diagnostics and R8 analysis survive fact-format changes: the `JsonTypeMetadata` and `JsonTypeMetadataData` binary names and fixed members; the protected `(Class requested, String generatedName, int version)` constructor; the final `abiVersion()` accessor; the companion's public `(Class)` constructor; the section, indexed-type, and indexed-operation bootstrap methods; and the generated thunk base methods on `JsonFieldAccessor`, `JsonCreatorInvoker`, `JsonAnySetterInvoker`, `JsonCodecFactory`, and `ObjectInstantiator`. This is the generated-code contract, not a compatibility shim. The compact fact format and all decoded metadata value classes may break at an ABI bump because generated bytecode does not reference them.

## Generated Metadata Model

The runtime ABI is Java 8 bytecode and must not mention `AnnotatedType`, `RecordComponent`, compiler APIs, or JDK multi-release implementation classes in any Android-loadable descriptor.

One companion provides independently lazy neutral sections for these facts. The decoder produces the minimal runtime shapes `JsonTypeNode`, field metadata, method metadata, creator metadata, and subtype metadata. They name concrete data shapes rather than introducing a generic source abstraction.

- declaration binary name and generated ABI version;
- superclass and interface declaration relationships needed for annotation inheritance;
- eligible fields in base-to-derived order, including declaring class, source name, modifiers, raw type, full generic type tree, and field annotations;
- public bean getters and setters, including declaring class, signature, source logical name, full return/parameter type tree, override relationship, and method annotations;
- `@JsonAnyGetter`, `@JsonAnySetter`, and `@JsonAnyProperty` facts;
- all valid `@JsonCreator` candidates, executable kind, parameters, names, annotations, and full parameter type trees;
- record component facts and canonical-constructor mapping when the platform supports records;
- nearest class/superclass `@JsonPropertyOrder` declaration;
- `@JsonSubTypes`, inclusion mode, literal class references, class-name strings, and logical subtype names;
- direct and inherited declaration-level `@JsonCodec` candidates with their declaring types;
- every supported nested type-use `@JsonCodec` node on fields, accessors, creators, record components, arrays, generic arguments, and type variables, plus unannotated owner/wildcard structure required for substitution;
- singleton accessors and invokers for every candidate operation.

The generated type tree must preserve:

- raw and parameterized types, including owner types;
- generic array components and every array dimension;
- type-variable declaration identity and parameter index for class, method, and constructor declarations;
- wildcard upper and lower bounds;
- annotations on the exact supported type node;
- enough information for owner-type substitution and projection to collection/map supertypes.

Fory Core's `SourceTypeNode` is a pattern but cannot be reused unchanged because Fory JSON's nested `@JsonCodec` resolution distinguishes type variables, lower bounds, and exact annotated nodes that Core currently collapses. A stable generated declaration key is a section-local declaration index plus member kind and JVM descriptor; a type parameter adds its index. It is never compared with an R8-renamed runtime class name and does not require a runtime `Method`, `Constructor`, or generic `Signature`.

Every referenced type is classified independently from member accessibility. A directly nameable type receives an indexed generated class literal; the verifier may resolve that symbolic reference, but generated code does not initialize or semantically validate it before selection. A source-inaccessible type uses its exact binary name in inert facts and receives a precise generated `-keep,allowoptimization` rule so R8 neither removes nor renames it. Structural type shapes compare normalized token indexes, not source names that R8 may rewrite. No token is semantically requested merely because it occurs in the section union.

After the active semantic owner selects an occurrence, it resolves that token through the same role as the JVM path. A serialized target/value occurrence uses `JsonTypeResolver`/`JsonSharedRegistry` secure-type ordering; a subtype class-name entry uses the existing name-before-load and class-after-load subtype ordering through the builder's fixed class loader; and an annotation codec class receives existing codec structural/constructor validation but is not submitted to the serialized-type checker. A member declaring/signature token needed only to resolve an exact generated operation is identity/descriptor/owner-validated through the annotated target's loader; it does not acquire value-type security semantics. When one token has multiple selected roles, each role performs its current required validation before use. An unselected direct token triggers no checker, class initialization, operation singleton initialization, or handle construction; an unselected string token also is not loaded. A rejected selected token cannot request its operation or publish its decoded candidate.

Owner types and wildcard bounds are preserved structurally for substitution, but this design does not expand `@JsonCodec` semantics: a codec annotation on a parameterized owner segment, wildcard, or wildcard bound is a processor error matching the current JVM `JsonTypeUse` contract.

Generated type variables are never implemented as `TypeVariable`: that Java interface exposes `AnnotatedType` and would reintroduce an Android-incompatible ABI. Resolution uses this algorithm:

1. Convert the requested raw/parameterized owner occurrence into bindings keyed by the generated declaration key and parameter index.
2. Traverse generated superclass and interface edge nodes, substituting those bindings transitively. Android never calls `getGenericSuperclass()` or `getGenericInterfaces()`.
3. Resolve the selected member node in the key-based graph. A bound variable substitutes normally. A raw/unbound class, method, or constructor variable remains an explicit generated unresolved-variable state with its generated bounds; it is not silently converted into the upper bound for codec validation. An explicit `@JsonCodec` on that unresolved node is rejected exactly as on the JVM. Recursive bounds use a visiting set and retain the unresolved key plus raw bound rather than recurse.
4. For codec/raw-type classification after validation, derive the first upper-bound type, or `Object` when no narrower bound exists, while retaining the unresolved marker in `JsonTypeUse` for shape merging and diagnostics. Materialize standard Java `Class`, Fory parameterized type, generic-array type, and wildcard type values only from the substituted/bound graph. Never pass a generated variable placeholder to `TypeRef`.

Add generated-key overloads to the existing `JsonTypeUse` binding, projection, and substitution algorithms. The JVM overloads retain real `TypeVariable<?>` identity; both paths join at the same resolved-node merge and codec selection logic.

Anonymous `TypeRef<T>` root APIs are a separate user-owned type token and still capture `T` from their subclass `Signature`. The Android consumer rule therefore retains `Signature` and live `TypeRef` subclasses while allowing their names to be obfuscated:

```proguard
-keepattributes Signature
-keep,allowshrinking,allowoptimization,allowobfuscation class org.apache.fory.reflect.TypeRef {
}
-keep,allowshrinking,allowoptimization,allowobfuscation class * extends org.apache.fory.reflect.TypeRef {
}
```

The rules explicitly retain both signature endpoints while `allowshrinking` avoids rooting unused type-token subclasses. In R8 full mode, the attribute is retained only on endpoints matched by a keep rule; field/member signatures are not matched and do not become a metadata source. A full-mode minified test passes `TypeRef<List<AnnotatedModel>>` through every declared-type root API and proves the parameter survives R8.

The metadata must not contain any builder-dependent result:

- final implicit JSON property names;
- field-mode or JavaBean-mode membership;
- null-write decisions;
- final property order;
- `JsonFieldKind`, child codecs, `JsonFieldInfo`, or `JsonFieldTable`;
- exact builder codec registrations;
- generated-code eligibility or reader/writer capability classes;
- a completed `ObjectCodec`.

Storing any of these results in the companion would give the processor the wrong owner and would make one compiled model incompatible with multiple `ForyJsonBuilder` instances.

## Generated Accessors and Invokers

### Decision

Generate member access code, but do not generate one top-level class per field and do not generate complete codecs. One source field owns exactly one required private static nested `JsonFieldAccessor` subclass and `INSTANCE`, implementing every valid typed/object get and put shape for that field. Its JSON-write and JSON-read candidate facts reference the same operation index; directional ignore and finality control selection, not accessor identity. A getter method and setter method remain distinct operation owners because they are distinct Java members. Other directly expressible creators and Any calls likewise own one operation subclass and singleton, with no additional holder class. The semantic owner requests an operation index only after selecting the candidate and validating its required types.

For a selected source-inaccessible field, the cold decoder creates one Core `FieldAccessor`/`JsonFieldAccessor` pair for that field with a direction mask derived from the final builder selection. It constructs only the required getter/setter handles and shares that accessor across both selected directions; it never creates duplicate wrappers or handles for the same field. Other inaccessible candidates retain one cached exact method handle or executable in their existing owner. This gives R8 direct symbolic references where possible while avoiding unused candidate initialization, duplicate field accessors, extra DEX types, classpath indexes, and all per-value accessor allocation.

`ObjectCodecBuilder` selects the appropriate candidate after it applies field mode, JavaBean mode, annotation merging, and read/write availability. The companion carries the union required by every builder configuration, so R8 may optimize and rename direct candidates but must not remove a candidate reachable from the generated ABI.

### Direct source access

For a source-accessible field or method expression, the generated accessor reads, writes, or invokes it directly. For every primitive field, primitive getter, and primitive setter, it overrides the matching typed `getBoolean`/`getInt`/`putLong` method and equivalents so the existing codec loop does not box. Reference access uses `getObject`/`putObject`. R8 rewrites these symbolic references when it renames the member, so no member-name keep rule is needed.

The access code is emitted in the companion's package. Java access rules therefore permit public and package-access members in the same package, plus protected members where the generated access expression is legal. The processor tests the complete expression: target and declaring types, every enclosing type, member access, cast/return/parameter types, arrays, and constructor expression must all be nameable from that package. A `public` modifier alone is not sufficient; a public method can expose an inaccessible class in its signature.

### Source-inaccessible fields

Field mode intentionally supports private fields and inherited fields that are not source-accessible from the companion package. After validating the object facts and type tokens, the decoder resolves the exact declared `Field` once and delegates to the existing `JsonFieldAccessor.forField`/Core typed `FieldAccessor` owner. On API 26+, Core sets accessibility once, creates getter/setter method handles with [`Lookup.unreflectGetter`/`unreflectSetter`](https://developer.android.com/reference/java/lang/invoke/MethodHandles.Lookup), adapts them once to the exact `(Object)primitive`, `(Object,primitive)void`, `(Object)Object`, or `(Object,Object)void` shape, and caches them in the accessor. The exact generated rule retains that field name and descriptor.

The steady-state primitive paths call the typed handle with `invokeExact` from `getBoolean`, `getInt`, `putLong`, and equivalent operations. The current Core Android `ReflectionAccessor`, which inherits boxing `Field.get`/`Field.set` defaults, is replaced rather than wrapped or retained. Successful access does not box, allocate, call `Field.get`/`Field.set`, or branch on Android. Cold field lookup, access elevation, handle construction, and failure translation stay in separate methods so they are not inlined into the accessor hot path.

There is no parallel Fory JSON field-handle implementation. A generated direct call remains preferable wherever Java can express it; the source-inaccessible case extends Core's existing `FieldAccessor` owner with its API 26 implementation.

### Other call shapes

The companion also owns narrow, configuration-independent call objects where `JsonFieldAccessor` is not the right shape:

- `JsonCreatorInvoker` invokes an annotated constructor or public static factory;
- `JsonAnySetterInvoker` invokes a public Any setter with `(String, value)`;
- `JsonCodecFactory` directly constructs a processor-validated public annotation codec;
- an `ObjectInstantiator` directly invokes a no-argument or record canonical constructor.

Directly generated objects are companion-owned static singletons; cold-resolved objects are decoded-section singletons. Existing `JsonCreatorInfo`, Any metadata, and `ObjectCodec` retain and invoke them. They do not create a second construction or Any-property pipeline. When the complete call expression is source-accessible, generated code calls it directly. Otherwise the same owner resolves the exact `Method` or `Constructor` from validated generated name/descriptor facts and sets accessibility once. Getter/setter and Any owners unreflect and adapt fixed explicit-argument method handles. Construction owners cache the exact executable: no-argument construction passes Core's single static empty `Object[]`, while record and creator construction consume the caller-owned fixed argument array. No successful call creates an invocation carrier or wrapper.

For a selected source-inaccessible primitive JavaBean getter or setter, `JsonFieldAccessor` chooses the primitive kind once, adapts the handle to the exact `(Object)primitive` or `(Object,primitive)void` shape, and overrides the matching typed accessor method with `invokeExact`. All eight primitive kinds have typed implementations; they never route through `getObject`/`putObject`. Reference getters and setters use `(Object)Object` and `(Object,Object)void`. This requirement applies equally when the target, declaring type, or signature type makes an otherwise public method source-inexpressible.

For a source-accessible no-argument constructor, the generated `ObjectInstantiator` performs `new Target()` directly. A private or otherwise source-inaccessible no-argument constructor remains owned by Core's Android `ObjectInstantiator`: Core resolves the exact constructor, makes it accessible once, caches it, and invokes it with one process-wide empty `Object[]`. The precise R8 rule retains only that constructor. This is the required Android construction owner, not a fallback.

Records do not use `JsonCreatorInfo`. When the complete canonical-constructor expression is source-nameable, the generated `ObjectInstantiator.newInstanceWithArguments` invokes it directly. Otherwise the decoded section selects the existing Core `ObjectInstantiator` owner, resolves and caches the exact canonical constructor after type-token and security validation, and invokes it with the already-required decoded argument array. This covers a private static record and a canonical signature containing a source-inaccessible component type without adding a record-specific construction path. The exact generated R8 rule retains only the inaccessible canonical constructor. An explicit `@JsonCreator` remains rejected for a record. The Android collector passes the validated constructor to Core's explicit Android record-instantiator factory; it must not re-run record discovery through `ObjectInstantiators.createObjectInstantiator(type)` after that selection.

The executable choice is based on API 26 ART measurement rather than a compatibility fallback. Across 100,000 escaped-result invocations, a direct constructor allocated 100,000 objects while a raw generic constructor-handle invocation allocated 699,992. Adapting the constructor handle with `explicitCastArguments` corrupted ART's construction return type, and a `filterReturnValue` composition failed `EmulatedStackFrame` reference checks. After warmup, cached reflective no-argument construction measured 100,001 allocations, a cached creator constructor measured 100,002, and a cached static factory measured 100,002 against 100,000 direct controls. The fixed difference is counter/runtime noise, while the method-handle shape added approximately six objects per invocation. Cached exact executables with owner-supplied arrays are therefore the zero-carrier Android construction design.

Generated direct calls, exact method-handle access, and cached executable construction preserve existing exception contracts without successful-path allocation. Each call site catches `Throwable`, including direct no-argument and record construction; this avoids naming an inaccessible declared exception and covers unchecked failures. Field/getter/setter access calls a cold error helper owned by `JsonFieldAccessor`; creator, Any invoker, and `ObjectInstantiator` contracts own their corresponding cold translation helpers. Field access preserves the current wrapped access failure, while creator and Any invocation rethrow `Error`, unwrap `InvocationTargetException` to retain the target cause, and create `ForyJsonException` only on failure. `JsonCreatorInfo` continues to enforce the exact non-null result type for factory creators.

## Runtime Integration

### Android mode selection

Normalize Android configuration once when `JsonSharedRegistry` is created:

- effective runtime code generation is `false`;
- effective asynchronous compilation is `false`;
- no `JsonCodegen`, `JsonJITContext`, per-state JIT lock/callback map, compiler executor, Janino class, or generated-code instantiator is created or linked from the Android execution path;
- all other builder settings retain their documented semantics.

`withCodegen(true)` and `withAsyncCompilation(true)` remain accepted because they are portable tuning options, but Android's effective values are false. They must not cause a warning per operation or a hidden partial compiler initialization.

The Android branch is isolated in a cold registry-construction method. Existing JVM code generation and asynchronous replacement remain byte-for-byte in their current owner where practical.

Runtime disabling alone is not the shrink contract. `JsonTypeResolver` currently calls `JsonCodegen` classification helpers even while constructing interpreted codecs. Move field read/write capability and nested-type classification to their semantic owners in `JsonFieldInfo` and `ObjectCodec`; both the resolver and code generator consume those package-private facts. After this change, no interpreted classification method has a type, field, method descriptor, or unconditional call edge to `JsonCodegen`.

The codegen-disabled fast path is concrete. `JsonSharedRegistry` stores null codegen/JIT factories and `JsonTypeResolver` allocates no `JsonJITContext`. Each capability selector tests the final codegen reference before erasing the owner, looking in `canonicalObjectTypeInfos`, reading a capability slot, or creating a callback; it immediately returns the interpreted codec when null. Inline-subtype resolution has the same first guard. Root JIT lock/unlock methods return immediately when the context is null. Under the Android R8 assumption these guards and their calls fold to direct interpreted returns/no-ops, so `ObjectCodec` entries contain no capability map lookup or JIT lock operation. On a non-Android codegen-enabled JVM, the existing order after the guard is unchanged; codegen-disabled JVM behavior may use the same allocation-free early return and is covered by the regression gates.

The remaining codegen object, executor, replacement callbacks, and generated-code instantiators live only in cold methods dominated by `!AndroidSupport.IS_ANDROID && codegenEnabled`. Package an R8-targeted Android consumer rule in the `fory-json` JAR under `META-INF/com.android.tools/r8/` that supplies the true Android value to R8:

```proguard
-assumevalues class org.apache.fory.platform.AndroidSupport {
  public static final boolean IS_ANDROID return true;
}
```

This is an Android-targeted rule interpreted by Android Gradle Plugin and inert on the ordinary JVM classpath. `-assumevalues` is sound only because real Android detection cannot be forced false. Full-mode R8 removes the dominated fields and cold methods and follows no edge into Fory JSON codegen or Core's relocated compiler. Release verification rejects any DEX containing `JsonCodegen`, `JsonJITContext`, compiler executor/replacement support, generated-code instantiators, relocated Janino classes, or descriptors referring to them. It also inspects optimized `ObjectCodec` entries to prove no residual map lookup, JIT lock call, or Android branch. If R8 cannot prove those absences, Android support is not complete.

Make the constant assumption sound in Core: detection of Dalvik/Android runtime names always returns true before consulting `FORY_ANDROID_ENABLED`. The environment variable may force Android behavior on a JVM for tests, but it cannot force a real Android process to report false. Add unit and device tests for that invariant. This is a deliberate breaking cleanup of a test knob, not an Android runtime configuration option.

### Metadata registry

Add one `JsonTypeMetadataRegistry` to each `JsonSharedRegistry` only on Android. It owns:

- positive metadata entries keyed by application `Class<?>` identity;
- negative entries for deterministic missing-provider failures;
- independently decoded declaration, subtype, and default-object sections that otherwise require reflection.

The cache is not static and does not retain application classes beyond the owning `ForyJson`. Concurrent first resolution publishes one validated immutable metadata object. Failed construction is not published as a partial value. Recursive object-codec construction remains owned by `JsonTypeResolver` and its existing publication/rollback protocol, not by the metadata registry.

The registry resolves the companion's public `(Class)` constructor once and keeps the raw handle returned by `Lookup.unreflectConstructor`. It invokes that handle without adapting its return type to `JsonTypeMetadata`. Android API 26's constructor-handle implementation treats an explicitly adapted return type as the class to instantiate; adapting to the abstract ABI base therefore attempts to instantiate the base instead of the concrete companion. This is a cold, once-per-type generic handle invocation and has no reflection fallback or steady-state cost.

Before loading a companion, the runtime applies the existing secure-type check to its known target. Later token resolution is candidate-selective and role-specific; the metadata registry does not apply one blanket checker policy to a section. Serialized value types use the current `JsonTypeResolver` check, subtype strings use `JsonSharedRegistry`'s current name-before-load path, codec implementation classes use `validateCodecClass`, and exact member resolution validates generated owner/name/descriptor facts without treating the declaring class as a separately serialized value. Every failed selected-role check prevents its operation from being requested and prevents that candidate from publication. An unselected direct token may be verifier-resolved but causes no class initialization, checker call, operation construction, or publication; an unselected string token also causes no class load.

### Object codec construction

Split only the cold collection step:

```text
JVM:     reflection collectors ─┐
                                ├─ existing ObjectCodecBuilder normalization ─ ObjectCodec
Android: generated collectors ──┘
```

Only a request that has passed exact/type-use/declaration/subtype/built-in resolution can load the inert default-object section. `ObjectCodecBuilder` first receives structural field, method, creator, Any, order, and type-use facts. It applies field/JavaBean mode, directional ignores, annotation merging, and read/write availability before resolving selected type/operation indexes. Its current merge and validation methods remain the sole implementation. Do not introduce a generic metadata-source policy interface if two direct cold entry methods are sufficient.

Default object mapping has one deterministic Android platform boundary. Before selecting inherited object members, the Android collector rejects a generated hierarchy containing a non-terminal superclass loaded by the same loader as `Object`, other than `Object` or, in the record profile, `Record`. Android's boot loader is not required to be represented by `null`, while Android SDK/core-library stubs do not expose authoritative private platform state; comparing loader identity detects the platform owner without a package-name heuristic. The JVM owner uses `getDeclaredFields()` for every superclass, so silently accepting such a hierarchy would publish a partial schema. Exact builder, exact type-use, or declaration codecs still resolve before the object section and therefore bypass this check. The failure names the platform ancestor and requires a complete codec.

The existing private `FieldBuilder` currently stores `Field`, `Method`, `AnnotatedElement`, and annotation proxy objects. It must be refactored so its semantic state is annotation values, resolved type nodes, source descriptions, and selected accessors. The JVM collector continues to attach optional reflection members needed by JVM runtime code generation; the Android collector attaches generated accessors/invokers and never synthesizes a `Method` or a `Field` for directly accessed members. Two collector-specific overloads populate the same `FieldBuilder` and call the same merge/validation functions. Do not allocate a generic candidate wrapper per reflected JVM member merely to make the two collectors look identical.

The source-neutral cold boundary is concrete:

- generated field facts contain declaring-type indexes, source name, modifiers, flattened `JsonIgnore`/`JsonProperty`/Any values, inert `JsonTypeNode`, source description, directional eligibility, and one shared direct-accessor index or one exact field-handle recipe;
- generated method facts contain declaring-type index, name, JVM descriptor, modifiers, flattened annotations, inert return/parameter `JsonTypeNode` values, override identity, source description, and direct-operation indexes or exact member recipes for getter/setter/Any directions;
- generated creator facts contain executable kind, source description, parameter names/annotations/type nodes, raw-type indexes, defaults, and a direct-operation index or exact executable recipe;
- generated record facts contain component names/annotations/type nodes and a canonical-constructor direct-operation index or exact constructor recipe;
- generated subtype/order/declaration-codec facts contain annotation values and class/type/factory indexes or exact class-name strings, never annotation proxies.

For a selected occurrence, `JsonTypeNode.resolve(ownerType, ownerTypeUse)` requests only its required class indexes and reconstructs the declared/bound `Type`, raw class, unresolved-variable state, and `JsonTypeUse` from generated structure and the requested owner binding; it never reads a member `Signature`. It has concrete representations for parameterized, array, wildcard, and generated variable-key nodes, but it never materializes a fake `TypeVariable`. `JsonFieldInfo` receives explicit resolved read/write `Type`, raw class, type use, and accessor instead of deriving them from `Field` or `Method`. Its optional reflection members exist only for JVM code generation and diagnostics.

The final `AnyInfo` keeps the ordinary JVM 80-byte layout and its private hot helpers. One
`setterHandle` carries either the JVM exact Any-setter handle or the Android bound handle returned
by `JsonAnySetterInvoker.exactHandle()`. The hot `put` helper performs one `invokeExact`; it does not
branch on the platform, look up metadata, retain a generated invoker, or allocate an invocation
carrier. `AndroidAnyInfo` is only the cold Android factory that binds the generated invoker before
constructing the common runtime value. Read and write capability continue to follow the selected
accessor or setter handle, without additional flags in the common value.

Generated-only retained state is isolated in Android/generated subclasses rather than widening
ordinary JVM values. `AndroidJsonSharedRegistry` owns the metadata registry and generated
declaration resolution, while `AndroidJsonTypeResolver` owns the no-codegen resolver behavior.
`GeneratedJsonTypeUse`, `AndroidJsonCreatorInfo`, `AndroidJsonCodecDeclaration`,
`GeneratedJsonCodecCandidate`, and `GeneratedFieldBuilder` retain generated factories, operations,
tokens, resolved nodes, and access state only where those facts are used. The ordinary
`JsonSharedRegistry`, `JsonTypeResolver`, `JsonTypeUse`, `JsonCreatorInfo`, `JsonCodecDeclaration`,
codec candidate, and `FieldBuilder` layouts and class-loading graph therefore remain aligned with
the non-Android JVM baseline.

After construction, Android publishes the same `JsonTypeInfo` and five reader/writer capability slots used on the JVM. `ObjectCodec`, `JsonFieldInfo`, and `JsonFieldTable` retain concrete selected accessors. Serialization and deserialization loops do not query the metadata registry and do not branch on the platform.

### Declaration annotations outside object construction

Android `JsonSharedRegistry.codecDeclaration` and subtype resolution decode their own generated sections rather than calling `Class.getDeclaredAnnotation`. Neither path requests or initializes the default-object section. JVM retains its current reflection traversal.

Android resolution preserves the current precedence with an explicit intrinsic-built-in boundary:

1. Resolve an exact TYPE_USE codec, then an exact builder registration. Either result bypasses declaration metadata.
2. If the raw class is an exact intrinsic built-in owned by `JsonSharedRegistry`, select that built-in. The intrinsic set is a closed identity set, not a package-name heuristic or assignability test.
3. For every other raw declaration, require its companion and resolve declaration `@JsonCodec` before enum/container/date/other assignable-family built-ins.
4. If the companion reports no declaration codec, continue through the existing built-in and default object/subtype selection.

Thus an application enum, collection subclass, map implementation, date subclass, or other assignable-family type cannot silently lose a declaration codec when processing is missing: it requires metadata even if the companion ultimately selects built-in representation. Platform/exact library built-ins remain annotation-free and need no companion. This closes missing-provider diagnostics without retaining runtime annotation attributes.

If declaration-level `@JsonCodec` inheritance crosses an unannotated parent or interface, the annotated concrete type's companion embeds the necessary declaration frontier. Resolving the unannotated declaration itself as a root still requires its own `@JsonType`, an exact registered codec, or a built-in codec.

`JsonTypeUse` gains factories for generated type trees. Its reflective factories remain JVM-only. Remove the current behavior that silently reports annotation support as disabled on Android; Android must either receive generated type-use metadata or fail before building the codec.

## Builder Configuration Semantics

All configuration-dependent choices are made when the runtime builds an interpreted object codec:

- `withFieldMode` selects fields only or merges fields with public JavaBean accessors. An Any method remains invalid in field mode.
- `withPropertyNamingStrategy` transforms only implicit names. A non-empty `@JsonProperty` value remains a final JSON name.
- `writeNullFields` combines with merged `JsonProperty.Include`: `DEFAULT` uses the builder, while `ALWAYS` and `NON_NULL` override it.
- explicit property order is resolved against final JSON names and then Java logical names; indexed entries follow, alphabetic remainder follows that, and an Any property occupies one logical position.
- `registerCodec` remains an exact per-builder registration and can change representation and `JsonFieldKind`.
- the fixed class loader, type checker, depth, concurrency, and buffer retention remain runtime settings.
- declared generic owner substitution is performed for each requested `Type`, not frozen for the raw annotated class.

Two `ForyJson` instances built from different builders can therefore consume the same generated companion and produce different correct `ObjectCodec` instances without regenerating application code.

## `@JsonCodec` Semantics

The processor must preserve enough information for Android to implement the current precedence and validation exactly:

1. A codec on the exact current type-use node wins, including over an exact builder registration.
2. Otherwise an exact builder registration wins over a declaration annotation.
3. Otherwise a direct type declaration wins.
4. Otherwise the most-specific annotated superclass/interface frontier wins. Incomparable frontier declarations selecting the same codec are accepted; different codecs are rejected.

A direct declaration, exact type-use, or exact builder registration may disambiguate an inherited conflict. A value returned by an inherited declaration codec must be null or assignable to the actual resolved type.

The selected annotation codec is instantiated at most once per codec class per `ForyJson`, after security validation. A codec class must be public, concrete, top-level or static nested, be enclosed only by public declarations, and expose a public no-argument constructor. A public codec nested in a private or package-private enclosing declaration is a processor/runtime error, matching `JsonSharedRegistry.validateCodecClass`; it is not routed through inaccessible construction. Android metadata supplies a generated `JsonCodecFactory` that calls the valid constructor directly; the JVM reflection collector retains its current constructor path.

A complete codec owns its entire value. Explicit descendant codecs under that node are rejected. Built-in containers delegate only to supported child nodes:

- arrays delegate to dimensions/components;
- `Optional` and atomic references delegate to their value;
- collections delegate to their projected element;
- maps delegate to their projected value;
- map-key subtrees reject `@JsonCodec` because map key encoding has a separate owner.

Field, getter, setter, record-component, and creator type-use trees for one logical property are merged only when their complete shapes match. The same codec at the same node is accepted; different codecs are rejected with the exact source path. Any-map roots cannot carry a complete codec, while their value node can.

No Android logic reads `@JsonCodec` using `AnnotatedType`, `getAnnotation`, or `getDeclaredAnnotation`. Both declaration and nested type-use information come from the generated companion.

## Other Annotation Semantics

The generated facts must preserve all current object-model behavior:

- `@JsonIgnore` is directional. An ignored field suppresses the same logical property's getter for writes and setter for reads; an ignored same-name Any direction remains an error rather than bypassing the field declaration.
- `@JsonProperty` name, index, and include values from selected merged members must agree when explicit. Invalid/duplicate indexes, duplicate canonical names, name-hash collisions, empty names, and conflicting read/write generic types are rejected with both source descriptions. An index or include rule requiring a write source is validated after runtime member selection.
- exactly one valid public `@JsonCreator` constructor or public static factory may be selected. Both list-form names and parameter-local names are preserved. Creator parameters form the complete read schema.
- creator-only properties cannot declare serialization indexes or inclusion policies; creator parameters must match the selected logical property type and read-ignore direction; primitive creator defaults and null-to-primitive errors remain unchanged.
- record components merge with component accessors and the canonical constructor. An explicit creator on a record remains invalid.
- `@JsonAnyGetter`, `@JsonAnySetter`, and `@JsonAnyProperty` preserve `Map<String,V>` projection, matching value types, map-key codec rejection, null-to-primitive setter rejection, fixed-property collision/skipped-name handling, final-map fill-versus-replace behavior, creator binding restrictions, and ordering. Field-backed and method-backed Any declarations cannot be mixed.
- `@JsonPropertyOrder` uses the nearest class or superclass declaration and does not inherit through interfaces.
- `@JsonSubTypes` preserves PROPERTY, WRAPPER_OBJECT, and WRAPPER_ARRAY inclusion; class literals and class-name strings; assignability and uniqueness checks; discriminator collision checks; and the declared-type requirement for subtype-aware writes.

Exact TYPE_USE, exact builder, and declaration complete codecs are resolved before `@JsonSubTypes`. PROPERTY inclusion requires every selected child to use the default `ObjectCodec`; WRAPPER_OBJECT and WRAPPER_ARRAY may wrap custom child codecs. A runtime-type root write does not search for a base type's subtype table, so subtype-aware root writes continue to require a declared-type Fory JSON API.

Processor validation handles source-invariant errors early. Builder-dependent conflicts remain runtime errors and use the same `ObjectCodecBuilder` diagnostics on JVM and Android.

## R8 Full-mode Contract

For each companion, the processor emits
`META-INF/fory-json/r8/fory-json-generated-<escaped-name>.pro`. Rules are precise and derived from
the generated access mode. This path is a private carrier ABI between the processor and the Gradle
plugin; it is not an Android Gradle Plugin consumer-rule location and must not leak into a published
AAR or final APK.

The application task rejects malformed, non-UTF-8, broad, or duplicate logical carriers, sorts by
logical resource name, and writes a canonical rule file without per-build nondeterminism. It scans
the complete variant class scope, adds that file directly to the variant's R8 inputs, and excludes
`META-INF/fory-json/r8/**` through the variant's public packaging-resources API so carriers from the
application and dependencies cannot enter an APK or Android App Bundle. The library task applies the
same validation, preserves existing `proguard.txt` bytes as the prefix, appends the canonical
generated rules, removes carrier entries from `classes.jar`, and writes a deterministic AAR. If a
library has no carriers, the AAR bytes remain unchanged. Plugin application order relative to
`com.android.application` or `com.android.library` does not affect configuration. Applying the
plugin without exactly one supported Android plugin, or with Android Gradle Plugin earlier than 8.0,
is an actionable build error.

### Convention lookup

- Keep the annotated target class and its binary name because the runtime derives the companion name from it and an inaccessible target is intentionally referenced only by its generated binary-name constant.
- Keep the companion name, its public `(Class)` constructor, and stable public `Object metadata(int)`, `Class metadataType(int,int)`, and `Object metadataOperation(int,int)` bootstrap methods.
- For a version-matched companion, `metadata(section)` reaches only the selected inert fact/name holder. `metadataType` selects a direct literal from a bounded switch, while `metadataOperation` reads the selected generated subclass's own singleton. Unselected operation classes remain uninitialized; direct literals may be verifier-resolved but receive no semantic action before selection.
- Allow optimization where it does not invalidate convention lookup, but do not allow obfuscation of either convention name or removal of an ABI entry point.

The generated rule has this semantic shape, with exact generated method signatures rather than a wildcard in the implementation:

```proguard
-keep,allowoptimization class application.model.Target {
}
-keep,allowoptimization class application.model.Target_ForyJsonMetadata extends org.apache.fory.json.meta.JsonTypeMetadata {
  public <init>(java.lang.Class);
  public java.lang.Object metadata(int);
  public java.lang.Class metadataType(int,int);
  public java.lang.Object metadataOperation(int,int);
}
```

`@JsonType` is an explicit retention declaration. The companion is reached only through a derived string, and a private target may also appear only as a generated binary-name constant, so a correct full-mode rule retains both classes as roots without retaining target members. The design does not claim that R8 can remove an unused annotated model. This bounded size cost is the deterministic consequence of name-based, registration-free lookup. R8 may still optimize and obfuscate directly referenced members and removes unrelated unannotated code.

### Direct member access

Do not keep a field, getter, or setter solely because generated bytecode references it directly. R8 owns and rewrites that symbolic reference. It may inline or remove the generated accessor when safe.

### Reflective members

For every field, method, or constructor resolved on the cold path, emit one exact `-keepclassmembers,allowoptimization` rule with its declaring class, name, and descriptor. Do not allow obfuscation of the reflected name. Do not retain unrelated members.

Every source-inaccessible class token encoded by binary name receives one exact `-keep,allowoptimization class ExactName {}` rule. The explicit empty member block is required: omitting the member block can retain a default constructor, which is not part of class-token reachability. This rule is required for private nested raw types, generic arguments and bounds, inaccessible ancestors, and literal subtype values because R8 cannot rewrite an arbitrary generated string. Directly nameable class literals need no such rule. User-authored `JsonSubTypes.Type.className` entries use the same empty-member class rule and keep their separate builder-class-loader semantics.

Before interpolating a user-authored class name into a generated rule, the processor and runtime apply one shared strict binary-name grammar: dot-separated non-empty segments whose Unicode code points satisfy Java identifier start/part rules, including legal `$` nested-name separators. Arrays, descriptors, whitespace, empty segments, every R8 metacharacter, `void`, and all eight primitive type names are rejected. The explicit primitive/`void` deny set preserves the current runtime contract even though those words pass identifier-character checks. This deliberately tightens the remaining runtime-only checks, which are not safe for rule generation because strings containing `*`, `#`, braces, commas, or directive text could otherwise alter the generated rule. Invalid source annotations fail compilation before any resource is written; runtime validates again before class loading for separately compiled metadata.

An annotation codec constructor is directly referenced by its generated `JsonCodecFactory`, so it needs no reflective rule. A source-inaccessible ordinary no-argument constructor retained for Core's cached executable receives one exact constructor rule. Literal `Class<?>` references establish class reachability normally. A `JsonSubTypes.Type.className` string requires an exact `-keep,allowoptimization` rule for the named class, without obfuscation, because R8 cannot rewrite an arbitrary string and could otherwise remove the class.

An unresolved `className` is legal only for a subtype supplied to the final application as a later runtime dependency, which is the purpose of the string form. A library processor emits the exact rule text without loading that class; final-app R8 must resolve it. Runtime still checks presence, assignability, uniqueness, security, and the subtype's own metadata/custom-codec requirement. Tests cover both a runtime-only dependency that succeeds and a genuinely missing class that preserves the current actionable runtime failure.

### Forbidden rules and dependencies

Generated rules must never contain package-wide `-keep class ** { *; }`, retain runtime annotation/method-parameter attributes, retain member generic signatures, or preserve all members of an annotated type. The only signature exception is the live anonymous `TypeRef` subclass/base endpoint rule required by the declared-type root API. Runtime model semantics use generated strings and type nodes rather than source names recovered from reflection.

The release APK must not contain an external Janino dependency, relocated Janino bytecode, JDK 25 multi-release classes, Android-incompatible `AnnotatedType` descriptors in Android-loaded metadata classes, or any JSON runtime-codegen class/descriptor. Mark Core's build-time Janino dependency optional in the published `fory-core` POM. The repository does not publish a separate Gradle Module Metadata owner; Gradle consumers resolve the Maven POM. The shaded, relocated Janino classes remain inside the Core JAR for JVM runtime code generation, while Android consumers no longer receive the external artifact and full-mode R8 removes the shaded Android-unreachable copy. Users no longer write a manual exclusion. Published Maven and Gradle dependency-graph tests against that POM and final DEX inspection enforce both halves.

## Android API Levels and Java Features

The base Android contract is Android Gradle Plugin 8.0 or later, R8 full mode, Java 8 application bytecode, and minSdk 26. Ordinary classes, field mode, JavaBean mode, creators, Any properties, subtype handling, custom codecs, all reader inputs, and all writer outputs run on API 26.

API 26 defines two source-inaccessible member shapes. Core fields and Fory JSON getter/setter/Any owners cache exact adapted method handles. Core construction owners and Fory JSON creator owners cache the exact `Constructor` or `Method` and consume owner-supplied fixed argument arrays. Generated direct calls remain preferable, and no second access abstraction is introduced.

Java records are not part of the API 26 platform contract. Record support is a separate product profile requiring Android Gradle Plugin 8.2 or later, Java 17 source/target compatibility, and minSdk 34 or later; it does not rely on record desugaring. AGP 8.2 introduced native record dexing for min-api 34 and later, and `java.lang.Record` is an API 34 class, as documented in the [AGP 8.2 release notes](https://developer.android.com/build/releases/agp-8-2-0-release-notes) and [Android Java version guidance](https://developer.android.com/build/jdks). The processor's Java 8-compatible record adapter emits record metadata whenever the host compiler exposes a record declaration, because a Java annotation processor does not own or reliably receive the Android minSdk. The Java guide and Gradle fixtures enforce the platform profile. A minSdk 26 fixture containing a record is a required negative build test, and record execution is tested on API 34+. No record class is loaded in API 26 tests.

## Errors and Security

All failures occur on the cold resolution path and throw `ForyJsonException` with the target type and corrective action:

- missing companion: name the type, require `@JsonType`, the annotation processor and Gradle plugin, matching versions, and intact generated R8 rules;
- ABI mismatch: report runtime and generated versions and require recompilation;
- wrong companion target: report both classes;
- missing cold-resolved member or inaccessible type token: identify the declaring class, member/type, generated companion, and likely processor/R8 defect;
- unsupported declaration form: fail compilation where possible and runtime validation otherwise;
- missing reachable object metadata: report the property/type-use path from the annotated owner;
- incomplete Android boot-class hierarchy: name the superclass and require a complete exact, type-use, or declaration codec;
- unsafe subtype binary name: identify the annotation entry and the first invalid code point/segment before any R8 rule or class load;
- invalid codec or subtype: preserve existing security, assignability, constructor, conflict, and class-loader diagnostics.

There is no fallback to best-effort reflection, `setAccessible` retries, alternate provider names, package scans, or silently ignored annotations. Such fallbacks would make release behavior depend on R8 accidents and would create a second semantic path.

## Performance Design

### Hot-path invariants

- A completed codec uses the existing `ObjectCodec`, reader, writer, `JsonTypeInfo`, `JsonFieldInfo`, and `JsonFieldTable` hot loops.
- No per-value platform test, metadata lookup, map lookup, reflective member discovery, callback wrapper, tuple, or generated-metadata allocation is added.
- Direct generated primitive access uses typed overrides and does not box.
- Accessors, invokers, neutral fact arrays, decoded metadata, and type nodes are constructed once and retained by the generated companion or registry.
- Source-inaccessible members are resolved once; hot access uses cached exact method handles, while construction and creators use cached exact executables in their existing owners.
- Large loading, validation, and reflective restoration methods remain separate cold methods and are not tiny forwarding helpers likely to inline into codec loops.
- Object creation and an existing creator argument array are semantic allocations; this design adds no allocation to them.

### JVM isolation

The non-Android branch does not derive a companion name, call `Class.forName`, allocate a metadata registry, wrap reflected members, or test `@JsonType`. Existing JVM reflection and code generation remain the default. `@JsonType` is ignored at runtime on JVM.

Any shared code added to `ObjectCodecBuilder` is cold property normalization already executed once per type binding. Existing JVM codec-loop bytecode must not change except where a separately measured cleanup is required.

### Acceptance thresholds

For non-Android JVM benchmarks, compare the branch against a fresh `apache/main` worktree for both generated and interpreted codecs, annotated and unannotated models. A retained median regression greater than 1% for the same benchmark is unresolved.

Measure cold JVM cost separately: `ForyJson` construction plus first raw-object binding, first parameterized-object binding, and first serialization for small and inheritance-heavy models. Canonical ordering reuses reflection-returned arrays or existing builder collections and a static comparator; it does not allocate a per-member wrapper. A retained median cold regression greater than 1% is also unresolved.

On Android, measure field-backed and JavaBean-backed models in release-minified builds. Allocation tracking must show zero additional allocations per serialized/deserialized property after codec construction. Compare generated direct and cached inaccessible-member access against direct controls. Construction and creator controls must prove that cached executable invocation adds no per-call carrier beyond the result object and the already-owned argument array.

## Verification Plan

### Annotation processor tests

- Generate companions for top-level, nested, inherited, generic, array, wildcard, and recursive models.
- Compile the generated source with Java 8 source/target rules.
- Exercise `@JsonType` and real `@JsonCodec` annotations through javac; tests must fail if generated metadata is removed.
- Cover `@JsonCodec` on declarations and every nested TYPE_USE location, including the javac tree fallback.
- Cover fields, public getters/setters, source-inaccessible fields/signatures/ancestors, creators, Any setters, and record components. Include private nested raw types, nested generic arguments, bounds, literal subtype values, and public methods whose signatures expose them.
- Assert a normal mutable source field generates one accessor subclass and one singleton whose shared operation index serves both valid directions; ignored/final directions do not create another class. Getter and setter methods retain distinct indexes.
- Generate a direct canonical-constructor instantiator for a fully source-nameable record and select Core's cached exact-constructor instantiator for a private static record or inaccessible canonical signature; reject a codec enclosed by any non-public declaration.
- Validate deterministic escaped names, permanent bootstrap/transport/thunk ABI constants, independently lazy sections, indexed direct-type switches, operation-owned singletons, originating elements, aggregating processor metadata, and one exact R8 rule carrier per type.
- Compile a large stress hierarchy with javac and process it with minimum/current D8 and R8 to prove deterministic switch chunks/fact holders stay within method-bytecode, constant-pool, field, and reference budgets; record generated class count, DEX type count, and APK size, and prove the decoder never concatenates chunks.
- Reject local, anonymous, a non-static annotated member, invalid creator, invalid codec constructor, and source-invariant type-use conflicts; accept private static targets, private enclosing chains, and a static target inside a non-static enclosing class on Java 17.
- Reject every invalid or R8-active subtype class-name form before writing a rule, including wildcards, comments, braces, commas, whitespace, descriptors, arrays, empty segments, directive text, `void`, and each primitive name; accept legal Unicode and `$` binary names and revalidate them at runtime.
- Cover raw class variables, recursive bounds, and generic bean-method variables with and without `@JsonCodec`; assert JVM/Android unresolved-variable parity.
- Prove declaration-only enum/interface/abstract/built-in-family companions neither emit nor initialize a default-object section. Compile concrete hierarchies against Android-like boot stubs that omit private fields; verify the generated superclass facts drive the runtime default-object rejection, while exact/type-use/declaration codec variants never request that object section.
- Load the processor and compile ordinary Java 8 models on its minimum JDK; compile record models under JDK 17+ through the version-neutral record adapter.
- Inspect generated R8 rules for exact members and inaccessible type tokens, empty-member `TypeRef` signature endpoints, the private `META-INF/fory-json/r8/` carrier location, and absence of broad keeps or accidental constructor/member constraints.
- Exercise the Gradle plugin with both plugin application orders. Verify application, same-build AAR, Java-library JAR, and published AAR paths; deterministic and cacheable task output; exact existing-consumer-rule prefix preservation; malformed and duplicate carrier rejection; and absence of carriers in published AARs and final APKs.
- Run Gradle TestKit and Android multi-module incremental cases for editing one annotated type, removing `@JsonType`, deleting/renaming a type, changing a same-module superclass/interface, changing an upstream library ABI/annotation, and rebuilding an AAR. Assert aggregating invalidation reruns the processor when inherited facts can change, stale generated source/rules are deleted, and incremental output is byte-identical to a clean rebuild.

### JVM Android-mode tests

Run subprocess tests with `FORY_ANDROID_ENABLED=1` to cover behavior that does not require ART:

- codegen and async options normalize without constructing `JsonCodegen` or `JsonJITContext`, and capability calls return before any map lookup;
- generated metadata lookup, cache publication, recursion rollback, missing companion, and ABI mismatch;
- candidate-selective initialization: decoding facts makes no explicit type/operation request; each selected index initializes once only after its owning semantic checks; unselected direct candidates cause no checker, class initialization, operation construction, or publication, while unselected string tokens additionally are not loaded;
- exact intrinsic built-ins without metadata, plus application enum/container/date-family types whose declaration-only companion is required before built-in selection and never initializes object members;
- field/JavaBean mode, LOWER_CAMEL_CASE/SNAKE_CASE naming, null inclusion, property order, and exact codec registration matrices;
- declaration and type-use codec precedence, inheritance conflicts, nested generics/arrays, map-value and map-key rules, and Any values;
- all subtype inclusion modes, literal and string subtype declarations, class loader, checker, and security order;
- field mode does not check, initialize, construct, or publish a checker-rejected JavaBean-only candidate; a source-inaccessible bean-only string token is not loaded; a codec implementation outside the serialized-type allowlist remains valid after codec structural validation; two builders select disjoint candidates from one companion without shared failure state;
- selected value/subtype/codec/member tokens follow their distinct existing validation owners and ordering, and every rejected selected token prevents its operation from initializing or publishing;
- an annotated default-object subtype of a non-terminal bootstrap superclass fails before member/operation resolution, while an exact/type-use/declaration complete codec bypasses the object section and succeeds;
- target/companion loading through a child class loader while the builder's fixed subtype loader is different;
- concurrent first use from every pooled `JsonState`.

### Android instrumentation matrix

GitHub CI exclusively owns release-minified fixture builds, R8 acceptance, emulator
instrumentation, and Android benchmarks. Local R8 or device runs are not final acceptance evidence.
CI retains the exact APK, Android App Bundle, mapping, seeds, usage, dependency, instrumentation,
and benchmark artifacts for every matrix entry.

GitHub CI builds real release-minified fixtures with R8 full mode and no application broad keep
rules. Pin the complete non-record matrix to exactly AGP 8.0 and its bundled D8/R8 using JDK 17,
and run the same non-record matrix on the current supported AGP/R8 toolchain. If the minimum
toolchain cannot consume any required generated resource or optimization rule, raise the base
declared minimum before release. On both base-profile toolchains, run:

- API 26 and current API 36 for the complete non-record matrix;
- application-owned annotated models and models supplied by independent Java-library JAR and Android-library AAR artifacts; inspect annotation-processor `CLASS_OUTPUT`, JAR carriers, published AAR consumer rules, the canonical variant rule file, the merged R8 configuration, and final APK to prove exact generated rules and the packaged Android `-assumevalues` rule were consumed and no private carrier leaked;
- String and UTF-8 writers, OutputStream output, and Latin1, UTF16, and UTF-8 readers;
- field-backed, JavaBean, creator-only, Any, generic, recursive, private-static-nested, custom-codec, and subtype models;
- a default-object Android framework subclass that fails with the exact-codec diagnostic and complete-codec variants that bypass object metadata;
- both default `withCodegen(true)` and explicit true/false combinations to prove Android normalization.

Build the record profile separately on exactly AGP 8.2 with its bundled D8/R8 and on the current supported toolchain. Run the record corpus on API 34 or later for application-, JAR-, and AAR-owned models, and repeat the generated-rule, merged-configuration, final-APK, semantic parity, and inaccessible-canonical-constructor checks. AGP 8.0 is not a record acceptance fixture.

For the same corpus and builder settings, compare Android minified, JVM interpreted, and JVM generated outputs byte-for-byte, including property order. Each implementation must read the String and UTF-8 output produced by the other implementations. The parity matrix includes field mode, LOWER_CAMEL_CASE/SNAKE_CASE naming, null inclusion, property order, Any properties, and every subtype inclusion mode.

Inspect the minified APK, R8 mapping, seeds, and usage outputs to prove:

- companion convention names survive;
- a private target referenced only by its generated binary-name constant survives, while its unrelated fields, methods, and constructors are not retained by the class-root rule;
- directly referenced members may be renamed;
- only reflectively accessed members retain names;
- class-name string subtypes survive;
- every generated ABI entry point and runtime-selectable candidate survives while direct members can still be renamed and optimized;
- direct type lookup uses bounded switches rather than one holder class per token, generated operation subclasses own their singletons directly, and the generated-class formula is one companion plus bounded chunk classes, one accessor per direct source field, and one subclass per other required direct operation; record final DEX type count and APK size to catch optimizer-dependent expansion;
- `TypeRef` root generic signatures survive while unrelated `TypeRef` members and anonymous-subclass constructors remain unconstrained;
- stale generated fact formats reach the stable ABI diagnostic instead of an R8 missing-class failure;
- no external Janino, unsupported multi-release class, `AnnotatedType`-based Android path,
  `JsonJITContext`, or runtime compiler path is packaged; and
- the R8 mapping contains no forbidden runtime-codegen, generated-code-instantiator, Janino, or
  `AnnotatedType` input class, even if its output name was obfuscated. Raw DEX descriptor checks
  remain an independent guard for unrenamed forbidden classes.

### JVM regression suite

Run the existing Fory JSON test suite, especially codec annotation/hierarchy, type-use, Any property, creator, property order, subtypes, async compilation, module access, and all generated reader/writer capability tests. The same semantic corpus should be reused by Android parity tests instead of creating a reduced smoke model.

### Performance and allocation tests

- Benchmark current JVM generated and interpreted paths against `apache/main` according to repository performance rules.
- Use an AndroidX Benchmark instrumentation harness in the release-minified/full-mode fixture on API 26 and the current API. Record device/emulator image, AGP/R8 version, commit, warmup, iterations, raw output, and retained medians.
- Benchmark primitive field, all eight primitive bean getter/setter kinds, reference bean getter/setter, source-inaccessible typed field, inaccessible-target/signature getter/setter/Any/creator, direct no-argument construction, private no-argument construction, creator, and Any setter after warmup.
- Use ART allocation counters around the measured region and a control benchmark to attribute events. Exclude `ForyJson` and codec construction from steady-state measurement. Generated and method-handle access must add no allocation; cached executable construction may allocate only the result object and must reuse its owner-supplied argument array.
- Assert both directions of one mutable direct field use the same generated accessor identity, and both selected directions of one inaccessible field use the same Core-backed accessor identity and handle set.
- Measure first-use metadata loading separately so cold cost is visible and never mixed into throughput claims.

## User-visible Changes and Migration

- Android Java object models add `@JsonType`, configure the matching annotation processor, and apply the matching Gradle plugin to every Android application and Android library. Every declaration-owned codec/subtype root and every runtime object subtype follows the closed reachable-type rule.
- `@JsonType` is also a precise R8 retention declaration. Users annotate only model types that can participate in Fory JSON; no package-wide keep file is added.
- The supported base build profile is Android Gradle Plugin 8.0 or later with R8 full mode and minSdk 26; exact generated rules work for application, JAR, same-build AAR, and published AAR-owned models.
- A model extending a non-terminal Android boot/platform superclass needs a complete codec; default object metadata never guesses private state omitted from SDK stubs.
- Android accepts codegen/async builder options but normalizes both off. The interpreted codec still honors every semantic builder option.
- Android builds remove any manual external-Janino exclusion after Core publishes Janino as optional.
- JVM users need no processor and `@JsonType` has no runtime JVM effect.
- Android metadata preserves the JVM collector's existing candidate ordering. Users who require an
  explicit byte order declare it with `@JsonPropertyOrder`.
- Libraries compiled with an older generated ABI must be rebuilt with the same Fory runtime/processor version; no compatibility shim is retained.
- Records require the separate API 34/AGP 8.2/Java 17 profile. All other supported Java model features retain the API 26 baseline.

## Implementation Surface and Sequence

Implementation is complete only when all phases land together. Intermediate branches may be used for review, but no partial public Android claim is published.

1. Add `@JsonType`, the permanent inert-section plus indexed type/operation ABI, deterministic naming, candidate-selective metadata decoding, and `JsonTypeMetadataRegistry` to `fory-json`.
2. Extract the narrow source type-use and record-model adapters and add `ForyJsonProcessor`, service registration, aggregating metadata, generated companions, accessors/invokers, and exact R8 rule carriers to `fory-annotation-processor`.
3. Add the Android cold collector to `ObjectCodecBuilder`; route declaration codec and subtype lookups through generated metadata; preserve the single normalization pipeline.
4. Extend Core's Android field owner and the existing JSON getter/setter/Any owners with fixed-shape API 26 method handles. Give Core construction and JSON creator owners cached exact executables with static or caller-owned fixed argument arrays; remove every allocating generic method-handle construction shape and duplicate reflection wrapper.
5. Normalize Android codegen/async at registry construction, eliminate per-state disabled-JIT allocation, and put the codegen-null return before all capability map/callback work.
6. Fix published dependency packaging so Android does not require manual Janino exclusions and add static APK/linkage checks.
7. Add the first-class Gradle plugin with public variant wiring, exact carrier validation, canonical application rules, and deterministic standard AAR consumer-rule publication.
8. Add processor, JVM forced-mode, release-minified API 26/36, API 34+ record, application/JAR/AAR rule-publication, semantic parity, allocation, and performance coverage.
9. Update the Java JSON user guide with `@JsonType`, Gradle setup, reachable-type rules, exact-codec escape hatch, API levels, and R8 behavior.
10. Remove obsolete Android annotation-disable branches, replaced boxed/reflection access paths, stale tests, and any documentation that says Fory JSON is unsupported on Android.

Likely owning files and packages are:

- `java/fory-json/.../annotation/JsonType.java`;
- `java/fory-json/.../meta/JsonTypeMetadata.java`, the stable inert transport and indexed bootstrap contract, and compact selected metadata value types;
- `java/fory-json/.../resolver/JsonTypeMetadataRegistry.java`;
- existing `JsonSharedRegistry`, `JsonTypeUse`, `JsonFieldAccessor`, `JsonCreatorInfo`, `ObjectCodecBuilder`, and `ObjectCodec`;
- Core's existing `InstanceFieldAccessors` and `ObjectInstantiators` Android implementations;
- `java/fory-annotation-processor/.../ForyJsonProcessor.java` and shared source scanning/generation helpers;
- processor service and Gradle incremental metadata resources;
- `java/fory-json-gradle-plugin` application collection and AAR publication tasks;
- Android integration fixtures and the Java JSON guide.

Exact class decomposition must remain minimal during implementation. Compact immutable metadata value types are justified by distinct data shapes; manager, policy, session, plan, adapter, or parallel codec layers are not.

## Rejected Alternatives

### Generate five complete codecs per type

Rejected because field mode, naming, null policy, exact registrations, declared generic binding, and other runtime settings change codec construction. Pre-generating every combination is unbounded and makes the processor the wrong semantic owner.

### Runtime reflection plus broad R8 rules

Rejected because Android lacks reliable nested type-use annotation access, broad keeps defeat shrinking/obfuscation, source names and generic attributes become accidental runtime dependencies, and release behavior is not deterministic.

### One top-level accessor class per field

Rejected because it multiplies convention names, generated resources, class loading, and R8 rules without improving access. Private static nested singleton accessors in the one type companion have the correct type owner.

### Method handles for every field and method

Rejected because direct generated calls are more optimizable when the complete expression is source-accessible. API 26 method handles are used only for inaccessible field/getter/setter/Any access and remain inside their existing owners. Construction and creators use their measured cached executable owners. A universal handle layer would add cold work and indirection without expanding support.

### Generate only metadata and keep current method reflection

Rejected because unplanned reflection over every property leaves structure, annotations, and access dependent on R8 and may allocate argument carriers. Source-accessible calls are generated directly; inaccessible field/getter/setter/Any access uses typed handles; only construction and creators use cached exact executables with owner-supplied fixed arrays and precise retention.

### Global generated registry or `ServiceLoader`

Rejected because it creates a second aggregate artifact, obscures per-type invalidation/deletion, retains application class loaders, and adds startup scanning.

### Reflective fallback when metadata is missing

Rejected because it hides build errors, cannot recover complete `@JsonCodec` type-use semantics, and makes correctness depend on shrinker configuration.

## Implementation Owners

The implementation extends the existing owner paths rather than introducing a parallel Android
serializer model:

- [`ForyJsonBuilder`](../../java/fory-json/src/main/java/org/apache/fory/json/ForyJsonBuilder.java) and [`JsonConfig`](../../java/fory-json/src/main/java/org/apache/fory/json/JsonConfig.java) define the runtime configuration boundary.
- [`JsonSharedRegistry`](../../java/fory-json/src/main/java/org/apache/fory/json/resolver/JsonSharedRegistry.java) owns shared codec declarations, subtypes, exact registrations, security, and runtime codegen.
- [`ObjectCodecBuilder`](../../java/fory-json/src/main/java/org/apache/fory/json/codec/ObjectCodecBuilder.java) owns field/method discovery, annotation merging, creators, Any properties, ordering, and `ObjectCodec` construction.
- [`JsonTypeResolver`](../../java/fory-json/src/main/java/org/apache/fory/json/resolver/JsonTypeResolver.java) owns recursive type binding and five reader/writer capability slots.
- [`JsonTypeUse`](../../java/fory-json/src/main/java/org/apache/fory/json/meta/JsonTypeUse.java), [`JsonFieldAccessor`](../../java/fory-json/src/main/java/org/apache/fory/json/meta/JsonFieldAccessor.java), and [`JsonCreatorInfo`](../../java/fory-json/src/main/java/org/apache/fory/json/meta/JsonCreatorInfo.java) consume generated type-use, member-access, and creator facts on Android while retaining the JVM path.
- [`ObjectCodec.AnyInfo`](../../java/fory-json/src/main/java/org/apache/fory/json/codec/ObjectCodec.java) is the current runtime owner for flattened Any access.
- Core's [`InstanceFieldAccessors`](../../java/fory-core/src/main/java/org/apache/fory/reflect/InstanceFieldAccessors.java), [`ObjectInstantiators`](../../java/fory-core/src/main/java/org/apache/fory/reflect/ObjectInstantiators.java), and [`StaticGeneratedSerializerRegistry`](../../java/fory-core/src/main/java/org/apache/fory/resolver/StaticGeneratedSerializerRegistry.java) establish the cold-selection, construction, and deterministic-provider patterns.
- [`ForyStructProcessor`](../../java/fory-annotation-processor/src/main/java/org/apache/fory/annotation/processing/ForyStructProcessor.java) and [`SourceTypeNode`](../../java/fory-annotation-processor/src/main/java/org/apache/fory/annotation/processing/SourceTypeNode.java) establish processor naming, consumer-rule, and Android-safe type-tree patterns that JSON extends without reusing an insufficient type model.

## Completion Criteria

Fory JSON Android support is complete only when all of the following are true:

- `@JsonType` Java models serialize and deserialize with every supported builder configuration on API 26 and current Android.
- Declaration and nested type-use `@JsonCodec` behavior matches JVM precedence and validation after R8 full-mode minification.
- Source-accessible fields/methods/constructors use generated direct calls; inaccessible field/getter/setter/Any access uses one cold-resolved typed method handle, and inaccessible construction/creators use one cached exact executable in their existing owner.
- Real R8 release builds require no broad application keep rules and work for application-, Java-library JAR-, and Android-library AAR-owned models.
- Android default codegen settings never initialize or package a runtime compiler path.
- Android codec hot loops contain no platform branch, JIT capability map lookup, metadata lookup, boxing primitive field access, or new per-property allocation. Cached `Constructor`/`Method` invocation appears only in source-inaccessible construction/creator owners and reuses fixed argument arrays without per-call carrier allocation.
- The JVM path performs no generated-metadata lookup and stays within the repository's 1% regression threshold.
- Processor diagnostics, runtime errors, public API documentation, examples, build metadata, tests, allocation checks, and benchmarks all describe and exercise the same ownership model.
- Obsolete Android guards, reflection-only method paths, stale documentation, and temporary compatibility code are removed.
