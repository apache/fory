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

![Java Heap Schema Consistent Serialization](java_heap_serialize_consistent.png)

#### Java schema compatible serialization

The deserialization peer can have different class definition with the serialization peer.
Class forward/backward compatibility are supported in this mode.

![Java Heap Schema Compatible Serialization](java_heap_serialize_compatible.png)

#### Java schema consistent deserialization

The deserialization peer must have same class definition with the serialization peer.
No class forward/backward compatibility are supported in this mode.

![Java Heap Schema Consistent Deserialization](java_heap_deserialize_consistent.png)

#### Java schema compatible deserialization

The deserialization peer can have different class definition with the serialization peer.
Class forward/backward compatibility are supported in this mode.

![Java Heap Schema Compatible Deserialization](java_heap_deserialize_compatible.png)

### Off-heap serialization

Serialize data off-heap memory.

#### Java schema consistent serialization

The deserialization peer must have same class definition with the serialization peer.
No class forward/backward compatibility are supported in this mode.

![Java Off Heap Schema Consistent Serialization](java_offheap_serialize_consistent.png)

#### Java schema compatible serialization

The deserialization peer can have different class definition with the serialization peer.
Class forward/backward compatibility are supported in this mode.

![Java Off Heap Schema Compatible Serialization](java_offheap_serialize_compatible.png)

#### Java schema consistent deserialization

The deserialization peer must have same class definition with the serialization peer.
No class forward/backward compatibility are supported in this mode.

![Java Off Heap Schema Consistent Deserialization](java_offheap_deserialize_consistent.png)

#### Java schema compatible deserialization

The deserialization peer can have different class definition with the serialization peer.
Class forward/backward compatibility are supported in this mode.

![Java Off Heap Schema Compatible Deserialization](java_offheap_deserialize_compatible.png)

### Zero-copy serialization

Note that zero-copy serialization just avoid the copy in serialization, if you send data to other machine, there may be copies.

But if you serialize data between processes on same node and use shared-memory, if the data are in off-heap before serialization, then other processes can read this buffer without any copies.

#### Java zero-copy serialize to heap buffer

![Java Zero Copy Serialization](java_zero_copy_serialize.png)

#### Java zero-copy serialize to direct buffer

![Java Zero Copy Deserialization](java_zero_copy_deserialize.png)

## Benchmark Data

### Java Serialization

| Benchmark              | objectType    | bufferType   | references | Fory           | ForyMetaShared  | Kryo           | Fst           | Hession       | Jdk           | Protostuff    |
| ---------------------- | ------------- | ------------ | ---------- | -------------- | --------------- | -------------- | ------------- | ------------- | ------------- | ------------- |
| serialize              | STRUCT        | array        | False      | 7501415.567260 |                 | 558194.100861  | 882178.995727 | 258233.998931 | 155908.244240 | 330975.350403 |
| serialize              | STRUCT        | array        | True       | 6264439.154428 |                 | 557542.628765  | 757753.756691 | 260845.209485 | 151258.539369 |               |
| serialize              | STRUCT        | directBuffer | False      | 9834223.243204 |                 | 1078046.011115 | 807847.663261 | 266481.009225 | 154875.908438 | 340262.650047 |
| serialize              | STRUCT        | directBuffer | True       | 7551780.823133 |                 | 853350.408656  | 762088.935404 | 261762.594966 | 156404.686214 |               |
| serialize              | STRUCT2       | array        | False      | 3586126.623874 |                 | 325172.969175  | 371762.982661 | 56056.080075  | 36846.049162  | 322563.440433 |
| serialize              | STRUCT2       | array        | True       | 3306474.506382 |                 | 259863.332448  | 380638.700267 | 60038.879790  | 38183.705811  |               |
| serialize              | STRUCT2       | directBuffer | False      | 2643155.135327 |                 | 355688.882786  | 365317.705376 | 55924.319442  | 37444.967981  | 325093.716261 |
| serialize              | STRUCT2       | directBuffer | True       | 2391110.083108 |                 | 338960.426033  | 370851.880711 | 56674.065604  | 35798.679246  |               |
| serialize              | MEDIA_CONTENT | array        | False      | 3031642.924542 |                 | 730792.521676  | 751892.023189 | 367782.358049 | 137989.198821 | 780618.761219 |
| serialize              | MEDIA_CONTENT | array        | True       | 2250384.600246 |                 | 445251.084327  | 583859.907758 | 329427.470680 | 140260.668888 |               |
| serialize              | MEDIA_CONTENT | directBuffer | False      | 2479862.129632 |                 | 608972.517580  | 728001.080250 | 372477.138150 | 138567.623369 | 805941.345157 |
| serialize              | MEDIA_CONTENT | directBuffer | True       | 1938527.588331 |                 | 359875.473951  | 595679.580108 | 353376.085025 | 140158.673910 |               |
| serialize              | SAMPLE        | array        | False      | 3570966.469087 |                 | 1105365.931217 | 915907.574306 | 220386.502846 | 118374.836631 | 663272.710783 |
| serialize              | SAMPLE        | array        | True       | 1767693.835090 |                 | 734215.482291  | 731869.156376 | 192414.014211 | 119858.140625 |               |
| serialize              | SAMPLE        | directBuffer | False      | 3684487.760591 |                 | 1376560.302168 | 902302.261168 | 220981.308085 | 118273.584257 | 693641.589806 |
| serialize              | SAMPLE        | directBuffer | True       | 1826456.709478 |                 | 932887.968348  | 723614.066770 | 211949.960255 | 108263.040839 |               |
| serialize_compatible   | STRUCT        | array        | False      | 3530406.108869 | 9204444.777172  | 145964.199559  |               | 258650.663523 |               |               |
| serialize_compatible   | STRUCT        | array        | True       | 3293059.098127 | 7064625.291374  | 136180.832879  |               | 263564.913879 |               |               |
| serialize_compatible   | STRUCT        | directBuffer | False      | 2653169.568374 | 11650229.648715 | 106695.800225  |               | 249221.452137 |               |               |
| serialize_compatible   | STRUCT        | directBuffer | True       | 2393817.762938 | 8702412.752357  | 106458.212005  |               | 263623.143601 |               |               |
| serialize_compatible   | STRUCT2       | array        | False      | 2773368.997680 | 2575824.143864  | 125807.748004  |               | 58509.125342  |               |               |
| serialize_compatible   | STRUCT2       | array        | True       | 2564174.550276 | 3543082.528217  | 114983.546343  |               | 55552.977735  |               |               |
| serialize_compatible   | STRUCT2       | directBuffer | False      | 1912402.937879 | 2714748.572248  | 92130.672361   |               | 58908.567439  |               |               |
| serialize_compatible   | STRUCT2       | directBuffer | True       | 1848338.968058 | 1866073.031851  | 88989.724768   |               | 55524.373547  |               |               |
| serialize_compatible   | MEDIA_CONTENT | array        | False      | 1679272.036223 | 2992288.235281  | 188911.259146  |               | 377195.903772 |               |               |
| serialize_compatible   | MEDIA_CONTENT | array        | True       | 1406736.538716 | 2058738.716953  | 145782.916427  |               | 351657.879556 |               |               |
| serialize_compatible   | MEDIA_CONTENT | directBuffer | False      | 1710680.937387 | 2291443.556971  | 185363.714829  |               | 371729.727192 |               |               |
| serialize_compatible   | MEDIA_CONTENT | directBuffer | True       | 1149999.473994 | 1804349.244125  | 142836.961878  |               | 343834.954942 |               |               |
| serialize_compatible   | SAMPLE        | array        | False      | 3604596.465625 | 4409055.687063  | 378907.663184  |               | 234454.975158 |               |               |
| serialize_compatible   | SAMPLE        | array        | True       | 1619648.337293 | 1840705.439334  | 320815.567701  |               | 206174.173039 |               |               |
| serialize_compatible   | SAMPLE        | directBuffer | False      | 3484533.218305 | 5043538.364886  | 296102.615094  |               | 194761.224263 |               |               |
| serialize_compatible   | SAMPLE        | directBuffer | True       | 1730822.630648 | 1859289.705838  | 276757.392449  |               | 212840.483308 |               |               |
| deserialize            | STRUCT        | array        | False      | 4595230.434552 |                 | 607750.343557  | 357887.235311 | 84709.108821  | 29603.066599  | 517381.168594 |
| deserialize            | STRUCT        | array        | True       | 4634753.596131 |                 | 552802.227807  | 353480.554035 | 91050.370224  | 29727.744196  |               |
| deserialize            | STRUCT        | directBuffer | False      | 5012002.859236 |                 | 910534.169114  | 352441.597147 | 91151.633583  | 28717.004518  | 538922.947147 |
| deserialize            | STRUCT        | directBuffer | True       | 4864329.316938 |                 | 914404.107564  | 334574.303484 | 91037.205901  | 29549.998286  |               |
| deserialize            | STRUCT2       | array        | False      | 1126298.359550 |                 | 275984.042401  | 280131.091068 | 69758.767783  | 14888.805111  | 416212.973861 |
| deserialize            | STRUCT2       | array        | True       | 1046649.083082 |                 | 222710.554833  | 260649.308016 | 68616.029248  | 14034.100664  |               |
| deserialize            | STRUCT2       | directBuffer | False      | 1117586.457565 |                 | 319247.256793  | 262519.858810 | 66866.108653  | 14652.043788  | 425523.315814 |
| deserialize            | STRUCT2       | directBuffer | True       | 1018277.848128 |                 | 249105.828416  | 234973.637096 | 65338.345185  | 14425.886048  |               |
| deserialize            | MEDIA_CONTENT | array        | False      | 2054066.903469 |                 | 577631.234369  | 363455.785182 | 118156.072284 | 38536.250402  | 951662.019963 |
| deserialize            | MEDIA_CONTENT | array        | True       | 1507767.206603 |                 | 365530.417232  | 304371.728638 | 120016.594171 | 38957.191090  |               |
| deserialize            | MEDIA_CONTENT | directBuffer | False      | 1502746.028159 |                 | 389473.174523  | 311691.658687 | 111067.942626 | 40512.632076  | 964664.641598 |
| deserialize            | MEDIA_CONTENT | directBuffer | True       | 1290593.975753 |                 | 306995.220799  | 251820.171513 | 121820.821260 | 37030.594632  |               |
| deserialize            | SAMPLE        | array        | False      | 2069988.624415 |                 | 979173.981159  | 473409.796491 | 119471.518388 | 29309.573998  | 619338.385412 |
| deserialize            | SAMPLE        | array        | True       | 1797942.442313 |                 | 716438.884369  | 428315.502365 | 121106.002978 | 27466.003923  |               |
| deserialize            | SAMPLE        | directBuffer | False      | 2229791.078395 |                 | 983538.936801  | 441027.550809 | 117806.916589 | 28128.457935  | 624804.978534 |
| deserialize            | SAMPLE        | directBuffer | True       | 1958815.397807 |                 | 762889.302732  | 420523.770904 | 121940.783597 | 28221.014735  |               |
| deserialize_compatible | STRUCT        | array        | False      | 2110335.039275 | 4978833.206806  | 78771.635309   |               | 88617.486795  |               |               |
| deserialize_compatible | STRUCT        | array        | True       | 2135681.982674 | 4807963.882520  | 72805.937649   |               | 90206.654212  |               |               |
| deserialize_compatible | STRUCT        | directBuffer | False      | 1596464.248141 | 5149070.657830  | 58574.904225   |               | 89580.561575  |               |               |
| deserialize_compatible | STRUCT        | directBuffer | True       | 1684681.074242 | 5137500.621288  | 60685.320299   |               | 84407.472531  |               |               |
| deserialize_compatible | STRUCT2       | array        | False      | 849507.176263  | 1201998.142474  | 60602.285743   |               | 63703.763814  |               |               |
| deserialize_compatible | STRUCT2       | array        | True       | 815120.319155  | 1058423.614156  | 62729.908347   |               | 69521.573119  |               |               |
| deserialize_compatible | STRUCT2       | directBuffer | False      | 784036.589363  | 1131212.586953  | 54637.329134   |               | 69342.030965  |               |               |
| deserialize_compatible | STRUCT2       | directBuffer | True       | 782679.662083  | 1089162.408165  | 51761.569591   |               | 68542.055543  |               |               |
| deserialize_compatible | MEDIA_CONTENT | array        | False      | 1441671.706320 | 2279742.810882  | 180882.860363  |               | 121619.090797 |               |               |
| deserialize_compatible | MEDIA_CONTENT | array        | True       | 1121136.039627 | 1623938.202345  | 154311.211540  |               | 119994.104050 |               |               |
| deserialize_compatible | MEDIA_CONTENT | directBuffer | False      | 1256034.732514 | 1718098.363961  | 134485.160300  |               | 107594.474890 |               |               |
| deserialize_compatible | MEDIA_CONTENT | directBuffer | True       | 1054942.751816 | 1333345.536684  | 119311.787329  |               | 116531.023438 |               |               |
| deserialize_compatible | SAMPLE        | array        | False      | 2296046.895861 | 2485564.396196  | 255086.928308  |               | 121898.105768 |               |               |
| deserialize_compatible | SAMPLE        | array        | True       | 1834139.395757 | 2002938.794909  | 238811.995510  |               | 121297.485903 |               |               |
| deserialize_compatible | SAMPLE        | directBuffer | False      | 2308111.633661 | 2289261.533644  | 201993.787890  |               | 124044.417439 |               |               |
| deserialize_compatible | SAMPLE        | directBuffer | True       | 1820490.585648 | 1927548.827586  | 174534.710870  |               | 120276.449497 |               |               |

### Java Zero-copy

| Benchmark   | array_size | bufferType   | dataType        | Fory           | Kryo           | Fst            |
| ----------- | ---------- | ------------ | --------------- | -------------- | -------------- | -------------- |
| serialize   | 200        | array        | BUFFER          | 5123572.914045 | 1985187.977633 | 2400193.220466 |
| serialize   | 200        | array        | PRIMITIVE_ARRAY | 8297232.942927 | 147342.606262  | 313986.053417  |
| serialize   | 200        | directBuffer | BUFFER          | 5400346.890126 | 1739454.519770 | 2282550.111756 |
| serialize   | 200        | directBuffer | PRIMITIVE_ARRAY | 8335248.350301 | 972683.763633  | 294132.218623  |
| serialize   | 1000       | array        | BUFFER          | 4979590.929127 | 1616159.671230 | 1805557.477810 |
| serialize   | 1000       | array        | PRIMITIVE_ARRAY | 8772856.921028 | 31395.721514   | 67209.107012   |
| serialize   | 1000       | directBuffer | BUFFER          | 5376191.775007 | 1377272.568510 | 1644789.427010 |
| serialize   | 1000       | directBuffer | PRIMITIVE_ARRAY | 8207563.785251 | 209183.090868  | 66108.014322   |
| serialize   | 5000       | array        | BUFFER          | 5018916.322770 | 711287.533377  | 811029.402136  |
| serialize   | 5000       | array        | PRIMITIVE_ARRAY | 8027439.580226 | 6248.006967    | 14997.400124   |
| serialize   | 5000       | directBuffer | BUFFER          | 5330897.682960 | 707092.956534  | 477148.540850  |
| serialize   | 5000       | directBuffer | PRIMITIVE_ARRAY | 7695981.988316 | 43565.678616   | 15000.378818   |
| deserialize | 200        | array        | BUFFER          | 3302149.383135 | 1296284.787720 | 657754.887247  |
| deserialize | 200        | array        | PRIMITIVE_ARRAY | 986136.067809  | 146675.360652  | 219333.990504  |
| deserialize | 200        | directBuffer | BUFFER          | 3113115.471758 | 1004844.498712 | 598421.278941  |
| deserialize | 200        | directBuffer | PRIMITIVE_ARRAY | 991807.969328  | 518713.299422  | 179604.045774  |
| deserialize | 1000       | array        | BUFFER          | 2831942.848999 | 721266.541130  | 422147.154601  |
| deserialize | 1000       | array        | PRIMITIVE_ARRAY | 205671.992736  | 30409.835023   | 53100.903684   |
| deserialize | 1000       | directBuffer | BUFFER          | 3397690.327371 | 592972.713203  | 298929.116572  |
| deserialize | 1000       | directBuffer | PRIMITIVE_ARRAY | 202275.242341  | 112132.004609  | 38572.001768   |
| deserialize | 5000       | array        | BUFFER          | 3296658.120035 | 147251.846111  | 136934.604328  |
| deserialize | 5000       | array        | PRIMITIVE_ARRAY | 40312.590172   | 6122.351228    | 10672.872798   |
| deserialize | 5000       | directBuffer | BUFFER          | 3284441.570594 | 148614.476829  | 77950.612503   |
| deserialize | 5000       | directBuffer | PRIMITIVE_ARRAY | 40413.743717   | 21826.040410   | 8561.694533    |
