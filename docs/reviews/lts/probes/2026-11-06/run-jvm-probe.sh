#!/usr/bin/env bash
set -euo pipefail
repo=${1:?snapshot worktree required}
site_sha=${2:-d211a7a}
probe_dir=$(cd "$(dirname "$0")" && pwd)
gradle_cache=${GRADLE_USER_HOME:-$HOME/.gradle}/caches
modules=$gradle_cache/modules-2/files-2.1
scratch=$(mktemp -d)
trap 'find "$scratch" -maxdepth 1 -type f -delete; rmdir "$scratch"' EXIT

# Maven cache grouping uses artifact ids separately from group ids.
cached() {
    local group=$1 artifact=$2 version=$3
    find "$modules/$group/$artifact/$version" -name "$artifact-$version.jar" -print -quit
}

compiler=$(cached org.jetbrains.kotlin kotlin-compiler-embeddable 2.2.20)
stdlib=$(cached org.jetbrains.kotlin kotlin-stdlib 2.2.20)
reflect=$(cached org.jetbrains.kotlin kotlin-reflect 2.2.0)
coroutines=$(cached org.jetbrains.kotlinx kotlinx-coroutines-core-jvm 1.8.0)
annotations=$(cached org.jetbrains annotations 13.0)
gson=$(cached com.google.code.gson gson 2.11.0)
java_cmd=${JAVA_CMD:-/usr/lib/jvm/java-21-openjdk/bin/java}
pkg="$repo/app/src/main/java/com/takahashirinta/ncrust"

# Extract these unchanged declarations to avoid bringing the Android service
# and Compose panel onto the host JVM. The probe runs the production logic.
{ printf '%s\n' 'package com.takahashirinta.ncrust.update'
  awk '/^sealed interface CheckOutcome/{read_decl=1} read_decl{print} read_decl && /^}/{exit}' "$pkg/update/UpdateService.kt"
} > "$scratch/CheckOutcome.kt"
{ printf '%s\n' 'package com.takahashirinta.ncrust.ui.player'
  awk '/^internal fun currentLineIndex/{read_decl=1} read_decl{print} read_decl && /^}/{exit}' "$pkg/ui/player/NcrustLyricsPanel.kt"
} > "$scratch/CurrentLineIndex.kt"
compose_annotations=$(find "$gradle_cache" -path '*/runtime-annotation/jars/classes.jar' -print -quit)

"$java_cmd" -cp "$compiler:$stdlib:$reflect:$coroutines:$annotations" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-stdlib -no-reflect -classpath "$stdlib:$gson:$annotations:$compose_annotations" -d "$scratch/probe.jar" \
    "$pkg"/update/{AppVersion,ForkRelease,ReleaseFeed,UpdateChecker,UpdateRecovery}.kt \
    "$pkg"/lyric/{LrcParser,LyricClock}.kt "$scratch"/{CheckOutcome,CurrentLineIndex}.kt "$probe_dir/LtsAuditProbe.kt"
git -C "$repo" show "$site_sha:relay/public/api-fallback/releases-array.json" > "$scratch/releases-array.json"
printf 'application=%s website_snapshot=%s\n' "$(git -C "$repo" rev-parse HEAD)" "$(git -C "$repo" rev-parse "$site_sha")"
sha256sum "$scratch/releases-array.json" | cut -d' ' -f1
"$java_cmd" -cp "$scratch/probe.jar:$stdlib:$gson" com.takahashirinta.ncrust.audit.LtsAuditProbeKt "$scratch/releases-array.json"
