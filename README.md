<p align="center"><img src="https://raw.githubusercontent.com/jarrunner/jr/main/icon/jr-icon.svg" width="96" alt="jr"></p>

# jr-maven-plugin

Give your Java app its own launcher from `mvn package`: a Windows exe and a macOS binary or app, with your name, icon and version, run by [jr](https://github.com/jarrunner/jr).

- **One file to ship.** The exe carries the app's settings (Java version, JVM options, arguments). jr checks them while the build runs, so a bad setting fails the build rather than your user's launch.
- **Java and the jar arrive by themselves.** jr finds or downloads Java. A release exe can fetch its jar on first run and check it against the SHA-256 baked in.
- **Updates.** With an `updateUrl`, `myapp -Xjr:update` moves the app to its next version, even while it runs.
- **Windows and macOS from one build**, on your machine or in CI.

**Try it:** add the plugin block below after your shade plugin and run `mvn package`. `target/jr/<artifactId>.exe` runs the jar you just built. The [tutorial](docs/tutorial.md) takes you from there to releases, updates and GitHub Actions.

[Tutorial](docs/tutorial.md) · [Parameters](#parameters) · [jr](https://github.com/jarrunner/jr) · [jr-apputils](https://github.com/jarrunner/jr-apputils) · [jarrunner.github.io](https://jarrunner.github.io)

## How it works

The plugin builds a [jr](https://github.com/jarrunner/jr) launcher exe for a Java app as part of its Maven build: `io.github.jarrunner:jr-maven-plugin`, goal `jr:exe`, bound to `package` by default.

It hashes the app's shaded jar (SHA-256 and CRC32), writes the jrc-json, and has jr itself check and bake that config, the icon and the version info into a copy of the jr exe bundled in the plugin. The result is one exe that carries everything it needs: its config cannot be overridden by a file beside it, and a release exe fetches its jar on first run, verifies it, and updates itself with `-Xjr:update`.

The Windows exes are stamped through Windows' own resource calls, so they are built on Windows (on another OS that part logs a warning and is skipped); the macOS outputs (see below) are built on any OS. On Maven Central from 1.1.0 (`io.github.jarrunner:jr-maven-plugin:1.1.0`, which bundles jr 1.1.0); `1.2.0-SNAPSHOT` is on the Central snapshot repository. The plugin bundles the jr binaries of one jr release, without the jr icon: the build downloads them from [jr's releases](https://github.com/jarrunner/jr/releases) and checks each against a SHA-256 sum written in the pom (`jr.release` and the `jr.sha256.*` properties), so `mvn install` needs nothing else. This version bundles jr 1.4.0-beta.3.

New to it? [docs/tutorial.md](docs/tutorial.md) walks through a Windows exe and a macOS binary from one build, icons for both, releases and updates, and GitHub Actions.

## Use

Declare it after the shade plugin, so it hashes the jar shade just wrote:

```xml
<plugin>
  <groupId>io.github.jarrunner</groupId>
  <artifactId>jr-maven-plugin</artifactId>
  <version>1.1.0</version>
  <executions><execution><goals><goal>exe</goal></goals></execution></executions>
  <configuration>
    <icon>${project.basedir}/app.ico</icon>
    <javaVersion>25+</javaVersion>
    <jvmMode>dll</jvmMode>
    <jarUrl>https://github.com/OWNER/REPO/releases/download/v${project.version}/app.jar</jarUrl>
    <updateUrl>https://github.com/OWNER/REPO/releases/latest/download/app.update.json</updateUrl>
  </configuration>
</plugin>
```

```
mvn package                          target/jr/<name>.exe runs the jar just built (source=path)
mvn -Djr.source=url package          a release exe: fetches jarUrl on first run
mvn -Djr.installDir=<dir> package    also copies the exe to <dir>
```

`installDir` is meant to come from outside the project (`-D`, or a profile in `~/.m2/settings.xml`), never from a pom, so a personal tools folder never ends up in a public repository. An exe there that is running is renamed to `<exe>.jr-replaced` first, which jr deletes on its next start.

**A trap met in practice:** if the plugin is inherited from a parent pom, bind it to `verify` rather than `package`: an inherited plugin runs before the module's own shade plugin in the same phase, and would hash the previous jar.

## Parameters

- `jar` (`jr.jar`): the jar to launch, default `target/<finalName>.jar`.
- `name` (`jr.name`): the exe's name, default the artifactId.
- `source` (`jr.source`): where the exe finds its jar: `path` (default, the local build), `maven`, or `url`. With `maven` or `url` the exe downloads the jar and checks it against the baked SHA-256.
- `jarPath`, `mavenCoords` (default the project's own coordinates), `jarUrl`: the location for each source. `jarUrl` may contain `{sha8}`, replaced by the first 8 hex digits of the jar's SHA-256 (`app-win-{sha8}.jar` -> `app-win-a0c0b76e.jar`): every build of the jar then has its own file name, so a stale exe asks for a file that is not there instead of downloading another build and failing its check, and the release page shows which jar belongs to which exe. The release folder uses the resolved name.
- `jarVerify` (`jr.jarVerify`): the check on every run for a downloaded jar: `crc32` (default, against the CRC32 baked into the exe), `sha256`, or `none`.
- `appId` (default `groupId:artifactId`), `appVersion` (default the project version): the update feature matches on these.
- `updateUrl`, `updateChannel`: where `-Xjr:update` and `-Xjr:update-check` read the update file (format in jr's README), and which channel.
- `javaVersion` (`25`; `25` and `25+` both mean at least 25 with 25 preferred), or `javaMin` / `javaPreferred` / `javaMax` (numbers, not together with `javaVersion`); `javaType` (`jre`/`jdk`), `javaHome`, `javaAutoinstall`. jr uses an installed Java of exactly the preferred version, else downloads it, else the nearest installed one within min/max. AOT (on unless `aot` is false) needs Java 25, so it raises a lower version to 25 with a build warning. Contradictions (min above max, a max below 25 with AOT) fail the build. Rules and reasons: jr's PRP-31.
- `supportName`, `supportEmail`, `supportIssues`, `supportUrl`: who supports the app, shown in jr's error dialog (Email and Report issue buttons, which open a pre-filled mail or GitHub issue) and in `-Xjr:doctor`. Each defaults from the pom: `<organization><name>` or the first developer's name, the first developer's email, `<issueManagement><url>`, `<url>`.
- `jvmMode` (`dll` runs the JVM inside the exe, so the process carries the app's name), `vmArgs` (a list), `appArgs` (a list), `javaArgs` (instead of a jar, for a classpath launch), `aot`.
- `icon` (a 256px PNG-compressed entry; if the small caption icon looks smeared, add hand-drawn 16-32px entries case by case, see jr's README and `icon/jr-icon.ico`), `fileDescription` (what Task Manager shows, default the name), `productName`, `companyName`, `copyright`.
- `architectures`: `x86_64` (default) and/or `arm64`; with several, the exes are named `<name>-windows-<arch>.exe`.
- `outputDirectory` (default `target/jr`), `installDir` (`jr.installDir`), `skip` (`jr.skip`).

## macOS (1.2.0)

`platforms` (`jr.platforms`): `windows` (default) and/or `macos`. The Windows exes are stamped with Windows' own resource calls, so they are built only on Windows. The macOS outputs are built on any OS, Windows and CI included, and land in `target/jr/macos/`:

- `macosForms` (`jr.macosForms`): `binary` (default) and/or `app`.
  - `binary` is one file, `<name>`, with the app's config embedded in it. It copies and installs like any command-line tool. Since PRP-42 it also carries its app: the Info.plist, PkgInfo and the icon (one PNG image, 512 px if it fits beside the config, else 256 px; a 512 px `.png` given as the icon is used byte for byte, so a hand-optimized palette PNG stays small). `<name> -Xjr:install` writes `~/Applications/<Display Name>.app` from them, copies the binary into it, and links `~/.local/bin/<name>` to that copy, so one file is both the app (Dock, Finder, Launchpad) and the command. After `-Xjr:update` replaces that binary, its next launch rewrites the bundle's files from it. The same files go into the `app` form's binary, with the same Info.plist, so jr leaves a plugin-built bundle as it is.
  - `app` is `<Display Name>.app` (Info.plist, the binary, the icon) plus `<name>-<version>-macos.zip` of it, which is the single file to download. The zip keeps the executable bit. The app never carries the jar: as on Windows, `source=path` names the build's own jar (or `jarPath`), and `maven`/`url` are downloaded by jr into its cache, so a new jar needs no new app. For an app meant to run on other Macs, use `maven` or `url`. When the build runs on a Mac, Apple's `codesign` seals the bundle ad hoc; built elsewhere, the app runs the same but `codesign -v` reports it as unsealed.
- `macosArchitecture` (`jr.macosArchitecture`): `arm64`, `x86_64`, or `universal` (default; one fat binary for both).
- `macosIcon`: a `.png`, `.icns`, or PNG-entry `.ico`; default `icon`. `macosNoIcon` (`jr.macosNoIcon`) leaves the icon out. Inside the app, jr passes `-Xdock:name` and `-Xdock:icon` to Java, so the Dock shows the app's name and icon rather than "java".
- `macosDisplayName` (default `productName`, else `name`), `macosBundleId` (default `appId` with `:` as `.`). The app needs macOS 13 or later.
- With `source=path` and no `jarPath`, a `binary` built on a Mac runs the build's own jar, as on Windows. Built anywhere else it expects `<jar name>` beside itself.
- How the config gets in: jr's macOS build carries an empty `__DATA,__jrc` section (32 KB since PRP-42; jr 1.3.1 and earlier: 16 KB). The plugin writes the config there, followed by the app's bundle files (below), and recomputes the affected page hashes of the binary's ad-hoc signature, so the result runs on Apple silicon without a Mac in the build. jr reads its config from there and from nowhere else, as on Windows.
- Signing: ad hoc only, with no Apple Developer ID. A copy downloaded with a browser is blocked by Gatekeeper until the user allows it (System Settings, Privacy & Security, Open Anyway). Installing with `curl` avoids that. See jr's `docs/macos.md`.
- In a release, the binary and the app's zip are published, listed in `SHA256SUMS`, and written into the update file (PRP-42): the binary under `exe`, the zip under `app`, each keyed `macos-<architecture>` (`macos-universal`, `macos-arm64`, `macos-x86_64`). `-Xjr:update` on a Mac replaces a bare binary in place, and an app as a whole bundle (see jr's `docs/development.md`, self-update). Needs a jr release whose macOS build has `-Xjr:update` (after 1.3.1).

## The release folder (1.2.0)

For a release build (`source=url`, or `release=true`) the goal also writes everything a release publishes into `releaseDirectory` (default `target/jr/release`, emptied first), so no script has to assemble it:

- the exe(s), and the jar under the file name its `jarUrl` downloads, copied from the very jar whose SHA-256 the exe carries;
- `version.txt` (the version, no newline) and any `releaseFiles` (each must exist, or the build fails naming it);
- `SHA256SUMS`, `<sha256>  <name>` per file, sorted, LF: the format `sha256sum` writes;
- `<name>.update.json`, jr's update format 1: this release first, with each exe's SHA-256 and its url (`releaseBaseUrl`, default `jarUrl`'s folder, plus the exe's name), the channel (`updateChannel`, default `stable`) set to this version, and, from `updateMergeFrom`, the channels and other releases of the previous update file. Publish it at `updateUrl`.

Parameters: `release` (`jr.release`), `releaseDirectory`, `releaseBaseUrl` (`jr.releaseBaseUrl`), `releaseFiles` (a list), `updateMergeFrom` (`jr.updateMergeFrom`).

**One jar per platform.** When the jar carries one platform's natives (JavaFX does), build once per jar: for example `-Pwin` for the Windows exe, then the Apple silicon jar with `-Djr.platforms=macos -Djr.macosArchitecture=arm64`, then the Intel one with `x86_64`, each run with `-Djr.updateMergeFrom=<the update file of the run before>`. A run whose version is already in that file keeps that release's platforms and adds its own, so the last run's update file lists them all. Gather the runs' release folders into one GitHub release. A single-architecture macOS binary and zip carry the architecture in their release names (`myapp-macos-arm64`, `myapp-1.0-macos-arm64.zip`), so both fit in one release; a universal build keeps `myapp` and `myapp-1.0-macos.zip`. jr on a Mac takes `macos-universal` if the release has it, else its own architecture's entry. When the jar is the same for every platform, one run with the default universal binary is all it takes.

The jrc-json it writes is kept beside the exe as `<name>.jrc.json`, for review; the exe never reads it from there.

The app receives its whole config as `-Dio.github.jarrunner.jr.<path>` properties; [jr-apputils](https://github.com/jarrunner/jr-apputils) reads them (`JrApp`) and gives the app its own update and install commands.
