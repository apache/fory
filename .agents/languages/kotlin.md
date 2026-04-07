# Kotlin

Load this file when changing `kotlin/`.

## Rules

- Run Kotlin Maven commands from within `kotlin/`.
- Kotlin serializers build on the Java implementation. If Java changed and the updated Java artifacts are not installed yet, run `cd ../java && mvn -T16 install -DskipTests` first.

## Commands

```bash
# Build
mvn clean package

# Run tests
mvn test
```
