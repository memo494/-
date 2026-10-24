# Decisions (why things are the way they are)

Short records so no agent re-opens a settled question. Add a new entry instead of editing an old
one; mark an entry "superseded by N" when it changes.

1. **On-device first, cloud last.** Reading and memory cost no tokens and answer in well under a
   second; the screenshot request is the slowest and most expensive step, so it is the fallback.
2. **Fail closed.** A reader that is not sure returns null and the next step answers. A wrong
   question read with confidence (a missing word, a missing option) is worse than a slower answer.
   Hence: confidence floors per line, overlay masks, the hidden-button slot check, clipped-text
   detection in templates.
3. **Templates, not on-device training.** A game's layout is learned as geometry (boxes as
   fractions of the screen) plus region signatures; no model is trained on the phone. Cheap,
   inspectable, and confirmed against real answers before it is trusted.
4. **Fingerprint = game, signatures = screen type.** On real captures the 24x12 fingerprint
   separates games (same game <= 1.59, other game >= 1.97) but not question types inside one game
   (numeric vs 2x2 grid: 0.5), so every template also checks each region's background and
   uniformity. Thresholds are calibrated on 7 real screens; recalibrate when games are added.
5. **Real screenshots stay out of this public repository.** They show other players and the
   owner. Tests use synthetic images; real-screen checks run locally or from a private fixtures repo.
6. **Claude over REST, no SDK.** The Anthropic Java SDK added megabytes of dependencies for one
   request; `ClaudeApi` is a small, tested request/response layer.
7. **Gemini is the default (free tier), Claude optional (paid, accurate).** Text requests ask for
   the option number only, with the least thinking, to keep them fast and short.
8. **English OCR is ML Kit's bundled Latin model.** Works offline on first launch; Arabic stays on
   the PP-OCRv5 ONNX recogniser, which reads the games' Arabic at 0.93-0.97 confidence.
9. **Tests are the contract between agents.** The planner writes them before the code; the
   implementer may not change them. This removes back-and-forth and makes review objective.
10. **Learning never slows an answer.** Layout requests run in the background after the answer is
    shown, rate-limited, only for screens with no fitting template.
11. **A picture question is asked with its picture, and remembered per picture.** In the parchment
    game many questions ask about a photo pinned at the left ("ما اسم الشخصية الخيالية الاتية ؟" names
    no picture at all), so the words never decide them: the text-only request cannot see the photo, and
    a memory keyed by words would return the answer of an earlier photo. A template may carry the
    photo's rect (`picture`); the photo is present when the luma of the right half of that rect is not
    plain (std >= 20, measured: plain parchment 4-11, photos 31-89; the left half is the dark sea when
    there is none). The crop (long side 512, JPEG 80) goes with the text in one request, much smaller
    and faster than the screenshot, and the answer is stored with a 16x16 average hash of the photo
    (the same photo differs by a few bits, another one by about half). Only a TRUSTED template that
    reads the question decides this; anything else behaves as before.
12. **Interactions follow answer patterns, not game names.** One preparation step receives every
    answer source after solving, preserving the raw reply, reader geometry and picture context.
    Pure pattern planners currently cover 2–8 choices and verified integer keypads; image-only
    position guesses never authorize taps. A numeric submit waits for asynchronous field readback,
    so successful gesture callbacks alone cannot confirm a partially entered answer. Unsupported
    patterns show the answer without guessed interaction; later patterns extend this boundary
    rather than adding provider-specific tap logic. See `INTERACTIONS.md`.
13. **Measured picture-request speed overrides the crop transport in decision 11.** Main's
    `ea6d15f` reports 5.5 s for cropped-picture requests versus 2.4 s for whole screenshots on the
    owner's phone. Picture questions therefore keep picture-specific lookup/hash/guards but send
    the screenshot on a memory miss. The cropped provider API remains available; it is not the
    active service path. Result watching also retains that update's option-only pixel buffer.
14. **Do not clear numeric input automatically.** The owner requested removal of the pre-answer
    clear/backspace presses because they delay entry. The service now proves the field empty from
    pixels before planning and immediately before the first digit. Gray zero hints count as empty;
    gold zeroes or other existing input do not. Only a verified empty field sets `fieldIsEmpty=true`;
    the service sends digits and optional submit, with neither reset controls nor reset delays.
    Occupied or unknown fields stop unchanged. Legacy reset planning/sequencing APIs remain for
    compatibility with their original contract tests; the application no longer calls those modes.
15. **The Arabic lines of a screen are read side by side; the model stays fp32.** `ScreenReader`
    hands every question and option line to one `LineBatch`; the app reads 4 at once
    (`ParallelLines`), each inference on 2 threads. Same text and confidences as one by one
    (`OcrPipelineTest.linesReadSideBySide_readExactlyWhatOneByOneReads`); on 4 cores recognising
    S1/S2/S3 took 55/64/98 ms instead of 70/115/183 ms. An int8 copy of the model (onnxruntime
    dynamic quantisation) was measured and rejected: with the convolutions quantised it read
    "سورة طله" for "سورة طه" (S2 fails) and was 2-3x slower on x86; with only MatMul quantised it was
    no faster and misread a question letter. Re-measure on an ARM phone before revisiting.
