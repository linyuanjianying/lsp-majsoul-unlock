//! Score / point expectation.
//!
//! Wraps `riichienv-core`'s hand evaluators (`HandEvaluator` for 4-player,
//! `HandEvaluator3P` for 3-player) to compute, for a tenpai 13-tile hand,
//! per-wait-tile ron/tsumo scores plus the aggregate expectation used by the
//! attack ranking:
//!
//!   - dama (no riichi) ron / tsumo,
//!   - riichi ron / riichi tsumo (closed hands only).
//!
//! Ura-dora is ignored (no information at decision time), ippatsu is never
//! assumed, and honba / riichi sticks are zeroed so the numbers are comparable
//! across seats and rounds.
//!
//! ## Wait-score schema
//!
//! [`wait_scores`] returns one [`WaitScore`] per wait tile. Each of the four
//! situation slots (`ron` / `tsumo` / `riichi_ron` / `riichi_tsumo`) is:
//!
//!   - `None` — the situation is out of scope for this hand (open hands have no
//!     riichi slots) or the hand has no yaku there (can't legally win);
//!   - `Some(..)` — a legal win, with han / fu and the payment *total* for the
//!     winner (tsumo slots are the sum of all payers).
//!
//! Open hands therefore never carry riichi slots, which is what the UI needs in
//! order to stop showing "立直" options for a called hand.

use riichienv_core::hand_evaluator::HandEvaluator;
use riichienv_core::hand_evaluator_3p::HandEvaluator3P;
use riichienv_core::types::{Conditions, Meld as RiMeld, MeldType as RiMeldType, Wind, WinResult};

use super::hand::{Meld34, Meld34Kind, PlayerInfo34};
use super::result::{WaitScore, WaitScoreValue};
use super::tile::{Tile34, HONOR_BASE};
use super::waits::Waits;

/// Per-wait point breakdown returned by [`expectation`].
#[derive(Debug, Clone, Default)]
pub struct ScoreEstimate {
    /// Average dama (no riichi) ron point, weighted by remaining-tile count.
    pub dama_point: f64,
    /// Average riichi ron point. Zero if the hand is open.
    pub riichi_point: f64,
    /// Yaku ids encountered across all waits (union, deduplicated).
    pub yaku_ids: Vec<u32>,
    /// Whether at least one wait produces a valid (≥1 han) winning hand.
    pub has_yaku: bool,
}

fn wind_from_tile(tile: Tile34) -> Wind {
    let off = tile.idx().saturating_sub(HONOR_BASE);
    Wind::from(off)
}

fn meld_to_riichienv(m: &Meld34) -> RiMeld {
    let mt = match m.kind {
        Meld34Kind::Chi => RiMeldType::Chi,
        Meld34Kind::Pon => RiMeldType::Pon,
        Meld34Kind::Daiminkan => RiMeldType::Daiminkan,
        Meld34Kind::Ankan => RiMeldType::Ankan,
        Meld34Kind::Kakan => RiMeldType::Kakan,
    };
    let mut tiles_136: Vec<u8> = Vec::new();
    match m.kind {
        Meld34Kind::Chi => {
            for (i, t) in m.tiles.iter().enumerate() {
                tiles_136.push(t.idx() * 4 + (i as u8));
            }
        }
        Meld34Kind::Pon => {
            if let Some(t) = m.tiles.first() {
                for k in 0..3 {
                    tiles_136.push(t.idx() * 4 + k);
                }
            }
        }
        Meld34Kind::Daiminkan | Meld34Kind::Ankan | Meld34Kind::Kakan => {
            if let Some(t) = m.tiles.first() {
                for k in 0..4 {
                    tiles_136.push(t.idx() * 4 + k);
                }
            }
        }
    }
    let opened = !matches!(m.kind, Meld34Kind::Ankan);
    let called_136 = m.called_tile.map(|t| t.idx() * 4).filter(|_| opened);
    RiMeld::new(
        mt,
        tiles_136,
        opened,
        m.from_who.map(|w| w as i8).unwrap_or(-1),
        called_136,
    )
}

/// Aka-dora sentinel offsets within a 136-id tile group: 5m=16, 5p=52, 5s=88,
/// each at offset 0 of their tile-34 index. We skip these unless the player
/// is holding a red copy.
fn five_idx_with_aka(idx: u8) -> Option<u8> {
    match idx {
        4 | 13 | 22 => Some(0),
        _ => None,
    }
}

/// Materialise the concealed hand as 136-tile ids.
///
/// For 5m/5p/5s we steer around the aka-dora sentinel at offset 0 unless the
/// player actually holds red copies — riichienv counts those sentinels as aka
/// dora and would otherwise inject phantom han.
fn hand_tiles_136(info: &PlayerInfo34) -> Vec<u8> {
    let mut tiles_136: Vec<u8> = Vec::new();
    let aka_left = info.aka_count.min(3); // cap at 3 distinct red fives
    let mut aka_remaining = aka_left;
    for (idx_us, count) in info.hand.iter().enumerate() {
        let idx = idx_us as u8;
        let aka_offset = five_idx_with_aka(idx);
        let want_aka = aka_offset.is_some() && aka_remaining > 0 && *count > 0;
        let mut emitted = 0u8;
        if want_aka {
            tiles_136.push(idx * 4 + aka_offset.unwrap());
            aka_remaining -= 1;
            emitted += 1;
        }
        // Fill the rest with non-aka offsets (1, 2, 3 for fives; 0..count for everything else).
        let start = if aka_offset.is_some() { 1 } else { 0 };
        let mut off = start;
        while emitted < *count {
            // Skip the aka offset we already used (if any).
            if Some(off) == aka_offset {
                off += 1;
                continue;
            }
            if off > 3 {
                break;
            }
            tiles_136.push(idx * 4 + off);
            off += 1;
            emitted += 1;
        }
    }
    tiles_136
}

fn build_evaluator(info: &PlayerInfo34) -> HandEvaluator {
    let melds: Vec<RiMeld> = info.melds.iter().map(meld_to_riichienv).collect();
    HandEvaluator::new(hand_tiles_136(info), melds)
}

fn build_evaluator_3p(info: &PlayerInfo34) -> HandEvaluator3P {
    let melds: Vec<RiMeld> = info.melds.iter().map(meld_to_riichienv).collect();
    HandEvaluator3P::new(hand_tiles_136(info), melds)
}

/// Which of riichienv's evaluators to drive.
///
/// Sanma is selected from `PlayerInfo34::num_players == 3`. The 3p evaluator
/// differs from the 4p one in three ways that matter here: the 1m↔9m dora
/// wrap, north (kita) counted as dora + nukidora, and 3-player tsumo splits.
/// Extracted north count is supplied by the current player snapshot.
enum Evaluator {
    Four(HandEvaluator),
    Three(HandEvaluator3P),
}

impl Evaluator {
    fn build(info: &PlayerInfo34) -> Self {
        if info.num_players == 3 {
            Evaluator::Three(build_evaluator_3p(info))
        } else {
            Evaluator::Four(build_evaluator(info))
        }
    }

    fn calc(&self, win_tile_136: u8, dora_indicators: &[u8], conditions: Conditions) -> WinResult {
        match self {
            Evaluator::Four(e) => {
                e.calc(win_tile_136, dora_indicators.to_vec(), vec![], Some(conditions))
            }
            Evaluator::Three(e) => {
                e.calc(win_tile_136, dora_indicators.to_vec(), vec![], Some(conditions))
            }
        }
    }
}

/// Whether the hand is closed (no open meld). Ankan keeps the hand closed.
fn is_closed(info: &PlayerInfo34) -> bool {
    info.melds
        .iter()
        .all(|m| matches!(m.kind, Meld34Kind::Ankan))
}

/// riichienv yaku ids that are only dora and therefore cannot make a hand
/// legal on their own.
fn is_dora_yaku(id: u32) -> bool {
    use riichienv_core::yaku::{ID_AKADORA, ID_DORA, ID_NUKIDORA, ID_URADORA};
    matches!(id, ID_DORA | ID_AKADORA | ID_URADORA | ID_NUKIDORA)
}

/// Convert a `WinResult` into a `WaitScoreValue` entry, or `None` when the win is
/// illegal for that situation (no hand shape, no yaku, or zero han).
///
/// This is the single place that decides "legal win vs. nothing"; every slot
/// goes through it so we never fabricate points for a hand that cannot win.
fn to_win_score(res: &WinResult, tsumo: bool, info: &PlayerInfo34) -> Option<WaitScoreValue> {
    if !res.is_win || res.han == 0 {
        return None;
    }
    // Defensive: the engines already gate on has_yaku, but keep the invariant
    // explicit so a future engine change can't leak a dora-only "win".
    if !res.yakuman && res.yaku.iter().all(|&id| is_dora_yaku(id)) {
        return None;
    }
    let points = if tsumo {
        if info.jikaze.idx() == HONOR_BASE {
            res.tsumo_agari_ko * (info.num_players as u32 - 1)
        } else {
            res.tsumo_agari_oya + res.tsumo_agari_ko * (info.num_players as u32 - 2)
        }
    } else {
        res.ron_agari
    };
    if points == 0 {
        return None;
    }
    Some(WaitScoreValue {
        han: res.han as u16,
        fu: res.fu as u16,
        points,
        yakuman: res.yakuman,
        yaku_ids: res.yaku.clone(),
    })
}

/// Per-wait score summary for one tile.
struct TileEval {
    ron: Option<WaitScoreValue>,
    tsumo: Option<WaitScoreValue>,
    riichi_ron: Option<WaitScoreValue>,
    riichi_tsumo: Option<WaitScoreValue>,
}

impl TileEval {
    /// True when nothing can be won on this tile in any supported situation
    /// (e.g. an open, yakuless hand). Callers still emit the tile so the UI can
    /// render "无役" instead of silently dropping it.
    #[cfg_attr(not(test), allow(dead_code))]
    fn all_none(&self) -> bool {
        self.ron.is_none()
            && self.tsumo.is_none()
            && self.riichi_ron.is_none()
            && self.riichi_tsumo.is_none()
    }
}

fn conditions_for(info: &PlayerInfo34, tsumo: bool, riichi: bool) -> Conditions {
    Conditions {
        tsumo,
        riichi,
        // Everything below stays at its neutral value on purpose: ippatsu,
        // haitei/houtei, rinshan, chankan and tenhou/chiihou are all
        // situational and unknown at decision time. Honba / riichi sticks are
        // zeroed so numbers are comparable across seats and rounds.
        round_wind: wind_from_tile(info.bakaze),
        player_wind: wind_from_tile(info.jikaze),
        honba: 0,
        riichi_sticks: 0,
        // Extracted north count from the sanma snapshot.
        kita_count: info.kita_count,
        is_sanma: info.num_players == 3,
        num_players: info.num_players,
        ..Conditions::default()
    }
}

fn eval_tile(
    evaluator: &Evaluator,
    info: &PlayerInfo34,
    tile: u8,
    closed: bool,
    allow_riichi: bool,
) -> TileEval {
    // Wait labels describe ordinary fives; do not assume an unseen red five.
    let win_tile_136 = tile * 4 + if five_idx_with_aka(tile).is_some() { 1 } else { 0 };
    let dora: Vec<u8> = info.dora_indicators.iter().map(|t| t.idx() * 4).collect();

    let ron = evaluator.calc(win_tile_136, &dora, conditions_for(info, false, false));
    let tsumo = evaluator.calc(win_tile_136, &dora, conditions_for(info, true, false));

    let (riichi_ron, riichi_tsumo) = if closed && allow_riichi {
        (
            to_win_score(
                &evaluator.calc(win_tile_136, &dora, conditions_for(info, false, true)),
                false, info,
            ),
            to_win_score(
                &evaluator.calc(win_tile_136, &dora, conditions_for(info, true, true)),
                true, info,
            ),
        )
    } else {
        // Open hands have no riichi slot at all (not "0 points"): the call site
        // renders these as unavailable instead of showing a false 立直 option.
        (None, None)
    };

    TileEval {
        ron: to_win_score(&ron, false, info),
        tsumo: to_win_score(&tsumo, true, info),
        riichi_ron,
        riichi_tsumo,
    }
}

/// Score every wait tile under the four supported situations.
///
/// `allow_riichi` mirrors [`expectation`]: pass `false` to suppress the riichi
/// slots even for a closed hand. The output order follows the `Waits` map
/// (ascending `Tile34`), so it is deterministic.
///
/// Tiles whose every slot is `None` (open, yakuless) are still emitted so the
/// caller can render "无役" (no yaku) instead of an empty list.
///
/// ## Known limitations
///
///   - Kita count is provided from the sanma snapshot.
///   - Ura-dora, ippatsu, haitei/houtei, rinshan and chankan are not modelled
///     (they are situational); riichi tsumo therefore omits ura-dora.
///   - Honba and riichi sticks are reported separately (always zero here).
pub fn wait_scores(info: &PlayerInfo34, waits: &Waits, allow_riichi: bool) -> Vec<WaitScore> {
    if waits.is_empty() {
        return Vec::new();
    }
    let closed = is_closed(info);
    let evaluator = Evaluator::build(info);
    let mut out = Vec::new();
    for (tile, left) in waits.iter() {
        if left == 0 {
            continue;
        }
        let e = eval_tile(&evaluator, info, tile, closed, allow_riichi);
        // Summary slots (`han`/`fu`/`points`) describe the best available
        // situation: riichi-ron for closed hands, plain ron otherwise.
        let primary = e.riichi_ron.as_ref().or(e.ron.as_ref());
        out.push(WaitScore {
            tile: Tile34(tile).to_mjai().to_string(),
            left,
            han: primary.map(|s| s.han),
            fu: primary.map(|s| s.fu),
            points: primary.map(|s| s.points),
            ron: e.ron,
            tsumo: e.tsumo,
            riichi_ron: e.riichi_ron,
            riichi_tsumo: e.riichi_tsumo,
        });
    }
    out
}

/// Compute the point expectation across the wait set.
pub fn expectation(info: &PlayerInfo34, waits: &Waits, allow_riichi: bool) -> ScoreEstimate {
    expectation_from_wait_scores(&wait_scores(info, waits, allow_riichi))
}

/// Reuse the exact ron slots already evaluated for the UI; do not evaluate the
/// same hand a second time for the attack-ranking expectation.
pub fn expectation_from_wait_scores(scores: &[WaitScore]) -> ScoreEstimate {
    let mut sum_dama = 0.0f64;
    let mut sum_riichi = 0.0f64;
    let mut total_w = 0u32;
    let mut yaku_set: std::collections::BTreeSet<u32> = Default::default();
    let mut has_yaku = false;

    for e in scores {
        let left = e.left;
        if left == 0 {
            continue;
        }
        let dama_pt = e.ron.as_ref().map(|s| s.points as f64).unwrap_or(0.0);
        let riichi_pt = e.riichi_ron.as_ref().map(|s| s.points as f64).unwrap_or(0.0);

        for y in e
            .ron
            .iter()
            .chain(e.riichi_ron.iter())
            .flat_map(|s| s.yaku_ids.iter())
        {
            yaku_set.insert(*y);
        }
        has_yaku |= e.ron.is_some() || e.riichi_ron.is_some();

        let w = left as u32;
        sum_dama += dama_pt * w as f64;
        sum_riichi += riichi_pt * w as f64;
        total_w += w;
    }

    if total_w == 0 {
        return ScoreEstimate::default();
    }

    ScoreEstimate {
        dama_point: sum_dama / total_w as f64,
        riichi_point: sum_riichi / total_w as f64,
        yaku_ids: yaku_set.into_iter().collect(),
        has_yaku,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::analysis::hand::PlayerInfo34Builder;
    use crate::analysis::tile::Tile34;
    use crate::analysis::waits::Waits;

    fn waits_of(info: &PlayerInfo34, tiles: &[&str]) -> Waits {
        let mut w = Waits::new();
        let left = info.compute_left_tiles();
        for t in tiles {
            let idx = Tile34::from_mjai(t).unwrap().idx();
            w.insert(idx, left[idx as usize]);
        }
        w
    }

    fn chi(a: &str, b: &str, c: &str, called: &str) -> Meld34 {
        Meld34 {
            kind: Meld34Kind::Chi,
            tiles: vec![
                Tile34::from_mjai(a).unwrap(),
                Tile34::from_mjai(b).unwrap(),
                Tile34::from_mjai(c).unwrap(),
            ],
            called_tile: Some(Tile34::from_mjai(called).unwrap()),
            from_who: Some(1),
            aka_count: 0,
        }
    }

    #[test]
    fn expectation_reuses_weighted_ron_slots_only() {
        let value = |points, yaku_ids| WaitScoreValue {
            han: 1, fu: 30, points, yakuman: false, yaku_ids,
        };
        let scores = vec![
            WaitScore { tile: "1m".into(), left: 1, han: None, fu: None, points: None,
                ron: Some(value(1000, vec![2])), riichi_ron: Some(value(2000, vec![1, 2])),
                tsumo: Some(value(99999, vec![99])), riichi_tsumo: None },
            WaitScore { tile: "4m".into(), left: 3, han: None, fu: None, points: None,
                ron: None, riichi_ron: Some(value(4000, vec![1])),
                tsumo: None, riichi_tsumo: None },
        ];
        let estimate = expectation_from_wait_scores(&scores);
        assert_eq!(estimate.dama_point, 250.0);
        assert_eq!(estimate.riichi_point, 3500.0);
        assert_eq!(estimate.yaku_ids, vec![1, 2]);
        assert!(estimate.has_yaku);
        assert_eq!(expectation_from_wait_scores(&[]).dama_point, 0.0);
    }

    #[test]
    fn riichi_strictly_increases_over_dama_for_closed_hand() {
        // Pinfu shape — both dama and riichi should produce positive ron points,
        // and riichi should pay strictly more (extra han from riichi yaku).
        let info = PlayerInfo34Builder::new()
            .add_many(&[
                "1m", "1m", "2m", "3m", "4m", "2p", "3p", "4p", "6p", "7p", "2s", "3s", "4s",
            ])
            .build();
        let mut waits = Waits::new();
        waits.insert(Tile34::from_mjai("5p").unwrap().idx(), 4);
        waits.insert(Tile34::from_mjai("8p").unwrap().idx(), 4);
        let est = expectation(&info, &waits, true);
        assert!(est.dama_point > 0.0, "dama_point={}", est.dama_point);
        assert!(
            est.riichi_point > est.dama_point,
            "riichi {} should beat dama {}",
            est.riichi_point,
            est.dama_point
        );
    }

    #[test]
    fn empty_waits_zero() {
        let info = PlayerInfo34Builder::new().build();
        let waits = Waits::new();
        let est = expectation(&info, &waits, true);
        assert_eq!(est.dama_point, 0.0);
        assert_eq!(est.riichi_point, 0.0);
        assert!(wait_scores(&info, &waits, true).is_empty());
    }

    // ------------------------------------------------------------ wait_scores

    #[test]
    fn wait_scores_pinfu_hand_has_all_four_slots() {
        // 11m 234m 234p 234s + 67p ryanmen wait on 5p/8p, closed, dealer East.
        let info = PlayerInfo34Builder::new()
            .add_many(&[
                "1m", "1m", "2m", "3m", "4m", "2p", "3p", "4p", "6p", "7p", "2s", "3s", "4s",
            ])
            .bakaze("E")
            .jikaze("E")
            .build();
        let ws = wait_scores(&info, &waits_of(&info, &["5p", "8p"]), true);
        assert_eq!(ws.len(), 2, "{ws:?}");
        for w in &ws {
            assert!(w.ron.is_some(), "damaten ron must score: {w:?}");
            assert!(w.tsumo.is_some(), "damaten tsumo must score: {w:?}");
            assert!(w.riichi_ron.is_some(), "riichi ron must score: {w:?}");
            assert!(w.riichi_tsumo.is_some(), "riichi tsumo must score: {w:?}");
        }
        let r = &ws[0];
        let ron = r.ron.as_ref().unwrap();
        let ts = r.tsumo.as_ref().unwrap();
        let rron = r.riichi_ron.as_ref().unwrap();
        let rts = r.riichi_tsumo.as_ref().unwrap();
        assert_eq!(rts.han, ts.han + 1, "{r:?}");
        assert_eq!(rron.han, ron.han + 1, "{r:?}");
        assert!(rron.points > ron.points, "{r:?}");
        assert!(ts.points > 0, "{r:?}");
        // Summary mirrors riichi-ron for a closed hand.
        assert_eq!(r.han, Some(rron.han));
        assert_eq!(r.fu, Some(rron.fu));
        assert_eq!(r.points, Some(rron.points));
    }

    #[test]
    fn open_hand_never_reports_riichi_slots() {
        // Open tanyao: chi 234m + 234p + 234s + 55s + 67s waiting on 8s.
        let info = PlayerInfo34Builder::new()
            .meld(chi("2m", "3m", "4m", "4m"))
            .add_many(&["2p", "3p", "4p", "2s", "3s", "4s", "5s", "5s", "6s", "7s"])
            .bakaze("E")
            .jikaze("S")
            .build();
        let ws = wait_scores(&info, &waits_of(&info, &["8s"]), true);
        assert_eq!(ws.len(), 1, "{ws:?}");
        let w = &ws[0];
        assert!(w.ron.is_some(), "open tanyao ron must score: {w:?}");
        assert!(w.tsumo.is_some(), "open tanyao tsumo must score: {w:?}");
        assert!(w.riichi_ron.is_none(), "open hand has no riichi ron: {w:?}");
        assert!(w.riichi_tsumo.is_none(), "open hand has no riichi tsumo: {w:?}");
        // Summary mirrors the dama ron for open hands.
        assert_eq!(w.han, w.ron.as_ref().map(|s| s.han));
        assert_eq!(w.points, w.ron.as_ref().map(|s| s.points));
    }

    #[test]
    fn closed_yakuless_damaten_reports_nulls_but_riichi_scores() {
        // 123m 456m 789m 123p + EE pair waiting on E. Round South, seat West, so
        // East is neither yakuhai nor a valid yaku → damaten cannot win.
        let info = PlayerInfo34Builder::new()
            .add_many(&[
                "1m", "2m", "3m", "4m", "5m", "6m", "7p", "8p", "9p", "2s", "3s", "4s", "E",
            ])
            .bakaze("S")
            .jikaze("W")
            .build();
        let ws = wait_scores(&info, &waits_of(&info, &["E"]), true);
        assert_eq!(ws.len(), 1, "{ws:?}");
        let w = &ws[0];
        assert!(w.ron.is_none(), "yakuless damaten must not score: {w:?}");
        assert!(w.tsumo.is_some(), "closed self draw has menzen-tsumo yaku: {w:?}");
        assert!(w.riichi_ron.is_some(), "riichi makes it legal: {w:?}");
        assert!(w.riichi_tsumo.is_some(), "riichi makes it legal: {w:?}");
        assert_eq!(w.points, w.riichi_ron.as_ref().map(|s| s.points));
    }

    #[test]
    fn open_yakuless_hand_still_lists_tile_with_all_slots_null() {
        // Open 123m chi, 123p, 123s, 99s pair + single 6s waiting on 6s? Build a
        // shape with no yaku at all: chi 123m makes it open, 456p/789s are
        // plain runs, 99s pair, waiting 6s.
        let info = PlayerInfo34Builder::new()
            .meld(chi("1m", "2m", "3m", "1m"))
            .add_many(&["1p", "2p", "3p", "4p", "5p", "6p", "7s", "8s", "9s", "9s", "6s"])
            .bakaze("E")
            .jikaze("S")
            .build();
        let ws = wait_scores(&info, &waits_of(&info, &["6s"]), true);
        assert_eq!(ws.len(), 1, "{ws:?}");
        let w = &ws[0];
        assert!(w.riichi_ron.is_none() && w.riichi_tsumo.is_none(), "{w:?}");
        for slot in [&w.ron, &w.tsumo] {
            if let Some(s) = slot {
                assert!(s.han >= 1 && s.points > 0, "{w:?}");
            }
        }
    }

    #[test]
    fn sanma_tsumo_splits_across_two_payers() {
        // Same hand evaluated as 3p and 4p: ron is identical, tsumo total for a
        // dealer goes from 3 payers to 2.
        let base = PlayerInfo34Builder::new()
            .add_many(&[
                "1p", "2p", "3p", "4p", "5p", "6p", "7p", "8p", "9p", "2s", "2s", "3s", "4s",
            ])
            .bakaze("E")
            .jikaze("E")
            .build();
        let mut i4 = base.clone();
        i4.num_players = 4;
        let mut i3 = base.clone();
        i3.num_players = 3;

        let ws4 = wait_scores(&i4, &waits_of(&i4, &["5s"]), true);
        let ws3 = wait_scores(&i3, &waits_of(&i3, &["5s"]), true);
        assert_eq!(ws3.len(), 1, "{ws3:?}");
        assert_eq!(ws4.len(), 1, "{ws4:?}");

        let t3 = ws3[0].tsumo.as_ref().expect("3p tsumo scores");
        let t4 = ws4[0].tsumo.as_ref().expect("4p tsumo scores");
        assert_eq!(t3.han, t4.han, "{ws3:?} vs {ws4:?}");
        assert_eq!(t3.fu, t4.fu, "{ws3:?} vs {ws4:?}");
        assert!(t3.points < t4.points, "{ws3:?} vs {ws4:?}");
        assert_eq!(
            ws3[0].ron.as_ref().map(|s| s.points),
            ws4[0].ron.as_ref().map(|s| s.points),
            "ron must not depend on player count"
        );
    }

    #[test]
    fn wait_scores_reproduces_expectation_aggregate() {
        // The per-tile ron points must reproduce `expectation`'s weighted mean,
        // proving both paths drive the same evaluator + conditions.
        let info = PlayerInfo34Builder::new()
            .add_many(&[
                "1m", "1m", "2m", "3m", "4m", "2p", "3p", "4p", "6p", "7p", "2s", "3s", "4s",
            ])
            .bakaze("E")
            .jikaze("S")
            .build();
        let waits = waits_of(&info, &["5p", "8p"]);
        let est = expectation(&info, &waits, true);
        let ws = wait_scores(&info, &waits, true);
        let mut sum = 0.0;
        let mut wsum = 0u32;
        for w in &ws {
            let pts = w.ron.as_ref().map(|s| s.points as f64).unwrap_or(0.0);
            sum += pts * w.left as f64;
            wsum += w.left as u32;
        }
        let mean = sum / wsum as f64;
        assert!(
            (mean - est.dama_point).abs() < 1e-9,
            "{mean} vs {}",
            est.dama_point
        );
    }

    #[test]
    fn akuless_ordering_matches_waits_order() {
        // Output order must follow the waiting-tile order so the UI stays stable.
        let info = PlayerInfo34Builder::new()
            .add_many(&[
                "1m", "2m", "3m", "4m", "5m", "6m", "7m", "8m", "9m", "1p", "2p", "3p", "4p",
            ])
            .build();
        let ws = wait_scores(&info, &waits_of(&info, &["4p", "5p"]), true);
        let tiles: Vec<&str> = ws.iter().map(|w| w.tile.as_str()).collect();
        assert_eq!(tiles, vec!["4p", "5p"], "{ws:?}");
        assert!(ws.iter().all(|w| w.left > 0), "{ws:?}");
    }
}
