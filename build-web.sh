#!/bin/sh
# 把 web/ 编译成旧 WebView（Chrome 52+）能跑的版本，放进 assets
set -e
cd "$(dirname "$0")"
npx --yes esbuild@0.24.2 web/app.src.js --target=chrome52 --outfile=app/src/main/assets/web/app.js
cp web/index.html web/style.css web/logo.png web/remote.html app/src/main/assets/web/
