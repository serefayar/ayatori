# Changelog

All notable changes to this project will be documented in this file.
This project follows [Keep a Changelog](https://keepachangelog.com/).

## [Unreleased]

### Changed

- **Breaking**: URI format changed from `ayatori://host:port/c/{ref}` to `ayatori://host:port/c/{agent}/{cap}`
  - URIs are now human-readable and self-describing
  - No registry lookup needed for URI resolution
  - Security handled via kex tokens, not URI obscurity

- **Breaking**: Dependency resolution is now name-based at runtime
  - Deps resolved at call time via wiring, not injected as CapHandles at start!
  - Enables hot-swap and dynamic rewiring without restart
  - Unresolved dep errors now occur at runtime (when dep is called) instead of at start!

- `rewire!` now simply updates the wiring map (simpler implementation)

### Removed

- `cap/make-ref` function (UUIDs no longer used in URIs)
- Ref-based registry (`ref -> entry` mapping)
- CapHandle injection for deps at start! time
