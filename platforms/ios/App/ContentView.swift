import SwiftUI

struct ContentView: View {
    @ObservedObject var model: AppModel
    @FocusState private var searchFocused: Bool

    var body: some View {
        ZStack {
            MotoPalette.canvas.ignoresSafeArea()

            ScrollView {
                VStack(spacing: 22) {
                    if searchFocused && !model.isNavigationActive {
                        focusedSearchHeader
                    } else {
                        masthead
                        // Route confirmation has one job: let the rider inspect
                        // the whole trip and choose a candidate. The compact
                        // masthead badge still exposes BLE status here; keeping
                        // the full connection card pushed the map below the fold.
                        if model.isNavigationActive || model.selectedPlace == nil {
                            connectionRail
                        }
                    }
                    if model.isNavigationActive {
                        activeNavigationPanel
                    } else {
                        destinationSearchPanel
                    }
                    if !searchFocused && !model.isNavigationActive {
                        Link("MOTO GPS · Maler X · 非商业使用",
                             destination: URL(string: "https://github.com/mx3353672833-debug/moto-gps-waveshare")!)
                            .font(.caption2)
                            .foregroundStyle(MotoPalette.subtle)
                    }
                }
                .padding(.horizontal, 20)
                .padding(.top, 14)
                .padding(.bottom, 112)
            }
            .scrollDismissesKeyboard(.interactively)
            .scrollIndicators(.hidden)
        }
        .safeAreaInset(edge: .bottom) {
            // The fixed action bar used to sit directly on top of the first
            // POI row when the software keyboard was open. The result looked
            // selectable but its tap landed on the disabled navigation bar.
            // Give destination selection the full keyboard-safe viewport.
            if !searchFocused {
                actionBar
            }
        }
        .onChange(of: model.destinationQuery) { _, _ in
            model.destinationQueryDidChange()
        }
        .toolbar {
            ToolbarItemGroup(placement: .keyboard) {
                Spacer()
                Button("完成") { searchFocused = false }
            }
        }
        .preferredColorScheme(.dark)
    }

    private var masthead: some View {
        HStack(alignment: .top) {
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 8) {
                    Capsule()
                        .fill(MotoPalette.route)
                        .frame(width: 24, height: 4)
                    Text("MOTO GPS")
                        .font(.system(size: 11, weight: .bold, design: .monospaced))
                        .tracking(2.3)
                        .foregroundStyle(MotoPalette.route)
                }
                Text(mastheadTitle)
                    .font(.system(size: 31, weight: .bold, design: .rounded))
                    .foregroundStyle(MotoPalette.ink)
                Text(mastheadDetail)
                    .font(.system(size: 13, weight: .medium))
                    .foregroundStyle(MotoPalette.subtle)
            }
            Spacer(minLength: 12)
            statusBadge
        }
    }

    private var focusedSearchHeader: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 3) {
                Text("选择目的地")
                    .font(.system(size: 24, weight: .bold, design: .rounded))
                    .foregroundStyle(MotoPalette.ink)
                Text("结果优先显示你当前位置附近的地点")
                    .font(.system(size: 12, weight: .medium))
                    .foregroundStyle(MotoPalette.subtle)
            }
            Spacer()
            Button("完成") { searchFocused = false }
                .font(.system(size: 13, weight: .bold, design: .rounded))
                .foregroundStyle(MotoPalette.route)
                .padding(.horizontal, 13)
                .padding(.vertical, 9)
                .background(MotoPalette.route.opacity(0.12), in: Capsule())
        }
    }

    private var statusBadge: some View {
        HStack(spacing: 7) {
            Circle()
                .fill(deviceTint)
                .frame(width: 7, height: 7)
                .shadow(color: deviceTint.opacity(0.55), radius: 5)
            Text(deviceBadgeText)
                .font(.system(size: 11, weight: .semibold, design: .rounded))
                .foregroundStyle(MotoPalette.ink)
        }
        .padding(.horizontal, 11)
        .padding(.vertical, 8)
        .background(MotoPalette.panelRaised, in: Capsule())
    }

    private var connectionRail: some View {
        VStack(spacing: 17) {
            HStack(spacing: 12) {
                connectionEndpoint(icon: "iphone", label: "iPhone", ready: true)

                VStack(spacing: 7) {
                    HStack(spacing: 4) {
                        ForEach(0 ..< 5, id: \.self) { index in
                            Capsule()
                                .fill(index == 2 ? deviceTint : deviceTint.opacity(0.38))
                                .frame(maxWidth: .infinity)
                                .frame(height: index == 2 ? 3 : 2)
                        }
                    }
                    Text(connectionRailLabel)
                        .font(.system(size: 9, weight: .bold, design: .monospaced))
                        .tracking(1.25)
                        .foregroundStyle(deviceTint)
                }
                .frame(maxWidth: .infinity)

                connectionEndpoint(icon: "circle.circle.fill", label: "圆屏", ready: model.deviceReady)
            }

            HStack(alignment: .center, spacing: 12) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(deviceTitle)
                        .font(.system(size: 16, weight: .bold, design: .rounded))
                        .foregroundStyle(MotoPalette.ink)
                    Text(deviceDetail)
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(MotoPalette.subtle)
                        .lineLimit(2)
                }
                Spacer()
                if canRetryConnection {
                    Button("重试") { model.toggleDeviceConnection() }
                        .font(.system(size: 13, weight: .bold, design: .rounded))
                        .foregroundStyle(MotoPalette.route)
                        .padding(.horizontal, 13)
                        .padding(.vertical, 9)
                        .background(MotoPalette.route.opacity(0.12), in: Capsule())
                }
            }
        }
        .padding(18)
        .background(
            RoundedRectangle(cornerRadius: 24, style: .continuous)
                .fill(MotoPalette.panel)
                .overlay(alignment: .leading) {
                    Capsule()
                        .fill(deviceTint)
                        .frame(width: 3, height: 42)
                        .padding(.leading, 1)
                }
        )
        .accessibilityElement(children: .combine)
        .accessibilityLabel("圆屏连接状态，\(deviceTitle)，\(deviceDetail)")
    }

    private func connectionEndpoint(icon: String, label: String, ready: Bool) -> some View {
        VStack(spacing: 7) {
            ZStack {
                Circle()
                    .fill(ready ? MotoPalette.route.opacity(0.14) : MotoPalette.field)
                Circle()
                    .stroke(ready ? MotoPalette.route.opacity(0.5) : MotoPalette.divider, lineWidth: 1)
                Image(systemName: icon)
                    .font(.system(size: 21, weight: .semibold))
                    .foregroundStyle(ready ? MotoPalette.ink : MotoPalette.muted)
            }
            .frame(width: 50, height: 50)
            Text(label)
                .font(.system(size: 10, weight: .semibold, design: .monospaced))
                .foregroundStyle(MotoPalette.subtle)
        }
    }

    private var destinationSearchPanel: some View {
        VStack(alignment: .leading, spacing: 16) {
            // Once a destination is selected, the selected-place card and its
            // “更换” action replace the search chrome. This removes duplicated
            // destination text and brings the full-route map into the first
            // viewport without taking away any action.
            if model.selectedPlace == nil {
                if !searchFocused {
                    VStack(alignment: .leading, spacing: 5) {
                        Text("你要去哪里？")
                            .font(.system(size: 25, weight: .bold, design: .rounded))
                            .foregroundStyle(MotoPalette.ink)
                        Text("搜索真实地点，选择后由高德规划实时驾车路线")
                            .font(.system(size: 13))
                            .foregroundStyle(MotoPalette.subtle)
                    }
                }

                searchField

                HStack(spacing: 7) {
                    Image(systemName: model.searchBiasAvailable ? "location.fill" : "location.slash")
                    Text(model.searchScopeText)
                }
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(model.searchBiasAvailable ? MotoPalette.ready : MotoPalette.muted)
            }

            if let place = model.selectedPlace {
                selectedPlaceCard(place)
                if model.isPlanningRoutePreview {
                    routePlanningCard
                } else if model.hasRoutePreview {
                    routePreviewPanel(place)
                } else if let failure = model.routePreviewFailure {
                    routePreviewFailureCard(failure)
                }
            } else if !model.placeResults.isEmpty {
                searchResults
            } else if model.destinationQuery.isEmpty, !model.recentPlaces.isEmpty {
                recentSearches
            } else {
                searchHint
            }

            if let message = model.navigationFailure {
                HStack(alignment: .top, spacing: 11) {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .foregroundStyle(MotoPalette.warning)
                    Text(message)
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(MotoPalette.ink)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                .padding(14)
                .background(MotoPalette.warning.opacity(0.10), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            }

            if !searchFocused, model.selectedPlace == nil {
                demoButton
            }
        }
    }

    private var searchField: some View {
        HStack(spacing: 12) {
            Image(systemName: "magnifyingglass")
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(MotoPalette.route)

            TextField("城市、道路或地点", text: $model.destinationQuery)
                .accessibilityIdentifier("destination-search-field")
                .focused($searchFocused)
                .submitLabel(.search)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .font(.system(size: 16, weight: .semibold, design: .rounded))
                .foregroundStyle(MotoPalette.ink)
                .onSubmit {
                    model.submitDestinationSearch()
                    searchFocused = false
                }

            if model.isSearchingPlaces {
                ProgressView()
                    .tint(MotoPalette.route)
                    .controlSize(.small)
            } else if !model.destinationQuery.isEmpty {
                Button {
                    model.clearDestination()
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .font(.system(size: 17))
                        .foregroundStyle(MotoPalette.muted)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("清空终点")
            }
        }
        .padding(.horizontal, 16)
        .frame(height: 56)
        .background(
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .fill(MotoPalette.field)
                .overlay {
                    RoundedRectangle(cornerRadius: 18, style: .continuous)
                        .stroke(searchFocused ? MotoPalette.route.opacity(0.75) : MotoPalette.divider, lineWidth: 1)
                }
        )
    }

    private func selectedPlaceCard(_ place: PlaceSearchResult) -> some View {
        HStack(spacing: 14) {
            ZStack {
                Circle().fill(MotoPalette.ready.opacity(0.14))
                Image(systemName: "location.fill")
                    .font(.system(size: 18, weight: .bold))
                    .foregroundStyle(MotoPalette.ready)
            }
            .frame(width: 44, height: 44)

            VStack(alignment: .leading, spacing: 4) {
                Text(place.name)
                    .font(.system(size: 17, weight: .bold, design: .rounded))
                    .foregroundStyle(MotoPalette.ink)
                    .lineLimit(1)
                Text(placeSubtitle(place))
                    .font(.system(size: 12))
                    .foregroundStyle(MotoPalette.subtle)
                    .lineLimit(2)
            }
            Spacer(minLength: 8)
            Button("更换") {
                model.clearDestination()
                searchFocused = true
            }
            .font(.system(size: 12, weight: .bold))
            .foregroundStyle(MotoPalette.route)
        }
        .padding(16)
        .background(MotoPalette.panel, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
    }

    private var routePlanningCard: some View {
        HStack(spacing: 14) {
            ProgressView()
                .tint(MotoPalette.route)
                .controlSize(.regular)
            VStack(alignment: .leading, spacing: 4) {
                Text("正在生成可选路线")
                    .font(.system(size: 16, weight: .bold, design: .rounded))
                    .foregroundStyle(MotoPalette.ink)
                Text("读取高德实时驾车路线和分段路况")
                    .font(.system(size: 12, weight: .medium))
                    .foregroundStyle(MotoPalette.subtle)
            }
            Spacer()
        }
        .padding(18)
        .background(MotoPalette.panel, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        .accessibilityIdentifier("route-preview-loading")
    }

    private func routePreviewPanel(_ place: PlaceSearchResult) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .firstTextBaseline) {
                VStack(alignment: .leading, spacing: 3) {
                    Text("路线全览")
                        .font(.system(size: 22, weight: .bold, design: .rounded))
                        .foregroundStyle(MotoPalette.ink)
                    Text(model.routePreviewCandidates.count > 1
                         ? "点选路线后再开始导航"
                         : "确认全程后再开始导航")
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(MotoPalette.subtle)
                }
                Spacer()
                Text("\(model.routePreviewCandidates.count) 条")
                    .font(.system(size: 11, weight: .bold, design: .monospaced))
                    .foregroundStyle(MotoPalette.route)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 7)
                    .background(MotoPalette.route.opacity(0.12), in: Capsule())
            }

            RouteOverviewMap(
                candidates: model.routePreviewCandidates,
                selectedID: model.selectedRoutePreviewID,
                origin: model.routePreviewOrigin,
                destination: place.location
            )
            .frame(height: 286)
            .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: 20, style: .continuous)
                    .stroke(MotoPalette.divider, lineWidth: 1)
            }
            .overlay(alignment: .topLeading) {
                Label("全程", systemImage: "arrow.up.left.and.arrow.down.right")
                    .font(.system(size: 10, weight: .bold, design: .monospaced))
                    .foregroundStyle(MotoPalette.ink)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 7)
                    .background(.black.opacity(0.68), in: Capsule())
                    .padding(12)
            }
            .accessibilityIdentifier("route-preview-map")

            VStack(spacing: 9) {
                ForEach(model.routePreviewCandidates) { candidate in
                    routeOptionCard(candidate)
                }
            }
        }
        .padding(16)
        .background(MotoPalette.panel, in: RoundedRectangle(cornerRadius: 24, style: .continuous))
    }

    private func routeOptionCard(_ candidate: RoutePreviewCandidate) -> some View {
        let selected = candidate.id == model.selectedRoutePreviewID
        return Button {
            withAnimation(.easeOut(duration: 0.18)) {
                model.selectRoutePreview(candidate.id)
            }
        } label: {
            HStack(spacing: 13) {
                ZStack {
                    Circle()
                        .fill(selected ? MotoPalette.route : MotoPalette.muted.opacity(0.22))
                    Text("\(candidate.ordinal + 1)")
                        .font(.system(size: 12, weight: .heavy, design: .rounded))
                        .foregroundStyle(selected ? MotoPalette.canvas : MotoPalette.subtle)
                }
                .frame(width: 34, height: 34)

                VStack(alignment: .leading, spacing: 4) {
                    HStack(spacing: 7) {
                        Text(candidate.title)
                            .font(.system(size: 15, weight: .bold, design: .rounded))
                            .foregroundStyle(MotoPalette.ink)
                        Text(candidate.trafficSummary)
                            .font(.system(size: 10, weight: .bold, design: .rounded))
                            .foregroundStyle(trafficTint(candidate))
                    }
                    Text("\(candidate.durationText) · \(candidate.distanceText)")
                        .font(.system(size: 12, weight: .semibold, design: .rounded))
                        .foregroundStyle(MotoPalette.subtle)
                        .monospacedDigit()
                }
                Spacer(minLength: 8)
                Image(systemName: selected ? "checkmark.circle.fill" : "circle")
                    .font(.system(size: 20, weight: .semibold))
                    .foregroundStyle(selected ? MotoPalette.route : MotoPalette.muted)
            }
            .padding(.horizontal, 14)
            .frame(minHeight: 66)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .background(
            selected ? MotoPalette.route.opacity(0.10) : MotoPalette.field,
            in: RoundedRectangle(cornerRadius: 17, style: .continuous)
        )
        .overlay {
            RoundedRectangle(cornerRadius: 17, style: .continuous)
                .stroke(selected ? MotoPalette.route.opacity(0.72) : MotoPalette.divider, lineWidth: 1)
        }
        .accessibilityIdentifier("route-option-\(candidate.ordinal)")
        .accessibilityLabel("\(candidate.title)，\(candidate.durationText)，\(candidate.distanceText)，\(candidate.trafficSummary)")
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    private func routePreviewFailureCard(_ message: String) -> some View {
        HStack(alignment: .center, spacing: 13) {
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundStyle(MotoPalette.warning)
            VStack(alignment: .leading, spacing: 4) {
                Text("路线没有生成")
                    .font(.system(size: 15, weight: .bold, design: .rounded))
                    .foregroundStyle(MotoPalette.ink)
                Text(message)
                    .font(.system(size: 12, weight: .medium))
                    .foregroundStyle(MotoPalette.subtle)
            }
            Spacer()
            Button("重试") { model.planRoutePreview() }
                .font(.system(size: 12, weight: .bold, design: .rounded))
                .foregroundStyle(MotoPalette.route)
                .padding(.horizontal, 12)
                .padding(.vertical, 9)
                .background(MotoPalette.route.opacity(0.12), in: Capsule())
        }
        .padding(16)
        .background(MotoPalette.warning.opacity(0.09), in: RoundedRectangle(cornerRadius: 18, style: .continuous))
    }

    private var searchResults: some View {
        VStack(spacing: 0) {
            ForEach(Array(model.placeResults.enumerated()), id: \.offset) { index, place in
                Button {
                    model.selectPlace(place)
                    searchFocused = false
                } label: {
                    HStack(spacing: 13) {
                        Image(systemName: "mappin")
                            .font(.system(size: 16, weight: .bold))
                            .foregroundStyle(MotoPalette.route)
                            .frame(width: 28)
                        VStack(alignment: .leading, spacing: 4) {
                            Text(place.name)
                                .font(.system(size: 16, weight: .semibold, design: .rounded))
                                .foregroundStyle(MotoPalette.ink)
                                .lineLimit(1)
                            Text(placeSubtitle(place))
                                .font(.system(size: 12))
                                .foregroundStyle(MotoPalette.subtle)
                                .lineLimit(1)
                        }
                        Spacer()
                        Text("选为终点")
                            .font(.system(size: 11, weight: .bold, design: .rounded))
                            .foregroundStyle(MotoPalette.route)
                            .padding(.horizontal, 9)
                            .padding(.vertical, 7)
                            .background(MotoPalette.route.opacity(0.12), in: Capsule())
                    }
                    .padding(.horizontal, 15)
                    .frame(minHeight: 68)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("place-result-\(index)")

                if index < model.placeResults.count - 1 {
                    Divider()
                        .overlay(MotoPalette.divider)
                        .padding(.leading, 56)
                }
            }
        }
        .background(MotoPalette.panel, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
    }

    private var recentSearches: some View {
        VStack(spacing: 0) {
            HStack {
                Text("最近搜索")
                    .font(.system(size: 12, weight: .bold, design: .rounded))
                    .foregroundStyle(MotoPalette.subtle)
                Spacer()
                Button("清空") { model.clearRecentPlaces() }
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(MotoPalette.muted)
            }
            .padding(.horizontal, 15)
            .frame(height: 42)

            Divider().overlay(MotoPalette.divider)

            ForEach(Array(model.recentPlaces.enumerated()), id: \.offset) { index, place in
                Button {
                    model.selectPlace(place)
                } label: {
                    HStack(spacing: 13) {
                        Image(systemName: "clock.arrow.circlepath")
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundStyle(MotoPalette.muted)
                            .frame(width: 28)
                        VStack(alignment: .leading, spacing: 3) {
                            Text(place.name)
                                .font(.system(size: 15, weight: .semibold, design: .rounded))
                                .foregroundStyle(MotoPalette.ink)
                                .lineLimit(1)
                            Text(placeSubtitle(place))
                                .font(.system(size: 11))
                                .foregroundStyle(MotoPalette.subtle)
                                .lineLimit(1)
                        }
                        Spacer()
                        Image(systemName: "chevron.right")
                            .font(.system(size: 11, weight: .bold))
                            .foregroundStyle(MotoPalette.muted)
                    }
                    .padding(.horizontal, 15)
                    .frame(minHeight: 62)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)

                if index < model.recentPlaces.count - 1 {
                    Divider().overlay(MotoPalette.divider).padding(.leading, 56)
                }
            }
        }
        .background(MotoPalette.panel, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
    }

    private var demoButton: some View {
        Button {
            searchFocused = false
            model.startDemoNavigation()
        } label: {
            HStack(spacing: 12) {
                ZStack {
                    Circle().fill(MotoPalette.route.opacity(0.13))
                    Image(systemName: "play.fill")
                        .font(.system(size: 13, weight: .bold))
                        .foregroundStyle(MotoPalette.route)
                        .offset(x: 1)
                }
                .frame(width: 38, height: 38)
                VStack(alignment: .leading, spacing: 3) {
                    Text("演示导航")
                        .font(.system(size: 15, weight: .bold, design: .rounded))
                        .foregroundStyle(MotoPalette.ink)
                    Text(model.deviceReady ? "用内置轨迹演示圆屏的完整导航" : "可先在手机预览；圆屏连接后自动同步")
                        .font(.system(size: 11))
                        .foregroundStyle(MotoPalette.subtle)
                }
                Spacer()
                Image(systemName: "chevron.right")
                    .font(.system(size: 11, weight: .bold))
                    .foregroundStyle(MotoPalette.muted)
            }
            .padding(14)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("demo-navigation-button")
        .background(
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .stroke(MotoPalette.divider, lineWidth: 1)
        )
    }

    private var searchHint: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: model.placeSearchFailure == nil ? "sparkle.magnifyingglass" : "exclamationmark.triangle.fill")
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(model.placeSearchFailure == nil ? MotoPalette.muted : MotoPalette.warning)
            Text(model.placeSearchFailure ?? "输入至少两个字，例如“奥体中心”；会优先找你附近的地点")
                .font(.system(size: 13, weight: .medium))
                .foregroundStyle(MotoPalette.subtle)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.horizontal, 4)
    }

    private var activeNavigationPanel: some View {
        VStack(spacing: 20) {
            VStack(spacing: 12) {
                ZStack {
                    Circle()
                        .fill(MotoPalette.ready.opacity(0.13))
                    Circle()
                        .stroke(MotoPalette.ready.opacity(0.32), lineWidth: 1)
                    Image(systemName: navigationSymbol)
                        .font(.system(size: 28, weight: .bold))
                        .foregroundStyle(navigationTint)
                }
                .frame(width: 68, height: 68)

                Text(model.phaseTitle)
                    .font(.system(size: 25, weight: .bold, design: .rounded))
                    .foregroundStyle(MotoPalette.ink)
                    .multilineTextAlignment(.center)
                Text(model.phaseDetail)
                    .font(.system(size: 13, weight: .medium))
                    .foregroundStyle(MotoPalette.subtle)
                    .multilineTextAlignment(.center)
            }
            .frame(maxWidth: .infinity)

            HStack(spacing: 12) {
                Image(systemName: model.isDemoActive ? "play.fill" : "flag.checkered")
                    .font(.system(size: 17, weight: .bold))
                    .foregroundStyle(MotoPalette.route)
                VStack(alignment: .leading, spacing: 3) {
                    Text(model.isDemoActive ? "演示" : "目的地")
                        .font(.system(size: 10, weight: .bold, design: .monospaced))
                        .tracking(1.2)
                        .foregroundStyle(MotoPalette.muted)
                    Text(model.activeDestinationName)
                        .font(.system(size: 16, weight: .bold, design: .rounded))
                        .foregroundStyle(MotoPalette.ink)
                        .lineLimit(1)
                }
                Spacer()
            }
            .padding(16)
            .background(MotoPalette.field, in: RoundedRectangle(cornerRadius: 18, style: .continuous))

            HStack(spacing: 10) {
                metric(label: "剩余时间", value: model.remainingDurationText)
                metric(label: "剩余路程", value: model.remainingDistanceText)
            }

            VStack(spacing: 0) {
                statusRow(icon: "circle.circle", title: "圆屏", value: model.deviceReady ? "同步中" : "正在重连", tint: model.deviceReady ? MotoPalette.ready : MotoPalette.warning)
                Divider().overlay(MotoPalette.divider).padding(.leading, 45)
                statusRow(icon: "location", title: "手机定位", value: model.navigation.hasUsableFix ? "精确定位" : "获取中", tint: model.navigation.hasUsableFix ? MotoPalette.ready : MotoPalette.warning)
                Divider().overlay(MotoPalette.divider).padding(.leading, 45)
                statusRow(icon: "car.side", title: "实时路况", value: trafficStatusText, tint: trafficStatusTint)
            }
            .padding(.horizontal, 15)
            .background(MotoPalette.panel, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        }
    }

    private func metric(label: String, value: String) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(label)
                .font(.system(size: 10, weight: .bold, design: .monospaced))
                .tracking(1.1)
                .foregroundStyle(MotoPalette.muted)
            Text(value)
                .font(.system(size: 22, weight: .bold, design: .rounded))
                .monospacedDigit()
                .foregroundStyle(MotoPalette.ink)
                .minimumScaleFactor(0.75)
                .lineLimit(1)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(MotoPalette.field, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
    }

    private func statusRow(icon: String, title: String, value: String, tint: Color) -> some View {
        HStack(spacing: 12) {
            Image(systemName: icon)
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(tint)
                .frame(width: 22)
            Text(title)
                .font(.system(size: 14, weight: .semibold, design: .rounded))
                .foregroundStyle(MotoPalette.ink)
            Spacer()
            HStack(spacing: 6) {
                Circle().fill(tint).frame(width: 6, height: 6)
                Text(value)
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(MotoPalette.subtle)
            }
        }
        .frame(height: 51)
    }

    private var actionBar: some View {
        VStack(spacing: 0) {
            Rectangle()
                .fill(MotoPalette.divider)
                .frame(height: 1)
            Button(action: model.toggleNavigation) {
                HStack(spacing: 10) {
                    if model.isPlanningRoutePreview {
                        ProgressView()
                            .tint(MotoPalette.canvas)
                    } else {
                        Image(systemName: model.isNavigationActive ? "stop.fill" : "arrow.triangle.turn.up.right.circle.fill")
                    }
                    Text(model.primaryActionTitle)
                    Spacer()
                    if !model.isNavigationActive {
                        Image(systemName: "chevron.right")
                            .font(.system(size: 13, weight: .bold))
                    }
                }
                .font(.system(size: 17, weight: .bold, design: .rounded))
                .foregroundStyle(model.isNavigationActive ? MotoPalette.ink : MotoPalette.canvas)
                .padding(.horizontal, 20)
                .frame(height: 58)
                .background(
                    model.isNavigationActive ? MotoPalette.stop : MotoPalette.route,
                    in: RoundedRectangle(cornerRadius: 18, style: .continuous)
                )
            }
            .buttonStyle(.plain)
            .disabled(!model.isNavigationActive && (model.selectedPlace == nil || model.isPlanningRoutePreview))
            .opacity(!model.isNavigationActive && (model.selectedPlace == nil || model.isPlanningRoutePreview) ? 0.44 : 1)
            .accessibilityIdentifier("primary-navigation-action")
            .padding(.horizontal, 20)
            .padding(.top, 12)
            .padding(.bottom, 8)
        }
        .background(.ultraThinMaterial)
    }

    private func placeSubtitle(_ place: PlaceSearchResult) -> String {
        let area = place.displayArea.isEmpty ? [place.city, place.district].filter { !$0.isEmpty }.joined(separator: " · ") : place.displayArea
        var parts = [area, place.address].filter { !$0.isEmpty }
        if let distance = place.distanceM, distance >= 0 {
            let distanceText = distance >= 1_000
                ? String(format: "%.1f km", distance / 1_000)
                : "\(Int(distance.rounded())) m"
            parts.append("距你 \(distanceText)")
        }
        return parts.joined(separator: " · ")
    }

    private var connectionRailLabel: String {
        switch model.device.connection {
        case .connected: return "BLE LIVE"
        case .scanning: return "SCANNING"
        case .connecting: return "HANDSHAKE"
        case .failed: return "RETRY"
        case .bluetoothUnavailable: return "BLUETOOTH OFF"
        case .idle: return "STANDBY"
        }
    }

    private var mastheadTitle: String {
        if model.isNavigationActive { return "正在骑行" }
        if model.selectedPlace != nil { return "确认路线" }
        return "准备出发"
    }

    private var mastheadDetail: String {
        if model.isNavigationActive { return "路线由手机持续更新，指引同步到圆屏" }
        if model.selectedPlace != nil { return "先看全程并选择路线，再发送到圆屏" }
        return "手机负责路线，圆屏负责骑行"
    }

    private func trafficTint(_ candidate: RoutePreviewCandidate) -> Color {
        switch candidate.trafficSummary {
        case "拥堵较多": return MotoPalette.stop
        case "部分路段缓行": return MotoPalette.warning
        case "路况顺畅": return MotoPalette.ready
        default: return MotoPalette.muted
        }
    }

    private var deviceBadgeText: String {
        switch model.device.connection {
        case .connected: return "已连接"
        case .scanning: return "搜索中"
        case .connecting: return "握手中"
        case .failed: return "连接失败"
        case .bluetoothUnavailable: return "蓝牙关闭"
        case .idle: return "未连接"
        }
    }

    private var deviceTint: Color {
        switch model.device.connection {
        case .connected: return MotoPalette.ready
        case .failed, .bluetoothUnavailable: return MotoPalette.stop
        case .scanning, .connecting: return MotoPalette.warning
        case .idle: return MotoPalette.muted
        }
    }

    private var deviceTitle: String {
        switch model.device.connection {
        case .idle: return "圆屏尚未连接"
        case .bluetoothUnavailable: return "请打开手机蓝牙"
        case .scanning: return "正在寻找 MOTO GPS 圆屏"
        case let .connecting(name): return "正在与 \(name) 握手"
        case let .connected(name): return "\(name) 已连接"
        case .failed: return "圆屏连接失败"
        }
    }

    private var deviceDetail: String {
        switch model.device.connection {
        case .connected:
            return "协议已就绪，导航数据可持续同步"
        case let .failed(message):
            return message
        case .bluetoothUnavailable:
            return "打开蓝牙后会自动继续连接"
        case .scanning, .connecting:
            return "请保持圆屏通电，首次连接可能需要几秒"
        case .idle:
            return "圆屏通电后点击重试"
        }
    }

    private var canRetryConnection: Bool {
        switch model.device.connection {
        case .idle, .failed: return true
        default: return false
        }
    }

    private var navigationSymbol: String {
        if model.navigationFailure != nil { return "exclamationmark.triangle.fill" }
        switch model.navigation.stateName {
        case "arrived": return "flag.checkered"
        case "rerouting": return "arrow.triangle.2.circlepath"
        case "navigating": return "location.north.fill"
        default: return "ellipsis"
        }
    }

    private var navigationTint: Color {
        model.navigationFailure == nil ? MotoPalette.ready : MotoPalette.warning
    }

    private var trafficStatusText: String {
        if model.isDemoActive { return "演示数据" }
        if model.navigation.trafficRequestInFlight { return "更新中" }
        return model.navigation.networkName == "online" ? "在线" : "离线"
    }

    private var trafficStatusTint: Color {
        if model.isDemoActive { return MotoPalette.route }
        return model.navigation.networkName == "online" ? MotoPalette.ready : MotoPalette.warning
    }
}

private enum MotoPalette {
    static let canvas = Color(red: 0.025, green: 0.055, blue: 0.080)
    static let panel = Color(red: 0.055, green: 0.101, blue: 0.132)
    static let panelRaised = Color(red: 0.074, green: 0.128, blue: 0.159)
    static let field = Color(red: 0.040, green: 0.082, blue: 0.109)
    static let ink = Color(red: 0.925, green: 0.962, blue: 0.972)
    static let subtle = Color(red: 0.581, green: 0.671, blue: 0.710)
    static let muted = Color(red: 0.329, green: 0.420, blue: 0.459)
    static let divider = Color.white.opacity(0.09)
    static let route = Color(red: 0.239, green: 0.731, blue: 0.973)
    static let ready = Color(red: 0.337, green: 0.875, blue: 0.596)
    static let warning = Color(red: 0.969, green: 0.699, blue: 0.259)
    static let stop = Color(red: 0.721, green: 0.215, blue: 0.231)
}
