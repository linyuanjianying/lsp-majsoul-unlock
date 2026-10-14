//! Load a real Mortal checkpoint end to end.
//!
//! Point `MORTAL_MODEL_PTH` at a `.pth` (default
//! `/workspace/models/mortal.pth`) and this asserts the loader accepts it and
//! reports the right player count. It is the check that the key-namespace,
//! architecture auto-detection and head discovery all agree with a real file,
//! rather than only with the synthetic key sets in `mortal.rs`'s unit tests.
//!
//! Skipped when the file is absent, so CI without the weights stays green.
#![cfg(feature = "infer")]

use std::path::PathBuf;

use native_bot::mortal::{active_contract, load_and_activate_pth, CustomContract, MortalMeta};

fn model_path() -> PathBuf {
    std::env::var("MORTAL_MODEL_PTH")
        .map(PathBuf::from)
        .unwrap_or_else(|_| PathBuf::from("/workspace/models/mortal.pth"))
}

/// Expected player count, if the caller knows it.
fn expected_players() -> Option<u8> {
    std::env::var("MORTAL_MODEL_PLAYERS")
        .ok()
        .and_then(|v| v.parse().ok())
}

#[test]
fn loads_a_real_checkpoint() {
    let path = model_path();
    if !path.exists() {
        eprintln!("SKIP: {} not present", path.display());
        return;
    }

    let want = expected_players();
    let rc = load_and_activate_pth(want.unwrap_or(0), path.to_str().unwrap());

    assert!(
        rc > 0,
        "load_and_activate_pth returned {rc} for {}; \
         negative codes are -2 load, -3 player mismatch, -4 forward, -5 lock",
        path.display()
    );
    let players = rc as u8;
    assert!(players == 3 || players == 4, "unexpected player count {players}");
    if let Some(w) = want {
        assert_eq!(players, w);
    }

    // The contract must now be a live MortalPth with sane metadata.
    let contract = active_contract(players);
    match contract {
        CustomContract::MortalPth(model) => {
            let m: MortalMeta = model.meta;
            eprintln!(
                "loaded {}: players={} version={} in_channels={} conv_channels={} blocks={} actions={}",
                path.display(),
                m.num_players,
                m.version,
                m.in_channels,
                m.conv_channels,
                m.num_blocks,
                m.action_space
            );
            assert!(
                matches!(m.in_channels, 775 | 934 | 938 | 942 | 1012),
                "in_channels {} is not one of the known Mortal widths",
                m.in_channels
            );
            assert!(m.num_blocks > 0, "no res blocks detected");
            assert_eq!(m.conv_channels, 256, "expected 256 conv channels");
            assert!(
                matches!(m.action_space, 44 | 46),
                "action space {} should be 44 (sanma) or 46 (4p)",
                m.action_space
            );
            // The version and width must agree.
            let want_version = match m.in_channels {
                1012 => 4,
                942 => 2,
                938 => 1,
                934 => 3,
                775 => 5,
                _ => unreachable!(),
            };
            assert_eq!(
                m.version, want_version,
                "in_channels {} should map to version {want_version}",
                m.in_channels
            );
        }
        CustomContract::AkagiOnnx => panic!("expected a MortalPth contract, got AkagiOnnx"),
        CustomContract::MortalOnnx(m) => {
            panic!("expected a MortalPth contract, got MortalOnnx {m:?}")
        }
        CustomContract::MortalPth(_) => unreachable!(),
    }
}

/// A forward pass on the loaded model must produce finite logits of the right
/// width. This catches a head that loads but mis-splits (e.g. picking the
/// `aux_net` tensor instead of the dueling head).
#[test]
fn loaded_model_forwards_finite_logits() {
    let path = model_path();
    if !path.exists() {
        eprintln!("SKIP: {} not present", path.display());
        return;
    }

    let rc = load_and_activate_pth(expected_players().unwrap_or(0), path.to_str().unwrap());
    assert!(rc > 0, "load failed with {rc}");
    let players = rc as u8;

    let contract = active_contract(players);
    let CustomContract::MortalPth(model) = contract else {
        panic!("expected MortalPth");
    };

    let meta = model.meta;
    let obs = vec![0.0f32; meta.in_channels * 34];
    let logits = model.forward_logits(&obs).expect("forward");

    assert_eq!(
        logits.len(),
        meta.action_space,
        "forward_logits returns per-action Q, i.e. ACTION_SPACE values \
         (the 1 + ACTION_SPACE head width is combined internally)"
    );
    assert!(
        logits.iter().all(|v| v.is_finite()),
        "logits must be finite; NaN here means the weights are being read wrong"
    );
}
