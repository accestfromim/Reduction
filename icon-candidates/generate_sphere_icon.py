from PIL import Image, ImageDraw, ImageFilter
import numpy as np
import os

S = 1024
CX = CY = S / 2
R = 330.0

y, x = np.mgrid[0:S, 0:S].astype(np.float32)
dx = (x - CX) / R
dy = (y - CY) / R
rr = dx * dx + dy * dy
mask = rr <= 1.0
z = np.zeros_like(dx)
z[mask] = np.sqrt(np.maximum(0.0, 1.0 - rr[mask]))
n = np.stack([dx, dy, z], axis=-1)

def normalize(v):
    v = np.array(v, dtype=np.float32)
    return v / np.linalg.norm(v)
light = normalize([-0.45, -0.62, 0.65])
view = np.array([0.0, 0.0, 1.0], dtype=np.float32)
half = normalize(light + view)
diff = np.clip(np.sum(n * light, axis=-1), 0.0, 1.0)
spec = np.clip(np.sum(n * half, axis=-1), 0.0, 1.0) ** 42
rim = (1.0 - z) ** 3
rim = np.where(mask, rim, 0.0)

deep = np.array([7, 24, 74], dtype=np.float32)
cyan = np.array([54, 190, 255], dtype=np.float32)
color = deep[None, None, :] + (cyan - deep)[None, None, :] * diff[..., None]
color *= (0.58 + 0.42 * diff[..., None])
color += spec[..., None] * np.array([255, 255, 255], dtype=np.float32) * 1.25
color += rim[..., None] * np.array([86, 224, 255], dtype=np.float32) * 0.65
color = np.clip(color, 0, 255).astype(np.uint8)
alpha = np.where(mask, 255, 0).astype(np.uint8)
arr = np.dstack([color, alpha])
sphere = Image.fromarray(arr, 'RGBA')

# soft outer glow
glow = Image.new('RGBA', (S, S), (0, 0, 0, 0))
gd = ImageDraw.Draw(glow)
gd.ellipse([CX-R-34, CY-R-34, CX+R+34, CY+R+34], fill=(40, 170, 255, 90))
glow = glow.filter(ImageFilter.GaussianBlur(44))

img = Image.alpha_composite(glow, sphere)
d = ImageDraw.Draw(img, 'RGBA')

# tech orbit ring
for width, alpha_value in [(7, 120), (3, 210)]:
    d.ellipse([CX-R*1.28, CY-R*0.42, CX+R*1.28, CY+R*0.42],
              outline=(105, 225, 255, alpha_value), width=width)
# front arc highlight
d.arc([CX-R*1.28, CY-R*0.42, CX+R*1.28, CY+R*0.42], 200, 340,
      fill=(220, 250, 255, 240), width=5)
# small orbital nodes
for angle in (-20, 25, 160):
    import math
    ax = CX + math.cos(math.radians(angle)) * R * 1.28
    ay = CY + math.sin(math.radians(angle)) * R * 0.42
    d.ellipse([ax-7, ay-7, ax+7, ay+7], fill=(210, 248, 255, 230))

out = 'icon-candidates/sphere-icon-1024.png'
img.save(out)

# adaptive foreground 432
fg = img.resize((432, 432), Image.LANCZOS)
fg.save('app/src/main/res/drawable-nodpi/ic_launcher_foreground.png')
# in-app logo
img.resize((256, 256), Image.LANCZOS).save('app/src/main/assets/img/app-logo.png')

# monochrome: simple sphere + orbit silhouette
mono = Image.new('RGBA', (432, 432), (0, 0, 0, 0))
md = ImageDraw.Draw(mono)
cx = cy = 216
r = 139
md.ellipse([cx-r, cy-r, cx+r, cy+r], fill=(255, 255, 255, 255))
md.ellipse([cx-r*1.28, cy-r*0.42, cx+r*1.28, cy+r*0.42],
           outline=(255, 255, 255, 255), width=6)
mono.save('app/src/main/res/drawable-nodpi/ic_launcher_monochrome.png')
print('saved', out)
