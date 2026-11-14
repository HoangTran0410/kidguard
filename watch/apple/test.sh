#!/bin/sh
# Core tests with only the Command Line Tools installed (no XCTest there; Swift Testing is, but SwiftPM needs the paths).
# With full Xcode just run `swift test` in Core/.
cd "$(dirname "$0")/Core" || exit 1
D=/Library/Developer/CommandLineTools/Library/Developer
exec swift test -Xswiftc -F -Xswiftc $D/Frameworks -Xlinker -F -Xlinker $D/Frameworks \
  -Xlinker -rpath -Xlinker $D/Frameworks -Xlinker -rpath -Xlinker $D/usr/lib "$@"
