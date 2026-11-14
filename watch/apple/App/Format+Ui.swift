import Foundation

func date(ms: Int64) -> Date { Date(timeIntervalSince1970: Double(ms) / 1000) }

/// "5 min ago" in the watch's language
func ago(ms: Int64) -> String {
    guard ms > 0 else { return "-" }
    let f = RelativeDateTimeFormatter()
    f.unitsStyle = .short
    return f.localizedString(for: date(ms: ms), relativeTo: Date())
}

func clock(ms: Int64) -> String {
    date(ms: ms).formatted(date: .omitted, time: .shortened)
}
