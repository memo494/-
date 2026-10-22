# Real screenshot regression corpus (22 Oct 2026)

These labels are transcribed from user-provided screenshots. They are **test fixtures**, not a training dataset and not production answer keys.

**Important:** The original screenshots are supplied separately as `QuickAnswer_User_Reference_Screenshots.zip` in the conversation. GitHub's current text-only file editor cannot ingest their binary attachments directly; do not claim the binary JPGs are in this branch. This manifest is committed so that no observations are lost, and the source ZIP contains the exact `app/src/test/resources/user_20261022/` paths for later import.

Do not use cached options/answers to decide an answer without reading the live question. A successful Gradle build does not validate real-time OCR/taps.

| Fixture | Screen kind | Expected behavior |
|---|---|---|
| `1000277246.jpg` | `dark_numeric_with_confirm` | في أي عام تم الانتهاء من هدم جدار برلين؟ |
| `1000277245.jpg` | `dark_numeric_with_confirm` | في أي عام ميلادي بدأت الحرب العالمية الأولى؟ |
| `1000277244.jpg` | `dark_grid_2x2` | من هو قائد جيش المسلمين في معركة حطين؟ |
| `1000277243.jpg` | `dark_grid_2x2_with_image` | أين يقع المعلم السياحي الموجود في الصورة؟ |
| `1000277242.jpg` | `dark_grid_2x2` | ما هو اسم الشهر الأول من السنة في التقويم الهجري؟ |
| `1000277019.jpg` | `white_numeric_with_green_submit` | كم عدد الحكام الموجودين على ارض الملعب في رياضة كرة السلة؟ |
| `1000276493.jpg` | `white_numeric_with_green_submit` | في أي عام ميلادي تم بناء الجامع الأموي في دمشق؟ |
| `1000276181.jpg` | `gallery_screenshot_negative` | Gallery view, reject |
| `1000276172.webp` | `white_vertical_options` | ما هي السورة التي بفضلها أسلم الصحابي الجليل عمر بن الخطاب؟ |

**Question matching:** preserve Arabic text and letter variants. **Geometry:** screen resolution and insets may differ; never tap merely from template location. **Answer verification:** only the 2×2 and vertical options with identified words are labeled. Numeric examples with ambiguous chronology/rules remain unlabeled.

Each original screenshot's SHA-256 is included in `screenshot-index.json`, along with the known question, answer choices and layouts. This supports reproducible ingestion once the binary images are placed under the indicated resource paths.
