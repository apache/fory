# Fory JSON Android Support Design

Status: Proposed

## Summary

Fory JSON will support Android API 26 and later with Android Gradle Plugin 8.0 or later release builds using R8 full mode. Android applications opt a Java type into compile-time metadata generation with a new `@JsonType` annotation and run the existing interpreted Fory JSON codecs at runtime. The annotation processor generates all facts that Android cannot safely discover after R8 shrinking, including declaration annotations, nested type-use annotations, generic type structure, members, constructors, subtype declarations, and direct member access code.

The processor does not generate a complete JSON codec. A complete codec would embed choices owned by a particular `ForyJsonBuilder`, such as field or JavaBean discovery, naming, null inclusion, registered exact codecs, and generated-code policy. Instead, the existing `ObjectCodecBuilder` remains the only owner of property normalization and builds the same `ObjectCodec` model from either JVM reflection metadata or Android generated metadata.

The generated companion contains configuration-independent `JsonFieldAccessor` implementations and narrow constructor and any-setter invokers. Members whose complete Java expression is source-accessible use direct typed Java access. Other valid members use cached, exact-shape API 26 method handles in the existing Fory Core or Fory JSON access owner, with the member resolved once on the cold metadata-loading path and precisely retained by generated R8 rules. No Android hot path uses `Method.invoke`, `Constructor.newInstance`, creates an argument array for ordinary member access, performs a metadata lookup, or checks the platform.

In this design, “runtime reflection serialization” means that Android uses Fory JSON's existing interpreted runtime codec pipeline rather than ahead-of-time complete codecs. Compile-time metadata replaces reflection for structure and annotations, generated access thunks replace ordinary member reflection, and cold reflection only resolves precisely retained members that cannot be named in generated Java source. Those members execute through cached method handles after resolution.

Non-Android JVM metadata discovery, runtime code generation, asynchronous compilation, and hot codec loops retain their current paths. The JVM path does not look for `@JsonType` companions. The one intentional JVM behavior change is canonical cold candidate ordering, required to replace unspecified reflection order with identical JVM/Android output.

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
- Kotlin source processing. This design is for Java annotation processing. Complete Kotlin support requires a Kotlin Symbol Processing frontend that emits the same runtime metadata ABI.
- Backporting Java records to Android API 26. Record support follows the Android platform level described below.

## Required User API

### `@JsonType`

Add the following marker in `fory-json`:

```java
package org.apache.fory.json.annotation;

@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface JsonType {}
```

`@JsonType` means that the Java compiler must generate the Android metadata companion for the annotated declaration. It is not inherited and has no JVM runtime effect. `CLASS` retention is sufficient because the processor owns discovery; Android never reads `@JsonType`, JSON structure, or `@JsonCodec` values from runtime annotations.

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

Android Java users add matching versions of the runtime and processor:

```gradle
dependencies {
  implementation("org.apache.fory:fory-json:<version>")
  annotationProcessor("org.apache.fory:fory-annotation-processor:<version>")
}
```

The processor has a compile dependency on `fory-json`; `fory-json` never depends on the processor. A version mismatch is diagnosed by the generated ABI check at runtime and should also be prevented by dependency-management tests.

No application ProGuard file is required for annotated types. The processor emits R8-targeted rules under `META-INF/com.android.tools/r8/`. Android Gradle Plugin consumes this location from a JAR and from `classes.jar` inside an AAR; unlike the legacy `META-INF/proguard/` JAR location, it therefore covers Android-library-owned models without copying generated rules into the AAR root. The application-module, Java-library JAR, and Android-library AAR paths are all release-build acceptance fixtures. This follows Android's [library optimization guidance](https://developer.android.com/topic/performance/app-optimization/library-optimization) and is verified using [R8 full mode](https://developer.android.com/topic/performance/app-optimization/full-mode).

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
| Other inaccessible calls  | Existing JSON accessor, creator, and Any owners                      | Resolve exact members once and invoke cached fixed-shape API 26 method handles.                    |
| R8 retention              | Per-type generated targeted R8 rule                                  | Retains only convention names, cold-resolved members, constructors, and inaccessible type tokens.  |

There is one semantic pipeline. JVM reflection and Android generated metadata are two cold input collectors for the same `ObjectCodecBuilder` normalization and codec construction. They are not separate JSON implementations.

## Module and Processor Design

### Processor placement

Extend `fory-annotation-processor` with an independent `ForyJsonProcessor`. Do not add JSON behavior to `ForyStructProcessor`: the two annotations have different generated ABIs, reachability rules, and diagnostics. Both processors may share a narrowly extracted source type-use scanner.

Add `ForyJsonProcessor` to `META-INF/services/javax.annotation.processing.Processor`. Register it independently in `META-INF/gradle/incremental.annotation.processors` as `aggregating`. One annotated declaration still produces one source file and one targeted R8-rule resource with that declaration as an originating element, and the processor emits no global registry or aggregate resource. Aggregating classification is required because a closed companion embeds declarations, and for default-object types also members, generic declarations, and annotations from unannotated ancestors; a private or annotation-only ancestor change might not otherwise recompile the annotated child.

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
7. Generate the metadata companion and its precise targeted R8 rule.

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

The registry then validates that the loaded class is a concrete `JsonTypeMetadata` and validates its public `(Class)` constructor without initialization. The generated class has no non-constant class initializer. Its constructor only calls `JsonTypeMetadata(target, EXPECTED_BINARY_NAME, ABI_VERSION)`. The base constructor resolves that name without initialization through the generated class's defining loader and validates exact target identity and ABI, so a same-name class from another loader is rejected without requiring generated source to name a private target. A later section request initializes only its corresponding preflight holder, followed after validation by that section's direct-type holder and then operation holder. Class literals do not initialize their classes; the registry security-checks annotation codec and subtype classes before constructing an operation or executing user code.

### Generated ABI version

`JsonTypeMetadata` exposes a compile-time constant ABI version and permanent integer constants `DECLARATIONS`, `SUBTYPES`, and `OBJECT`. Its protected constructor receives the requested target, generated target binary name, and generated version, and validates all three before metadata is accessed. Each companion implements permanent public `Object metadata(int section)`, `Object metadataTypes(int section)`, and `Object metadataOperations(int section)` bootstrap methods. Private preflight, direct-type, and operation holders exist for each section. Asking only for declaration state does not initialize subtype or object-member sections, and asking for one section's preflight data does not initialize its class literals, accessors, invokers, or constructors. An unknown section is rejected before touching any holder.

After an exact version check, the registry invokes `metadata(section)` and casts the result to the permanent `JsonTypeMetadataData` preflight transport. It contains only compact versioned `byte[][]` fact chunks and precisely retained binary names for source-inaccessible tokens. The decoder validates the fact framing, counts, tags, and name indexes; applies the disallow list and configured checker to every binary name; then loads, identity/role/assignability-validates, and class-checks every accepted named type. Only after the whole preflight succeeds does the registry invoke `metadataTypes(section)` and cast the result to `JsonTypeMetadataTypes`. It class-checks every direct token and validates all cross-table type identities, roles, and assignability. Only after every type succeeds does it invoke `metadataOperations(section)`, validate every stable generated thunk against its fact-declared shape, and construct handle-backed inaccessible operations. A rejected name may initialize only the inert preflight holder; it cannot initialize a direct-type or operation holder. A rejected direct type cannot initialize an operation holder. Neither failure can construct an operation or publish a decoded section.

The permanent transport shapes are deliberately narrow. `JsonTypeMetadataData` has a public constructor taking `byte[][] facts` and `String[][] inaccessibleTypeNames`; `JsonTypeMetadataTypes` has a public constructor taking `Class<?>[][] directTypes`; and `JsonTypeMetadataOperations` has a public constructor taking `Object[][] operations`. Each exposes final accessors only for its arrays. Fact entries carry table indexes, but no later-table index is dereferenced before the preceding phase succeeds. The decoder rejects an out-of-range index, wrong stable thunk base type, duplicate declaration key, trailing byte, or section-inappropriate tag before publishing the decoded section. Empty chunks use stable shared empties. No map, annotation proxy, reflective member, or decoded metadata object crosses this ABI.

This neutral transport is required for R8, not only for lazy JVM linking. Full-mode R8 analyzes every kept companion method and would report a removed old payload class before a runtime `abiVersion()` check could run. A stale companion instead references only the permanent bootstrap/transport/thunk ABI; the runtime rejects its version before decoding its fact stream or initializing an unrequested holder. `JsonTypeMetadataRegistry` instructs the user to align `fory-json` and `fory-annotation-processor` and recompile the annotated model.

Breaking the compact fact format and decoded internal metadata is allowed. Its version rejects stale generated code; no old decoder or metadata shim is retained.

One generated ABI surface is permanently stable so mismatch diagnostics and R8 analysis survive fact-format changes: the `JsonTypeMetadata`, `JsonTypeMetadataData`, `JsonTypeMetadataTypes`, and `JsonTypeMetadataOperations` binary names and fixed members; the protected `(Class requested, String generatedName, int version)` constructor; the final `abiVersion()` accessor; the companion's public `(Class)` constructor; the three section bootstrap methods; and the generated thunk base methods on `JsonFieldAccessor`, `JsonCreatorInvoker`, `JsonAnySetterInvoker`, `JsonCodecFactory`, and `ObjectInstantiator`. This is the generated-code contract, not a compatibility shim. The compact fact format and all decoded metadata value classes may break at an ABI bump because generated bytecode does not reference them.

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

Every referenced type is classified independently from member accessibility. A directly nameable type uses a generated class literal. A source-inaccessible type uses its exact binary name in the neutral facts and receives a precise generated `-keep,allowoptimization` rule so R8 neither removes nor renames it. Before any load, `JsonSharedRegistry` applies its existing name-level owner path: the Fory disallow list followed by the configured `JsonTypeChecker` decision for that binary name. Only an accepted name is loaded without initialization through the annotated target's defining loader. The registry then verifies the exact binary name, expected metadata role and assignability, and applies the existing class-level security path before publishing the token. This applies to erased member types, nested generic arguments, bounds, literal subtype entries, and inaccessible ancestors. The fixed builder class loader remains reserved for user-authored `JsonSubTypes.Type.className` entries, which follow the same name-before-load and class-after-load order through that loader. A rejected name may initialize its inert preflight holder but must not load the class, initialize the section's direct-type or operation holder, construct an operation, or publish a decoded section.

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
-keep,allowshrinking,allowoptimization,allowobfuscation class org.apache.fory.reflect.TypeRef { *; }
-keep,allowshrinking,allowoptimization,allowobfuscation class * extends org.apache.fory.reflect.TypeRef
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

Generate member access code, but do not generate one top-level class per field and do not generate complete codecs. Each metadata companion's per-section operation holder owns static singleton operation slots for directly expressible candidate directions through private static nested `JsonFieldAccessor` implementations. That holder is requested only after the section's inert facts, every inaccessible binary name, and every direct class token pass their respective checks. For an inaccessible candidate, the validated cold decoder then constructs the existing owner with one cached exact handle and retains it in the registry's decoded section. This gives R8 direct symbolic references where possible while avoiding classpath indexes and all per-value accessor allocation.

`ObjectCodecBuilder` selects the appropriate candidate after it applies field mode, JavaBean mode, annotation merging, and read/write availability. The companion carries the union required by every builder configuration, so R8 may optimize and rename direct candidates but must not remove a candidate reachable from the generated ABI.

### Direct source access

For a source-accessible field or method expression, the generated accessor reads, writes, or invokes it directly. For every primitive field, primitive getter, and primitive setter, it overrides the matching typed `getBoolean`/`getInt`/`putLong` method and equivalents so the existing codec loop does not box. Reference access uses `getObject`/`putObject`. R8 rewrites these symbolic references when it renames the member, so no member-name keep rule is needed.

The access code is emitted in the companion's package. Java access rules therefore permit public and package-access members in the same package, plus protected members where the generated access expression is legal. The processor tests the complete expression: target and declaring types, every enclosing type, member access, cast/return/parameter types, arrays, and constructor expression must all be nameable from that package. A `public` modifier alone is not sufficient; a public method can expose an inaccessible class in its signature.

### Source-inaccessible fields

Field mode intentionally supports private fields and inherited fields that are not source-accessible from the companion package. After validating the object facts and type tokens, the decoder resolves the exact declared `Field` once and delegates to the existing `JsonFieldAccessor.forField`/Core typed `FieldAccessor` owner. On API 26+, Core sets accessibility once, creates getter/setter method handles with [`Lookup.unreflectGetter`/`unreflectSetter`](https://developer.android.com/reference/java/lang/invoke/MethodHandles.Lookup), adapts them once to the exact `(Object)primitive`, `(Object,primitive)void`, `(Object)Object`, or `(Object,Object)void` shape, and caches them in the accessor. The generated targeted rule retains that exact field name and descriptor.

The steady-state primitive paths call the typed handle with `invokeExact` from `getBoolean`, `getInt`, `putLong`, and equivalent operations. The current Core Android `ReflectionAccessor`, which inherits boxing `Field.get`/`Field.set` defaults, is replaced rather than wrapped or retained. Successful access does not box, allocate, call `Field.get`/`Field.set`, or branch on Android. Cold field lookup, access elevation, handle construction, and failure translation stay in separate methods so they are not inlined into the accessor hot path.

There is no parallel Fory JSON field-handle implementation. A generated direct call remains preferable wherever Java can express it; the source-inaccessible case extends Core's existing `FieldAccessor` owner with its API 26 implementation.

### Other call shapes

The companion also owns narrow, configuration-independent call objects where `JsonFieldAccessor` is not the right shape:

- `JsonCreatorInvoker` invokes an annotated constructor or public static factory;
- `JsonAnySetterInvoker` invokes a public Any setter with `(String, value)`;
- `JsonCodecFactory` directly constructs a processor-validated public annotation codec;
- an `ObjectInstantiator` directly invokes a no-argument or record canonical constructor.

Directly generated objects are companion-owned static singletons; handle-backed objects are decoded-section singletons. Existing `JsonCreatorInfo`, Any metadata, and `ObjectCodec` retain and invoke them. They do not create a second construction or Any-property pipeline. When the complete call expression is source-accessible, generated code calls it directly. Otherwise the same owner resolves the exact `Method` or `Constructor` from validated generated name/descriptor facts, sets accessibility once, unreflects it, and adapts it once to a fixed signature-polymorphic call shape. Getter/setter and Any handles take explicit arguments; creator handles use `asSpreader(Object[].class, arity)` and consume the already-required decoded argument array. No successful call creates an invocation carrier or wrapper.

For a source-accessible no-argument constructor, the generated `ObjectInstantiator` performs `new Target()` directly. A private or otherwise source-inaccessible no-argument constructor remains owned by Core's Android `ObjectInstantiator`: Core resolves and makes the exact constructor accessible once, creates a `Lookup.unreflectConstructor` handle, adapts it to `()Object`, and invokes it with `invokeExact`. The precise R8 rule retains only that constructor. There is no `Constructor.newInstance` fallback; API 26's accessible-flag-aware unreflection is the required owner path, and device allocation verification must show only the constructed result.

Records do not use `JsonCreatorInfo`. When the complete canonical-constructor expression is source-nameable, the generated `ObjectInstantiator.newInstanceWithArguments` invokes it directly. Otherwise the decoded section selects the existing `ObjectInstantiator` owner, resolves the exact canonical constructor after type-token and security validation, unreflects it once, adapts it with `asSpreader(Object[].class, arity)` to `(Object[])Object`, and caches that handle. This covers a private static record and a canonical signature containing a source-inaccessible component type without adding a record-specific construction path. The targeted R8 rule retains only the exact inaccessible canonical constructor. An explicit `@JsonCreator` remains rejected for a record. The Android collector supplies the selected generated or Core instantiator to `ObjectCodecBuilder`; it must not unconditionally call `ObjectInstantiators.createObjectInstantiator(type)` after that selection.

Generated direct calls and exact method-handle calls preserve existing exception contracts without successful-path allocation. Each call site catches `Throwable`, including direct no-argument and record construction; this avoids naming an inaccessible declared exception and covers unchecked failures. Field/getter/setter access calls a cold error helper owned by `JsonFieldAccessor`; creator, Any invoker, and `ObjectInstantiator` contracts own their corresponding cold translation helpers. Field access preserves the current wrapped access failure, while creator and Any invocation rethrow `Error`, retain the target cause, and create `ForyJsonException` only on failure. `JsonCreatorInfo` continues to enforce the exact non-null result type for factory creators.

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

Before loading or initializing a generated companion or annotation codec, the runtime applies the existing security check to the target type. For every binary-name token, `JsonSharedRegistry` first runs the disallow list and configured checker against the name, loads without initialization only after acceptance, then validates exact identity/role/assignability and runs the existing class-level check. Direct class literals still receive the class-level check before use. A failed name check must not load the named class, initialize an unrequested generated holder, construct a handle-backed operation, or publish a decoded section; a failed class check also prevents operation construction and publication.

### Object codec construction

Split only the cold collection step:

```text
JVM:     reflection collectors ─┐
                                ├─ existing ObjectCodecBuilder normalization ─ ObjectCodec
Android: generated collectors ──┘
```

Only a request that has passed exact/type-use/declaration/subtype/built-in resolution can load the default-object section. `ObjectCodecBuilder` then receives the same candidate field, accessor, creator, Any, order, and type-use information. Its current merge and validation methods remain the sole implementation. Do not introduce a generic metadata-source policy interface if two direct cold entry methods are sufficient.

The existing private `FieldBuilder` currently stores `Field`, `Method`, `AnnotatedElement`, and annotation proxy objects. It must be refactored so its semantic state is annotation values, resolved type nodes, source descriptions, and selected accessors. The JVM collector continues to attach optional reflection members needed by JVM runtime code generation; the Android collector attaches generated accessors/invokers and never synthesizes a `Method` or a `Field` for directly accessed members. Two collector-specific overloads populate the same `FieldBuilder` and call the same merge/validation functions. Do not allocate a generic candidate wrapper per reflected JVM member merely to make the two collectors look identical.

The source-neutral cold boundary is concrete:

- generated field facts contain declaring class, source name, modifiers, flattened `JsonIgnore`/`JsonProperty`/Any values, `JsonTypeNode`, source description, read/write eligibility, and generated read/write accessors;
- generated method facts contain declaring class, name, JVM descriptor, modifiers, flattened annotations, return/parameter `JsonTypeNode` values, override identity, source description, and generated getter/setter/Any invokers;
- generated creator facts contain executable kind, source description, parameter names/annotations/type nodes, raw parameter classes, defaults, and `JsonCreatorInvoker`;
- generated record facts contain component names/annotations/type nodes and the canonical-constructor `ObjectInstantiator`;
- generated subtype/order/declaration-codec facts contain annotation values, class literals or exact class-name strings, and annotation-codec factories, never annotation proxies.

`JsonTypeNode.resolve(ownerType, ownerTypeUse)` reconstructs the declared/bound `Type`, raw class, unresolved-variable state, and `JsonTypeUse` from generated structure and the requested owner binding; it never reads a member `Signature`. It has concrete representations for parameterized, array, wildcard, and generated variable-key nodes, but it never materializes a fake `TypeVariable`. `JsonFieldInfo` receives explicit resolved read/write `Type`, raw class, type use, and accessor instead of deriving them from `Field` or `Method`. Its optional reflection members exist only for JVM code generation and diagnostics.

`AnyInfo` stores explicit read/write capability flags, resolved map/value types, accessors, and `JsonAnySetterInvoker`; it does not use non-null `Field`/`Method` values as capability flags. `JsonCreatorInfo` gains a generated entry point taking explicit creator facts and `JsonCreatorInvoker`; its optional `Executable` and existing JVM handle remain only on the JVM collector path, while an Android inaccessible-call handle is encapsulated by the generated invoker selected on the cold path. These changes remove reflection ownership from runtime metadata without duplicating merge, order, or validation logic.

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

The selected annotation codec is instantiated at most once per codec class per `ForyJson`, after security validation. A codec class must be public, concrete, top-level or static nested, be enclosed only by public declarations, and expose a public no-argument constructor. A public codec nested in a private or package-private enclosing declaration is a processor/runtime error, matching `JsonSharedRegistry.validateCodecClass`; it is not routed through an inaccessible-constructor handle. Android metadata supplies a generated `JsonCodecFactory` that calls the valid constructor directly; the JVM reflection collector retains its current constructor path.

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

For each companion, emit `META-INF/com.android.tools/r8/fory-json-generated-<escaped-name>.pro`. Rules are precise and derived from the generated access mode. The same targeted location is preserved in a Java-library JAR and in an Android library's `classes.jar`; using only legacy `META-INF/proguard` would not satisfy the AAR contract.

### Convention lookup

- Keep the annotated target class and its binary name because the runtime derives the companion name from it and an inaccessible target is intentionally referenced only by its generated binary-name constant.
- Keep the companion name, its public `(Class)` constructor, and stable public `Object metadata(int)`, `Object metadataTypes(int)`, and `Object metadataOperations(int)` bootstrap methods.
- For a version-matched companion, `metadata(section)` reaches only the selected inert fact/name holder. After name preflight, `metadataTypes(section)` reaches only the matching direct-token holder. After type validation, `metadataOperations(section)` reaches only the matching operation holder.
- Allow optimization where it does not invalidate convention lookup, but do not allow obfuscation of either convention name or removal of an ABI entry point.

The generated rule has this semantic shape, with exact generated method signatures rather than a wildcard in the implementation:

```proguard
-keep,allowoptimization class application.model.Target {
}
-keep,allowoptimization class application.model.Target_ForyJsonMetadata extends org.apache.fory.json.meta.JsonTypeMetadata {
  public <init>(java.lang.Class);
  public java.lang.Object metadata(int);
  public java.lang.Object metadataTypes(int);
  public java.lang.Object metadataOperations(int);
}
```

`@JsonType` is an explicit retention declaration. The companion is reached only through a derived string, and a private target may also appear only as a generated binary-name constant, so a correct full-mode rule retains both classes as roots without retaining target members. The design does not claim that R8 can remove an unused annotated model. This bounded size cost is the deterministic consequence of name-based, registration-free lookup. R8 may still optimize and obfuscate directly referenced members and removes unrelated unannotated code.

### Direct member access

Do not keep a field, getter, or setter solely because generated bytecode references it directly. R8 owns and rewrites that symbolic reference. It may inline or remove the generated accessor when safe.

### Reflective members

For every field, method, or constructor resolved on the cold path, emit one exact `-keepclassmembers,allowoptimization` rule with its declaring class, name, and descriptor. Do not allow obfuscation of the reflected name. Do not retain unrelated members.

Every source-inaccessible class token encoded by binary name receives one exact `-keep,allowoptimization class ExactName {}` rule. The explicit empty member block is required: omitting the member block can retain a default constructor, which is not part of class-token reachability. This rule is required for private nested raw types, generic arguments and bounds, inaccessible ancestors, and literal subtype values because R8 cannot rewrite an arbitrary generated string. Directly nameable class literals need no such rule. User-authored `JsonSubTypes.Type.className` entries use the same empty-member class rule and keep their separate builder-class-loader semantics.

An annotation codec constructor is directly referenced by its generated `JsonCodecFactory`, so it needs no reflective rule. A source-inaccessible ordinary no-argument constructor retained for Core unreflection receives one exact constructor rule. Literal `Class<?>` references establish class reachability normally. A `JsonSubTypes.Type.className` string requires an exact `-keep,allowoptimization` rule for the named class, without obfuscation, because R8 cannot rewrite an arbitrary string and could otherwise remove the class.

An unresolved `className` is legal only for a subtype supplied to the final application as a later runtime dependency, which is the purpose of the string form. A library processor emits the exact rule text without loading that class; final-app R8 must resolve it. Runtime still checks presence, assignability, uniqueness, security, and the subtype's own metadata/custom-codec requirement. Tests cover both a runtime-only dependency that succeeds and a genuinely missing class that preserves the current actionable runtime failure.

### Forbidden rules and dependencies

Generated rules must never contain package-wide `-keep class ** { *; }`, retain runtime annotation/method-parameter attributes, retain member generic signatures, or preserve all members of an annotated type. The only signature exception is the live anonymous `TypeRef` subclass/base endpoint rule required by the declared-type root API. Runtime model semantics use generated strings and type nodes rather than source names recovered from reflection.

The release APK must not contain an external Janino dependency, relocated Janino bytecode, JDK 25 multi-release classes, Android-incompatible `AnnotatedType` descriptors in Android-loaded metadata classes, or any JSON runtime-codegen class/descriptor. Mark Core's build-time Janino dependency optional in the published `fory-core` POM and Gradle module metadata. The shaded, relocated Janino classes remain inside the Core JAR for JVM runtime code generation, while Android consumers no longer receive the external artifact and full-mode R8 removes the shaded Android-unreachable copy. Users no longer write a manual exclusion. Published Maven and Gradle dependency-graph tests and final DEX inspection enforce both halves.

## Android API Levels and Java Features

The base Android contract is Android Gradle Plugin 8.0 or later, R8 full mode, Java 8 application bytecode, and minSdk 26. Ordinary classes, field mode, JavaBean mode, creators, Any properties, subtype handling, custom codecs, all reader inputs, and all writer outputs run on API 26.

API 26 method handles define the inaccessible-member boundary: generated direct calls remain preferable, while Core's field/construction owners and Fory JSON's existing method/creator/Any owners cache exact adapted handles when Java source cannot express the call. No second access abstraction is introduced.

Java records are not part of the API 26 platform contract. Record support is a separate product profile requiring Android Gradle Plugin 8.2 or later, Java 17 source/target compatibility, and minSdk 34 or later; it does not rely on record desugaring. AGP 8.2 introduced native record dexing for min-api 34 and later, and `java.lang.Record` is an API 34 class, as documented in the [AGP 8.2 release notes](https://developer.android.com/build/releases/agp-8-2-0-release-notes) and [Android Java version guidance](https://developer.android.com/build/jdks). The processor's Java 8-compatible record adapter emits record metadata whenever the host compiler exposes a record declaration, because a Java annotation processor does not own or reliably receive the Android minSdk. The Java guide and Gradle fixtures enforce the platform profile. A minSdk 26 fixture containing a record is a required negative build test, and record execution is tested on API 34+. No record class is loaded in API 26 tests.

## Errors and Security

All failures occur on the cold resolution path and throw `ForyJsonException` with the target type and corrective action:

- missing companion: name the type, require `@JsonType`, the annotation processor, matching versions, and intact generated targeted R8 rules;
- ABI mismatch: report runtime and generated versions and require recompilation;
- wrong companion target: report both classes;
- missing cold-resolved member or inaccessible type token: identify the declaring class, member/type, generated companion, and likely processor/R8 defect;
- unsupported declaration form: fail compilation where possible and runtime validation otherwise;
- missing reachable object metadata: report the property/type-use path from the annotated owner;
- invalid codec or subtype: preserve existing security, assignability, constructor, conflict, and class-loader diagnostics.

There is no fallback to best-effort reflection, `setAccessible` retries, alternate provider names, package scans, or silently ignored annotations. Such fallbacks would make release behavior depend on R8 accidents and would create a second semantic path.

## Performance Design

### Hot-path invariants

- A completed codec uses the existing `ObjectCodec`, reader, writer, `JsonTypeInfo`, `JsonFieldInfo`, and `JsonFieldTable` hot loops.
- No per-value platform test, metadata lookup, map lookup, reflective member discovery, callback wrapper, tuple, or generated-metadata allocation is added.
- Direct generated primitive access uses typed overrides and does not box.
- Accessors, invokers, neutral fact arrays, decoded metadata, and type nodes are constructed once and retained by the generated companion or registry.
- Source-inaccessible members are resolved once; hot operations use cached exact method handles in their existing owners.
- Large loading, validation, and reflective restoration methods remain separate cold methods and are not tiny forwarding helpers likely to inline into codec loops.
- Object creation and an existing creator argument array are semantic allocations; this design adds no allocation to them.

### JVM isolation

The non-Android branch does not derive a companion name, call `Class.forName`, allocate a metadata registry, wrap reflected members, or test `@JsonType`. Existing JVM reflection and code generation remain the default. `@JsonType` is ignored at runtime on JVM.

Any shared code added to `ObjectCodecBuilder` is cold property normalization already executed once per type binding. Existing JVM codec-loop bytecode must not change except where a separately measured cleanup is required.

### Acceptance thresholds

For non-Android JVM benchmarks, compare the branch against a fresh `apache/main` worktree for both generated and interpreted codecs, annotated and unannotated models. A retained median regression greater than 1% for the same benchmark is unresolved.

Measure cold JVM cost separately: `ForyJson` construction plus first raw-object binding, first parameterized-object binding, and first serialization for small and inheritance-heavy models. Canonical ordering reuses reflection-returned arrays or existing builder collections and a static comparator; it does not allocate a per-member wrapper. A retained median cold regression greater than 1% is also unresolved.

On Android, measure field-backed and JavaBean-backed models in release-minified builds. Allocation tracking must show zero additional allocations per serialized/deserialized property after codec construction. Compare generated direct access and cached inaccessible-member handles to the isolated current reflection/`Method.invoke` implementation; a slower or allocating selected implementation is not accepted.

## Verification Plan

### Annotation processor tests

- Generate companions for top-level, nested, inherited, generic, array, wildcard, and recursive models.
- Compile the generated source with Java 8 source/target rules.
- Exercise `@JsonType` and real `@JsonCodec` annotations through javac; tests must fail if generated metadata is removed.
- Cover `@JsonCodec` on declarations and every nested TYPE_USE location, including the javac tree fallback.
- Cover fields, public getters/setters, source-inaccessible fields/signatures/ancestors, creators, Any setters, and record components. Include private nested raw types, nested generic arguments, bounds, literal subtype values, and public methods whose signatures expose them.
- Generate a direct canonical-constructor instantiator for a fully source-nameable record and a cached exact-handle instantiator for a private static record or inaccessible canonical signature; reject a codec enclosed by any non-public declaration.
- Validate deterministic escaped names, permanent bootstrap/transport/thunk ABI constants, independently lazy sections, originating elements, aggregating processor metadata, and one targeted R8 rule per type.
- Compile a large stress hierarchy to prove deterministic fact/operation chunking keeps every generated method below JVM/DEX limits and the decoder never concatenates chunks.
- Reject local, anonymous, a non-static annotated member, invalid creator, invalid codec constructor, and source-invariant type-use conflicts; accept private static targets, private enclosing chains, and a static target inside a non-static enclosing class on Java 17.
- Cover raw class variables, recursive bounds, and generic bean-method variables with and without `@JsonCodec`; assert JVM/Android unresolved-variable parity.
- Prove declaration-only enum/interface/abstract/built-in-family companions neither emit nor initialize a default-object section; include Android-stub platform ancestors whose private fields are unavailable to javac.
- Load the processor and compile ordinary Java 8 models on its minimum JDK; compile record models under JDK 17+ through the version-neutral record adapter.
- Inspect generated R8 rules for exact members and inaccessible type tokens, the targeted `META-INF/com.android.tools/r8/` location, and absence of broad keeps.
- Run Gradle TestKit and Android multi-module incremental cases for editing one annotated type, removing `@JsonType`, deleting/renaming a type, changing a same-module superclass/interface, changing an upstream library ABI/annotation, and rebuilding an AAR. Assert aggregating invalidation reruns the processor when inherited facts can change, stale generated source/rules are deleted, and incremental output is byte-identical to a clean rebuild.

### JVM Android-mode tests

Run subprocess tests with `FORY_ANDROID_ENABLED=1` to cover behavior that does not require ART:

- codegen and async options normalize without constructing `JsonCodegen` or `JsonJITContext`, and capability calls return before any map lookup;
- generated metadata lookup, cache publication, recursion rollback, missing companion, and ABI mismatch;
- three-stage section initialization: rejected preflight names initialize only inert fact/name data, accepted names permit direct tokens to initialize and be checked, and only accepted direct tokens permit the operation holder to initialize exactly once;
- exact intrinsic built-ins without metadata, plus application enum/container/date-family types whose declaration-only companion is required before built-in selection and never initializes object members;
- field/JavaBean mode, LOWER_CAMEL_CASE/SNAKE_CASE naming, null inclusion, property order, and exact codec registration matrices;
- declaration and type-use codec precedence, inheritance conflicts, nested generics/arrays, map-value and map-key rules, and Any values;
- all subtype inclusion modes, literal and string subtype declarations, class loader, checker, and security order;
- rejected generated binary-name tokens fail the name-level check before class loading, direct-type/operation-holder initialization, handle construction, or decoded-section publication; accepted tokens receive exact identity/role/assignability and class-level checks after loading, and a rejected direct token cannot initialize the operation holder;
- target/companion loading through a child class loader while the builder's fixed subtype loader is different;
- concurrent first use from every pooled `JsonState`.

### Android instrumentation matrix

Build a real release-minified fixture with R8 full mode and no application broad keep rules. Run:

- API 26 and current API 36 for the complete non-record matrix;
- API 34 or later for the record matrix;
- application-owned annotated models and models supplied by independent Java-library JAR and Android-library AAR artifacts; inspect each intermediate artifact and the final merged R8 configuration to prove its generated targeted rules were consumed;
- String and UTF-8 writers, OutputStream output, and Latin1, UTF16, and UTF-8 readers;
- field-backed, JavaBean, creator-only, Any, generic, recursive, private-static-nested, custom-codec, and subtype models;
- both default `withCodegen(true)` and explicit true/false combinations to prove Android normalization.

For the same corpus and builder settings, compare Android minified, JVM interpreted, and JVM generated outputs byte-for-byte, including property order. Each implementation must read the String and UTF-8 output produced by the other implementations. The parity matrix includes field mode, LOWER_CAMEL_CASE/SNAKE_CASE naming, null inclusion, property order, Any properties, and every subtype inclusion mode.

Inspect the minified APK, R8 mapping, seeds, and usage outputs to prove:

- companion convention names survive;
- a private target referenced only by its generated binary-name constant survives, while its unrelated fields, methods, and constructors are not retained by the class-root rule;
- directly referenced members may be renamed;
- only reflectively accessed members retain names;
- class-name string subtypes survive;
- every generated ABI entry point and runtime-selectable candidate survives while direct members can still be renamed and optimized;
- stale generated fact formats reach the stable ABI diagnostic instead of an R8 missing-class failure;
- no external Janino, unsupported multi-release class, `AnnotatedType`-based Android path, `JsonJITContext`, or runtime compiler path is packaged.

### JVM regression suite

Run the existing Fory JSON test suite, especially codec annotation/hierarchy, type-use, Any property, creator, property order, subtypes, async compilation, module access, and all generated reader/writer capability tests. The same semantic corpus should be reused by Android parity tests instead of creating a reduced smoke model.

### Performance and allocation tests

- Benchmark current JVM generated and interpreted paths against `apache/main` according to repository performance rules.
- Use an AndroidX Benchmark instrumentation harness in the release-minified/full-mode fixture on API 26 and the current API. Record device/emulator image, AGP/R8 version, commit, warmup, iterations, raw output, and retained medians.
- Benchmark primitive field, primitive bean getter/setter, reference bean getter/setter, source-inaccessible typed field, inaccessible-signature getter/setter/Any/creator, direct no-argument construction, private no-argument construction, creator, and Any setter after warmup.
- Use ART allocation counters around the measured region and a control benchmark to attribute events. Exclude `ForyJson` and codec construction from steady-state measurement. Generated and method-handle access must add no allocation; private construction may allocate only the result object.
- Measure first-use metadata loading separately so cold cost is visible and never mixed into throughput claims.

## User-visible Changes and Migration

- Android Java object models add `@JsonType` and configure the matching annotation processor. Every declaration-owned codec/subtype root and every runtime object subtype follows the closed reachable-type rule.
- `@JsonType` is also a precise R8 retention declaration. Users annotate only model types that can participate in Fory JSON; no package-wide keep file is added.
- The supported base build profile is Android Gradle Plugin 8.0 or later with R8 full mode and minSdk 26; generated targeted rules work for application, JAR, and AAR-owned models.
- Android accepts codegen/async builder options but normalizes both off. The interpreted codec still honors every semantic builder option.
- Android builds remove any manual external-Janino exclusion after Core publishes Janino as optional.
- JVM users need no processor and `@JsonType` has no runtime JVM effect.
- Default unannotated property order becomes the documented canonical order on both JVM and Android. Users who require another byte order declare it with `@JsonPropertyOrder`.
- Libraries compiled with an older generated ABI must be rebuilt with the same Fory runtime/processor version; no compatibility shim is retained.
- Records require the separate API 34/AGP 8.2/Java 17 profile. All other supported Java model features retain the API 26 baseline.

## Implementation Surface and Sequence

Implementation is complete only when all phases land together. Intermediate branches may be used for review, but no partial public Android claim is published.

1. Add `@JsonType`, the permanent three-stage neutral generated ABI, deterministic naming, independently lazy metadata decoding, and `JsonTypeMetadataRegistry` to `fory-json`.
2. Extract the narrow source type-use and record-model adapters and add `ForyJsonProcessor`, service registration, aggregating metadata, generated companions, accessors/invokers, and targeted R8 rules to `fory-annotation-processor`.
3. Add the Android cold collector to `ObjectCodecBuilder`; route declaration codec and subtype lookups through generated metadata; preserve the single normalization pipeline.
4. Extend Core's Android field/construction owners and the existing JSON method/creator/Any owners with fixed-shape API 26 method handles; remove the boxed reflection and `Method.invoke`/`Constructor.newInstance` hot paths they replace.
5. Normalize Android codegen/async at registry construction, eliminate per-state disabled-JIT allocation, and put the codegen-null return before all capability map/callback work.
6. Fix published dependency packaging so Android does not require manual Janino exclusions and add static APK/linkage checks.
7. Add processor, JVM forced-mode, release-minified API 26/36, API 34+ record, application/JAR/AAR targeted-rule, semantic parity, allocation, and performance coverage.
8. Update the Java JSON user guide with `@JsonType`, Gradle setup, reachable-type rules, exact-codec escape hatch, API levels, and R8 behavior.
9. Remove obsolete Android annotation-disable branches, replaced boxed/reflection access paths, stale tests, and any documentation that says Fory JSON is unsupported on Android.

Likely owning files and packages are:

- `java/fory-json/.../annotation/JsonType.java`;
- `java/fory-json/.../meta/JsonTypeMetadata.java`, the three stable neutral transports, and compact decoded metadata value types;
- `java/fory-json/.../resolver/JsonTypeMetadataRegistry.java`;
- existing `JsonSharedRegistry`, `JsonTypeUse`, `JsonFieldAccessor`, `JsonCreatorInfo`, `ObjectCodecBuilder`, and `ObjectCodec`;
- Core's existing `InstanceFieldAccessors` and `ObjectInstantiators` Android implementations;
- `java/fory-annotation-processor/.../ForyJsonProcessor.java` and shared source scanning/generation helpers;
- processor service and Gradle incremental metadata resources;
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

Rejected because direct generated calls are more optimizable when the complete expression is source-accessible. API 26 method handles are used only for the unavoidable inaccessible case and remain inside the existing Core field/construction or Fory JSON method/creator/Any owner; a universal handle layer would add cold work and indirection without expanding support.

### Generate only metadata and keep current method reflection

Rejected because `Method.invoke` remains in the Android hot path and can allocate argument carriers. Source-accessible calls are generated directly; the remaining exact members use cached signature-polymorphic method handles and precise retention.

### Global generated registry or `ServiceLoader`

Rejected because it creates a second aggregate artifact, obscures per-type invalidation/deletion, retains application class loaders, and adds startup scanning.

### Reflective fallback when metadata is missing

Rejected because it hides build errors, cannot recover complete `@JsonCodec` type-use semantics, and makes correctness depend on shrinker configuration.

## Current Implementation Evidence

The design is based on the current owner paths, not a generalized Android serializer model:

- [`ForyJsonBuilder`](../../java/fory-json/src/main/java/org/apache/fory/json/ForyJsonBuilder.java) and [`JsonConfig`](../../java/fory-json/src/main/java/org/apache/fory/json/JsonConfig.java) define the runtime configuration boundary.
- [`JsonSharedRegistry`](../../java/fory-json/src/main/java/org/apache/fory/json/resolver/JsonSharedRegistry.java) owns shared codec declarations, subtypes, exact registrations, security, and runtime codegen.
- [`ObjectCodecBuilder`](../../java/fory-json/src/main/java/org/apache/fory/json/codec/ObjectCodecBuilder.java) owns field/method discovery, annotation merging, creators, Any properties, ordering, and `ObjectCodec` construction.
- [`JsonTypeResolver`](../../java/fory-json/src/main/java/org/apache/fory/json/resolver/JsonTypeResolver.java) owns recursive type binding and five reader/writer capability slots.
- [`JsonTypeUse`](../../java/fory-json/src/main/java/org/apache/fory/json/meta/JsonTypeUse.java), [`JsonFieldAccessor`](../../java/fory-json/src/main/java/org/apache/fory/json/meta/JsonFieldAccessor.java), and [`JsonCreatorInfo`](../../java/fory-json/src/main/java/org/apache/fory/json/meta/JsonCreatorInfo.java) expose the current Android annotation, member-access, and creator gaps.
- [`ObjectCodec.AnyInfo`](../../java/fory-json/src/main/java/org/apache/fory/json/codec/ObjectCodec.java) is the current runtime owner for flattened Any access.
- Core's [`InstanceFieldAccessors`](../../java/fory-core/src/main/java/org/apache/fory/reflect/InstanceFieldAccessors.java), [`ObjectInstantiators`](../../java/fory-core/src/main/java/org/apache/fory/reflect/ObjectInstantiators.java), and [`StaticGeneratedSerializerRegistry`](../../java/fory-core/src/main/java/org/apache/fory/resolver/StaticGeneratedSerializerRegistry.java) establish the cold-selection, construction, and deterministic-provider patterns.
- [`ForyStructProcessor`](../../java/fory-annotation-processor/src/main/java/org/apache/fory/annotation/processing/ForyStructProcessor.java) and [`SourceTypeNode`](../../java/fory-annotation-processor/src/main/java/org/apache/fory/annotation/processing/SourceTypeNode.java) establish processor naming, consumer-rule, and Android-safe type-tree patterns that JSON extends without reusing an insufficient type model.

## Completion Criteria

Fory JSON Android support is complete only when all of the following are true:

- `@JsonType` Java models serialize and deserialize with every supported builder configuration on API 26 and current Android.
- Declaration and nested type-use `@JsonCodec` behavior matches JVM precedence and validation after R8 full-mode minification.
- Source-accessible fields/methods/constructors use generated direct calls; all other valid members use one cold-resolved, cached exact method handle in their existing owner.
- Real R8 release builds require no broad application keep rules and work for app and AAR-owned models.
- Android default codegen settings never initialize or package a runtime compiler path.
- Android codec hot loops contain no platform branch, JIT capability map lookup, metadata lookup, `Method.invoke`, `Constructor.newInstance`, boxing primitive field access, or new per-property allocation.
- The JVM path performs no generated-metadata lookup and stays within the repository's 1% regression threshold.
- Processor diagnostics, runtime errors, public API documentation, examples, build metadata, tests, allocation checks, and benchmarks all describe and exercise the same ownership model.
- Obsolete Android guards, reflection-only method paths, stale documentation, and temporary compatibility code are removed.
