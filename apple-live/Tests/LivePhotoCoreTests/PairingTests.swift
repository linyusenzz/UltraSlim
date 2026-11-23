import XCTest
import ImageIO
import CoreGraphics
import UniformTypeIdentifiers
@testable import LivePhotoCore

final class PairingTests: XCTestCase {
    func testPresentationTimestampIsNotOffsetTwice() throws {
        XCTAssertEqual(try LivePhotoImport.photoTime(timestampUs:3_000_000,start:2,duration:4),3)
        XCTAssertEqual(try LivePhotoImport.photoTime(timestampUs:-1,start:2,duration:4),4)
        XCTAssertEqual(try LivePhotoImport.photoTime(timestampUs:6_050_000,start:2,duration:4),6.0 - 1.0 / 600.0,accuracy:1e-6)
        XCTAssertThrowsError(try LivePhotoImport.photoTime(timestampUs:1_000_000,start:2,duration:4))
        XCTAssertThrowsError(try LivePhotoImport.photoTime(timestampUs:6_200_000,start:2,duration:4))
    }
    func testMetadataPairingKeepsCodedItemsForImageWithoutExif() throws {
        let dir=FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at:dir,withIntermediateDirectories:true); defer { try? FileManager.default.removeItem(at:dir) }
        let source=dir.appendingPathComponent("source.heic"),target=dir.appendingPathComponent("paired.heic")
        let context=CGContext(data:nil,width:64,height:48,bitsPerComponent:8,bytesPerRow:0,space:CGColorSpaceCreateDeviceRGB(),bitmapInfo:CGImageAlphaInfo.noneSkipLast.rawValue)!
        context.setFillColor(CGColor(red:0.1,green:0.7,blue:0.3,alpha:1)); context.fill(CGRect(x:0,y:0,width:64,height:48))
        let destination=CGImageDestinationCreateWithURL(source as CFURL,UTType.heic.identifier as CFString,1,nil)!
        CGImageDestinationAddImage(destination,context.makeImage()!,nil); XCTAssertTrue(CGImageDestinationFinalize(destination))
        let before=try Data(contentsOf:source)
        try HEICPairing.write(source:source,destination:target,identifier:UUID().uuidString)
        let after=try Data(contentsOf:target)
        for b in try HEICPairing.boxes(before,0,before.count) where b.type != "meta" { XCTAssertEqual(before[b.start..<b.end],after[b.start..<b.end]) }
        XCTAssertEqual(before,try Data(contentsOf:source))
        let tagged=CGImageSourceCreateWithURL(target as CFURL,nil)!
        XCTAssertNotNil(CGImageSourceCreateImageAtIndex(tagged,0,nil))
        let properties=CGImageSourceCopyPropertiesAtIndex(tagged,0,nil) as! [String:Any]
        let exif=properties[kCGImagePropertyExifDictionary as String] as! [String:Any]
        XCTAssertEqual(exif[kCGImagePropertyExifPixelXDimension as String] as? Int,64)
        XCTAssertEqual(exif[kCGImagePropertyExifPixelYDimension as String] as? Int,48)
        let topAfter=try HEICPairing.boxes(after,0,after.count)
        let metaAfter=try XCTUnwrap(topAfter.last(where:{$0.type=="meta"}))
        let iinfAfter=try XCTUnwrap(try HEICPairing.boxes(after,metaAfter.payload+4,metaAfter.end).first(where:{$0.type=="iinf"}))
        let infes=try HEICPairing.boxes(after,iinfAfter.payload+(after[iinfAfter.payload]==0 ? 6:8),iinfAfter.end)
        let exifInfe=try XCTUnwrap(infes.first(where:{ b in b.payload+8+4<=b.end && String(decoding:after[b.payload+8..<b.payload+12],as:UTF8.self)=="Exif" }))
        XCTAssertEqual(exifInfe.end - exifInfe.start, 21, "ISO 14496-12 v2 Exif infe box must be 21 bytes without trailing duplicate name")
    }
    /** App 产出的 HEIC 常态自带 Exif item：配对走「替换 extents + 保留 cdsc」分支。 */
    func testMetadataPairingReplacesExistingExifItemWithoutReencode() throws {
        let dir=FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at:dir,withIntermediateDirectories:true); defer { try? FileManager.default.removeItem(at:dir) }
        let source=dir.appendingPathComponent("source.heic"),target=dir.appendingPathComponent("paired.heic")
        let context=CGContext(data:nil,width:64,height:48,bitsPerComponent:8,bytesPerRow:0,space:CGColorSpaceCreateDeviceRGB(),bitmapInfo:CGImageAlphaInfo.noneSkipLast.rawValue)!
        context.setFillColor(CGColor(red:0.4,green:0.2,blue:0.8,alpha:1)); context.fill(CGRect(x:0,y:0,width:64,height:48))
        let destination=CGImageDestinationCreateWithURL(source as CFURL,UTType.heic.identifier as CFString,1,nil)!
        let properties:[String:Any]=[kCGImagePropertyExifDictionary as String:[kCGImagePropertyExifUserComment as String:"ultraslim-pairing-regression"]]
        CGImageDestinationAddImage(destination,context.makeImage()!,properties as CFDictionary); XCTAssertTrue(CGImageDestinationFinalize(destination))
        // 前置确认：源文件确实携带 Exif item，否则测的不是 keepExif 分支。
        let sourceImage=CGImageSourceCreateWithURL(source as CFURL,nil)!
        let sourceProps=CGImageSourceCopyPropertiesAtIndex(sourceImage,0,nil) as! [String:Any]
        XCTAssertNotNil(sourceProps[kCGImagePropertyExifDictionary as String])
        let before=try Data(contentsOf:source)
        // 生产路径的标识符是 UUID（36 字符）；短字符串会被 ImageIO 用 '.' 填充到 32 字节，
        // write() 内部的逐字校验会因此拒绝，所以这里必须用与 App 相同形态的标识符。
        let identifier=UUID().uuidString
        try HEICPairing.write(source:source,destination:target,identifier:identifier)
        let after=try Data(contentsOf:target)
        for b in try HEICPairing.boxes(before,0,before.count) where b.type != "meta" { XCTAssertEqual(before[b.start..<b.end],after[b.start..<b.end]) }
        XCTAssertEqual(before,try Data(contentsOf:source))
        let tagged=CGImageSourceCreateWithURL(target as CFURL,nil)!
        XCTAssertNotNil(CGImageSourceCreateImageAtIndex(tagged,0,nil))
        let props=CGImageSourceCopyPropertiesAtIndex(tagged,0,nil) as! [String:Any]
        let exif=props[kCGImagePropertyExifDictionary as String] as! [String:Any]
        XCTAssertEqual(exif[kCGImagePropertyExifUserComment as String] as? String,"ultraslim-pairing-regression")
        XCTAssertEqual(exif[kCGImagePropertyExifPixelXDimension as String] as? Int,64)
        XCTAssertEqual(exif[kCGImagePropertyExifPixelYDimension as String] as? Int,48)
        XCTAssertEqual((props[kCGImagePropertyMakerAppleDictionary as String] as? [String:Any])?["17"] as? String,identifier)
    }
    func testMalformedBoxesAndIlocFailWithoutOutOfBounds() throws {
        for n in 0..<7 { XCTAssertThrowsError(try HEICPairing.boxes(Data(repeating:0,count:n+1),0,n+1)) }
        XCTAssertThrowsError(try HEICPairing.boxes(Data([255,255,255,255,109,101,116,97]),0,8))
        XCTAssertThrowsError(try HEICPairing.uint(Data([0]),0,16))
        XCTAssertThrowsError(try HEICPairing.locations(Data([1,0,0,0,0x44,0x40,0,1]),HEICPairing.Box(start:0,end:8,header:0,type:"iloc")))
    }
    func testExistingHDRFixtureIsNotReencoded() throws {
        guard let path=ProcessInfo.processInfo.environment["ULTRASLIM_LIVE_HEIC_FIXTURE"] else { throw XCTSkip("Set ULTRASLIM_LIVE_HEIC_FIXTURE for real HDR fixture") }
        let source=URL(fileURLWithPath:path), target=FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString+".heic")
        defer { try? FileManager.default.removeItem(at:target) }
        try HEICPairing.write(source:source,destination:target,identifier:UUID().uuidString)
        let before=try Data(contentsOf:source),after=try Data(contentsOf:target)
        for b in try HEICPairing.boxes(before,0,before.count) where b.type != "meta" { XCTAssertEqual(before[b.start..<b.end],after[b.start..<b.end]) }
        let s=CGImageSourceCreateWithURL(source as CFURL,nil)!,t=CGImageSourceCreateWithURL(target as CFURL,nil)!
        let gm=CGImageSourceCopyAuxiliaryDataInfoAtIndex(s,0,kCGImageAuxiliaryDataTypeISOGainMap) as? [String:Any]
        let next=CGImageSourceCopyAuxiliaryDataInfoAtIndex(t,0,kCGImageAuxiliaryDataTypeISOGainMap) as? [String:Any]
        XCTAssertNotNil(gm); XCTAssertEqual(gm?[kCGImageAuxiliaryDataInfoData as String] as? Data,next?[kCGImageAuxiliaryDataInfoData as String] as? Data)
    }
}
