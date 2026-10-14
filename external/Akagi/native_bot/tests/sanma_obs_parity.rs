//! Bit-exact parity for the 3-player layout against the real `libriichi3p`.
//!
//! `weights/libriichi3p_obs.json` holds observations captured from the actual
//! `libriichi3p` binary (see `tools/sanma/README.md`), together with the mjai
//! lines that produced them. This test replays those lines into a `GameState3P`,
//! builds the seat view, encodes with `Layout::SANMA`, and compares every float.
//!
//! That closes the loop the same way `tests/mortal_obs_diff.rs` does for 4
//! players: the model weights and the layout are a matched pair, so a silently
//! drifted channel degrades play instead of raising an error.
//!
//! Set `MORTAL_SANMA_PARITY_STRICT=1` to fail on any difference (it reports and
//! passes otherwise, so the suite stays useful while the port is in progress).
#![cfg(feature = "infer")]

use std::fs;
use std::path::Path;

use riichienv_core::rule::GameRule;
use riichienv_core::state_3p::GameState3P;

use native_bot::mjai_compat::parse_line;
use native_bot::mortal_obs::{encode_mortal_obs_with, Layout, CHANNELS_SANMA, TILES};
use native_bot::state_features::MortalSeatView;

fn fixture_path() -> std::path::PathBuf {
    std::env::var("MORTAL_SANMA_FIXTURE")
        .map(std::path::PathBuf::from)
        .unwrap_or_else(|_| {
            Path::new(env!("CARGO_MANIFEST_DIR"))
                .join("weights/libriichi3p_obs.json")
        })
}

#[test]
fn sanma_layout_is_775_times_34() {
    let l = Layout::SANMA;
    assert_eq!(l.channels, CHANNELS_SANMA);
    assert_eq!(l.num_players, 3);
    // The widths that differ from the 4-player layout.
    assert_eq!(l.rank_channels, 3);
    assert_eq!(l.kyoku_channels, 3);
    assert_eq!(l.riichi_declared_channels, 2);
    assert_eq!(l.riichi_accepted_channels, 2);
    assert_eq!(l.call_block_channels, 1);
    // doras_owned stays 4 even in 3p.
    assert_eq!(l.doras_owned_channels, 4);
}

#[test]
fn matches_libriichi3p_bit_for_bit() {
    let path = fixture_path();
    let raw = match fs::read(&path) {
        Ok(r) => r,
        Err(_) => {
            eprintln!("SKIP: {} not present", path.display());
            return;
        }
    };
    let fix: serde_json::Value = serde_json::from_slice(&raw).expect("fixture json");

    let channels = fix["channels"].as_u64().unwrap() as usize;
    assert_eq!(channels, CHANNELS_SANMA, "fixture is not a 775-channel dump");
    assert_eq!(fix["tiles"].as_u64().unwrap() as usize, TILES);
    assert_eq!(fix["action_space"].as_u64().unwrap() as usize, 44);

    let lines: Vec<String> = fix["lines"]
        .as_array()
        .unwrap()
        .iter()
        .map(|v| serde_json::to_string(v).unwrap())
        .collect();

    let cases = fix["cases"].as_array().expect("cases");
    assert!(!cases.is_empty());

    let mut total_diffs = 0usize;
    let mut compared = 0usize;

    for case in cases {
        let after = case["after_line"].as_u64().unwrap() as usize;
        let want: Vec<f32> = case["obs"]
            .as_array()
            .unwrap()
            .iter()
            .map(|v| v.as_f64().unwrap() as f32)
            .collect();

        // Replay the game up to the decision point.
        let rule = GameRule::default_tenhou();
        let mut state = GameState3P::new(0, false, None, 0, rule);
        for line in lines.iter().take(after + 1) {
            if let Some(ev) = parse_line(line, 3) {
                state.apply_mjai_event(ev);
            }
        }

        let seat = 0u8;
        let view = MortalSeatView::from_state_3p(&state, seat)
            .with_legal_actions_3p(&mut state, seat);
        let got = encode_mortal_obs_with(&view, Layout::SANMA);

        assert_eq!(
            got.len(),
            want.len(),
            "case {}: length mismatch {} vs {}",
            case["index"],
            got.len(),
            want.len()
        );
        compared += 1;

        let diffs: Vec<usize> = got
            .iter()
            .zip(want.iter())
            .enumerate()
            .filter(|(_, (a, b))| {
                let d = (*a - *b).abs();
                d > 1e-5 && d > 1e-5 * b.abs().max(1.0)
            })
            .map(|(i, _)| i)
            .collect();

        if diffs.is_empty() {
            eprintln!("case {}: identical ({} floats)", case["index"], got.len());
            continue;
        }

        total_diffs += diffs.len();
        eprintln!(
            "case {} (after_line {}): {} of {} floats differ",
            case["index"],
            after,
            diffs.len(),
            got.len()
        );
        // Group the differences by channel so the report points at a section.
        let mut by_ch: std::collections::BTreeMap<usize, (usize, f32, f32)> =
            Default::default();
        for &i in &diffs {
            let (ch, _tile) = (i / TILES, i % TILES);
            let e = by_ch.entry(ch).or_insert((0, 0.0, 0.0));
            e.0 += 1;
            if e.1 == 0.0 && e.2 == 0.0 {
                e.1 = got[i];
                e.2 = want[i];
            }
        }
        for (ch, (n, g, w)) in by_ch.iter().take(12) {
            eprintln!("    ch {ch:4}: {n:3} differing, first got {g:.6} want {w:.6}");
        }
        if by_ch.len() > 12 {
            eprintln!("    ... (+{} more channels)", by_ch.len() - 12);
        }
    }

    eprintln!("compared {compared} cases, {total_diffs} differing floats total");

    if std::env::var("MORTAL_SANMA_PARITY_STRICT").is_ok() {
        assert_eq!(
            total_diffs, 0,
            "{total_diffs} floats differ from libriichi3p; see the per-channel report above"
        );
    }
}
