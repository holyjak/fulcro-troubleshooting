# Change Log

## [Unreleased]
### Changed
### Added

## [v10] - 2024-01-14
### Changed

Fix RAD/form false positives:

1. Don't warn about nil ident on RAD report -Row components when the report is configured not to normalize its data
2. Don't warn about nil joins when they are for subforms (typically 1:many ref, with no children created yet)

## [v9] - 2023-10-20
### Changed

- The Check: 'Valid :initial-state' now only shows the extra props that shouldn't be there

## [v8] - 2023-10-20
### Added

- Add Check: No duplicates in a query

## [v7] - 2023-09-29
### Added

* Error messages now contain the corresponding header from the Readme
### Added

* The border around a component with warnings is orange instead of green
## [v6] - 2022-05-05

### Added

- Add `:initial-state-filter` config
- Add `:query-inclusion-filter` config

### Fixed

#7 Link, ident queries should not require the key to be in initial state

## [v5] - 2021-03-06
### Added
- Wrap user's own components with React Error Boundary
- Check initial state for validity

### Changed
### Removed
### Fixed

[Unreleased]: https://github.com/holyjak/fulcro-troubleshooting/compare/latest...HEAD
[v5]: https://github.com/holyjak/fulcro-troubleshooting/compare/v4...v5
