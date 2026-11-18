import Foundation

extension PhoneStore {
    func syncPayload(files: [String: JSON] = [:]) throws -> JSON {
        let kept = state.cards.values.sorted { $0.name < $1.name }
        let cards: JSON = ["cards": try Self.wire(kept), "deleted": try Self.wire(state.cardDeletions)]
        let memos: JSON = ["items": try Self.wire(state.memos), "next": .number(Double(state.nextMemo)), "deleted": try Self.wire(state.memoDeletions)]
        let history: JSON = ["lines": try Self.wire(state.history), "clearedAt": .number(Double(state.historyClearedAt))]
        return ["version": 1, "cards": cards, "memos": memos, "history": history, "media": .array(try mediaNames().map(JSON.string)),
                "files": .object(files)]
    }

    private static func wire(_ value: some Encodable) throws -> JSON { try JSON(data: JSONEncoder().encode(value)) }

    func filesMissing(from peer: JSON) throws -> [String: JSON] {
        let theirs = Set(peer["media"].values.map(\.text))
        var room = 6 * 1024 * 1024
        var files: [String: JSON] = [:]
        for name in try mediaNames() where !theirs.contains(name) {
            guard let entry = mediaURL(name), let size = try entry.resourceValues(forKeys: [.fileSizeKey]).fileSize, size <= room else { continue }
            let data = try Data(contentsOf: entry)
            guard data.count <= room else { continue }
            room -= data.count
            files[name] = .string(data.base64EncodedString())
        }
        return files
    }

    func merge(_ peer: JSON) throws -> Bool {
        guard peer["version"].integer == 1 else { throw ReductionError("电脑返回了不支持的同步协议") }
        for (name, blob) in peer["files"].fields where Self.isMediaID(name) && mediaURL(name) == nil {
            guard let data = Data(base64Encoded: blob.text), !data.isEmpty, data.count <= 6 * 1024 * 1024 else {
                throw ReductionError("电脑同步的附件无效")
            }
            try data.write(to: media.appendingPathComponent(name), options: .atomic)
        }
        let before = state
        try change { next in
            let remoteCards = peer["cards"]
            for (raw, deleted) in remoteCards["deleted"].fields {
                let name = try Self.cleanName(raw)
                guard !Self.managedCards.contains(name), deleted.integer > (next.cards[name]?.at ?? 0) else { continue }
                next.cards.removeValue(forKey: name)
                next.cardDeletions[name] = max(next.cardDeletions[name] ?? 0, deleted.integer)
            }
            for entry in remoteCards["cards"].values {
                let name = try Self.cleanName(entry["name"].text)
                let at = entry["at"].integer
                let text = entry["text"].text
                let old = next.cards[name]
                guard !Self.managedCards.contains(name), at > (next.cardDeletions[name] ?? 0), text.utf16.count <= 60_000,
                      at > (old?.at ?? 0) || (at == old?.at && text > (old?.text ?? "")) else { continue }
                next.cards[name] = PhoneCard(name: name, text: text, purpose: Self.cleanPurpose(entry["purpose"].text),
                                             prompt: entry["prompt"].flag, at: at)
                next.cardDeletions.removeValue(forKey: name)
            }
            let remoteHistory = peer["history"]
            next.historyClearedAt = max(next.historyClearedAt, remoteHistory["clearedAt"].integer)
            next.history.removeAll { $0.at <= next.historyClearedAt }
            var known = Set(next.history.map(\.id))
            for entry in remoteHistory["lines"].values {
                let line = try JSONDecoder().decode(HistoryLine.self, from: entry.data())
                if line.at > next.historyClearedAt, ["assistant", "user"].contains(line.role), !line.id.isEmpty, known.insert(line.id).inserted {
                    next.history.append(line)
                }
            }
            next.history = Array(next.history.sorted { ($0.at, $0.id) < ($1.at, $1.id) }.suffix(100))
            try Self.mergeMemos(peer["memos"], into: &next)
        }
        return before != state || !peer["files"].fields.isEmpty
    }

    private static func mergeMemos(_ response: JSON, into next: inout PhoneSnapshot) throws {
        for (id, value) in response["deleted"].fields {
            next.memoDeletions[id] = max(value.integer, next.memoDeletions[id] ?? 0)
            next.memos.removeAll { $0.uid == id && $0.updatedAt <= value.integer }
        }
        let remote = try response["items"].values.map { entry -> PhoneMemo in
            let uid = entry["uid"].text, id = entry["id"].integer, at = entry["createdAt"].integer
            guard !uid.isEmpty, id > 0 else { throw ReductionError("电脑同步的待办无效") }
            return PhoneMemo(id: id, uid: uid, text: entry["text"].text, due: try MemoDays.parse(entry["due"].text),
                             part: try MemoDays.part(entry["part"].text), done: entry["done"].flag, createdAt: at,
                             updatedAt: max(at, entry["updatedAt"].integer), doneAt: entry["doneAt"].integer)
        }
        var assigned = Set(remote.map(\.id))
        next.nextMemo = max(next.nextMemo, response["next"].integer, (assigned.max() ?? 0) + 1)
        var items = Dictionary(uniqueKeysWithValues: next.memos.map { ($0.uid, $0) })
        let remoteIDs = Set(remote.map(\.uid))
        for theirs in remote where theirs.updatedAt > (next.memoDeletions[theirs.uid] ?? 0) {
            var chosen = theirs
            if let local = items[theirs.uid], local.updatedAt >= theirs.updatedAt { chosen = local }
            chosen.id = theirs.id
            items[theirs.uid] = chosen
        }
        for uid in items.keys.sorted() where !remoteIDs.contains(uid) {
            guard var item = items[uid] else { continue }
            if !assigned.insert(item.id).inserted {
                while assigned.contains(next.nextMemo) { next.nextMemo += 1 }
                item.id = next.nextMemo
                assigned.insert(item.id)
                next.nextMemo += 1
                items[uid] = item
            }
        }
        next.memos = items.values.sorted { $0.id < $1.id }
        next.nextMemo = max(next.nextMemo, (next.memos.map(\.id).max() ?? 0) + 1)
    }
}
