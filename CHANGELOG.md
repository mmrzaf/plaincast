# Changelog

## 2.0.0 — 2026-07-25

### Added

- Supplied PlainCast logo across Android launcher, adaptive, themed, notification, browser, and PWA assets.
- Signed GitHub release workflow publishing debug and release APKs plus SHA-256 checksums.
- Release setup, privacy, security, and final verification documentation.

### Changed

- Reset the public application release to `2.0.0` with monotonically higher Android `versionCode` 20000.
- Standardized dependency repositories on the official Google, Maven Central, and Gradle Plugin Portal endpoints.
- Aligned all product documentation with room protocol 10 and the stop-before-switch device-audio model.
- Updated Android and browser visual accents to the PlainCast purple identity.

### Stabilized

- Early ICE handling and perfect-negotiation ordering.
- Media transport readiness and diagnostics.
- Android MediaProjection foreground-service lifecycle.
- Shared-audio buffering, backpressure, decode, and playback.
- Remote video ownership and reconnect cleanup.
