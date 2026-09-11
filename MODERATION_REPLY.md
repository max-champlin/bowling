# Modrinth moderation thread — reply to paste

Post this in the existing moderation thread. It is deliberately short,
non-defensive, and asks exactly one question. I cannot post it for you — I have
no Modrinth access — so copy from the block below.

---

Thanks for the detailed review.

**Slug, summary and description** — all fair, all being fixed. I'm renaming the
project to **Ten Pin Bowling** so the name matches the existing
`ten-pin-bowling` slug, rewriting the summary, and tightening the description.

I also found an accuracy problem you didn't flag: my `neoforge.mods.toml`
declared `versionRange = "[1.21.1,1.22)"`, so the listing claimed 1.21.1 through
1.21.11 when only 1.21.1 is tested and NeoForge 21.2+ changes the entity and
rendering APIs this mod depends on. That is now `[1.21.1,1.21.2)`.

**On the images (6.2a)** — none of the textures are generative AI output. They
are produced by `tools/gen_textures.py` in the public repository: 209 lines of
Python that writes the PNGs directly via `struct` and `zlib`, drawing each image
from geometry and a named palette. Nothing is traced, sampled or copied, and
running the script rebuilds the entire asset set identically. It was written
that way specifically so the provenance of every pixel is a file anyone can
re-run. Source: https://github.com/max-champlin/bowling

If procedural generation of that kind still falls under 6.2a, I would genuinely
like to understand why, so I can avoid it in future.

**On 6.2b** — I disclosed AI assistance for code, assets and text, and I am not
going to walk that back. My question is narrow:

> **What evidence of human authorship would change this decision?**

For context on what the human side of this project looks like: I derived the
physics constants from real ten-pin bowling, then measured 40 throws in game —
22% strikes, mean 8.45 pins, mode of 9 — and corrected four bugs those numbers
exposed, including pins sliding as upright cylinders instead of sweeping their
bodies across the deck, and a missing kickback wall that made the 10 pin fall
wrongly. None of that came out of a model; it came out of bowling in a test
world and writing the numbers down.

If there is a threshold or a form of evidence that matters to you, tell me what
it is and I will either meet it or stop taking up your time. If the rule is
categorical, I would rather know that plainly than keep resubmitting.

Thanks for your time either way.

---

## Why it is written like this

- **Leads with agreement.** Four of the five points are fair; conceding them
  immediately costs nothing and makes the one disagreement credible.
- **Volunteers a defect they missed.** The version range was genuinely wrong.
  Someone gaming the process does not hand over extra faults.
- **The images argument is a demonstration, not a claim.** They can clone the
  repo and run the script.
- **Asks one question and offers to stop.** "Tell me and I will meet it or go
  away" is much harder to ignore than an argument, and it gets a categorical
  policy stated plainly instead of implied.
- **Does not relitigate the disclosure.** Walking that back would be lying, and
  it is the one move that would actually deserve a ban.
