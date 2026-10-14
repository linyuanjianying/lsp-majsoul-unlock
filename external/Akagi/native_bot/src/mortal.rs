//! Native Mortal (.pth and .onnx) support for 4-player (v1..=v4) and 3-player (v5 sanma).
//!
//! Provides:
//! - Direct PyTorch `.pth` checkpoint loading via `candle_nn::VarBuilder` with
//!   automatic architecture inspection (`in_channels`, `conv_channels`, `num_blocks`,
//!   `version`, `action_space`) and load-time `BatchNorm1d` folding.
//! - Mortal observation feature encoding (`[C, 34]`) for 4P (`1012`, `948`, `938`, `450`)
//!   and 3P (`775`) from `riichienv-core` game states.
//! - Mortal 46-action (4P) and 44-action (3P) legal action indexing and softmax ranking.

use std::collections::btree_map::Entry;
use std::collections::BTreeMap;
use std::path::Path;
use std::sync::{Arc, Mutex, OnceLock};

use candle_core::{DType, Device, Result as CandleResult, Tensor};
use candle_nn::VarBuilder;
use riichienv_core::action::{Action, ActionType};
use riichienv_core::state::GameState;
use riichienv_core::state_3p::GameState3P;

use crate::adapt::{enc_input_3p, enc_input_4p};
use crate::obs::EncInput;
use crate::tiles::{is_aka, next_dora_tile34, tile34, TILE34_TO_COMPACT};

/// Mortal 4P action space size (`libriichi::consts::ACTION_SPACE`).
pub const MORTAL_ACTION_SPACE_4P: usize = 46;
/// Mortal 3P (`mortal-sanma`) action space size.
pub const MORTAL_ACTION_SPACE_3P: usize = 44;
/// Mortal tile axis width (both 4P and 3P `mortal-sanma` use 34 tile columns).
pub const MORTAL_TILE_DIM: usize = 34;

/// Metadata describing an active Mortal model (.pth or .onnx).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct MortalMeta {
    pub num_players: u8,
    pub version: u8,
    pub in_channels: usize,
    pub conv_channels: usize,
    pub num_blocks: usize,
    pub action_space: usize,
}

/// Active custom model contract for a player count slot (4P or 3P).
#[derive(Clone)]
pub enum CustomContract {
    /// Standard Akagi `.onnx` contract (`[1, 39, 34] -> [1, 82]` or `[1, 37, 27] -> [1, 60]`).
    AkagiOnnx,
    /// Mortal `.onnx` evaluated via Android `OrtSession` callback.
    MortalOnnx(MortalMeta),
    /// Mortal `.pth` evaluated directly in Rust via Candle.
    MortalPth(Arc<MortalPthModel>),
}

static CONTRACT_4P: OnceLock<Mutex<CustomContract>> = OnceLock::new();
static CONTRACT_3P: OnceLock<Mutex<CustomContract>> = OnceLock::new();

fn contract_slot(num_players: u8) -> &'static Mutex<CustomContract> {
    if num_players == 3 {
        CONTRACT_3P.get_or_init(|| Mutex::new(CustomContract::AkagiOnnx))
    } else {
        CONTRACT_4P.get_or_init(|| Mutex::new(CustomContract::AkagiOnnx))
    }
}

/// Get the currently configured custom model contract for `num_players`.
pub fn active_contract(num_players: u8) -> CustomContract {
    contract_slot(num_players)
        .lock()
        .map(|g| g.clone())
        .unwrap_or(CustomContract::AkagiOnnx)
}

/// Configure a custom `.onnx` contract (`kind = 0` for Akagi, `kind = 1` for Mortal).
pub fn set_custom_onnx_contract(
    num_players: u8,
    kind: u8,
    version: u8,
    in_channels: usize,
    action_space: usize,
) {
    if let Ok(mut guard) = contract_slot(num_players).lock() {
        if kind == 1 {
            *guard = CustomContract::MortalOnnx(MortalMeta {
                num_players,
                version,
                in_channels,
                conv_channels: 0,
                num_blocks: 0,
                action_space,
            });
        } else {
            *guard = CustomContract::AkagiOnnx;
        }
    }
}

/// Load, validate, and activate a Mortal `.pth` checkpoint from `path`.
pub fn load_and_activate_pth(expected_players: u8, path: &str) -> i32 {
    let model = match MortalPthModel::load_from_file(Path::new(path)) {
        Ok(m) => m,
        Err(_) => return -2,
    };
    let players = model.meta.num_players;
    if expected_players != 0 && expected_players != players {
        return -3;
    }
    let dummy_obs = vec![0.0f32; model.meta.in_channels * MORTAL_TILE_DIM];
    match model.forward_logits(&dummy_obs) {
        Ok(logits)
            if logits.len() == model.meta.action_space
                && logits.iter().all(|v| v.is_finite()) => {}
        _ => return -4,
    }
    if let Ok(mut guard) = contract_slot(players).lock() {
        *guard = CustomContract::MortalPth(Arc::new(model));
        players as i32
    } else {
        -5
    }
}

struct FoldedConv1d {
    weight: Tensor,
    bias: Tensor,
    padding: usize,
}

impl FoldedConv1d {
    fn forward(&self, x: &Tensor) -> CandleResult<Tensor> {
        let y = x.conv1d(&self.weight, self.padding, 1, 1, 1)?;
        y.broadcast_add(&self.bias)
    }
}

struct ChannelAttention {
    fc1_w: Tensor,
    fc1_b: Option<Tensor>,
    fc2_w: Tensor,
    fc2_b: Option<Tensor>,
}

impl ChannelAttention {
    fn forward(&self, x: &Tensor) -> CandleResult<Tensor> {
        let y = x.mean(2)?;
        let mut y = y.matmul(&self.fc1_w.t()?)?;
        if let Some(b) = &self.fc1_b {
            y = y.broadcast_add(b)?;
        }
        let y = y.relu()?;
        let mut y = y.matmul(&self.fc2_w.t()?)?;
        if let Some(b) = &self.fc2_b {
            y = y.broadcast_add(b)?;
        }
        let scale = candle_nn::ops::sigmoid(&y)?.unsqueeze(2)?;
        x.broadcast_mul(&scale)
    }
}

struct ResBlock {
    conv1: FoldedConv1d,
    conv2: FoldedConv1d,
    ca: Option<ChannelAttention>,
    use_silu: bool,
}

impl ResBlock {
    fn activate(&self, x: &Tensor) -> CandleResult<Tensor> {
        if self.use_silu {
            candle_nn::ops::silu(x)
        } else {
            x.relu()
        }
    }

    fn forward(&self, x: &Tensor) -> CandleResult<Tensor> {
        let mut out = self.conv1.forward(x)?;
        out = self.activate(&out)?;
        out = self.conv2.forward(&out)?;
        if let Some(ca) = &self.ca {
            out = ca.forward(&out)?;
        }
        let sum = (x + &out)?;
        self.activate(&sum)
    }
}

enum OutputHead {
    DuelingCombined {
        weight: Tensor,
        bias: Tensor,
        action_space: usize,
        feat_dim: usize,
    },
    DuelingSplit {
        v_w: Tensor,
        v_b: Tensor,
        a_w: Tensor,
        a_b: Tensor,
        feat_dim: usize,
    },
    Direct {
        weight: Tensor,
        bias: Tensor,
        feat_dim: usize,
    },
}

pub struct MortalPthModel {
    pub meta: MortalMeta,
    entry_conv: FoldedConv1d,
    blocks: Vec<ResBlock>,
    post_bn_scale: Option<(Tensor, Tensor)>,
    head_fc1: Option<(Tensor, Tensor)>,
    head: OutputHead,
    use_silu: bool,
}

impl MortalPthModel {
    pub fn load_from_file(path: &Path) -> CandleResult<Self> {
        let dev = Device::Cpu;
        let brain_vb = Self::open_brain_vb(path, &dev)?;
        let dqn_vb = Self::open_dqn_vb(path, &dev)?;
        let (entry_conv, conv_channels, in_channels) = Self::load_entry_conv(&brain_vb, &dev)?;

        let (num_players, version, action_space) = match in_channels {
            775 => (3u8, 5u8, MORTAL_ACTION_SPACE_3P),
            1012 => (4u8, 4u8, MORTAL_ACTION_SPACE_4P),
            948 => (4u8, 3u8, MORTAL_ACTION_SPACE_4P),
            938 => (4u8, 2u8, MORTAL_ACTION_SPACE_4P),
            450 => (4u8, 1u8, MORTAL_ACTION_SPACE_4P),
            other if (600..850).contains(&other) => (3u8, 5u8, MORTAL_ACTION_SPACE_3P),
            _ => (4u8, 4u8, MORTAL_ACTION_SPACE_4P),
        };

        let use_silu = version >= 2;
        let mut blocks = Vec::new();
        for idx in 0..128usize {
            if let Some(block) = Self::try_load_res_block(&brain_vb, idx, conv_channels, use_silu, &dev)? {
                blocks.push(block);
            } else if idx > 0 {
                break;
            }
        }
        if blocks.is_empty() {
            return Err(candle_core::Error::Msg(
                "No ResNet blocks found in Mortal .pth checkpoint".into(),
            ));
        }
        let num_blocks = blocks.len();
        let post_bn_scale = Self::try_load_standalone_bn(&brain_vb, conv_channels)?;
        let (head_fc1, head) = Self::load_output_head(&dqn_vb, &brain_vb, conv_channels, action_space)?;

        Ok(Self {
            meta: MortalMeta {
                num_players,
                version,
                in_channels,
                conv_channels,
                num_blocks,
                action_space,
            },
            entry_conv,
            blocks,
            post_bn_scale,
            head_fc1,
            head,
            use_silu,
        })
    }

    pub fn forward_logits(&self, obs: &[f32]) -> CandleResult<Vec<f32>> {
        let expected = self.meta.in_channels * MORTAL_TILE_DIM;
        if obs.len() != expected {
            return Err(candle_core::Error::Msg(format!(
                "Mortal obs length mismatch: expected {expected}, got {}",
                obs.len()
            )));
        }
        let dev = Device::Cpu;
        let x = Tensor::from_slice(obs, (1, self.meta.in_channels, MORTAL_TILE_DIM), &dev)?;

        let mut h = self.entry_conv.forward(&x)?;
        h = if self.use_silu {
            candle_nn::ops::silu(&h)?
        } else {
            h.relu()?
        };

        for block in &self.blocks {
            h = block.forward(&h)?;
        }

        if let Some((scale, bias)) = &self.post_bn_scale {
            h = h.broadcast_mul(scale)?.broadcast_add(bias)?;
            h = if self.use_silu {
                candle_nn::ops::silu(&h)?
            } else {
                h.relu()?
            };
        }

        let target_feat_dim = match &self.head_fc1 {
            Some((w, _)) => w.dims()[1],
            None => match &self.head {
                OutputHead::DuelingCombined { feat_dim, .. }
                | OutputHead::DuelingSplit { feat_dim, .. }
                | OutputHead::Direct { feat_dim, .. } => *feat_dim,
            },
        };

        let mut feat = if target_feat_dim == self.meta.conv_channels * MORTAL_TILE_DIM {
            h.flatten_from(1)?
        } else {
            h.mean(2)?
        };

        if let Some((fc1_w, fc1_b)) = &self.head_fc1 {
            feat = feat.matmul(&fc1_w.t()?)?.broadcast_add(fc1_b)?;
            feat = if self.use_silu {
                candle_nn::ops::silu(&feat)?
            } else {
                feat.relu()?
            };
        }

        let q = match &self.head {
            OutputHead::DuelingCombined {
                weight,
                bias,
                action_space,
                ..
            } => {
                let va = feat.matmul(&weight.t()?)?.broadcast_add(bias)?;
                let v = va.narrow(1, 0, 1)?;
                let a = va.narrow(1, 1, *action_space)?;
                let a_mean = a.mean_keepdim(1)?;
                v.broadcast_add(&a.broadcast_sub(&a_mean)?)?
            }
            OutputHead::DuelingSplit {
                v_w,
                v_b,
                a_w,
                a_b,
                ..
            } => {
                let v = feat.matmul(&v_w.t()?)?.broadcast_add(v_b)?;
                let a = feat.matmul(&a_w.t()?)?.broadcast_add(a_b)?;
                let a_mean = a.mean_keepdim(1)?;
                v.broadcast_add(&a.broadcast_sub(&a_mean)?)?
            }
            OutputHead::Direct { weight, bias, .. } => {
                feat.matmul(&weight.t()?)?.broadcast_add(bias)?
            }
        };

        q.squeeze(0)?.to_vec1::<f32>()
    }

    fn open_brain_vb<'a>(path: &Path, dev: &Device) -> CandleResult<VarBuilder<'a>> {
        for key in ["mortal", "brain", "model", "state_dict"] {
            if let Ok(vb) = VarBuilder::from_pth_with_state(path, DType::F32, key, dev) {
                if Self::has_any_entry_conv(&vb) {
                    return Ok(vb);
                }
            }
        }
        let flat = VarBuilder::from_pth(path, DType::F32, dev)?;
        for prefix in ["mortal", "brain", "model"] {
            let sub = flat.pp(prefix);
            if Self::has_any_entry_conv(&sub) {
                return Ok(sub);
            }
        }
        Ok(flat)
    }

    fn open_dqn_vb<'a>(path: &Path, dev: &Device) -> CandleResult<VarBuilder<'a>> {
        for key in ["current_dqn", "dqn", "target_dqn", "head", "policy"] {
            if let Ok(vb) = VarBuilder::from_pth_with_state(path, DType::F32, key, dev) {
                if Self::has_any_head_tensor(&vb) {
                    return Ok(vb);
                }
            }
        }
        let flat = VarBuilder::from_pth(path, DType::F32, dev)?;
        for prefix in ["current_dqn", "dqn", "head", "mortal"] {
            let sub = flat.pp(prefix);
            if Self::has_any_head_tensor(&sub) {
                return Ok(sub);
            }
        }
        Ok(flat)
    }

    fn entry_conv_candidates() -> &'static [&'static str] {
        &[
            "entry.0",
            "entry",
            "stem.0",
            "stem",
            "conv_in.0",
            "conv_in",
            "in_conv.0",
            "in_conv",
            "backbone.entry.0",
            "backbone.0",
            "0",
        ]
    }

    fn has_any_entry_conv(vb: &VarBuilder) -> bool {
        Self::entry_conv_candidates()
            .iter()
            .any(|p| vb.contains_tensor(&format!("{p}.weight")))
    }

    fn has_any_head_tensor(vb: &VarBuilder) -> bool {
        [
            "v_a.weight",
            "v_a.0.weight",
            "v_a.2.weight",
            "fc.weight",
            "head.weight",
            "out.weight",
            "a_head.weight",
            "0.weight",
            "2.weight",
        ]
        .iter()
        .any(|k| vb.contains_tensor(k))
    }

    fn load_entry_conv(
        vb: &VarBuilder,
        dev: &Device,
    ) -> CandleResult<(FoldedConv1d, usize, usize)> {
        for &prefix in Self::entry_conv_candidates() {
            let w_name = format!("{prefix}.weight");
            if vb.contains_tensor(&w_name) {
                let w = vb.get_unchecked_dtype(&w_name, DType::F32)?;
                if w.dims().len() == 3 {
                    let conv_channels = w.dims()[0];
                    let in_channels = w.dims()[1];
                    let bn_candidates = if let Some(stem) = prefix.strip_suffix(".0") {
                        vec![format!("{stem}.1"), format!("{stem}.bn"), "bn1".to_string()]
                    } else {
                        vec![
                            format!("{prefix}_bn"),
                            "entry.1".to_string(),
                            "bn1".to_string(),
                            "1".to_string(),
                        ]
                    };
                    let folded = Self::fold_conv_with_bn(vb, prefix, &bn_candidates, conv_channels, dev)?;
                    return Ok((folded, conv_channels, in_channels));
                }
            }
        }
        Err(candle_core::Error::Msg(
            "Unable to find entry Conv1d weight in Mortal .pth".into(),
        ))
    }

    fn fold_conv_with_bn(
        vb: &VarBuilder,
        conv_prefix: &str,
        bn_prefixes: &[String],
        out_channels: usize,
        dev: &Device,
    ) -> CandleResult<FoldedConv1d> {
        let w = vb.get_unchecked_dtype(&format!("{conv_prefix}.weight"), DType::F32)?;
        let ksize = w.dims()[2];
        let padding = if ksize == 3 { 1 } else { 0 };
        let b_name = format!("{conv_prefix}.bias");
        let conv_b = if vb.contains_tensor(&b_name) {
            vb.get_unchecked_dtype(&b_name, DType::F32)?
        } else {
            Tensor::zeros((out_channels,), DType::F32, dev)?
        };

        for bn_p in bn_prefixes {
            let mean_k = format!("{bn_p}.running_mean");
            let var_k = format!("{bn_p}.running_var");
            if vb.contains_tensor(&mean_k) && vb.contains_tensor(&var_k) {
                let mean = vb.get_unchecked_dtype(&mean_k, DType::F32)?;
                let var = vb.get_unchecked_dtype(&var_k, DType::F32)?;
                let gamma_k = format!("{bn_p}.weight");
                let beta_k = format!("{bn_p}.bias");
                let gamma = if vb.contains_tensor(&gamma_k) {
                    vb.get_unchecked_dtype(&gamma_k, DType::F32)?
                } else {
                    Tensor::ones((out_channels,), DType::F32, dev)?
                };
                let beta = if vb.contains_tensor(&beta_k) {
                    vb.get_unchecked_dtype(&beta_k, DType::F32)?
                } else {
                    Tensor::zeros((out_channels,), DType::F32, dev)?
                };

                let std = (var + 1e-5f64)?.sqrt()?;
                let scale = (&gamma / &std)?;
                let scale_3d = scale.reshape((out_channels, 1, 1))?;
                let folded_w = w.broadcast_mul(&scale_3d)?;
                let folded_b = (((&conv_b - &mean)? * &scale)? + &beta)?.reshape((1, out_channels, 1))?;
                return Ok(FoldedConv1d {
                    weight: folded_w,
                    bias: folded_b,
                    padding,
                });
            }
        }

        Ok(FoldedConv1d {
            weight: w,
            bias: conv_b.reshape((1, out_channels, 1))?,
            padding,
        })
    }

    fn try_load_res_block(
        vb: &VarBuilder,
        idx: usize,
        channels: usize,
        use_silu: bool,
        dev: &Device,
    ) -> CandleResult<Option<ResBlock>> {
        let block_prefixes = [
            format!("backbone.blocks.{idx}"),
            format!("blocks.{idx}"),
            format!("backbone.{idx}"),
            format!("layers.{idx}"),
            format!("res.{idx}"),
        ];

        for bp in &block_prefixes {
            let sub = vb.pp(bp);
            let conv_layouts = [
                ("conv1.0", vec!["conv1.1".to_string()], "conv2.0", vec!["conv2.1".to_string()]),
                ("conv1", vec!["bn1".to_string(), "conv1_bn".to_string()], "conv2", vec!["bn2".to_string(), "conv2_bn".to_string()]),
                ("0", vec!["1".to_string()], "3", vec!["4".to_string()]),
                ("block.0", vec!["block.1".to_string()], "block.3", vec!["block.4".to_string()]),
            ];

            for (c1_p, bn1_p, c2_p, bn2_p) in conv_layouts {
                if sub.contains_tensor(&format!("{c1_p}.weight"))
                    && sub.contains_tensor(&format!("{c2_p}.weight"))
                {
                    let conv1 = Self::fold_conv_with_bn(&sub, c1_p, &bn1_p, channels, dev)?;
                    let conv2 = Self::fold_conv_with_bn(&sub, c2_p, &bn2_p, channels, dev)?;
                    let ca = Self::try_load_ca(&sub)?;
                    return Ok(Some(ResBlock {
                        conv1,
                        conv2,
                        ca,
                        use_silu,
                    }));
                }
            }
        }
        Ok(None)
    }

    fn try_load_ca(block_vb: &VarBuilder) -> CandleResult<Option<ChannelAttention>> {
        let ca_layouts = [
            ("ca.fc1", "ca.fc2"),
            ("ca.0", "ca.2"),
            ("se.fc1", "se.fc2"),
            ("se.0", "se.2"),
            ("attn.0", "attn.2"),
            ("channel_attention.0", "channel_attention.2"),
        ];
        for (p1, p2) in ca_layouts {
            let w1_k = format!("{p1}.weight");
            let w2_k = format!("{p2}.weight");
            if block_vb.contains_tensor(&w1_k) && block_vb.contains_tensor(&w2_k) {
                let mut fc1_w = block_vb.get_unchecked_dtype(&w1_k, DType::F32)?;
                let mut fc2_w = block_vb.get_unchecked_dtype(&w2_k, DType::F32)?;
                if fc1_w.dims().len() == 3 {
                    fc1_w = fc1_w.squeeze(2)?;
                }
                if fc2_w.dims().len() == 3 {
                    fc2_w = fc2_w.squeeze(2)?;
                }
                let b1_k = format!("{p1}.bias");
                let b2_k = format!("{p2}.bias");
                let fc1_b = if block_vb.contains_tensor(&b1_k) {
                    Some(block_vb.get_unchecked_dtype(&b1_k, DType::F32)?.reshape((1, fc1_w.dims()[0]))?)
                } else {
                    None
                };
                let fc2_b = if block_vb.contains_tensor(&b2_k) {
                    Some(block_vb.get_unchecked_dtype(&b2_k, DType::F32)?.reshape((1, fc2_w.dims()[0]))?)
                } else {
                    None
                };
                return Ok(Some(ChannelAttention {
                    fc1_w,
                    fc1_b,
                    fc2_w,
                    fc2_b,
                }));
            }
        }
        Ok(None)
    }

    fn try_load_standalone_bn(
        vb: &VarBuilder,
        channels: usize,
    ) -> CandleResult<Option<(Tensor, Tensor)>> {
        for prefix in ["backbone.bn", "bn_out", "out_bn", "norm", "final_bn"] {
            let mean_k = format!("{prefix}.running_mean");
            let var_k = format!("{prefix}.running_var");
            if vb.contains_tensor(&mean_k) && vb.contains_tensor(&var_k) {
                let mean = vb.get_unchecked_dtype(&mean_k, DType::F32)?;
                let var = vb.get_unchecked_dtype(&var_k, DType::F32)?;
                let gamma = vb.get_unchecked_dtype(&format!("{prefix}.weight"), DType::F32)?;
                let beta = vb.get_unchecked_dtype(&format!("{prefix}.bias"), DType::F32)?;
                let std = (var + 1e-5f64)?.sqrt()?;
                let scale = (&gamma / &std)?.reshape((1, channels, 1))?;
                let shift = (&beta - (&mean * (&gamma / &std)?)?)?.reshape((1, channels, 1))?;
                return Ok(Some((scale, shift)));
            }
        }
        Ok(None)
    }

    fn load_output_head(
        dqn_vb: &VarBuilder,
        brain_vb: &VarBuilder,
        conv_channels: usize,
        action_space: usize,
    ) -> CandleResult<(Option<(Tensor, Tensor)>, OutputHead)> {
        let mut head_fc1 = None;
        for fc1_p in ["v_a.0", "fc1", "fc", "net.0", "0"] {
            let wk = format!("{fc1_p}.weight");
            let bk = format!("{fc1_p}.bias");
            for vb in [dqn_vb, brain_vb] {
                if vb.contains_tensor(&wk) && vb.contains_tensor(&bk) {
                    if let Ok(w) = vb.get_unchecked_dtype(&wk, DType::F32) {
                        if w.dims().len() == 2
                            && w.dims()[0] != action_space + 1
                            && w.dims()[0] != action_space
                            && w.dims()[0] != 1
                        {
                            let b = vb
                                .get_unchecked_dtype(&bk, DType::F32)?
                                .reshape((1, w.dims()[0]))?;
                            head_fc1 = Some((w, b));
                            break;
                        }
                    }
                }
            }
            if head_fc1.is_some() {
                break;
            }
        }

        for p in ["v_a", "v_a.2", "v_a.1", "head", "out", "fc_out", "2", "1", "0"] {
            let wk = format!("{p}.weight");
            let bk = format!("{p}.bias");
            for vb in [dqn_vb, brain_vb] {
                if vb.contains_tensor(&wk) && vb.contains_tensor(&bk) {
                    let w = vb.get_unchecked_dtype(&wk, DType::F32)?;
                    if w.dims().len() == 2 && w.dims()[0] == action_space + 1 {
                        let feat_dim = w.dims()[1];
                        let b = vb
                            .get_unchecked_dtype(&bk, DType::F32)?
                            .reshape((1, action_space + 1))?;
                        return Ok((
                            head_fc1,
                            OutputHead::DuelingCombined {
                                weight: w,
                                bias: b,
                                action_space,
                                feat_dim,
                            },
                        ));
                    }
                }
            }
        }

        for (vp, ap) in [
            ("v_head", "a_head"),
            ("v", "a"),
            ("value", "advantage"),
            ("val", "adv"),
        ] {
            for vb in [dqn_vb, brain_vb] {
                if vb.contains_tensor(&format!("{vp}.weight"))
                    && vb.contains_tensor(&format!("{ap}.weight"))
                {
                    let v_w = vb.get_unchecked_dtype(&format!("{vp}.weight"), DType::F32)?;
                    let v_b = vb
                        .get_unchecked_dtype(&format!("{vp}.bias"), DType::F32)?
                        .reshape((1, 1))?;
                    let a_w = vb.get_unchecked_dtype(&format!("{ap}.weight"), DType::F32)?;
                    let a_b = vb
                        .get_unchecked_dtype(&format!("{ap}.bias"), DType::F32)?
                        .reshape((1, action_space))?;
                    let feat_dim = a_w.dims()[1];
                    return Ok((
                        head_fc1,
                        OutputHead::DuelingSplit {
                            v_w,
                            v_b,
                            a_w,
                            a_b,
                            feat_dim,
                        },
                    ));
                }
            }
        }

        for p in ["head", "policy", "actor", "fc_out", "out", "v_a", "0", "2"] {
            let wk = format!("{p}.weight");
            let bk = format!("{p}.bias");
            for vb in [dqn_vb, brain_vb] {
                if vb.contains_tensor(&wk) && vb.contains_tensor(&bk) {
                    let w = vb.get_unchecked_dtype(&wk, DType::F32)?;
                    if w.dims().len() == 2 && w.dims()[0] == action_space {
                        let feat_dim = w.dims()[1];
                        let b = vb
                            .get_unchecked_dtype(&bk, DType::F32)?
                            .reshape((1, action_space))?;
                        return Ok((
                            head_fc1,
                            OutputHead::Direct {
                                weight: w,
                                bias: b,
                                feat_dim,
                            },
                        ));
                    }
                }
            }
        }

        let _ = conv_channels;
        Err(candle_core::Error::Msg(
            "Unable to locate Dueling DQN or Policy output head in Mortal .pth".into(),
        ))
    }
}

pub fn mortal_action_index(action: &Action, num_players: u8) -> Option<usize> {
    if num_players == 3 {
        mortal_action_index_3p(action)
    } else {
        mortal_action_index_4p(action)
    }
}

fn mortal_action_index_4p(action: &Action) -> Option<usize> {
    match action.action_type {
        ActionType::Discard => {
            let tid = action.tile?;
            match tid {
                16 => Some(34),
                52 => Some(35),
                88 => Some(36),
                _ => Some(tile34(tid).min(33)),
            }
        }
        ActionType::Riichi => Some(37),
        ActionType::Chi => {
            let called_t34 = tile34(action.tile?);
            let mut c: Vec<usize> = action.consume_tiles.iter().map(|&t| tile34(t)).collect();
            c.sort_unstable();
            if c.len() >= 2 {
                if called_t34 < c[0] {
                    Some(38)
                } else if called_t34 > c[1] {
                    Some(40)
                } else {
                    Some(39)
                }
            } else {
                Some(38)
            }
        }
        ActionType::Pon => Some(41),
        ActionType::Daiminkan | ActionType::Ankan | ActionType::Kakan => Some(42),
        ActionType::Tsumo | ActionType::Ron => Some(43),
        ActionType::KyushuKyuhai => Some(44),
        ActionType::Pass => Some(45),
        ActionType::Kita => None,
    }
}

fn mortal_action_index_3p(action: &Action) -> Option<usize> {
    match action.action_type {
        ActionType::Discard => {
            let tid = action.tile?;
            let t34 = tile34(tid);
            TILE34_TO_COMPACT[t34]?;
            match tid {
                52 => Some(35),
                88 => Some(36),
                _ => Some(t34.min(33)),
            }
        }
        ActionType::Riichi => Some(37),
        ActionType::Pon => Some(38),
        ActionType::Daiminkan | ActionType::Ankan | ActionType::Kakan => Some(39),
        ActionType::Kita => Some(40),
        ActionType::Tsumo | ActionType::Ron => Some(41),
        ActionType::KyushuKyuhai => Some(42),
        ActionType::Pass => Some(43),
        ActionType::Chi => None,
    }
}

pub fn rank_by_mortal_logits(
    legal: &[Action],
    logits: &[f32],
    num_players: u8,
    top_n: usize,
) -> Vec<(Action, f32)> {
    let mut by_id: BTreeMap<usize, &Action> = BTreeMap::new();
    for a in legal {
        let Some(mut idx) = mortal_action_index(a, num_players) else {
            continue;
        };
        if matches!(idx, 34..=36) {
            if let Some(tid) = a.tile {
                let plain_idx = tile34(tid);
                let q_aka = logits.get(idx).copied().unwrap_or(f32::NEG_INFINITY);
                let q_plain = logits.get(plain_idx).copied().unwrap_or(f32::NEG_INFINITY);
                if q_plain > q_aka {
                    idx = plain_idx;
                }
            }
        }
        match by_id.entry(idx) {
            Entry::Vacant(e) => {
                e.insert(a);
            }
            Entry::Occupied(mut e) => {
                let prev_aka = e.get().action_type == ActionType::Discard
                    && e.get().tile.is_some_and(is_aka);
                let curr_aka = a.action_type == ActionType::Discard && a.tile.is_some_and(is_aka);
                if prev_aka && !curr_aka {
                    e.insert(a);
                }
            }
        }
    }
    if by_id.is_empty() {
        return Vec::new();
    }

    let scored: Vec<(&Action, f32)> = by_id
        .into_iter()
        .map(|(idx, a)| (a, logits.get(idx).copied().unwrap_or(f32::NEG_INFINITY)))
        .collect();
    let max_logit = scored
        .iter()
        .map(|(_, l)| *l)
        .fold(f32::NEG_INFINITY, f32::max);
    let exps: Vec<f32> = scored.iter().map(|(_, l)| (l - max_logit).exp()).collect();
    let sum: f32 = exps.iter().sum();

    let mut order: Vec<usize> = (0..scored.len()).collect();
    order.sort_by(|&a, &b| {
        scored[b]
            .1
            .partial_cmp(&scored[a].1)
            .unwrap_or(std::cmp::Ordering::Equal)
    });
    order
        .into_iter()
        .take(top_n)
        .map(|i| {
            let prob = if sum > 0.0 { exps[i] / sum } else { 0.0 };
            (scored[i].0.clone(), prob)
        })
        .collect()
}

pub fn encode_mortal_obs_4p(
    state: &mut GameState,
    seat: u8,
    in_channels: usize,
) -> (Vec<f32>, Vec<Action>) {
    let turn = state.turn_count;
    let last_discard = state.last_discard.map(|(_pid, tile)| tile);
    let obs = state.get_observation(seat);
    let legal = obs.legal_actions_method();
    let inp = enc_input_4p(&obs, turn, last_discard);
    (encode_mortal_planes(&inp, in_channels), legal)
}

pub fn encode_mortal_obs_3p(
    state: &mut GameState3P,
    seat: u8,
    in_channels: usize,
) -> (Vec<f32>, Vec<Action>) {
    let turn = state.turn_count;
    let kita: [u8; 3] = std::array::from_fn(|i| state.players[i].kita_tiles.len() as u8);
    let last_discard = state.last_discard.map(|(_pid, tile)| tile);
    let obs = state.get_observation(seat);
    let legal: Vec<Action> = obs.legal_actions_method().into_iter().map(|a| a.0).collect();
    let inp = enc_input_3p(&obs, turn, kita, last_discard);
    (encode_mortal_planes(&inp, in_channels), legal)
}

pub fn encode_mortal_planes(inp: &EncInput, in_channels: usize) -> Vec<f32> {
    let t = MORTAL_TILE_DIM;
    let mut buf = vec![0.0f32; in_channels * t];
    let mut ch = 0usize;

    macro_rules! next_ch {
        () => {{
            let c = ch;
            if c >= in_channels {
                return buf;
            }
            ch += 1;
            c
        }};
    }
    macro_rules! set_tile {
        ($c:expr, $tile34:expr, $val:expr) => {{
            let idx = $tile34;
            if $c < in_channels && idx < t {
                buf[$c * t + idx] = $val;
            }
        }};
    }
    macro_rules! broadcast {
        ($c:expr, $val:expr) => {{
            if $c < in_channels {
                let base = $c * t;
                for i in 0..t {
                    buf[base + i] = $val;
                }
            }
        }};
    }

    let mut hand_counts = [0u8; 34];
    let mut aka_in_hand = [false; 3];
    for &tid in &inp.hand {
        let idx = tile34(tid).min(33);
        hand_counts[idx] = hand_counts[idx].saturating_add(1);
        match tid {
            16 => aka_in_hand[0] = true,
            52 => aka_in_hand[1] = true,
            88 => aka_in_hand[2] = true,
            _ => {}
        }
    }
    for thresh in 1..=4u8 {
        let c = next_ch!();
        for idx in 0..34 {
            if hand_counts[idx] >= thresh {
                set_tile!(c, idx, 1.0);
            }
        }
    }
    for (k, &aka_t34) in [4usize, 13, 22].iter().enumerate() {
        let c = next_ch!();
        if aka_in_hand[k] {
            broadcast!(c, 1.0);
            set_tile!(c, aka_t34, 1.0);
        }
    }

    let c_drawn = next_ch!();
    if let Some(tid) = inp.drawn_tile {
        set_tile!(c_drawn, tile34(tid).min(33), 1.0);
    }
    let c_last = next_ch!();
    if let Some(tid) = inp.last_discard {
        set_tile!(c_last, tile34(tid).min(33), 1.0);
    }
    let c_waits = next_ch!();
    for &w34 in &inp.waits {
        set_tile!(c_waits, (w34 as usize).min(33), 1.0);
    }

    let c_dora_ind = next_ch!();
    let c_dora_tile = next_ch!();
    for &tid in &inp.dora_indicators {
        let ind34 = tile34(tid).min(33);
        buf[c_dora_ind * t + ind34] += 0.25;
        let dora34 = next_dora_tile34(ind34 as u8, inp.num_players) as usize;
        if dora34 < 34 {
            buf[c_dora_tile * t + dora34] += 0.25;
        }
    }

    let c_rw = next_ch!();
    set_tile!(c_rw, 27 + (inp.round_wind as usize).min(3), 1.0);
    let c_sw = next_ch!();
    set_tile!(c_sw, 27 + (inp.seat_wind as usize).min(3), 1.0);

    let c_honba = next_ch!();
    broadcast!(c_honba, (inp.honba as f32 / 10.0).min(1.0));
    let c_sticks = next_ch!();
    broadcast!(c_sticks, (inp.riichi_sticks as f32 / 10.0).min(1.0));
    let c_turn = next_ch!();
    broadcast!(c_turn, (inp.turn_count as f32 / 70.0).min(1.0));
    let c_kyoku = next_ch!();
    broadcast!(c_kyoku, (inp.kyoku_index as f32 / 8.0).min(1.0));
    let c_dealer = next_ch!();
    broadcast!(c_dealer, if inp.is_dealer { 1.0 } else { 0.0 });
    let c_tenpai = next_ch!();
    broadcast!(c_tenpai, if inp.is_tenpai { 1.0 } else { 0.0 });

    for seat in &inp.seats {
        let c_score = next_ch!();
        broadcast!(c_score, (seat.score as f32 / 100_000.0).clamp(0.0, 2.0));
        let c_riichi = next_ch!();
        broadcast!(c_riichi, if seat.riichi_declared { 1.0 } else { 0.0 });
        let c_rtile = next_ch!();
        if let Some(tid) = seat.riichi_tile {
            set_tile!(c_rtile, tile34(tid).min(33), 1.0);
        }
        let c_kita = next_ch!();
        broadcast!(c_kita, (seat.kita_count as f32 / 4.0).min(1.0));

        let mut meld_counts = [0u8; 34];
        for &tid in &seat.meld_tiles {
            let idx = tile34(tid).min(33);
            meld_counts[idx] = meld_counts[idx].saturating_add(1);
        }
        for thresh in 1..=4u8 {
            let c = next_ch!();
            for idx in 0..34 {
                if meld_counts[idx] >= thresh {
                    set_tile!(c, idx, 1.0);
                }
            }
        }

        let mut disc_counts = [0u8; 34];
        for &tid in &seat.discards {
            let idx = tile34(tid).min(33);
            disc_counts[idx] = disc_counts[idx].saturating_add(1);
        }
        for thresh in 1..=4u8 {
            let c = next_ch!();
            for idx in 0..34 {
                if disc_counts[idx] >= thresh {
                    set_tile!(c, idx, 1.0);
                }
            }
        }
        for step in 0..24usize {
            let c = next_ch!();
            if let Some(&tid) = seat.discards.get(step) {
                set_tile!(c, tile34(tid).min(33), 1.0);
            }
        }
    }

    buf
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn mortal_action_mapping_4p_and_3p() {
        let aka_5m = Action::new(ActionType::Discard, Some(16), vec![], Some(0));
        let plain_5m = Action::new(ActionType::Discard, Some(17), vec![], Some(0));
        assert_eq!(mortal_action_index(&aka_5m, 4), Some(34));
        assert_eq!(mortal_action_index(&plain_5m, 4), Some(4));

        let chi_low = Action::new(ActionType::Chi, Some(0), vec![4, 8], Some(0));
        let chi_mid = Action::new(ActionType::Chi, Some(4), vec![0, 8], Some(0));
        let chi_high = Action::new(ActionType::Chi, Some(8), vec![0, 4], Some(0));
        assert_eq!(mortal_action_index(&chi_low, 4), Some(38));
        assert_eq!(mortal_action_index(&chi_mid, 4), Some(39));
        assert_eq!(mortal_action_index(&chi_high, 4), Some(40));

        let pass = Action::new(ActionType::Pass, None, vec![], Some(0));
        let kita = Action::new(ActionType::Kita, None, vec![], Some(0));
        assert_eq!(mortal_action_index(&pass, 4), Some(45));
        assert_eq!(mortal_action_index(&pass, 3), Some(43));
        assert_eq!(mortal_action_index(&kita, 3), Some(40));
    }

    #[test]
    fn mortal_ranking_normalizes_and_orders() {
        let legal = vec![
            Action::new(ActionType::Discard, Some(0), vec![], Some(0)),
            Action::new(ActionType::Pon, Some(4), vec![5, 6], Some(0)),
            Action::new(ActionType::Pass, None, vec![], Some(0)),
        ];
        let mut logits = vec![0.0f32; 46];
        logits[0] = 0.5;
        logits[41] = 2.5;
        logits[45] = 1.0;

        let ranked = rank_by_mortal_logits(&legal, &logits, 4, 3);
        assert_eq!(ranked.len(), 3);
        assert_eq!(ranked[0].0.action_type, ActionType::Pon);
        assert_eq!(ranked[1].0.action_type, ActionType::Pass);
        assert_eq!(ranked[2].0.action_type, ActionType::Discard);
        let sum: f32 = ranked.iter().map(|(_, p)| *p).sum();
        assert!((sum - 1.0).abs() < 1e-5);
    }
}
