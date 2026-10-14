#!/usr/bin/env python3
"""Optional helper to inspect a Mortal .pth checkpoint or export it to .onnx."""
import argparse
import pathlib
import torch


def auto_inspect_mortal_pth(pth_path: pathlib.Path):
    ckpt = torch.load(str(pth_path), map_location="cpu", weights_only=True)
    mortal_sd = ckpt.get("mortal", ckpt)
    first_conv_key = next(
        k for k, v in mortal_sd.items()
        if isinstance(v, torch.Tensor) and v.ndim == 3 and k.endswith(".weight")
    )
    conv_channels, in_channels, _ = mortal_sd[first_conv_key].shape
    players = 3 if in_channels == 775 else 4
    action_dim = 44 if players == 3 else 46
    version_map = {1012: 4, 948: 3, 938: 2, 450: 1, 775: 5}
    version = version_map.get(in_channels, 4)
    print(
        f"Detected Mortal checkpoint: players={players}, version={version}, "
        f"in_channels={in_channels}, conv_channels={conv_channels}, actions={action_dim}"
    )


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=pathlib.Path, required=True, help="Path to Mortal .pth file")
    args = parser.parse_args()
    auto_inspect_mortal_pth(args.input)
