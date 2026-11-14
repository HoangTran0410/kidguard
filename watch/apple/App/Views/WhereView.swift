import SwiftUI
import MapKit
import KidGuardCore

/// Where: MapKit map (watchOS 10 has its own tiles, nothing to download) + accuracy circle + Directions in Apple Maps.
struct WhereView: View {
    let kid: Kid
    @ObservedObject var model: KidHomeModel

    var body: some View {
        ScrollView {
            if let l = model.s?.loc {
                let c = CLLocationCoordinate2D(latitude: l.lat, longitude: l.lng)
                VStack(spacing: 6) {
                    Map(initialPosition: .region(MKCoordinateRegion(
                        center: c, latitudinalMeters: max(Double(l.acc) * 4, 400), longitudinalMeters: max(Double(l.acc) * 4, 400)))) {
                        MapCircle(center: c, radius: Double(max(l.acc, 5)))
                            .foregroundStyle(.blue.opacity(0.18)).stroke(.blue, lineWidth: 1)
                        Marker(kid.name, coordinate: c)
                    }
                    .frame(height: 150).clipShape(RoundedRectangle(cornerRadius: 12))
                    Text(String(format: String(localized: "where.fix"), l.acc, ago(ms: l.t))).font(.footnote)
                    if let z = model.s?.zone { Text(z.text).font(.footnote).foregroundStyle(.secondary) }
                    Button { directions(c) } label: { Label("where.directions", systemImage: "arrow.triangle.turn.up.right.diamond") }
                    Button { model.send(String(localized: "act.locate")) { try await model.api.command("locate") } } label: {
                        Label("act.locate", systemImage: "location")
                    }
                }
            } else {
                Text("where.none").foregroundStyle(.secondary)
                Button("act.locate") { model.send(String(localized: "act.locate")) { try await model.api.command("locate") } }
            }
        }
        .navigationTitle("act.where")
    }

    /// Opens Apple Maps with directions to the kid (MKMapItem.openInMaps; not an http link, watchOS has no browser).
    private func directions(_ c: CLLocationCoordinate2D) {
        let item = MKMapItem(placemark: MKPlacemark(coordinate: c))
        item.name = kid.name
        item.openInMaps(launchOptions: [MKLaunchOptionsDirectionsModeKey: MKLaunchOptionsDirectionsModeDriving])
    }
}
