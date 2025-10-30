# Apache Fory™ CPython Benchmark

Microbenchmark comparing Apache Fory™ and Pickle serialization performance in CPython.

## Quick Start

### Step 1: Install Apache Fory™ into Python

Follow the installation instructions from the main documentation.

### Step 2: Execute the benchmark script

```bash
python fory_benchmark.py
```

This will run all benchmarks with both Fory and Pickle serializers using default settings.

## Usage

### Basic Usage

```bash
# Run all benchmarks with both Fory and Pickle
python fory_benchmark.py

# Run all benchmarks without reference tracking
python fory_benchmark.py --no-ref

# Run specific benchmarks
python fory_benchmark.py --benchmarks dict,large_dict,complex

# Compare only Fory performance
python fory_benchmark.py --serializers fory

# Compare only Pickle performance
python fory_benchmark.py --serializers pickle

# Run with more iterations for better accuracy
python fory_benchmark.py --iterations 50 --repeat 10

# Debug with pure Python mode
python fory_benchmark.py --disable-cython --benchmarks dict
```

## Command-Line Options

### Benchmark Selection

#### `--benchmarks BENCHMARK_LIST`

Comma-separated list of benchmarks to run. Default: `all`

Available benchmarks:

- `dict` - Small dictionary serialization (28 fields with mixed types)
- `large_dict` - Large dictionary (2^10 + 1 entries)
- `dict_group` - Group of 3 dictionaries
- `tuple` - Small tuple with nested list
- `large_tuple` - Large tuple (2^20 + 1 integers)
- `large_float_tuple` - Large tuple of floats (2^20 + 1 elements)
- `large_boolean_tuple` - Large tuple of booleans (2^20 + 1 elements)
- `list` - Nested lists (10x10x10 structure)
- `large_list` - Large list (2^20 + 1 integers)
- `complex` - Complex dataclass objects with nested structures

Examples:

```bash
# Run only dictionary benchmarks
python fory_benchmark.py --benchmarks dict,large_dict,dict_group

# Run only large data benchmarks
python fory_benchmark.py --benchmarks large_dict,large_tuple,large_list

# Run only the complex object benchmark
python fory_benchmark.py --benchmarks complex
```

#### `--serializers SERIALIZER_LIST`

Comma-separated list of serializers to benchmark. Default: `all`

Available serializers:

- `fory` - Apache Fory™ serialization
- `pickle` - Python's built-in pickle serialization

Examples:

```bash
# Compare both serializers (default)
python fory_benchmark.py --serializers fory,pickle

# Benchmark only Fory
python fory_benchmark.py --serializers fory

# Benchmark only Pickle
python fory_benchmark.py --serializers pickle
```

### Fory Configuration

#### `--no-ref`

Disable reference tracking for Fory. By default, Fory tracks references to handle shared and circular references.

```bash
# Run without reference tracking
python fory_benchmark.py --no-ref
```

#### `--disable-cython`

Use pure Python mode instead of Cython serialization for Fory. Useful for debugging protocol issues.

```bash
# Use pure Python serialization
python fory_benchmark.py --disable-cython
```

### Benchmark Parameters

These options control the benchmark measurement process:

#### `--warmup N`

Number of warmup iterations before measurement starts. Default: `3`

```bash
python fory_benchmark.py --warmup 5
```

#### `--iterations N`

Number of measurement iterations to collect. Default: `20`

```bash
python fory_benchmark.py --iterations 50
```

#### `--repeat N`

Number of times to repeat each iteration. Default: `5`

```bash
python fory_benchmark.py --repeat 10
```

#### `--number N`

Number of times to call the serialization function per measurement (inner loop). Default: `100`

```bash
python fory_benchmark.py --number 1000
```

#### `--help`

Display help message and exit.

```bash
python fory_benchmark.py --help
```

## Examples

### Running Specific Comparisons

```bash
# Compare Fory and Pickle on dictionary benchmarks
python fory_benchmark.py --benchmarks dict,large_dict,dict_group

# Compare performance without reference tracking
python fory_benchmark.py --no-ref

# Test only Fory with high precision
python fory_benchmark.py --serializers fory --iterations 100 --repeat 10
```

### Performance Tuning

```bash
# Quick test with fewer iterations
python fory_benchmark.py --warmup 1 --iterations 5 --repeat 3

# High-precision benchmark
python fory_benchmark.py --warmup 10 --iterations 100 --repeat 10

# Benchmark large data structures with more inner loop iterations
python fory_benchmark.py --benchmarks large_list,large_tuple --number 1000
```

### Debugging and Development

```bash
# Debug protocol issues with pure Python mode
python fory_benchmark.py --disable-cython --benchmarks dict

# Test complex objects only
python fory_benchmark.py --benchmarks complex --iterations 10

# Compare Fory with and without ref tracking
python fory_benchmark.py --serializers fory --benchmarks dict
python fory_benchmark.py --serializers fory --benchmarks dict --no-ref
```

## Output Format

The benchmark script provides three sections of output:

1. **Progress**: Real-time progress as each benchmark runs
2. **Summary**: Table of all results showing mean time and standard deviation
3. **Speedup**: Comparison table showing Fory speedup vs Pickle (only when both serializers are tested)

Example output:

```
Benchmarking 3 benchmark(s) with 2 serializer(s)
Warmup: 3, Iterations: 20, Repeat: 5, Inner loop: 100
Fory reference tracking: enabled
================================================================================

Running fory_dict... 12.34 us ± 0.56 us
Running pickle_dict... 45.67 us ± 1.23 us
...

================================================================================
SUMMARY
================================================================================
Serializer      Benchmark                 Mean                 Std Dev
--------------------------------------------------------------------------------
fory            dict                      12.34 us             0.56 us
pickle          dict                      45.67 us             1.23 us
...

================================================================================
SPEEDUP (Fory vs Pickle)
================================================================================
Benchmark                 Fory                 Pickle               Speedup
--------------------------------------------------------------------------------
dict                      12.34 us             45.67 us             3.70x
...
```
