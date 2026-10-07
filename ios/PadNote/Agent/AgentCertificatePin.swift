import CryptoKit
import Foundation
import Security

/// Same-network connection: trust exactly the certificate named in the pairing QR.
///
/// The connection assistant generates its own certificate and puts its SHA-256
/// into the one-time QR. A pinned session accepts that one leaf and nothing
/// else -- no CA, no hostname -- so a tablet on the same Wi-Fi needs no
/// third-party network software. Without a pin the system's normal HTTPS trust
/// evaluation applies unchanged.
public enum AgentCertificatePin {
    /// Normalizes a pin, or returns nil for none; a malformed value is refused.
    public static func normalize(_ value: String?) throws -> String? {
        guard let raw = value?.trimmingCharacters(in: .whitespacesAndNewlines), !raw.isEmpty else { return nil }
        let lower = raw.lowercased()
        guard lower.count == 64, lower.allSatisfy({ $0.isHexDigit }) else {
            throw AgentConnectionError.invalidCertificatePin
        }
        return lower
    }

    public static func fingerprint(_ certificate: SecCertificate) -> String {
        let der = SecCertificateCopyData(certificate) as Data
        return SHA256.hash(data: der).map { String(format: "%02x", $0) }.joined()
    }

    /// An ephemeral session that refuses redirects and, when pinned, trusts only that leaf.
    static func session(pin: String?) -> (URLSession, URLSessionDelegate) {
        let delegate = PinnedSessionDelegate(pin: pin)
        let configuration = URLSessionConfiguration.ephemeral
        configuration.httpCookieStorage = nil
        configuration.urlCache = nil
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        return (URLSession(configuration: configuration, delegate: delegate, delegateQueue: nil), delegate)
    }

    /// Whether a server trust presents exactly the pinned leaf certificate.
    static func trustMatches(_ trust: SecTrust, pin: String) -> Bool {
        guard let chain = SecTrustCopyCertificateChain(trust) as? [SecCertificate],
              let leaf = chain.first else { return false }
        let actual = Data(fingerprint(leaf).utf8)
        let expected = Data(pin.utf8)
        // Constant-time comparison of two equal-length ASCII hex strings.
        guard actual.count == expected.count else { return false }
        return zip(actual, expected).reduce(UInt8(0)) { $0 | ($1.0 ^ $1.1) } == 0
    }

    final class PinnedSessionDelegate: NSObject, URLSessionTaskDelegate {
        private let pin: String?

        init(pin: String?) { self.pin = pin }

        func urlSession(
            _ session: URLSession,
            task: URLSessionTask,
            willPerformHTTPRedirection response: HTTPURLResponse,
            newRequest request: URLRequest,
            completionHandler: @escaping (URLRequest?) -> Void
        ) {
            completionHandler(nil)
        }

        func urlSession(
            _ session: URLSession,
            task: URLSessionTask,
            didReceive challenge: URLAuthenticationChallenge,
            completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void
        ) {
            guard let pin,
                  challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust,
                  let trust = challenge.protectionSpace.serverTrust else {
                completionHandler(.performDefaultHandling, nil)
                return
            }
            if AgentCertificatePin.trustMatches(trust, pin: pin) {
                completionHandler(.useCredential, URLCredential(trust: trust))
            } else {
                completionHandler(.cancelAuthenticationChallenge, nil)
            }
        }
    }
}
