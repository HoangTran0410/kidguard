import Foundation

/// Loose JSON value. The phone's rules object is a FULL REPLACE, so unknown fields must survive a
/// read -> edit -> PUT round trip: rules are kept as `[String: JSON]`, never as a fixed struct.
public enum JSON: Codable, Equatable {
    case null
    case bool(Bool)
    case num(Double)
    case str(String)
    case arr([JSON])
    case obj([String: JSON])

    public init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        if c.decodeNil() { self = .null }
        else if let v = try? c.decode(Bool.self) { self = .bool(v) }
        else if let v = try? c.decode(Double.self) { self = .num(v) }
        else if let v = try? c.decode(String.self) { self = .str(v) }
        else if let v = try? c.decode([JSON].self) { self = .arr(v) }
        else if let v = try? c.decode([String: JSON].self) { self = .obj(v) }
        else { throw DecodingError.dataCorruptedError(in: c, debugDescription: "bad json") }
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        switch self {
        case .null: try c.encodeNil()
        case .bool(let v): try c.encode(v)
        case .num(let v):
            // whole numbers go out as integers (ms timestamps, minutes): the phone reads them as Long / Int
            if v.rounded() == v, abs(v) < 9e15 { try c.encode(Int64(v)) } else { try c.encode(v) }
        case .str(let v): try c.encode(v)
        case .arr(let v): try c.encode(v)
        case .obj(let v): try c.encode(v)
        }
    }

    public static func parse(_ data: Data) throws -> JSON {
        if data.isEmpty { return .obj([:]) }
        return try JSONDecoder().decode(JSON.self, from: data)
    }

    public func data() throws -> Data { try JSONEncoder().encode(self) }

    public subscript(_ key: String) -> JSON {
        if case .obj(let o) = self { return o[key] ?? .null }
        return .null
    }
    public subscript(_ i: Int) -> JSON {
        if case .arr(let a) = self, a.indices.contains(i) { return a[i] }
        return .null
    }

    public var string: String? { if case .str(let s) = self { return s }; return nil }
    public var double: Double? { if case .num(let n) = self { return n }; return nil }
    public var int: Int? { double.map { Int($0) } }
    public var int64: Int64 { double.map { Int64($0) } ?? 0 }
    public var bool: Bool? { if case .bool(let b) = self { return b }; return nil }
    public var array: [JSON] { if case .arr(let a) = self { return a }; return [] }
    public var object: [String: JSON]? { if case .obj(let o) = self { return o }; return nil }
    public var isNull: Bool { if case .null = self { return true }; return false }
}
