import Foundation
import ImageIO
import CoreGraphics
import UniformTypeIdentifiers

public struct LiveError: LocalizedError {
    public let message: String
    public init(_ message: String) { self.message = message }
    public var errorDescription: String? { message }
}

// Relocate only meta, append a new Exif payload, leave every existing coded image,
// grid, gain map, profile and item reference untouched. No ImageIO re-encoding.
enum HEICPairing {
    struct Box { let start: Int; let end: Int; let header: Int; let type: String; var payload: Int { start + header } }
    static func uint(_ data: Data, _ start: Int, _ count: Int) throws -> UInt64 {
        guard count >= 0, count <= 8, start >= 0, start <= data.count - count else { throw LiveError("HEIC 元数据越界") }
        return data[start..<start+count].reduce(0) { ($0 << 8) | UInt64($1) }
    }
    static func bytes(_ value: UInt64, _ count: Int) -> Data { Data((0..<count).reversed().map { UInt8(truncatingIfNeeded: value >> ($0 * 8)) }) }
    static func box(_ type: String, _ content: Data) -> Data { bytes(UInt64(content.count + 8), 4) + Data(type.utf8) + content }
    static func boxes(_ data: Data, _ start: Int, _ end: Int) throws -> [Box] {
        var result: [Box] = []; var p = start
        while p < end {
            guard end-p >= 8 else { throw LiveError("HEIC box 被截断") }
            var n = try uint(data,p,4); var header = 8
            if n == 1 { n = try uint(data,p+8,8); header = 16 }
            if n == 0 { n = UInt64(end-p) }
            guard n >= header, n <= end-p else { throw LiveError("HEIC box 长度错误") }
            result.append(Box(start:p,end:p+Int(n),header:header,type:String(decoding:data[p+4..<p+8],as:UTF8.self))); p += Int(n)
        }
        return result
    }
    struct Extent { var index: UInt64; var offset: UInt64; var length: UInt64 }
    struct Item { var id: UInt64; var method: UInt64; var reference: UInt64; var base: UInt64; var extents: [Extent] }
    static func locations(_ data: Data, _ b: Box) throws -> [Item] {
        var p=b.payload
        let version = try uint(data,p,1); guard version <= 2 else { throw LiveError("不支持此 HEIC iloc 版本") }; p += 4
        let sizes=try uint(data,p,1), baseIndex=try uint(data,p+1,1); p += 2
        let os=Int(sizes>>4), ls=Int(sizes&15), bs=Int(baseIndex>>4), ix=version>0 ? Int(baseIndex&15):0
        func read(_ n: Int) throws -> UInt64 { defer { p += n }; guard p+n<=b.end else { throw LiveError("HEIC iloc 被截断") }; return try uint(data,p,n) }
        let count=try read(version<2 ? 2:4); guard count <= 100000 else { throw LiveError("HEIC item 过多") }
        var result: [Item]=[]
        for _ in 0..<count {
            let id=try read(version<2 ? 2:4), method=version>0 ? try read(2)&15:0, ref=try read(2), base=try read(bs), n=try read(2)
            var extents: [Extent]=[]
            for _ in 0..<n { extents.append(Extent(index:try read(ix),offset:try read(os),length:try read(ls))) }
            result.append(Item(id:id,method:method,reference:ref,base:base,extents:extents))
        }
        guard p==b.end else { throw LiveError("HEIC iloc 有未知数据") }; return result
    }
    static func encodeLocations(_ items: [Item]) -> Data {
        var d=Data([1,0,0,0,0x44,0x40])+bytes(UInt64(items.count),2)
        for i in items { d += bytes(i.id,2)+bytes(i.method,2)+bytes(i.reference,2)+bytes(i.base,4)+bytes(UInt64(i.extents.count),2)
            for e in i.extents { d += bytes(e.offset,4)+bytes(e.length,4) }
        }
        return box("iloc",d)
    }
    static func exifPayload(source: CGImageSource, identifier: String) throws -> Data {
        var properties = (CGImageSourceCopyPropertiesAtIndex(source,0,nil) as? [String:Any]) ?? [:]
        // ImageIO is only used to serialize EXIF on a disposable 1x1 image.
        var maker = properties[kCGImagePropertyMakerAppleDictionary as String] as? [String:Any] ?? [:]
        maker["17"]=identifier; properties[kCGImagePropertyMakerAppleDictionary as String]=maker
        let pixel=[UInt8](repeating:0,count:4)
        let provider=CGDataProvider(data:Data(pixel) as CFData)!
        let image=CGImage(width:1,height:1,bitsPerComponent:8,bitsPerPixel:32,bytesPerRow:4,space:CGColorSpaceCreateDeviceRGB(),bitmapInfo:CGBitmapInfo(rawValue:CGImageAlphaInfo.noneSkipLast.rawValue),provider:provider,decode:nil,shouldInterpolate:false,intent:.defaultIntent)!
        let buffer=NSMutableData()
        guard let dest=CGImageDestinationCreateWithData(buffer,UTType.jpeg.identifier as CFString,1,nil) else { throw LiveError("无法建立配对元数据") }
        CGImageDestinationAddImage(dest,image,properties as CFDictionary)
        guard CGImageDestinationFinalize(dest) else { throw LiveError("无法写入配对元数据") }
        let jpeg=buffer as Data; var p=2
        while p+4<=jpeg.count {
            guard jpeg[p]==255 else { break }; let marker=jpeg[p+1]; if marker==0xda || marker==0xd9 { break }
            let n=Int(try uint(jpeg,p+2,2)); guard n>=2,p+2+n<=jpeg.count else { break }
            if marker==0xe1, n>=8, jpeg[p+4..<p+10]==Data([69,120,105,102,0,0]) { var tiff=Data(jpeg[p+10..<p+2+n])
                try restoreDimensions(&tiff,width:properties[kCGImagePropertyPixelWidth as String] as? Int ?? 0,height:properties[kCGImagePropertyPixelHeight as String] as? Int ?? 0)
                return Data([0,0,0,0])+tiff }
            p += n+2
        }
        throw LiveError("未生成 EXIF 配对信息")
    }
    // The disposable JPEG serializer stamps 1x1 into ExifIFD. Restore the real
    // dimensions without changing any TIFF/MakerNote offsets.
    static func restoreDimensions(_ tiff: inout Data, width: Int, height: Int) throws {
        guard tiff.count>=8, width>0,height>0 else { throw LiveError("照片尺寸无效") }
        let little=tiff[0]==73 && tiff[1]==73
        guard little || (tiff[0]==77 && tiff[1]==77) else { throw LiveError("EXIF 字节序无效") }
        func read(_ offset:Int,_ size:Int) throws -> UInt64 {
            guard offset>=0,offset+size<=tiff.count else { throw LiveError("EXIF 数据不完整") }
            let d=Data(tiff[offset..<offset+size]); return try uint(little ? Data(d.reversed()):d,0,size)
        }
        func entries(_ offset:Int) throws -> [Int] {
            let count=Int(try read(offset,2)); guard offset+2+count*12+4<=tiff.count else { throw LiveError("EXIF 目录不完整") }
            return (0..<count).map { offset+2+$0*12 }
        }
        let first=Int(try read(4,4))
        for entry in try entries(first) where try read(entry,2)==0x8769 {
            let exif=Int(try read(entry+8,4))
            for field in try entries(exif) {
                let tag=try read(field,2),type=try read(field+2,2),count=try read(field+4,4)
                if tag==0xa002 || tag==0xa003 {
                    guard count==1,type==3 || type==4 else { throw LiveError("EXIF 尺寸字段不受支持") }
                    let size=type==3 ? 2:4, value=tag==0xa002 ? width:height
                    guard size==4 || value<=65535 else { throw LiveError("EXIF 尺寸超出范围") }
                    let encoded=bytes(UInt64(value),size)
                    tiff.replaceSubrange(field+8..<field+8+size,with:little ? Data(encoded.reversed()):encoded)
                }
            }
        }
    }
    static func write(source: URL, destination: URL, identifier: String) throws {
        let original=try Data(contentsOf:source,options:.mappedIfSafe)
        guard original.count < Int(UInt32.max)-1024*1024 else { throw LiveError("照片超过配对大小限制") }
        guard let image=CGImageSourceCreateWithURL(source as CFURL,nil), CGImageSourceGetType(image) as String? == UTType.heic.identifier else { throw LiveError("导入包需要 HEIC 照片") }
        let top=try boxes(original,0,original.count)
        guard let meta=top.first(where:{$0.type=="meta"}), top.filter({$0.type=="meta"}).count==1 else { throw LiveError("未找到唯一 HEIC meta") }
        let children=try boxes(original,meta.payload+4,meta.end)
        guard let iloc=children.first(where:{$0.type=="iloc"}),let iinf=children.first(where:{$0.type=="iinf"}),let pitm=children.first(where:{$0.type=="pitm"}) else { throw LiveError("HEIC 缺少 item 信息") }
        guard pitm.payload+6<=pitm.end, iloc.payload+8<=iloc.end, iinf.payload+6<=iinf.end else { throw LiveError("HEIC 元数据不完整") }
        let primary=try uint(original,pitm.payload+4,original[pitm.payload]==0 ? 2:4)
        guard primary<UInt16.max else { throw LiveError("主图 ID 不受支持") }
        var entries=try locations(original,iloc)
        let infos=try boxes(original,iinf.payload+(original[iinf.payload]==0 ? 6:8),iinf.end)
        var exifIDs:[UInt64]=[]
        for b in infos where b.type=="infe" {
            guard b.payload+4<=b.end else { throw LiveError("HEIC item 描述被截断") }
            let version=original[b.payload]; guard version==2 || version==3 else { throw LiveError("不支持旧版 HEIC item 描述") }
            let idSize=version==2 ? 2:4; let typePos=b.payload+4+idSize+2
            guard typePos+4<=b.end else { throw LiveError("HEIC item 描述被截断") }
            if String(decoding:original[typePos..<typePos+4],as:UTF8.self)=="Exif" { exifIDs.append(try uint(original,b.payload+4,idSize)) }
        }
        guard exifIDs.count<=1 else { throw LiveError("多份 EXIF 的 HEIC 暂不支持配对，原文件未修改") }
        let newItem=exifIDs.isEmpty
        let exifID=exifIDs.first ?? ((entries.map(\.id).max() ?? 0)+1)
        guard exifID<UInt16.max, entries.count<UInt16.max, entries.allSatisfy({$0.id<UInt16.max && $0.base<UInt32.max && $0.extents.allSatisfy({$0.index==0 && $0.offset<UInt32.max && $0.length<UInt32.max})}) else { throw LiveError("HEIC item ID 超出范围") }
        let payload=try exifPayload(source:image,identifier:identifier)
        let replacement=Item(id:exifID,method:0,reference:0,base:0,extents:[Extent(index:0,offset:UInt64(original.count+8),length:UInt64(payload.count))])
        entries.removeAll{$0.id==exifID}; entries.append(replacement)
        var infoData=Data([0,0,0,0])+bytes(UInt64(infos.count+(newItem ? 1:0)),2)
        for b in infos { infoData += original[b.start..<b.end] }
        if newItem { infoData += box("infe",Data([2,0,0,0])+bytes(exifID,2)+Data([0,0])+Data("Exif\0".utf8)) }
        var refData=Data([0,0,0,0]); var hasReference=false
        if let refs=children.first(where:{$0.type=="iref"}) {
            guard refs.payload+4<=refs.end, original[refs.payload]<=1 else { throw LiveError("不支持此 HEIC 引用版本") }
            let idSize=original[refs.payload]==0 ? 2:4
            for b in try boxes(original,refs.payload+4,refs.end) {
                let from=try uint(original,b.payload,idSize), count=try uint(original,b.payload+idSize,2)
                guard b.payload+idSize+2+Int(count)*idSize==b.end else { throw LiveError("HEIC 引用被截断") }
                guard from<UInt16.max else { throw LiveError("引用超出范围") }
                var targets=Data()
                for j in 0..<Int(count) { let to=try uint(original,b.payload+idSize+2+j*idSize,idSize); guard to<UInt16.max else { throw LiveError("引用超出范围") }; targets += bytes(to,2); if b.type=="cdsc" && from==exifID && to==primary { hasReference=true } }
                refData += box(b.type,bytes(from,2)+bytes(count,2)+targets)
            }
        }
        if !hasReference { refData += box("cdsc",bytes(exifID,2)+bytes(1,2)+bytes(primary,2)) }
        var newMeta=Data(original[meta.payload..<meta.payload+4])
        for b in children {
            switch b.type {
            case "iloc": newMeta += encodeLocations(entries)
            case "iinf": newMeta += box("iinf",infoData)
            case "iref": newMeta += box("iref",refData)
            default: newMeta += original[b.start..<b.end]
            }
        }
        if !children.contains(where:{$0.type=="iref"}) { newMeta += box("iref",refData) }
        let trailing = box("mdat", payload) + box("meta", newMeta)
        let dir = destination.deletingLastPathComponent()
        let temp = dir.appendingPathComponent(".ultraslim-live-\(UUID().uuidString).tmp")
        FileManager.default.createFile(atPath: temp.path, contents: nil)
        do {
            let handle = try FileHandle(forWritingTo: temp)
            do {
                try handle.write(contentsOf: original[0..<meta.start + 4])
                try handle.write(contentsOf: Data("free".utf8))
                try handle.write(contentsOf: original[meta.start + 8..<original.count])
                try handle.write(contentsOf: trailing)
                try handle.synchronize()
                try handle.close()
            } catch {
                try? handle.close()
                throw error
            }
            if FileManager.default.fileExists(atPath: destination.path) {
                _ = try FileManager.default.replaceItemAt(destination, withItemAt: temp)
            } else {
                try FileManager.default.moveItem(at: temp, to: destination)
            }
        } catch {
            try? FileManager.default.removeItem(at: temp)
            throw error
        }
        guard let check=CGImageSourceCreateWithURL(destination as CFURL,nil),let props=CGImageSourceCopyPropertiesAtIndex(check,0,nil) as? [String:Any],let maker=props[kCGImagePropertyMakerAppleDictionary as String] as? [String:Any],maker["17"] as? String==identifier else { throw LiveError("Apple 未识别配对标识，未导入相册") }
        for type in [kCGImageAuxiliaryDataTypeISOGainMap,kCGImageAuxiliaryDataTypeHDRGainMap] {
            if CGImageSourceCopyAuxiliaryDataInfoAtIndex(image,0,type) != nil && CGImageSourceCopyAuxiliaryDataInfoAtIndex(check,0,type)==nil { throw LiveError("配对后增益图不可读，未导入相册") }
        }
    }
}
