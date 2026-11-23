#include "../jni/transcode.h"
#include "../jni/image_metadata.h"
#include <ultrahdr_api.h>
#include "ultrahdr/jpegdecoderhelper.h"
#include "ultrahdr/jpegrutils.h"
#include <libheif/heif.h>
#include <jpeglib.h>
#include <fcntl.h>
#include <unistd.h>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <algorithm>
#include <vector>
#include <string>

static void check(bool ok, const char* msg) { if(!ok){fprintf(stderr,"FAIL: %s\n",msg);exit(1);} }
static std::vector<uint8_t> readFile(const std::string& path) {
 FILE*f=fopen(path.c_str(),"rb");check(f,"open");fseek(f,0,SEEK_END);long n=ftell(f);rewind(f);std::vector<uint8_t>b(n);check(fread(b.data(),1,n,f)==size_t(n),"read");fclose(f);return b;
}
struct Map { int w=0,h=0,matrix=-1,fullRange=-1; bool multi=false; std::vector<uint8_t> rgb,compressed,iso; uhdr_gainmap_metadata_t meta{}; };
static Map map(const std::string& path, bool jpeg) {
 Map r;auto bytes=readFile(path);auto*d=uhdr_create_decoder();uhdr_compressed_image_t src{};src.data=bytes.data();src.data_sz=src.capacity=bytes.size();
 check(uhdr_dec_set_image(d,&src).error_code==UHDR_CODEC_OK && uhdr_dec_probe(d).error_code==UHDR_CODEC_OK,"gain map recognized");
 r.meta=*uhdr_dec_get_gainmap_metadata(d);
 if(jpeg){
  auto*b=uhdr_dec_get_gainmap_image(d);r.compressed.assign((uint8_t*)b->data,(uint8_t*)b->data+b->data_sz);
  jpeg_decompress_struct c{};jpeg_error_mgr e{};c.err=jpeg_std_error(&e);jpeg_create_decompress(&c);jpeg_mem_src(&c,r.compressed.data(),r.compressed.size());jpeg_read_header(&c,TRUE);
  r.multi=c.num_components>1;c.out_color_space=JCS_RGB;jpeg_start_decompress(&c);r.w=c.output_width;r.h=c.output_height;r.rgb.resize(size_t(r.w)*r.h*3);
  while(c.output_scanline<c.output_height){JSAMPROW p=r.rgb.data()+size_t(c.output_scanline)*r.w*3;jpeg_read_scanlines(&c,&p,1);}jpeg_finish_decompress(&c);jpeg_destroy_decompress(&c);
 } else {
  auto*c=heif_context_alloc();check(heif_context_read_from_file(c,path.c_str(),nullptr).code==heif_error_Ok,"container read");heif_image_handle*b=nullptr,*g=nullptr;
  heif_context_get_primary_image_handle(c,&b);check(heif_image_handle_get_gain_map_image_handle(b,&g).code==heif_error_Ok,"map handle");
  heif_color_profile_nclx* profile=nullptr;
  if(heif_image_handle_get_nclx_color_profile(g,&profile).code==heif_error_Ok){r.matrix=profile->matrix_coefficients;r.fullRange=profile->full_range_flag;heif_nclx_color_profile_free(profile);}
  r.iso.resize(heif_image_handle_get_gain_map_metadata_size(b));heif_image_handle_get_gain_map_metadata(b,r.iso.data());
  heif_image*i=nullptr;check(heif_decode_image(g,&i,heif_colorspace_undefined,heif_chroma_undefined,nullptr).code==heif_error_Ok,"native map decode");r.multi=heif_image_get_chroma_format(i)!=heif_chroma_monochrome;heif_image_release(i);
  check(heif_decode_image(g,&i,heif_colorspace_RGB,heif_chroma_interleaved_RGB,nullptr).code==heif_error_Ok,"map RGB decode");
  r.w=heif_image_get_primary_width(i);r.h=heif_image_get_primary_height(i);r.rgb.resize(size_t(r.w)*r.h*3);int stride;auto*p=heif_image_get_plane_readonly(i,heif_channel_interleaved,&stride);
  for(int y=0;y<r.h;y++)memcpy(r.rgb.data()+size_t(y)*r.w*3,p+y*stride,r.w*3);
  heif_image_release(i);heif_image_handle_release(g);heif_image_handle_release(b);heif_context_free(c);
 }
 uhdr_release_decoder(d);return r;
}
static std::vector<uint8_t> jpeg(int w,int h,bool multi,int orientation,bool rowBands=false) {
 jpeg_compress_struct c{};jpeg_error_mgr e{};c.err=jpeg_std_error(&e);jpeg_create_compress(&c);unsigned char*mem=nullptr;unsigned long len=0;jpeg_mem_dest(&c,&mem,&len);
 c.image_width=w;c.image_height=h;c.input_components=multi?3:1;c.in_color_space=multi?JCS_RGB:JCS_GRAYSCALE;jpeg_set_defaults(&c);jpeg_set_quality(&c,85,TRUE);jpeg_start_compress(&c,TRUE);
 if(orientation){unsigned char exif[]={'E','x','i','f',0,0,'I','I',42,0,8,0,0,0,1,0,0x12,1,3,0,1,0,0,0,(uint8_t)orientation,0,0,0,0,0,0,0};jpeg_write_marker(&c,JPEG_APP0+1,exif,sizeof(exif));}
 std::vector<uint8_t> row(w*c.input_components);
 while(c.next_scanline<c.image_height){for(int x=0;x<w;x++)for(int ch=0;ch<c.input_components;ch++)row[x*c.input_components+ch]=rowBands ? ((c.next_scanline*17)%192+x%16+ch*16)%256 : (x*7+c.next_scanline*11+ch*73)%256;JSAMPROW r=row.data();jpeg_write_scanlines(&c,&r,1);}
 jpeg_finish_compress(&c);std::vector<uint8_t> out(mem,mem+len);free(mem);jpeg_destroy_compress(&c);return out;
}
static void fixture(const std::string& path,bool multi,int orientation,int gw=41,int gh=25,int bw=163,int bh=99,bool rowBands=false,bool sharedBoost=false){
 auto b=jpeg(bw,bh,true,orientation,rowBands),g=jpeg(gw,gh,multi,0);auto*e=uhdr_create_encoder();uhdr_compressed_image_t base{},gm{};base.data=b.data();base.data_sz=base.capacity=b.size();base.cg=UHDR_CG_BT_709;base.ct=UHDR_CT_SRGB;base.range=UHDR_CR_FULL_RANGE;gm=base;gm.data=g.data();gm.data_sz=gm.capacity=g.size();
 uhdr_gainmap_metadata_t m{};for(int i=0;i<3;i++){m.min_content_boost[i]=1;m.max_content_boost[i]=sharedBoost?4:4+i;m.gamma[i]=1;m.offset_sdr[i]=m.offset_hdr[i]=1.0f/64;}m.hdr_capacity_min=1;m.hdr_capacity_max=8;m.use_base_cg=1;
 check(uhdr_enc_set_compressed_image(e,&base,UHDR_BASE_IMG).error_code==UHDR_CODEC_OK,"set base");check(uhdr_enc_set_gainmap_image(e,&gm,&m).error_code==UHDR_CODEC_OK,"set gain map");check(uhdr_encode(e).error_code==UHDR_CODEC_OK,"mux fixture");auto*r=uhdr_get_encoded_stream(e);FILE*f=fopen(path.c_str(),"wb");fwrite(r->data,1,r->data_sz,f);fclose(f);uhdr_release_encoder(e);
}
static std::vector<uint8_t> scanData(const std::vector<uint8_t>& jpeg) {
 size_t p=2;while(p+4<jpeg.size()){check(jpeg[p]==0xff,"JPEG marker");int marker=jpeg[p+1];if(marker==0xda)return {jpeg.begin()+p,jpeg.end()};size_t len=(jpeg[p+2]<<8)|jpeg[p+3];check(len>=2,"marker size");p+=len+2;}check(false,"scan exists");return {};
}
static void sameMetadata(const Map&a,const Map&b){
 for(int c=0;c<3;c++) {check(a.meta.min_content_boost[c]==b.meta.min_content_boost[c] && a.meta.max_content_boost[c]==b.meta.max_content_boost[c] && a.meta.gamma[c]==b.meta.gamma[c] && a.meta.offset_sdr[c]==b.meta.offset_sdr[c] && a.meta.offset_hdr[c]==b.meta.offset_hdr[c],"HDR channel parameters unchanged");}
 check(a.meta.hdr_capacity_min==b.meta.hdr_capacity_min&&a.meta.hdr_capacity_max==b.meta.hdr_capacity_max&&a.meta.use_base_cg==b.meta.use_base_cg,"HDR headroom and color space unchanged");
}
static void equal(const Map&a,const Map&b,int orientation=1,int maxDiff=0){
 check(a.multi==b.multi,"original channel count preserved");int w=orientation>=5?a.h:a.w,h=orientation>=5?a.w:a.h;check(w==b.w&&h==b.h,"original map dimensions/orientation preserved");
 for(int y=0;y<a.h;y++)for(int x=0;x<a.w;x++){
  int dx=x,dy=y;switch(orientation){case 2:dx=a.w-1-x;break;case 3:dx=a.w-1-x;dy=a.h-1-y;break;case 4:dy=a.h-1-y;break;case 5:dx=y;dy=x;break;case 6:dx=a.h-1-y;dy=x;break;case 7:dx=a.h-1-y;dy=a.w-1-x;break;case 8:dx=y;dy=a.w-1-x;break;}
  for(int c=0;c<3;c++){
   int diff = std::abs((int)a.rgb[(y*a.w+x)*3+c] - (int)b.rgb[(dy*w+dx)*3+c]);
   if(diff > maxDiff){fprintf(stderr,"pixel %d,%d channel %d: %d -> %d\n",x,y,c,a.rgb[(y*a.w+x)*3+c],b.rgb[(dy*w+dx)*3+c]);check(false,"gain map pixel-exact preservation");}
  }
 }
 sameMetadata(a,b);
}
static void convert(const std::string& src,const std::string& dst,int codec,int resize=100,int speed=1,bool retry=false,int quality=90,bool compatible=false,bool heicCompatible=false){
 int a=open(src.c_str(),O_RDONLY),b=open(dst.c_str(),O_RDWR|O_CREAT|O_TRUNC,0600);ultraslim::TranscodeOptions o;o.outCodec=codec;o.codecThreads=2;o.resizePercent=resize;o.speedLevel=speed;o.retrySmaller=retry;o.quality=quality;
 o.avifGainmapCompatibility=compatible;
 o.heicGainmapCompatibility=heicCompatible;
 // Deliberately incompatible legacy settings must have no effect on preservation.
 o.gainmapScale=128;o.gainmapQuality=1;o.multiChannelGainmap=false;
 auto r=ultraslim::transcodeImage(a,b,o);close(a);close(b);if(r.errorCode)fprintf(stderr,"%s\n",r.error.c_str());check(r.errorCode==0,"preserving transcode");
}
static void refuses(const std::string& source,const std::string& output,int codec) {
 int a=open(source.c_str(),O_RDONLY),b=open(output.c_str(),O_RDWR|O_CREAT|O_TRUNC,0600);
 ultraslim::TranscodeOptions o;o.outCodec=codec;auto r=ultraslim::transcodeImage(a,b,o);
 check(r.errorCode==31,"unsupported preservation is explicit, not silent rebuild");check(lseek(b,0,SEEK_END)==0,"unsupported output is empty");close(a);close(b);
}
static void highBitDepthFixture(const std::string& path,const std::vector<uint8_t>& iso) {
 auto*ctx=heif_context_alloc();const heif_encoder_descriptor*d=nullptr;
 check(heif_context_get_encoder_descriptors(ctx,heif_compression_AV1,"aom",&d,1)==1,"AOM present");
 heif_encoder*e=nullptr;heif_context_get_encoder(ctx,d,&e);heif_encoder_set_parameter_integer(e,"speed",6);
 heif_encoder_set_lossless(e,1);
 auto makeImage=[](int size,int depth){heif_image*i=nullptr;check(heif_image_create(size,size,heif_colorspace_monochrome,heif_chroma_monochrome,&i).code==heif_error_Ok,"create mono");heif_image_add_plane(i,heif_channel_Y,size,size,depth);int stride;auto*p=heif_image_get_plane(i,heif_channel_Y,&stride);for(int y=0;y<size;y++){if(depth==8)memset(p+y*stride,128,size);else for(int x=0;x<size;x++)((uint16_t*)(p+y*stride))[x]=(x*31+y*47)%1024;}return i;};
 auto*base=makeImage(64,8);heif_image_handle*b=nullptr;check(heif_context_encode_image(ctx,base,e,nullptr,&b).code==heif_error_Ok,"encode base");
 auto*gm=makeImage(16,10);heif_image_handle*g=nullptr;check(heif_context_encode_gain_map_image(ctx,b,e,gm,nullptr,iso.data(),iso.size(),nullptr,&g).code==heif_error_Ok,"encode 10-bit map");
 check(heif_context_write_to_file(ctx,path.c_str()).code==heif_error_Ok,"write 10-bit fixture");
 if(g)heif_image_handle_release(g);heif_image_handle_release(b);heif_image_release(gm);heif_image_release(base);heif_encoder_release(e);heif_context_free(ctx);
}
int main(int argc,char**argv){
 if(argc==3 && std::string(argv[1])=="--preview-fixture") {fixture(argv[2],true,1,1025,769,4096,3072,true);return 0;}
 if(argc==3 && std::string(argv[1])=="--benchmark-fixture") {fixture(argv[2],true,1,1025,769,4096,3072);return 0;}
 if(argc==3 && std::string(argv[1])=="--grid-fixture") {fixture(argv[2],true,1,513,769,5001,513);return 0;}
 if(argc==4 && (std::string(argv[1])=="--compare" || std::string(argv[1])=="--compare-compatible")) {
  auto bytes=readFile(argv[2]);const bool jpegIn=bytes[0]==0xff&&bytes[1]==0xd8;auto out=readFile(argv[3]);const bool jpegOut=out[0]==0xff&&out[1]==0xd8;
  int orientation=1;
  if(jpegIn&&!jpegOut) {auto*d=uhdr_create_decoder();uhdr_compressed_image_t src{};src.data=bytes.data();src.data_sz=src.capacity=bytes.size();uhdr_dec_set_image(d,&src);uhdr_dec_probe(d);auto*e=uhdr_dec_get_exif(d);if(e){std::vector<uint8_t>x((uint8_t*)e->data,(uint8_t*)e->data+e->data_sz);orientation=ultraslim::ExifEditor(x).orientation();}uhdr_release_decoder(d);}
  auto a=map(argv[2],jpegIn),b=map(argv[3],jpegOut);
  const int maxDiff=std::string(argv[1])=="--compare-compatible" && a.multi ? 3 : 0;
  equal(a,b,orientation,maxDiff);
  printf("PASS: %dx%d %s gain map, orientation %d, max pixel error <= %d/255 and exact HDR metadata\n",a.w,a.h,a.multi?"RGB":"mono",orientation,maxDiff);return 0;
 }
 check(argc==3,"output-dir and Apple fixture required");std::string root=argv[1];
 auto apple=map(argv[2],true);check(apple.meta.use_base_cg==1,"Apple maps use the base color space");
 auto sourceBytes=readFile(argv[2]);ultrahdr::JpegDecoderHelper primary,mapParser;
 primary.parseImage(sourceBytes.data(),sourceBytes.size());mapParser.parseImage(apple.compressed.data(),apple.compressed.size());
 ultrahdr::uhdr_gainmap_metadata_ext_t sentinel;sentinel.use_base_cg=false;
 check(ultrahdr::getMetadataFromXMP((uint8_t*)mapParser.getXMPPtr(),mapParser.getXMPSize(),(uint8_t*)primary.getEXIFPtr(),primary.getEXIFSize(),&sentinel).error_code==UHDR_CODEC_OK,"Apple XMP parse");
 check(sentinel.use_base_cg==true,"Apple parser initializes the application color space instead of inheriting stack data");
 for(int codec:{1,2}){auto dst=root+"/apple-exact-"+std::to_string(codec);convert(argv[2],dst,codec);equal(apple,map(dst,false));}

 auto reference=map(root+"/apple-exact-2",false);auto high=root+"/high-bit-depth.avif";
 highBitDepthFixture(high,reference.iso);refuses(high,high+"-refused",2);
 refuses(root+"/apple-exact-2",root+"/refused-jpeg.jpg",0);
 for(bool multi:{false,true})for(int orientation=1;orientation<=8;orientation++){
  auto src=root+"/original-"+std::to_string(multi)+"-"+std::to_string(orientation)+".jpg";fixture(src,multi,orientation);auto original=map(src,true);
  auto jpg=src+".jpg";convert(src,jpg,0);auto copied=map(jpg,true);equal(original,copied);check(scanData(original.compressed)==scanData(copied.compressed),"JPEG gain map encoded scan unchanged");
  for(int codec:{1,2}){
   auto dst=src+"-"+std::to_string(codec);convert(src,dst,codec);auto first=map(dst,false);equal(original,first,orientation);
   // Compatibility is opt-in, AVIF RGB only. Check every pixel including odd
   // edges and all EXIF orientations, with bounded RGB/YUV rounding only.
   auto compat=dst+"-compat";convert(src,compat,codec,50,2,false,90,true);
   const auto compatibleMap=map(compat,false);
   equal(original,compatibleMap,orientation,codec==2&&multi?3:0);
   check(compatibleMap.matrix==(codec==2&&multi?6:0),"compatible RGB uses BT.601; strict/mono stays Identity");
   check(compatibleMap.fullRange==(codec==2&&multi?0:1),"gain-map range matches encoded samples");
   auto round=dst+"-round";convert(dst,round,codec==1?2:1,50);auto second=map(round,false);equal(first,second);check(first.iso==second.iso,"ISO metadata preserved byte-for-byte between containers");
  }
 }
 // HEIC compatibility must retain mono values and HDR metadata across all
 // orientations, odd dimensions and main-image resizing. RGB 4:2:0 is lossy,
 // but must not permute channels or systematically shift their mean values.
 for(bool multi:{false,true}) for(int orientation=1;orientation<=8;orientation++) {
  auto src=root+"/original-"+std::to_string(multi)+"-"+std::to_string(orientation)+".jpg";
  auto dst=src+"-heic-compatible";
  convert(src,dst,1,50,2,false,90,false,true);
  auto a=map(src,true),b=map(dst,false);
  check(b.matrix==6 && b.fullRange==(multi?0:1),"HEIC map matrix/range matches its actual samples");
  check(b.w==(orientation>=5?a.h:a.w) && b.h==(orientation>=5?a.w:a.h),"compatible HEIC retains map canvas and orientation");
  sameMetadata(a,b);
  if(!multi) { b.multi=false; equal(a,b,orientation); }
  else {
   for(int c=0;c<3;c++) {
    double before=0,after=0;
    for(size_t i=c;i<a.rgb.size();i+=3){before+=a.rgb[i];after+=b.rgb[i];}
    check(std::abs(before-after)/(a.w*a.h)<3,"compatible RGB map keeps channel averages");
   }
  }
 }
 // Equal gain bounds still permit different RGB samples in a compatible HEIC.
 {
  const auto src=root+"/shared-boost.jpg", dst=root+"/shared-boost.heic";
  fixture(src,true,1,41,25,163,99,false,true);
  convert(src,dst,1,100,2,false,90,false,true);
  const auto expected=map(dst,false);
  ultraslim::PreviewGainmapData preview;
  int fd=open(dst.c_str(),O_RDONLY);
  check(fd>=0 && ultraslim::decodePreviewGainmap(fd,256,128u*1024*1024,preview),"decode color gain map for preview");
  close(fd);
  check(!preview.singleChannel && preview.pixels.size()==size_t(expected.w)*expected.h*4,"keep RGB preview channels");
  for(size_t i=0;i<expected.rgb.size();i++)
   check(preview.pixels[(i/3)*4+i%3]==expected.rgb[i],"match decoded HEIC gain map samples");
 }
 // Switching compatibility must never reuse a cached strict map, or vice versa.
 for(bool multi:{false,true}) {
  auto src=root+"/original-"+std::to_string(multi)+"-1.jpg",dst=src+"-heic-retry";
  convert(src,dst,1,100,2,true,90,false,true);
  convert(src,dst+"-expected",1,100,2,false,85,false,true);
  check(readFile(dst)==readFile(dst+"-expected"),"HEIC compatible retry matches independent encode");
  auto strict=dst+"-strict";convert(src,strict,1);equal(map(src,true),map(strict,false));
 }
 // A camera-size map must take the same strictly lossless path as a thumbnail.
 for(bool multi:{false,true}) {
  auto src=root+"/large-"+std::to_string(multi)+".jpg";fixture(src,multi,1,801,625);auto original=map(src,true);
  for(int codec:{1,2}) {
   auto dst=src+"-"+std::to_string(codec);convert(src,dst,codec);equal(original,map(dst,false));
   const std::string packet="<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">motion-test</x:xmpmeta>";
   std::vector<uint8_t> xmp(packet.begin(),packet.end());
   int a=open(src.c_str(),O_RDONLY),b=open((dst+"-xmp").c_str(),O_RDWR|O_CREAT|O_TRUNC,0600);
   ultraslim::TranscodeOptions o;o.outCodec=codec;o.codecThreads=2;o.xmp=xmp;
   check(ultraslim::transcodeImage(a,b,o).errorCode==0,"encode with XMP");
   check(ultraslim::readHeifXmp(b)==xmp,"XMP roundtrip");close(a);close(b);
   equal(original,map(dst+"-xmp",false));
  }
  const auto jxl=src+".jxl",round=jxl+".heic";
  convert(src,jxl,3);convert(jxl,round,1);equal(original,map(round,false));
 }
 // Speed presets and quality retry must preserve every gain-map sample.
 for (bool multi : {false, true}) {
  const auto src = root + "/large-" + std::to_string(multi) + ".jpg";
  const auto original = map(src, true);
  for (int codec : {1, 2, 3}) {
   const auto dst = src + "-retry-" + std::to_string(codec);
   convert(src, dst + "-first", codec, 100, 2, false, 90);
   check(readFile(dst + "-first").size() >= readFile(src).size(), "fixture triggers retry");
   convert(src, dst, codec, 100, 2, true, 90);
   convert(src, dst + "-expected", codec, 100, 2, false, 85);
   check(readFile(dst) == readFile(dst + "-expected"), "cached retry equals independent lower-quality encoding");
   if (codec == 3) {
    convert(dst, dst + "-heic", 1); equal(original, map(dst + "-heic", false));
   } else equal(original, map(dst, false));
  }
 }
 // Retry must reuse the compatible YCbCr map, never a stale Identity map.
 {
  const auto src=root+"/large-1.jpg",dst=src+"-compat-retry";
  convert(src,dst+"-first",2,100,2,false,90,true);
  check(readFile(dst+"-first").size()>=readFile(src).size(),"compatibility fixture triggers retry");
  convert(src,dst,2,100,2,true,90,true);
  convert(src,dst+"-expected",2,100,2,false,85,true);
  check(readFile(dst)==readFile(dst+"-expected"),"compatible cached retry equals independent encoding");
  equal(map(src,true),map(dst,false),1,3);
 }
 // Tiles must form the exact canvas, including partial right/bottom tiles.
 // A retry must not substitute the first tile for every other tile.
 for(bool multi : {false,true}) for(int orientation : {1,6}) {
  const auto src=root+"/grid-"+std::to_string(multi)+"-"+std::to_string(orientation)+".jpg";
  fixture(src,multi,orientation,513,769);
  const auto original=map(src,true);
  for(bool compatible : {false,true}) {
   const auto dst=src+(compatible?"-compatible.avif":"-strict.avif");
   convert(src,dst,2,100,2,true,90,compatible);
   equal(original,map(dst,false),orientation,compatible&&multi?3:0);
   auto*ctx=heif_context_alloc();heif_image_handle *base=nullptr,*gm=nullptr;
   check(heif_context_read_from_file(ctx,dst.c_str(),nullptr).code==heif_error_Ok,"grid container");
   heif_context_get_primary_image_handle(ctx,&base);
   check(heif_image_handle_get_gain_map_image_handle(base,&gm).code==heif_error_Ok,"grid map");
   heif_image_tiling tiles{};
   check(heif_image_handle_get_image_tiling(gm,0,&tiles).code==heif_error_Ok,"grid tiling");
   check(tiles.num_columns==2&&tiles.num_rows==2&&tiles.tile_width==512&&tiles.tile_height==512,"bounded map tiles");
   heif_image_handle_release(gm);heif_image_handle_release(base);heif_context_free(ctx);
  }
 }
 // HEVC primary grids must retain exact dimensions and HDR association too.
 for(int width:{5000,5001}) {
  auto src=root+"/heic-grid-"+std::to_string(width)+".jpg",dst=src+".heic";
  fixture(src,false,1,513,769,width,514);
  convert(src,dst,1,100,2,false,92,false,true);
  auto original=map(src,true),result=map(dst,false);result.multi=false;equal(original,result);
  int fd=open(dst.c_str(),O_RDONLY);auto probe=ultraslim::probeImage(fd);close(fd);
  check(probe.errorCode==0 && probe.width==width && probe.height==514 && probe.hasGainMap,"HEIC grid retains dimensions and HDR");
  auto*ctx=heif_context_alloc();heif_image_handle*base=nullptr;
  check(heif_context_read_from_file(ctx,dst.c_str(),nullptr).code==heif_error_Ok,"HEIC grid read");
  heif_context_get_primary_image_handle(ctx,&base);heif_image_tiling tiles{};
  check(heif_image_handle_get_image_tiling(base,0,&tiles).code==heif_error_Ok && tiles.num_columns==3 && tiles.tile_width==2048,"HEIC large primary uses bounded cells");
  heif_image_handle_release(base);heif_context_free(ctx);
 }
 // Both primary and gain map use grids; odd primary dimensions require clap.
 {
  const auto src=root+"/base-grid.jpg",dst=root+"/base-grid.avif";
  fixture(src,true,1,513,769,5001,513);
  convert(src,dst,2,100,2,false,90,true);
  equal(map(src,true),map(dst,false),1,3);
  int fd=open(dst.c_str(),O_RDONLY);auto probe=ultraslim::probeImage(fd);close(fd);
  check(probe.errorCode==0&&probe.width==5001&&probe.height==513&&probe.hasGainMap,"odd grid canvas retains dimensions and HDR");
 }
 puts("PASS: mono/RGB maps, all 8 orientations, HEIC/AVIF cross-codec roundtrip, main resize, exact JPEG map bytes, exact map pixels and HDR metadata");
}
