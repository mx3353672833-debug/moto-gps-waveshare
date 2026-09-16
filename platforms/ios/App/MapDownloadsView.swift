import Combine
import Foundation
import MapKit
import MotoNavigationCore
import SwiftUI

struct SurroundingMapStatusRow: View {
    @ObservedObject var store: SurroundingMapStore

    var body: some View {
        LabeledContent {
            Text(store.statusText).foregroundStyle(.secondary).multilineTextAlignment(.trailing)
        } label: { Label("周边地图", systemImage: "map") }
    }
}

struct MapCity: Decodable, Identifiable, Hashable {
    let id: String
    let name: String
    let detail: String
    let boundsWGS84: [Double]

    enum CodingKeys: String, CodingKey {
        case id, name, detail
        case boundsWGS84 = "bounds_wgs84"
    }
}

@MainActor
final class MapCitySearch: ObservableObject {
    @Published var query = ""
    @Published private(set) var cities: [MapCity] = []
    @Published private(set) var isSearching = false
    @Published private(set) var errorMessage: String?
    private let baseURL: URL
    private var task: Task<Void, Never>?
    private var generation = 0

    init(baseURL: URL) { self.baseURL = baseURL }
    deinit { task?.cancel() }

    func search() {
        task?.cancel()
        generation += 1
        let current = generation
        cities = []
        errorMessage = nil
        let keywords = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard (2 ... 80).contains(keywords.count) else { isSearching = false; return }
        isSearching = true
        var components = URLComponents(url: baseURL.appendingPathComponent("v1/map/cities"), resolvingAgainstBaseURL: false)!
        components.queryItems = [URLQueryItem(name: "keywords", value: keywords)]
        let url = components.url!
        task = Task { [weak self] in
            do {
                try await Task.sleep(for: .milliseconds(350))
                var request = URLRequest(url: url)
                request.timeoutInterval = 20
                let (data, response) = try await URLSession.shared.data(for: request)
                try Task.checkCancellation()
                guard let response = response as? HTTPURLResponse,
                      response.statusCode == 200, data.count <= 1_048_576
                else { throw URLError(.badServerResponse) }
                struct Reply: Decodable { let cities: [MapCity] }
                let reply = try JSONDecoder().decode(Reply.self, from: data)
                let valid = reply.cities.prefix(30).filter { city in
                    let b = city.boundsWGS84
                    return b.count == 4 && b.allSatisfy(\.isFinite) && b[0] < b[2] && b[1] < b[3] &&
                        b[0] >= -180 && b[2] <= 180 && b[1] >= -85 && b[3] <= 85
                }
                guard let self, self.generation == current else { return }
                self.cities = Array(valid)
                self.isSearching = false
            } catch {
                guard !Task.isCancelled, let self, self.generation == current else { return }
                self.isSearching = false
                self.errorMessage = "暂时无法搜索城市，请检查网络后重试。"
            }
        }
    }
}

struct MapDownloadsView: View {
    @ObservedObject var store: SurroundingMapStore
    let gatewayBaseURL: URL
    let route: [GCJ02Point]
    let destinationName: String?
    @Environment(\.dismiss) private var dismiss
    @State private var planningRoute = false
    @State private var planningError: String?

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Label {
                        VStack(alignment: .leading, spacing: 5) {
                            Text("自动加载周边地图")
                            Text(store.statusText).font(.subheadline).foregroundStyle(.secondary)
                        }
                    } icon: { Image(systemName: "network").foregroundStyle(.blue) }
                } footer: {
                    Text("骑行时自动加载圆屏周边的道路和建筑。网络不可用时，使用已下载的地图。")
                }
                Section {
                    NavigationLink {
                        MapCitySearchView(store: store, baseURL: gatewayBaseURL)
                    } label: {
                        Label("下载城市地图", systemImage: "building.2.crop.circle")
                    }
                    .accessibilityIdentifier("map-download-city")
                    if route.count >= 2 {
                        Button(action: downloadRoute) {
                            Label {
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(planningRoute ? "正在计算下载范围…" : "下载这条路线周边")
                                    Text(destinationName ?? "当前选择的路线")
                                        .font(.subheadline).foregroundStyle(.secondary)
                                }
                            } icon: { Image(systemName: "arrow.triangle.turn.up.right.diamond") }
                        }
                        .disabled(store.isDownloading || planningRoute)
                        .accessibilityIdentifier("map-download-route")
                    }
                } footer: {
                    Text("跨市骑行可先规划路线，只保存沿途约一公里范围的底图。离线底图不包含实时路况，规划新路线仍需要网络。")
                }
                downloadStatus(store)
                if let planningError {
                    Section { Text(planningError).foregroundStyle(.secondary) }
                }
                Section("已保存") {
                    if store.packs.isEmpty {
                        Text("还没有下载城市或路线地图")
                            .foregroundStyle(.secondary)
                    }
                    ForEach(store.packs) { pack in
                        VStack(alignment: .leading, spacing: 7) {
                            HStack(alignment: .firstTextBaseline) {
                                Text(pack.name).font(.headline)
                                Spacer()
                                Image(systemName: pack.isComplete ? "checkmark.circle.fill" : "pause.circle")
                                    .foregroundStyle(pack.isComplete ? Color.green : Color.secondary)
                            }
                            Text(pack.detail).font(.subheadline).foregroundStyle(.secondary)
                            Text("\(ByteCountFormatter.string(fromByteCount: pack.byteCount, countStyle: .file)) · \(pack.isComplete ? "已下载" : "尚未下载完成")")
                                .font(.footnote).foregroundStyle(.secondary)
                            if !pack.isComplete && !store.isDownloading {
                                Button("继续下载") { store.resume(pack) }.buttonStyle(.borderless)
                            }
                        }
                        .padding(.vertical, 4)
                        .swipeActions {
                            Button("删除", role: .destructive) { store.delete(pack) }
                                .disabled(store.isDownloading)
                        }
                    }
                    Label("济南基础地图 · 随 App 提供", systemImage: "internaldrive")
                        .font(.subheadline).foregroundStyle(.secondary)
                }
                Section {
                    Button("清理自动缓存") { store.clearTemporaryCache() }
                        .disabled(store.isDownloading)
                } footer: {
                    Text("保留手动下载的地图。自动缓存最多 128 MB，手动下载最多 512 MB；城市与路线共用的数据只保存一份。下载中断后，可在 App 内继续。")
                }
                Section {
                    Link("© OpenStreetMap contributors", destination: URL(string: "https://www.openstreetmap.org/copyright")!)
                    Link("Protomaps 地图数据", destination: URL(string: "https://protomaps.com")!)
                } footer: { Text("建筑和道路的完整程度取决于当地地图数据。") }
            }
            .listStyle(.insetGrouped)
            .navigationTitle("地图")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") { dismiss() }.accessibilityIdentifier("map-downloads-done")
                }
            }
            .accessibilityIdentifier("map-downloads-sheet")
        }
    }

    private func downloadRoute() {
        planningRoute = true
        planningError = nil
        let points = route
        Task {
            do {
                let tiles = try await Task.detached(priority: .userInitiated) {
                    try MapTilePlanner.corridorGCJ02(points)
                }.value
                store.download(name: "前往\(destinationName ?? "目的地")", detail: "所选路线及沿途约一公里", tiles: tiles)
            } catch { planningError = error.localizedDescription }
            planningRoute = false
        }
    }
}

private struct MapCitySearchView: View {
    @ObservedObject var store: SurroundingMapStore
    @StateObject private var search: MapCitySearch

    init(store: SurroundingMapStore, baseURL: URL) {
        self.store = store
        _search = StateObject(wrappedValue: MapCitySearch(baseURL: baseURL))
    }

    var body: some View {
        List {
            if search.isSearching {
                HStack { ProgressView(); Text("正在搜索城市…").foregroundStyle(.secondary) }
            } else if let message = search.errorMessage {
                Text(message)
                Button("重试") { search.search() }
            } else if search.cities.isEmpty {
                ContentUnavailableView(
                    search.query.count >= 2 ? "未找到城市" : "添加常用城市",
                    systemImage: "building.2",
                    description: Text("输入城市或区县名称，例如“济南”“上海”“历下区”。")
                )
            } else {
                ForEach(search.cities) { city in
                    NavigationLink {
                        MapCityDownloadView(store: store, city: city)
                    } label: {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(city.name)
                            Text(city.detail).font(.subheadline).foregroundStyle(.secondary)
                        }
                    }
                    .accessibilityIdentifier("map-city-result-\(city.id)")
                }
            }
        }
        .navigationTitle("下载城市地图")
        .navigationBarTitleDisplayMode(.inline)
        .searchable(text: $search.query, prompt: "搜索城市或区县")
        .onChange(of: search.query) { _, _ in search.search() }
    }
}

private struct MapCityDownloadView: View {
    @ObservedObject var store: SurroundingMapStore
    let city: MapCity
    @State private var tiles: [MapTileID] = []
    @State private var errorMessage: String?

    var body: some View {
        List {
            Section {
                Map(initialPosition: .region(region)) {
                    MapPolygon(coordinates: corners)
                        .foregroundStyle(.blue.opacity(0.1))
                        .stroke(.blue, lineWidth: 2)
                }
                .mapStyle(.standard(elevation: .flat, pointsOfInterest: .excludingAll))
                .frame(height: 260)
                .listRowInsets(EdgeInsets())
                .accessibilityLabel("\(city.name)离线地图下载范围")
                LabeledContent("下载区域", value: city.detail)
            } footer: {
                Text("蓝框内的道路和建筑将保存在手机。范围覆盖所选城市或区县，边缘可能包括部分相邻地区。")
            }
            Section {
                if let errorMessage { Text(errorMessage).foregroundStyle(.secondary) }
                Button("下载地图") {
                    store.download(name: city.name, detail: city.detail, tiles: tiles)
                }
                .disabled(tiles.isEmpty || store.isDownloading)
                .accessibilityIdentifier("map-city-start-download")
            } footer: {
                Text("建议连接 Wi-Fi 下载，实际大小会随当地道路和建筑数量变化。范围过大时，可搜索具体区县。")
            }
            downloadStatus(store)
        }
        .navigationTitle(city.name)
        .navigationBarTitleDisplayMode(.inline)
        .task {
            do { tiles = try MapTilePlanner.boundsWGS84(city.boundsWGS84) }
            catch { errorMessage = error.localizedDescription }
        }
    }

    private var region: MKCoordinateRegion {
        let b = city.boundsWGS84
        return MKCoordinateRegion(center: CLLocationCoordinate2D(latitude: (b[1] + b[3]) / 2, longitude: (b[0] + b[2]) / 2),
            span: MKCoordinateSpan(latitudeDelta: (b[3] - b[1]) * 1.15, longitudeDelta: (b[2] - b[0]) * 1.15))
    }

    private var corners: [CLLocationCoordinate2D] {
        let b = city.boundsWGS84
        return [(b[1], b[0]), (b[1], b[2]), (b[3], b[2]), (b[3], b[0])].map {
            CLLocationCoordinate2D(latitude: $0.0, longitude: $0.1)
        }
    }
}

@MainActor @ViewBuilder
private func downloadStatus(_ store: SurroundingMapStore) -> some View {
    if store.isDownloading {
        Section {
            ProgressView(value: store.downloadProgress)
                .accessibilityLabel("地图下载进度")
            Text(store.downloadMessage ?? "正在下载…").font(.subheadline).foregroundStyle(.secondary)
            Button("暂停下载") { store.cancelDownload() }
        }
    } else if let message = store.downloadMessage {
        Section { Text(message).font(.subheadline).foregroundStyle(.secondary) }
    }
}
