//! Rebuild the `mortal_obs_diff.py` fixture and dump the Rust encoder output.
//!
//! Run via `tools/mortal_obs_diff.py --emit`, then this test writes
//! `MORTAL_OBS_DIFF_OUT` (default `/tmp/mortal_rust_obs.json`), and
//! `tools/mortal_obs_diff.py --report` compares the two.
//!
//! Set `MORTAL_OBS_DIFF_STRICT=1` to make a mismatch fail the test instead of
//! only reporting it.
#![cfg(feature = "infer")]

use std::fs;
use std::path::PathBuf;

use native_bot::mortal_obs::{encode_mortal_obs, TILES};
use native_bot::state_features::MortalSeatView;

fn fixture_path() -> PathBuf {
    std::env::var("MORTAL_OBS_DIFF_FIXTURE")
        .map(PathBuf::from)
        .unwrap_or_else(|_| PathBuf::from("/tmp/mortalfix.json"))
}

fn out_path() -> PathBuf {
    std::env::var("MORTAL_OBS_DIFF_OUT")
        .map(PathBuf::from)
        .unwrap_or_else(|_| PathBuf::from("/tmp/mortal_rust_obs.json"))
}

fn ints(v: &serde_json::Value) -> Vec<u8> {
    v.as_array()
        .unwrap()
        .iter()
        .map(|x| x.as_u64().unwrap() as u8)
        .collect()
}

fn bools(v: &serde_json::Value) -> Vec<bool> {
    v.as_array().unwrap().iter().map(|x| x.as_bool().unwrap()).collect()
}

#[test]
fn compare_against_reference() {
    let path = fixture_path();
    let raw = match fs::read(&path) {
        Ok(r) => r,
        Err(_) => {
            eprintln!(
                "SKIP: {} not found. Run `python3 tools/mortal_obs_diff.py --emit` first.",
                path.display()
            );
            return;
        }
    };
    let fix: serde_json::Value = serde_json::from_slice(&raw).expect("valid fixture json");

    // Rebuild the view exactly as the fixture describes.
    let mut view = MortalSeatView::default();
    let tehai = ints(&fix["tehai"]);
    view.tehai.copy_from_slice(&tehai);
    let akas = bools(&fix["akas_in_hand"]);
    view.akas_in_hand.copy_from_slice(&akas);
    let scores: Vec<i32> = fix["scores"]
        .as_array()
        .unwrap()
        .iter()
        .map(|x| x.as_i64().unwrap() as i32)
        .collect();
    view.scores.copy_from_slice(&scores);
    view.rank = fix["rank"].as_u64().unwrap() as usize;
    view.kyoku = fix["kyoku"].as_u64().unwrap() as u8;
    view.honba = fix["honba"].as_u64().unwrap() as u8;
    view.kyotaku = fix["kyotaku"].as_u64().unwrap() as u32;
    view.round_wind = fix["round_wind"].as_u64().unwrap() as u8;
    view.seat_wind = fix["seat_wind"].as_u64().unwrap() as u8;
    view.tiles_left = fix["tiles_left"].as_u64().unwrap() as u8;
    view.dora_indicators = ints(&fix["dora_indicators"]);
    view.doras_seen = fix["doras_seen"].as_u64().unwrap() as u8;
    let owned = ints(&fix["doras_owned"]);
    view.doras_owned.copy_from_slice(&owned);

    let waits = bools(&fix["waits"]);
    view.waits.copy_from_slice(&waits);
    view.shanten = fix["shanten"].as_u64().unwrap() as u8;
    let rd = bools(&fix["riichi_declared"]);
    view.riichi_declared.copy_from_slice(&rd);
    let ra = bools(&fix["riichi_accepted"]);
    view.riichi_accepted.copy_from_slice(&ra);

    view.can_discard = true;
    // The reference's `discard_candidates` are de-aka'd indices; this crate's
    // fields hold raw tile ids, so map 0/4/8 -> 0/17/32.
    view.discard_candidates = vec![0, 17, 32];
    view.can_riichi = true;
    view.can_chi_low = fix["can_chi_low"].as_bool().unwrap();
    view.can_pon = true;

    // Kawa: rebuild the same discards the reference used. The reference's
    // `KawaItem.tile` is already a de-aka'd 0..33 index, so the raw ids here
    // must be a *plain* tile of that index - never 16/52/88, which are the red
    // fives and would set the aka channel.
    view.kawa[0] = native_bot::mortal_obs::build_kawa(
        &[17, 48, 80, 112], // de-aka 4, 12, 20, 28
        &[true, true, false, true],
        &[false, false, false, false],
    );
    view.kawa[1] = native_bot::mortal_obs::build_kawa_with_dora(
        &[0, 32, 108], // de-aka 0, 8, 27
        &[true, true, true],
        &[false, false, true],
        &[false, true, false], // tile 8 is the dora, per the reference fixture
    );
    view.kawa[2] = native_bot::mortal_obs::build_kawa(&[124, 132], &[false, true], &[false, false]);
    view.kawa_overview = [
        vec![4, 12, 20, 28],
        vec![0, 8, 27],
        vec![31, 33],
        vec![],
    ];
    view.tiles_seen = [1; TILES];

    let obs = encode_mortal_obs(&view);
    assert_eq!(obs.len(), 1012 * TILES);

    // Dump for the Python comparator.
    let payload = serde_json::json!({ "obs": obs });
    fs::write(out_path(), serde_json::to_vec(&payload).unwrap()).expect("write rust obs");

    // In-process comparison too, so the test is useful on its own.
    let reference: Vec<f32> = fix["ref"]
        .as_array()
        .unwrap()
        .iter()
        .map(|v| v.as_f64().unwrap() as f32)
        .collect();

    if reference.len() != obs.len() {
        panic!(
            "length mismatch: rust {} vs reference {}",
            obs.len(),
            reference.len()
        );
    }

    let diffs: Vec<usize> = obs
        .iter()
        .zip(reference.iter())
        .enumerate()
        .filter(|(_, (a, b))| (*a - *b).abs() > 1e-5)
        .map(|(i, _)| i)
        .collect();

    if diffs.is_empty() {
        eprintln!("IDENTICAL: all {} floats match the libriichi reference", obs.len());
        return;
    }

    eprintln!("{} of {} floats differ:", diffs.len(), obs.len());
    for &i in diffs.iter().take(30) {
        let (ch, tile) = (i / TILES, i % TILES);
        eprintln!(
            "  ch {ch:4} tile {tile:2}: rust {:.6}  ref {:.6}",
            obs[i], reference[i]
        );
    }

    if std::env::var("MORTAL_OBS_DIFF_STRICT").is_ok() {
        panic!("{} floats differ from the reference", diffs.len());
    }
}
