import AVFoundation
import Foundation
import UniformTypeIdentifiers

/// Attaches an `Authorization` header to an `AVPlayer` stream — issue #114. Readium's own
/// `EpubReaderViewController`/`PdfReaderViewController` get this "for free" via
/// `DefaultHTTPClient(additionalHeaders:)`, but `AVAssetResourceLoaderDelegate` is the real,
/// documented mechanism for `AVURLAsset`: the older `AVURLAssetHTTPHeaderFieldsKey` init option
/// still works in practice but is explicitly **not** a supported/documented API. The catch —
/// confirmed against real guidance, not assumed — is that the delegate only fires for a
/// *custom* URL scheme; a plain `http(s)` URL never reaches it at all. So this rewrites the
/// scheme to [scheme] on the way in and restores the real `https` scheme on the way out, for
/// every request AVFoundation makes as it buffers/seeks through the file.
///
/// Forwards each `AVAssetResourceLoadingRequest` as a real ranged `URLSession` request (a
/// `Range: bytes=...` header built from `dataRequest.requestedOffset`/`.requestedLength`),
/// fills in `contentInformationRequest` from the response headers (content type via
/// `UTType(mimeType:)`, length from `Content-Range` when present, else `Content-Length`), and
/// streams received chunks straight into `dataRequest.respond(with:)` as they arrive — the
/// same progressive-buffering shape a real HTTP audio player needs, not a full-file download
/// first.
final class AudiobookStreamLoader: NSObject, AVAssetResourceLoaderDelegate, URLSessionDataDelegate {
    static let scheme = "dexxicon-stream"

    /// The URL to hand to `AVURLAsset` — same host/path/query as [remoteURL], scheme swapped
    /// so `AVAssetResourceLoader` actually calls this delegate.
    static func loaderURL(for remoteURL: URL) -> URL {
        var components = URLComponents(url: remoteURL, resolvingAgainstBaseURL: false)!
        components.scheme = scheme
        return components.url ?? remoteURL
    }

    private let authHeader: String?
    /// Both `AVAssetResourceLoaderDelegate` callbacks (registered against this queue in
    /// [attach]) and `URLSessionDataDelegate` callbacks (via `operationQueue.underlyingQueue`
    /// below) run serialized on this one queue — `requestsByTaskId` is mutated from both, and
    /// a plain `Dictionary` isn't safe to mutate from two independent queues at once.
    private let callbackQueue = DispatchQueue(label: "audiobook-stream-loader")
    private lazy var session: URLSession = {
        let operationQueue = OperationQueue()
        operationQueue.underlyingQueue = callbackQueue
        return URLSession(configuration: .default, delegate: self, delegateQueue: operationQueue)
    }()
    private var requestsByTaskId: [Int: AVAssetResourceLoadingRequest] = [:]

    init(authHeader: String?) {
        self.authHeader = authHeader
    }

    /// Registers this loader against [asset] on the same serial queue its own `URLSession`
    /// delegate callbacks run on — see [callbackQueue]'s own doc comment for why that matters.
    func attach(to asset: AVURLAsset) {
        asset.resourceLoader.setDelegate(self, queue: callbackQueue)
    }

    private func realURL(from loaderURL: URL) -> URL? {
        var components = URLComponents(url: loaderURL, resolvingAgainstBaseURL: false)
        components?.scheme = "https"
        return components?.url
    }

    // MARK: AVAssetResourceLoaderDelegate

    func resourceLoader(
        _ resourceLoader: AVAssetResourceLoader,
        shouldWaitForLoadingOfRequestedResource loadingRequest: AVAssetResourceLoadingRequest
    ) -> Bool {
        guard let loaderURL = loadingRequest.request.url, let url = realURL(from: loaderURL) else {
            return false
        }

        var request = URLRequest(url: url)
        if let authHeader { request.setValue(authHeader, forHTTPHeaderField: "Authorization") }
        if let dataRequest = loadingRequest.dataRequest {
            let start = dataRequest.requestedOffset
            let length = Int64(dataRequest.requestedLength)
            // A negative/zero length is AVFoundation asking for "everything from start" —
            // no Range header at all lets the server answer with the full remaining body.
            if length > 0 {
                request.setValue("bytes=\(start)-\(start + length - 1)", forHTTPHeaderField: "Range")
            } else if start > 0 {
                request.setValue("bytes=\(start)-", forHTTPHeaderField: "Range")
            }
        }

        let task = session.dataTask(with: request)
        requestsByTaskId[task.taskIdentifier] = loadingRequest
        task.resume()
        return true
    }

    func resourceLoader(
        _ resourceLoader: AVAssetResourceLoader,
        didCancel loadingRequest: AVAssetResourceLoadingRequest
    ) {
        // The task's own didComplete callback (with a cancellation error) does the actual
        // bookkeeping cleanup — nothing else to do here.
    }

    // MARK: URLSessionDataDelegate

    func urlSession(
        _ session: URLSession,
        dataTask: URLSessionDataTask,
        didReceive response: URLResponse,
        completionHandler: @escaping (URLSession.ResponseDisposition) -> Void
    ) {
        guard
            let loadingRequest = requestsByTaskId[dataTask.taskIdentifier],
            let http = response as? HTTPURLResponse,
            (200..<300).contains(http.statusCode)
        else {
            completionHandler(.cancel)
            return
        }

        if let info = loadingRequest.contentInformationRequest {
            info.isByteRangeAccessSupported = http.statusCode == 206
                || http.value(forHTTPHeaderField: "Accept-Ranges") == "bytes"
            info.contentType = http.mimeType.flatMap { UTType(mimeType: $0)?.identifier } ?? UTType.audio.identifier
            if let contentRange = http.value(forHTTPHeaderField: "Content-Range"),
               let totalString = contentRange.split(separator: "/").last,
               let total = Int64(totalString) {
                info.contentLength = total
            } else {
                info.contentLength = http.expectedContentLength
            }
        }

        completionHandler(.allow)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        requestsByTaskId[dataTask.taskIdentifier]?.dataRequest?.respond(with: data)
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let loadingRequest = requestsByTaskId.removeValue(forKey: task.taskIdentifier) else { return }
        if let error {
            loadingRequest.finishLoading(with: error)
        } else {
            loadingRequest.finishLoading()
        }
    }
}
