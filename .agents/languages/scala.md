# Scala

Load this file when changing `scala/`.

## Rules

- Run Scala commands from within `scala/`.
- Scala serializers build on the Java implementation. If Java changed and the updated Java artifacts are not installed yet, run `cd ../java && mvn -T16 install -DskipTests` first.

## Commands

```bash
# Compile
sbt compile

# Run tests
sbt test

# Format code
sbt scalafmt
```
