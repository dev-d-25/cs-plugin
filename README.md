# cs-plugin

CloudStream extensions for MoviesMod and MoviesLeech.
Kotlin, built with the standard CloudStream gradle plugin. Outputs `.cs3` files.

## Modules

- `MoviesModProvider` - https://moviesmod.zone/
- `MoviesLeechProvider` - https://moviesleech.art/

## Dev

1. Open this folder in Android Studio.
2. Run `./gradlew MoviesModProvider:make` or `deployWithAdb` with a device attached.
3. Push to `main`. GitHub Action builds `.cs3` + `plugins.json` to the `builds` branch.
4. In CloudStream go to Settings > Extensions > Add Repository and paste your `repo.json` raw URL.

Replace `YOUR_GITHUB_USER` in `repo.json` and in root `build.gradle.kts` cloudstream block after you create the GitHub repo.

## Notes

Both sites are WordPress. Search is `?s=query`. Detail pages link out to an intermediate host (links.modpro.blog style) then to HubCloud / FilePress / VCloud. See provider files for selectors.
