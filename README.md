# jfs

an java file system to store smallfile like image or other

`jfs` packs many small files (images or other blobs) into a single append-only
container file, keeping an in-memory index of the latest live record per id. Writes
are sequential; reads are a single seek using the index. Records carry a CRC32 so
corruption is detected on read.

## Requirements

- JDK 21
- Maven 3.9+

Cloud Agents get this toolchain automatically from `.cursor/environment.json`
(a `maven:3.9.11-eclipse-temurin-21` image). Locally, install a JDK 21 and Maven.

## Build and test

```bash
mvn -B -ntp clean package
```

This compiles the code, runs the JUnit test suite, and produces a runnable jar at
`target/jfs.jar`.

## Run the demo

The demo stores a tiny PNG and a text note, reads them back with integrity checks,
reopens the store to prove on-disk persistence, and deletes a record:

```bash
java -jar target/jfs.jar          # uses a temp directory
java -jar target/jfs.jar ./mydata # or a directory of your choice

# or, without building a jar:
mvn -q -ntp exec:java
```

## Library usage

```java
import com.github.jfs.Jfs;
import java.nio.file.Path;

try (Jfs store = Jfs.open(Path.of("data"))) {
    String id = store.put(imageBytes); // auto-generated id
    store.put("logo.png", otherBytes); // explicit id
    byte[] back = store.get(id);
    store.delete("logo.png");
}
```
