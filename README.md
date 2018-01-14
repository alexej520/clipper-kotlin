# clipper-kotlin
Polygon and line clipping and offsetting library

Polygon Clipper is a library to generate various boolean operations (Union, Difference, XOR, etc.) on arbitrary 2D polygons, e.g. calculate the area in which two polygons overlap.

It’s a Kotlin portation of the Clipper project developed by [Angus Johnson](http://www.angusj.com/delphi/clipper.php), which as an implementation of the algorithm proposed by [Bala R. Vatti](http://en.wikipedia.org/wiki/Vatti_clipping_algorithm).

This port based on [C# Clipper](https://sourceforge.net/projects/polyclipping/) version 6.4.2.

[Demo Applications](https://github.com/alexej520/clipper-demos) that use this library

## Getting started and Documentation

Original Angus Johnson project's documentation: [http://www.angusj.com/delphi/clipper/documentation/](http://www.angusj.com/delphi/clipper/documentation/Docs/Overview/_Body.htm)

## Binaries

Example for Gradle:

```groovy
repositories {
    maven { url 'https://jitpack.io' }
}

compile 'com.github.alexej520.clipper-kotlin:1.0'
```

Feel free to contact me if you found a bug or have a question regarding the Kotlin version.
