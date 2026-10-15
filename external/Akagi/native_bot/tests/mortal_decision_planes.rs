//! Regression guard for the decision-feature planes that used to be all zero.
//!
//! The 4p observation ends with 123 channels of single-player-table data: an EV
//! pair, per-discard required tiles, and the per-turn tenpai/win/EV planes.
//! `MortalSeatView` declared the fields but nothing populated them, so the model
//! was fed a block of zeros — which it reads as "no line has any value at all",
//! producing weak, passive play rather than an outright crash.
//!
//! This test drives a deterministic mid-game position and asserts those planes
//! carry signal. It does not compare against libriichi's trained tables (this
//! crate approximates them), so it checks structure and range, not exact values.
#![cfg(feature = "infer")]

use native_bot::mortal::encode_mortal_obs_4p;
use native_bot::mortal_obs::TILES;
use riichienv_core::replay::MjaiEvent;
use riichienv_core::rule::GameRule;
use riichienv_core::state::GameState;

const CHANNELS: usize = 1012;

fn ev(line: &str) -> MjaiEvent {
    serde_json::from_str(line).expect("valid mjai event")
}

/// A mid-game position: ~20 discards, a red five in hand, several dora in the
/// pond, and our own draw pending so `can_discard` is live.
fn midgame() -> GameState {
    let h0 = r#"["1m","2m","3m","5mr","6m","7m","3p","4p","5p","7p","1s","9s","E"]"#;
    let h1 = r#"["1m","4m","6m","9m","1p","3p","6p","9p","2s","5s","8s","S","W"]"#;
    let h2 = r#"["2m","5m","8m","2p","5pr","8p","3s","6s","9s","N","C","P","F"]"#;
    let h3 = r#"["3m","6m","9m","3p","6p","9p","1s","4s","7s","E","S","W","N"]"#;
    let start = format!(
        r#"{{"type":"start_kyoku","bakaze":"E","dora_marker":"5s","kyoku":1,"honba":0,"kyotaku":0,"oya":0,"scores":[25000,25000,25000,25000],"tehais":[{h0},{h1},{h2},{h3}]}}"#
    );

    let rule = GameRule::default();
    let mut st = GameState::new(0, true, None, 0, rule);
    st.apply_mjai_event(ev(r#"{"type":"start_game","names":["a","b","c","d"]}"#));
    st.apply_mjai_event(ev(&start));

    let plays = [
        (0u8, "1s", true), (1u8, "9m", true), (2u8, "1m", false), (3u8, "9p", true),
        (0u8, "9s", true), (1u8, "1m", false), (2u8, "6s", true), (3u8, "1s", true),
        (0u8, "1m", false), (1u8, "S", true), (2u8, "9s", true), (3u8, "4s", true),
        (0u8, "2m", false), (1u8, "W", true), (2u8, "N", false), (3u8, "7s", true),
        (0u8, "3m", false), (1u8, "1p", true), (2u8, "C", true), (3u8, "E", true),
    ];
    for (actor, pai, tsumogiri) in plays {
        st.apply_mjai_event(ev(&format!(
            r#"{{"type":"tsumo","actor":{actor},"pai":"{pai}"}}"#
        )));
        st.apply_mjai_event(ev(&format!(
            r#"{{"type":"dahai","actor":{actor},"pai":"{pai}","tsumogiri":{tsumogiri}}}"#
        )));
    }
    st.apply_mjai_event(ev(r#"{"type":"tsumo","actor":0,"pai":"2p"}"#));
    st
}

fn row(obs: &[f32], ch: usize) -> &[f32] {
    &obs[ch * TILES..(ch + 1) * TILES]
}

fn span_has_signal(obs: &[f32], start: usize, len: usize) -> bool {
    (start..(start + len).min(CHANNELS)).any(|c| row(obs, c).iter().any(|&v| v != 0.0))
}

fn count_lit(obs: &[f32], start: usize, len: usize) -> usize {
    (start..(start + len).min(CHANNELS))
        .filter(|&c| row(obs, c).iter().any(|&v| v != 0.0))
        .count()
}

#[test]
fn decision_planes_carry_signal() {
    let mut st = midgame();
    let (obs, legal) = encode_mortal_obs_4p(&mut st, 0, CHANNELS);
    assert_eq!(obs.len(), CHANNELS * TILES);
    assert!(
        legal.iter().any(|a| format!("{:?}", a.action_type).contains("Discard")),
        "the position must offer at least one discard"
    );

    // EV pair: [count/100k, count/30k] — must not be two zero planes.
    assert!(
        span_has_signal(&obs, 889, 2),
        "EV planes (889..891) are empty"
    );

    // Per-discard required tiles: a real position has at least one candidate
    // that is waiting on something.
    assert!(
        span_has_signal(&obs, 891, 68),
        "required-tile planes (891..959) are empty"
    );

    // The per-turn tenpai/win/EV planes are keyed by discard tile; with a live
    // discard choice these should be broadly populated.
    let sp_lit = count_lit(&obs, 961, 51);
    assert!(
        sp_lit >= 10,
        "SP table planes (961..1012) only lit {sp_lit}/51 channels"
    );

    // Dora ownership is derived from the indicators, so a round with a dora
    // indicator must light at least the unseen-dora plane.
    assert!(
        span_has_signal(&obs, 718, 4),
        "doras-owned planes (718..722) are empty"
    );
    assert!(
        span_has_signal(&obs, 722, 1),
        "doras-unseen plane (722) is empty"
    );

    // Everything the encoder emits must still be a valid feature value.
    assert!(
        obs.iter().all(|v| (0.0..=1.0).contains(v)),
        "encoder produced a value outside [0, 1]"
    );

    // The decision-flag block should no longer be a single lit channel: with a
    // discard available, at least the candidate and keep-shanten planes fill.
    let flags = count_lit(&obs, 874, 15);
    assert!(
        flags >= 2,
        "decision-flag planes (874..889) only lit {flags}/15 channels"
    );
}
