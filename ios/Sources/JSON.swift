import Foundation

enum JSON: Codable, Equatable, Sendable {
    case null, bool(Bool), number(Double), string(String), array([JSON]), object([String: JSON])

    init(from decoder: Decoder) throws {
        let value = try decoder.singleValueContainer()
        if value.decodeNil() { self = .null }
        else if let bool = try? value.decode(Bool.self) { self = .bool(bool) }
        else if let number = try? value.decode(Double.self) { self = .number(number) }
        else if let string = try? value.decode(String.self) { self = .string(string) }
        else if let array = try? value.decode([JSON].self) { self = .array(array) }
        else { self = .object(try value.decode([String: JSON].self)) }
    }

    func encode(to encoder: Encoder) throws {
        var value = encoder.singleValueContainer()
        switch self {
        case .null: try value.encodeNil()
        case .bool(let bool): try value.encode(bool)
        case .number(let number): try value.encode(number)
        case .string(let string): try value.encode(string)
        case .array(let array): try value.encode(array)
        case .object(let object): try value.encode(object)
        }
    }

    var text: String { if case .string(let value) = self { return value }; return "" }
    var values: [JSON] { if case .array(let value) = self { return value }; return [] }
    var fields: [String: JSON] { if case .object(let value) = self { return value }; return [:] }
    var flag: Bool { self == .bool(true) }
    var integer: Int { if case .number(let value) = self, let integer = Int(exactly: value) { return integer }; return 0 }

    subscript(_ key: String) -> JSON { fields[key] ?? .null }
    subscript(_ index: Int) -> JSON { values.indices.contains(index) ? values[index] : .null }

    init(data: Data) throws { self = try JSONDecoder().decode(JSON.self, from: data) }
    init(text: String) throws { try self.init(data: Data(text.utf8)) }

    func data() throws -> Data { try JSONEncoder().encode(self) }
    func encoded() throws -> String { String(decoding: try data(), as: UTF8.self) }
}

extension JSON: ExpressibleByStringLiteral, ExpressibleByBooleanLiteral, ExpressibleByIntegerLiteral,
                ExpressibleByDictionaryLiteral, ExpressibleByArrayLiteral {
    init(stringLiteral value: String) { self = .string(value) }
    init(booleanLiteral value: Bool) { self = .bool(value) }
    init(integerLiteral value: Int) { self = .number(Double(value)) }
    init(dictionaryLiteral elements: (String, JSON)...) { self = .object(Dictionary(uniqueKeysWithValues: elements)) }
    init(arrayLiteral elements: JSON...) { self = .array(elements) }
}

struct ReductionError: LocalizedError {
    let errorDescription: String?

    init(_ message: String) { errorDescription = message }
}

extension String {
    var trimmed: String { trimmingCharacters(in: .whitespacesAndNewlines) }
}

func nowMilliseconds() -> Int { Int(Date().timeIntervalSince1970 * 1000) }
