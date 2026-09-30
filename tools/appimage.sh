#!/usr/bin/env bash
#
# Собирает файл .AppImage из каталога app-image, который готовит :desktopApp:createDistributable.
#
# Почему скриптом, а не задачей Gradle: задача packageAppImage у Compose доводит дело только
# до каталога app-image — jpackage формата AppImage не знает, — а настоящий файл делает
# appimagetool, сторонняя утилита, которую надо скачать. Тот же скрипт работает и в CI,
# и локально, поэтому рецепт не приходится держать в голове.
#
# Использование: tools/appimage.sh [каталог-app-image] [куда-положить]
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
app_dir="${1:-$root/desktopApp/build/compose/binaries/main/app/AIStudio}"
out_dir="${2:-$root/desktopApp/build/compose/binaries/main/appimage}"
name="AIStudio"

# Версия берётся из сборки, а не дублируется здесь: иначе файл и установщик разойдутся.
version="$(sed -n 's/.*packageVersion = "\([^"]*\)".*/\1/p' "$root/desktopApp/build.gradle.kts" | head -1)"
version="${version:-0.1.0}"

case "$(uname -m)" in
  x86_64) arch="x86_64" ;;
  aarch64 | arm64) arch="aarch64" ;;
  *)
    echo "Неизвестная архитектура: $(uname -m)" >&2
    exit 1
    ;;
esac

[ -d "$app_dir" ] || {
  echo "Нет каталога приложения: $app_dir — сначала ./gradlew :desktopApp:createDistributable" >&2
  exit 1
}
[ -x "$app_dir/bin/$name" ] || {
  echo "Нет исполняемого файла $app_dir/bin/$name" >&2
  exit 1
}
[ -f "$app_dir/lib/$name.png" ] || {
  echo "Нет иконки $app_dir/lib/$name.png" >&2
  exit 1
}

# Утилита кладётся вне каталога с пакетами: иначе она попала бы в артефакты CI
# вместе с самим AppImage.
tool="${APPIMAGETOOL:-$root/desktopApp/build/appimagetool/appimagetool-$arch.AppImage}"
if [ ! -x "$tool" ]; then
  mkdir -p "$out_dir" "$(dirname "$tool")"
  echo "Скачиваю appimagetool ($arch)…"
  curl -fsSL -o "$tool" \
    "https://github.com/AppImage/appimagetool/releases/download/continuous/appimagetool-$arch.AppImage"
  chmod +x "$tool"
fi

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
appdir="$work/$name.AppDir"
cp -r "$app_dir" "$appdir"
cp "$appdir/lib/$name.png" "$appdir/$name.png"

cat > "$appdir/$name.desktop" <<DESKTOP
[Desktop Entry]
Type=Application
Name=AI Studio
Exec=$name
Icon=$name
Categories=Development;
Terminal=false
DESKTOP

# AppRun — точка входа AppImage; APPDIR выставляет сама среда AppImage, поэтому путь
# к приложению не зависит от того, куда файл положили.
cat > "$appdir/AppRun" <<APPRUN
#!/bin/sh
exec "\$APPDIR/bin/$name" "\$@"
APPRUN
chmod +x "$appdir/AppRun"

# --appimage-extract-and-run: в CI и в контейнерах нет FUSE, без этого appimagetool
# не запускается вовсе.
ARCH="$arch" "$tool" --appimage-extract-and-run "$appdir" "$out_dir/$name-$version-$arch.AppImage"
echo "Готово: $out_dir/$name-$version-$arch.AppImage"
