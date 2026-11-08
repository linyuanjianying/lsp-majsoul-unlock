//! Result types produced by the analysis engine.
//!
//! Mirrors mahjong-helper's `Hand13AnalysisResult` / `Hand14AnalysisResult`
//! structures with field-for-field equivalence where the algorithm has been
//! ported. Phase 1 only populated [`WaitInfo`]; Phase 2 fills in
//! [`Hand13Result`] / [`Hand14Result`] / [`DiscardCandidate`].

use serde::Serialize;

use super::tile::Tile34;
use super::waits::Waits;

/// One legal winning shape for a single wait tile, as produced by
/// `riichienv-core`'s hand evaluator.
///
/// "Legal" means the engine reported `is_win` (agari shape + at least one
/// non-dora yaku), so a `Some(..)` slot is always a hand the player could
/// actually declare. A slot that is absent (`None`) is *not* the same as
/// "0 points": it means this situation can't be won at all (no yaku there),
/// or is out of scope (open hands have no riichi slots).
#[derive(Debug, Clone, Serialize)]
pub struct WaitScoreValue {
    /// Han counted by the evaluator (dora included, ura-dora excluded).
    pub han: u16,
    /// Fu counted by the evaluator.
    pub fu: u16,
    /// Payment total received by the winner, in points.
    /// For `ron` / `riichi_ron` this is the discarder's single payment.
    /// For `tsumo` / `riichi_tsumo` it is the sum over every payer
    /// (so a 3-player tsumo sums 2 payers, a 4-player one sums 3).
    pub points: u32,
    /// Whether the han count is yakuman-driven (han is then bucketed at 13+).
    pub yakuman: bool,
    /// Yaku ids reported by the evaluator (riichienv yaku id space).
    pub yaku_ids: Vec<u32>,
}

/// Score breakdown for one wait tile of a 13-tile hand.
///
/// Produced by `analysis::score::wait_scores`. Emitted for every wait tile,
/// including tiles where nothing is winnable — in that case every slot is
/// `None` and the caller should surface "无役" (no yaku).
///
/// Deliberate simplifications (see `score::wait_scores` docs):
/// no ura-dora, no ippatsu, no haitei/houtei/rinshan/chankan, no honba or
/// riichi sticks. Sanma kita count is included.
#[derive(Debug, Clone, Serialize)]
pub struct WaitScore {
    /// The wait tile (mjai notation, never a red-five suffix).
    pub tile: String,
    /// Copies of `tile` still unseen (same value as the matching `WaitInfo.left`).
    pub left: u8,
    /// Summary han of the best-available situation (riichi-ron when the hand
    /// is closed, plain ron otherwise). `None` when nothing is winnable.
    pub han: Option<u16>,
    /// Summary fu, matching the same situation as `han`.
    pub fu: Option<u16>,
    /// Summary points, matching the same situation as `han`.
    pub points: Option<u32>,
    /// Dama (no-riichi) ron. `None` when the hand has no yaku without riichi.
    pub ron: Option<WaitScoreValue>,
    /// Dama tsumo. `None` when the hand has no yaku without riichi.
    pub tsumo: Option<WaitScoreValue>,
    /// Riichi ron. Always `None` for open hands (no riichi possible).
    pub riichi_ron: Option<WaitScoreValue>,
    /// Riichi tsumo. Always `None` for open hands (no riichi possible).
    pub riichi_tsumo: Option<WaitScoreValue>,
}

#[derive(Debug, Clone, Serialize)]
pub struct WaitInfo {
    pub tile: String,
    pub left: u8,
    /// Per-wait agari rate (percent). `None` until Phase 2 populates it.
    pub agari_rate: Option<f64>,
}

impl WaitInfo {
    /// Build wait infos with no agari rate (Phase 1 helper).
    pub fn from_waits(waits: &Waits) -> Vec<WaitInfo> {
        waits
            .map
            .iter()
            .map(|(&idx, &left)| WaitInfo {
                tile: Tile34(idx).to_mjai().to_string(),
                left,
                agari_rate: None,
            })
            .collect()
    }

    /// Build wait infos pulling the per-tile agari rate from a side map.
    pub fn from_waits_with_rates(
        waits: &Waits,
        rates: &std::collections::BTreeMap<u8, f64>,
    ) -> Vec<WaitInfo> {
        waits
            .map
            .iter()
            .map(|(&idx, &left)| WaitInfo {
                tile: Tile34(idx).to_mjai().to_string(),
                left,
                agari_rate: rates.get(&idx).copied(),
            })
            .collect()
    }
}

/// One improves entry: drawing this tile doesn't progress shanten, but the
/// best discard from the resulting 14-state widens the wait set.
#[derive(Debug, Clone, Serialize)]
pub struct ImproveEntry {
    /// The drawn tile (mjai).
    pub draw: String,
    /// The widened waits + their remaining counts (post-best-discard).
    pub widened_waits: Vec<WaitInfo>,
    /// Sum of `widened_waits.left`. Always greater than `Hand13Result.waits_total`.
    pub widened_total: u32,
}

/// 13-tile hand analysis. Populated by `analysis::improves::analyze_13`.
#[derive(Debug, Clone, Serialize)]
pub struct Hand13Result {
    /// Shanten (0 = tenpai, 1 = ichi-shanten, …; -1 = agari shape).
    pub shanten: i8,
    /// Tiles whose draw progresses shanten by one (or completes the hand).
    pub waits: Vec<WaitInfo>,
    /// Sum of remaining-tile counts across all waits.
    pub waits_total: u32,
    /// Per-progressing-draw: the largest waits-count reachable after the
    /// best discard. Indexed by Tile34 (0..34) → count.
    pub next_shanten_waits_count: std::collections::BTreeMap<u8, u32>,
    /// Weighted average of `next_shanten_waits_count` (weighted by remaining).
    pub avg_next_shanten_waits: f64,
    /// "Speed" score blending current waits and next-shanten waits.
    /// Special-cased: divided by 4 for 2-shanten hands (per Go reference).
    pub mixed_waits_score: f64,
    /// Per-wait agari rate aggregated into a single number (percent).
    pub avg_agari_rate: f64,
    /// Whether the hand is in furiten state (any wait is on our river).
    pub is_furiten: bool,
    /// Furiten rate: 1.0 at tenpai (genuine furiten), 0.5 at 1-shanten
    /// (potential furiten — we may still escape), 0 otherwise.
    pub furiten_rate: f64,
    /// Improves: non-progressing draws + best widening discard. The keyset
    /// is exactly those tiles for which a wait-widening discard exists.
    pub improves: Vec<ImproveEntry>,
    /// Number of (draw, discard) pairs that successfully widen waits.
    pub improve_way_count: u32,
    /// Weighted average waits count taking improves into account. Weights
    /// span all 34 tile types by remaining-in-pool count.
    pub avg_improve_waits_count: f64,
    /// Estimated ron point at damaten.
    pub dama_point: f64,
    /// Estimated ron point under riichi (closed hands only; 0 if open).
    pub riichi_point: f64,
    /// `agari_rate% * (best_point + 1500) - 1500`. Per-round expected delta.
    pub mixed_round_point: f64,
    /// Yaku ids encountered across waits (riichienv yaku ids).
    pub yaku_ids: Vec<u32>,
    /// Per-wait score breakdown: damaten / riichi × ron / tsumo for every
    /// waiting tile. Empty unless the hand is tenpai with at least one wait.
    /// Open hands carry no riichi slots; a tile that can't win at all is
    /// still listed with every optional field `null`.
    pub win_scores: Vec<WaitScore>,
}

/// One discard option from a 14-tile state.
#[derive(Debug, Clone, Serialize)]
pub struct DiscardCandidate {
    /// The tile we'd discard.
    pub discard: String,
    /// Resulting 13-tile analysis.
    pub result: Hand13Result,
}

/// 14-tile hand analysis: list of discard candidates plus an optional
/// backwards-shanten list (drawing the discard would lower shanten).
#[derive(Debug, Clone, Serialize)]
pub struct Hand14Result {
    /// Current shanten of the 14-tile state (one less than the post-discard
    /// best path's shanten when in agari shape).
    pub shanten: i8,
    /// Discards that maintain the current shanten.
    pub maintain: Vec<DiscardCandidate>,
    /// Discards that would walk back one shanten level (kept for reference).
    pub backwards: Vec<DiscardCandidate>,
}

/// One opponent's view as seen by the active seat.
#[derive(Debug, Clone, Serialize)]
pub struct OpponentRisk {
    pub seat: u8,
    pub tenpai_rate: f64,
    /// Per-tile deal-in risk (%), 34-vector keyed by Tile34 index.
    pub risk: Vec<f64>,
    /// Whether this opponent is currently in riichi.
    pub is_riichi: bool,
}

/// Top-level analysis output.
#[derive(Debug, Clone, Serialize)]
pub struct AnalysisResult {
    pub seat: u8,
    pub turn: u8,
    pub shanten: i8,
    pub state: HandState,
    pub hand13: Option<Hand13Result>,
    pub hand14: Option<Hand14Result>,
    pub opponents: Vec<OpponentRisk>,
    /// Combined deal-in risk (%) across all opponents above the 15% tenpai-rate
    /// gate. Indexed by `Tile34`. `None` if there are no opponents.
    pub mixed_risk: Vec<f64>,
    /// Top-rated discard from `hand14.maintain` (attack-leaning).
    pub best_attack_discard: Option<String>,
    /// Lowest-risk discard from the active player's hand.
    pub best_defence_discard: Option<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum HandState {
    /// 13-tile waiting state (between tsumo opportunities).
    Wait13,
    /// 14-tile state (just drew, picking discard).
    Discard14,
}
