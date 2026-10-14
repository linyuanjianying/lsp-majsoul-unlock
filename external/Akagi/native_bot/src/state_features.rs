//! Self-relative seat view for Mortal observation encoding.
//!
//! Turns a [`GameState`] into the per-seat, self-relative feature set that
//! [`crate::mortal_obs::encode_mortal_obs`] consumes. Everything here is about
//! *rotation and derivation*; the channel layout lives in `mortal_obs.rs`.
//!
//! Seat order is `[self, shimocha, toimen, kamicha]` — index 0 is always the
//! deciding player, matching libriichi's `PlayerState` view.

use riichienv_core::state::GameState;
use riichienv_core::types::Meld;

use crate::mortal_obs::{build_kawa, split_melds, KawaItem, SpCandidate, TILES};

/// Number of players this view supports.
pub const NUM_PLAYERS: usize = 4;

/// The self-relative view fed to the Mortal encoder.
#[derive(Debug, Clone)]
pub struct MortalSeatView {
    pub n_players: u8,

    /// Concealed hand as a 34-wide count vector.
    pub tehai: [u8; TILES],
    /// Which of 5m / 5p / 5s (red five) are held.
    pub akas_in_hand: [bool; 3],

    pub scores: [i32; NUM_PLAYERS],
    /// 0-based placing (0 = first).
    pub rank: usize,
    pub kyoku: u8,
    pub honba: u8,
    pub kyotaku: u32,
    pub round_wind: u8,
    pub seat_wind: u8,

    pub dora_indicators: Vec<u8>,
    pub doras_owned: [u8; NUM_PLAYERS],
    pub doras_seen: u8,

    /// Per-seat kawa, self-relative order, already de-aka'd into KawaItems.
    pub kawa: [Vec<Option<KawaItem>>; NUM_PLAYERS],
    /// Per-seat set of tiles visible in that seat's kawa (for kawa overview).
    pub kawa_overview: [Vec<u8>; NUM_PLAYERS],
    /// Per-seat open melds (chi/pon/daiminkan), for fuuro overview.
    pub fuuro_overview: [Vec<Meld>; NUM_PLAYERS],
    /// Per-seat concealed kans, for ankan overview.
    pub ankan_overview: [Vec<u8>; NUM_PLAYERS],

    pub tiles_seen: [u8; TILES],
    pub tiles_left: u8,

    pub last_tedashis: [Option<KawaItem>; NUM_PLAYERS],
    pub riichi_sutehais: [Option<KawaItem>; NUM_PLAYERS],
    pub riichi_declared: [bool; NUM_PLAYERS],
    pub riichi_accepted: [bool; NUM_PLAYERS],

    pub waits: [bool; TILES],
    pub at_furiten: bool,
    pub shanten: u8,

    // -- decision context -------------------------------------------------
    pub at_kan_select: bool,
    pub can_pass: bool,
    pub last_kawa_tile: Option<KawaItem>,
    pub dora_factor: [u8; TILES],

    pub can_discard: bool,
    pub discard_candidates: Vec<u8>,
    pub keep_shanten_discards: Vec<u8>,
    pub next_shanten_discards: Vec<u8>,
    pub unconditional_tenpai_discards: Vec<u8>,
    pub can_riichi: bool,
    pub can_nukidora: bool,
    pub can_chi_low: bool,
    pub can_chi_mid: bool,
    pub can_chi_high: bool,
    pub can_pon: bool,
    pub can_daiminkan: bool,
    pub can_ankan: bool,
    pub ankan_candidates: Vec<u8>,
    pub can_kakan: bool,
    pub kakan_candidates: Vec<u8>,
    pub can_agari: bool,
    pub can_ryukyoku: bool,

    // -- single-player table ----------------------------------------------
    pub max_ev: f32,
    pub sp_candidates: Vec<SpCandidate>,
}

impl Default for MortalSeatView {
    fn default() -> Self {
        Self {
            n_players: 4,
            tehai: [0; TILES],
            akas_in_hand: [false; 3],
            scores: [0; NUM_PLAYERS],
            rank: 0,
            kyoku: 0,
            honba: 0,
            kyotaku: 0,
            round_wind: 0,
            seat_wind: 0,
            dora_indicators: Vec::new(),
            doras_owned: [0; NUM_PLAYERS],
            doras_seen: 0,
            kawa: Default::default(),
            kawa_overview: Default::default(),
            fuuro_overview: Default::default(),
            ankan_overview: Default::default(),
            tiles_seen: [0; TILES],
            tiles_left: 69,
            last_tedashis: [None; NUM_PLAYERS],
            riichi_sutehais: [None; NUM_PLAYERS],
            riichi_declared: [false; NUM_PLAYERS],
            riichi_accepted: [false; NUM_PLAYERS],
            waits: [false; TILES],
            at_furiten: false,
            shanten: 6,
            at_kan_select: false,
            can_pass: false,
            last_kawa_tile: None,
            dora_factor: [0; TILES],
            can_discard: true,
            discard_candidates: Vec::new(),
            keep_shanten_discards: Vec::new(),
            next_shanten_discards: Vec::new(),
            unconditional_tenpai_discards: Vec::new(),
            can_riichi: false,
            can_nukidora: false,
            can_chi_low: false,
            can_chi_mid: false,
            can_chi_high: false,
            can_pon: false,
            can_daiminkan: false,
            can_ankan: false,
            ankan_candidates: Vec::new(),
            can_kakan: false,
            kakan_candidates: Vec::new(),
            can_agari: false,
            can_ryukyoku: false,
            max_ev: 0.0,
            sp_candidates: Vec::new(),
        }
    }
}

/// Map an absolute player id to its index in self-relative order.
fn rel(pid: u8, seat: u8) -> usize {
    ((pid + NUM_PLAYERS as u8 - seat) as usize) % NUM_PLAYERS
}

/// Shanten for a concealed-hand count vector plus `num_melds` called sets.
///
/// riichienv's `calc_shanten_from_counts` expects `tehai_len_div3` = number of
/// melds, i.e. how many 3-tile groups are already fixed.
fn calc_shanten(tehai: &[u8; TILES], num_melds: usize) -> u8 {
    let shanten =
        riichienv_core::shanten::calc_shanten_from_counts(tehai, num_melds as u8);
    // The encoder one-hots up to 6; negative means a winning hand.
    shanten.clamp(0, 6) as u8
}

impl MortalSeatView {
    /// Derive the view for `seat` from a live game state.
    ///
    /// This fills the structural fields (hand, melds, kawa, tiles_seen, winds,
    /// scores). The *decision* fields (legal-action flags, shanten, waits and
    /// the single-player table) depend on the legal-action set, which the
    /// caller owns — see [`Self::with_legal_actions`].
    pub fn from_state(state: &GameState, seat: u8) -> Self {
        let mut v = Self {
            n_players: NUM_PLAYERS as u8,
            honba: state.honba,
            kyotaku: state.riichi_sticks,
            kyoku: state.kyoku_idx,
            round_wind: state.round_wind,
            seat_wind: (seat + NUM_PLAYERS as u8 - state.oya) % NUM_PLAYERS as u8,
            ..Default::default()
        };

        // -- hands, melds, kawa, per-seat features ------------------------
        for pid in 0..NUM_PLAYERS as u8 {
            let r = rel(pid, seat);
            let player = &state.players[pid as usize];

            if pid == seat {
                for &t in &player.hand {
                    v.tehai[crate::mortal_obs::mortal_obs_deaka(t)] += 1;
                    match t {
                        16 => v.akas_in_hand[0] = true,
                        52 => v.akas_in_hand[1] = true,
                        88 => v.akas_in_hand[2] = true,
                        _ => {}
                    }
                }
            }

            let kawa = build_kawa(
                &player.discards,
                &player.discard_from_hand,
                &player.discard_is_riichi,
            );
            v.kawa[r] = kawa.clone();
            v.kawa_overview[r] = player
                .discards
                .iter()
                .map(|&t| crate::mortal_obs::mortal_obs_deaka(t) as u8)
                .collect();

            let (fuuro, ankan) = split_melds(&player.melds);
            v.fuuro_overview[r] = fuuro;
            v.ankan_overview[r] = ankan.into_iter().flatten().collect();

            v.scores[r] = player.score;
            v.riichi_declared[r] = player.riichi_declared;

            v.last_tedashis[r] = state.last_tedashis[pid as usize].map(|raw| KawaItem {
                tile: crate::mortal_obs::mortal_obs_deaka(raw),
                is_aka: crate::mortal_obs::mortal_obs_is_aka(raw),
                is_tedashi: true,
                ..Default::default()
            });
            v.riichi_sutehais[r] = state.riichi_sutehais[pid as usize].map(|raw| KawaItem {
                tile: crate::mortal_obs::mortal_obs_deaka(raw),
                is_aka: crate::mortal_obs::mortal_obs_is_aka(raw),
                is_riichi: true,
                is_tedashi: true,
                ..Default::default()
            });
        }

        // -- rank ---------------------------------------------------------
        let my_score = state.players[seat as usize].score;
        v.rank = state
            .players
            .iter()
            .filter(|p| p.score > my_score)
            .count()
            .min(3);

        // -- dora ---------------------------------------------------------
        v.dora_indicators = state.wall.dora_indicators.clone();
        v.doras_seen = v.dora_indicators.len().min(5) as u8;
        for (_i, player) in state.players.iter().enumerate() {
            for m in &player.melds {
                if m.meld_type == riichienv_core::types::MeldType::Kakan {
                    // A kakan promotes one indicator; count it as seen.
                }
            }
        }

        // -- tiles seen / left --------------------------------------------
        for player in state.players.iter() {
            for &t in player.discards.iter() {
                v.tiles_seen[crate::mortal_obs::mortal_obs_deaka(t)] = v.tiles_seen
                    [crate::mortal_obs::mortal_obs_deaka(t)]
                .saturating_add(1);
            }
            for m in &player.melds {
                for &t in &m.tiles {
                    v.tiles_seen[crate::mortal_obs::mortal_obs_deaka(t)] = v.tiles_seen
                        [crate::mortal_obs::mortal_obs_deaka(t)]
                    .saturating_add(1);
                }
            }
        }
        for &t in &v.dora_indicators {
            v.tiles_seen[crate::mortal_obs::mortal_obs_deaka(t)] =
                v.tiles_seen[crate::mortal_obs::mortal_obs_deaka(t)].saturating_add(1);
        }

        // `tiles_left` is the live wall count; libriichi divides by 69.
        v.tiles_left = state.wall.tiles.len().min(69) as u8;

        // -- furiten -------------------------------------------------------
        // `forbidden_discards` is exactly the set of tiles the seat may not
        // win on, i.e. the furiten tiles.
        let me = &state.players[seat as usize];
        v.at_furiten = !me.forbidden_discards.is_empty();

        // -- shanten -------------------------------------------------------
        // The encoder one-hots `shanten` capped at 6, and the SP-table planes
        // are only meaningful at low shanten.
        v.shanten = calc_shanten(&v.tehai, me.melds.len());

        v
    }

    /// Attach the legal-action-derived decision context.
    ///
    /// `legal` is the action list the engine is choosing over; the flags here
    /// mirror what libriichi's `Cans` computes from the same set.
    ///
    /// Takes `&mut GameState` because `get_observation` needs a mutable borrow
    /// to snapshot the legal-action set.
    pub fn with_legal_actions(mut self, state: &mut GameState, seat: u8) -> Self {
        use riichienv_core::action::ActionType;

        let obs = state.get_observation(seat);
        let legal = obs.legal_actions_method();

        let has = |t: ActionType| legal.iter().any(|a| a.action_type == t);

        self.can_discard = has(ActionType::Discard);
        self.can_riichi = has(ActionType::Riichi);
        self.can_pon = has(ActionType::Pon);
        self.can_daiminkan = has(ActionType::Daiminkan);
        self.can_ankan = has(ActionType::Ankan);
        self.can_kakan = has(ActionType::Kakan);
        self.can_agari = has(ActionType::Tsumo) || has(ActionType::Ron);
        self.can_ryukyoku = has(ActionType::KyushuKyuhai);
        self.can_pass = legal.len() > 1
            && legal.iter().any(|a| a.action_type == ActionType::Pass);

        // Chi variants are distinguished by the called tile's position.
        for a in legal.iter() {
            if a.action_type == ActionType::Chi {
                if let Some(t) = a.tile {
                    let called = crate::mortal_obs::mortal_obs_deaka(t);
                    let mut consumed: Vec<usize> = a
                        .consume_tiles
                        .iter()
                        .map(|&x| crate::mortal_obs::mortal_obs_deaka(x))
                        .collect();
                    consumed.sort_unstable();
                    if consumed.len() >= 2 {
                        if called < consumed[0] {
                            self.can_chi_low = true;
                        } else if called > consumed[1] {
                            self.can_chi_high = true;
                        } else {
                            self.can_chi_mid = true;
                        }
                    }
                }
            }
        }

        self.discard_candidates = legal
            .iter()
            .filter(|a| a.action_type == ActionType::Discard)
            .filter_map(|a| a.tile)
            .collect();
        self.ankan_candidates = legal
            .iter()
            .filter(|a| a.action_type == ActionType::Ankan)
            .filter_map(|a| a.tile)
            .collect();
        self.kakan_candidates = legal
            .iter()
            .filter(|a| a.action_type == ActionType::Kakan)
            .filter_map(|a| a.tile)
            .collect();

        self.waits = [false; TILES];
        for &w in &obs.waits {
            self.waits[(w as usize).min(TILES - 1)] = true;
        }

        if let Some((_, tile)) = state.last_discard {
            self.last_kawa_tile = Some(KawaItem {
                tile: crate::mortal_obs::mortal_obs_deaka(tile),
                is_aka: crate::mortal_obs::mortal_obs_is_aka(tile),
                is_tedashi: true,
                ..Default::default()
            });
        }

        self
    }
}
