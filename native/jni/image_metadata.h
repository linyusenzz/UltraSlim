#pragma once
#include <algorithm>
#include <cstdint>
#include <vector>

namespace ultraslim {
// Bounded TIFF editing. Offsets stay stable; GPS payloads are zeroed before unlinking.
class ExifEditor {
  std::vector<uint8_t>& bytes;
  size_t base = 6;
  bool little = true, valid = false;
  uint16_t u16(size_t p) const { if(p+2>bytes.size()) return 0; return little ? bytes[p] | (bytes[p+1]<<8) : (bytes[p]<<8)|bytes[p+1]; }
  uint32_t u32(size_t p) const { if(p+4>bytes.size()) return 0; return little ? uint32_t(u16(p)) | (uint32_t(u16(p+2))<<16) : (uint32_t(u16(p))<<16)|u16(p+2); }
  void put16(size_t p,uint16_t v) { if(p+2>bytes.size()) return; bytes[p]=little?v:v>>8; bytes[p+1]=little?v>>8:v; }
  void put32(size_t p,uint32_t v) { if(little){put16(p,v);put16(p+2,v>>16);}else{put16(p,v>>16);put16(p+2,v);} }
  size_t ifd() const { return base + u32(base+4); }
  bool bounded(size_t p) const { return valid && p>=base+8 && p+2<=bytes.size() && p+2+size_t(u16(p))*12+4<=bytes.size(); }
  bool wipeGps(size_t p) {
    if(!bounded(p)) return false;
    const size_t sizes[]={0,1,1,2,4,8,1,1,2,4,8,4,8};
    auto count=u16(p);
    bool complete = true;
    for(size_t i=0;i<count;i++) {
      size_t e=p+2+i*12; auto type=u16(e+2); auto n=u32(e+4);
      if(type==0 || type>=13) { complete = false; continue; }
      uint64_t len=uint64_t(sizes[type])*n;
      if(len>4) {
        uint64_t start=base+uint64_t(u32(e+8));
        if(start<p+2+size_t(count)*12+4 || start+len>bytes.size()) { complete = false; continue; }
        std::fill(bytes.begin()+start,bytes.begin()+start+len,0);
      }
    }
    std::fill(bytes.begin()+p,bytes.begin()+p+2+count*12+4,0);
    return complete;
  }
 public:
  explicit ExifEditor(std::vector<uint8_t>& b):bytes(b) {
    if(b.size()<14) return;
    little=b[6]=='I'&&b[7]=='I';
    valid=(little||(b[6]=='M'&&b[7]=='M'))&&u16(8)==42;
  }
  int orientation() const {
    auto p=ifd(); if(!bounded(p))return 1;
    for(int i=0;i<u16(p);i++){size_t e=p+2+i*12;if(u16(e)==0x112&&u16(e+2)==3&&u32(e+4)==1){int v=u16(e+8);return v>=1&&v<=8?v:1;}}
    return 1;
  }
  bool hasGps() const {
    auto p=ifd(); if(!bounded(p))return false;
    for(int i=0;i<u16(p);i++){if(u16(p+2+i*12)==0x8825) return true;}
    return false;
  }
  bool stripGpsOnly() {
    auto p=ifd(); if(!bounded(p))return false;
    bool stripped=false;
    for(int i=0;i<u16(p);i++) {
      size_t e=p+2+i*12;
      if(u16(e)==0x8825) {
        const auto n=u16(p); const uint64_t gps=base+uint64_t(u32(e+8));
        if(gps<p+2+size_t(n)*12+4 || gps>=bytes.size() || !wipeGps(size_t(gps))) return false;
        std::move(bytes.begin()+e+12,bytes.begin()+p+2+n*12+4,bytes.begin()+e);
        std::fill(bytes.begin()+p+2+(n-1)*12+4,bytes.begin()+p+2+n*12+4,0);
        put16(p,n-1);--i;stripped=true;
      }
    }
    return stripped;
  }
  void normalize(int width,int height,bool keepLocation,int orientation = 1) {
    auto p=ifd(); if(!bounded(p))return;
    size_t exif=0;
    for(int i=0;i<u16(p);i++) {
      size_t e=p+2+i*12; auto tag=u16(e);
      if(tag==0x8769) exif=base+u32(e+8);
      if(tag==0x112) {put16(e+2,3);put32(e+4,1);put32(e+8,0);put16(e+8,orientation);}
      if(tag==0x100||tag==0x101){put16(e+2,4);put32(e+4,1);put32(e+8,tag==0x100?width:height);}
      if(tag==0x8825&&!keepLocation) {
        const auto n=u16(p); const uint64_t gps=base+uint64_t(u32(e+8));
        if(gps>=p+2+size_t(n)*12+4 && gps<bytes.size()) wipeGps(size_t(gps));
        std::move(bytes.begin()+e+12,bytes.begin()+p+2+n*12+4,bytes.begin()+e);
        std::fill(bytes.begin()+p+2+(n-1)*12+4,bytes.begin()+p+2+n*12+4,0);
        put16(p,n-1);--i;
      }
    }
    // A source thumbnail and dimensions must not describe the unscaled/unrotated source.
    put32(p+2+u16(p)*12,0);
    if(bounded(exif)) for(int i=0;i<u16(exif);i++){size_t e=exif+2+i*12;auto tag=u16(e);if(tag==0xa002||tag==0xa003){put16(e+2,4);put32(e+4,1);put32(e+8,tag==0xa002?width:height);}}
  }
};
}
