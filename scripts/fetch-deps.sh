#!/usr/bin/env bash
# Downloads the third-party jars that get merged into the .sh3p, pinned by
# version and SHA-256. Safe to re-run: files that already match are skipped.
set -euo pipefail

MAVEN=https://repo1.maven.org/maven2
LIB="$(cd "$(dirname "$0")/.." && pwd)/lib"

# lib-subdir  path-under-maven-root  sha256
JARS=(
  # Bouncy Castle: TLS fallback for JREs without EC crypto (BcHttpsClient)
  "bouncycastle org/bouncycastle/bcprov-jdk18on/1.86/bcprov-jdk18on-1.86.jar 2af190b300cbb0b35e248ccf5f4a06b6072030aeb3da7a98ec73abe5b4cb371f"
  "bouncycastle org/bouncycastle/bcutil-jdk18on/1.86/bcutil-jdk18on-1.86.jar 1c268e15f785aafb1e4670d8d6e5f856f69475e96c162dadcd1ff9b350e9139f"
  "bouncycastle org/bouncycastle/bctls-jdk18on/1.86.2/bctls-jdk18on-1.86.2.jar 8ce8d5fb1b5d323a73df1fdba920b2f97c12d047986028fe65bf64d3997e50f2"
  # TwelveMonkeys: WebP reader for IKEA's textures
  "twelvemonkeys com/twelvemonkeys/common/common-lang/3.10.1/common-lang-3.10.1.jar 49d5eccf76df213c598189429c7d371a696a0b3b779e5052b0f9bb1fd1ed2f11"
  "twelvemonkeys com/twelvemonkeys/common/common-io/3.10.1/common-io-3.10.1.jar 5691fb7ad2402f8155e97a975530c143979036abc34361649d93b9ba41c2ce01"
  "twelvemonkeys com/twelvemonkeys/common/common-image/3.10.1/common-image-3.10.1.jar 960e9bce140694d0d7b8fe958dc9ab04f8d625c428f7aa9e9bfa1d7d9cd416d1"
  "twelvemonkeys com/twelvemonkeys/imageio/imageio-core/3.10.1/imageio-core-3.10.1.jar bb992bd1adf89ee3fde68c7338ecba3f9d0feefe9cc1f6916908b350dc629254"
  "twelvemonkeys com/twelvemonkeys/imageio/imageio-metadata/3.10.1/imageio-metadata-3.10.1.jar 4ead4e5faff303f781bc8fef1b86f4aeff8f9b35d15f2f82f60ea076a364e602"
  "twelvemonkeys com/twelvemonkeys/imageio/imageio-webp/3.10.1/imageio-webp-3.10.1.jar 0d332f5d8076bd0e35f671b852f2bfb7160c3d7d607efdb21d365157938ffb9e"
)

sha256() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1
  else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

for entry in "${JARS[@]}"; do
  read -r dir path want <<<"$entry"
  mkdir -p "$LIB/$dir"
  file="$LIB/$dir/${path##*/}"
  if [ -f "$file" ] && [ "$(sha256 "$file")" = "$want" ]; then
    echo "ok       ${file##*/}"
    continue
  fi
  curl -fsSL "$MAVEN/$path" -o "$file.part"
  got=$(sha256 "$file.part")
  if [ "$got" != "$want" ]; then
    rm -f "$file.part"
    echo "SHA-256 mismatch for ${path##*/}: expected $want, got $got" >&2
    exit 1
  fi
  mv "$file.part" "$file"
  echo "fetched  ${file##*/}"
done
