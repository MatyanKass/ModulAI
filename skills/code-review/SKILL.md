---
name: code-review
description: Review a piece of code for bugs and risky spots, most serious first, with concrete fixes.
language: en
---

# Code review

The user pasted code and wants to know what is wrong with it before it causes trouble. The value
of a review is in the problems it finds that the author missed, so spend the effort on behaviour:
what the code does with unusual input, in what order things happen, what it assumes. Style
remarks are cheap to produce and rarely what the user needs; keep them to a line at the end.

## How to read the code

1. Work out what the code is meant to do, from names, comments and the user's message. If you
   cannot tell, say what you assumed.
2. Walk through it with concrete values, including the awkward ones: empty input, one element,
   zero, a negative number, null or a missing key, very large input, non-ASCII text, the same
   call made twice or from two threads.
3. Look for the usual sources of real bugs:
   - boundaries: off-by-one in loops and slices, `<` against `<=`, integer overflow and division;
   - failure paths: errors swallowed or ignored, resources not closed when something throws,
     a partial result left behind;
   - state: a value changed while it is being iterated, shared data without synchronisation,
     a stale cache, the order of initialisation;
   - trust: user input reaching SQL, a shell command, a file path or HTML unescaped; secrets
     written to logs;
   - contract: a function that does not do what its name or comment says, a return value the
     caller does not check.

Report only what you can point to in the code. If something would be a bug only under a condition
you cannot see (how a function is called, what a library guarantees), say it as a question to
check, not as a finding. Do not invent line numbers; quote the line instead.

## What to answer

Start with one sentence: what the code does and your overall verdict. Then list the findings,
most serious first, each in this shape:

    **[bug | risk | question]** short title
    The line or expression in question, quoted.
    What goes wrong and with which input.
    The fix, as corrected code when it is a few lines.

Give at most five findings unless the user asks for a full audit; three real ones are worth more
than ten that bury them. If you found nothing serious, say that plainly and name what you
checked, so the user knows how far the review went. Finish with one optional line of
readability or naming suggestions if any are worth making.

Answer in the language the user wrote in; keep code, identifiers and error messages as they are.
When the user asks for the corrected version, change only what the findings require and keep
the rest of their code and style intact.
