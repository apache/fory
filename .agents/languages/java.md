# Java

Load this file when changing anything under `java/` or when Java drives a cross-language validation flow.

## Rules

- Run all Maven commands from within `java/`.
- Changes under `java/` must pass code style checks and tests.
- Fory Java requires JDK `17+`.
- Run Java `spotless` with JDK `21+`. If the current runtime is lower than 21, export `JAVA_HOME` to a JDK 21 installation before running `mvn spotless:check` or `mvn spotless:apply`.
- `fory-core` targets Java 8 bytecode and `fory-format` targets Java 11 bytecode. Do not use newer APIs in those modules.
- Do not use wildcard imports.
- Import config and annotation types instead of fully qualifying enum constants or annotation
  values; use qualified names only when a real name conflict requires it.
- If you run temporary tests with `java -cp`, run `mvn -T16 install -DskipTests` first so local Fory jars are current.
- `WriteContext`, `ReadContext`, and `CopyContext` must stay explicit. Do not reintroduce `ThreadLocal` or ambient runtime-context patterns.
- Generated serializers must not retain runtime context fields. `Fory` should stay a root-operation facade rather than accumulating serializer or convenience state.
- When the serializer class and constructor shape are known at the call site, prefer direct constructor lambdas or direct instantiation over reflective `Serializers.newSerializer(...)`.
- For GraalVM, use `fory codegen` to generate serializers when building native images. Do not add reflection configuration except for JDK `proxy`.
- In Java native mode (`xlang=false`), only `Types.BOOL` through `Types.STRING` share type IDs with xlang mode. Other native-mode type IDs differ.
- Choose one serializer ownership location per logical Java type family. Add native/xlang serializer variants only when the wire format or constructor contract truly differs.
- Do not add normal-JVM process-global caches keyed by user classes, generated classes, serializer classes, classloaders, or class-bound method handles. Prefer per-runtime state, immutable shared metadata, or build-time-only template data.
- Concrete serializers may opt into sharing only after auditing retained fields. Treat serializers retaining `TypeResolver`, `RefResolver`, mutable scratch buffers, runtime state, or classloader-sensitive state as non-shareable unless that state is externalized.
- Resolver and serializer hot paths should keep the fast-path/null-slow-path shape obvious. Hoist repeated buffer or cache-state access into locals for multi-step operations and keep rebuild/restoration logic cold.
- Do not use `instanceof` in Java hot paths, including per-value, per-field, per-element,
  read/write/copy, resolver, serializer, codec, and buffer paths. Choose concrete
  implementations during cold setup or code generation, cache final/static-final shape decisions,
  or move type checks behind cold one-time dispatch instead.
- Hot-path feature gates that are runtime constants must be `static final` fields read directly in
  the branch. Do not hide them behind helper methods such as `jdkInternalFieldAccess()`, because
  that obscures branch folding and can leave avoidable call/inlining work in hot serializers.
- In Java codec hot paths, avoid `Preconditions.checkArgument` for attacker-controlled primitive
  validation. Use direct primitive branches and throw on the cold error path to preserve inlining and
  avoid varargs/helper overhead.
- Do not introduce codegen or generated-serializer changes that may cause behavior or performance
  regressions. When editing `java/fory-core/src/main/java/org/apache/fory/builder/**` or APIs used
  by generated serializers, do extra self-review: inspect the generated output impact, preserve
  unsafe/codegen optimizations unless intentionally changing them, and run validation appropriate to
  the regression risk.
- Android and JVM serializers must use a unified wire protocol: each side must be able to
  deserialize data written by the other side. If implementation paths diverge, the writer must emit
  enough metadata for either reader to identify and parse that path correctly; add both
  Android-read-JVM and JVM-read-Android regression coverage.
- In `MemoryBuffer`, Android branches are intentional method-boundary exits. Each
  `if (AndroidSupport.IS_ANDROID)` branch must contain exactly one `MemoryOps` call and no local
  Android heap logic. Keep heap index math, direct field updates, typed array loops, and
  reader/writer index changes in `MemoryOps`; do not add Android-named helpers, heap wrapper
  helpers, or Android-specific `MemoryBuffer` subclasses.
- In `MemoryBuffer` and `MemoryOps` hot paths, duplicate small straight-line copy/read/write logic
  when that keeps control flow direct. Do not add private helper indirection to hot paths just to
  reduce local code duplication; keep helpers for slow, cold, or error paths.
- In `MemoryBuffer` small-varint read/write hot paths, once Android has exited through the single
  `MemoryOps` call, keep JVM bulk loads/stores local with raw Unsafe operations instead of routing
  through branchful `_unsafeGet*` or `_unsafePut*` helpers. Add or preserve source comments that
  explain this inlining invariant when editing these methods.
- Unsafe object-offset arithmetic must stay `long` before calls such as `getLong(Object, long)` or
  `putLong(Object, long)`. An all-`int` expression can compile under JDK8 to a bytecode descriptor
  that fails with `NoSuchMethodError` on JDK9+.
- In primitive-array swap-endian readers, do not loop through `MemoryBuffer._unsafeGet*` helpers.
  Copy the payload through the typed payload API, then swap destination values locally so the path
  stays stream-safe and avoids Android-dispatch helper drift.
- Keep GraalVM feature code as a thin metadata/registration layer. Build time should publish metadata needed for runtime reconstruction, not retain concrete generated or user serializer instances in the image heap.
- If changes touch GraalVM bootstrap, serializer retention, native-image metadata, or `ObjectStreamSerializer` GraalVM behavior, verify the native-image build and run the produced binary; a plain Java compile is insufficient.
- Put latest-JDK or virtual-thread tests in the latest-JDK test modules with the matching compiler/profile floor, and centralize runtime-version probing in existing compatibility utilities.
- For JDK25+ zero-Unsafe work, preserve serializer-family selection by type and configuration. Do not switch a type from `ObjectStreamSerializer` or another Fory serializer family to `JavaSerializer`, a JDK stream fallback, or any broad `java.* Serializable` fallback by JDK version or no-arg-constructor shape.
- JDK25+ zero-Unsafe runtime support must distinguish launch shape. When Fory is on the module
  path, use `--add-opens=java.base/java.lang.invoke=org.apache.fory.core`; when Fory is on the
  classpath, use `--add-opens=java.base/java.lang.invoke=ALL-UNNAMED`. Missing this open is an
  invalid access configuration, not a reason to open per-package JDK internals or switch
  serializer/object-creation families. JPMS tests that validate named-module access should keep the
  `org.apache.fory.core` target.
- Do not probe JDK25+ trusted-lookup availability and turn `_JDKAccess` field-access booleans false when the required `java.base/java.lang.invoke` open is missing. Keep those access flags true on JDK25+ and let the owning trusted-lookup path raise the configuration error.
- Keep JDK25+ unsafe-removal implementation invariants in agent/design docs and tests, not user guides. User guides should document user actions such as `--sun-misc-unsafe-memory-access=deny` and `java.base/java.lang.invoke` opens; do not expose internal serializer names, owner-model rationale, or avoided fallback strategies there.
- JDK25+ user docs must not require application module package opens for Fory private-field access.
  The only required platform open is `java.base/java.lang.invoke`, targeted to `ALL-UNNAMED` for
  classpath runs or `org.apache.fory.core` for module-path runs; application module package opens
  are not part of this design.
- JDK25+ final-field user docs must not tell ordinary classes to implement
  `java.io.Serializable`. Fory supports ordinary non-Serializable classes; mention
  `Serializable` only for JDK serialization hook examples or `java.*` serializability checks.
- JDK25+ final-field user docs must not include fallback advice such as switching unsupported
  classes to records, no-arg constructors, or custom serializers. Keep user docs focused on the
  supported runtime setup and normal class model.
- Do not create a separate JDK25+ support user-guide page for the Java runtime setup unless the
  user explicitly asks for one. Keep the `java.base/java.lang.invoke` open in install-facing docs
  such as the Java/Kotlin/Scala install sections and README.
- JDK25+ zero-Unsafe final-field writes must use a true target-class trusted lookup from the original `IMPL_LOOKUP`, not `IMPL_LOOKUP.in(type)`. JDK26+ normal Fory final-field restoration must pass with `--illegal-final-field-mutation=deny` and must not require `--enable-final-field-mutation`.
- For JDK25+ object creation, do not use `sun.reflect.ReflectionFactory`, `jdk.unsupported`, or an
  Unsafe-backed object instantiator. Normal JVM no-constructor construction must use the
  `ObjectInstantiators` ReflectionFactory bypass path with `Object` as the template constructor, so
  normal Fory field restoration does not inherit ObjectStream-only first-non-Serializable-superclass
  constructor validation. ObjectStream-compatible serializers own the separate
  `ParentNoArgCtrInstantiator` path and must keep Java serialization parent-constructor rules. The
  JDK25+ ReflectionFactory path uses trusted-lookup access to `jdk.internal.reflect.ReflectionFactory`
  in `java.base` and must not require `--add-opens=java.base/jdk.internal.reflect=...`; the only
  JDK25+ platform open remains `java.base/java.lang.invoke=org.apache.fory.core`. GraalVM JDK25+
  native-image ordinary serializers may use an `ObjectStreamClass.newInstance` MethodHandle only
  for the exact Serializable case where the serialization constructor class is `Object`; that
  preserves normal empty-instance semantics because no user superclass constructor can run. For
  Serializable classes whose first non-Serializable superclass is not `Object`, fail explicitly
  instead of importing ObjectStream parent-constructor rules into normal Fory object creation.
  ObjectStream-compatible serializers are the broader stream-specific path: direct ReflectionFactory
  serialization constructors can produce `Object` there, so they use a cached `ObjectStreamClass`
  and a private `ObjectStreamClass.newInstance` MethodHandle from `_JDKAccess._trustedLookup`. If
  the instantiator is retained in the native-image heap, the
  MethodHandle owner may be initialized at build time but the per-type `ObjectStreamClass` descriptor
  must be cached only at image runtime. `ForyGraalVMFeature` must register the matching serialization
  constructor target class for each Serializable hierarchy member so runtime-lazy
  `ObjectStreamClass.lookupAny` can build descriptors for JDK classes such as `TreeMap` and
  `TreeSet`. Classes unsupported by ReflectionFactory itself require an accessible no-arg
  constructor, a record canonical constructor path, or a custom serializer.
- `UnsafeObjectInstantiator` is the JDK8-24 Unsafe owner only. It must be a top-level instantiator
  with a Java25 multi-release stub that contains no Unsafe, ObjectStream, ReflectionFactory, or
  constructor-bypass implementation.
- Keep the Java25 `_Lookup` overlay unless a future refactor can merge it without exposing Unsafe to the JDK25 class graph. Root `_Lookup` uses Unsafe for the JDK8-24 trusted-lookup fast path, while Java25 `_Lookup` uses the required `java.lang.invoke` open. `DefineClass` is root-owned; when Java25+ generated serializers need hidden nestmate class definition, it must use cached method handles and reflective `Lookup.ClassOption.NESTMATE` loading so Java 8 through Java 14 can still load the root class safely.
- Treat `ByteArrayOutputStream` and `ByteArrayInputStream` as ordinary streams on every JDK. Do
  not restore private-buffer wrapping for JDK8-24 performance, because that reintroduces
  `java.base/java.io` private-field ownership and module-open requirements.
- `ObjectStreamSerializer` must use its stream-specific object-instantiator path and must not use
  `TypeResolver.getObjectInstantiator`. ObjectStream reconstruction creates the object before stream
  fields are read. This path must not invoke Serializable class constructors, including no-arg
  constructors; the JDK8-24 ReflectionFactory template constructor must be the first
  non-Serializable superclass no-arg constructor, and private no-arg constructors must be rejected
  instead of made accessible. Package-private no-arg constructors are valid only for same-package
  Serializable subclasses.
- Runtime object-instantiator caches are `SharedRegistry` state backed by `ConcurrentHashMap`.
  Keep ObjectStream-specific instantiators separate from normal object instantiators.
- Generated Fory object serializers must initialize object-instantiator fields through
  `TypeResolver.getObjectInstantiator(Class)`, so generated code respects runtime-scoped object
  instantiators.
  Do not emit generated calls to
  `ObjectInstantiators.getObjectInstantiator(TypeResolver, Class)` or bypass the runtime-scoped owner; format
  builders without a Fory runtime context may use the base `ObjectInstantiators.getObjectInstantiator(Class)`
  construction default.
- Compatible scalar, list-array, and binary/uint8 adapters are immediate-field-only. For matched
  nested field metadata, classify schema pairs before generated dispatch and require exact
  nullable/ref/generic/type shape, except for user-defined type-family normalization.
- Root codegen and builder classes that still need Unsafe on JDK8-24 must route symbolic Unsafe
  access through a helper with a Java 25 replacement. Do not leave `_JDKAccess.unsafe()` or
  `sun.misc.Unsafe` references in JDK25-visible classes outside matching `java25` replacements.
- `ObjectCodecBuilder` primitive generated-code paths must keep one primitive field traversal and
  dispatch owner. Select direct Unsafe `(base, absoluteAddress)` versus indexed `MemoryBuffer`
  `(buffer, intIndex)` access at codegen setup time; do not duplicate the primitive switch or emit a
  per-field JDK-version branch. JDK25+ generated serializers must not reference `sun.misc.Unsafe`.
- `_UnsafeUtils` is the JDK8-24 Unsafe owner only. It must have a Java25 replacement with no
  `sun.misc.Unsafe` fields, methods, constructors, imports, or descriptors, and MR-JAR plus
  benchmark checks must require that replacement so JDK25+ never links `jdk.unsupported` through
  this utility.
- Multi-release replacement is class-file exact. Replacing `Foo.class` does not replace
  `Foo$Bar.class`; root nested classes that contain Unsafe descriptors must either be eliminated,
  moved under an exact replaceable owner, or have their direct Unsafe operations moved to the
  already-overlaid top-level class. JDK25 package checks must enumerate packaged Fory class files
  and inspect forbidden constants so nested leaks are caught without rejecting shaded third-party
  dependencies.
- Java 25 multi-release classes never run on Android. Do not keep `AndroidSupport.IS_ANDROID`
  branches or imports under `src/main/java25`; Android compatibility belongs to the root sources.
- String zero-copy construction is serializer-owned behavior. Keep private `String` constructor
  lambda factories in `StringSerializer`, keep `PlatformStringUtils`
  focused on field and array access, keep serialization hook discovery in serializer-owned code,
  and keep `_JDKAccess` limited to JDK lookup, module, function factory, and access-flag
  primitives.
- JDK25+ serialization hook access must use the required trusted lookup from
  `java.base/java.lang.invoke=org.apache.fory.core`. Keep `sun.reflect.ReflectionFactory` as a
  JDK8-24 hook optimization only, and do not add per-type reflective escapes for hook invocation.
- JDK25+ `PlatformStringUtils` getter methods sit behind `StringSerializer` static-final access
  gates. Do not add per-call access checks in those getters; missing module opens should fail at
  trusted-lookup initialization or cold setup, not inside string hot paths.
- `FieldAccessor` owns field-accessor dispatch. `RecordFieldAccessors` owns record field access,
  and `InstanceFieldAccessors` owns non-record instance field access. Do not reintroduce a
  `FieldAccessorFactory` layer. `InstanceFieldAccessors` is public only so generated serializers
  can name its concrete nested accessor type; treat it as internal owner code, not user API.
- Android non-record reflection field access belongs inside the root `InstanceFieldAccessors`
  owner. Do not keep a standalone `ReflectionFieldAccessor`; Java25 never needs that path, and
  record reflection fallback remains record-owned in `RecordFieldAccessors`. Keep `sun.misc.Unsafe`
  fields and descriptors below the JVM-only nested instance accessor so Android can load the owner
  and take the reflection branch.
- JDK25+ `InstanceFieldAccessors` owns instance field access only. Do not add static-field handling
  or per-write reflection fallback there, and do not expose public `FieldAccessor` static-field
  factories. Static special cases such as Scala `MODULE$` belong to the owning serializer and should
  use a cached `_JDKAccess._trustedLookup(...).findStaticGetter(...)` handle at that call site.
- JDK25+ final-instance field mutation should use the same trusted-lookup `VarHandle` owner path as
  non-final field mutation. Do not add a final-field-only `MethodHandle` setter path or ordinary
  reflective `Field.set*` fallback.
- JDK25+ `InstanceFieldAccessors` should use one final trusted-lookup `VarHandle` instance accessor
  with dense access-kind switches, not public primitive/object accessor classes or a JDK25
  `GeneratedAccessor` or hidden-class accessor generation. Do not wrap `VarHandle.get/set` in
  hot-path try/catch blocks and do not call `FieldAccessor.checkObj`; VarHandle validates null and
  receiver type itself. Root Unsafe offset access may keep a debug-only `assert` receiver check
  because Unsafe does not validate the target object; do not add production receiver checks.
- JDK25+ generated serializers should store field accessors as concrete
  `InstanceFieldAccessors.InstanceAccessor` static final fields, initialized once through
  `FieldAccessor.createAccessor(...)` and a static-init cast. This keeps platform dispatch out of
  generated read/write hot paths and avoids `FieldAccessor` virtual dispatch on final/private field
  get/set calls.
- `DefineClass#defineHiddenNestmate` belongs in the root `DefineClass` owner. Do not add a Java25
  overlay only to call `Lookup#defineHiddenClass` directly, and do not move it to `java9` because
  `Lookup#defineClass` defines normal package classes, not hidden nestmates. Root code must avoid
  direct `Lookup.ClassOption` linkage and cache the method-handle/option-array setup off the hot
  path.
- Hidden generated serializers are Java25+ only. Do not broaden serializer hidden-class definition
  to Java15-24, because those runtimes still use the unsafe-backed field/object path. Keep
  `AccessorHelper` as the source-generated same-package helper; do not turn it into a bytecode
  hidden-field owner unless a separate Java25-only design explicitly requires that.
- JDK25 hidden generated serializers must not emit private split helper methods. Janino lowers
  private instance helpers to static bridge methods whose receiver parameter uses the original
  binary class name, which fails hidden-class verification. Use non-private final split helpers on
  that path.
- Runtime codegen must not emit Janino source that names bootstrap JDK implementation classes in
  concealed or non-source-public packages. Generated source in the unnamed module cannot access
  those classes even when Fory's trusted field-access path can read/write their fields; use
  `ObjectSerializer` for those object-copy/field paths.
- Keep JDK26 `--illegal-final-field-mutation=deny` scoped to the JPMS runtime tests that prove
  Fory's field-access path. Do not put it in global Maven `JDK_JAVA_OPTIONS`, because build tools
  such as Lombok may perform their own reflective final-field access during compilation.
- JDK25+ collection serializers can restore JDK private/final collection fields through the
  required trusted lookup. Do not add JDK25-only unsupported branches for `Collections.newSetFromMap`
  or similar JDK wrappers when the normal JDK field-access owner can preserve the existing payload.
- Keep all private `Collections$SetFromMap` field access in one `SetFromMapAccess` owner helper.
  The non-codegen payload branch is the backing-map payload path used to preserve backing-map
  object/reference semantics, not a legacy fallback.
- Do not add a new built-in class to the auto-assigned `registerInternalSerializer` sequence unless
  it has an explicit stable internal type id. Use `ClassResolver#getSerializerClass` for native-only
  serializer selection when the class was not previously internally registered; shifting built-in
  type ids breaks Java native-mode binary compatibility.
- When a native-only serializer is selected for a class that was already routed through a different
  serializer family, do not shift built-in type ids. Preserve the existing payload only if it was
  semantically valid; otherwise use the owning semantic serializer path and cover the behavior with
  focused tests.
- When `ClassResolver#getSerializerClass` selects a serializer that is not internally registered,
  add that serializer class to `GraalvmSupport`'s default serializer metadata so native-image can
  construct it without shifting Java native-mode type ids.
- Do not add or restore constructor-binding APIs such as `@ForyConstructor` or
  `BaseFory.registerConstructor(...)`. Java parameter names, `-parameters`, and
  `@ConstructorProperties` are not a Fory object-creation contract. Runtime serializers for
  ordinary classes must create an empty instance through `TypeResolver.getObjectInstantiator(Class)` and
  set fields; records and source-generated Kotlin serializers are the constructor-owned paths.
- Source-generated constructor serializers must own their constructor metadata at generation time
  and call constructors directly. They must not depend on runtime `ObjectInstantiator` constructor-field
  metadata or varargs constructor calls.
- Java annotation-processor static serializers must support the same Java class surface as normal
  Fory object serialization when compatible-read static generation needs it. Ordinary classes that
  cannot be assigned directly should allocate through generated-subclass-owned
  `ObjectInstantiator` state and write private, inaccessible, or final fields through
  generated-subclass-owned cached `FieldAccessor`s. Do not add constructor-binding APIs, per-read
  reflective lookup, descriptor-based varargs constructor calls, or shared-parent argument buffers.
  Records and Kotlin KSP primary-constructor serializers remain constructor-owned paths.
- Generated JVM copy code may direct-copy immutable scalar values, but Java `Collection`/`Map`
  subclasses must be copied through `CopyContext.copyObject(...)` so collection/map serializers own
  concrete type, comparator, wrapper, and reference behavior.
- When a `Throwable` is created without running `Throwable` constructors, restore the private
  `cause` and `suppressedExceptions` sentinels directly before exposing the object. Do not call
  `initCause` or `addSuppressed` on a constructor-bypassed `Throwable` whose sentinels are still
  absent.
- For JDK25+ CI, do not run core runtime tests from raw Maven reactor `target/classes`. Those
  classes bypass `META-INF/versions/25` and exercise the JDK8-24 root implementation. Classpath
  Surefire tests must use a test-only classes directory overlaid with Java16 and Java25 replacement
  classes and without `module-info.class`, so the run stays unnamed while exercising the
  zero-Unsafe classes. JPMS tests still own named-module coverage where `org.apache.fory.core` is
  the real access target.
- After shading Janino into `fory-core`, refresh the JPMS module descriptor package table from the
  final jar before install. Otherwise named-module codegen cannot load concealed shaded Janino
  packages even though the classes are present in the jar.
- GraalVM native-image tests that run Fory on the classpath may target
  `java.base/java.lang.invoke=ALL-UNNAMED`; that is the classpath launch shape, not a named-module
  proof. Keep named-module zero-Unsafe verification in JPMS tests unless a native-image path itself
  runs Fory as `org.apache.fory.core`.

## Key Modules

- `fory-core`: core object-graph serialization runtime
- `fory-format`: row-format encoding and decoding
- `fory-extensions`: protobuf serializers and other extensions
- `fory-simd`: SIMD-accelerated serializers and utilities
- `fory-test-core`: shared Java test utilities
- `testsuite`: issue-driven and complex regression coverage
- `benchmark`: JMH benchmark suite

## Commands

```bash
# Clean the build
mvn -T16 clean

# Build
mvn -T16 package

# Install
mvn -T16 install -DskipTests

# Format check
mvn -T16 spotless:check

# Apply formatting
mvn -T16 spotless:apply

# Spotless with JDK 21 when the current runtime is older
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
mvn -T16 spotless:check

# Style check
mvn -T16 checkstyle:check

# Run tests
mvn -T16 test

# Run a specific test
mvn -T16 test -Dtest=org.apache.fory.TestClass#testMethod
```

## Debugging And IDE Notes

- Set `FORY_CODE_DIR` to dump generated code.
- Set `ENABLE_FORY_GENERATED_CLASS_UNIQUE_ID=false` when you need stable generated class names.
- When debugging Java tests or runtime behavior, set `FORY_LOG_LEVEL=INFO` unless a narrower
  level is required.
- In IntelliJ IDEA, use the same JDK and module flags as the Maven profile you are debugging.
