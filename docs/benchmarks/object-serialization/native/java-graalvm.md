# Java GraalVM Native Image Benchmark

These measurements cover Fory binary object serialization in GraalVM Native Image. Results depend on the recorded workloads and environment.

## Benchmark

Performance comparison between Fory and GraalVM JDK Serialization:

| Type   | Compression | Speed      | Size |
| ------ | ----------- | ---------- | ---- |
| Struct | Off         | 46x faster | 43%  |
| Struct | On          | 24x faster | 31%  |
| Pojo   | Off         | 12x faster | 56%  |
| Pojo   | On          | 12x faster | 48%  |

See [Benchmark.java](https://github.com/apache/fory/blob/main/integration_tests/graalvm_tests/src/main/java/org/apache/fory/graalvm/Benchmark.java) for benchmark code.

### Struct Benchmark

#### Class Fields

```java
public class Struct implements Serializable {
  public int f1;
  public long f2;
  public float f3;
  public double f4;
  public int f5;
  public long f6;
  public float f7;
  public double f8;
  public int f9;
  public long f10;
  public float f11;
  public double f12;
}
```

#### Benchmark Results

No compression:

```
Benchmark repeat number: 400000
Object type: class org.apache.fory.graalvm.Struct
Compress number: false
Fory size: 76.0
JDK size: 178.0
Fory serialization took mills: 49
JDK serialization took mills: 2254
Compare speed: Fory is 45.70x speed of JDK
Compare size: Fory is 0.43x size of JDK
```

Compress number:

```
Benchmark repeat number: 400000
Object type: class org.apache.fory.graalvm.Struct
Compress number: true
Fory size: 55.0
JDK size: 178.0
Fory serialization took mills: 130
JDK serialization took mills: 3161
Compare speed: Fory is 24.16x speed of JDK
Compare size: Fory is 0.31x size of JDK
```

### Pojo Benchmark

#### Class Fields

```java
public class Foo implements Serializable {
  int f1;
  String f2;
  List<String> f3;
  Map<String, Long> f4;
}
```

#### Benchmark Results

No compression:

```
Benchmark repeat number: 400000
Object type: class org.apache.fory.graalvm.Foo
Compress number: false
Fory size: 541.0
JDK size: 964.0
Fory serialization took mills: 1663
JDK serialization took mills: 16266
Compare speed: Fory is 12.19x speed of JDK
Compare size: Fory is 0.56x size of JDK
```

Compress number:

```
Benchmark repeat number: 400000
Object type: class org.apache.fory.graalvm.Foo
Compress number: true
Fory size: 459.0
JDK size: 964.0
Fory serialization took mills: 1289
JDK serialization took mills: 15069
Compare speed: Fory is 12.11x speed of JDK
Compare size: Fory is 0.48x size of JDK
```
