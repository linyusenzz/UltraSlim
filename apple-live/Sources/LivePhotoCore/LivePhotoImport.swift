import Foundation
import AppKit
import AVFoundation
import Photos
import CoreMedia

public struct PreparedLivePhoto: Sendable {
    public let directory: URL
    public let photo: URL
    public let video: URL
    public let usedEstimatedTimestamp: Bool
    public func removeTemporaryFiles() { try? FileManager.default.removeItem(at:directory) }
}
public enum LivePhotoImport {
    struct Manifest: Decodable { let version: Int; let stillFile: String; let videoFile: String; let presentationTimestampUs: Int64 }
    // Extract only fixed, validated names, never archive-supplied paths/symlinks.
    static func extract(_ entry: String, archive: URL, to output: URL, limit: Int) throws {
        let process=Process(); process.executableURL=URL(fileURLWithPath:"/usr/bin/unzip"); process.arguments=["-p",archive.path,entry]
        let pipe=Pipe(); process.standardOutput=pipe; process.standardError=FileHandle.nullDevice
        FileManager.default.createFile(atPath:output.path,contents:nil)
        let file=try FileHandle(forWritingTo:output); defer { try? file.close() }
        try process.run()
        defer { if process.isRunning { process.terminate() }; try? pipe.fileHandleForReading.close() }
        var size=0
        while let chunk=try pipe.fileHandleForReading.read(upToCount:128*1024), !chunk.isEmpty {
            try Task.checkCancellation(); size += chunk.count
            guard size<=limit else { throw LiveError("导入包中的 \(entry) 超过大小限制") }; try file.write(contentsOf:chunk)
        }
        process.waitUntilExit()
        guard process.terminationStatus==0,size>0 else { throw LiveError("无法读取 \(entry)，请重新导出完整 ZIP") }
    }
    public static func prepare(bundle: URL) async throws -> PreparedLivePhoto {
        let dir=FileManager.default.temporaryDirectory.appendingPathComponent("UltraSlimLive-"+UUID().uuidString,isDirectory:true)
        try FileManager.default.createDirectory(at:dir,withIntermediateDirectories:true)
        do {
            let manifestURL=dir.appendingPathComponent("manifest.json")
            try extract("manifest.json",archive:bundle,to:manifestURL,limit:65536)
            let m=try JSONDecoder().decode(Manifest.self,from:Data(contentsOf:manifestURL))
            guard m.version==1,m.stillFile=="photo.heic",m.videoFile=="video.mp4",m.presentationTimestampUs>=(-1) else { throw LiveError("不支持此导入包版本或内容") }
            let still=dir.appendingPathComponent("input.heic"), video=dir.appendingPathComponent("input.mp4")
            try extract(m.stillFile,archive:bundle,to:still,limit:512*1024*1024)
            try extract(m.videoFile,archive:bundle,to:video,limit:1024*1024*1024)
            let id=UUID().uuidString, photoOut=dir.appendingPathComponent("LivePhoto.HEIC"), videoOut=dir.appendingPathComponent("LivePhoto.MOV")
            let timestampUs=m.presentationTimestampUs
            try Task.checkCancellation()
            try await withThrowingTaskGroup(of: Void.self) { group in
                group.addTask { try HEICPairing.write(source:still,destination:photoOut,identifier:id) }
                group.addTask { try await pairVideo(source:video,destination:videoOut,identifier:id,timestampUs:timestampUs) }
                try await group.waitForAll()
            }
            return PreparedLivePhoto(directory:dir,photo:photoOut,video:videoOut,usedEstimatedTimestamp:timestampUs<0)
        } catch { try? FileManager.default.removeItem(at:dir); throw error }
    }
    // MotionPhotoPresentationTimestampUs is a presentation timestamp, not an
    // offset to add to a track's nonzero start time. Clamp end-of-clip timestamps
    // within 100ms tolerance so shutter-at-end clips are not rejected.
    static func photoTime(timestampUs: Int64, start: Double, duration: Double) throws -> Double {
        guard start.isFinite,start>=0,duration.isFinite,duration>0 else { throw LiveError("动态视频时间范围无效") }
        let time=timestampUs<0 ? start+duration/2:Double(timestampUs)/1_000_000
        let end=start+duration
        guard time>=start,time<=end+0.1 else { throw LiveError("照片时间点超出视频范围，未导入") }
        return time<end ? time : max(start, end - 1.0 / 600.0)
    }
    static func pairVideo(source: URL, destination: URL, identifier: String, timestampUs: Int64) async throws {
        let asset=AVURLAsset(url:source)
        guard let video=try await asset.loadTracks(withMediaType:.video).first else { throw LiveError("动态视频没有视频轨道") }
        let range=try await video.load(.timeRange)
        let duration=range.duration.seconds
        guard duration.isFinite,duration>0,duration<600 else { throw LiveError("动态视频时长不受支持") }
        let timestamp=try photoTime(timestampUs:timestampUs,start:range.start.seconds,duration:duration)
        let descriptions=try await video.load(.formatDescriptions)
        guard let format=descriptions.first,[kCMVideoCodecType_H264,kCMVideoCodecType_HEVC].contains(CMFormatDescriptionGetMediaSubType(format)) else { throw LiveError("视频需要 H.264 或 HEVC 编码；不会静默丢弃视频") }
        let reader=try AVAssetReader(asset:asset), writer=try AVAssetWriter(outputURL:destination,fileType:.mov)
        let identity=AVMutableMetadataItem(); identity.keySpace = .quickTimeMetadata; identity.key="com.apple.quicktime.content.identifier" as NSString; identity.value=identifier as NSString; identity.dataType=kCMMetadataBaseDataType_UTF8 as String
        writer.metadata=[identity]
        var io:[(AVAssetReaderTrackOutput,AVAssetWriterInput)]=[]
        let audio=try await asset.loadTracks(withMediaType:.audio)
        for track in [video]+audio {
            let desc=try await track.load(.formatDescriptions)
            let output=AVAssetReaderTrackOutput(track:track,outputSettings:nil); output.alwaysCopiesSampleData=false
            let input=AVAssetWriterInput(mediaType:track.mediaType,outputSettings:nil,sourceFormatHint:desc.first); input.expectsMediaDataInRealTime=false
            if track.mediaType == .video { input.transform=try await track.load(.preferredTransform) }
            guard reader.canAdd(output),writer.canAdd(input) else { throw LiveError("无法原样封装视频或音频轨道") }
            reader.add(output); writer.add(input); io.append((output,input))
        }
        var description: CMFormatDescription?
        let spec:[String:Any]=[kCMMetadataFormatDescriptionMetadataSpecificationKey_Identifier as String:"mdta/com.apple.quicktime.still-image-time",kCMMetadataFormatDescriptionMetadataSpecificationKey_DataType as String:kCMMetadataBaseDataType_SInt8]
        guard CMMetadataFormatDescriptionCreateWithMetadataSpecifications(allocator:kCFAllocatorDefault,metadataType:kCMMetadataFormatType_Boxed,metadataSpecifications:[spec] as CFArray,formatDescriptionOut:&description)==noErr else { throw LiveError("无法建立 Live Photo 时间轨道") }
        let metadata=AVAssetWriterInput(mediaType:.metadata,outputSettings:nil,sourceFormatHint:description)
        let adaptor=AVAssetWriterInputMetadataAdaptor(assetWriterInput:metadata)
        guard writer.canAdd(metadata) else { throw LiveError("视频不支持配对轨道") }; writer.add(metadata)
        guard writer.startWriting(),reader.startReading() else { throw writer.error ?? reader.error ?? LiveError("视频封装启动失败") }
        let sessionStart = range.start.isNumeric && range.start.seconds > 0 ? range.start : .zero
        writer.startSession(atSourceTime:sessionStart)
        do {
            let marker=AVMutableMetadataItem(); marker.keySpace = .quickTimeMetadata; marker.key="com.apple.quicktime.still-image-time" as NSString; marker.value=NSNumber(value:0); marker.dataType=kCMMetadataBaseDataType_SInt8 as String
            let frameDuration=CMTime(value:1,timescale:600)
            let time=CMTime(seconds:timestamp,preferredTimescale:1_000_000)
            guard adaptor.append(AVTimedMetadataGroup(items:[marker],timeRange:CMTimeRange(start:time,duration:frameDuration))) else { throw writer.error ?? LiveError("无法写入照片时间点") }
            metadata.markAsFinished()
            var done=Set<Int>(); let deadline=Date().addingTimeInterval(120)
            while done.count<io.count {
                try Task.checkCancellation()
                guard Date()<deadline,writer.status == .writing else { throw writer.error ?? LiveError("视频封装超时") }
                var progress=false
                for (n,pair) in io.enumerated() where !done.contains(n) {
                    while pair.1.isReadyForMoreMediaData {
                        if let sample=pair.0.copyNextSampleBuffer() {
                            guard pair.1.append(sample) else { throw writer.error ?? LiveError("写入视频失败") }
                            progress=true
                        } else {
                            pair.1.markAsFinished(); done.insert(n); progress=true; break
                        }
                    }
                }
                if !progress { try await Task.sleep(nanoseconds:2_000_000) }
            }
            guard reader.status == .completed else { throw reader.error ?? LiveError("视频读取未完成") }
            await writer.finishWriting()
            guard writer.status == .completed else { throw writer.error ?? LiveError("视频封装未完成") }
        } catch { reader.cancelReading(); writer.cancelWriting(); throw error }
    }
    public static func preview(_ prepared: PreparedLivePhoto) async throws -> PHLivePhoto {
        try await withCheckedThrowingContinuation { continuation in
            PHLivePhoto.request(withResourceFileURLs:[prepared.photo,prepared.video],placeholderImage:nil,targetSize:CGSize(width:1000,height:1000),contentMode:.aspectFit) { photo,info in
                if info[PHLivePhotoInfoIsDegradedKey] as? Bool == true { return }
                if let photo { continuation.resume(returning:photo) }
                else { continuation.resume(throwing:info[PHLivePhotoInfoErrorKey] as? Error ?? LiveError("Apple 未将照片和视频识别为 Live Photo，未导入")) }
            }
        }
    }
    public static func save(_ prepared: PreparedLivePhoto) async throws {
        try await saveAll([prepared])
    }
    public static func saveAll(_ items: [PreparedLivePhoto]) async throws {
        guard !items.isEmpty else { return }
        let auth=await PHPhotoLibrary.requestAuthorization(for:.readWrite)
        guard auth == .authorized || auth == .limited else { throw LiveError("需要允许访问「照片」才能导入。导出包未修改。") }
        try await PHPhotoLibrary.shared().performChanges {
            for prepared in items {
                let request=PHAssetCreationRequest.forAsset()
                request.addResource(with:.photo,fileURL:prepared.photo,options:nil)
                request.addResource(with:.pairedVideo,fileURL:prepared.video,options:nil)
            }
        }
    }
}
