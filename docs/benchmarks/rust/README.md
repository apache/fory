## Performance Comparison Report

_Generated on 2025-10-26 18:36:41_

How to generate performance report:

```bash
cd benchmarks/rust_benchmark
cargo bench 2>&1 | tee cargo_bench.log
python benchmark_report.py --log-file cargo_bench.log --output-dir=report_output
```

### Hardware & OS Info

| Key                  | Value         |
| -------------------- | ------------- |
| OS                   | Darwin 24.5.0 |
| Machine              | arm64         |
| Processor            | arm           |
| CPU Cores (Physical) | 12            |
| CPU Cores (Logical)  | 12            |
| Total RAM (GB)       | 48.0          |

### Benchmark Plots

**company**

<p align="center">
<img src="company.png" width="90%">
</p>

**ecommerce_data**

<p align="center">
<img src="ecommerce_data.png" width="90%">
</p>

**person**

<p align="center">
<img src="person.png" width="90%">
</p>

**simple_list**

<p align="center">
<img src="simple_list.png" width="90%">
</p>

**simple_map**

<p align="center">
<img src="simple_map.png" width="90%">
</p>

**simple_struct**

<p align="center">
<img src="simple_struct.png" width="90%">
</p>

**system_data**

<p align="center">
<img src="system_data.png" width="90%">
</p>

### Serialize Results

| Datatype       | Size   | Operation | Fory TPS   | JSON TPS   | Protobuf TPS | Fastest |
| -------------- | ------ | --------- | ---------- | ---------- | ------------ | ------- |
| company        | small  | serialize | 10,063,906 | 761,673    | 896,620      | fory    |
| company        | medium | serialize | 412,507    | 33,835     | 37,590       | fory    |
| company        | large  | serialize | 9,183      | 793        | 880          | fory    |
| ecommerce_data | small  | serialize | 2,350,729  | 206,262    | 256,970      | fory    |
| ecommerce_data | medium | serialize | 59,977     | 4,699      | 5,242        | fory    |
| ecommerce_data | large  | serialize | 3,727      | 266        | 295          | fory    |
| person         | small  | serialize | 13,632,522 | 1,345,189  | 1,475,035    | fory    |
| person         | medium | serialize | 3,839,656  | 337,610    | 369,031      | fory    |
| person         | large  | serialize | 907,853    | 79,631     | 91,408       | fory    |
| simple_list    | small  | serialize | 27,726,945 | 4,874,957  | 4,643,172    | fory    |
| simple_list    | medium | serialize | 4,770,765  | 401,558    | 397,551      | fory    |
| simple_list    | large  | serialize | 606,061    | 41,061     | 44,565       | fory    |
| simple_map     | small  | serialize | 22,862,369 | 3,888,025  | 2,695,999    | fory    |
| simple_map     | medium | serialize | 2,128,973  | 204,319    | 193,132      | fory    |
| simple_map     | large  | serialize | 177,847    | 18,419     | 18,668       | fory    |
| simple_struct  | small  | serialize | 35,729,598 | 10,167,045 | 8,633,342    | fory    |
| simple_struct  | medium | serialize | 34,988,279 | 9,737,098  | 6,433,350    | fory    |
| simple_struct  | large  | serialize | 31,801,558 | 4,545,041  | 7,420,049    | fory    |
| system_data    | small  | serialize | 5,382,131  | 468,033    | 569,930      | fory    |
| system_data    | medium | serialize | 174,240    | 11,896     | 14,753       | fory    |
| system_data    | large  | serialize | 10,671     | 876        | 1,040        | fory    |

### Deserialize Results

| Datatype       | Size   | Operation   | Fory TPS   | JSON TPS   | Protobuf TPS | Fastest |
| -------------- | ------ | ----------- | ---------- | ---------- | ------------ | ------- |
| company        | small  | deserialize | 1,145,593  | 718,597    | 917,431      | fory    |
| company        | medium | deserialize | 35,195     | 24,474     | 25,057       | fory    |
| company        | large  | deserialize | 836        | 623        | 623          | fory    |
| ecommerce_data | small  | deserialize | 278,427    | 183,237    | 241,109      | fory    |
| ecommerce_data | medium | deserialize | 5,622      | 3,820      | 4,249        | fory    |
| ecommerce_data | large  | deserialize | 298        | 204        | 217          | fory    |
| person         | small  | deserialize | 1,924,372  | 1,214,388  | 1,675,631    | fory    |
| person         | medium | deserialize | 371,016    | 265,280    | 266,333      | fory    |
| person         | large  | deserialize | 84,839     | 63,975     | 61,820       | fory    |
| simple_list    | small  | deserialize | 6,129,329  | 3,599,582  | 4,429,286    | fory    |
| simple_list    | medium | deserialize | 460,745    | 305,409    | 337,336      | fory    |
| simple_list    | large  | deserialize | 54,348     | 33,962     | 38,776       | fory    |
| simple_map     | small  | deserialize | 3,686,364  | 3,090,426  | 3,477,051    | fory    |
| simple_map     | medium | deserialize | 171,774    | 126,638    | 119,323      | fory    |
| simple_map     | large  | deserialize | 16,501     | 10,920     | 10,853       | fory    |
| simple_struct  | small  | deserialize | 31,476,235 | 12,948,168 | 28,280,543   | fory    |
| simple_struct  | medium | deserialize | 30,077,902 | 10,431,118 | 23,012,841   | fory    |
| simple_struct  | large  | deserialize | 28,201,585 | 8,955,759  | 23,528,858   | fory    |
| system_data    | small  | deserialize | 644,870    | 421,106    | 549,209      | fory    |
| system_data    | medium | deserialize | 14,322     | 9,205      | 10,565       | fory    |
| system_data    | large  | deserialize | 961        | 634        | 706          | fory    |
