# Partner bank integration

Proposed provider-neutral boundary. [RevenueCat publicly names Core Bank](https://www.revenuecat.com/early-payouts); no private contract or Core Bank integration specification has been supplied. The interfaces here are our application contracts, not claimed bank endpoints. Provider examples cited below illustrate requirements to confirm during diligence.

## What belongs here

The adapter handles authentication, account/beneficiary setup, payload mapping, provider idempotency, rate limits, transfer submission, cancellation requests where supported, transaction-feed retrieval, and verified notifications. It normalizes evidence without erasing the original provider status or payload.

The domain owns eligibility, holds, principal/fee calculation, payment intent, and ledger policy. The bank executes through supported rails and applies its own onboarding and transaction controls. A successful account-opening API response does not necessarily mean an account is verified or permitted to fund.

## Provider capability contract

| Capability | Must be established before enabling it |
| --- | --- |
| Business onboarding | Required entity/owner data, approval lifecycle, prohibited activities, responsibility split, review and suspension events |
| Account structure | Ownership, permitted funds use, collection-account attribution, reporting access, account closure behavior |
| Beneficiary verification | Verification evidence, versioning, allowed payout destinations, changes and re-approval |
| Transfer idempotency | Scope, payload mismatch behavior, retention duration, cross-endpoint behavior, duplicate-request response |
| Unknown-result recovery | Safe retry semantics, lookup by operation/reference, retention and consistency of lookup results |
| Rail execution | Currency, cutoff timezone, holidays, settlement definition, cancellation window, returns/reversals, limits, fees |
| Evidence | Authenticated callbacks, event IDs, posted transaction IDs, pagination, statement/report availability and revisions |
| Availability | Rate limits, outage procedures, sandbox fidelity, support escalation and exception SLAs |

If safe retry and reliable recovery are unavailable, automatically executing ambiguous operations is a launch blocker. Do not switch banks to retry a possibly executed payment; cross-provider idempotency does not exist just because each provider supports a key.

## Proposed Kotlin-facing interface

```kotlin
// Design sketch only; these types are not added to the running application.
interface BankGateway {
    suspend fun submit(command: FrozenTransferCommand): SubmissionObservation
    suspend fun recover(operation: ProviderOperationRef): RecoveryObservation
    suspend fun requestCancellation(operation: ProviderOperationRef): CancellationObservation
    suspend fun transactions(account: BankAccountRef, cursor: String?): TransactionPage
    suspend fun accountState(account: BankAccountRef): BankAccountObservation
}

sealed interface SubmissionObservation {
    data class Accepted(val providerTransferId: String, val evidenceRef: String) : SubmissionObservation
    data class Rejected(val code: String, val evidenceRef: String) : SubmissionObservation
    data class Unknown(val reason: String) : SubmissionObservation
}
```

`FrozenTransferCommand` includes internal operation ID, provider idempotency key, source account, verified beneficiary version, currency, minor-unit amount, rail, purpose, and accepted scheduling terms. No risk score or recomputable amount appears in the adapter. The adapter cannot select a different beneficiary on retry.

`RecoveryObservation` distinguishes found, definitively not executed, temporarily not found, and unknown. An eventually consistent "not found" must not be mapped to definitive failure. Persist request hashes and redacted request/response evidence. Never log credentials or full account numbers.

## Submission and retry semantics

1. Persist provider key and canonical payload before the first network call.
2. Classify a response by the provider contract. A success may mean only accepted. A timeout, connection break, or some 5xx responses may leave execution unknown. Only explicit provider evidence can establish rejection/nonexecution.
3. For recoverable transport errors, use the same immutable request and key if the provider guarantees this is safe. Back off with jitter and a deadline; do not create a new business operation when retrying.
4. If the provider requires lookup/recovery, follow that procedure. Retain state while results are inconclusive. Exhausted retry budgets create an operations case and a paused workflow, not a released reservation.
5. After key expiry, unknown requests need operator/provider resolution; sending the old key may create a second operation at some providers.

Increase documents stable-key replay and conflict on key reuse with a different object; Stripe documents a different retention/result behavior. Therefore the adapter must encode the chosen provider's actual contract rather than assume universal idempotency semantics. [Increase idempotency](https://www.increase.com/documentation/idempotency-keys), [Stripe idempotency](https://docs.stripe.com/api/idempotent_requests)

## Webhooks, polling, and statement evidence

- Verify source authentication against the raw request, including signatures/timestamps/replay controls where the provider specifies them. Bind provider account to the correct tenant and bank environment.
- Persist a raw evidence reference and unique inbox identity before returning success. Process asynchronously. If durable persistence fails, return a retryable failure rather than falsely acknowledge.
- Handle duplicate delivery and older observations without repeating financial effects. When necessary, retrieve current resource state, while retaining the original event for history.
- Use overlapping transaction-feed polling windows and stable transaction IDs. Persist cursors only after the whole page is durable. Advance resumable watermarks carefully on backfills.
- Reconcile against statements independently. Notifications are operational signals; their presence or absence is not the entire cash ledger.

Stripe explicitly documents duplicate events, unordered delivery, and signature verification; it is an example of why the adapter must define these behaviors. [Stripe webhooks](https://docs.stripe.com/webhooks)

Semantic deduplication is broader than event-ID deduplication: two different notifications and a statement may describe the same bank debit. Post it once using `(provider, bank_account, provider_transaction_id, posting_role)` or another verified stable identity. If two feeds cannot be correlated reliably, open a case instead of guessing.

## Account and destination changes

`BankConnection`: `PENDING_VERIFICATION → ACTIVE → RESTRICTED | CLOSED`, with explicit evidence for reactivation. Store credentials by secret reference and version; rotate without changing historical evidence. Revoked credentials stop new calls and raise operational alerts.

`PayoutDestination` is versioned. Changing it requires authorization, verification and any policy-defined hold/review. Existing orders remain bound to their frozen version. A changed destination invalidates queued authorization; create a fresh reviewed instruction only after proving the earlier one was not sent or was resolved. Do not silently reroute a dispatched transfer.

## Scheduling and cash availability

Maintain a provider/rail business calendar, cutoff, timezone, holiday version, and submission lead time. Display expected arrival as an estimate grounded in that contract. Crossing a cutoff creates a new scheduled execution date and may require refreshed risk approval. Do not promise that every rail is instant or every payment can be canceled.

Track both owned ledger cash net of unposted commitments and provider available cash. Use the more conservative verified bound, with an operational buffer. Avoid subtracting a provider's own pending hold twice: distinguish posted cash, provider encumbrances, and our reservations. Collection-account customer liabilities are not unrestricted funding liquidity.

## Isolation and sandbox acceptance

Separate production and sandbox credentials, account IDs, webhook routes, storage prefixes, and permissions. Disable unsupported capabilities explicitly. Use one configured bank route initially. Additional providers require validated routing, independent liquidity, compatible account ownership, reconciled cutover, and a rule that in-flight unknown operations stay with their original provider.

Contract tests must cover accepted/settled separation, response loss after execution, retry with the same key, different payload conflict, duplicate/out-of-order callbacks, returns after settlement, partial amount facts, cancellation failure, credential revocation, rate limiting, pagination restart, and statement-to-event deduplication. A sandbox does not prove rail finality or production cutoff behavior; record the provider confirmations needed for launch.
