# Round 4 — Panel consensus (quality / simplicity / ux)

Scope: master @ 33ef6e2 (v4.4). Reviewer reports: round4-{quality,architecture,robustness,theming}.md
(simplicity + ux reports delivered via agent notifications; key findings folded in below.)

## Consensus items implemented in v4.5 (each confirmed by >= 2 reviewers)
1. **SettingsActivity**: `WorkerScope.spawn { ... }.start()` double-starts the thread ->
   IllegalThreadStateException on "Test connection". Removed trailing `.start()`.
2. **ImageLoader**: bounded queue (24) + default AbortPolicy -> RejectedExecutionException on the
   27th queued poster. Switched to `DiscardOldestPolicy()`.
3. **Catalog.tick()**: simulated ticker advanced *arr-backed requests too, fighting
   LiveRepository (status flapping). Now skips `external.isNotBlank()` requests.
4. **RequestsActivity**: `lastSignature = ""` collided with the empty-store signature, so the
   empty state never rendered. Now a `String? = null` sentinel; reset to null after delete.
5. **LiveRepository.refreshStatuses()**: transient *arr fetch failure produced empty lists that
   downgraded AVAILABLE -> PENDING. Each *arr is now fetched as one `Result`; on failure the
   request keeps its current status.

## Deferred (not consensus / out of scope for hotfix)
- Prowlarr magnet flakiness: user said "nevermind for now".
- Further theming polish: parked until user is back.
