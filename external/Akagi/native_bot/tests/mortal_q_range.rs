//! Regression guard for the two defects that made a real Mortal checkpoint
//! produce plausible-looking but wildly wrong Q values:
//!
//! 1. `ChannelAttention` was looked up in the wrong scope. It is a *sibling* of
//!    `res_unit` (`encoder.net.N.ca.*`), but both probes used the same scope, so
//!    every block silently ran without its attention gate. The trunk then grew
//!    by ~5 orders of magnitude across 54 blocks.
//! 2. The leading `BatchNorm` was folded into the following convolution's
//!    weights, but the activation sits *between* them. `conv(actv(bn(x)))` is not
//!    `conv_folded(actv(x))` for a nonlinear activation.
//!
//! Both produced finite, correctly-shaped logits, so nothing downstream could
//! detect them. This test bounds the output magnitude, which is the cheap
//! invariant that catches such a mismatch.
#![cfg(feature = "infer")]

use std::path::Path;

use native_bot::mortal::{MortalPthModel, MORTAL_ACTION_SPACE_4P, MORTAL_TILE_DIM};

/// A Mortal 4p Q head returns score-like values. Anything far outside this range
/// means the trunk or the head is wired up incorrectly, even though the tensor
/// is finite and correctly shaped.
const SANE_Q_ABS_LIMIT: f32 = 1.0e3;

fn model_path() -> Option<std::path::PathBuf> {
    let p = std::env::var("MORTAL_MODEL_PTH").ok()?;
    let path = std::path::PathBuf::from(p);
    path.is_file().then_some(path)
}

#[test]
fn real_checkpoint_produces_bounded_q_values() {
    let Some(path) = model_path() else {
        eprintln!("SKIP: set MORTAL_MODEL_PTH to a Mortal .pth to run this check");
        return;
    };
    let model = MortalPthModel::load_from_file(Path::new(&path))
        .expect("checkpoint should load");
    assert_eq!(model.meta.action_space, MORTAL_ACTION_SPACE_4P);

    let n = model.meta.in_channels * MORTAL_TILE_DIM;

    // Zero observation: exercises the bias paths and the residual sum without
    // any input signal.
    let zeros = vec![0.0f32; n];
    let q0 = model.forward_logits(&zeros).expect("forward on zeros");
    assert_eq!(q0.len(), MORTAL_ACTION_SPACE_4P);
    let lo0 = q0.iter().cloned().fold(f32::INFINITY, f32::min);
    let hi0 = q0.iter().cloned().fold(f32::NEG_INFINITY, f32::max);
    assert!(
        lo0.abs() <= SANE_Q_ABS_LIMIT && hi0.abs() <= SANE_Q_ABS_LIMIT,
        "Q range on a zero observation is {lo0:.3}..{hi0:.3}; a missing channel \
         attention or a mis-folded BatchNorm inflates the trunk by orders of \
         magnitude. Limit is {SANE_Q_ABS_LIMIT}."
    );

    // A constant non-zero observation: exercises the convolutional paths, which
    // is where a magnitude blow-up is most visible.
    let ones = vec![0.5f32; n];
    let q1 = model.forward_logits(&ones).expect("forward on constants");
    let lo1 = q1.iter().cloned().fold(f32::INFINITY, f32::min);
    let hi1 = q1.iter().cloned().fold(f32::NEG_INFINITY, f32::max);
    assert!(
        lo1.abs() <= SANE_Q_ABS_LIMIT && hi1.abs() <= SANE_Q_ABS_LIMIT,
        "Q range on a constant observation is {lo1:.3}..{hi1:.3} (limit {SANE_Q_ABS_LIMIT})"
    );

    // The head must actually discriminate: a constant across all actions would
    // mean the sample value or the advantages are not being combined.
    let spread = hi1 - lo1;
    assert!(
        spread > 1e-3,
        "Q values are constant across all actions (spread {spread}); the dueling \
         head is probably not being combined correctly"
    );

    println!("Q ranges — zeros: {lo0:.3}..{hi0:.3}, const: {lo1:.3}..{hi1:.3}");
}
