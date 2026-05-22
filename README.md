# Gallery

GrapheneOS Gallery app.

This project is a fork of [ReFra](https://github.com/IacobIonut01/Gallery), originally developed
by [IacobIonut01](https://github.com/IacobIonut01).

## About

Gallery is a modern Android media gallery built with Jetpack Compose. It supports browsing photos and videos, albums,
media details, search, editing, trash, favorites, widgets, and other media-management features.

## Development

### Building

GrapheneOS Gallery is built without all files access and maps, but with ML models.

To quickly build and stage the release version in the GOS tree, use `scripts/build-and-copy-to-gos.sh`.

Before running, do `ln -s "$HOME/.android/debug.keystore" app/release_key.jks` to use your debug key for release signing
or create a keystore in `app/release_key.jks`.

Set `GOS_ROOT` env variable pointing to the root of your GrapheneOS checkout.

### Syncing with the upstream

#### Resources overrides

Gallery has custom resource overrides for certain features and UI elements located in `app/src/gos`.

After syncing with the upstream, make sure that all strings are overridden correctly (for example, upstream may
introduce new translations). To do this, use `./gradlew :app:checkGosStringOverrides --no-daemon`.

### Running CTS tests

```shell
atest --test-mapping external/Gallery:all
```

## License

This project is licensed under the Apache License 2.0. See [LICENSE](LICENSE).
