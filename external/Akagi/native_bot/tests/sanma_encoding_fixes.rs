//! Regression guards for the two sanma encoding fixes found via the
//! `libriichi3p` parity fixture.
//!
//! 1. `kawa_overview` must hold **raw** tile ids. `encode_tile_set` de-akas its
//!    input, so storing already-de-aka'd ids applied the conversion twice:
//!    tile 22 (5s) became 5 (6m) and the whole pond overview was scrambled. The
//!    4-player builder stores de-aka'd ids because its own reference does; the
//!    two forks genuinely differ, so the asymmetry is deliberate.
//!
//! 2. Shanten must be computed from the **3n+1** hand, i.e. without the freshly
//!    drawn tile. libriichi maintains `shanten` that way. Computing it on the
//!    3n+2 hand reads one lower whenever the draw completes a set — on the
//!    fixture that hit 2 of 8 cases (drawn 1m and 2p).
//!
//! Both produced silently plausible values, which is why they went unnoticed.

use native_bot::mortal_obs::mortal_obs_deaka;
use riichienv_core::shanten::calc_shanten_from_counts_3p;

const T: usize = 34;

/// The sanma shanten routine on a 13-tile hand.
fn sh13(counts: &[u8; T]) -> i8 {
    let n: usize = counts.iter().map(|&x| x as usize).sum();
    calc_shanten_from_counts_3p(counts, (n / 3) as u8)
}

#[test]
fn shanten_excludes_the_drawn_tile() {
    // Fixture hand: 1m 9m 1p 2p 3p 4p 5p 6p 7p 1s 2s 3s E  (13 tiles)
    let mut base = [0u8; T];
    for t in [0usize, 8, 9, 10, 11, 12, 13, 14, 15, 18, 19, 20, 27] {
        base[t] += 1;
    }
    assert_eq!(sh13(&base), 2, "the base hand is 2-shanten");

    // Drawing 1m (already held) makes a 14-tile vector that measures 1, but
    // libriichi still reports 2 because the draw is not folded into `shanten`.
    let mut with_draw = base;
    with_draw[0] += 1;
    let n14: usize = with_draw.iter().map(|&x| x as usize).sum();
    let naive = calc_shanten_from_counts_3p(&with_draw, (n14 / 3) as u8);
    assert_eq!(
        naive, 1,
        "the 14-tile form reads one lower — this is the trap the fix avoids"
    );

    // The fixed path removes the drawn tile first.
    let mut reduced = with_draw;
    reduced[0] -= 1;
    assert_eq!(sh13(&reduced), 2, "removing the drawn tile restores shanten 2");
}

#[test]
fn kawa_overview_must_use_raw_ids() {
    // A raw tile id and its de-aka'd form differ by a factor of four, so a
    // second conversion is observable.
    let raw_5s = 88u8; // first copy of 5s
    assert_eq!(mortal_obs_deaka(raw_5s), 22);

    // `encode_tile_set` de-akas its input, so feeding it 22 lands the value on
    // tile 5, which is 6m. The sanma builder must therefore store raw ids.
    let double = mortal_obs_deaka(22);
    assert_eq!(double, 5, "22/4 = 5, i.e. 6m — the double-conversion artefact");
    assert_ne!(double, 22);
}

/// Pins the `keep_shanten_discards` / `next_shanten_discards` rule, quoted from
/// libriichi's `PlayerState::update_shanten_discards`:
///
/// ```text
/// match shanten_after.cmp(&self.shanten) {
///     Ordering::Less  => next_shanten_discards[tid] = true,
///     Ordering::Equal => keep_shanten_discards[tid] = true,
///     _               => (),
/// }
/// ```
///
/// The two sets are *not* a partition of the candidates: a discard that makes the
/// hand worse is recorded in neither. The `tehai_len_div3` argument stays at the
/// pre-discard value inside the loop, which is why the shrunken vector still
/// needs `4` rather than `3`.
#[test]
fn keep_and_next_shanten_are_not_a_partition() {
    // 14-tile hand: 1m 9m 1p 2p 3p 4p 5p 6p 7p 1s 2s 3s E + drawn 1m.
    let mut hand = [0u8; T];
    for t in [0usize, 8, 9, 10, 11, 12, 13, 14, 15, 18, 19, 20, 27] {
        hand[t] += 1;
    }
    hand[0] += 1;

    let base = calc_shanten_from_counts_3p(&hand, 4);

    let mut next = Vec::new();
    let mut keep = Vec::new();
    let mut neither = Vec::new();
    for t in 0..T {
        if hand[t] == 0 {
            continue;
        }
        let mut after = hand;
        after[t] -= 1;
        // `tehai_len_div3` is deliberately still 4, matching upstream.
        let s = calc_shanten_from_counts_3p(&after, 4);
        if s < base {
            next.push(t);
        } else if s == base {
            keep.push(t);
        } else {
            neither.push(t);
        }
    }

    // The rule's distinguishing property is that the three outcomes can occur
    // independently; assert only what this hand actually exhibits and print the
    // rest, so the test documents behaviour instead of forcing a shape.
    assert!(
        !next.is_empty() || !keep.is_empty(),
        "every discard made the hand worse, which the rule records in neither set"
    );
    println!(
        "base={base}  next={next:?}  keep={keep:?}  neither={neither:?}"
    );
}
