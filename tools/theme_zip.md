# Theme ZIP files

`theme_zip.py` is a host-side, Python-standard-library transport helper. It never connects to, writes to, or
restarts a TV. Pack or unpack first, then use the existing `tools/theme.sh` or `tools/theme.ps1` to push the
unpacked folder. That existing push still has its usual reset and launcher-settings behaviour; a ZIP does not
make uploading transactional and does not add an on-TV theme library.

```bash
python3 tools/theme_zip.py pack themes/aurora aurora.zip
python3 tools/theme_zip.py inspect aurora.zip
python3 tools/theme_zip.py unpack aurora.zip imported-aurora
TV_HOST=192.168.1.50 tools/theme.sh imported-aurora
```

On Windows, the same Python commands work with `python` in place of `python3`. Push the resulting folder with
`powershell -ExecutionPolicy Bypass -File tools\theme.ps1 -TvHost 192.168.1.50 imported-aurora`.

The root must contain UTF-8 `config.json` without a BOM, no larger than 64 KiB, containing a JSON object.
Unknown config keys are kept byte-for-byte: this format is a transport format, not a visual-key sandbox.
`wallpaper.jpg`, up to 100 `wallpapers/<name>.jpg|jpeg|png|webp` files (10 MiB each), and UTF-8
`wallpapers/captions.txt` (64 KiB) form the deployment payload. `screenshot.jpg` (300 KiB), `README.md`,
`CREDITS.md`, `LICENSE`, and `LICENSE.txt` (64 KiB each) are retained for host-side documentation only and are
not pushed by the existing theme scripts.

The tool rejects arbitrary files, paths, symlinks, special files, encrypted or non-STORE/DEFLATE ZIP entries,
duplicate/case-colliding names, traversal, and malformed JSON. Archives have at most 128 members, a 128 MiB ZIP
file limit, a 1 MiB central-directory limit, and a 128 MiB verified expanded-data limit. Multi-disk and ZIP64
end records are unsupported. Validation checks the archive and JSON, not whether images decode or each
config value has a visible effect. `pack` rejects source symlinks and fork-only
`manifest.json`, `banners/`, `icons/`, and `fonts/`; it explicitly reports a conventional root
`make_wallpaper.py`/`make_wallpapers.py` generator as ignored; this allows packing `themes/ruska` directly.
It never runs these scripts. Other unsupported source files must be removed or left outside a staging subset.

`unpack` validates and CRC-reads every member before it creates the final destination. The new destination must
not exist; extraction finishes in a private sibling staging directory before the tool reserves the new
destination and moves the files into it. A failed move removes only the destination this invocation created.
The move is not an atomic directory switch and does not promise recovery from power loss. `pack` also refuses
an existing output file. Keep source files and archives unchanged until the command completes.
