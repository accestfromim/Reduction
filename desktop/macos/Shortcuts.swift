import Carbon

final class DesktopShortcuts {
    enum Action: UInt32 {
        case talk = 1, dictate, panel
    }

    private static let signature: OSType = 0x52656475
    private var handler: EventHandlerRef?
    private var hotkeys: [EventHotKeyRef] = []
    var onEvent: (Action, Bool) -> Void = { _, _ in }

    func start() throws {
        guard handler == nil else { return }
        var events = [
            EventTypeSpec(eventClass: OSType(kEventClassKeyboard), eventKind: UInt32(kEventHotKeyPressed)),
            EventTypeSpec(eventClass: OSType(kEventClassKeyboard), eventKind: UInt32(kEventHotKeyReleased))
        ]
        let target = GetApplicationEventTarget()
        let registered = InstallEventHandler(target, { _, event, context in
            guard let event, let context else { return OSStatus(eventNotHandledErr) }
            var id = EventHotKeyID()
            let result = GetEventParameter(event, EventParamName(kEventParamDirectObject), EventParamType(typeEventHotKeyID),
                                           nil, MemoryLayout<EventHotKeyID>.size, nil, &id)
            guard result == noErr, id.signature == DesktopShortcuts.signature,
                  let action = Action(rawValue: id.id) else { return OSStatus(eventNotHandledErr) }
            let owner = Unmanaged<DesktopShortcuts>.fromOpaque(context).takeUnretainedValue()
            owner.onEvent(action, GetEventKind(event) == UInt32(kEventHotKeyPressed))
            return noErr
        }, events.count, &events, Unmanaged.passUnretained(self).toOpaque(), &handler)
        guard registered == noErr else { throw ShortcutError(status: registered) }

        let modifiers = UInt32(controlKey | shiftKey)
        do {
            try register(.talk, key: kVK_Space, modifiers: modifiers)
            try register(.dictate, key: kVK_Space, modifiers: modifiers | UInt32(optionKey))
            try register(.panel, key: kVK_ANSI_R, modifiers: modifiers)
        } catch {
            stop()
            throw error
        }
    }

    func stop() {
        for key in hotkeys { UnregisterEventHotKey(key) }
        hotkeys.removeAll()
        if let handler { RemoveEventHandler(handler) }
        handler = nil
    }

    private func register(_ action: Action, key: Int, modifiers: UInt32) throws {
        var reference: EventHotKeyRef?
        let id = EventHotKeyID(signature: Self.signature, id: action.rawValue)
        let status = RegisterEventHotKey(UInt32(key), modifiers, id, GetApplicationEventTarget(), 0, &reference)
        guard status == noErr, let reference else { throw ShortcutError(status: status) }
        hotkeys.append(reference)
    }

    private struct ShortcutError: Error {
        let status: OSStatus
    }
}
