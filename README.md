# Bowling

**Ten-pin bowling for Minecraft, with physics derived from the real thing.**

Minecraft 1.21.1 / NeoForge. MIT. No dependencies.

> **Beta.** Playable and tuned, but the numbers are still moving. See
> [Tuning it yourself](#tuning-it-yourself) if you disagree with them — you can
> change any of them in game, and I would rather hear that you did.

---

## Playing

Build a lane, place the foul line, right-click with the ball.

- **Aim** by where you stand and where you look
- **Hold** right-click for power
- **Up / Down arrows** for spin — this is the hook, and it matters
- The lane keeps frame state and the scoreboard, so a game survives logging out
  and several players can share one lane

The ball comes back on its own. You only ever need one.

---

## The physics

Constants are derived from real bowling rather than invented, then measured in
game and corrected.

- Ball 7.26 kg, pin 1.53 kg, restitution ~0.5 ball-on-pin and ~0.7 pin-on-pin,
  applied along the line of centres — so a pin clipped on its left edge goes
  right, not straight on
- Release at 19.7 mph, inside the real 17-21 range
- A **40-foot oil pattern on a 60-foot lane**, so the ball skids for two thirds
  and only bites in the last third. That late bite is what makes a hook look
  like it goes straight and then turns hard
- Pins fall, and a **fallen pin sweeps its body across the deck** rather than
  sliding around as an upright cylinder. Six of the ten in a strike come from
  pin action, not from the ball
- **Kickback walls** on the pin deck, because the 6 rebounding off the right
  wall is how the 10 pin actually falls
- **Release variation** — a human hand does not repeat a delivery exactly

Measured over 40 throws at the pocket: **22% strikes, mean 8.45 pins**, mode of
9, most common leave a single 6 or 10. That is club-bowler bowling.

A dead straight ball into the head pin is the *weak* shot, as in real life — it
pays the full deflection toll and leaves you a split. The pocket is a degree or
so off centre with hook on it.

---

## Tuning it yourself

Every physics constant is settable at runtime. **Ops only** (permission 2), so
a single-player host has it by default and a server needs to grant it.

```
/bowlsim show                     list every constant and its value
/bowlsim set pinToPin 0.9         change one
/bowlsim 40 0.75 1.0 -1.0         throw 40 balls: aim, power, spin
```

The last one is a measurement harness. It throws a repeatable delivery, waits
for the deck to settle, counts what fell, and writes the lot to
`bowling/bowl-sim.jsonl` — pins down, which pins survived, how far they
travelled, and the constants it ran under.

That is how the mod was tuned, and it is shipped because it is the honest way to
argue about the physics. If you think the hook is wrong, sweep it and open an
issue with the numbers.

Values are **not persisted** — a restart returns to the compiled defaults.

---

## Known gaps

- Pins are modelled as point masses. A real pin carries its centre of gravity
  about a third of the way up and imparts **yaw** when hit off-centre, sweeping
  a wider path than its diameter. Not modelled yet.
- `PIN_RETAINED` no longer pairs with `PIN_TO_PIN` — they were derived together
  from equal masses, then only the transfer was tuned by measurement. Documented
  in the source. Worth testing as a pair.
- The pin collider is 0.22 wide; the model is drawn nearer 0.30. Visual and
  collision are not quite in step.

---

## Building

```
gradle build
```

Output in `build/libs/`.
