import Foundation

/// Small pure helpers shared by the screens (unit-tested).
public enum Fmt {
    /// "1h 05m" / "45m" / "0m"
    public static func duration(ms: Int64) -> String {
        let m = Swift.max(0, Int(ms / 60_000))
        return m >= 60 ? "\(m / 60)h \(String(format: "%02d", m % 60))m" : "\(m)m"
    }
    /// seconds -> "m:ss" for the code countdown
    public static func countdown(seconds: Int) -> String {
        let s = Swift.max(0, seconds)
        return "\(s / 60):\(String(format: "%02d", s % 60))"
    }
    /// "123456" -> "123 456"
    public static func code(_ c: String) -> String { c.count == 6 ? c.prefix(3) + " " + c.suffix(3) : c }
}
