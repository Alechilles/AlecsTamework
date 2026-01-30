# .Alec's Tamework!

temp

## Building

```bash
./mvnw clean package
```

Or on Windows:

```cmd
mvnw.cmd clean package
```

## Development

### Run Server with Plugin

```bash
./mvnw clean package -Prun-server
```

This will build your plugin, copy it to the server's mods folder, and start the Hytale server.

### Install Plugin Only (Hot Reload)

```bash
./mvnw clean package -Pinstall-plugin
```

This builds and copies the plugin to the server without starting it.

## Requirements

- **JDK 25** - Required for Maven and compilation
- Maven 3.9.9 or newer (included via wrapper)
- Hytale Server installation

The Hytale installation path is configured in `pom.xml` properties.

## License

GPL-3.0

## Author

Alec
