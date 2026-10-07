import Security
import XCTest
@testable import PadNote

/// Same-network pairing: the QR's certificate fingerprint must survive every hop
/// (QR -> profile -> task identity) and gate trust to exactly that leaf.
final class AgentCertificatePinTests: XCTestCase {
    /// A self-signed RSA certificate generated for this test only (no private key is kept).
    private static let certificateDER = Data(base64Encoded: "MIICqjCCAZICCQCGGCs4krO2ITANBgkqhkiG9w0BAQsFADAXMRUwEwYDVQQDDAxQYWROb3RlIFRlc3QwHhcNMjYwOTI5MTYzMDQzWhcNMzYwOTI2MTYzMDQzWjAXMRUwEwYDVQQDDAxQYWROb3RlIFRlc3QwggEiMA0GCSqGSIb3DQEBAQUAA4IBDwAwggEKAoIBAQDlS7qD+tdqhV8BWxrpgSWnDTLhN6tZ8Rwl+mN1jNBEhluX+x2gYAU5yHrRFTsTTkwQPubTTUqAykzRyiH9UOO4myj5hcrx6a3fHn+U+hU7fpYIlvdyKfa6VMi73G3DXR9QcAVDckDfqoSmagUgLVC9tTUE6VXt0rPxTdxTCE5gvIfxUDVKBhf2zr8EvoBVL6FhLary1f8YgV1aIAFvRKOjmnlmMHKg/WemI8VC/YcFLDpZEFxkhQA6xL6BYbm5paaycn47qfg05cn36lSE7X8o966eEdgUOqDJpxmRtM5q6pGe8DFuFh5WlwmGduRFgvNrgEtUQ4WI0n/YpEiPr97zAgMBAAEwDQYJKoZIhvcNAQELBQADggEBAJYs4exMRBQjQhifN5YKcZrRChL73DSWBS+gS30wbzOEIAiRdQG86jW0SQoKnesI2LRdGapQJ50TGXg9iSoZTT3U+JwTS9aZ7U9M/uxqxqdjt3sc9S+2eNn8a01T1ROjrbH1iJ3JPEN73zbzPlgAMsV7B7LqTiAf2MOPnFNqCiTmZ0nTJ98TS71B076BkLtsPi7sUU76AokgvHW/Pcn+t7I+qXMbIa3QYMXNQLOe7jb1FuRZqt83YnJ+TwxCx9ooNcGVgx5tpMs6UepYVfmdvckvBbV7VKhsz+XvAgWXnAULdUwL31MR5ePYswQuK4CD7zMjWrAatctBqHq/9xxYwmk=")!
    private static let certificateSHA256 = "8d5258a1873aea54295ab78e1286d331dd8ad49c8b9429588c58788b5e6c3da6"

    func testFingerprintIsSha256OfTheDerCertificate() throws {
        let certificate = try XCTUnwrap(SecCertificateCreateWithData(nil, Self.certificateDER as CFData))
        XCTAssertEqual(AgentCertificatePin.fingerprint(certificate), Self.certificateSHA256)
    }

    func testPinFormatIsStrict() throws {
        XCTAssertNil(try AgentCertificatePin.normalize(nil))
        XCTAssertNil(try AgentCertificatePin.normalize("  "))
        XCTAssertEqual(try AgentCertificatePin.normalize(Self.certificateSHA256.uppercased()), Self.certificateSHA256)
        for bad in ["abc", String(repeating: "z", count: 64), String(repeating: "a", count: 63)] {
            XCTAssertThrowsError(try AgentCertificatePin.normalize(bad), bad)
        }
    }

    func testTrustMatchesOnlyThePinnedLeaf() throws {
        let certificate = try XCTUnwrap(SecCertificateCreateWithData(nil, Self.certificateDER as CFData))
        var trust: SecTrust?
        XCTAssertEqual(SecTrustCreateWithCertificates(certificate, SecPolicyCreateBasicX509(), &trust), errSecSuccess)
        let serverTrust = try XCTUnwrap(trust)
        XCTAssertTrue(AgentCertificatePin.trustMatches(serverTrust, pin: Self.certificateSHA256))
        XCTAssertFalse(AgentCertificatePin.trustMatches(serverTrust, pin: String(repeating: "0", count: 64)))
    }

    func testPairingCodeCarriesThePinAndRefusesAMalformedOne() throws {
        let pinned = try AgentPairingCode(json: #"{"type":"padnote-pair","version":1,"url":"https://192.168.1.20:8767","bridge_id":"bridge-1","code":"opaque","cert_sha256":"\#(Self.certificateSHA256.uppercased())"}"#)
        XCTAssertEqual(pinned.certSHA256, Self.certificateSHA256)
        let unpinned = try AgentPairingCode(json: #"{"type":"padnote-pair","version":1,"url":"https://computer.example.ts.net","bridge_id":"bridge-1","code":"opaque"}"#)
        XCTAssertNil(unpinned.certSHA256)
        XCTAssertThrowsError(try AgentPairingCode(json: #"{"type":"padnote-pair","version":1,"url":"https://192.168.1.20:8767","bridge_id":"bridge-1","code":"opaque","cert_sha256":"not-a-pin"}"#))
    }

    func testThePinPersistsOnTheProfileAndIsDroppedWhenTheAddressChanges() throws {
        let suite = "padnote.pin.tests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let tokens = PinTestTokenStore()
        let store = AgentConnectionStore(defaults: defaults, keychain: tokens)
        let profile = try store.create(name: "Home", kind: .hermes, endpoint: "https://192.168.1.20:8767",
                                       token: "device-token", transport: .bridge, bridgeID: "bridge-1",
                                       instanceID: "instance-1", certSHA256: Self.certificateSHA256)
        let reloaded = try XCTUnwrap(AgentConnectionStore(defaults: defaults, keychain: tokens)
            .profiles().first)
        XCTAssertEqual(reloaded.certSHA256, Self.certificateSHA256)
        XCTAssertEqual(AgentConnectionConfig(profile: reloaded, token: "t").certSHA256, Self.certificateSHA256)
        XCTAssertEqual(AgentTaskConnectionIdentity(profile: reloaded).certSHA256, Self.certificateSHA256)

        let moved = try store.update(id: profile.id, expectedRevision: profile.revision, name: "Home",
                                     kind: .hermes, endpoint: "https://192.168.1.30:8767", token: "new-token",
                                     transport: .bridge, bridgeID: "bridge-1", instanceID: "instance-1")
        XCTAssertNil(moved.certSHA256, "a pin belongs to the paired address")
    }

    func testAProfileSavedBeforePinsExistedStillDecodes() throws {
        let legacy = #"{"id":"\#(UUID().uuidString)","name":"Old","kind":"hermes","endpoint":"https://computer.example.ts.net","credentialReference":"connection.x","transport":"bridge","revision":1,"capabilities":{}}"#
        let profile = try JSONDecoder().decode(AgentConnectionProfile.self, from: Data(legacy.utf8))
        XCTAssertNil(profile.certSHA256)
    }
}

private final class PinTestTokenStore: AgentTokenStore {
    var values: [String: String] = [:]
    func save(_ value: String, reference: String) throws { values[reference] = value }
    func read(reference: String) throws -> String? { values[reference] }
    func delete(reference: String) { values.removeValue(forKey: reference) }
}
