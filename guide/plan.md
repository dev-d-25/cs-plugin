# CloudStream v3 plugin diagnosis and implementation plan

## Current conclusion

The project can build locally. The main failure is in runtime link resolution and deployment, not in the basic CloudStream v3 packaging.

Verified command:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-temurin-jdk \
./gradlew --offline --no-daemon \
  :MoviesLeechProvider:compileDebugKotlin \
  :MoviesLeechProvider:make
```

Result: `BUILD SUCCESSFUL`.

The generated files are valid `.cs3` packages with version `4`.

## What went wrong in the previous OpenCode session

### 1. The wrong feedback loop was used

The link chain was tested with Python and a browser, but not with CloudStream's Android HTTP path. A browser succeeding does not prove that `NiceHttp`, cookies, redirects, headers, and `loadLinks()` work inside the app.

The official CloudStream documentation identifies video-link loading as the hardest and most important provider stage:

<https://recloudstream.github.io/csdocs/devs/create-your-own-providers/>

The previous session worked on two sites and a complex verification bypass before creating a deterministic test for one source.

### 2. Series archive loading has a broken fallback

`expandArchive()` uses `app.get()` to parse an archive page that was observed to populate its episode links through JavaScript.

When the static request returns no episodes:

1. The provider creates a fallback episode pointing to the detail page.
2. `loadLinks()` scans the detail page.
3. Archive URLs are passed to generic `loadExtractor()`.
4. The provider's custom archive resolver is skipped.
5. CloudStream reports `No links found`.

Relevant code:

- `MoviesLeechProvider.kt`, `expandArchive()` around line 64
- `MoviesLeechProvider.kt`, series construction around line 114
- `MoviesLeechProvider.kt`, `loadLinks()` around line 453

### 3. Errors are swallowed

Most resolver stages use empty catches such as:

```kotlin
catch (e: Exception) {
    false
}
```

This makes all failures look identical:

- bad selector;
- expired token;
- missing cookie;
- malformed redirect;
- HTTP 403;
- JS-only page;
- unsupported final host.

The app receives no useful diagnostic information.

### 4. The bypass parser is too brittle

`bypassCloudLink()` depends on exact HTML formatting:

- exact form attribute order;
- exact quote style;
- exact hidden-input order;
- exact single-quoted `s_343(...)` syntax;
- exact redirect format.

Any small server-side change silently returns `null`.

### 5. The Android path was never proven

The previous session claimed that token encoding and cookie handling were fixed based on Python/browser tests. Those tests did not exercise the same Android request client used by the plugin.

The current code also hides failures at the exact boundaries where diagnostics are needed: POST response parsing, cookie propagation, redirect resolution, final-link extraction, and callback emission.

### 6. Movie mirrors are discarded

Movie loading currently stores only the first raw link:

```kotlin
val data = rawLinks.firstOrNull()?.second ?: url
```

That means the other qualities and servers cannot appear in the CloudStream mirror picker.

### 7. The repository publishing path is incomplete

`repo.json` points to:

```text
https://raw.githubusercontent.com/dev-d-25/cs-plugin/builds/plugins.json
```

The current `builds` branch does not contain `plugins.json` or the generated `.cs3` files. Automatic installation and updating therefore cannot be trusted yet.

Relevant file:

- `repo.json`, line 5

### 8. Java selection is not deterministic

Java 17 exists at `/usr/lib/jvm/java-17-temurin-jdk`, while the default `java` command still reports Java 25. The build succeeds when `JAVA_HOME` is explicitly set.

The project should configure Java 17 explicitly instead of relying on the current shell environment.

## Recommended implementation order

### Phase 1: Make local development deterministic

Keep the official CloudStream plugin-template structure, but make the local toolchain explicit.

Add or configure:

- Java 17 path;
- Android SDK path;
- Gradle wrapper;
- offline-friendly dependency cache;
- one-module build commands;
- Android lint;
- generated `.cs3` manifest verification.

Fast local loop:

```bash
./gradlew :MoviesLeechProvider:compileDebugKotlin --offline --no-daemon
./gradlew :MoviesLeechProvider:lintDebug --offline --no-daemon
./gradlew :MoviesLeechProvider:make --offline --no-daemon
```

GitHub Actions should publish builds only after the local loop is green. The official template recommends starting from the CloudStream test-plugin repository and enabling workflow write access:

<https://recloudstream.github.io/csdocs/devs/using-plugin-template/>

### Phase 2: Work on one site only

Start with `MoviesLeechProvider`.

The first milestone should support only:

1. one movie;
2. one series;
3. one known quality;
4. one known source;
5. one final playable/downloadable link.

Do not debug both websites, every quality, every mirror, archive expansion, and the verification gate simultaneously.

### Phase 3: Create testable seams

Split the provider into small components:

- `MoviesLeechParser` for search, detail, movie, season, and episode HTML;
- `SourceCandidate` for quality, server name, URL, and source kind;
- `LinkResolver` for archive, redirect, and final-link stages;
- `PageClient` for GET, POST, cookies, headers, and redirects.

The HTTP client should be injectable in tests. The parser and resolver should be testable without CloudStream or a live website.

Add fixtures under `src/test/resources/fixtures/` for:

- search page;
- movie detail page;
- series detail page;
- archive page;
- redirect response;
- cookie-setting response;
- final file/seed page.

Each test must assert the user-visible result, not merely that a request completed:

- movie produces at least one `ExtractorLink`;
- every expected quality is preserved;
- series produces correct seasons and episodes;
- selected episode produces only that episode's links;
- callback receives a `VIDEO` link with the expected quality and name.

CloudStream's `loadLinks()` API is callback-based; a link is useful only when the callback receives an `ExtractorLink`:

<https://recloudstream.github.io/dokka/library/com.lagradost.cloudstream3/-main-a-p-i/index.html>

### Phase 4: Use structured episode data

Do not encode multiple sources using:

```text
quality|url|||quality|url
```

URLs and tokens can contain separator characters. Use a small JSON payload instead, for example:

```json
{
  "sources": [
    {"quality": 1080, "server": "Fast Server", "url": "..."},
    {"quality": 720, "server": "Server 2", "url": "..."}
  ]
}
```

For series, each `Episode` should carry its own structured source list.

### Phase 5: Implement movies first

Movie acceptance criteria:

- all available qualities are retained;
- all usable servers are retained;
- ZIP/batch links are excluded;
- every mirror is emitted separately;
- mirror names are readable;
- the final URL is a direct video/download URL;
- a small `HEAD` or byte-range check confirms the final URL without downloading a multi-gigabyte file.

Expected mirror names should look like:

```text
MoviesLeech · 1080p · Fast Server
MoviesLeech · 720p · Server 2
```

### Phase 6: Add series support

Series acceptance criteria:

- every season is parsed;
- every episode has correct season and episode numbers;
- episode names are shown as `S01E01`, `S01E02`, etc.;
- episode posters are preserved when available;
- missing qualities do not remove the entire episode;
- different episode counts across sources are handled safely;
- only the selected episode's links are resolved.

### Phase 7: Handle the verification gate separately

Do not make the verification/ad gate the first milestone. First prove the provider architecture with a permitted direct source, captured sanitized fixtures, or a local mock server.

If the live source requires browser JavaScript, human verification, or unstable cookie behavior, treat that as a separate compatibility adapter. Do not hide it inside a generic `loadLinks()` catch block or claim it works until the Android path is tested.

### Phase 8: Repair publishing last

After local runtime tests pass:

1. build both `.cs3` files;
2. generate `plugins.json`;
3. publish the files to `builds`;
4. verify every raw GitHub URL returns HTTP 200;
5. install from the repository in CloudStream;
6. test update/version changes.

## User-visible contract

| CloudStream location | What should be visible |
|---|---|
| Home/search | Poster, title, and movie/series type |
| Movie details | Metadata and Play Movie |
| Series details | Correct seasons and episode list |
| Download mirror picker | One named entry per quality/server |
| Player/download | Direct `VIDEO` link with required referer/headers |

## Edge cases to test

- relative, absolute, and redirected URLs;
- missing posters or titles;
- duplicate cards and duplicate mirrors;
- batch/ZIP links mixed with playable links;
- missing quality labels;
- 4K/2160p naming variants;
- multiple seasons and specials;
- unequal episode counts;
- empty archive responses;
- JavaScript-only episode lists;
- expired or single-use tokens;
- cookies set at different redirect stages;
- URL-encoded query parameters;
- 403, 404, timeout, and rate-limit responses;
- final links requiring a referer;
- download links that stream but do not support resume.

## Success definition for the first working version

The first version is successful when one MoviesLeech movie and one MoviesLeech series pass the local parser/resolver tests, produce named mirrors in CloudStream, and remain installable from a verified local `.cs3` file. Only then should the second site or additional mirror logic be added.

