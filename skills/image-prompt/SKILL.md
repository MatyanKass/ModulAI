---
name: image-prompt
description: Write a detailed Stable Diffusion prompt and negative prompt from a short idea.
language: en
---

# Image prompt

The user has a short idea ("a fox in a library") and wants a prompt that makes a Stable Diffusion
model draw it well. These models do not understand sentences the way you do. They react to a list
of concrete visual phrases, and the phrases that come first weigh the most. Your job is to decide
the things the user left open and write them down as such a list.

## How to build the prompt

Write in English whatever language the user wrote in; the image models were trained on English
captions. Use comma-separated phrases, most important first, in this order:

1. **Subject** — who or what, with the details that make it specific: age, build, hair, clothing,
   material, colour, pose, expression. "a red fox wearing round glasses, sitting on a stack of books".
2. **Setting** — where and when: place, time of day, weather, what is in the background.
3. **Composition** — shot type and angle: close-up portrait, full body, wide shot, low angle,
   from above, centered, rule of thirds.
4. **Light and colour** — warm candlelight, soft window light, golden hour, neon rim light,
   muted palette, high contrast.
5. **Style and medium** — one clear choice: photograph (add lens and depth of field: "85mm,
   shallow depth of field"), digital painting, watercolor, anime illustration, 3D render, pixel art.
6. **Quality tags** — a few at the end: highly detailed, sharp focus, intricate.

Aim for 30 to 60 words. Longer prompts get cut off by the text encoder and the tail is ignored.
Describe what is visible, not what it means: "tears on her cheeks, looking down" rather than
"she feels sad". Avoid negations in the main prompt ("no people" tends to add people); things to
keep out belong in the negative prompt. If the user asked for a known style or artist, keep it.

## Negative prompt

List what commonly goes wrong for this kind of image, 10 to 20 words. A reasonable base:
`blurry, low quality, jpeg artifacts, watermark, text, signature, cropped, deformed`.
For people add `bad anatomy, extra fingers, extra limbs, disfigured face`.
For photos add `cartoon, illustration, painting`; for illustrations add `photo, realistic`.

## What to answer

Give the result in this form and nothing before it:

    Prompt: <the prompt on one line>
    Negative prompt: <the negative prompt on one line>
    Settings: <aspect ratio that fits the composition, e.g. portrait 2:3, landscape 3:2, square>

Then add one short line naming the choices you made for the user (style, light, framing) so they
can ask for a different direction. If the idea is ambiguous in a way that changes the whole
picture, offer two prompts with different readings instead of asking a question first.
