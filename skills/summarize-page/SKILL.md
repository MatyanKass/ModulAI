---
name: summarize-page
description: Summarize a web page or a pasted text faithfully, with the key facts and numbers kept.
tools: [fetch_page]
language: en
---

# Summarize a page

The user wants to know what a page or a long text says without reading it. A good summary lets
them decide whether to read the original and lets them repeat its main points correctly. That
means every statement in the summary has to come from the text itself, and the specifics that
carry the meaning (numbers, dates, names, conditions) have to survive.

## Getting the text

- If the user gave a link, open it with `fetch_page`. If the tool is not available or the page
  fails to load, say so and ask the user to paste the text. Do not summarize a page from its
  address or from memory: that produces a plausible summary of something you have not read.
- If the user pasted the text, use that.
- If the text you received ends abruptly or is clearly only a part (a cut-off note, a cookie
  banner, a login wall), summarize what you have and say that the source was incomplete.

## Writing the summary

Answer in the language the user wrote in, even when the page is in another language.

1. Start with one or two sentences that say what the text is (news report, product page, study,
   opinion piece, manual) and its main point.
2. Follow with 3 to 7 bullet points, one idea each, in the order of importance rather than the
   order of the page. Keep the exact figures, dates, names and conditions: "revenue rose 12% to
   $4.1 billion in Q3 2026", not "revenue rose noticeably".
3. If the page gives a date of publication or of last update, mention it. For news, prices and
   software versions the reader needs to know how old the information is.
4. Keep the author's claims apart from established facts: "the author argues that…", "the company
   says…". An opinion piece summarized as fact misleads the reader.

Length: about a tenth of the source, and no more than 200 words unless the user asks for more
detail. A short page gets a short summary; do not pad.

## What to leave out

Navigation, adverts, related links, comment sections, cookie notices. Your own opinion about the
content, unless the user asks for it. Anything you would have to guess: if the text does not say
why something happened, the summary does not either.

If the user asked a specific question about the page ("what does it say about pricing?"), answer
that question first and from the text, then offer the general summary only if it helps.
