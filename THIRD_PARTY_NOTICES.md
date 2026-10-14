# Third-party notices

## KaTeX 0.17.0

PadNote Android and iPad bundle the browser distribution of KaTeX 0.17.0 to render LaTeX formulas locally and offline. Only `katex.min.js`, `katex.min.css`, WOFF2 fonts, and the upstream license are included under `android/app/src/main/assets/katex/` and `ios/PadNote/Resources/Web/katex/`.

- Project: https://katex.org/
- Source: https://github.com/KaTeX/KaTeX/tree/v0.17.0
- License: MIT
- Release archive SHA-256: `f77cf2555a40e879a4784e43e229d41867f106125bc05b15161de3e761b64b88`

The complete upstream license text is preserved at `android/app/src/main/assets/katex/LICENSE` and `ios/PadNote/Resources/Web/katex/LICENSE`.

## Mermaid 11.12.2

PadNote Android and iPad bundle the browser distribution of Mermaid 11.12.2 to render diagrams locally and offline. Only `mermaid.min.js` and the upstream license are included under `android/app/src/main/assets/mermaid/` and `ios/PadNote/Resources/Web/mermaid/`. The WebView that loads it has network access disabled.

- Project: https://mermaid.js.org/
- Source: https://github.com/mermaid-js/mermaid/tree/v11.12.2
- License: MIT
- Distribution file SHA-256: `d0830a6c05546e9edb8fe20a8f545f3e0dc7c4c3134d584bad9c13a99d7a71e0`

The complete upstream license text is preserved at `android/app/src/main/assets/mermaid/LICENSE` and `ios/PadNote/Resources/Web/mermaid/LICENSE`.

## ZXing Android Embedded 4.3.0 and ZXing Core 3.5.3

PadNote Android uses the embedded camera scanner to read connection-assistant pairing codes. The scanner works on the device and does not upload camera images.

- Android integration: https://github.com/journeyapps/zxing-android-embedded/tree/v4.3.0
- Barcode library: https://github.com/zxing/zxing/tree/zxing-3.5.3
- License: Apache-2.0; the complete upstream ZXing license also preserves its bundled component notices.
- Embedded AAR SHA-256: `4ab03353127c34e55cbb80fbc9a34031decbcfb1ec5e8f991944d2d494621a33`
- Core JAR SHA-256: `8d8064c1636fdaef7189dd9055c7d59950a8940a12f2293956446ec3c109fd82`

The complete upstream license texts are included in the APK under `assets/licenses/ZXING-ANDROID-EMBEDDED-LICENSE.txt` and `assets/licenses/ZXING-CORE-LICENSE.txt`.

## Nayuki QR Code Generator 1.8.0

The local PadNote connection assistant includes the unmodified Python QR encoder to display pairing codes without sending their contents to an external service.

- Source: https://github.com/nayuki/QR-Code-generator/tree/v1.8.0
- License: MIT
- Vendored `qrcodegen.py` SHA-256: `b089855caf16185c61421ea4927c1b213cf9468940d71fa8ab11ef83662dcc84`

The source and complete license are preserved under `desktop/connection-assistant/vendor/`.
