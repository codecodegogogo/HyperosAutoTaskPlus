from PIL import Image
import sys
p = sys.argv[1]
im = Image.open(p).convert("RGB")
w,h = im.size
cols, rows = 56, 100
im2 = im.resize((cols, rows))
def ch(px):
    r,g,b = px
    lum = (r*299+g*587+b*114)//1000
    mx, mn = max(px), min(px)
    sat = mx-mn
    if sat > 40 and b > r:  # blue-ish
        return "B"
    if lum > 235: return "."
    if lum > 215: return ":"
    if lum > 190: return "-"
    if lum > 150: return "="
    if lum > 100: return "#"
    return "@"
for y in range(rows):
    print("".join(ch(im2.getpixel((x,y))) for x in range(cols)))
