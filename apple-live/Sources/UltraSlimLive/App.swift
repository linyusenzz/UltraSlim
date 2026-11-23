import SwiftUI
import AppKit
import PhotosUI
import UniformTypeIdentifiers
import LivePhotoCore

@main struct UltraSlimLiveApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) var delegate
    var body: some Scene { WindowGroup("UltraSlim Live") { ImportView() }.defaultSize(width:640,height:640) }
}
final class AppDelegate: NSObject, NSApplicationDelegate {
    func applicationDidFinishLaunching(_ notification: Notification) { NSApp.setActivationPolicy(.regular); NSApp.activate(ignoringOtherApps:true) }
    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication)->Bool { true }
}
@MainActor final class ImportModel: ObservableObject {
    @Published var photo: PHLivePhoto?
    @Published var status="在 Android 成品详情中选择「导出 Apple Live Photo」，然后在这里打开或拖入导出的 ZIP（支持多选批量导入）。"
    @Published var busy=false
    @Published var imported=false
    @Published var name=""
    @Published var preparedCount=0
    private var preparedItems: [PreparedLivePhoto]=[]
    private var pendingURLs: [URL]=[]
    deinit { preparedItems.forEach { $0.removeTemporaryFiles() } }
    func open(_ url: URL) { openMany([url]) }
    func openMany(_ urls: [URL]) {
        let zips = urls.filter { $0.pathExtension.lowercased() == "zip" }
        guard !zips.isEmpty else {
            if !busy { status = "请选择导出的 .zip 导入包。" }
            return
        }
        for url in zips where !pendingURLs.contains(url) { pendingURLs.append(url) }
        if !busy { processPendingBatch() }
    }
    private func processPendingBatch(keepingPrepared: Bool = false) {
        guard !pendingURLs.isEmpty else { return }
        busy = true
        if !keepingPrepared {
            photo = nil
            imported = false
            preparedItems.forEach { $0.removeTemporaryFiles() }
            preparedItems.removeAll()
            preparedCount = 0
        }
        let firstName = pendingURLs[0].deletingPathExtension().lastPathComponent
        name = firstName
        status = "正在配对照片与视频，检查 Live Photo…"
        Task {
            var built = preparedItems
            var firstPreview = photo
            var failures: [String] = []
            var processed = 0
            while !pendingURLs.isEmpty {
                let batch = pendingURLs
                pendingURLs.removeAll()
                let total = processed + batch.count
                for url in batch {
                    processed += 1
                    if total > 1 { status = "正在批量准备 \(processed)/\(total) 张 Live Photo…" }
                    let access = url.startAccessingSecurityScopedResource()
                    defer { if access { url.stopAccessingSecurityScopedResource() } }
                    do {
                        let next = try await LivePhotoImport.prepare(bundle: url)
                        do {
                            let preview = try await LivePhotoImport.preview(next)
                            if firstPreview == nil { firstPreview = preview }
                            built.append(next)
                        } catch {
                            next.removeTemporaryFiles()
                            throw error
                        }
                    } catch {
                        failures.append("\(url.lastPathComponent)：\(error.localizedDescription)")
                    }
                }
            }
            let estimatedCount = built.filter { $0.usedEstimatedTimestamp }.count
            if built.count > 1 { name = "共 \(built.count) 张 Live Photo" }
            busy = false
            if built.isEmpty {
                status = "无法准备：\(failures.joined(separator: "；"))"
                return
            }
            photo = firstPreview
            preparedItems = built
            preparedCount = built.count
            if built.count == 1 && failures.isEmpty {
                status = estimatedCount > 0
                    ? "已识别为 Live Photo。原图未提供拍摄时间点，使用视频中点；请先播放检查。"
                    : "已识别为 Live Photo。可播放预览，确认后导入「照片」。"
            } else {
                var summary = "已就绪 \(built.count) 张 Live Photo（预览首张），确认后一次导入「照片」。"
                if estimatedCount > 0 { summary += "其中 \(estimatedCount) 张使用视频中点时间。" }
                if !failures.isEmpty { summary += "失败 \(failures.count) 项：\(failures.first!)" }
                status = summary
            }
        }
    }
    func save() {
        guard !preparedItems.isEmpty,!busy,!imported else { return }
        let items = preparedItems
        busy=true; status = items.count == 1 ? "正在导入「照片」…" : "正在批量导入 \(items.count) 张到「照片」…"
        Task {
            defer {
                busy = false
                // New Finder/drop requests may arrive while PhotoKit is saving.
                processPendingBatch(keepingPrepared: !imported)
            }
            do {
                try await LivePhotoImport.saveAll(items)
                imported=true
                status = items.count == 1
                    ? "已导入为 Live Photo。开启 Mac 与 iPhone 的 iCloud 照片后会自动同步。"
                    : "已导入 \(items.count) 张 Live Photo。开启 Mac 与 iPhone 的 iCloud 照片后会自动同步。"
            } catch { status="导入失败：\(error.localizedDescription)" }
        }
    }
}
struct LivePreview: NSViewRepresentable {
    let photo: PHLivePhoto
    func makeNSView(context: Context)->PHLivePhotoView { let view=PHLivePhotoView(); view.contentMode = .aspectFit; view.livePhoto=photo; return view }
    func updateNSView(_ view: PHLivePhotoView,context: Context) { if view.livePhoto !== photo { view.livePhoto=photo } }
}
struct ImportView: View {
    @StateObject private var model=ImportModel()
    var body: some View {
        VStack(alignment:.leading,spacing:18) {
            HStack(spacing:12) {
                Image(systemName:"livephoto").font(.system(size:36)).foregroundStyle(.blue)
                VStack(alignment:.leading) { Text("把动态带进「照片」").font(.title2.bold()); Text("UltraSlim Live · Mac 导入工具").foregroundStyle(.secondary) }
                Spacer()
            }
            ZStack {
                RoundedRectangle(cornerRadius:16).fill(Color.primary.opacity(0.035))
                if let photo=model.photo { LivePreview(photo:photo).padding(8) }
                else { VStack(spacing:12) { Image(systemName:"photo.on.rectangle.angled").font(.system(size:48)).foregroundStyle(.secondary); Text(model.busy ? "正在准备…":"打开或拖入 Android 导出的 ZIP").foregroundStyle(.secondary) } }
            }.frame(minHeight:280,maxHeight:.infinity)
                .onDrop(of: [.fileURL], isTargeted: nil) { providers in
                    Task { @MainActor in
                        var urls: [URL] = []
                        for provider in providers {
                            if let data = try? await provider.loadItem(forTypeIdentifier: UTType.fileURL.identifier, options: nil) as? Data,
                               let url = URL(dataRepresentation: data, relativeTo: nil) {
                                urls.append(url)
                            }
                        }
                        if !urls.isEmpty { model.openMany(urls) }
                    }
                    return true
                }
            if !model.name.isEmpty { Text(model.name).font(.headline).lineLimit(1) }
            Text(model.status).font(.callout).textSelection(.enabled).fixedSize(horizontal:false,vertical:true)
            HStack {
                Button("打开导入包…") { let p=NSOpenPanel(); p.allowedContentTypes=[.zip]; p.allowsMultipleSelection=true; p.canChooseDirectories=false; if p.runModal() == .OK,!p.urls.isEmpty { model.openMany(p.urls) } }.disabled(model.busy)
                if model.photo != nil { Text("按住预览播放").font(.caption).foregroundStyle(.secondary) }
                Spacer()
                if model.busy { ProgressView().controlSize(.small) }
                let importTitle = model.imported ? "已导入" : (model.preparedCount > 1 ? "导入 \(model.preparedCount) 张到「照片」" : "导入「照片」")
                Button(importTitle) { model.save() }.buttonStyle(.borderedProminent).disabled(model.photo==nil || model.busy || model.imported)
            }
            Text("照片与视频只在本机处理。同步到 iPhone 由你的 iCloud 照片设置控制。").font(.caption).foregroundStyle(.secondary)
        }.padding(24).frame(minWidth:580,minHeight:600).onOpenURL { model.open($0) }
    }
}
