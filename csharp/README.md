# Apache Fory™ C\#

## Threading Model

- `Fory` is optimized for single-threaded reuse. Do not use one `Fory` instance concurrently from multiple threads.
- For multi-threaded usage, build and use `ThreadSafeFory`:

```csharp
ThreadSafeFory fory = Fory.Builder().BuildThreadSafe();
```
