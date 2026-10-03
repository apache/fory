# Kotlin

Load this file when changing `kotlin/` or compiler code that generates Kotlin source.

## Rules

- Missing JSON constructor parameters use explicit Kotlin defaults first; otherwise non-null
  numeric and Boolean parameters use zero and false, and nullable parameters use null. Other
  non-null reference parameters remain required. Under `onNullRead(SET)`, explicit null never
  requests a default; `SKIP` leaves the occurrence absent, including its presence state.
  With `failOnMissingRequiredProperties(true)`, ordinary constructor parameters without declared
  defaults must appear, including nullable and scalar parameters. Preserve declared defaults and
  existing container recovery; do not invent defaults for non-null containers.
- Fory JSON Kotlin property inclusion follows the configured global or property-level rule,
  independently of constructor defaults and deferred initializers. Do not override or reject
  `NON_NULL` or `NON_EMPTY` to guarantee round trips. Missing-property defaults and nullability
  remain reader-owned.
- `NON_DEFAULT` requires explicit property or class authorization; global use is invalid. Kotlin
  properties without defaults, including required parameters and lateinit properties, remain written
  under either authorization form. A defaulted property still requires a legal reference baseline.
  Kotlin defaults use one reference object per model metadata only when the selected constructor has no
  required parameters. Never fabricate required arguments, change creator selection, or construct
  a comparison object on each write. Authorization covers constructor and initializer execution;
  the caller owns stable defaults and consistent missing-field recovery, including dependencies.
  Metadata does not prove default expression purity. Do not add bytecode analysis or plugins to
  infer it, and never share reference defaults with deserialization results.
- Run Kotlin Maven commands from within `kotlin/`.
- Kotlin serializers build on the Java implementation. If Java changed and the updated Java artifacts are not installed yet, run `cd ../java && mvn -T16 install -DskipTests` first.
- KSP `@ForyStruct` serializers that use a primary constructor map constructor parameters to
  same-named source properties at generation time and call the constructor directly. Do not restore
  `@ForyConstructor`, runtime constructor registration, or Kotlin `javaParameters` dependencies;
  mutable no-argument structs should use `var` properties with `@ForyField`.
- Preserve serializer-family selection for Kotlin standard-library types already registered by
  Fory. Do not auto-install a new serializer for an existing type-registered Kotlin class unless the
  wire format matches the previous serializer family and old-payload/new-runtime compatibility is
  tested.
- When adding Kotlin gRPC service companions, emit Kotlin source only. Reuse the generated schema
  module's `ThreadSafeFory` and KSP-generated schema serializers, and keep grpc-java/grpc-kotlin
  dependencies application-owned instead of adding them as hard `fory-kotlin` dependencies.

## Commands

```bash
# Build
mvn clean package

# Run tests
mvn test
```
