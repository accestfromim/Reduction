import ReductionShared

enum RelativeDates {
    static func dayOf(_ expression: String, referenceDate: String) -> String? {
        RelativeDateMapping.shared.dayOf(expression: expression, referenceDate: referenceDate)
    }

    static func context(_ text: String, referenceDate: String?) -> String {
        RelativeDateMapping.shared.context(text: text, referenceDate: referenceDate)
    }

    static func display(_ date: String, relativeTo referenceDate: String) -> String {
        RelativeDateMapping.shared.display(date: date, referenceDate: referenceDate)
    }
}
