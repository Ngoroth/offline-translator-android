# Google Play Graphics

Prepared on 10 November 2026 as a new store-listing artwork proposal. These files do not change the Android launcher icon.

- `app-icon-512.png`: upload to **App icon**. 512 × 512, 32-bit RGBA PNG, opaque full-square background, embedded sRGB profile. No baked-in outer rounding or drop shadow; Google Play applies its own mask.
- `feature-graphic-1024x500.png`: upload to **Feature graphic**. 1024 × 500, RGB PNG without transparency, embedded sRGB profile.

The artwork was drawn for this project from basic geometric primitives, without importing third-party logos, stock imagery or existing icon packs. The palette follows the application's purple Conversation controls. Banner dialogue is illustrative artwork, not a screenshot; its English input and Russian translation match the actual Tencent Text capture in `../tencent/text-translation.png`.

Text was rasterized using the locally installed Segoe UI font; no font files are distributed. Original project artwork falls under the repository's license. This provenance statement is not trademark clearance or a guarantee of exclusive rights in generic speech-bubble and arrow shapes.

The previous green launcher vector at `app/src/main/res/drawable/ic_launcher.xml` has no explicit artwork provenance recorded in the repository; its available file history begins at the initial source publication. Its origin must not be assumed from the repository license alone.

Requirements checked against Google's [Play icon specifications](https://developer.android.com/distribute/google-play/resources/icon-design-specifications) and [preview-asset requirements](https://support.google.com/googleplay/android-developer/answer/9866151?hl=en). Both PNGs were decoded, visually inspected and checked for dimensions, color mode and file-size limits. Publication and replacement of the APK launcher artwork remain separate actions.
