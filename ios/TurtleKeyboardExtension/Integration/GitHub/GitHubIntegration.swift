import Foundation
#if os(iOS)
import UIKit

// MARK: - GitHubIntegration
//
// `/github` — pull live info about any GitHub repo straight into the text
// field, no app-switching. Public repos work unauthenticated; once the user
// connects GitHub in the host app, private repos work too (and the rate limit
// jumps from 60 to 5,000 req/hr).
//
// What you can fetch (the keyboard shows these as tappable action chips after
// you pick a repo; power users can also type the keyword):
//
//   owner/repo                → 📊 Overview  (stars, forks, open issues,
//                               language, last updated, link) — the default
//   owner/repo commit         → 🔨 latest commit (sha, message, author, when)
//   owner/repo issues         → 🐛 top open issues
//   owner/repo prs            → 🔀 top open pull requests
//   owner/repo release        → 🏷️ latest release (tag, name, link)
//   owner/repo#42             → status of issue / PR #42
//
// Also accepts github.com URLs (incl. /pull/42, /issues/42). The fetch is
// async; results hop to the main thread before touching the field or banner.

final class GitHubIntegration: KeyboardIntegration {

    let id = "github"

    private static let bannerMs = 1_800

    enum Action { case overview, commit, issues, prs, release, issue(Int) }

    func commands() -> [CommandSpec] {
        [
            CommandSpec(
                name: "github", label: "GitHub", emoji: "🐙", needsPrompt: true,
                handler: { prompt, ctx in Self.handle(prompt: prompt, ctx: ctx) }
            ),
        ]
    }

    static func handle(prompt: String, ctx: IntegrationContext) {
        var query = prompt.trimmingCharacters(in: .whitespacesAndNewlines)
        // Bare /github → first pinned, else most-recent repo (overview).
        if query.isEmpty {
            query = pinnedRepos(ctx.store).first ?? recentRepos(ctx.store).first ?? ""
        }
        guard !query.isEmpty else {
            ctx.showBanner("Try /github facebook/react — or pin repos in the app", autoHideMs: bannerMs)
            return
        }
        guard let cmd = parseCommand(query) else {
            ctx.showBanner("Use owner/repo  (e.g. apple/swift)", autoHideMs: bannerMs)
            return
        }

        let o = cmd.owner, r = cmd.repo
        let token = ctx.store.string(forKey: GitHubKeys.accessToken, fallback: "")
        let base = "https://api.github.com/repos/\(o)/\(r)"

        switch cmd.action {
        case .overview:
            ctx.showBusy("🐙 \(o)/\(r) — overview…")
            run(base, token: token, owner: o, repo: r, ctx: ctx,
                notFound: "\(o)/\(r) not found") { formatOverview($0, owner: o, repo: r) }

        case .commit:
            ctx.showBusy("🐙 \(o)/\(r) — latest commit…")
            run("\(base)/commits?per_page=1", token: token, owner: o, repo: r, ctx: ctx,
                notFound: "\(o)/\(r) not found") { formatLatestCommit($0, owner: o, repo: r) }

        case .issues:
            ctx.showBusy("🐙 \(o)/\(r) — open issues…")
            run("\(base)/issues?state=open&per_page=10", token: token, owner: o, repo: r, ctx: ctx,
                notFound: "\(o)/\(r) not found") { formatIssueList($0, owner: o, repo: r) }

        case .prs:
            ctx.showBusy("🐙 \(o)/\(r) — open PRs…")
            run("\(base)/pulls?state=open&per_page=10", token: token, owner: o, repo: r, ctx: ctx,
                notFound: "\(o)/\(r) not found") { formatPRList($0, owner: o, repo: r) }

        case .release:
            ctx.showBusy("🐙 \(o)/\(r) — latest release…")
            run("\(base)/releases/latest", token: token, owner: o, repo: r, ctx: ctx,
                notFound: "No releases in \(o)/\(r)") { formatRelease($0, owner: o, repo: r) }

        case .issue(let n):
            ctx.showBusy("🐙 \(o)/\(r)#\(n)…")
            run("\(base)/issues/\(n)", token: token, owner: o, repo: r, ctx: ctx,
                notFound: "#\(n) not found in \(o)/\(r)") { formatIssue($0, owner: o, repo: r) }
        }
    }

    // MARK: - Networking

    private static func run(_ urlString: String,
                            token: String,
                            owner: String,
                            repo: String,
                            ctx: IntegrationContext,
                            notFound: String,
                            format: @escaping (Data) -> String?) {
        guard let url = URL(string: urlString) else {
            DispatchQueue.main.async { ctx.showBanner("Couldn't build request", autoHideMs: bannerMs) }
            return
        }
        var req = URLRequest(url: url)
        req.timeoutInterval = 12
        req.setValue("TurtleKeyboard", forHTTPHeaderField: "User-Agent")
        req.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
        if !token.isEmpty { req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }

        URLSession.shared.dataTask(with: req) { data, resp, err in
            let insert: (String) -> Void = { text in
                recordRecent("\(owner)/\(repo)", store: ctx.store)
                DispatchQueue.main.async { ctx.commitText(text) }
            }
            let fail: (String) -> Void = { text in
                DispatchQueue.main.async { ctx.showBanner(text, autoHideMs: bannerMs) }
            }
            if let err = err { fail("⚠️ \(err.localizedDescription)"); return }
            let status = (resp as? HTTPURLResponse)?.statusCode ?? -1
            switch status {
            case 404: fail("⚠️ \(notFound)"); return
            case 401: fail("⚠️ GitHub sign-in expired — reconnect in the app"); return
            case 403: fail("⚠️ GitHub rate limit — sign in for a higher limit"); return
            default: break
            }
            guard (200..<300).contains(status), let data = data else {
                fail("⚠️ GitHub error \(status)"); return
            }
            guard let summary = format(data) else { fail("⚠️ Nothing to show"); return }
            insert(summary)
        }.resume()
    }

    // MARK: - Parsing

    /// Parse `owner/repo [action]` / `owner/repo#N` / github.com URLs into the
    /// repo + which action to run. Unknown / missing keyword → `.overview`.
    static func parseCommand(_ input: String) -> (owner: String, repo: String, action: Action)? {
        var s = input.trimmingCharacters(in: .whitespacesAndNewlines)
        if let r = s.range(of: "github.com/") { s = String(s[r.upperBound...]) }
        s = s.replacingOccurrences(of: "https://", with: "").replacingOccurrences(of: "http://", with: "")

        var issueNum: Int?
        if let hash = s.firstIndex(of: "#") {
            issueNum = Int(s[s.index(after: hash)...].prefix { $0.isNumber })
            s = String(s[..<hash])
        }
        if let cut = s.firstIndex(where: { $0 == "?" }) { s = String(s[..<cut]) }

        // First whitespace token = repo path; optional second token = action.
        let tokens = s.split(separator: " ").map(String.init)
        guard let repoToken = tokens.first else { return nil }
        let rp = repoToken.split(separator: "/").map(String.init)
        guard rp.count >= 2, !rp[0].isEmpty else { return nil }
        let owner = rp[0]
        var repo = rp[1]
        if repo.hasSuffix(".git") { repo = String(repo.dropLast(4)) }
        guard !repo.isEmpty else { return nil }
        // URL form owner/repo/pull/42 or owner/repo/issues/42.
        if issueNum == nil, rp.count >= 4, rp[2] == "pull" || rp[2] == "issues" {
            issueNum = Int(rp[3])
        }

        let action: Action
        if let n = issueNum {
            action = .issue(n)
        } else if tokens.count >= 2 {
            switch tokens[1].lowercased() {
            case "commit", "commits":           action = .commit
            case "issue", "issues", "bug", "bugs": action = .issues
            case "pr", "prs", "pull", "pulls":  action = .prs
            case "release", "releases":         action = .release
            default:                            action = .overview
            }
        } else {
            action = .overview
        }
        return (owner, repo, action)
    }

    // MARK: - Formatting

    /// repo object → stars / forks / open issues / language / updated + link.
    static func formatOverview(_ data: Data, owner: String, repo: String) -> String? {
        guard let o = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
        let stars = o["stargazers_count"] as? Int ?? 0
        let forks = o["forks_count"] as? Int ?? 0
        let issues = o["open_issues_count"] as? Int ?? 0
        let lang = o["language"] as? String
        let updated = (o["pushed_at"] as? String).flatMap(relativeDate)
        let desc = (o["description"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines)
        let url = o["html_url"] as? String ?? "https://github.com/\(owner)/\(repo)"

        var line = "📦 \(owner)/\(repo)"
        if let desc = desc, !desc.isEmpty { line += " — \(desc)" }
        var stats = "⭐ \(compact(stars))  🍴 \(compact(forks))  🐛 \(compact(issues)) open"
        if let lang = lang { stats += "  ·  \(lang)" }
        if let updated = updated { stats += "  ·  updated \(updated)" }
        line += "\n\(stats)\n🔗 \(url)"
        return line
    }

    /// `commits` array → `owner/repo @ sha — "msg" by author (when)`.
    static func formatLatestCommit(_ data: Data, owner: String, repo: String) -> String? {
        guard let arr = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]],
              let first = arr.first else { return nil }
        let sha = (first["sha"] as? String).map { String($0.prefix(7)) } ?? "?"
        let commit = first["commit"] as? [String: Any]
        let raw = commit?["message"] as? String ?? "(no message)"
        let message = raw.split(separator: "\n").first.map(String.init) ?? raw
        let login = (first["author"] as? [String: Any])?["login"] as? String
        let gitName = (commit?["author"] as? [String: Any])?["name"] as? String
        let author = login ?? gitName ?? "unknown"
        let ago = ((commit?["author"] as? [String: Any])?["date"] as? String).flatMap(relativeDate)
        var line = "🔨 \(owner)/\(repo) @ \(sha) — \"\(message)\" by \(author)"
        if let ago = ago { line += " (\(ago))" }
        return line
    }

    /// open issues → header + up to 5 `#num Title — author`. (The issues
    /// endpoint also returns PRs, so items carrying `pull_request` are dropped.)
    static func formatIssueList(_ data: Data, owner: String, repo: String) -> String? {
        guard let arr = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else { return nil }
        let issues = arr.filter { $0["pull_request"] == nil }
        if issues.isEmpty { return "🐛 \(owner)/\(repo) — no open issues 🎉" }
        var lines = ["🐛 \(owner)/\(repo) — \(issues.count)+ open issues"]
        for it in issues.prefix(5) { lines.append(itemLine(it)) }
        return lines.joined(separator: "\n")
    }

    /// open PRs → header + up to 5 `#num Title — author`.
    static func formatPRList(_ data: Data, owner: String, repo: String) -> String? {
        guard let arr = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else { return nil }
        if arr.isEmpty { return "🔀 \(owner)/\(repo) — no open pull requests" }
        var lines = ["🔀 \(owner)/\(repo) — \(arr.count)+ open PRs"]
        for pr in arr.prefix(5) { lines.append(itemLine(pr)) }
        return lines.joined(separator: "\n")
    }

    /// latest release → `🏷️ owner/repo tag "name" (when)` + link.
    static func formatRelease(_ data: Data, owner: String, repo: String) -> String? {
        guard let o = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
        let tag = o["tag_name"] as? String ?? "?"
        let name = (o["name"] as? String)?.trimmingCharacters(in: .whitespaces)
        let ago = (o["published_at"] as? String).flatMap(relativeDate)
        let url = o["html_url"] as? String ?? "https://github.com/\(owner)/\(repo)/releases"
        var line = "🏷️ \(owner)/\(repo) \(tag)"
        if let name = name, !name.isEmpty, name != tag { line += " \"\(name)\"" }
        if let ago = ago { line += " (\(ago))" }
        line += "\n🔗 \(url)"
        return line
    }

    /// issue/PR object → `owner/repo#42 [PR · merged] "Title" by user (when)`.
    static func formatIssue(_ data: Data, owner: String, repo: String) -> String? {
        guard let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let number = obj["number"] as? Int else { return nil }
        let title = (obj["title"] as? String) ?? "(no title)"
        let user = (obj["user"] as? [String: Any])?["login"] as? String ?? "unknown"
        let pr = obj["pull_request"] as? [String: Any]
        let kind = pr != nil ? "PR" : "Issue"
        let merged = (pr?["merged_at"] as? String) != nil
        let state = merged ? "merged" : ((obj["state"] as? String) ?? "?")
        let ago = (obj["created_at"] as? String).flatMap(relativeDate)
        var line = "\(owner)/\(repo)#\(number) [\(kind) · \(state)] \"\(title)\" by \(user)"
        if let ago = ago { line += " (\(ago))" }
        return line
    }

    /// One issue/PR list row: `#123 Title — author`.
    private static func itemLine(_ obj: [String: Any]) -> String {
        let n = obj["number"] as? Int ?? 0
        let title = (obj["title"] as? String) ?? "(no title)"
        let user = (obj["user"] as? [String: Any])?["login"] as? String ?? "?"
        return "#\(n) \(title) — \(user)"
    }

    /// 1234 → "1.2k", 1_500_000 → "1.5M". Trailing ".0" trimmed.
    private static func compact(_ n: Int) -> String {
        func trim(_ s: String) -> String { s.replacingOccurrences(of: ".0", with: "") }
        if n >= 1_000_000 { return trim(String(format: "%.1f", Double(n) / 1_000_000)) + "M" }
        if n >= 1_000     { return trim(String(format: "%.1f", Double(n) / 1_000)) + "k" }
        return "\(n)"
    }

    /// ISO-8601 → abbreviated relative string ("2h ago"). nil if unparseable.
    private static func relativeDate(_ iso: String) -> String? {
        let parser = ISO8601DateFormatter()
        guard let date = parser.date(from: iso) else { return nil }
        let rel = RelativeDateTimeFormatter()
        rel.unitsStyle = .abbreviated
        return rel.localizedString(for: date, relativeTo: Date())
    }

    // MARK: - Saved repos (shared with the host connect screen)

    static func pinnedRepos(_ store: SplitStore) -> [String] {
        splitList(store.string(forKey: GitHubKeys.pinnedRepos, fallback: ""))
    }

    static func recentRepos(_ store: SplitStore) -> [String] {
        splitList(store.string(forKey: GitHubKeys.recentRepos, fallback: ""))
    }

    /// Quick-pick repo chips for the keyboard: pinned first, then recents,
    /// de-duplicated and capped.
    static func repoChips(_ store: SplitStore, max: Int = 8) -> [String] {
        var seen = Set<String>(); var out: [String] = []
        for repo in pinnedRepos(store) + recentRepos(store) where seen.insert(repo.lowercased()).inserted {
            out.append(repo); if out.count >= max { break }
        }
        return out
    }

    static func recordRecent(_ repo: String, store: SplitStore) {
        let clean = repo.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !clean.isEmpty else { return }
        var list = recentRepos(store).filter { $0.lowercased() != clean.lowercased() }
        list.insert(clean, at: 0)
        if list.count > 8 { list = Array(list.prefix(8)) }
        store.setString(list.joined(separator: "\n"), forKey: GitHubKeys.recentRepos)
    }

    private static func splitList(_ raw: String) -> [String] {
        raw.split(separator: "\n").map { String($0).trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
    }
}

// MARK: - GitHubKeys (extension-target copy)
//
// The keyboard extension is a separate module from the host app, so it can't
// see the host's `GitHubKeys`. These strings MUST match the host copy in
// `TurtleKeyboard/Cloud/GitHubAuth.swift`.
enum GitHubKeys {
    static let accessToken = "github.access_token"
    static let login       = "github.login"
    static let pinnedRepos = "github.pinned_repos"
    static let recentRepos = "github.recent_repos"
}
#endif
