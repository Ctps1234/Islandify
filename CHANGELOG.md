# Changelog

## 1.3

- Added baseline profile for smoother scrolling and a faster first launch.
- Settings are now saved in the background (debounced) and flushed when the app goes to the background.
- App icons for a first notification load off the main thread (no stutter).
- Larger app icon cache (24 -> 64).
- Island position watchdog polls less often when idle (battery).
- Backup export always includes the latest settings; import is no longer overwritten by a pending save.
- README: corrected target SDK (34), added what's new and a screenshot gallery.

## 1.2

- Previous release.
