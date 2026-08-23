# Apache Fory JSON for Scala

`fory-json-scala` adds direct Scala type support to Fory JSON without converting values through
Java collections or a JSON tree. It supports Scala 2.13 and Scala 3 on the ordinary JVM and GraalVM
Native Image.

Add the Scala artifact that matches the application Scala version:

```sbt
libraryDependencies += "org.apache.fory" %% "fory-json-scala" % "1.6.1"
```

Create and reuse one Scala-aware Fory JSON instance:

```scala
import org.apache.fory.json.scala.ForyJsonScala

case class Person(name: String, age: Int = 18, aliases: List[String] = Nil)

val json = ForyJsonScala.builder().build()
val text = json.toJson(Person("Ada"))
val value = json.fromJson(text, classOf[Person])
```

The module supports case classes and their constructor defaults, mutable body properties, Scala
collections and maps, `Option`, `Either`, tuples, ranges, durations, numeric wrappers, Scala 2
`Enumeration`, and Scala 3 enums. Parameterized Scala types should use `TypeRef`; use `ScalaTypeRef`
when a Scala value type would otherwise erase to `Object`:

```scala
import org.apache.fory.json.scala.ScalaTypeRef

val rangeType = ScalaTypeRef[scala.collection.immutable.NumericRange[Int]]
val range = json.fromJson("[1,3,5]", rangeType)
```

See the [Scala JSON guide](../../docs/json/scala.md) for the supported type table, annotations,
closed enum derivation, unsupported runtime-state types, and GraalVM behavior.

Licensed under the [Apache License 2.0](../../LICENSE).
