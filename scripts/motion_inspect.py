#!/usr/bin/env python3
"""检查动态照片样本的内部布局，用于厂商格式逆向（小米/OPPO/vivo/荣耀）。

用法: python3 scripts/motion_inspect.py FILE [FILE...]

输出每个文件: JPEG 段或 HEIF 顶层 box 结构、XMP 运动相关字段、所有 ftyp 位置、
尾部 hexdump、厂商标记，以及与 app MotionPhotoContainer.detect 等价的判定路径。
仅用标准库；不修改文件。
"""

import re
import sys

XMP_HEADER = b"http://ns.adobe.com/xap/1.0/\x00"
XMLNS_PATTERN = "xmlns:(\\w+)="
VENDOR_MARKERS = [
    b"SEFT", b"SEFH", b"MotionPhoto_Data", b"vivovideo", b"vivo_",
    b"HwMef", b"hw_mef", b"oplus", b"OpCamera", b"mi_live", b"Xiaomi",
]
MOTION_FIELDS = {
    "MotionPhoto", "MotionPhotoVersion", "MotionPhotoPresentationTimestampUs",
    "MicroVideo", "MicroVideoVersion", "MicroVideoOffset",
    "MicroVideoPresentationTimestampUs", "VideoLength", "Semantic", "Mime",
    "Length", "Padding",
}


def u32(data, off):
    return int.from_bytes(data[off:off + 4], "big")


def s32(data, off):
    return int.from_bytes(data[off:off + 4], "big", signed=False) - (1 << 32 if data[off] & 0x80 else 0)


def fourcc(data, off):
    return data[off:off + 4].decode("latin-1", "replace")


def box_at(data, off, end):
    if off < 0 or end - off < 8:
        return None
    if any(not (0x20 <= data[off + 4 + i] <= 0x7e) for i in range(4)):
        return None  # box type 必须可打印 ASCII，拒绝伪造成吞尾 box 的厂商信封
    size = u32(data, off)
    header = 8
    if size == 1:
        if end - off < 16:
            return None
        size = int.from_bytes(data[off + 8:off + 16], "big")
        header = 16
    elif size == 0:
        size = end - off
    if size < header or size > end - off:
        return None
    return off, size, header, fourcc(data, off + 4)


def mp4_walk(data, off, end):
    """模仿 validVideo: 首 box 必须 ftyp、需含 moov+mdat、干净走到 end。"""
    boxes, pos = [], off
    if off < 0 or end - off < 24:
        return False, boxes, "区域不足 24 字节"
    while pos < end:
        b = box_at(data, pos, end)
        if b is None:
            return False, boxes, f"box 解析失败 @{pos}（剩余 {end - pos} 字节不是干净结构）"
        boxes.append((pos, b[1], b[3]))
        pos += b[1]
    types = {t for _, _, t in boxes}
    if boxes and boxes[0][2] != "ftyp":
        return False, boxes, f"首 box 是 {boxes[0][2]} 不是 ftyp"
    if "moov" not in types:
        return False, boxes, "缺少 moov"
    if "mdat" not in types:
        return False, boxes, "缺少 mdat"
    return True, boxes, "ok"


def jpeg_segments(data):
    pos = 2
    out = []
    while pos + 4 <= len(data):
        if data[pos] != 0xFF:
            return out, f"段结构断裂 @{pos}"
        marker = data[pos + 1]
        if marker in (0xDA, 0xD9):
            return out, None
        length = (data[pos + 2] << 8) | data[pos + 3]
        if length < 2 or pos + 2 + length > len(data):
            return out, f"APP 段长度异常 @{pos} ({length})"
        out.append((pos, marker, length))
        pos += length + 2
    return out, "未找到 SOS/EOI"


def jpeg_xmp(data):
    segments, err = jpeg_segments(data)
    if err:
        return None
    for start, marker, length in segments:
        if marker != 0xE1:
            continue
        payload = data[start + 4:start + 2 + length]
        if payload.startswith(XMP_HEADER):
            return payload[len(XMP_HEADER):].decode("utf-8", "replace")
    return None


def _read_u16(d, o):
    return int.from_bytes(d[o:o + 2], "big")


def _read_u32(d, o):
    return int.from_bytes(d[o:o + 4], "big")


def heif_xmp(data):
    """顶层 xml/mime box 或 HEIF/AVIF 的 mime/xml XMP item（经 iinf+iloc 定位）。"""
    pos, size = 0, len(data)
    meta = None
    while pos < size:
        b = box_at(data, pos, size)
        if b is None:
            break
        if b[3] in ("xml ", "mime"):
            return data[pos + b[2]:pos + b[1]].decode("utf-8", "replace")
        if b[3] == "meta":
            meta = b
        pos += b[1]
    if meta is None:
        return None
    mpos, msize, _, _ = meta
    sub = []
    p = mpos + 12  # FullBox 版本/标志
    while p < mpos + msize:
        b = box_at(data, p, mpos + msize)
        if b is None:
            break
        sub.append(b)
        p += b[1]
    items = {}
    iloc = None
    idat = None
    for spos, ssize, _hdr, styp in sub:
        if styp == "iinf":
            v = data[spos + 8]
            q = spos + 12
            cnt = _read_u16(data, q) if v == 0 else _read_u32(data, q)
            q += 2 if v == 0 else 4
            r = q
            while r < spos + ssize:
                esz = _read_u32(data, r)
                if esz < 8:
                    break
                if data[r + 4:r + 8] != b"infe":
                    r += esz
                    continue
                iv = data[r + 8]
                iid = _read_u32(data, r + 12) if iv == 3 else _read_u16(data, r + 12)
                items[iid] = data[r + 16:r + 20]
                r += esz
        if styp == "idat":
            idat = spos + 8
        if styp == "iloc":
            iloc = (spos, ssize)
    if iloc is None:
        return None
    spos, ssize = iloc
    v = data[spos + 8]
    osz = (data[spos + 12] >> 4) & 0xF
    lsz = data[spos + 12] & 0xF
    bosz = (data[spos + 13] >> 4) & 0xF
    p = spos + 14
    cnt = _read_u16(data, p) if v < 2 else _read_u32(data, p)
    p += 2 if v < 2 else 4
    for _ in range(cnt):
        iid = _read_u16(data, p) if v < 2 else _read_u32(data, p)
        p += 2 if v < 2 else 4
        cm = _read_u16(data, p) if v >= 1 else 0
        if v >= 1:
            p += 2
        p += 2
        bo = int.from_bytes(data[p:p + bosz], "big") if bosz else 0
        p += bosz
        ec = _read_u16(data, p)
        p += 2
        for _ in range(ec):
            off = int.from_bytes(data[p:p + osz], "big") if osz else 0
            p += osz
            ln = int.from_bytes(data[p:p + lsz], "big") if lsz else 0
            p += lsz
            if items.get(iid) in (b"mime", b"xml "):
                s = (idat + off) if cm == 1 else bo + off
                text = data[s:s + ln].decode("utf-8", "replace")
                if "rdf" in text or "xmp" in text.lower():
                    return text
    return None


def xmp_motion_fields(xmp):
    """返回 (标量字段 dict, Container 目录项列表)。"""
    if not xmp:
        return {}, []
    out = {}
    for full, value in re.findall(r"((?:\w+):(?:\w+))\s*=\s*[\"']([^\"']*)[\"']", xmp):
        local = full.split(":", 1)[1]
        if local in MOTION_FIELDS or local.startswith("MicroVideo"):
            out.setdefault(full, []).append(value)
    items = []
    for attrs in re.findall(r"<[\w:]*(?:Item|item)\b([^>]*)>", xmp):
        item = dict(re.findall(r"([\w:]+)\s*=\s*[\"']([^\"']*)[\"']", attrs))
        items.append(item)
    return out, items


def motion_item_length(items):
    for item in reversed(items):
        semantic = next((v for k, v in item.items() if k.endswith("Semantic")), "")
        if semantic.lower() == "motionphoto":
            for k, v in item.items():
                if k.endswith("Length"):
                    return int(v)
    return None


def verdict(data, xmp_fields, items):
    """复刻 app detect() 的判定顺序，返回 (结论, 说明)。"""
    size = len(data)
    is_jpeg = data[:2] == b"\xff\xd8"
    container_len = motion_item_length(items)
    micro_offset = None
    for full, values in xmp_fields.items():
        if full.endswith("MicroVideoOffset"):
            micro_offset = int(values[0])
    video_length = None
    for full, values in xmp_fields.items():
        if full.endswith("VideoLength"):
            video_length = int(values[0])
    declared = container_len if container_len else micro_offset
    if video_length and 0 < video_length < size:
        for start in dict.fromkeys([size - declared if declared else None, size - video_length]):
            if start is None:
                continue
            ok, _, why = mp4_walk(data, start, start + video_length)
            if ok:
                return f"OPPO VideoLength 路径命中: 视频 [{start}, {start + video_length}) 长度 {video_length}", None
    if declared and 0 < declared < size:
        ok, _, why = mp4_walk(data, size - declared, size)
        if ok:
            return f"标准 XMP 路径命中: 视频 [{size - declared}, {size}) 长度 {declared}", None
    if not is_jpeg:
        pos, appended = 0, None
        while pos < size:
            b = box_at(data, pos, size)
            if b is None:
                break
            if b[3] == "mpvd" and pos + b[1] == size:
                off = pos + b[2]
                ok, _, _ = mp4_walk(data, off, size)
                if ok:
                    return f"mpvd 路径命中: 视频 [{off}, {size})", None
            if b[3] == "ftyp" and pos > 0:
                appended = pos
            pos += b[1]
        if appended is not None:
            ok, _, _ = mp4_walk(data, appended, size)
            if ok:
                return f"顶层第二 ftyp 命中: 视频 [{appended}, {size})", None
    ftyps = [m.start() - 4 for m in re.finditer(b"ftyp", data) if m.start() >= 4]
    for off in reversed(ftyps):
        ok, _, _ = mp4_walk(data, off, size)
        if ok:
            return f"回扫路径命中: 视频 [{off}, {size}) 长度 {size - off}", None
    detail = []
    if declared:
        ok, _, why = mp4_walk(data, size - declared, size)
        detail.append(f"XMP 声明长度 {declared} 校验失败（{why}）")
    elif micro_offset is None and container_len is None:
        detail.append("无运动 XMP 字段")
    if ftyps:
        tail = mp4_walk(data, ftyps[-1], size)
        detail.append(f"末个 ftyp @{ftyps[-1]} 到 EOF 校验: {tail[2]}")
    else:
        detail.append("文件内无 ftyp")
    return "未识别为动态照片", "; ".join(detail)


def hexdump(data, start, length):
    out = []
    for i in range(0, length, 16):
        chunk = data[start + i:start + i + 16]
        if not chunk:
            break
        hexs = " ".join(f"{b:02x}" for b in chunk)
        text = "".join(chr(b) if 32 <= b < 127 else "." for b in chunk)
        out.append(f"  {start + i:08x}  {hexs:<47}  {text}")
    return "\n".join(out)


def inspect(path):
    with open(path, "rb") as f:
        data = f.read()
    size = len(data)
    print(f"\n=== {path} ({size:,} bytes) ===")
    print(f"magic: {data[:16].hex()}  ({data[:4]!r})")
    is_jpeg = data[:2] == b"\xff\xd8"
    if is_jpeg:
        segments, err = jpeg_segments(data)
        for start, marker, length in segments:
            note = "XMP" if marker == 0xE1 and data[start + 4:start + 4 + 29].startswith(XMP_HEADER[:20]) else ""
            print(f"  JPEG 段 @{start}: marker FF{marker:02X} len {length} {note}")
        if err:
            print(f"  JPEG 段解析: {err}")
    else:
        pos = 0
        while pos < size:
            b = box_at(data, pos, size)
            if b is None:
                print(f"  顶层 box 解析中断 @{pos}（剩余 {size - pos} 字节）")
                break
            print(f"  box @{pos}: {b[3]!r} size {b[1]}")
            pos += b[1]
    xmp = jpeg_xmp(data) if is_jpeg else heif_xmp(data)
    fields, items = xmp_motion_fields(xmp) if xmp else ({}, [])
    if xmp:
        print("  XMP 运动字段:")
        for full, values in fields.items():
            print(f"    {full} = {', '.join(values)}")
        for item in items:
            print(f"    目录项: {item}")
        print(f"  XMP 命名空间: {sorted(set(re.findall(XMLNS_PATTERN, xmp)))}")
    else:
        print("  XMP: 未找到")
    ftyps = [m.start() - 4 for m in re.finditer(b"ftyp", data)]
    if ftyps:
        for off in ftyps:
            brand = data[off + 8:off + 12]
            print(f"  ftyp @{off} brand={brand!r}")
        ok, boxes, why = mp4_walk(data, ftyps[-1], size)
        print(f"  末个 ftyp 到 EOF 的 box 序列: {' '.join(t for _, _, t in boxes[:12])} → {why}")
    window = data[max(0, size - 262144):]
    for marker in VENDOR_MARKERS:
        i = window.rfind(marker)
        if i >= 0:
            print(f"  厂商标记 {marker!r} @文件偏移 {max(0, size - 262144) + i}")
    print(f"  尾部 64 字节:\n{hexdump(data, size - 64, 64)}")
    result, detail = verdict(data, fields, items)
    print(f"  判定: {result}")
    if detail:
        print(f"  细节: {detail}")


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(2)
    for path in sys.argv[1:]:
        try:
            inspect(path)
        except Exception as exc:  # 样本千奇百怪，单个文件失败不中断批量分析
            print(f"\n=== {path} ===\n  分析失败: {exc!r}")


if __name__ == "__main__":
    main()
