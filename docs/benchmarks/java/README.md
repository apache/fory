# Java Benchmarks

## System Environment

- Operation System：4.9.151-015.x86_64
- CPU：Intel(R) Xeon(R) Platinum 8163 CPU @ 2.50GHz
- Byte Order：Little Endian
- L1d cache： 32K
- L1i cache：32K
- L2 cache： 1024K
- L3 cache： 33792K

## JMH params

Don't skip **warm up**, otherwise the results aren't accurate.

```bash
 -f 1 -wi 3 -i 3 -t 1 -w 2s -r 2s -rf cs
```

## Benchmark Data

### Struct

Struct is a class with 100 primitive fields:

```java
public class Struct {
  public int f1;
  public long f2;
  public float f3;
  public double f4;
  // ...
  public double f99;
}
```

### Struct2

Struct2 is a class with 100 boxed fields:

```java
public class Struct {
  public Integer f1;
  public Long f2;
  public Float f3;
  public Double f4;
  // ...
  public Double f99;
}
```

### MediaContent

MEDIA_CONTENT is a class from [jvm-serializers](https://github.com/eishay/jvm-serializers/blob/master/tpc/src/data/media/MediaContent.java).

### Sample

SAMPLE is a class from [kryo benchmark](https://github.com/EsotericSoftware/kryo/blob/master/benchmarks/src/main/java/com/esotericsoftware/kryo/benchmarks/data/Sample.java)

## Benchmark Plots

### Serialize to heap buffer

Serialize data java byte array.

#### Java schema consistent serialization

The deserialization peer must have same class definition with the serialization peer.
No class forward/backward compatibility are supported in this mode.

<p align="center">
<img width="24%" alt="" src="serialization/bench_serialize_STRUCT_to_array_tps.png">
<img width="24%" alt="" src="serialization/bench_serialize_STRUCT2_to_array_tps.png">
<img width="24%" alt="" src="serialization/bench_serialize_MEDIA_CONTENT_to_array_tps.png">
<img width="24%" alt="" src="serialization/bench_serialize_SAMPLE_to_array_tps.png">
</p>

#### Java schema compatible serialization

The deserialization peer can have different class definition with the serialization peer.
Class forward/backward compatibility are supported in this mode.

<p align="center">
<img width="24%" alt="" src="serialization/bench_serialize_compatible_STRUCT_to_array_tps.png">
<img width="24%" alt="" src="serialization/bench_serialize_compatible_STRUCT2_to_array_tps.png">
<img width="24%" alt="" src="serialization/bench_serialize_compatible_MEDIA_CONTENT_to_array_tps.png">
<img width="24%" alt="" src="serialization/bench_serialize_compatible_SAMPLE_to_array_tps.png">
</p>

#### Java schema consistent deserialization

The deserialization peer must have same class definition with the serialization peer.
No class forward/backward compatibility are supported in this mode.

<p align="center">
<img width="24%" alt="" src="deserialization/bench_deserialize_STRUCT_from_array_tps.png">
<img width="24%" alt="" src="deserialization/bench_deserialize_STRUCT2_from_array_tps.png">
<img width="24%" alt="" src="deserialization/bench_deserialize_MEDIA_CONTENT_from_array_tps.png">
<img width="24%" alt="" src="deserialization/bench_deserialize_SAMPLE_from_array_tps.png">
</p>

#### Java schema compatible deserialization

The deserialization peer can have different class definition with the serialization peer.
Class forward/backward compatibility are supported in this mode.

<p align="center">
<img width="24%" alt="" src="deserialization/bench_deserialize_compatible_STRUCT_from_array_tps.png">
<img width="24%" alt="" src="deserialization/bench_deserialize_compatible_STRUCT2_from_array_tps.png">
<img width="24%" alt="" src="deserialization/bench_deserialize_compatible_MEDIA_CONTENT_from_array_tps.png">
<img width="24%" alt="" src="deserialization/bench_deserialize_compatible_SAMPLE_from_array_tps.png">
</p>

### Off-heap serialization

Serialize data off-heap memory.

#### Java schema consistent serialization

The deserialization peer must have same class definition with the serialization peer.
No class forward/backward compatibility are supported in this mode.

<p align="center">
<img width="24%" alt="" src="serialization/bench_serialize_STRUCT_to_directBuffer_tps.png">
<img width="24%" alt="" src="serialization/bench_serialize_STRUCT2_to_directBuffer_tps.png">
<img width="24%" alt="" src="serialization/bench_serialize_MEDIA_CONTENT_to_directBuffer_tps.png">
<img width="24%" alt="" src="serialization/bench_serialize_compatible_SAMPLE_to_directBuffer_tps.png">
</p>

#### Java schema compatible serialization

The deserialization peer can have different class definition with the serialization peer.
Class forward/backward compatibility are supported in this mode.

<p align="center">
<img width="24%" alt="" src="serialization/bench_serialize_compatible_STRUCT_to_directBuffer_tps.png">
<img width="24%" alt="" src="serialization/bench_serialize_compatible_STRUCT2_to_directBuffer_tps.png">
<img width="24%" alt="" src="serialization/bench_serialize_compatible_MEDIA_CONTENT_to_directBuffer_tps.png">
<img width="24%" alt="" src="serialization/bench_serialize_SAMPLE_to_directBuffer_tps.png">
</p>

#### Java schema consistent deserialization

The deserialization peer must have same class definition with the serialization peer.
No class forward/backward compatibility are supported in this mode.

<p align="center">
<img width="24%" alt="" src="deserialization/bench_deserialize_STRUCT_from_directBuffer_tps.png">
<img width="24%" alt="" src="deserialization/bench_deserialize_STRUCT2_from_directBuffer_tps.png">
<img width="24%" alt="" src="deserialization/bench_deserialize_MEDIA_CONTENT_from_directBuffer_tps.png">
<img width="24%" alt="" src="deserialization/bench_deserialize_SAMPLE_from_directBuffer_tps.png">
</p>

#### Java schema compatible deserialization

The deserialization peer can have different class definition with the serialization peer.
Class forward/backward compatibility are supported in this mode.

<p align="center">
<img width="24%" alt="" src="deserialization/bench_deserialize_compatible_STRUCT_from_directBuffer_tps.png">
<img width="24%" alt="" src="deserialization/bench_deserialize_compatible_STRUCT2_from_directBuffer_tps.png">
<img width="24%" alt="" src="deserialization/bench_deserialize_compatible_MEDIA_CONTENT_from_directBuffer_tps.png">
<img width="24%" alt="" src="deserialization/bench_deserialize_compatible_SAMPLE_from_directBuffer_tps.png">
</p>

### Zero-copy serialization

Note that zero-copy serialization just avoid the copy in serialization, if you send data to other machine, there may be copies.

But if you serialize data between processes on same node and use shared-memory, if the data are in off-heap before serialization, then other processes can read this buffer without any copies.

#### Java zero-copy serialize to heap buffer

<p align="center">
<img width="24%" alt="" src="zerocopy/zero_copy_bench_serialize_BUFFER_to_array_tps.png">
<img width="24%" alt="" src="zerocopy/zero_copy_bench_serialize_BUFFER_to_directBuffer_tps.png">
<img width="24%" alt="" src="zerocopy/zero_copy_bench_serialize_PRIMITIVE_ARRAY_to_array_tps.png">
<img width="24%" alt="" src="zerocopy/zero_copy_bench_serialize_PRIMITIVE_ARRAY_to_directBuffer_tps.png">
</p>

#### Java zero-copy serialize to direct buffer

<p align="center">
<img width="24%" alt="" src="zerocopy/zero_copy_bench_deserialize_BUFFER_from_array_tps.png">
<img width="24%" alt="" src="zerocopy/zero_copy_bench_deserialize_BUFFER_from_directBuffer_tps.png">
<img width="24%" alt="" src="zerocopy/zero_copy_bench_deserialize_PRIMITIVE_ARRAY_from_array_tps.png">
<img width="24%" alt="" src="zerocopy/zero_copy_bench_deserialize_PRIMITIVE_ARRAY_from_directBuffer_tps.png">
</p>

## Benchmark Data

### Java Serialization

| Lib            | Benchmark              | bufferType   | objectType    | references | Tps             |
| -------------- | ---------------------- | ------------ | ------------- | ---------- | --------------- |
| Fst            | serialize              | array        | SAMPLE        | False      | 915907.574306   |
| Fst            | serialize              | array        | SAMPLE        | True       | 731869.156376   |
| Fst            | serialize              | array        | MEDIA_CONTENT | False      | 751892.023189   |
| Fst            | serialize              | array        | MEDIA_CONTENT | True       | 583859.907758   |
| Fst            | serialize              | array        | STRUCT        | False      | 882178.995727   |
| Fst            | serialize              | array        | STRUCT        | True       | 757753.756691   |
| Fst            | serialize              | array        | STRUCT2       | False      | 371762.982661   |
| Fst            | serialize              | array        | STRUCT2       | True       | 380638.700267   |
| Fst            | serialize              | directBuffer | SAMPLE        | False      | 902302.261168   |
| Fst            | serialize              | directBuffer | SAMPLE        | True       | 723614.06677    |
| Fst            | serialize              | directBuffer | MEDIA_CONTENT | False      | 728001.08025    |
| Fst            | serialize              | directBuffer | MEDIA_CONTENT | True       | 595679.580108   |
| Fst            | serialize              | directBuffer | STRUCT        | False      | 807847.663261   |
| Fst            | serialize              | directBuffer | STRUCT        | True       | 762088.935404   |
| Fst            | serialize              | directBuffer | STRUCT2       | False      | 365317.705376   |
| Fst            | serialize              | directBuffer | STRUCT2       | True       | 370851.880711   |
| Fory           | serialize              | array        | SAMPLE        | False      | 3570966.469087  |
| Fory           | serialize              | array        | SAMPLE        | True       | 1767693.83509   |
| Fory           | serialize              | array        | MEDIA_CONTENT | False      | 3031642.924542  |
| Fory           | serialize              | array        | MEDIA_CONTENT | True       | 2450384.600246  |
| Fory           | serialize              | array        | STRUCT        | False      | 7501415.56726   |
| Fory           | serialize              | array        | STRUCT        | True       | 6264439.154428  |
| Fory           | serialize              | array        | STRUCT2       | False      | 3586126.623874  |
| Fory           | serialize              | array        | STRUCT2       | True       | 3306474.506382  |
| Fory           | serialize              | directBuffer | SAMPLE        | False      | 3684487.760591  |
| Fory           | serialize              | directBuffer | SAMPLE        | True       | 1826456.709478  |
| Fory           | serialize              | directBuffer | MEDIA_CONTENT | False      | 2479862.129632  |
| Fory           | serialize              | directBuffer | MEDIA_CONTENT | True       | 1938527.588331  |
| Fory           | serialize              | directBuffer | STRUCT        | False      | 9834243.243204  |
| Fory           | serialize              | directBuffer | STRUCT        | True       | 7551780.823133  |
| Fory           | serialize              | directBuffer | STRUCT2       | False      | 2643155.135327  |
| Fory           | serialize              | directBuffer | STRUCT2       | True       | 2391110.083108  |
| Fory           | serialize_compatible   | array        | SAMPLE        | False      | 3604596.465625  |
| Fory           | serialize_compatible   | array        | SAMPLE        | True       | 1619648.337293  |
| Fory           | serialize_compatible   | array        | MEDIA_CONTENT | False      | 1679272.036243  |
| Fory           | serialize_compatible   | array        | MEDIA_CONTENT | True       | 1406736.538716  |
| Fory           | serialize_compatible   | array        | STRUCT        | False      | 3530406.108869  |
| Fory           | serialize_compatible   | array        | STRUCT        | True       | 3293059.098127  |
| Fory           | serialize_compatible   | array        | STRUCT2       | False      | 2773368.99768   |
| Fory           | serialize_compatible   | array        | STRUCT2       | True       | 2564174.550276  |
| Fory           | serialize_compatible   | directBuffer | SAMPLE        | False      | 3484533.218305  |
| Fory           | serialize_compatible   | directBuffer | SAMPLE        | True       | 1730824.630648  |
| Fory           | serialize_compatible   | directBuffer | MEDIA_CONTENT | False      | 1710680.937387  |
| Fory           | serialize_compatible   | directBuffer | MEDIA_CONTENT | True       | 1149999.473994  |
| Fory           | serialize_compatible   | directBuffer | STRUCT        | False      | 2653169.568374  |
| Fory           | serialize_compatible   | directBuffer | STRUCT        | True       | 2393817.762938  |
| Fory           | serialize_compatible   | directBuffer | STRUCT2       | False      | 1912402.937879  |
| Fory           | serialize_compatible   | directBuffer | STRUCT2       | True       | 1848338.968058  |
| Forymetashared | serialize_compatible   | array        | SAMPLE        | False      | 4409055.687063  |
| Forymetashared | serialize_compatible   | array        | SAMPLE        | True       | 1840705.439334  |
| Forymetashared | serialize_compatible   | array        | MEDIA_CONTENT | False      | 2992488.235281  |
| Forymetashared | serialize_compatible   | array        | MEDIA_CONTENT | True       | 2058738.716953  |
| Forymetashared | serialize_compatible   | array        | STRUCT        | False      | 9204444.777172  |
| Forymetashared | serialize_compatible   | array        | STRUCT        | True       | 7064625.291374  |
| Forymetashared | serialize_compatible   | array        | STRUCT2       | False      | 2575824.143864  |
| Forymetashared | serialize_compatible   | array        | STRUCT2       | True       | 3543082.528217  |
| Forymetashared | serialize_compatible   | directBuffer | SAMPLE        | False      | 5043538.364886  |
| Forymetashared | serialize_compatible   | directBuffer | SAMPLE        | True       | 1859289.705838  |
| Forymetashared | serialize_compatible   | directBuffer | MEDIA_CONTENT | False      | 2491443.556971  |
| Forymetashared | serialize_compatible   | directBuffer | MEDIA_CONTENT | True       | 1804349.244125  |
| Forymetashared | serialize_compatible   | directBuffer | STRUCT        | False      | 11650249.648715 |
| Forymetashared | serialize_compatible   | directBuffer | STRUCT        | True       | 8702412.752357  |
| Forymetashared | serialize_compatible   | directBuffer | STRUCT2       | False      | 2714748.572448  |
| Forymetashared | serialize_compatible   | directBuffer | STRUCT2       | True       | 1866073.031851  |
| Hession        | serialize              | array        | SAMPLE        | False      | 240386.502846   |
| Hession        | serialize              | array        | SAMPLE        | True       | 192414.014211   |
| Hession        | serialize              | array        | MEDIA_CONTENT | False      | 367782.358049   |
| Hession        | serialize              | array        | MEDIA_CONTENT | True       | 329427.47068    |
| Hession        | serialize              | array        | STRUCT        | False      | 258233.998931   |
| Hession        | serialize              | array        | STRUCT        | True       | 260845.209485   |
| Hession        | serialize              | array        | STRUCT2       | False      | 56056.080075    |
| Hession        | serialize              | array        | STRUCT2       | True       | 60038.87979     |
| Hession        | serialize              | directBuffer | SAMPLE        | False      | 240981.308085   |
| Hession        | serialize              | directBuffer | SAMPLE        | True       | 211949.960255   |
| Hession        | serialize              | directBuffer | MEDIA_CONTENT | False      | 372477.13815    |
| Hession        | serialize              | directBuffer | MEDIA_CONTENT | True       | 353376.085025   |
| Hession        | serialize              | directBuffer | STRUCT        | False      | 266481.009245   |
| Hession        | serialize              | directBuffer | STRUCT        | True       | 261762.594966   |
| Hession        | serialize              | directBuffer | STRUCT2       | False      | 55924.319442    |
| Hession        | serialize              | directBuffer | STRUCT2       | True       | 56674.065604    |
| Hession        | serialize_compatible   | array        | SAMPLE        | False      | 234454.975158   |
| Hession        | serialize_compatible   | array        | SAMPLE        | True       | 206174.173039   |
| Hession        | serialize_compatible   | array        | MEDIA_CONTENT | False      | 377195.903772   |
| Hession        | serialize_compatible   | array        | MEDIA_CONTENT | True       | 351657.879556   |
| Hession        | serialize_compatible   | array        | STRUCT        | False      | 258650.663523   |
| Hession        | serialize_compatible   | array        | STRUCT        | True       | 263564.913879   |
| Hession        | serialize_compatible   | array        | STRUCT2       | False      | 58509.125342    |
| Hession        | serialize_compatible   | array        | STRUCT2       | True       | 55552.977735    |
| Hession        | serialize_compatible   | directBuffer | SAMPLE        | False      | 194761.244263   |
| Hession        | serialize_compatible   | directBuffer | SAMPLE        | True       | 212840.483308   |
| Hession        | serialize_compatible   | directBuffer | MEDIA_CONTENT | False      | 371729.727192   |
| Hession        | serialize_compatible   | directBuffer | MEDIA_CONTENT | True       | 343834.954942   |
| Hession        | serialize_compatible   | directBuffer | STRUCT        | False      | 249241.452137   |
| Hession        | serialize_compatible   | directBuffer | STRUCT        | True       | 263623.143601   |
| Hession        | serialize_compatible   | directBuffer | STRUCT2       | False      | 58908.567439    |
| Hession        | serialize_compatible   | directBuffer | STRUCT2       | True       | 55524.373547    |
| Jdk            | serialize              | array        | SAMPLE        | False      | 118374.836631   |
| Jdk            | serialize              | array        | SAMPLE        | True       | 119858.140625   |
| Jdk            | serialize              | array        | MEDIA_CONTENT | False      | 137989.198821   |
| Jdk            | serialize              | array        | MEDIA_CONTENT | True       | 140260.668888   |
| Jdk            | serialize              | array        | STRUCT        | False      | 155908.24424    |
| Jdk            | serialize              | array        | STRUCT        | True       | 151258.539369   |
| Jdk            | serialize              | array        | STRUCT2       | False      | 36846.049162    |
| Jdk            | serialize              | array        | STRUCT2       | True       | 38183.705811    |
| Jdk            | serialize              | directBuffer | SAMPLE        | False      | 118273.584257   |
| Jdk            | serialize              | directBuffer | SAMPLE        | True       | 108263.040839   |
| Jdk            | serialize              | directBuffer | MEDIA_CONTENT | False      | 138567.623369   |
| Jdk            | serialize              | directBuffer | MEDIA_CONTENT | True       | 140158.67391    |
| Jdk            | serialize              | directBuffer | STRUCT        | False      | 154875.908438   |
| Jdk            | serialize              | directBuffer | STRUCT        | True       | 156404.686214   |
| Jdk            | serialize              | directBuffer | STRUCT2       | False      | 37444.967981    |
| Jdk            | serialize              | directBuffer | STRUCT2       | True       | 35798.679246    |
| Kryo           | serialize              | array        | SAMPLE        | False      | 1105365.931217  |
| Kryo           | serialize              | array        | SAMPLE        | True       | 734215.482491   |
| Kryo           | serialize              | array        | MEDIA_CONTENT | False      | 730792.521676   |
| Kryo           | serialize              | array        | MEDIA_CONTENT | True       | 445251.084327   |
| Kryo           | serialize              | array        | STRUCT        | False      | 558194.100861   |
| Kryo           | serialize              | array        | STRUCT        | True       | 557542.628765   |
| Kryo           | serialize              | array        | STRUCT2       | False      | 325172.969175   |
| Kryo           | serialize              | array        | STRUCT2       | True       | 259863.332448   |
| Kryo           | serialize              | directBuffer | SAMPLE        | False      | 1376560.302168  |
| Kryo           | serialize              | directBuffer | SAMPLE        | True       | 932887.968348   |
| Kryo           | serialize              | directBuffer | MEDIA_CONTENT | False      | 608972.51758    |
| Kryo           | serialize              | directBuffer | MEDIA_CONTENT | True       | 359875.473951   |
| Kryo           | serialize              | directBuffer | STRUCT        | False      | 1078046.011115  |
| Kryo           | serialize              | directBuffer | STRUCT        | True       | 853350.408656   |
| Kryo           | serialize              | directBuffer | STRUCT2       | False      | 355688.882786   |
| Kryo           | serialize              | directBuffer | STRUCT2       | True       | 338960.426033   |
| Kryo           | serialize_compatible   | array        | SAMPLE        | False      | 378907.663184   |
| Kryo           | serialize_compatible   | array        | SAMPLE        | True       | 320815.567701   |
| Kryo           | serialize_compatible   | array        | MEDIA_CONTENT | False      | 188911.259146   |
| Kryo           | serialize_compatible   | array        | MEDIA_CONTENT | True       | 145782.916427   |
| Kryo           | serialize_compatible   | array        | STRUCT        | False      | 145964.199559   |
| Kryo           | serialize_compatible   | array        | STRUCT        | True       | 136180.832879   |
| Kryo           | serialize_compatible   | array        | STRUCT2       | False      | 125807.748004   |
| Kryo           | serialize_compatible   | array        | STRUCT2       | True       | 114983.546343   |
| Kryo           | serialize_compatible   | directBuffer | SAMPLE        | False      | 296102.615094   |
| Kryo           | serialize_compatible   | directBuffer | SAMPLE        | True       | 276757.392449   |
| Kryo           | serialize_compatible   | directBuffer | MEDIA_CONTENT | False      | 185363.714829   |
| Kryo           | serialize_compatible   | directBuffer | MEDIA_CONTENT | True       | 142836.961878   |
| Kryo           | serialize_compatible   | directBuffer | STRUCT        | False      | 106695.800245   |
| Kryo           | serialize_compatible   | directBuffer | STRUCT        | True       | 106458.212005   |
| Kryo           | serialize_compatible   | directBuffer | STRUCT2       | False      | 92130.672361    |
| Kryo           | serialize_compatible   | directBuffer | STRUCT2       | True       | 88989.724768    |
| Protostuff     | serialize              | array        | SAMPLE        | False      | 663272.710783   |
| Protostuff     | serialize              | array        | MEDIA_CONTENT | False      | 780618.761219   |
| Protostuff     | serialize              | array        | STRUCT        | False      | 330975.350403   |
| Protostuff     | serialize              | array        | STRUCT2       | False      | 324563.440433   |
| Protostuff     | serialize              | directBuffer | SAMPLE        | False      | 693641.589806   |
| Protostuff     | serialize              | directBuffer | MEDIA_CONTENT | False      | 805941.345157   |
| Protostuff     | serialize              | directBuffer | STRUCT        | False      | 340262.650047   |
| Protostuff     | serialize              | directBuffer | STRUCT2       | False      | 325093.716261   |
| Fst            | deserialize            | array        | SAMPLE        | False      | 473409.796491   |
| Fst            | deserialize            | array        | SAMPLE        | True       | 428315.502365   |
| Fst            | deserialize            | array        | MEDIA_CONTENT | False      | 363455.785182   |
| Fst            | deserialize            | array        | MEDIA_CONTENT | True       | 304371.728638   |
| Fst            | deserialize            | array        | STRUCT        | False      | 357887.235311   |
| Fst            | deserialize            | array        | STRUCT        | True       | 353480.554035   |
| Fst            | deserialize            | array        | STRUCT2       | False      | 280131.091068   |
| Fst            | deserialize            | array        | STRUCT2       | True       | 260649.308016   |
| Fst            | deserialize            | directBuffer | SAMPLE        | False      | 441027.550809   |
| Fst            | deserialize            | directBuffer | SAMPLE        | True       | 420523.770904   |
| Fst            | deserialize            | directBuffer | MEDIA_CONTENT | False      | 311691.658687   |
| Fst            | deserialize            | directBuffer | MEDIA_CONTENT | True       | 251820.171513   |
| Fst            | deserialize            | directBuffer | STRUCT        | False      | 352441.597147   |
| Fst            | deserialize            | directBuffer | STRUCT        | True       | 334574.303484   |
| Fst            | deserialize            | directBuffer | STRUCT2       | False      | 262519.85881    |
| Fst            | deserialize            | directBuffer | STRUCT2       | True       | 234973.637096   |
| Fory           | deserialize            | array        | SAMPLE        | False      | 2069988.624415  |
| Fory           | deserialize            | array        | SAMPLE        | True       | 1797942.442313  |
| Fory           | deserialize            | array        | MEDIA_CONTENT | False      | 2054066.903469  |
| Fory           | deserialize            | array        | MEDIA_CONTENT | True       | 1507767.206603  |
| Fory           | deserialize            | array        | STRUCT        | False      | 4595230.434552  |
| Fory           | deserialize            | array        | STRUCT        | True       | 4634753.596131  |
| Fory           | deserialize            | array        | STRUCT2       | False      | 1126298.35955   |
| Fory           | deserialize            | array        | STRUCT2       | True       | 1046649.083082  |
| Fory           | deserialize            | directBuffer | SAMPLE        | False      | 2429791.078395  |
| Fory           | deserialize            | directBuffer | SAMPLE        | True       | 1958815.397807  |
| Fory           | deserialize            | directBuffer | MEDIA_CONTENT | False      | 1502746.028159  |
| Fory           | deserialize            | directBuffer | MEDIA_CONTENT | True       | 1290593.975753  |
| Fory           | deserialize            | directBuffer | STRUCT        | False      | 5012002.859236  |
| Fory           | deserialize            | directBuffer | STRUCT        | True       | 4864329.316938  |
| Fory           | deserialize            | directBuffer | STRUCT2       | False      | 1117586.457565  |
| Fory           | deserialize            | directBuffer | STRUCT2       | True       | 1018277.848128  |
| Fory           | deserialize_compatible | array        | SAMPLE        | False      | 2496046.895861  |
| Fory           | deserialize_compatible | array        | SAMPLE        | True       | 1834139.395757  |
| Fory           | deserialize_compatible | array        | MEDIA_CONTENT | False      | 1441671.70632   |
| Fory           | deserialize_compatible | array        | MEDIA_CONTENT | True       | 1121136.039627  |
| Fory           | deserialize_compatible | array        | STRUCT        | False      | 2110335.039275  |
| Fory           | deserialize_compatible | array        | STRUCT        | True       | 2135681.982674  |
| Fory           | deserialize_compatible | array        | STRUCT2       | False      | 849507.176263   |
| Fory           | deserialize_compatible | array        | STRUCT2       | True       | 815120.319155   |
| Fory           | deserialize_compatible | directBuffer | SAMPLE        | False      | 2308111.633661  |
| Fory           | deserialize_compatible | directBuffer | SAMPLE        | True       | 1820490.585648  |
| Fory           | deserialize_compatible | directBuffer | MEDIA_CONTENT | False      | 1256034.732514  |
| Fory           | deserialize_compatible | directBuffer | MEDIA_CONTENT | True       | 1054942.751816  |
| Fory           | deserialize_compatible | directBuffer | STRUCT        | False      | 1596464.248141  |
| Fory           | deserialize_compatible | directBuffer | STRUCT        | True       | 1684681.074242  |
| Fory           | deserialize_compatible | directBuffer | STRUCT2       | False      | 784036.589363   |
| Fory           | deserialize_compatible | directBuffer | STRUCT2       | True       | 782679.662083   |
| Forymetashared | deserialize_compatible | array        | SAMPLE        | False      | 2485564.396196  |
| Forymetashared | deserialize_compatible | array        | SAMPLE        | True       | 2002938.794909  |
| Forymetashared | deserialize_compatible | array        | MEDIA_CONTENT | False      | 2479742.810882  |
| Forymetashared | deserialize_compatible | array        | MEDIA_CONTENT | True       | 1623938.202345  |
| Forymetashared | deserialize_compatible | array        | STRUCT        | False      | 4978833.206806  |
| Forymetashared | deserialize_compatible | array        | STRUCT        | True       | 4807963.88252   |
| Forymetashared | deserialize_compatible | array        | STRUCT2       | False      | 1201998.142474  |
| Forymetashared | deserialize_compatible | array        | STRUCT2       | True       | 1058423.614156  |
| Forymetashared | deserialize_compatible | directBuffer | SAMPLE        | False      | 2489261.533644  |
| Forymetashared | deserialize_compatible | directBuffer | SAMPLE        | True       | 1927548.827586  |
| Forymetashared | deserialize_compatible | directBuffer | MEDIA_CONTENT | False      | 1718098.363961  |
| Forymetashared | deserialize_compatible | directBuffer | MEDIA_CONTENT | True       | 1333345.536684  |
| Forymetashared | deserialize_compatible | directBuffer | STRUCT        | False      | 5149070.65783   |
| Forymetashared | deserialize_compatible | directBuffer | STRUCT        | True       | 5137500.621288  |
| Forymetashared | deserialize_compatible | directBuffer | STRUCT2       | False      | 1131212.586953  |
| Forymetashared | deserialize_compatible | directBuffer | STRUCT2       | True       | 1089162.408165  |
| Hession        | deserialize            | array        | SAMPLE        | False      | 119471.518388   |
| Hession        | deserialize            | array        | SAMPLE        | True       | 121106.002978   |
| Hession        | deserialize            | array        | MEDIA_CONTENT | False      | 118156.072484   |
| Hession        | deserialize            | array        | MEDIA_CONTENT | True       | 120016.594171   |
| Hession        | deserialize            | array        | STRUCT        | False      | 84709.108821    |
| Hession        | deserialize            | array        | STRUCT        | True       | 91050.370244    |
| Hession        | deserialize            | array        | STRUCT2       | False      | 69758.767783    |
| Hession        | deserialize            | array        | STRUCT2       | True       | 68616.029248    |
| Hession        | deserialize            | directBuffer | SAMPLE        | False      | 117806.916589   |
| Hession        | deserialize            | directBuffer | SAMPLE        | True       | 121940.783597   |
| Hession        | deserialize            | directBuffer | MEDIA_CONTENT | False      | 111067.942626   |
| Hession        | deserialize            | directBuffer | MEDIA_CONTENT | True       | 121820.82126    |
| Hession        | deserialize            | directBuffer | STRUCT        | False      | 91151.633583    |
| Hession        | deserialize            | directBuffer | STRUCT        | True       | 91037.205901    |
| Hession        | deserialize            | directBuffer | STRUCT2       | False      | 66866.108653    |
| Hession        | deserialize            | directBuffer | STRUCT2       | True       | 65338.345185    |
| Hession        | deserialize_compatible | array        | SAMPLE        | False      | 121898.105768   |
| Hession        | deserialize_compatible | array        | SAMPLE        | True       | 121297.485903   |
| Hession        | deserialize_compatible | array        | MEDIA_CONTENT | False      | 121619.090797   |
| Hession        | deserialize_compatible | array        | MEDIA_CONTENT | True       | 119994.10405    |
| Hession        | deserialize_compatible | array        | STRUCT        | False      | 88617.486795    |
| Hession        | deserialize_compatible | array        | STRUCT        | True       | 90206.654212    |
| Hession        | deserialize_compatible | array        | STRUCT2       | False      | 63703.763814    |
| Hession        | deserialize_compatible | array        | STRUCT2       | True       | 69521.573119    |
| Hession        | deserialize_compatible | directBuffer | SAMPLE        | False      | 124044.417439   |
| Hession        | deserialize_compatible | directBuffer | SAMPLE        | True       | 120276.449497   |
| Hession        | deserialize_compatible | directBuffer | MEDIA_CONTENT | False      | 107594.47489    |
| Hession        | deserialize_compatible | directBuffer | MEDIA_CONTENT | True       | 116531.023438   |
| Hession        | deserialize_compatible | directBuffer | STRUCT        | False      | 89580.561575    |
| Hession        | deserialize_compatible | directBuffer | STRUCT        | True       | 84407.472531    |
| Hession        | deserialize_compatible | directBuffer | STRUCT2       | False      | 69342.030965    |
| Hession        | deserialize_compatible | directBuffer | STRUCT2       | True       | 68542.055543    |
| Jdk            | deserialize            | array        | SAMPLE        | False      | 29309.573998    |
| Jdk            | deserialize            | array        | SAMPLE        | True       | 27466.003923    |
| Jdk            | deserialize            | array        | MEDIA_CONTENT | False      | 38536.250402    |
| Jdk            | deserialize            | array        | MEDIA_CONTENT | True       | 38957.19109     |
| Jdk            | deserialize            | array        | STRUCT        | False      | 29603.066599    |
| Jdk            | deserialize            | array        | STRUCT        | True       | 29727.744196    |
| Jdk            | deserialize            | array        | STRUCT2       | False      | 14888.805111    |
| Jdk            | deserialize            | array        | STRUCT2       | True       | 14034.100664    |
| Jdk            | deserialize            | directBuffer | SAMPLE        | False      | 28128.457935    |
| Jdk            | deserialize            | directBuffer | SAMPLE        | True       | 28241.014735    |
| Jdk            | deserialize            | directBuffer | MEDIA_CONTENT | False      | 40512.632076    |
| Jdk            | deserialize            | directBuffer | MEDIA_CONTENT | True       | 37030.594632    |
| Jdk            | deserialize            | directBuffer | STRUCT        | False      | 28717.004518    |
| Jdk            | deserialize            | directBuffer | STRUCT        | True       | 29549.998286    |
| Jdk            | deserialize            | directBuffer | STRUCT2       | False      | 14652.043788    |
| Jdk            | deserialize            | directBuffer | STRUCT2       | True       | 14425.886048    |
| Kryo           | deserialize            | array        | SAMPLE        | False      | 979173.981159   |
| Kryo           | deserialize            | array        | SAMPLE        | True       | 716438.884369   |
| Kryo           | deserialize            | array        | MEDIA_CONTENT | False      | 577631.234369   |
| Kryo           | deserialize            | array        | MEDIA_CONTENT | True       | 365530.417232   |
| Kryo           | deserialize            | array        | STRUCT        | False      | 607750.343557   |
| Kryo           | deserialize            | array        | STRUCT        | True       | 552802.247807   |
| Kryo           | deserialize            | array        | STRUCT2       | False      | 275984.042401   |
| Kryo           | deserialize            | array        | STRUCT2       | True       | 242710.554833   |
| Kryo           | deserialize            | directBuffer | SAMPLE        | False      | 983538.936801   |
| Kryo           | deserialize            | directBuffer | SAMPLE        | True       | 762889.302732   |
| Kryo           | deserialize            | directBuffer | MEDIA_CONTENT | False      | 389473.174523   |
| Kryo           | deserialize            | directBuffer | MEDIA_CONTENT | True       | 306995.240799   |
| Kryo           | deserialize            | directBuffer | STRUCT        | False      | 910534.169114   |
| Kryo           | deserialize            | directBuffer | STRUCT        | True       | 914404.107564   |
| Kryo           | deserialize            | directBuffer | STRUCT2       | False      | 319247.256793   |
| Kryo           | deserialize            | directBuffer | STRUCT2       | True       | 249105.828416   |
| Kryo           | deserialize_compatible | array        | SAMPLE        | False      | 255086.928308   |
| Kryo           | deserialize_compatible | array        | SAMPLE        | True       | 238811.99551    |
| Kryo           | deserialize_compatible | array        | MEDIA_CONTENT | False      | 180882.860363   |
| Kryo           | deserialize_compatible | array        | MEDIA_CONTENT | True       | 154311.21154    |
| Kryo           | deserialize_compatible | array        | STRUCT        | False      | 78771.635309    |
| Kryo           | deserialize_compatible | array        | STRUCT        | True       | 72805.937649    |
| Kryo           | deserialize_compatible | array        | STRUCT2       | False      | 60602.285743    |
| Kryo           | deserialize_compatible | array        | STRUCT2       | True       | 62729.908347    |
| Kryo           | deserialize_compatible | directBuffer | SAMPLE        | False      | 201993.78789    |
| Kryo           | deserialize_compatible | directBuffer | SAMPLE        | True       | 174534.71087    |
| Kryo           | deserialize_compatible | directBuffer | MEDIA_CONTENT | False      | 134485.1603     |
| Kryo           | deserialize_compatible | directBuffer | MEDIA_CONTENT | True       | 119311.787329   |
| Kryo           | deserialize_compatible | directBuffer | STRUCT        | False      | 58574.904245    |
| Kryo           | deserialize_compatible | directBuffer | STRUCT        | True       | 60685.320299    |
| Kryo           | deserialize_compatible | directBuffer | STRUCT2       | False      | 54637.329134    |
| Kryo           | deserialize_compatible | directBuffer | STRUCT2       | True       | 51761.569591    |
| Protostuff     | deserialize            | array        | SAMPLE        | False      | 619338.385412   |
| Protostuff     | deserialize            | array        | MEDIA_CONTENT | False      | 951662.019963   |
| Protostuff     | deserialize            | array        | STRUCT        | False      | 517381.168594   |
| Protostuff     | deserialize            | array        | STRUCT2       | False      | 416212.973861   |
| Protostuff     | deserialize            | directBuffer | SAMPLE        | False      | 624804.978534   |
| Protostuff     | deserialize            | directBuffer | MEDIA_CONTENT | False      | 964664.641598   |
| Protostuff     | deserialize            | directBuffer | STRUCT        | False      | 538924.947147   |
| Protostuff     | deserialize            | directBuffer | STRUCT2       | False      | 425523.315814   |

### Java Zero-copy

| Lib  | Benchmark   | array_size | bufferType   | dataType        | Tps            |
| ---- | ----------- | ---------- | ------------ | --------------- | -------------- |
| Fst  | deserialize | 200        | array        | PRIMITIVE_ARRAY | 219333.990504  |
| Fst  | deserialize | 200        | array        | BUFFER          | 657754.887247  |
| Fst  | deserialize | 200        | directBuffer | PRIMITIVE_ARRAY | 179604.045774  |
| Fst  | deserialize | 200        | directBuffer | BUFFER          | 598421.278941  |
| Fst  | deserialize | 1000       | array        | PRIMITIVE_ARRAY | 53100.903684   |
| Fst  | deserialize | 1000       | array        | BUFFER          | 424147.154601  |
| Fst  | deserialize | 1000       | directBuffer | PRIMITIVE_ARRAY | 38572.001768   |
| Fst  | deserialize | 1000       | directBuffer | BUFFER          | 298929.116572  |
| Fst  | deserialize | 5000       | array        | PRIMITIVE_ARRAY | 10672.872798   |
| Fst  | deserialize | 5000       | array        | BUFFER          | 136934.604328  |
| Fst  | deserialize | 5000       | directBuffer | PRIMITIVE_ARRAY | 8561.694533    |
| Fst  | deserialize | 5000       | directBuffer | BUFFER          | 77950.612503   |
| Fst  | serialize   | 200        | array        | PRIMITIVE_ARRAY | 313986.053417  |
| Fst  | serialize   | 200        | array        | BUFFER          | 2400193.240466 |
| Fst  | serialize   | 200        | directBuffer | PRIMITIVE_ARRAY | 294132.218623  |
| Fst  | serialize   | 200        | directBuffer | BUFFER          | 2482550.111756 |
| Fst  | serialize   | 1000       | array        | PRIMITIVE_ARRAY | 67209.107012   |
| Fst  | serialize   | 1000       | array        | BUFFER          | 1805557.47781  |
| Fst  | serialize   | 1000       | directBuffer | PRIMITIVE_ARRAY | 66108.014324   |
| Fst  | serialize   | 1000       | directBuffer | BUFFER          | 1644789.42701  |
| Fst  | serialize   | 5000       | array        | PRIMITIVE_ARRAY | 14997.400124   |
| Fst  | serialize   | 5000       | array        | BUFFER          | 811029.402136  |
| Fst  | serialize   | 5000       | directBuffer | PRIMITIVE_ARRAY | 15000.378818   |
| Fst  | serialize   | 5000       | directBuffer | BUFFER          | 477148.54085   |
| Fory | deserialize | 200        | array        | PRIMITIVE_ARRAY | 986136.067809  |
| Fory | deserialize | 200        | array        | BUFFER          | 3302149.383135 |
| Fory | deserialize | 200        | directBuffer | PRIMITIVE_ARRAY | 991807.969328  |
| Fory | deserialize | 200        | directBuffer | BUFFER          | 3113115.471758 |
| Fory | deserialize | 1000       | array        | PRIMITIVE_ARRAY | 205671.992736  |
| Fory | deserialize | 1000       | array        | BUFFER          | 2831942.848999 |
| Fory | deserialize | 1000       | directBuffer | PRIMITIVE_ARRAY | 202475.242341  |
| Fory | deserialize | 1000       | directBuffer | BUFFER          | 3397690.327371 |
| Fory | deserialize | 5000       | array        | PRIMITIVE_ARRAY | 40312.590172   |
| Fory | deserialize | 5000       | array        | BUFFER          | 3296658.120035 |
| Fory | deserialize | 5000       | directBuffer | PRIMITIVE_ARRAY | 40413.743717   |
| Fory | deserialize | 5000       | directBuffer | BUFFER          | 3284441.570594 |
| Fory | serialize   | 200        | array        | PRIMITIVE_ARRAY | 8297232.942927 |
| Fory | serialize   | 200        | array        | BUFFER          | 5123572.914045 |
| Fory | serialize   | 200        | directBuffer | PRIMITIVE_ARRAY | 8335248.350301 |
| Fory | serialize   | 200        | directBuffer | BUFFER          | 5400346.890126 |
| Fory | serialize   | 1000       | array        | PRIMITIVE_ARRAY | 8772856.921028 |
| Fory | serialize   | 1000       | array        | BUFFER          | 4979590.929127 |
| Fory | serialize   | 1000       | directBuffer | PRIMITIVE_ARRAY | 8207563.785251 |
| Fory | serialize   | 1000       | directBuffer | BUFFER          | 5376191.775007 |
| Fory | serialize   | 5000       | array        | PRIMITIVE_ARRAY | 8027439.580246 |
| Fory | serialize   | 5000       | array        | BUFFER          | 5018916.32477  |
| Fory | serialize   | 5000       | directBuffer | PRIMITIVE_ARRAY | 7695981.988316 |
| Fory | serialize   | 5000       | directBuffer | BUFFER          | 5330897.68296  |
| Kryo | deserialize | 200        | array        | PRIMITIVE_ARRAY | 146675.360652  |
| Kryo | deserialize | 200        | array        | BUFFER          | 1296284.78772  |
| Kryo | deserialize | 200        | directBuffer | PRIMITIVE_ARRAY | 518713.299424  |
| Kryo | deserialize | 200        | directBuffer | BUFFER          | 1004844.498712 |
| Kryo | deserialize | 1000       | array        | PRIMITIVE_ARRAY | 30409.835023   |
| Kryo | deserialize | 1000       | array        | BUFFER          | 721266.54113   |
| Kryo | deserialize | 1000       | directBuffer | PRIMITIVE_ARRAY | 112132.004609  |
| Kryo | deserialize | 1000       | directBuffer | BUFFER          | 592972.713203  |
| Kryo | deserialize | 5000       | array        | PRIMITIVE_ARRAY | 6124.351248    |
| Kryo | deserialize | 5000       | array        | BUFFER          | 147251.846111  |
| Kryo | deserialize | 5000       | directBuffer | PRIMITIVE_ARRAY | 21826.04041    |
| Kryo | deserialize | 5000       | directBuffer | BUFFER          | 148614.476829  |
| Kryo | serialize   | 200        | array        | PRIMITIVE_ARRAY | 147342.606262  |
| Kryo | serialize   | 200        | array        | BUFFER          | 1985187.977633 |
| Kryo | serialize   | 200        | directBuffer | PRIMITIVE_ARRAY | 972683.763633  |
| Kryo | serialize   | 200        | directBuffer | BUFFER          | 1739454.51977  |
| Kryo | serialize   | 1000       | array        | PRIMITIVE_ARRAY | 31395.721514   |
| Kryo | serialize   | 1000       | array        | BUFFER          | 1616159.67123  |
| Kryo | serialize   | 1000       | directBuffer | PRIMITIVE_ARRAY | 209183.090868  |
| Kryo | serialize   | 1000       | directBuffer | BUFFER          | 1377272.56851  |
| Kryo | serialize   | 5000       | array        | PRIMITIVE_ARRAY | 6248.006967    |
| Kryo | serialize   | 5000       | array        | BUFFER          | 711287.533377  |
| Kryo | serialize   | 5000       | directBuffer | PRIMITIVE_ARRAY | 43565.678616   |
| Kryo | serialize   | 5000       | directBuffer | BUFFER          | 707092.956534  |
