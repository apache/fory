# Fory Definition Language (FDL) Compiler

The FDL compiler generates cross-language serialization code from schema definitions. It enables type-safe cross-language data exchange by generating native data structures with Fory serialization support for multiple programming languages.

## Features

- **Multi-language code generation**: Java, Python, Go, Rust, C++, C#, JavaScript, Swift, Dart, Scala, and Kotlin
- **Rich type system**: Primitives, enums, messages, lists, dense arrays, maps
- **Cross-language serialization**: Generated code works seamlessly with Apache Fory
- **Type ID and namespace support**: Both numeric IDs and name-based type registration
- **Field modifiers**: Optional fields, reference tracking, list fields, scalar encoding modifiers
- **File imports**: Modular schemas with import support
- **gRPC service generation**: Native gRPC stubs and service bases for Java, Python, Go, Rust, C++, C#, JavaScript, Dart, Kotlin, and Scala

## Documentation

For comprehensive documentation, see the [FDL Schema Guide](../docs/compiler/index.md):

- [FDL Syntax Reference](../docs/compiler/schema-idl.md) - Complete language syntax and grammar
- [Type System](../docs/compiler/schema-idl.md#type-system) - Primitive types, collections, and language mappings
- [Compiler Guide](../docs/compiler/compiler-guide.md) - CLI options and build integration
- [Generated Code](../docs/compiler/generated-code.md) - Output format for each target language
- [Protocol Buffers vs FDL](../docs/compiler/protobuf-idl.md) - Feature comparison and porting guide

## Installation

```bash
cd compiler
pip install -e .
```

## Quick Start

### 1. Define Your Schema

Create a `.fdl` file:

```protobuf
package demo;

enum Color [id=101] {
    GREEN = 0;
    RED = 1;
    BLUE = 2;
}

message Dog [id=102] {
    optional string name = 1;
    int32 age = 2;
}

message Cat [id=103] {
    ref Dog friend = 1;
    optional string name = 2;
    list<string> tags = 3;
    map<string, int32> scores = 4;
    int32 lives = 5;
}
```

### 2. Compile

```bash
# Generate for all languages
foryc schema.fdl --output ./generated

# Generate for specific languages
foryc schema.fdl --lang java,python,csharp,javascript,scala --output ./generated

# Language-specific output directories (protoc-style)
foryc schema.fdl --java_out=./src/main/java --python_out=./python/src --csharp_out=./csharp/src/Generated --javascript_out=./javascript --scala_out=./scala/src/main/scala

# Combine with other options
foryc schema.fdl --java_out=./gen --go_out=./gen/go --csharp_out=./gen/csharp --javascript_out=./gen/js --scala_out=./gen/scala -I ./proto

# Also generate gRPC service stubs
foryc schema.fdl --lang java,python,go --grpc --output ./generated
```

### 3. Use Generated Code

**Java:**

```java
import demo.*;
import org.apache.fory.Fory;

Fory fory = Fory.builder()
    .withXlang(true)
    .withRefTracking(true)
    .withModule(DemoForyModule.INSTANCE)
    .build();

Cat cat = new Cat();
cat.setName("Whiskers");
cat.setLives(9);
byte[] bytes = fory.serialize(cat);
```

**Python:**

```python
import pyfory
from demo import Cat, register_demo_types

fory = pyfory.Fory(xlang=True)
register_demo_types(fory)

cat = Cat(name="Whiskers", lives=9)
data = fory.serialize(cat)
```

## FDL Syntax

### Package Declaration

```protobuf
package com.example.models;
```

### Imports

Import types from other FDL files:

```protobuf
import "common/types.fdl";
import "models/address.fdl";
```

Imports are resolved relative to the importing file. All types from imported files become available for use in the current file.

**Example:**

```protobuf
// common.fdl
package common;

message Address [id=100] {
    string street = 1;
    string city = 2;
}
```

```protobuf
// user.fdl
package user;
import "common.fdl";

message User [id=101] {
    string name = 1;
    Address address = 2;  // Uses imported type
}
```

### Enum Definition

```protobuf
enum Status [id=100] {
    PENDING = 0;
    ACTIVE = 1;
    INACTIVE = 2;
}
```

### Message Definition

```protobuf
message User [id=101] {
    string name = 1;
    int32 age = 2;
    optional string email = 3;
}
```

### Type Options

Types can have options specified in brackets after the name:

```protobuf
message User [id=101] { ... }              // Registered with type ID 101
message User [id=101, deprecated=true] { ... }  // Multiple options
```

Types without `[id=...]` use name-based registration:

```protobuf
message Config { ... }  // Registered as "package.Config"
```

### Primitive Types

| FDL Type    | Java        | Python              | Go          | Rust              | C++                    | C#               | JavaScript         |
| ----------- | ----------- | ------------------- | ----------- | ----------------- | ---------------------- | ---------------- | ------------------ |
| `bool`      | `boolean`   | `bool`              | `bool`      | `bool`            | `bool`                 | `bool`           | `boolean`          |
| `int8`      | `byte`      | `pyfory.Int8`       | `int8`      | `i8`              | `int8_t`               | `sbyte`          | `number`           |
| `int16`     | `short`     | `pyfory.Int16`      | `int16`     | `i16`             | `int16_t`              | `short`          | `number`           |
| `int32`     | `int`       | `pyfory.Int32`      | `int32`     | `i32`             | `int32_t`              | `int`            | `number`           |
| `int64`     | `long`      | `pyfory.Int64`      | `int64`     | `i64`             | `int64_t`              | `long`           | `bigint \| number` |
| `float16`   | `Float16`   | `pyfory.Float16`    | `float16`   | `Float16`         | `fory::float16_t`      | `Half`           | `number`           |
| `bfloat16`  | `BFloat16`  | `pyfory.BFloat16`   | `bfloat16`  | `BFloat16`        | `fory::bfloat16_t`     | `BFloat16`       | `number`           |
| `float32`   | `float`     | `pyfory.Float32`    | `float32`   | `f32`             | `float`                | `float`          | `number`           |
| `float64`   | `double`    | `pyfory.Float64`    | `float64`   | `f64`             | `double`               | `double`         | `number`           |
| `string`    | `String`    | `str`               | `string`    | `String`          | `std::string`          | `string`         | `string`           |
| `bytes`     | `byte[]`    | `bytes`             | `[]byte`    | `Vec<u8>`         | `std::vector<uint8_t>` | `byte[]`         | `Uint8Array`       |
| `date`      | `LocalDate` | `datetime.date`     | `time.Time` | `fory::Date`      | `fory::Date`           | `DateOnly`       | `Date`             |
| `timestamp` | `Instant`   | `datetime.datetime` | `time.Time` | `fory::Timestamp` | `fory::Timestamp`      | `DateTimeOffset` | `Date`             |

### Collection Types

```protobuf
list<string> tags = 1;               // List<String>
array<int32> dense_numbers = 2;      // Packed dense int32 array
map<string, fixed int32> scores = 3; // Map<String, fixed-width Integer>
```

### Field Modifiers

- **`optional`**: Field can be null/None
- **`ref`**: Enable reference tracking for shared/circular references
- **`list<T>`**: Ordered collection schema (alias: `repeated T`)
- **`array<T>`**: Dense numeric/vector schema

```protobuf
message Example {
    optional string nullable_field = 1;
    ref OtherMessage shared_ref = 2;
    list<int32> numbers = 3;
    list<fixed int32> offsets = 4;
    array<float32> embedding = 5;
}
```

### Service Definition

Define gRPC services alongside message types in the same FDL file:

```protobuf
package demo.greeter;

message HelloRequest {
    string name = 1;
}

message HelloReply {
    string reply = 1;
}

service Greeter {
    rpc SayHello (HelloRequest) returns (HelloReply);
    rpc StreamReplies (HelloRequest) returns (stream HelloReply);
    rpc CollectRequests (stream HelloRequest) returns (HelloReply);
    rpc Chat (stream HelloRequest) returns (stream HelloReply);
}
```

Each `rpc` declaration supports four streaming modes:

| Mode             | Syntax                                         |
| ---------------- | ---------------------------------------------- |
| Unary            | `rpc Method (Req) returns (Res)`               |
| Server streaming | `rpc Method (Req) returns (stream Res)`        |
| Client streaming | `rpc Method (stream Req) returns (Res)`        |
| Bidirectional    | `rpc Method (stream Req) returns (stream Res)` |

### Fory Options

FDL uses plain option keys without a `(fory)` prefix:

**File-level options:**

```protobuf
option use_record_for_java_message = true;
option polymorphism = true;
option enable_auto_type_id = true;
```

`enable_auto_type_id` defaults to `true`. Set it to `false` to keep name-based registration
for types that omit explicit IDs.

**Message/Enum options:**

```protobuf
message MyMessage [id=100] {
    option evolving = false;
    option use_record_for_java = true;
    string name = 1;
}

enum Status [id=101] {
    UNKNOWN = 0;
    ACTIVE = 1;
}
```

**Field options:**

```protobuf
message Example {
    ref MyType friend = 1;
    string nickname = 2 [nullable=true];
    ref MyType data = 3 [nullable=true];
    ref(weak=true) MyType parent = 4;
}
```

## Architecture

```
fory_compiler/
├── __init__.py           # Package exports
├── __main__.py           # Module entry point
├── cli.py                # Command-line interface
├── frontend/
│   └── fdl/
│       ├── __init__.py
│       ├── lexer.py      # Hand-written tokenizer
│       └── parser.py     # Recursive descent parser
├── ir/
│   ├── __init__.py
│   ├── ast.py            # Canonical Fory IDL AST (Schema, Message, Enum, Service, RpcMethod)
│   ├── validator.py      # Schema validation
│   └── emitter.py        # Optional FDL emitter
└── generators/
    ├── base.py           # Base generator class and GeneratorOptions
    ├── java.py           # Java POJO generator
    ├── python.py         # Python dataclass generator
    ├── go.py             # Go struct generator
    ├── rust.py           # Rust struct generator
    ├── cpp.py            # C++ struct generator
    ├── csharp.py         # C# class generator
    ├── javascript.py     # JavaScript interface generator
    └── services/
        ├── base.py       # StreamingMode enum and shared helpers
        ├── java.py       # Java gRPC stub generator (grpc-java style)
        ├── python.py     # Python gRPC companion module (grpcio style)
        ├── go.py         # Go gRPC stub generator (google.golang.org/grpc)
        ├── rust.py       # Rust gRPC service module (tonic style)
        ├── cpp.py        # C++ synchronous gRPC service companions
        ├── csharp.py     # C# gRPC service companion (Grpc.Core style)
        ├── javascript.py # JavaScript Node.js and gRPC-Web client generators
        ├── dart.py       # Dart gRPC service companion
        ├── kotlin.py     # Kotlin coroutine gRPC service companion
        └── scala.py      # Scala gRPC service companion
```

### FDL Frontend

The FDL frontend is a hand-written lexer/parser that produces the Fory IDL AST:

- **Lexer** (`frontend/fdl/lexer.py`): Tokenizes FDL source into tokens
- **Parser** (`frontend/fdl/parser.py`): Builds the AST from the token stream
- **AST** (`ir/ast.py`): Canonical node types - `Schema`, `Message`, `Enum`, `Field`, `FieldType`

### Generators

Each generator extends `BaseGenerator` and implements:

- `generate()`: Returns list of `GeneratedFile` objects for type definitions
- `generate_type()`: Converts FDL types to target language types
- `generate_services()`: Returns gRPC service companion files when `--grpc` is set
- Language-specific registration helpers or modules

Service generators live in `generators/services/` as mixins and are combined with the
corresponding type generator via multiple inheritance in each language generator class.

## Generated Output

### Java

Generates POJOs with:

- Private fields with getters/setters
- `@Nullable` annotations for nullable fields and `@Ref` annotations for ref fields
- Schema module class

```java
public class Cat {
    @Ref
    private Dog friend;

    @Nullable
    private String name;

    private List<String> tags;
    // ...
}
```

### Python

Generates dataclasses with:

- Type hints
- Default values
- Registration function

```python
@dataclass
class Cat:
    friend: Optional[Dog] = None
    name: Optional[str] = None
    tags: List[str] = None
```

### Go

Generates structs with:

- Fory struct tags
- Pointer types for nullable fields
- Registration function with error handling

```go
type Cat struct {
    Friend *Dog              `fory:"ref"`
    Name   *string           `fory:"nullable"`
    Tags   []string
}
```

### Rust

Generates structs with:

- `#[derive(ForyStruct)]`, `#[derive(ForyEnum)]`, and `#[derive(ForyUnion)]` macros
- `#[fory(...)]` field attributes
- a registration helper for name-based registration

```rust
#[derive(ForyStruct, Debug, Clone, PartialEq, Default)]
pub struct Cat {
    pub friend: Arc<Dog>,
    #[fory(nullable = true)]
    pub name: Option<String>,
    pub tags: Vec<String>,
}
```

### C++

Generates structs with:

- `FORY_STRUCT` macro for serialization
- `std::optional` for nullable fields
- `std::shared_ptr` for ref fields

```cpp
struct Cat {
    std::shared_ptr<Dog> friend;
    std::optional<std::string> name;
    std::vector<std::string> tags;
    int32_t scores;
    int32_t lives;
    FORY_STRUCT(Cat, friend, name, tags, scores, lives);
};
```

### C\#

Generates classes with:

- `[ForyStruct]`, `[ForyEnum]`, and `[ForyUnion]` model attributes
- Auto-properties for schema fields
- Registration helper class and `ToBytes`/`FromBytes` helpers

```csharp
[ForyStruct]
public sealed partial class Cat
{
    public Dog? Friend { get; set; }
    public string Name { get; set; } = string.Empty;
    public List<string> Tags { get; set; } = new();
}
```

For full C# IDL verification (including root cross-package imports and file-based
roundtrip paths), run:

```bash
cd integration_tests/idl_tests
./run_csharp_tests.sh
```

### JavaScript

Generates interfaces with:

- `export interface` declarations for messages
- `export enum` declarations for enums
- Discriminated unions with case enums
- Registration helper function

```javascript
export interface Cat {
  friend?: Dog | null;
  name?: string | null;
  tags: string[];
  scores: Map<string, number>;
  lives: number;
}
```

## gRPC Service Generation

Pass `--grpc` to generate gRPC service stubs alongside type definitions for all selected
languages that support service generation (Java, Python, Go, Rust, C#, JavaScript, Dart,
Kotlin, and Scala). Stubs use Fory serialization as the on-wire codec.

```bash
# Generate type definitions and gRPC stubs
foryc examples/service.fdl --lang java,python,go --grpc --output ./generated

# JavaScript gRPC-Web client (requires --grpc-web, implies JavaScript output)
foryc examples/service.fdl --javascript_out=./gen/js --grpc-web

# Python async mode (default) or sync mode
foryc examples/service.fdl --python_out=./gen/python --grpc --grpc-python-mode sync
```

### Generated gRPC Output

For each language the compiler emits one gRPC companion file per schema file.
The following examples use the schema from `examples/service.fdl`:

```protobuf
package demo.greeter;

message HelloRequest { string name = 1; }
message HelloReply   { string reply = 1; }

service Greeter {
    rpc SayHello (HelloRequest) returns (HelloReply);
}
```

#### Java

Generates `<ServiceName>Grpc.java` with a grpc-java-style companion class:

- Method descriptors with double-checked-locking initialization
- `<ServiceName>ImplBase` abstract server base
- `<ServiceName>Stub` (async), `<ServiceName>BlockingStub`, and `<ServiceName>FutureStub` client stubs
- Factory methods `newStub`, `newBlockingStub`, and `newFutureStub`
- Fory-backed marshaller shared by all methods in the class

```java
// GreeterGrpc.java (demo/greeter/GreeterGrpc.java)
public final class GreeterGrpc {
    public static final String SERVICE_NAME = "demo.greeter.Greeter";

    public abstract static class GreeterImplBase implements io.grpc.BindableService {
        public void sayHello(HelloRequest request,
                io.grpc.stub.StreamObserver<HelloReply> responseObserver) {
            io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(
                getSayHelloMethod(), responseObserver);
        }
        @Override
        public final io.grpc.ServerServiceDefinition bindService() {
            return GreeterGrpc.bindService(this);
        }
    }

    public static final class GreeterStub
            extends io.grpc.stub.AbstractAsyncStub<GreeterStub> { ... }
    public static final class GreeterBlockingStub
            extends io.grpc.stub.AbstractBlockingStub<GreeterBlockingStub> { ... }
    public static final class GreeterFutureStub
            extends io.grpc.stub.AbstractFutureStub<GreeterFutureStub> { ... }
}
```

#### Python

Generates `<module>_grpc.py` with a grpcio-style companion module. The default API
mode is async (`grpc.aio`); pass `--grpc-python-mode sync` for the classic sync API:

- `<ServiceName>Stub` client class wired to the Fory serializer/deserializer pair
- `<ServiceName>Servicer` server base with `UNIMPLEMENTED` stubs
- Per-service registration helper and a top-level `add_servicer(servicer, server)` dispatcher

```python
# demo_greeter_grpc.py
class GreeterStub(object):
    """Client stub for Greeter."""
    def __init__(self, channel):
        self.say_hello = channel.unary_unary(
            "/demo.greeter.Greeter/SayHello",
            request_serializer=_serialize,
            response_deserializer=_deserialize,
        )

class GreeterServicer(object):
    """AsyncIO base servicer for Greeter."""
    async def say_hello(self, request, context):
        await context.abort(grpc.StatusCode.UNIMPLEMENTED, "Method not implemented!")

def add_servicer(servicer, server): ...
```

#### Go

Generates `<file>_grpc.go` with a google.golang.org/grpc-compatible stub file:

- `CodecV2` implementing `grpc/encoding.CodecV2` using the Fory thread-safe runtime
- `<ServiceName>Client` interface and `New<ServiceName>Client` constructor
- `<ServiceName>Server` interface and `Unimplemented<ServiceName>Server` struct
- Per-streaming-mode send/receive stream types
- `Register<ServiceName>Server` and a `ServiceDesc` variable

```go
// greeter_grpc.go
type GreeterClient interface {
    SayHello(ctx context.Context, in *HelloRequest,
        opts ...grpc.CallOption) (*HelloReply, error)
}

func NewGreeterClient(cc grpc.ClientConnInterface) GreeterClient { ... }

type GreeterServer interface {
    SayHello(context.Context, *HelloRequest) (*HelloReply, error)
    mustEmbedUnimplementedGreeterServer()
}

func RegisterGreeterServer(s grpc.ServiceRegistrar, srv GreeterServer) { ... }
```

#### Rust

Generates two files: `<module>_api.rs` (service trait definitions) and
`<module>_grpc.rs` (tonic-compatible client/server modules):

- A service trait per service name
- `<service_name>_client` and `<service_name>_server` submodules compatible with tonic
- Fory codec registered via the `<SERVICE>_SERVICE_NAME` constant

```rust
// greeter_grpc.rs
pub mod greeter_client {
    pub struct GreeterClient<T> { inner: tonic::client::Grpc<T> }
    impl<T> GreeterClient<T> {
        pub async fn say_hello(&mut self, request: impl tonic::IntoRequest<HelloRequest>)
            -> std::result::Result<tonic::Response<HelloReply>, tonic::Status> { ... }
    }
}

pub mod greeter_server {
    pub trait Greeter: std::marker::Send + std::marker::Sync + 'static {
        async fn say_hello(&self, request: tonic::Request<HelloRequest>)
            -> std::result::Result<tonic::Response<HelloReply>, tonic::Status>;
    }
}
```

#### C\#

Generates `<ServiceName>Grpc.cs` with a Grpc.Core-style partial class:

- Static Fory marshallers for each distinct request/response type pair
- `Method<TReq, TRes>` descriptors for each RPC
- `<ServiceName>Base` abstract server base class
- `<ServiceName>Client` client class
- `BindService` helper for server-side registration

```csharp
// GreeterGrpc.cs
public static partial class Greeter
{
    static readonly string __ServiceName = "demo.greeter.Greeter";

    public abstract class GreeterBase
    {
        public virtual Task<HelloReply> SayHello(
            HelloRequest request, grpc::ServerCallContext context)
            => throw new grpc::RpcException(new grpc::Status(
                grpc::StatusCode.Unimplemented, ""));
    }

    public class GreeterClient : grpc::ClientBase<GreeterClient>
    {
        public virtual HelloReply SayHello(
            HelloRequest request, grpc::CallOptions options = default) { ... }
    }
}
```

#### JavaScript

Generates `<module>_grpc.js` (Node.js, `--grpc`) and/or `<module>_grpc_web.js`
(browser, `--grpc-web`) TypeScript/JavaScript companion modules:

- `<ServiceName>Client` class extending `grpc.Client` (Node) or a gRPC-Web base (browser)
- Per-method call wrappers using the Fory serializer/deserializer pair
- A `<ServiceName>Service` descriptor object for server-side registration (Node)

```typescript
// greeter_grpc.js (Node)
export class GreeterClient extends grpc.Client {
  sayHello(argument, metadata, options, callback) { ... }
}
export const GreeterService = {
  sayHello: { path: "/demo.greeter.Greeter/SayHello", ... },
};
```

#### Dart

Generates `<file>_grpc.dart` with a dart-grpc-compatible companion:

- `<ServiceName>Client` class extending `grpc.Client`
- `<ServiceName>ServiceBase` abstract server base class
- Fory codec passed as the `serialize`/`deserialize` pair on each `ClientMethod`

#### Kotlin

Generates `<ServiceName>GrpcKt.kt` with grpc-kotlin coroutine companions:

- `<ServiceName>CoroutineImplBase` abstract server base using suspend functions
- `<ServiceName>CoroutineStub` coroutine client stub
- Fory serialization used as the gRPC marshaller

#### Scala

Generates `<ServiceName>GrpcScala.scala` with ZIO/Monix-friendly stubs:

- `<ServiceName>Grpc` object with a `bindService` method for server registration
- `<ServiceName>Stub` client class
- Fory codec registered as the channel marshaller

## CLI Reference

```
foryc [OPTIONS] FILES...

Arguments:
  FILES                     FDL files to compile

Options:
  --lang TEXT               Target languages (java,python,cpp,rust,go,csharp,
                            javascript,swift,dart,scala,kotlin or "all")
                            Default: all
  --output, -o PATH         Output directory
                            Default: ./generated
  --java_out DST_DIR        Generate Java code in DST_DIR
  --python_out DST_DIR      Generate Python code in DST_DIR
  --go_out DST_DIR          Generate Go code in DST_DIR
  --rust_out DST_DIR        Generate Rust code in DST_DIR
  --cpp_out DST_DIR         Generate C++ code in DST_DIR
  --csharp_out DST_DIR      Generate C# code in DST_DIR
  --javascript_out DST_DIR  Generate JavaScript code in DST_DIR
  --swift_out DST_DIR       Generate Swift code in DST_DIR
  --dart_out DST_DIR        Generate Dart code in DST_DIR
  --scala_out DST_DIR       Generate Scala 3 code in DST_DIR
  --kotlin_out DST_DIR      Generate Kotlin code in DST_DIR
  -I PATH                   Add a directory to the import search path
  --grpc                    Generate gRPC service stubs alongside type definitions
  --grpc-web                Generate JavaScript gRPC-Web client code
  --grpc-python-mode MODE   Python gRPC API style: async (default) or sync
  --help                    Show help message
```

## Examples

See the `examples/` directory for sample FDL files and generated output.

```bash
# Compile the demo schema
foryc examples/demo.fdl --output examples/generated
```

## Development

```bash
# Install in development mode
pip install -e .

# Run the compiler
python -m fory_compiler compile examples/demo.fdl

# Or use the installed command
foryc examples/demo.fdl
```

## License

Apache License 2.0
