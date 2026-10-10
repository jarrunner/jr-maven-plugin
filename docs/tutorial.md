# Tutorial: one Java app, a Windows exe and a macOS binary, with icons and updates

This walks through giving a Java app what a native app has: one file per OS, an icon, a version, and self-update. It assumes a Maven project whose build already writes a shaded (runnable) jar. Every parameter is in the [README](../README.md); this page is the order to do things in, and the reasons.

## 1. What you get

- **Windows:** `target/jr/myapp.exe`. It carries its config, icon, version info and an application manifest. On first run it downloads its jar (a release build) and Java if none is found.
- **macOS:** `target/jr/macos/myapp`, one universal binary (Apple silicon and Intel). It carries its config and its own `.app` (Info.plist and icon), so `myapp install`, or jr's `myapp -Xjr:install`, gives it a Dock icon and a place in Launchpad, and it stays a terminal command. With `macosForms` set to `app` you also get `My App.app` and a zip of it.
- **Both:** a release folder (`target/jr/release/`) with everything to publish: exes, jar, `SHA256SUMS`, and the update file that `-Xjr:update` reads.

## 2. Which machine builds what

- **macOS outputs build on any OS:** Windows, Linux, macOS and CI. The plugin writes the config into a section of jr's macOS binary and fixes the binary's ad-hoc signature itself, so no Mac and no Apple tools are needed.
- **Windows exes build on Windows only:** they are stamped through Windows' own resource calls (by jr itself). On another OS the Windows part logs a warning and is skipped.

So **a Windows machine, or a `windows-latest` CI runner, builds everything in one `mvn package`**. A Mac is needed only to seal an `.app` bundle with `codesign` (optional: an unsealed app runs the same) and to look at the result with your own eyes.

## 3. The pom

```xml
<plugin>
  <groupId>io.github.jarrunner</groupId>
  <artifactId>jr-maven-plugin</artifactId>
  <version>1.2.0-SNAPSHOT</version>
  <executions><execution><goals><goal>exe</goal></goals></execution></executions>
  <configuration>
    <platforms><platform>windows</platform><platform>macos</platform></platforms>
    <icon>${project.basedir}/icon/myapp.ico</icon>
    <macosIcon>${project.basedir}/icon/myapp-512.png</macosIcon>
    <productName>My App</productName>
    <appId>com.example:myapp</appId>
    <javaVersion>25+</javaVersion>
    <jvmMode>dll</jvmMode>
    <jarUrl>https://github.com/OWNER/REPO/releases/download/v${project.version}/myapp.jar</jarUrl>
    <updateUrl>https://OWNER.github.io/myapp/update/myapp.json</updateUrl>
  </configuration>
</plugin>
```

- `jvmMode` `dll` runs the JVM inside the exe: one process, the app's own name in Task Manager, and on macOS the app's own Dock tile and "open with" events. (On macOS it is new: test it, and leave `jvmMode` out to start Java as a child as before.)
- Put `updateUrl` somewhere you control and can move, such as a GitHub Pages site. A `releases/latest/download/...` url works too, but then a pre-release can never have its own channel.
- Version `1.2.0-SNAPSHOT` comes from the Central snapshot repository. Add it once per machine in `~/.m2/settings.xml` as both a `<repository>` and a `<pluginRepository>`: `https://central.sonatype.com/repository/maven-snapshots/`.

Build:

```
mvn package                    exes that run the jar just built (for trying them out)
mvn -Djr.source=url package    release exes: they download jarUrl on first run, and target/jr/release/ is written
```

## 4. Icons

Draw one square master, 1024 x 1024 PNG with a transparent background, and make both files from it.

- **Windows, `.ico`:** one 256 px entry stored as PNG is enough, because Windows scales it for every view. If the 16 px caption icon looks smeared, add hand-drawn 16, 24 and 32 px entries, with fewer details, rather than scaled copies. With ImageMagick:

  ```
  magick icon-1024.png -resize 256x256 -define icon:auto-resize=256,48,32,16 myapp.ico
  ```

  Then open the exe's properties in Explorer and look at the icon at small sizes.
- **macOS, `.png`:** 512 x 512. The binary carries one image, and a 512 px `.png` is used byte for byte, so shrink it first: a palette PNG is usually under 15 KB (`magick icon-1024.png -resize 512x512 PNG8:myapp-512.png`, or `pngquant`). macOS icons conventionally sit inside a rounded square with about a 10% margin; a full-bleed square looks too big next to other apps.
- Keep the shapes flat and bold. Fine lines and text vanish at 16 px on Windows and 32 px in the Dock.
- Give no icon and the exe carries none (`macosNoIcon` for macOS only). Generic icons are fine while trying things out.

## 5. Releasing, and how users update

`mvn -Djr.source=url package` fills `target/jr/release/`. Publish its files (except the update file) as a GitHub release, then publish `myapp.update.json` at `updateUrl`. Do it in that order, so the update file never names a file that is not there yet. For the next release, pass `-Djr.updateMergeFrom=<the published update file>` so the new one keeps the older releases and the other channels.

Users then install with one line, no installer script (see jr's [docs/apps.md](https://github.com/jarrunner/jr/blob/main/docs/apps.md)):

```
curl.exe -fLo myapp.exe https://github.com/OWNER/REPO/releases/latest/download/myapp.exe
.\myapp.exe install
```

and update with `myapp update`, which your app implements in a few lines with [updateutils](https://github.com/jarrunner/updateutils), or directly with `myapp -Xjr:update`.

**Betas:** set `updateChannel` to `beta` for the beta build and publish its release as a GitHub pre-release. The update file then gains `"beta": "<version>"`, `stable` stays where it was, and installed stable copies are never offered the beta.

## 6. CI with GitHub Actions

One Windows job builds both platforms. A second job on a Mac is worth having only to run the macOS binary for real:

```yaml
name: release
on:
  workflow_dispatch:
jobs:
  build:
    runs-on: windows-latest
    steps:
      - uses: actions/checkout@v5
      - uses: actions/setup-java@v5
        with: { distribution: temurin, java-version: '25', cache: maven }
      - name: Build (Windows exe, macOS binary, release folder)
        run: mvn -B -Djr.source=url package
      - uses: actions/upload-artifact@v6
        with: { name: release, path: target/jr/release/ }

  try-on-mac:
    needs: build
    runs-on: macos-latest
    steps:
      - uses: actions/download-artifact@v7
        with: { name: release }
      - uses: actions/setup-java@v5
        with: { distribution: temurin, java-version: '25' }
      - name: The binary runs
        run: |
          chmod +x myapp
          ./myapp -Xjr:help
```

The snapshot repository needs the `settings.xml` lines from section 3 on the runner too (write the file in a step, or use `actions/setup-java`'s `server` options). Publishing a release from CI is `gh release create` with the files of `target/jr/release/`, and `--prerelease` for a beta.

## 7. Checking the result

- Windows: run the exe from Explorer, check its icon and its Properties, Details tab (version, product name), and run `myapp.exe -Xjr:doctor` to see which Java it would use and why.
- macOS: `myapp -Xjr:help` in Terminal; `./myapp install` (or `-Xjr:install`), then look for the app in Launchpad and keep it in the Dock. A copy downloaded with a browser is blocked by Gatekeeper until allowed once (System Settings, Privacy & Security, Open Anyway); one fetched with `curl` is not.
- Updates: build version 1.0, install it, publish 1.1, and run `myapp -Xjr:update-check` (exit 10 means a newer one exists), then `myapp update`.
