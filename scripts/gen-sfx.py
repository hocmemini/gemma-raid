#!/usr/bin/env python3
"""Synthesize chiptune-style sound effects (no samples, no licensing) into android/app/src/main/res/raw/."""
import math, random, struct, wave, os, sys
SR = 22050
OUT = os.path.join(os.path.dirname(__file__), "..", "android", "app", "src", "main", "res", "raw")

def env(i, n, a=0.005, d=0.1, s=0.6, r=0.2):
    t = i / SR; T = n / SR
    if t < a: return t / a
    if t < a + d: return 1 - (1 - s) * (t - a) / d
    if t > T - r: return s * max(0.0, (T - t) / r)
    return s

def tone(dur, f0, f1=None, wave_="sine", vol=0.6, a=0.005, d=0.1, s=0.6, r=0.2, vib=0.0):
    n = int(SR * dur); out = []
    f1 = f0 if f1 is None else f1
    ph = 0.0
    for i in range(n):
        t = i / n
        f = f0 * (f1 / f0) ** t
        if vib: f *= 1 + 0.03 * math.sin(2 * math.pi * vib * i / SR)
        ph += 2 * math.pi * f / SR
        if wave_ == "sine": v = math.sin(ph)
        elif wave_ == "square": v = 1.0 if math.sin(ph) > 0 else -1.0
        elif wave_ == "saw": v = 2 * ((ph / (2 * math.pi)) % 1) - 1
        elif wave_ == "tri": v = 2 * abs(2 * ((ph / (2 * math.pi)) % 1) - 1) - 1
        else: v = 0
        out.append(v * vol * env(i, n, a, d, s, r))
    return out

def noise(dur, vol=0.5, a=0.002, d=0.05, s=0.5, r=0.3, lp=0.0):
    n = int(SR * dur); out = []; y = 0.0
    for i in range(n):
        x = random.uniform(-1, 1)
        y = y + lp * (x - y) if lp else x   # crude low-pass when lp in (0,1)
        out.append(y * vol * env(i, n, a, d, s, r))
    return out

def mix(*parts):
    n = max(len(p) for p in parts); out = [0.0] * n
    for p in parts:
        for i, v in enumerate(p): out[i] += v
    return [max(-1, min(1, v)) for v in out]

def seq(*parts, gap=0.0):
    out = []
    g = [0.0] * int(SR * gap)
    for p in parts: out += p + g
    return out

def delay(sig, t=0.12, fb=0.4, taps=3):
    out = list(sig); d = int(SR * t)
    for k in range(1, taps + 1):
        g = fb ** k
        for i in range(len(sig)):
            j = i + d * k
            if j >= len(out): out.append(0.0)
            out[j] += sig[i] * g
    return [max(-1, min(1, v)) for v in out]

def save(name, sig):
    path = os.path.join(OUT, name + ".wav")
    with wave.open(path, "w") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes(b"".join(struct.pack("<h", int(v * 32767)) for v in sig))
    print(f"{name}.wav {len(sig)/SR:.2f}s")

random.seed(7)
save("sfx_spawn",   mix(tone(0.7, 220, 55, "saw", 0.5, 0.02, 0.2, 0.7, 0.3, vib=6), noise(0.7, 0.35, 0.01, 0.1, 0.5, 0.4, lp=0.08)))
save("sfx_slash",   mix(noise(0.22, 0.6, 0.002, 0.03, 0.4, 0.15, lp=0.6), tone(0.22, 2400, 300, "sine", 0.3, 0.002, 0.05, 0.3, 0.1)))
save("sfx_hit",     mix(tone(0.16, 240, 90, "square", 0.5, 0.002, 0.04, 0.4, 0.08), noise(0.12, 0.4, 0.001, 0.02, 0.3, 0.08)))
save("sfx_attack",  seq(tone(0.3, 90, 320, "saw", 0.5, 0.01, 0.1, 0.8, 0.05), tone(0.25, 70, 40, "sine", 0.8, 0.002, 0.05, 0.5, 0.18)))
save("sfx_parry",   mix(tone(0.32, 1200, 1150, "sine", 0.35, 0.001, 0.08, 0.3, 0.2), tone(0.32, 1850, 1800, "sine", 0.25, 0.001, 0.08, 0.3, 0.2), tone(0.32, 2600, 2500, "sine", 0.2, 0.001, 0.06, 0.2, 0.2)))
save("sfx_potion",  seq(tone(0.09, 300, 340, "sine", 0.5, 0.005, 0.02, 0.8, 0.03), tone(0.09, 400, 450, "sine", 0.5, 0.005, 0.02, 0.8, 0.03), tone(0.14, 520, 620, "sine", 0.5, 0.005, 0.02, 0.8, 0.06), gap=0.02))
save("sfx_legendary", delay(seq(tone(0.1, 523, None, "square", 0.35, 0.002, 0.03, 0.7, 0.03), tone(0.1, 659, None, "square", 0.35, 0.002, 0.03, 0.7, 0.03), tone(0.1, 784, None, "square", 0.35, 0.002, 0.03, 0.7, 0.03), tone(0.3, 1047, None, "square", 0.35, 0.002, 0.05, 0.7, 0.2)), 0.1, 0.35, 2))
save("sfx_kill",    mix(noise(0.9, 0.7, 0.002, 0.15, 0.5, 0.6, lp=0.15), tone(0.6, 80, 30, "sine", 0.8, 0.002, 0.1, 0.6, 0.4)))
save("sfx_victory", seq(tone(0.12, 392, None, "square", 0.3, 0.002, 0.03, 0.7, 0.03), tone(0.12, 523, None, "square", 0.3, 0.002, 0.03, 0.7, 0.03), tone(0.12, 659, None, "square", 0.3, 0.002, 0.03, 0.7, 0.03), tone(0.45, 784, None, "square", 0.3, 0.002, 0.05, 0.7, 0.3), gap=0.02))
save("sfx_levelup", mix(tone(0.5, 400, 1300, "tri", 0.45, 0.01, 0.1, 0.7, 0.2), tone(0.5, 1600, 2600, "sine", 0.12, 0.2, 0.1, 0.4, 0.2)))
save("sfx_wind",    seq(tone(0.12, 60, 50, "sine", 0.9, 0.002, 0.05, 0.5, 0.06), tone(0.12, 60, 50, "sine", 0.9, 0.002, 0.05, 0.5, 0.06), tone(0.5, 300, 900, "tri", 0.4, 0.05, 0.1, 0.7, 0.25), gap=0.08))
save("sfx_omen",    delay(tone(1.0, 180, 170, "sine", 0.4, 0.1, 0.3, 0.6, 0.4, vib=5), 0.2, 0.5, 3))
save("sfx_miss",    tone(0.22, 420, 180, "tri", 0.4, 0.005, 0.05, 0.6, 0.1))

# ---- second batch ----
save("sfx_shutter", mix(noise(0.06, 0.6, 0.001, 0.01, 0.4, 0.04), tone(0.05, 1800, 900, "square", 0.25, 0.001, 0.01, 0.3, 0.03)))
save("sfx_blip",    tone(0.08, 900, 1300, "sine", 0.35, 0.002, 0.02, 0.5, 0.04))
save("sfx_npc",     delay(seq(tone(0.12, 659, None, "sine", 0.35, 0.005, 0.05, 0.7, 0.05), tone(0.12, 784, None, "sine", 0.35, 0.005, 0.05, 0.7, 0.05), tone(0.3, 1047, None, "sine", 0.35, 0.005, 0.08, 0.7, 0.2), gap=0.01), 0.15, 0.3, 2))
save("sfx_coins",   seq(*[mix(tone(0.07, 2200 + i * 150, 2100 + i * 150, "sine", 0.3, 0.001, 0.02, 0.4, 0.04), tone(0.07, 3300, 3200, "sine", 0.15, 0.001, 0.02, 0.3, 0.04)) for i in range(5)], gap=0.03))
save("sfx_hurt",    mix(noise(0.25, 0.5, 0.002, 0.05, 0.4, 0.18, lp=0.12), tone(0.25, 140, 70, "saw", 0.45, 0.002, 0.06, 0.4, 0.15)))
save("sfx_whoosh",  noise(0.3, 0.45, 0.05, 0.08, 0.6, 0.15, lp=0.25))
save("sfx_steps",   seq(tone(0.1, 70, 45, "sine", 0.9, 0.002, 0.04, 0.4, 0.05), tone(0.1, 70, 45, "sine", 0.9, 0.002, 0.04, 0.4, 0.05), tone(0.1, 70, 45, "sine", 0.9, 0.002, 0.04, 0.4, 0.05), gap=0.18))
save("sfx_ping",    delay(tone(0.15, 1500, 1500, "sine", 0.35, 0.002, 0.05, 0.4, 0.1), 0.12, 0.4, 2))
save("sfx_join",    seq(tone(0.1, 523, None, "tri", 0.35, 0.005, 0.03, 0.7, 0.05), tone(0.2, 784, None, "tri", 0.35, 0.005, 0.05, 0.7, 0.12), gap=0.01))
save("sfx_windup",  seq(*[tone(0.05, 600 + i * 90, None, "square", 0.22, 0.001, 0.01, 0.6, 0.02) for i in range(8)], gap=0.07))
# loops (seamless-ish: envelopes fully sustained, lengths whole cycles)
def drone(dur, f, vol):
    n = int(SR * dur); out = []
    for i in range(n):
        t = i / SR
        v = (math.sin(2*math.pi*f*t) + 0.5*math.sin(2*math.pi*f*1.5*t) + 0.25*math.sin(2*math.pi*f*0.5*t)) / 1.75
        v *= 1 + 0.15 * math.sin(2*math.pi*0.5*t)
        out.append(v * vol)
    return out
save("sfx_ambient_battle",  mix(drone(4.0, 55, 0.18), noise(4.0, 0.05, 0.5, 0.5, 1.0, 0.5, lp=0.03)))
save("sfx_ambient_explore", mix(drone(4.0, 110, 0.07), noise(4.0, 0.06, 0.5, 0.5, 1.0, 0.5, lp=0.02)))
