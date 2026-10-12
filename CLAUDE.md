# ExyliaProxyUtils

- **Releases are automatic.** `.github/workflows/release.yml` publishes a GitHub release on every push to `main` whose `build.gradle` version has no release yet. Every finished change bumps `version` in `build.gradle` (patch for fixes, minor for features, major for a breaking config or protocol change) before the commit is pushed; a push without a bump ships nothing.
- **Proxies update themselves** (`common/.../Updater.java`): at start and at stop they follow `releases/latest/download/ExyliaProxyUtils.jar`, and a newer release of the same major replaces the running jar for the next restart. New majors are only logged. Every release must keep attaching that fixed-name asset.
