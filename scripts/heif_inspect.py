#!/usr/bin/env python3
"""检查 HEIF/HEIC 文件结构：item 类型、iref 关系、属性（ispe/colr/hvcC/pixi）、增益图定位。

用法: python3 scripts/heif_inspect.py FILE [FILE...]
用于增益图/动态照片兼容性排查（2026-10-25 Google Photos HEIC HDR 排查中开发）。
"""

import struct, sys, os

def u16(d,o): return struct.unpack_from(">H",d,o)[0]
def u32(d,o): return struct.unpack_from(">I",d,o)[0]
def boxes(d, start, end, out):
    pos = start
    while pos + 8 <= end:
        size = u32(d,pos); typ = d[pos+4:pos+8].decode('latin-1'); h=8
        if size == 1: size = struct.unpack_from(">Q",d,pos+8)[0]; h=16
        elif size == 0: size = end-pos
        if size < h or pos+size > end: break
        out.append((pos,size,typ)); pos += size

def dump(path):
    d = open(path,'rb').read()
    top=[]; boxes(d,0,len(d),top)
    name=os.path.basename(path)
    meta = next((b for b in top if b[2]=='meta'), None)
    mpos,msize,_=meta
    sub=[]; boxes(d,mpos+12,mpos+msize,sub)
    items={}
    for pos,size,typ in sub:
        if typ!='iinf': continue
        v=d[pos+8]; p=pos+12
        cnt = u16(d,p) if v==0 else u32(d,p); p += 2 if v==0 else 4
        ent=[]; boxes(d,p,pos+size,ent)
        for epos,esize,etyp in ent:
            if etyp!='infe': continue
            iv=d[epos+8]
            iid=u32(d,epos+12) if iv==3 else u16(d,epos+12)
            items[iid]=d[epos+16:epos+20].decode('latin-1')
    # iprp → ipco/ipma
    props=[]; assoc={}
    for pos,size,typ in sub:
        if typ!='iprp': continue
        pr=[]; boxes(d,pos+8,pos+size,pr)
        for ppos,psize,ptyp in pr:
            if ptyp=='ipco':
                pc=[]; boxes(d,ppos+8,ppos+psize,pc)
                props=pc
            if ptyp=='ipma':
                v=d[ppos+8]; fl=u32(d,ppos+8)&0xffffff; cnt=u32(d,ppos+12); p=ppos+16
                for i in range(cnt):
                    iid = u16(d,p) if v<1 else u32(d,p); p += 2 if v<1 else 4
                    n=d[p]; p+=1; ass=[]
                    for j in range(n):
                        if fl&1: ass.append(d[p]&0x7f); p+=2
                        else: ass.append(d[p]&0x7f); p+=1
                    assoc[iid]=ass
    def desc(idx):
        if idx-1>=len(props): return "?"
        ppos,psize,ptyp = props[idx-1]
        if ptyp=='ispe': return f"{u32(d,ppos+12)}x{u32(d,ppos+16)}"
        if ptyp=='colr':
            m=d[ppos+8:ppos+12].decode('latin-1')
            if m=='nclx':
                p=ppos+12
                return f"nclx({u16(d,p)},{u16(d,p+2)},{u16(d,p+4)},{d[p+6]})"
            if m=='prof': return "prof(ICC)"
            return m
        if ptyp=='hvcC':
            return "hvcC"
        if ptyp=='pixi':
            return f"pixi({list(d[ppos+9:ppos+9+d[ppos+8]])})"
        return ptyp
    def hvcC_cf(idx):
        if idx-1>=len(props): return None
        ppos,_,ptyp = props[idx-1]
        if ptyp!='hvcC': return None
        return (d[ppos+1]>>6)&3   # chroma_format_idc 在 configurationVersion 后一字节高2位? 实际在 bit5-6? 按 heif 标准: byte1 高 2 位是 chroma_format_idc
    print(f"== {name}")
    dimg_all={}
    grid_tiles={}
    for pos,size,typ in sub:
        if typ=='iref':
            ent=[]; boxes(d,pos+12,pos+size,ent)
            for rpos,rsize,rtyp in ent:
                a=u16(d,rpos+8); n=u16(d,rpos+10)
                refs=[u16(d,rpos+12+2*k) for k in range(n)]
                if rtyp=='dimg' and a in items:
                    dimg_all[a]=refs
                    if items[a]=='grid':
                        grid_tiles[a]=refs
    tmap_id = next((i for i,t in items.items() if t=='tmap'), None)
    all_tiles = {j for v in grid_tiles.values() for j in v}
    primary = next((i for i,t in items.items() if t=='grid' and i not in all_tiles), None)
    if primary is None:
        cand = [i for i,t in items.items() if t=='hvc1' and i not in all_tiles]
        gm_from_tmap = grid_tiles.get(tmap_id, []) if tmap_id is not None else []
        cand = [i for i in cand if i not in gm_from_tmap]
        primary = cand[0] if cand else None
    gm_from_tmap = dimg_all.get(tmap_id, []) if tmap_id is not None else []
    gm_candidates = [i for i in gm_from_tmap if i != primary] or \
                    [i for i,t in items.items() if t=='hvc1' and i != primary and i not in all_tiles and i not in gm_from_tmap]
    def line(iid,label):
        ps=assoc.get(iid,[])
        parts=[desc(i) for i in ps]
        cf=[hvcC_cf(i) for i in ps]
        cf=[c for c in cf if c is not None]
        print(f"  {label} item{iid} [{items.get(iid)}]: {' '.join(parts)}" + (f" chroma={cf}" if cf else ""))
    line(primary,"主图")
    for t,refs in grid_tiles.items():
        print(f"  grid item{t} tiles={refs}")
    if gm_candidates:
        line(gm_candidates[0],"增益图")
    # iloc 找 tmap 数据
    for pos,size,typ in sub:
        if typ!='iloc': continue
        v=d[pos+8]
        osz=(d[pos+12]>>4)&0xf; lsz=d[pos+12]&0xf; bosz=(d[pos+13]>>4)&0xf
        p=pos+14+(4 if v>=1 else 0)
        cnt = u16(d,p) if v<2 else u32(d,p); p += 2 if v<2 else 4
        for i in range(cnt):
            iid = u16(d,p) if v<2 else u32(d,p); p += 2 if v<2 else 4
            if v>=1: p+=3   # construction_method(1) + data_reference_index(2)
            bo=int.from_bytes(d[p:p+bosz],'big') if bosz else 0; p+=bosz
            ec=u16(d,p); p+=2
            for e in range(ec):
                off=int.from_bytes(d[p:p+osz],'big') if osz else 0; p+=osz
                ln=int.from_bytes(d[p:p+lsz],'big') if lsz else 0; p+=lsz
                if iid==tmap_id and e==0:
                    s=bo+off
                    print(f"  tmap[{s}:{s+ln}] {d[s:s+ln].hex()}")
    print()

for p in sys.argv[1:]: dump(p)
