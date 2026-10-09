def lum(h):
    h = h.lstrip('#'); c = [int(h[i:i+2], 16) / 255 for i in (0, 2, 4)]
    c = [x / 12.92 if x <= 0.04045 else ((x + 0.055) / 1.055) ** 2.4 for x in c]
    return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]
def cr(a, b):
    la, lb = sorted([lum(a), lum(b)], reverse=True); return (la + 0.05) / (lb + 0.05)
pairs = [l.split() for l in """
ink #14171A bg #F5F6F7
ink #14171A surface #FFFFFF
text2 #4A525B bg #F5F6F7
text2 #4A525B surface #FFFFFF
text3 #3A4250 bg #F5F6F7
text3 #3A4250 blueTint #E8EDFC
text4 #6B7480 bg #F5F6F7
text4 #6B7480 surface #FFFFFF
blueInk #1A3596 blueTint #E8EDFC
tealInk #0B5A54 tealTint #E3F2EF
amberInk #8A3B07 amberTint #FCEFE3
amberInk #8A3B07 amberWash #FCF6EE
redInk #A3330B redTint #FBE7E1
violet #5B2BB5 violetTint #EFEAFB
tealInk #0B5A54 surface #FFFFFF
amberInk #8A3B07 surface #FFFFFF
redInk #A3330B surface #FFFFFF
redInk #A3330B bg #F5F6F7
blueInk #1A3596 surface #FFFFFF
blue #2346C8 surface #FFFFFF
white #FFFFFF blue #2346C8
white #FFFFFF red #C2410C
white #FFFFFF ink #14171A
text2 #4A525B bg2 #ECEEF0
recText #F2F3F5 recBg #14171A
recText2 #A9B0B9 recBg #14171A
recAccent #FF9A6B recBg #14171A
undo #9DB4FF recBg #14171A
recFresh #FF8A55 recBg #14171A
recBarOld #4A525B recBg #14171A
recLine #3A4048 recBg #14171A
recDot #FF6A2B recBg #14171A
ink #14171A recText #F2F3F5
line2 #C9CED5 surface #FFFFFF
line #E2E5E9 surface #FFFFFF
line3 #9AA2AC surface #FFFFFF
blue #2346C8 blueTrack #C9D4F6
blue #2346C8 line #E2E5E9
teal #0F766E surface #FFFFFF
amber #B45309 surface #FFFFFF
red #C2410C surface #FFFFFF
redLine #E3B9A6 surface #FFFFFF
teal #0F766E bg #F5F6F7
""".strip().splitlines()]
for n1, c1, n2, c2 in pairs:
    r = cr(c1, c2); print(f"{n1:10} {c1} on {n2:11} {c2}: {r:5.2f}  {'AA' if r >= 4.5 else ('AA-large/UI' if r >= 3 else 'FAIL')}")
