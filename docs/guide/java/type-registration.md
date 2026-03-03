---
title: Type Registration & Security
sidebar_position: 3
id: type_registration
license: |
  Licensed to the Apache Software Foundation (ASF) under one or more
  contributor license agreements.  See the NOTICE file distributed with
  this work for additional information regarding copyright ownership.
  The ASF licenses this file to You under the Apache License, Version 2.0
  (the "License"); you may not use this file except in compliance with
  the License.  You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
---

This page covers class registration mechanisms and security configurations.

## Class Registration

`ForyBuilder#requireClassRegistration` can be used to disable class registration. This will allow deserializing objects of unknown types, which is more flexible but **may be insecure if the classes contain malicious code**.

**Do not disable class registration unless you can ensure your environment is secure**. Malicious code in `init/equals/hashCode` can be executed when deserializing unknown/untrusted types when this option is disabled.

Class registration can not only reduce security risks, but also avoid classname serialization cost.

### Register by ID

You can register class with API `Fory#register`:

```java
Fory fory = xxx;
fory.register(SomeClass.class);
fory.register(SomeClass1.class, 1);
```

Note that class registration order is important. Serialization and deserialization peers should have the same registration order.

Internal type IDs 0-32 are reserved for built-in xlang types. Java native built-ins start at
`Types.NONE + 1`, and user IDs are encoded as `(user_id << 8) | internal_type_id`.

### Register by Name

Register class by ID will have better performance and smaller space overhead. But in some cases, management for a bunch of type IDs is complex. In such cases, registering class by name using API `register(Class<?> cls, String namespace, String typeName)` is recommended:

```java
fory.register(Foo.class, "demo", "Foo");
```

If there are no duplicate names for types, `namespace` can be left as empty to reduce serialized size.

**Do not use this API to register class since it will increase serialized size a lot compared to registering class by ID.**

## Security Configuration

### Class Checker

If you invoke `ForyBuilder#requireClassRegistration(false)` to disable class registration check, you can set `org.apache.fory.resolver.ClassChecker` by `ClassResolver#setClassChecker` to control which classes are allowed for serialization.

For example, you can allow classes started with `org.example.*`:

```java
Fory fory = xxx;
fory.getClassResolver().setClassChecker(
  (classResolver, className) -> className.startsWith("org.example."));
```

### AllowListChecker

Fory provides a `org.apache.fory.resolver.AllowListChecker` which is an allowed/disallowed list based checker to simplify the customization of class check mechanism:

```java
AllowListChecker checker = new AllowListChecker(AllowListChecker.CheckLevel.STRICT);
ThreadSafeFory fory = new ThreadLocalFory(classLoader -> {
  Fory f = Fory.builder().requireClassRegistration(true).withClassLoader(classLoader).build();
  f.getClassResolver().setClassChecker(checker);
  checker.addListener(f.getClassResolver());
  return f;
});
checker.allowClass("org.example.*");
```

You can use this checker or implement a more sophisticated checker by yourself.

## Limit Max Deserialization Depth

Fory provides `ForyBuilder#withMaxDepth` to limit max deserialization depth. The default max depth is 50.

If max depth is reached, Fory will throw `ForyException`. This can be used to prevent malicious data from causing stack overflow or other issues.

```java
Fory fory = Fory.builder()
  .withLanguage(Language.JAVA)
  .withMaxDepth(100)  // Set custom max depth
  .build();
```

## Best Practices

1. **Always enable class registration in production**: Use `requireClassRegistration(true)`
2. **Use ID-based registration for performance**: Numeric IDs are faster than string names
3. **Maintain consistent registration order**: Same order on both serialization and deserialization sides
4. **Set appropriate max depth**: Prevent stack overflow attacks
5. **Use AllowListChecker for fine-grained control**: When you need flexible class filtering

## Related Topics

- [Configuration](configuration.md) - ForyBuilder security options
- [Custom Serializers](custom-serializers.md) - Register custom serializers
- [Troubleshooting](troubleshooting.md) - Common registration issues
