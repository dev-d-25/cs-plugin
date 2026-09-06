# cs-plugin

CloudStream extensions for MoviesMod and MoviesLeech.
Kotlin, built with the standard CloudStream gradle plugin. Outputs `.cs3` files.

## Install in CloudStream (for users)

1. Update CloudStream to the latest pre-release build.
2. Go to Settings > Extensions > Add Repository and paste this URL:

```
https://raw.githubusercontent.com/dev-d-25/cs-plugin/builds/repo.json
```

3. Open ModSuite Repo and install MoviesMod and MoviesLeech.
4. On Home, switch the provider selector to one of them and search.

## Modules

- `MoviesModProvider` - https://moviesmod.zone/
- `MoviesLeechProvider` - https://moviesleech.art/

## Dev

1. Open this folder in Android Studio.
2. Run `./gradlew MoviesModProvider:make` or `deployWithAdb` with a device attached.
3. Push to `main`. GitHub Action builds `.cs3` + `plugins.json` to the `builds` branch.
4. In CloudStream go to Settings > Extensions > Add Repository and paste your `repo.json` raw URL.

### Deterministic local loop (MoviesLeech first — see `guide/plan.md`)

The default `java` here is newer than AGP 8.7 supports, so always build
on Java 17 with the Android SDK visible. `scripts/build.sh` pins both
when the known local paths exist (CI sets its own JDK/SDK):

```bash
./scripts/build.sh :MoviesLeechProvider:compileDebugKotlin   # compile one module
./scripts/build.sh :MoviesLeechProvider:testDebugUnitTest    # parser/resolver unit tests (needs network once for JUnit)
./scripts/build.sh :MoviesLeechProvider:lintDebug            # Android lint (clean)
./scripts/build.sh :MoviesLeechProvider:make                 # build the .cs3
./scripts/verify-cs3.sh                                      # check every .cs3 manifest
```

Equivalent raw form (what CI does):

```bash
JAVA_HOME=/usr/lib/jvm/java-17-temurin-jdk \
./gradlew --offline --no-daemon \
  :MoviesLeechProvider:compileDebugKotlin \
  :MoviesLeechProvider:make
```

Unit tests live next to the pure seams they cover
(`MoviesLeechParser`, `EpisodePayload`, `LinkResolver`,
`CloudGateBypass`) with fixtures under
`MoviesLeechProvider/src/test/resources/fixtures/`. They assert
user-visible results: named mirrors per quality/server, S01E01 episodes
carrying only their own links, and one VIDEO link per resolved mirror.

### Playback notes (verified on-device)

- Resolved links are direct `video-downloads.googleusercontent.com`
  files. **Download and Play-with-MPV work.** CloudStream's built-in
  player can stall (spinner, 00:00): the file host ignores HTTP Range
  requests, and the player seeks the MKV index (`MediaHTTPConnection:
  readAt … ProtocolException` in logcat). That is a server limitation,
  not a plugin bug — use download or an external player for these links.
- `MoviesModProvider` shares the same architecture (seams copied,
  parser/provider retargeted to `moviesmod.zone`/`links.modpro.blog`).
  Shared files carry a "keep in sync" header.

Replace `YOUR_GITHUB_USER` in `repo.json` and in root `build.gradle.kts` cloudstream block after you create the GitHub repo.

## Notes

Both sites are WordPress. Search is `?s=query`. Detail pages link out to an intermediate host (links.modpro.blog style) then to HubCloud / FilePress / VCloud. See provider files for selectors.
