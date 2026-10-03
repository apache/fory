# Scala

Load this file when changing `scala/`.

## Rules

- Run Scala commands from within `scala/`.
- Scala serializers build on the Java implementation. If Java changed and the updated Java artifacts are not installed yet, run `cd ../java && mvn -T16 install -DskipTests` first.
- Missing Scala JSON case-class constructor parameters use their type defaults: zero for numbers,
  false for booleans, empty collections and arrays, None for Option, and null for other references.
  Explicit constructor defaults take precedence. Mutable defaults must be fresh for each object.
  Under `onNullRead(SET)`, explicit JSON null keeps its existing decoding semantics; `SKIP` leaves
  the occurrence absent, including its presence state. Preserve these rules in interpreted
  and generated readers. With `failOnMissingRequiredProperties(true)`, preserve declared defaults
  and Option/container defaults, but require ordinary constructor properties instead of applying
  scalar zero/false/null fallbacks. Explicit null and ignored properties keep their existing rules.
- Scala JSON data occurrences of Unit use BoxedUnit, including ScalaTypeRef roots, nested
  arguments, array components, and case-class properties. Normalize this at Scala type metadata
  construction in both compiler versions and reuse ScalaUnitCodec's JSON null representation.
  Do not special-case Option or change Java VoidCodec to accept Scala values. The Java Class
  writer overload continues to reject void; Scala callers use ScalaTypeRef[Unit].
- Recognize case classes independently of constructor support, including Scala 3 private copy
  methods. Unsupported constructors must fail in the Scala model instead of falling through to
  the ordinary object codec and silently dropping properties.
- Scala supports the JVM and GraalVM Native Image, not Android. Do not add Android-specific Scala
  sources, tests, resources, R8 metadata, compiler plugins, macros, dependencies, or compatibility
  design.
- JSON `NON_DEFAULT` requires explicit property or class authorization. Only declared compiler
  default methods are omission sources; reader type fallbacks must stay distinct. Evaluate dependent
  defaults with actual preceding object properties and reject missing dependencies. Do not infer
  purity from method availability or use a default reference object for parameter-dependent Scala
  defaults. Both class-level and field-level NON_DEFAULT retain properties without a supported
  compiler default method. Missing-field recovery remains reader-owned.

## Commands

```bash
# Compile
sbt compile

# Run tests
sbt test

# Repo-owned formatter pass for changed files
cd .. && ci/format.sh
```

The Scala module does not currently wire a `scalafmt` sbt command.
