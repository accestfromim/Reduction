from PIL import Image, ImageDraw, ImageFont
import numpy as np
import os

OUT = "/home/user/deepseek-assistant/icon-candidates"
os.makedirs(OUT, exist_ok=True)
S = 2048  # supersample canvas
FINAL = 1024

def hexrgb(h):
    h = h.lstrip('#')
    return tuple(int(h[i:i+2], 16) for i in (0, 2, 4))

def gradient(size, c1, c2, angle=135):
    c1 = np.array(hexrgb(c1), dtype=np.float32)
    c2 = np.array(hexrgb(c2), dtype=np.float32)
    y, x = np.mgrid[0:size, 0:size].astype(np.float32)
    a = np.deg2rad(angle)
    t = (x * np.cos(a) + y * np.sin(a))
    t = (t - t.min()) / (t.max() - t.min())
    arr = c1[None, None, :] * (1 - t[..., None]) + c2[None, None, :] * t[..., None]
    # soft radial highlight
    cx = cy = size / 2
    d = np.sqrt((x-cx)**2 + (y-cy)**2) / (size*0.7)
    glow = np.clip(1 - d, 0, 1) * 0.10
    arr = np.clip(arr + glow[..., None] * 255, 0, 255).astype(np.uint8)
    return Image.fromarray(arr, 'RGB').convert('RGBA')

def rounded_mask(size, radius_frac=0.23):
    mask = Image.new('L', (size, size), 0)
    d = ImageDraw.Draw(mask)
    r = int(size * radius_frac)
    d.rounded_rectangle([0, 0, size-1, size-1], radius=r, fill=255)
    return mask

def base(c1, c2):
    img = gradient(S, c1, c2)
    mask = rounded_mask(S)
    img.putalpha(mask)
    return img

def sc(v):
    return int(v * S)

def draw_bubble(icon):
    d = ImageDraw.Draw(icon)
    white = hexrgb('#F7FAFF') + (255,)
    d.rounded_rectangle([sc(0.20), sc(0.20), sc(0.80), sc(0.61)], radius=sc(0.13), fill=white)
    d.polygon([(sc(0.29), sc(0.60)), (sc(0.29), sc(0.76)), (sc(0.45), sc(0.60))], fill=white)
    bars = [('#4F8CFF', 0.20), ('#7FE7FF', 0.30), ('#4F8CFF', 0.24), ('#7FE7FF', 0.16)]
    x = 0.33
    for color, h in bars:
        d.rounded_rectangle([sc(x), sc(0.405-h/2), sc(x+0.055), sc(0.405+h/2)],
                            radius=sc(0.0275), fill=hexrgb(color) + (255,))
        x += 0.095

def draw_mic(icon):
    d = ImageDraw.Draw(icon)
    cyan = hexrgb('#2DD4BF') + (255,)
    blue = hexrgb('#2F7BFF') + (255,)
    white = hexrgb('#FFFFFF') + (255,)
    cx, cy = 0.5, 0.5
    for r, color, w in [(0.34, blue, 0.016), (0.27, cyan, 0.016), (0.20, blue, 0.014)]:
        d.arc([sc(cx-r), sc(cy-r), sc(cx+r), sc(cy+r)], 200, 340, fill=color, width=sc(w))
        d.arc([sc(cx-r), sc(cy-r), sc(cx+r), sc(cy+r)], 20, 160, fill=color, width=sc(w))
    d.rounded_rectangle([sc(0.455), sc(0.27), sc(0.545), sc(0.53)], radius=sc(0.045), fill=white)
    d.arc([sc(0.395), sc(0.45), sc(0.605), sc(0.61)], 0, 180, fill=white, width=sc(0.026))
    d.rounded_rectangle([sc(0.49), sc(0.61), sc(0.51), sc(0.70)], radius=sc(0.01), fill=white)
    d.rounded_rectangle([sc(0.44), sc(0.695), sc(0.56), sc(0.72)], radius=sc(0.012), fill=white)

def draw_headset(icon):
    d = ImageDraw.Draw(icon)
    white = hexrgb('#FFFFFF') + (255,)
    cyan = hexrgb('#7FE7FF') + (255,)
    d.arc([sc(0.25), sc(0.26), sc(0.75), sc(0.78)], 180, 360, fill=white, width=sc(0.055))
    d.rounded_rectangle([sc(0.235), sc(0.45), sc(0.325), sc(0.63)], radius=sc(0.035), fill=white)
    d.rounded_rectangle([sc(0.675), sc(0.45), sc(0.765), sc(0.63)], radius=sc(0.035), fill=white)
    d.line([(sc(0.30), sc(0.61)), (sc(0.41), sc(0.71)), (sc(0.47), sc(0.71))], fill=cyan, width=sc(0.025), joint='curve')
    d.ellipse([sc(0.455), sc(0.69), sc(0.505), sc(0.74)], fill=cyan)

def draw_sound_dot(icon):
    d = ImageDraw.Draw(icon)
    cyan = hexrgb('#7FE7FF') + (255,)
    blue = hexrgb('#3B82F6') + (255,)
    cx, cy = 0.43, 0.50
    d.ellipse([sc(cx-0.065), sc(cy-0.065), sc(cx+0.065), sc(cy+0.065)], fill=cyan)
    for r, color, w in [(0.16, cyan, 0.028), (0.25, blue, 0.026), (0.34, cyan, 0.022)]:
        d.arc([sc(cx-r), sc(cy-r), sc(cx+r), sc(cy+r)], -55, 55, fill=color, width=sc(w))

def draw_d_wave(icon):
    d = ImageDraw.Draw(icon)
    white = hexrgb('#FFFFFF') + (255,)
    cyan = hexrgb('#7FE7FF') + (255,)
    blue = hexrgb('#4F8CFF') + (255,)
    # D outline
    d.rounded_rectangle([sc(0.33), sc(0.27), sc(0.40), sc(0.73)], radius=sc(0.035), fill=white)
    d.arc([sc(0.33), sc(0.27), sc(0.73), sc(0.73)], -90, 90, fill=white, width=sc(0.07))
    # waveform inside D
    bars = [(0.46, 0.16, blue), (0.515, 0.28, cyan), (0.57, 0.20, blue)]
    for x, h, color in bars:
        d.rounded_rectangle([sc(x), sc(0.5-h/2), sc(x+0.032), sc(0.5+h/2)],
                            radius=sc(0.016), fill=color)

def render(fn, c1, c2, shape):
    img = base(c1, c2)
    shape(img)
    img = img.resize((FINAL, FINAL), Image.LANCZOS)
    return img

candidates = [
    ("1-声波气泡", "#0E1A2B", "#1E4FD8", draw_bubble, "对话气泡 + 声波，友好，最像聊天助手"),
    ("2-麦克风脉冲", "#071018", "#0E4C63", draw_mic, "麦克风 + 声波环，语音助手语义最直接"),
    ("3-耳机助手", "#101426", "#4B1FA8", draw_headset, "耳机 + 麦克风，贴合你用蓝牙耳机"),
    ("4-声波圆点", "#0B0F14", "#1F3B73", draw_sound_dot, "极简声波，小尺寸最清晰"),
    ("5-D 波纹", "#111827", "#2D63E8", draw_d_wave, "DeepSeek 的 D + 声波，品牌感更强"),
]

icons = []
for name, c1, c2, fn, desc in candidates:
    img = render(fn.__name__, c1, c2, fn)
    path = os.path.join(OUT, name + ".png")
    img.save(path)
    icons.append((name, img, desc))
    img.resize((512, 512), Image.LANCZOS).save(os.path.join(OUT, name + "-512.png"))

# Contact sheet
W, H = 1280, 1920
sheet = Image.new("RGB", (W, H), hexrgb("#0B0F14"))
d = ImageDraw.Draw(sheet)
font_title = ImageFont.truetype("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc", 54)
font_name = ImageFont.truetype("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc", 32)
font_desc = ImageFont.truetype("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc", 22)
title = "DeepSeek 助手 · 图标候选"
d.text((40, 32), title, font=font_title, fill=hexrgb("#E8EEF7"))
d.text((42, 102), "选一个编号，我再生成 Android 自适应图标并打包 APK", font=font_desc, fill=hexrgb("#8A97A8"))

cell_w, cell_h = 600, 560
x0, y0 = 40, 150
for i, (name, img, desc) in enumerate(icons):
    col = i % 2
    row = i // 2
    x = x0 + col * cell_w
    y = y0 + row * cell_h
    thumb = img.resize((420, 420), Image.LANCZOS)
    sheet.paste(thumb, (x + 80, y), thumb)
    d.text((x + 20, y + 8), name.split('-')[0], font=font_name, fill=hexrgb("#4F8CFF"))
    d.text((x + 80, y + 438), name, font=font_name, fill=hexrgb("#E8EEF7"))
    d.text((x + 80, y + 484), desc, font=font_desc, fill=hexrgb("#8A97A8"))

sheet.save(os.path.join(OUT, "contact-sheet.png"))
print("done")
for name, _, _ in icons:
    print(os.path.join(OUT, name + ".png"))
print(os.path.join(OUT, "contact-sheet.png"))
