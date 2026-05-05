# Sweet Home 3D — IKEA Browser Plugin

A Sweet Home 3D plugin that searches IKEA's online catalog and adds the 3D
models directly into your home. Inspired by the
[`ikea-browser`](https://github.com/shish/blender-ikea-browser) Blender add-on.

## Features

- Adds **Furniture → Import IKEA model…** to Sweet Home 3D.
- In-app search of IKEA's catalog with thumbnail grid.
- Country / language are configurable (defaults to `us` / `en`).
- Decodes Draco-compressed glTF (`KHR_draco_mesh_compression`) via a
  bundled libdraco JNI library — most IKEA models use Draco.
- Per-item caching: thumbnails, downloaded GLBs, and the converted OBJ
  bundles live in your Sweet Home 3D application folder so subsequent
  imports are instant.
- Imports use IKEA's reported width / depth / height so pieces are
  scaled correctly the moment they land in your floor plan.
- Falls back to a textured-box placeholder when the model can't be
  decoded (e.g. on a platform with no bundled native binary).

## Building

Requirements:

- A JDK (8 or newer) and Apache Ant.
- A C++14 compiler and the libdraco development files. These are only
  needed if you don't already have a native binary committed under
  `src/com/drskunk/sh3dikea/draco/native/<os>-<arch>/` for your host.
  - macOS: `brew install draco`
  - Debian / Ubuntu: `sudo apt install libdraco-dev`

Then:

```sh
# Build everything (native lib + .sh3p) using the standard Sweet Home 3D
# location for your platform:
make

# Override the SH3D location explicitly:
make SH3D_JAR=/path/to/SweetHome3D.jar

# Other useful targets:
make native     # rebuild the JNI bridge only
make package    # only re-run ant (skips native rebuild)
make clean      # wipe build/ and dist/
make distclean  # also remove all bundled native binaries
make help       # list targets and variables
```

The plugin file is written to `dist/IkeaBrowser.sh3p`.

If you'd rather invoke the underlying tools directly, `make -C native`
builds the JNI bridge and `ant -Dsh3d.jar=...` packages the `.sh3p`.
Drop a `SweetHome3D.jar` into `lib/` to skip the `-Dsh3d.jar` flag. On
macOS that jar lives at `/Applications/Sweet Home 3D.app/Contents/app/SweetHome3D.jar`.

### Native binary platforms

`make native` writes the compiled library to
`src/com/drskunk/sh3dikea/draco/native/<os>-<arch>/<libname>` and the Ant
build picks up everything in that directory tree. Layout:

| OS / Arch        | File                      | Built in CI |
| ---------------- | ------------------------- | ----------- |
| `macos-arm64`    | `libdracojni.dylib`       | yes         |
| `linux-x86_64`   | `libdracojni.so`          | yes         |
| `linux-arm64`    | `libdracojni.so`          | yes         |
| `windows-x86_64` | `dracojni.dll`            | yes         |
| `macos-x86_64`   | `libdracojni.dylib`       | no          |

Run `make native` on each target platform you want to support; commit
the resulting binaries alongside the source. Users on platforms without
a bundled binary still get the textured-box placeholder fallback.

## Installing

In Sweet Home 3D, open **File → Preferences → Plug-ins → Import…** and pick
`dist/IkeaBrowser.sh3p`. Restart Sweet Home 3D. The new entry appears under
the **Furniture** menu.

Alternatively, drop the `.sh3p` file into Sweet Home 3D's `plugins` folder:

- macOS: `~/Library/Application Support/eTeks/Sweet Home 3D/plugins/`
- Linux: `~/.eteks/sweethome3d/plugins/`
- Windows: `%APPDATA%\eTeks\Sweet Home 3D\plugins\`

## Using

1. **Furniture → Import IKEA model…**
2. (Optional) Set the **Country** and **Language** fields to match your
   local IKEA storefront (e.g. `gb` / `en`, `de` / `de`, `nl` / `nl`).
3. Type a search query in the field and press **Enter**.
4. The grid shows up to ~24 results. Click **Add to home** under any tile
   and the piece drops into your home next to the camera.

Note: not every IKEA product has a 3D model. If the API reports no model is
available, the import dialog says so and nothing is added.

## How models are imported

IKEA serves products via the same public endpoints used by the Blender
add-on, with the GLB downloaded from `web-api.ikea.com/.../rotera/...`. The
catalog also returns the official width / depth / height, which is used to
double-check the imported piece's footprint.

The pipeline:

1. **Download** the GLB and read the JSON + BIN chunks.
2. For each mesh primitive:
   - If the primitive has `KHR_draco_mesh_compression`, the Draco bytes
     from the bufferView are sent to the JNI bridge, which calls libdraco
     to decode positions, normals, texture coordinates, and indices.
   - Otherwise the primitive is read directly from glTF accessors.
3. **Apply node transforms** by walking the scene graph; positions are
   transformed by the world matrix, normals by its inverse-transpose 3×3.
4. **Extract textures** (PNG / JPEG pass straight through; WebP is run
   through `ImageIO` and re-encoded as PNG when a decoder is available).
5. **Write a ZIP** containing `model.obj`, `model.mtl`, and the
   extracted textures. Sweet Home 3D loads the OBJ via its own loader by
   way of a `jar:file:.../model.zip!/model.obj` URL.

## Cache

Files are cached at:

- macOS: `~/Library/Application Support/eTeks/Sweet Home 3D/IkeaBrowser/cache/`
- Linux: `~/.eteks/sweethome3d/IkeaBrowser/cache/`
- Windows: `%APPDATA%\eTeks\Sweet Home 3D\IkeaBrowser\cache\`

Per-item directories contain `thumbnail.jpg`, `model.glb`, `model.zip`
(SH3D-loadable bundle), and `bounds.txt`. Delete a directory to force a
re-download / re-conversion on the next import.

## Limitations

- IKEA's textures are typically WebP. Most stock JREs don't decode WebP
  through `ImageIO`, so the imported model lands with solid-colour
  materials and intact UV coordinates, and the geometry is the real GLB
  mesh. Adding the
  [TwelveMonkeys ImageIO-WebP](https://github.com/haraldk/TwelveMonkeys)
  jar to the Sweet Home 3D classpath enables texture decoding without
  changing this plugin.
- Only `KHR_draco_mesh_compression` is decoded; `EXT_meshopt_compression`
  falls back to a placeholder box.
- Search reaches the live IKEA API, so results vary by country / language
  and are subject to IKEA's availability.
- Country / language codes aren't validated; an unknown pair simply
  returns no results.
- The IKEA API is unofficial and may change without notice.
- Pieces are imported as one-off `HomePieceOfFurniture` entries — they
  aren't added to the SH3D furniture catalog for re-use.

## License

GPL v2 or later, matching the Sweet Home 3D plugin SDK. The bundled
libdraco binaries are statically linked from the
[`google/draco`](https://github.com/google/draco) project (Apache 2.0).
