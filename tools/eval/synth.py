# Copyright (c) 2026 Team Badmosip
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.
"""Image transforms that turn bona fide frames into synthetic presentation-attack frames.

Every function takes and returns an RGB ``numpy.uint8`` array of shape (height, width, 3) and is
deterministic for a given ``numpy.random.Generator``. The artefacts are simulations: they imitate a
print, a screen or a hand-held photo well enough to exercise the engine's defence layers in automated
regression tests, but they are not real presentation attack instruments and the results must not be
reported as ISO/IEC 30107-3 attack results.
"""

from __future__ import annotations

import math
from typing import Tuple

import numpy as np
from PIL import Image, ImageFilter

Array = np.ndarray


def to_image(a: Array) -> Image.Image:
    return Image.fromarray(np.clip(a, 0, 255).astype(np.uint8), "RGB")


def to_array(im: Image.Image) -> Array:
    return np.asarray(im.convert("RGB"), dtype=np.uint8)


def sensor_noise(a: Array, rng: np.random.Generator, sigma: float) -> Array:
    """Adds Gaussian noise, as the capturing camera does to every frame of a still artefact."""
    noisy = a.astype(np.float32) + rng.normal(0.0, sigma, a.shape).astype(np.float32)
    return np.clip(noisy, 0, 255).astype(np.uint8)


def blur(a: Array, radius: float) -> Array:
    if radius <= 0:
        return a
    return to_array(to_image(a).filter(ImageFilter.GaussianBlur(radius)))


def scale_brightness(a: Array, factor: float) -> Array:
    return np.clip(a.astype(np.float32) * factor, 0, 255).astype(np.uint8)


def print_look(a: Array) -> Array:
    """Imitates a colour office print photographed by the camera: lower saturation and contrast, lifted
    blacks, warm paper tint and a little loss of sharpness."""
    f = a.astype(np.float32) / 255.0
    grey = f.mean(axis=2, keepdims=True)
    f = grey + 0.75 * (f - grey)                      # desaturate
    f = 0.5 + 0.8 * (f - 0.5)                         # compress contrast
    f = 0.06 + 0.9 * f                                # paper white and ink black do not reach 0/255
    f *= np.array([1.03, 1.0, 0.94], dtype=np.float32)  # warm paper tint
    out = np.clip(f * 255.0, 0, 255).astype(np.uint8)
    return blur(out, 0.8)


def screen_look(a: Array, t: float, glare_center: Tuple[float, float] = (0.72, 0.28)) -> Array:
    """Imitates a photo or video shown on a phone or laptop screen and filmed by the camera: moire
    interference, a visible RGB sub-pixel grid, a cool colour cast with a different gamma, a specular glare
    spot and slight refresh flicker. ``t`` is the frame time in seconds (moves the moire and flicker)."""
    h, w, _ = a.shape
    f = a.astype(np.float32) / 255.0
    f = np.power(np.clip(f, 0, 1), 0.85)                  # screen gamma differs from the scene's
    f *= np.array([0.90, 0.98, 1.08], dtype=np.float32)   # cool cast
    y, x = np.mgrid[0:h, 0:w].astype(np.float32)
    phase = 2.0 * math.pi * 0.35 * t
    moire = (np.sin(2 * math.pi * (x * 0.173 + y * 0.041) + phase)
             * np.sin(2 * math.pi * (x * 0.169 - y * 0.037)))
    f *= (1.0 + 0.07 * moire)[..., None]
    grid = np.ones((h, w, 3), dtype=np.float32)
    for c in range(3):
        grid[:, (x[0].astype(int) % 3) != c, c] = 0.93   # each column favours one sub-pixel colour
    f *= grid
    gx, gy = glare_center[0] * w, glare_center[1] * h
    glare = np.exp(-(((x - gx) / (0.18 * w)) ** 2 + ((y - gy) / (0.12 * h)) ** 2))
    f += 0.35 * glare[..., None]
    f *= 1.0 + 0.02 * math.sin(2 * math.pi * t * 7.0)      # refresh flicker beating with the camera
    out = np.clip(f * 255.0, 0, 255).astype(np.uint8)
    return blur(out, 0.6)


def affine(a: Array, dx: float, dy: float, scale: float, angle_deg: float, fill=(128, 128, 128)) -> Array:
    """Moves a flat photo rigidly in the image plane: shift, zoom and roll about the image centre."""
    h, w, _ = a.shape
    cx, cy = w / 2.0, h / 2.0
    th = math.radians(angle_deg)
    cos, sin = math.cos(th) / scale, math.sin(th) / scale
    # Inverse map (output -> input) about the centre, then the shift.
    a11, a12 = cos, sin
    a21, a22 = -sin, cos
    c1 = cx - a11 * (cx + dx) - a12 * (cy + dy)
    c2 = cy - a21 * (cx + dx) - a22 * (cy + dy)
    im = to_image(a).transform((w, h), Image.AFFINE, (a11, a12, c1, a21, a22, c2), resample=Image.BICUBIC,
                               fillcolor=fill)
    return to_array(im)


def perspective_coefficients(width: int, height: int, yaw_deg: float, focal: float) -> Tuple[float, ...]:
    """Pillow PERSPECTIVE coefficients (output -> input) for a flat photo rotated by ``yaw_deg`` about its
    vertical centre line and seen by a pinhole camera with ``focal`` pixels at distance ``focal``.

    With the image centre (cx, cy), u' = u - cx and t = tan(yaw) / focal, a photo point maps to
    x = u' / (cos(yaw) (1 - u' t)), y = v' / (1 - u' t); the coefficients express that as the ratio of two
    linear functions that Pillow expects."""
    cx, cy = width / 2.0, height / 2.0
    th = math.radians(yaw_deg)
    t = math.tan(th) / focal
    k = 1.0 + cx * t
    a = (1.0 / math.cos(th) - cx * t) / k
    c = (cx - cx / math.cos(th) + cx * cx * t) / k
    d = (-cy * t) / k
    e = 1.0 / k
    f = (cy * cx * t) / k
    g = -t / k
    return (a, 0.0, c, d, e, f, g, 0.0)


def rotate_photo(a: Array, yaw_deg: float, focal: float = 700.0, fill=(128, 128, 128)) -> Array:
    """Shows a flat photo turned about its vertical axis, as an attacker tilts a print to fake a head turn."""
    h, w, _ = a.shape
    coeffs = perspective_coefficients(w, h, yaw_deg, focal)
    im = to_image(a).transform((w, h), Image.PERSPECTIVE, coeffs, resample=Image.BICUBIC, fillcolor=fill)
    return to_array(im)


def blank_scene(width: int, height: int, rng: np.random.Generator) -> Array:
    """An empty room-like scene: a soft vertical gradient with texture and no face."""
    y = np.linspace(0, 1, height, dtype=np.float32)[:, None, None]
    base = 170 - 60 * y + np.zeros((height, width, 3), dtype=np.float32)
    base *= np.array([1.0, 0.97, 0.92], dtype=np.float32)
    texture = rng.normal(0, 6, (height // 8 + 1, width // 8 + 1, 1)).astype(np.float32)
    texture = np.kron(texture, np.ones((8, 8, 1), dtype=np.float32))[:height, :width]
    return np.clip(base + texture, 0, 255).astype(np.uint8)


def side_by_side(left: Array, right: Array) -> Array:
    """The central halves of two frames next to each other (two people in front of the camera). Faces keep
    their proportions; the frames must have the same size."""
    h, w, _ = left.shape
    q = w // 4
    return np.concatenate([left[:, q:q + w // 2], right[:, q:q + w - w // 2]], axis=1)
