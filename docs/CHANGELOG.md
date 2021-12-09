# Changelog
All notable changes to this project will be documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com)

## [0.3.0] Unreleased - 2021-12-09
### Added
- Multi-Threading for exports
- Additional options to 'export' and 'convert-file'
- A csv to tbl converter

### Removed
- Command 'make-texture': is now part of 'convert-file'

## [0.2.3] - 2020-05-24
### Fixed
- A problem with blender's 2.82 gltf importer

## [0.2.2] - 2020-03-15
### Fixed
- An error which prevented the correct export of jpg textures with an unequal height and width

## [0.2.1] - 2020-03-09
### Added
- Command 'make-texture': Generates a .tex file which should be readable by WS

## [0.2.0] -  2020-03-07
### Added
- Support for m3 to wavefront obj export
- Command 'export-m3-texture': By default on. If set to off, m3 exporters will no longer export textures, which are referenced by the model. (UV maps are still included)
- Command 'export-tex-split': By default on. If set to off, tex exporters will no longer split textures, which contain more than just colors, into sub-images, which contain more specific data.
- Command 'show': new sub-command available to show file meta data
- Command 'about': shows about page

### Changed
- App does no longer accept commands on start

### Fixed
- 'convert-file' will no longer lock the process

## [0.1.3]
### Added
- Convert command 'convert-file'. Allows to directly convert exported or otherwise acquired binary data from Wildstar.

### Changed
- Search command. Will now write its results directly to file. The file will be loaded, if the search results are requested.
- Switch from Java 8 to Java 11

## [0.1.2] - 2019-02-11
### Added
- Project can be build with 'mvn clean package' and produces an executable version
- Error logging with log4j2

## [0.1.1] - 2019-02-09
### Added
- Show command -> show. For now only shows the results of the last 'search' command
- Debug command. Allows to toggle the debug mode of the app on/off. Debug mode will output more informations to the console
- m3 export 'gltf' mode will now also export textures

### Changed
- m3 export 'debug' mode will now bundle its output into a new folder, named after the m3, on the same folder level as the m3

### Fixed
- Changing a value with export-m3-type will now correctly show the old value
- tex export didn't use the correct reader. Will no longer produce empty folders

### Deprecated
- ModelSystem

## [0.1.0] - 2019-02-05
