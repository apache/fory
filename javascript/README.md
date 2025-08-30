# Apache Fory™ JavaScript

Javascript implementation for the Fory protocol.

The Cross-Language part of the protocol is not stable, so the output of this library may change in the future. Please be cautious when using it in a production environment.

## Usage

```Javascript
import Fory, { Type, InternalSerializerType } from '@apache-fory/fory';

/**
 * @apache-fory/hps use v8's fast-calls-api that can be called directly by jit, ensure that the version of Node is 20 or above.
 * Experimental feature, installation success cannot be guaranteed at this moment
 * If you are unable to install the module, replace it with `const hps = null;`
 **/
import hps from '@apache-fory/hps';

// Now we describe data structures using JSON, but in the future, we will use more ways.
const typeInfo = Type.struct('example.foo', {
  foo: Type.string(),
});
const fory = new Fory({ hps });
const { serialize, deserialize } = fory.registerSerializer(typeInfo);
const input = serialize({ foo: 'hello fory' });
const result = deserialize(input);
console.log(result);
```

## Packages

### fory

Fory protocol implementation. It generates JavaScript code at runtime to make sure that all the code could be optimized by v8 JIT efficiently.

### hps

Node.js high-performance suite, ensuring that your Node.js version is 20 or later.

`hps` is use for detect the string type in v8. Fory support latin1 and utf8 string both, we should get the certain type of string before write it
in buffer. It is slow to detect the string is latin1 or utf8, but hps can detect it by a hack way, which is called FASTCALL in v8.
so it is not stable now.
