#!/bin/sh
# Downloads Google's closed-source glide typing library (the same files HeliBoard's README points to)
# into app/glide-assets, for personal builds with -Pagent.bundleGlide=true. Not committed to git.
set -e
B=https://raw.githubusercontent.com/erkserkserks/openboard/46fdf2b550035ca69299ce312fa158e7ade36967/app/src/main/jniLibs
for a in arm64-v8a armeabi-v7a x86 x86_64; do
  mkdir -p app/glide-assets/glide/$a
  curl -sL -o app/glide-assets/glide/$a/libjni_latinimegoogle.so $B/$a/libjni_latinimegoogle.so
done
echo "glide libraries in app/glide-assets"
