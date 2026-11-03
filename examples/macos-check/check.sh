#!/bin/sh
# Checks, on a real Mac, what the plugin built from this project. CI runs it (macos workflow); it may be run by hand too.
# It is a mechanical check: it cannot see a permission prompt, which needs a person at a screen.
set -eu
OUT=target/jr/macos
APP="$OUT/Example Recorder.app"
BIN="$OUT/macos-check"
fail() { echo "FAIL: $*" >&2; exit 1; }
ok() { echo "ok: $*"; }

plist_check() { # $1 = an Info.plist
  plutil -lint "$1" >/dev/null || fail "$1 is not a valid plist"
  [ "$(plutil -extract NSMicrophoneUsageDescription raw "$1")" = "Example Recorder records what you say, to show the permission prompt." ] || fail "microphone text (from the library) is not in $1"
  [ "$(plutil -extract NSDocumentsFolderUsageDescription raw "$1")" = "Opens and saves the files you choose in your Documents folder." ] || fail "documents text (from the pom) is not in $1"
  [ "$(plutil -extract CFBundleDocumentTypes.0.CFBundleTypeName raw "$1")" = "Markdown document" ] || fail "document type missing in $1"
  [ "$(plutil -extract CFBundleDocumentTypes.0.LSItemContentTypes.0 raw "$1")" = "net.daringfireball.markdown" ] || fail "markdown uti missing in $1"
  [ "$(plutil -extract UTImportedTypeDeclarations.0.UTTypeIdentifier raw "$1")" = "com.example.macos-check.note" ] || fail "imported type missing in $1"
  [ "$(/usr/libexec/PlistBuddy -c "Print :UTImportedTypeDeclarations:0:UTTypeTagSpecification:public.filename-extension:0" "$1")" = "exnote" ] || fail "imported extension missing in $1"
}

[ -d "$APP" ] || fail "no app at $APP"
plist_check "$APP/Contents/Info.plist"; ok "the app form: a valid Info.plist with the library, pom and document-type entries"
codesign --verify --strict "$APP" || fail "the app bundle does not verify"; ok "the app form: codesign verifies the bundle"
grep -q "from library example-recorder" "$OUT/macos-check.permissions.txt" || fail "the report does not credit the library"; ok "the permissions report credits the library"

# the binary form installs its own app, from the plist inside it, into an empty home
# on a CI runner (thrown away afterwards) the app goes into the real home, because Launch Services ignores apps in temporary
# folders; anywhere else an empty temporary home keeps this demo out of the person's own Applications
if [ "${CI:-}" = true ]; then H="$HOME"; else H=$(mktemp -d); fi
HOME="$H" "$BIN" -Xjr:install
INSTALLED="$H/Applications/Example Recorder.app/Contents/Info.plist"
[ -f "$INSTALLED" ] || fail "-Xjr:install wrote no app at $INSTALLED"
plist_check "$INSTALLED"; ok "the binary form: -Xjr:install wrote the same keys into the installed app"

# does Launch Services offer the installed app for a markdown file (Finder Open With)? and for the new type?
LSR=/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister
"$LSR" -f "$H/Applications/Example Recorder.app"
echo "--- Launch Services entries for the installed app:"
"$LSR" -dump | grep -n -i -E "Example Recorder|macos-check" | head -12 || true
echo "---"
T=$(mktemp -d); touch "$T/x.md" "$T/x.exnote"
offered() { osascript -l JavaScript -e "ObjC.import(\"AppKit\"); var u=\$.NSWorkspace.sharedWorkspace.URLsForApplicationsToOpenURL(\$.NSURL.fileURLWithPath(\"$1\")); var r=[]; for (var i=0;i<u.count;i++) r.push(ObjC.unwrap(u.objectAtIndex(i).path)); r.join(\"\n\")"; }
offered "$T/x.md" | grep -q "Example Recorder.app" || { offered "$T/x.md"; fail "Open With does not offer the app for a .md file"; }
ok "Launch Services offers the installed app for a .md file"
offered "$T/x.exnote" | grep -q "Example Recorder.app" || { offered "$T/x.exnote"; fail "Open With does not offer the app for its own file type"; }
ok "Launch Services offers the installed app for its own imported type"
echo "all checks passed"
