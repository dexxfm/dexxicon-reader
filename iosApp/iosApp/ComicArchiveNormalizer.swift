import CryptoKit
import Foundation
import Unrar
import ZIPFoundation

/// iOS sibling of `core/reader/ComicArchiveNormalizer.kt` (issue #107, following #106/#108's
/// CBZ + manga RTL + edge-tap work). Readium Swift's format sniffing is ZIP-only, same as the
/// Kotlin toolkit — it can open CBZ but not CBR (RAR) — so a CBR comic needs unpacking and
/// repacking as a real ZIP/CBZ before `EpubReaderViewController` ever sees it. Same shape as
/// the Kotlin version, same two real dependencies it needed too, just named differently per
/// platform:
///
/// - **Reading the RAR**: [Unrar](https://github.com/mtgto/Unrar.swift) — wraps the same
///   official RARLAB unrar source UnrarKit does (real RAR5 support), the SPM-compatible
///   alternative issue #107 settled on after finding UnrarKit itself has no SPM support at all.
/// - **Writing the ZIP**: [ZIPFoundation](https://github.com/weichsel/ZIPFoundation) — a
///   genuinely new capability, not something already in the dependency graph under a
///   different name: Foundation has no ZIP *writer* on Apple platforms (only Readium's own
///   ZIP *reader*, pulled in transitively), unlike the JVM side, where `java.util.zip` already
///   ships both directions in the standard library.
///
/// ZIP inputs (CBZ) never reach this type at all — `ContentView.swift` only calls
/// `normalize(url:authHeader:)` once it already knows the comic looks like a CBR via
/// `looksLikeRar`, exactly mirroring how Android's `ComicReaderViewModel` only calls its
/// normalizer for RAR-shaped sources. RAR can't be range-streamed, so — same as Android's
/// `fromUrl` — a remote CBR is always fetched in full before extraction can even begin.
enum ComicArchiveNormalizer {
    enum NormalizeError: Error {
        case download(status: Int)
        case notAnImageArchive
    }

    private static let cacheDir: URL = {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(
            "comic-cbz", isDirectory: true
        )
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }()

    private static let imageExtensions: Set<String> = [
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "avif", "jxl",
    ]

    /// True when `url`/`mediaType` name a RAR-based comic archive — same href/media-type sniff
    /// Android's `comicSourceLooksLikeRar` uses, since a real magic-byte check needs the whole
    /// file downloaded first either way, and the caller wants to know before paying for that.
    static func looksLikeRar(url: URL, mediaType: String?) -> Bool {
        let ext = url.pathExtension.lowercased()
        let mt = mediaType?.lowercased() ?? ""
        return ext == "cbr" || ext == "rar" || mt.contains("rar") || mt.contains("cbr")
    }

    /// Downloads `url` in full (with `authHeader` attached, the same resolved header every
    /// other reader call in this app already carries) and returns a local file URL to a
    /// ZIP-based CBZ. Cached by URL hash — a second open of the same book is free, nothing is
    /// re-downloaded or re-unpacked, same as Android's `fromUrl`.
    static func normalize(url: URL, authHeader: String?) async throws -> URL {
        let cached = cacheDir.appendingPathComponent("u-\(sha1(url.absoluteString)).cbz")
        if FileManager.default.fileExists(atPath: cached.path) {
            return cached
        }

        var request = URLRequest(url: url)
        if let authHeader {
            request.setValue(authHeader, forHTTPHeaderField: "Authorization")
        }
        let (downloaded, response) = try await URLSession.shared.download(for: request)
        defer { try? FileManager.default.removeItem(at: downloaded) }

        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            let status = (response as? HTTPURLResponse)?.statusCode ?? -1
            throw NormalizeError.download(status: status)
        }

        // `looksLikeRar` is only an extension/media-type heuristic used to decide *whether* to
        // pay for a full download at all — the real, authoritative check is the file's own
        // magic bytes, same as Android's `isRar`. A mislabeled file that turns out to already
        // be a ZIP is copied straight through rather than fed to Unrar, which would just throw.
        if try isRar(downloaded) {
            try rarToCbz(rar: downloaded, out: cached)
        } else {
            try FileManager.default.copyItem(at: downloaded, to: cached)
        }
        return cached
    }

    private static func isRar(_ file: URL) throws -> Bool {
        let handle = try FileHandle(forReadingFrom: file)
        defer { try? handle.close() }
        guard let head = try handle.read(upToCount: 8), head.count == 8 else { return false }
        // "Rar!\x1A\x07" — RAR4 has 0x00 next, RAR5 has 0x01; both start the same, same check
        // as Android's `isRar`.
        return head[0] == 0x52 && head[1] == 0x61 && head[2] == 0x72 && head[3] == 0x21
            && head[4] == 0x1A && head[5] == 0x07
    }

    /// Unpacks every image entry of `rar` into a freshly-created ZIP at `out`. `Unrar.Archive`
    /// and `ZIPFoundation.Archive` are module-qualified throughout this function — both
    /// packages export an unrelated type named `Archive`.
    private static func rarToCbz(rar: URL, out: URL) throws {
        if FileManager.default.fileExists(atPath: out.path) {
            try FileManager.default.removeItem(at: out)
        }
        let source = try Unrar.Archive(fileURL: rar)
        let destination = try ZIPFoundation.Archive(url: out, accessMode: .create)

        var wroteAny = false
        for entry in try source.entries() where !entry.directory && entry.fileName.hasImageExtension {
            let data = try source.extract(entry)
            // Same normalization as Android's rarToCbz — some RAR sources use Windows-style
            // backslash separators, which a ZIP-based container should never contain.
            let name = entry.fileName.replacingOccurrences(of: "\\", with: "/")
            try destination.addEntry(
                with: name,
                type: .file,
                uncompressedSize: Int64(data.count)
            ) { position, size in
                let start = Int(position)
                let end = min(start + size, data.count)
                return data.subdata(in: start..<end)
            }
            wroteAny = true
        }

        guard wroteAny else {
            try? FileManager.default.removeItem(at: out)
            throw NormalizeError.notAnImageArchive
        }
    }

    private static func sha1(_ string: String) -> String {
        Insecure.SHA1.hash(data: Data(string.utf8))
            .map { String(format: "%02x", $0) }
            .joined()
    }
}

private extension String {
    var hasImageExtension: Bool {
        ComicArchiveNormalizer.imageExtensions.contains(
            (self as NSString).pathExtension.lowercased()
        )
    }
}
