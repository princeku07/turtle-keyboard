import Foundation

/// Shared base URL for the `turtle-worker` JSON API. Mirrors Android's
/// `overlay/OverlayUrls.java` — every overlay client (Poll, Wyr, future)
/// points here so a single edit re-targets a local `wrangler dev` instance.
///
/// For local Worker iteration on a simulator: `http://127.0.0.1:8787`.
/// For a device on the same WiFi: `http://<dev-LAN-IP>:8787` plus an ATS
/// exception for that host in both Info.plists.
enum WorkerUrls {
    static let workerBaseURL = "https://turtle-worker.trtlk.workers.dev"
}
