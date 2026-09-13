import CryptoKit
import Foundation

/// Downloads a remote URL in full to a cached local file, keyed by a SHA1 hash of the URL
/// string.
///
/// Issue #170/#171: Readium Swift Toolkit 3.11.0's HTTP range-streaming for ZIP-based formats
/// (EPUB and CBZ are both ZIP containers) goes through `ZIPFoundationArchiveFactory`, which
/// wraps the remote resource in a `BufferingResource` with a fixed 6 MiB look-ahead window —
/// confirmed against its real source, not guessed. `BufferingResource.stream(range:)` computes
/// `readRange = requestedRange.lowerBound ..< (requestedRange.lowerBound + 6 MiB)` and requests
/// exactly that range over HTTP, **without ever clamping it to the resource's known
/// `estimatedLength()`**. `ZIPFoundationArchiveFactory` reads the ZIP's end-of-central-directory
/// record from a `TailCachingResource` seeked to near the very end of the file, so this
/// oversized range request happens on almost every real book (whose length is rarely an exact
/// multiple of 6 MiB past that seek point) — confirmed live via NSLog against
/// books.ballhome.me: two different books both failed with HTTP 416 (Range Not Satisfiable) on
/// a range whose span was exactly 6 MiB − 1 byte. Downloading the whole file up front sidesteps
/// ranged reads entirely — the same workaround `ComicArchiveNormalizer` already needed for CBR
/// (which can't be range-streamed at all), just for a different underlying reason.
enum RemoteFileCache {
    enum DownloadError: Error {
        case badStatus(Int)
    }

    private static let cacheDir: URL = {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(
            "book-cache", isDirectory: true
        )
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }()

    /// Downloads `url` in full (with `authHeader` attached) and returns a local file URL named
    /// `u-<sha1(url)>.<extension>`. Cached by URL hash — a second open of the same book is free,
    /// nothing is re-downloaded.
    static func download(url: URL, authHeader: String?, extension ext: String) async throws -> URL {
        let cached = cacheDir.appendingPathComponent("u-\(sha1(url.absoluteString)).\(ext)")
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
            throw DownloadError.badStatus((response as? HTTPURLResponse)?.statusCode ?? -1)
        }

        try FileManager.default.copyItem(at: downloaded, to: cached)
        return cached
    }

    private static func sha1(_ string: String) -> String {
        Insecure.SHA1.hash(data: Data(string.utf8))
            .map { String(format: "%02x", $0) }
            .joined()
    }
}
