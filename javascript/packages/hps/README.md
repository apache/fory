# Apache Fory™ JavaScript

Node.js high-performance suite, ensuring that your Node.js version is 20 or later.

`hps` is use for detect the string type in v8. Fory support latin1 and utf8 string both, we should get the certain type of string before write it
in buffer. It is slow to detect the string is latin1 or utf8, but hps can detect it by a hack way, which is called FASTCALL in v8.
so it is not stable now.
