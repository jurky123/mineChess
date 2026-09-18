#!/usr/bin/env python3
"""MineChess 资源包生成：3D 棋盘、12 个低模棋子、落点/高亮贴图。

产物：pack/out/minechess.zip，交给 PackHost 放入 plugins/PackHost/packs/ 即可下发。
依赖 Pillow（与 MineUNO 的 pack/gen_pack.py 一致）。
"""
import json
import os
import shutil
import zipfile

from PIL import Image, ImageDraw, ImageFilter, ImageFont

FONT_PATH = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"
CHESS_GLYPHS = {"king": "♚", "queen": "♛", "rook": "♜",
                "bishop": "♝", "knight": "♞", "pawn": "♟"}

ROOT = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(ROOT, "src")
OUT = os.path.join(ROOT, "out")
# 2D 棋盘棋子素材（CC-BY 3.0，见 assets2d/CREDITS.txt）；缺失时回退到程序绘制的剪影
ASSETS_2D = os.path.join(ROOT, "assets2d")
NS = "minechess"

WHITE = (242, 239, 228, 255)
WHITE_DARK = (196, 187, 166, 255)
WHITE_LINE = (108, 98, 82, 255)
BLACK = (56, 54, 60, 255)
BLACK_DARK = (36, 35, 40, 255)
BLACK_LINE = (156, 150, 166, 255)

FACES = ("north", "east", "south", "west", "up", "down")


def write_json(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)


def save(img, name):
    path = os.path.join(SRC, "assets", NS, "textures/item", name + ".png")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path)
    return name


def save_gui(img, name):
    """MineUI 界面素材：assets/minechess/textures/gui/<name>.png。"""
    path = os.path.join(SRC, "assets", NS, "textures/gui", name + ".png")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path)
    return name


def piece_icon(white, kind):
    img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    font = ImageFont.truetype(FONT_PATH, 50)
    glyph = CHESS_GLYPHS[kind]
    fill = (242, 239, 228, 255) if white else (58, 56, 64, 255)
    stroke = (92, 82, 66, 255) if white else (176, 170, 188, 255)
    bbox = d.textbbox((0, 0), glyph, font=font, stroke_width=3)
    w = bbox[2] - bbox[0]
    h = bbox[3] - bbox[1]
    d.text((32 - w / 2 - bbox[0], 33 - h / 2 - bbox[1]), glyph, font=font,
           fill=fill, stroke_width=3, stroke_fill=stroke)
    return img


def sofa_icon():
    """观战席标识：32x32 像素风沙发。"""
    img = Image.new("RGBA", (32, 32), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    body = (86, 110, 164, 255)
    dark = (58, 76, 120, 255)
    light = (122, 148, 204, 255)
    line = (30, 40, 68, 255)
    # 靠背
    d.rounded_rectangle([5, 7, 26, 18], 3, fill=body, outline=line)
    # 座垫
    d.rounded_rectangle([4, 16, 27, 24], 3, fill=light, outline=line)
    # 两侧扶手
    d.rounded_rectangle([2, 11, 8, 25], 3, fill=dark, outline=line)
    d.rounded_rectangle([23, 11, 29, 25], 3, fill=dark, outline=line)
    # 靠背竖线
    d.line([(16, 8), (16, 17)], fill=dark)
    # 沙发脚
    d.rectangle([7, 25, 9, 27], fill=line)
    d.rectangle([22, 25, 24, 27], fill=line)
    return img


def logo_icon():
    img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([2, 2, 61, 61], 10, fill=(28, 32, 40, 255),
                        outline=(255, 213, 79, 255), width=3)
    # 棋盘格底纹
    for rank in range(2):
        for file in range(8):
            x0 = 8 + file * 6
            y0 = 46 + rank * 6
            color = (235, 224, 200, 255) if (rank + file) % 2 == 0 else (122, 91, 62, 255)
            d.rectangle([x0, y0, x0 + 5, y0 + 5], fill=color)
    font = ImageFont.truetype(FONT_PATH, 36)
    glyph = CHESS_GLYPHS["knight"]
    bbox = d.textbbox((0, 0), glyph, font=font, stroke_width=2)
    w = bbox[2] - bbox[0]
    h = bbox[3] - bbox[1]
    d.text((32 - w / 2 - bbox[0], 24 - h / 2 - bbox[1]), glyph, font=font,
           fill=(255, 255, 255, 255), stroke_width=2, stroke_fill=(255, 213, 79, 255))
    return img


def item_defs(name, texture, model=None):
    """name 可带 /（如 piece/white_pawn），model 为自定义 3D 模型时传 dict。"""
    write_json(os.path.join(SRC, "assets", NS, "items", name + ".json"),
               {"model": {"type": "minecraft:model", "model": "%s:item/%s" % (NS, name)}})
    if model is None:
        model = {"parent": "minecraft:item/generated",
                 "textures": {"layer0": "%s:item/%s" % (NS, texture)}}
    write_json(os.path.join(SRC, "assets", NS, "models/item", name + ".json"), model)


def cube(frm, to, texture="#0"):
    return {"from": list(frm), "to": list(to),
            "faces": {face: {"uv": [0, 0, 16, 16], "texture": texture} for face in FACES}}


def model(elements, texture):
    # 注意：物品模型必须使用 0..16 坐标（几何中心 8,8,8）。
    # 客户端渲染模型前会统一 translate(-0.5)，-8..8 建模会导致整体偏移半个方块。
    return {
        "credit": "MineChess",
        "textures": {"0": "%s:item/%s" % (NS, texture), "particle": "%s:item/%s" % (NS, texture)},
        "elements": elements,
    }


def pcube(frm, to):
    """棋子部件：x/z 以 0 为中心 → 平移到 0..16 坐标系（x/z +8）。"""
    return cube((frm[0] + 8, frm[1], frm[2] + 8), (to[0] + 8, to[1], to[2] + 8))


# ---------- 贴图 ----------

def piece_texture(base, dark, line):
    img = Image.new("RGBA", (32, 32), base)
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, 31, 31], outline=line, width=2)
    d.rectangle([5, 5, 26, 26], outline=dark, width=2)
    d.line([8, 8, 23, 8], fill=dark, width=1)
    return img


def board_texture():
    # 必须满幅：棋子按 1/8 网格摆放，贴图有边距会导致棋子偏离格子中心。
    size = 256
    cell = size // 8
    img = Image.new("RGBA", (size, size), (74, 52, 35, 255))
    d = ImageDraw.Draw(img)
    light = (235, 224, 200, 255)
    dark = (122, 91, 62, 255)
    # 模型 +X 映射到白方左手边，所以贴图的 file 轴要镜像绘制（a 列画在右侧），
    # 并让 a1 为深色（rank+file 为奇数画浅色）。
    for rank in range(8):
        for file in range(8):
            x0 = (7 - file) * cell
            y0 = rank * cell
            color = light if (rank + file) % 2 == 1 else dark
            d.rectangle([x0, y0, x0 + cell - 1, y0 + cell - 1], fill=color)
    d.rectangle([0, 0, size - 1, size - 1], outline=(52, 34, 20, 255), width=2)
    return img


# ---------- MineUI 2D 棋盘：像素风贴图（16x16 逻辑像素 x2 放大） ----------

def _pixel_piece_mask(kind):
    """16x16 逻辑像素的棋子剪影。"""
    mask = Image.new("L", (16, 16), 0)
    m = ImageDraw.Draw(mask)
    if kind == "pawn":
        m.ellipse([6, 3, 9, 6], fill=255)
        m.polygon([(6, 7), (9, 7), (10, 10), (5, 10)], fill=255)
        m.rectangle([4, 11, 11, 12], fill=255)
        m.rectangle([3, 13, 12, 14], fill=255)
    elif kind == "rook":
        m.rectangle([3, 2, 4, 3], fill=255)
        m.rectangle([6, 2, 7, 3], fill=255)
        m.rectangle([9, 2, 10, 3], fill=255)
        m.rectangle([3, 4, 12, 5], fill=255)
        m.rectangle([4, 5, 11, 11], fill=255)
        m.rectangle([3, 12, 12, 12], fill=255)
        m.rectangle([3, 13, 12, 14], fill=255)
    elif kind == "knight":
        m.polygon([(5, 3), (8, 3), (9, 4), (8, 6), (10, 7), (11, 11), (5, 11)], fill=255)
        m.polygon([(5, 3), (6, 1), (7, 3)], fill=255)
        m.rectangle([3, 12, 12, 12], fill=255)
        m.rectangle([3, 13, 12, 14], fill=255)
    elif kind == "bishop":
        m.ellipse([6, 1, 9, 4], fill=255)
        m.polygon([(6, 5), (9, 5), (10, 9), (5, 9)], fill=255)
        m.rectangle([5, 10, 10, 11], fill=255)
        m.rectangle([3, 12, 12, 12], fill=255)
        m.rectangle([3, 13, 12, 14], fill=255)
    elif kind == "queen":
        m.rectangle([3, 1, 4, 3], fill=255)
        m.rectangle([6, 0, 7, 3], fill=255)
        m.rectangle([9, 1, 10, 3], fill=255)
        m.rectangle([3, 4, 12, 5], fill=255)
        m.polygon([(5, 5), (10, 5), (11, 11), (4, 11)], fill=255)
        m.rectangle([3, 12, 12, 12], fill=255)
        m.rectangle([3, 13, 12, 14], fill=255)
    else:  # king
        m.rectangle([7, 0, 8, 3], fill=255)
        m.rectangle([5, 1, 10, 2], fill=255)
        m.rectangle([4, 4, 11, 5], fill=255)
        m.polygon([(5, 6), (10, 6), (11, 11), (4, 11)], fill=255)
        m.rectangle([3, 12, 12, 12], fill=255)
        m.rectangle([3, 13, 12, 14], fill=255)
    return mask


def piece2d_texture(kind, white):
    """像素风棋子：优先使用 assets2d 的第三方像素棋子（CC-BY 3.0），
    缺失时回退到“剪影 + 1px 描边 + 右侧轻微暗部”的程序绘制版本。"""
    src = os.path.join(ASSETS_2D, "pieces", "%s_%s.png" % ("white" if white else "black", kind))
    if os.path.exists(src):
        with Image.open(src) as art:
            return art.convert("RGBA")
    mask = _pixel_piece_mask(kind)
    outline = mask.filter(ImageFilter.MaxFilter(3))
    fill = (247, 243, 232, 255) if white else (52, 50, 58, 255)
    edge = (74, 66, 56, 255) if white else (226, 218, 200, 255)
    shade = (214, 206, 190, 255) if white else (38, 36, 43, 255)
    small = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    out_px = outline.load()
    mask_px = mask.load()
    small_px = small.load()
    for y in range(16):
        for x in range(16):
            if mask_px[x, y]:
                small_px[x, y] = shade if x >= 9 and y >= 4 else fill
            elif out_px[x, y]:
                small_px[x, y] = edge
    return small.resize((32, 32), Image.NEAREST)


def board_square_texture(light):
    """像素风棋盘格：32x32，带高光/阴影边。"""
    base = (235, 224, 198, 255) if light else (122, 91, 62, 255)
    hi = (248, 240, 220, 255) if light else (146, 112, 78, 255)
    lo = (204, 190, 162, 255) if light else (92, 66, 44, 255)
    border = (74, 52, 35, 255) if light else (58, 40, 26, 255)
    img = Image.new("RGBA", (32, 32), base)
    d = ImageDraw.Draw(img)
    d.line([(0, 0), (31, 0)], fill=hi, width=2)
    d.line([(0, 0), (0, 31)], fill=hi, width=2)
    d.line([(0, 31), (31, 31)], fill=lo, width=2)
    d.line([(31, 0), (31, 31)], fill=lo, width=2)
    d.rectangle([0, 0, 31, 31], outline=border, width=1)
    return img


def board_view_texture(black_view):
    """整板像素贴图（8x8 格，256x256），黑方视角旋转 180°。"""
    size = 256
    cell = 32
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    for row in range(8):
        for col in range(8):
            rank = row + 1 if black_view else 8 - row
            file = 7 - col if black_view else col
            light = (rank - 1 + file) % 2 == 1
            img.paste(board_square_texture(light), (col * cell, row * cell))
    return img


def highlight2d_texture(kind, size=32):
    """方块高亮；坐标按 32px 设计等比缩放到 size，便于与原生分辨率棋子合成。"""
    def sc(v):
        return int(round(v * size / 32.0))

    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    edge = size - 1
    if kind == "selected":
        d.rectangle([0, 0, edge, edge], outline=(255, 213, 79, 255), width=max(2, sc(3)))
        d.rectangle([sc(3), sc(3), edge - sc(3), edge - sc(3)], outline=(255, 236, 160, 160), width=1)
    elif kind == "legal":
        d.ellipse([sc(11), sc(11), sc(20), sc(20)], fill=(85, 255, 85, 230))
        d.ellipse([sc(13), sc(13), sc(18), sc(18)], fill=(190, 255, 190, 230))
    elif kind == "capture":
        d.ellipse([sc(2), sc(2), sc(29), sc(29)], outline=(85, 255, 85, 235), width=max(2, sc(3)))
        d.ellipse([sc(6), sc(6), sc(25), sc(25)], outline=(40, 150, 40, 170), width=1)
    elif kind == "last":
        d.rectangle([0, 0, edge, edge], fill=(255, 213, 79, 70))
        d.rectangle([0, 0, edge, edge], outline=(255, 213, 79, 150), width=max(1, sc(2)))
    elif kind == "check":
        d.rectangle([0, 0, edge, edge], fill=(220, 40, 40, 120))
        d.rectangle([0, 0, edge, edge], outline=(255, 90, 90, 235), width=max(2, sc(3)))
    return img


def overlay_texture(kind):
    size = 32
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    if kind == "dot":
        d.ellipse([10, 10, 21, 21], fill=(85, 255, 85, 220))
        d.ellipse([13, 13, 18, 18], fill=(190, 255, 190, 230))
    elif kind == "ring":
        d.ellipse([3, 3, 28, 28], outline=(85, 255, 85, 230), width=3)
        d.ellipse([7, 7, 24, 24], outline=(30, 140, 30, 160), width=1)
    elif kind == "square":
        d.rounded_rectangle([2, 2, 29, 29], radius=3, outline=(255, 215, 0, 235), width=3)
        d.rounded_rectangle([5, 5, 26, 26], radius=2, outline=(255, 240, 160, 120), width=1)
    elif kind == "check":
        d.rounded_rectangle([2, 2, 29, 29], radius=3, fill=(220, 40, 40, 130),
                            outline=(255, 90, 90, 235), width=3)
    return img


# ---------- 棋子几何（单位 1/16 方块，y=0 为底部） ----------

def piece_elements(kind):
    if kind == "pawn":
        return [pcube((-3, 0, -3), (3, 2, 3)), pcube((-2, 2, -2), (2, 6, 2)),
                pcube((-2.5, 6, -2.5), (2.5, 10, 2.5))]
    if kind == "rook":
        return [pcube((-3.5, 0, -3.5), (3.5, 2, 3.5)), pcube((-2.5, 2, -2.5), (2.5, 9, 2.5)),
                pcube((-3, 9, -3), (3, 11, 3)), pcube((-3, 11, -3), (-1, 13, -1)),
                pcube((1, 11, -3), (3, 13, -1)), pcube((-3, 11, 1), (-1, 13, 3)),
                pcube((1, 11, 1), (3, 13, 3))]
    if kind == "knight":
        return [pcube((-3, 0, -3), (3, 2, 3)), pcube((-2, 2, -2), (2, 8, 2)),
                pcube((-2, 6, -3.4), (2, 10, 1)), pcube((-1.4, 8, -4.2), (1.4, 9.6, -2.6))]
    if kind == "bishop":
        return [pcube((-3, 0, -3), (3, 2, 3)), pcube((-2.2, 2, -2.2), (2.2, 6, 2.2)),
                pcube((-1.6, 6, -1.6), (1.6, 10, 1.6)), pcube((-0.9, 10, -0.9), (0.9, 12.5, 0.9)),
                pcube((-0.4, 12.5, -0.4), (0.4, 14, 0.4))]
    if kind == "queen":
        return [pcube((-3.5, 0, -3.5), (3.5, 2, 3.5)), pcube((-2.5, 2, -2.5), (2.5, 7, 2.5)),
                pcube((-2, 7, -2), (2, 10.5, 2)), pcube((-3, 10.5, -3), (-1.4, 13, -1.4)),
                pcube((1.4, 10.5, -3), (3, 13, -1.4)), pcube((-3, 10.5, 1.4), (-1.4, 13, 3)),
                pcube((1.4, 10.5, 1.4), (3, 13, 3)), pcube((-0.9, 13, -0.9), (0.9, 15.5, 0.9))]
    # king
    return [pcube((-3.5, 0, -3.5), (3.5, 2, 3.5)), pcube((-2.5, 2, -2.5), (2.5, 7, 2.5)),
            pcube((-2, 7, -2), (2, 11, 2)), pcube((-2.6, 11, -2.6), (2.6, 13.5, 2.6)),
            pcube((-0.9, 13.5, -0.9), (0.9, 17.5, 0.9)), pcube((-2.4, 14.4, -0.7), (2.4, 16, 0.7))]


def main():
    if os.path.exists(SRC):
        shutil.rmtree(SRC)

    # 棋盘
    save(board_texture(), "board")
    item_defs("board", "board",
              model([cube((0, 0, 0), (16, 1, 16))], "board"))

    # 12 个棋子
    for side, color in (("white", WHITE), ("black", BLACK)):
        base = color
        dark = WHITE_DARK if side == "white" else BLACK_DARK
        line = WHITE_LINE if side == "white" else BLACK_LINE
        save(piece_texture(base, dark, line), "piece/" + side + "_base")
        for kind in ("pawn", "rook", "knight", "bishop", "queen", "king"):
            name = "piece/%s_%s" % (side, kind)
            item_defs(name, "piece/" + side + "_base",
                      model(piece_elements(kind), "piece/" + side + "_base"))

    # 落点与高亮
    for kind in ("dot", "ring", "square", "check"):
        save(overlay_texture(kind), "overlay/" + kind)
        item_defs("overlay/" + kind, "overlay/" + kind)

    # MineUI 界面素材（棋子图标 + Logo + 观战席沙发）
    save_gui(logo_icon(), "logo")
    save_gui(sofa_icon(), "sofa")
    for side, white in (("white", True), ("black", False)):
        for kind in CHESS_GLYPHS:
            save_gui(piece_icon(white, kind), "piece_%s_%s" % (side, kind))

    # MineUI 2D 棋盘：像素风棋盘格 / 棋子 / 高亮 / 整板
    save_gui(board_square_texture(True), "2d/square_light")
    save_gui(board_square_texture(False), "2d/square_dark")
    save_gui(board_view_texture(False), "2d/board_white")
    save_gui(board_view_texture(True), "2d/board_black")
    for side, white in (("white", True), ("black", False)):
        for kind in CHESS_GLYPHS:
            base = piece2d_texture(kind, white)
            save_gui(base, "2d/%s_%s" % (side, kind))
            # 状态绑定用变体：选中 / 可吃 / 将军（高亮在棋子底层，棋子仍清晰）
            for suffix, hl in (("_sel", "selected"), ("_cap", "capture"), ("_check", "check")):
                save_gui(Image.alpha_composite(highlight2d_texture(hl, base.size[0]), base),
                         "2d/%s_%s%s" % (side, kind, suffix))
    for kind in ("selected", "legal", "capture", "last", "check"):
        save_gui(highlight2d_texture(kind), "2d/hl_" + kind)

    # 26.2 资源包格式为 88.0（data 107）；新规范必须用 min_format/max_format 且 >= 65
    write_json(os.path.join(SRC, "pack.mcmeta"), {"pack": {
        "description": "MineChess 材质包（3D 棋盘、棋子与 2D 界面素材；2D 棋子：Lucas312，CC-BY 3.0）",
        "min_format": [88, 0],
        "max_format": 88,
    }})
    # 第三方素材署名（CC-BY 3.0 要求随包分发）
    credits = os.path.join(ASSETS_2D, "CREDITS.txt")
    if os.path.exists(credits):
        shutil.copyfile(credits, os.path.join(SRC, "CREDITS.txt"))

    os.makedirs(OUT, exist_ok=True)
    zip_path = os.path.join(OUT, "minechess.zip")
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as z:
        for root, _, files in os.walk(SRC):
            for f in sorted(files):
                full = os.path.join(root, f)
                z.write(full, os.path.relpath(full, SRC))
    print("生成完成: %s (%d KB, %d 个文件)" % (
        zip_path, os.path.getsize(zip_path) // 1024,
        sum(len(fs) for _, _, fs in os.walk(SRC))))


if __name__ == "__main__":
    main()
