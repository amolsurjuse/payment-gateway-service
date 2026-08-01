# ElectraHub Payment Gateway Service

Provider-neutral routing, encrypted payment-method tokens, durable financial operations, and verified webhook ingestion.

## Current sandbox coverage

| Region | Provider | Available without legal onboarding | Adapter state |
|---|---|---|---|
| US, Canada, UK | Stripe | Anonymous, expiring developer sandbox | Authorize, partial/full capture, void, refund, inquiry, 3DS action, signed webhooks |
| Singapore | 2C2P | Documentation sample only; the published `JT01` key is currently rejected by the sandbox | Signed sandbox validation/inquiry and hosted checkout; private sandboxes request card-only `PREAUTH` |
| India | Razorpay | Code only; test keys require an account login | Order checkout, authorize webhook, capture, idempotent refund, inquiry, automatic authorization release tracking |
| Netherlands, Germany | Mollie | Code only; test key requires an eligible account | Hosted manual authorization, capture, void, refund, authenticated webhook re-fetch |
| Sweden and EU fallback | Adyen | Code only; test account approval is required | Checkout session, capture, cancel, refund, batched HMAC webhooks |

The 2C2P public demo does not expose the exchange keys needed for maintenance operations, so capture, void, and refund are intentionally unavailable in that profile. On 2026-08-01, both the adapter request and the provider's minimal published request returned `9042` (hash mismatch) with the key shown in 2C2P's JWT example; treat that value as documentation-only until 2C2P issues or republishes a working sandbox key. Even with a working demo key, the profile is not eligible for ElectraHub session execution because that lifecycle requires both `AUTHORIZE` and `CAPTURE`. No live-money connection is seeded or activated by Liquibase.

## Safety boundaries

- Gateway execution and token-vault APIs reject raw PAN and CVV values. Browser and mobile clients must tokenize with the provider SDK or hosted checkout.
- Provider tokens are AES-256-GCM encrypted with random nonces and account/connection-bound associated data.
- Webhook signatures are verified before insertion. Mollie callbacks are verified by fetching the referenced payment with the configured API key.
- Webhook payloads are not stored; only normalized fields and a SHA-256 payload hash are retained.
- Provider event IDs are unique per connection, and terminal operations cannot be downgraded by delayed events.
- Provider credentials and webhook secrets are write-only environment references. Server-side policy accepts only the canonical environment variable for that provider and purpose, and they are never returned by the admin API.
- Production provider activation and payment-service execution are disabled by default.

## Runtime secrets

Generate a vault key outside source control:

```powershell
$bytes = New-Object byte[] 32
[Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
[Convert]::ToBase64String($bytes)
```

Create an environment-specific Kubernetes secret after replacing the placeholders:

```powershell
kubectl -n dev create secret generic payment-gateway-provider-dev `
  --from-literal=APP_GATEWAY_TOKEN_ENCRYPTION_KEY='<base64-256-bit-key>' `
  --from-literal=APP_GATEWAY_STRIPE_CREDENTIAL='<test-secret-key>' `
  --from-literal=APP_GATEWAY_STRIPE_WEBHOOK_SECRET='<whsec-value>' `
  --from-literal=APP_GATEWAY_MOLLIE_CREDENTIAL='<test-api-key>' `
  --from-literal=APP_GATEWAY_RAZORPAY_CREDENTIAL='<test-key-json>' `
  --from-literal=APP_GATEWAY_RAZORPAY_WEBHOOK_SECRET='<webhook-secret>' `
  --from-literal=APP_GATEWAY_ADYEN_CREDENTIAL='<test-credential-json>' `
  --from-literal=APP_GATEWAY_ADYEN_WEBHOOK_SECRET='<hex-hmac-key>' `
  --from-literal=APP_GATEWAY_2C2P_CREDENTIAL='<private-sandbox-credential-json>' `
  --from-literal=APP_GATEWAY_2C2P_DEMO_SECRET_KEY='<published-demo-signing-key>' `
  --dry-run=client -o yaml | kubectl apply -f -
```

Use these write-only references when creating a gateway connection:

- Stripe credential: `env:APP_GATEWAY_STRIPE_CREDENTIAL`
- Stripe webhook: `env:APP_GATEWAY_STRIPE_WEBHOOK_SECRET`
- Mollie credential: `env:APP_GATEWAY_MOLLIE_CREDENTIAL`
- Razorpay credential and webhook: `env:APP_GATEWAY_RAZORPAY_CREDENTIAL`, `env:APP_GATEWAY_RAZORPAY_WEBHOOK_SECRET`
- Adyen credential and webhook: `env:APP_GATEWAY_ADYEN_CREDENTIAL`, `env:APP_GATEWAY_ADYEN_WEBHOOK_SECRET`
- 2C2P private sandbox credential: `env:APP_GATEWAY_2C2P_CREDENTIAL`
- 2C2P public demo: leave the connection credential reference blank; the adapter reads `APP_GATEWAY_2C2P_DEMO_SECRET_KEY` only for endpoint profile `2c2p-sandbox-sg-demo`.

Provider credential value formats:

```text
Stripe:   sk_test_... or {"secretKey":"sk_test_..."}
Razorpay: {"keyId":"rzp_test_...","keySecret":"..."}
Mollie:   test_... or {"apiKey":"test_..."}
Adyen:    {"apiKey":"...","merchantAccount":"...","clientKey":"...","countryCode":"SE","manualCaptureEnabled":true}
2C2P:     {"merchantId":"...","secretKey":"..."} (private merchant profile only)
```

Adyen's webhook secret is the hex HMAC key. Razorpay and Stripe use the webhook secret generated in their dashboards. A provider-issued 2C2P demo signing key is supplied through `APP_GATEWAY_2C2P_DEMO_SECRET_KEY` at runtime and is not committed. `TwoC2PLiveSandboxTest` is opt-in through `TWO_C2P_DEMO_INTEGRATION_KEY` so key rotation is detected without making routine builds depend on an external sandbox.

## Activation order

1. Create a sandbox connection with the matching endpoint profile (`stripe-sandbox`, `mollie-sandbox`, `razorpay-sandbox`, `adyen-sandbox`, `2c2p-sandbox`, or `2c2p-sandbox-sg-demo`).
2. Validate and activate the connection.
3. Create and activate the merchant account.
4. Create the payment route disabled, then enable it in a separately audited action.
5. For a Stripe `CARD_ON_FILE` route, configure the token-vault secret, tokenize in the provider UI/SDK, and register through payment-service `POST /api/v1/payment/cards/tokenized`.
6. Mollie, Razorpay, and Adyen use `HOSTED_CHECKOUT`: send `gatewayPaymentFlow=HOSTED_CHECKOUT`, omit `paymentMethodReference`, and provide an `electrahub://` URL or an HTTPS return URL on `electrahub.net`. All three routes require authorize, manual-capture, and capture capabilities. Keep 2C2P routes disabled because its RSA maintenance exchange is not implemented.
7. Add one network to `PAYMENT_GATEWAY_EXECUTION_PILOT_NETWORK_IDS`.
8. Set `PAYMENT_GATEWAY_EXECUTION_ENABLED=true` only in the sandbox environment.
9. Exercise authorize, customer action, capture/void, webhook, and reconciliation flows.
10. Keep `APP_GATEWAY_PRODUCTION_ENABLED=false` until merchant onboarding, PCI scope, terms, refund policy, and reconciliation ownership are approved. This kill switch blocks new production-provider validation, activation, routing, and financial operations while preserving read-only status reconciliation and webhook verification for in-flight recovery.

The legacy payment-service `POST /cards` route is migration-only and is not eligible for provider execution. New clients must use `POST /cards/tokenized`; only the resulting ElectraHub payment-method UUID is stored by payment-service.

Payment-service reads durable reconciliation state through the authenticated internal endpoint `GET /api/v1/gateway/internal/operations/{operationId}`. It never infers a successful charge from a session or receipt status.

Provider callbacks use:

```text
POST https://api.electrahub.net/payment-gateway/api/v1/gateway/webhooks/{connectionId}
```

Only that exact callback route is anonymous at the API gateway. Signature headers and request bodies are omitted from gateway logs.

## Sandbox cards and mobile checkout

- Provider test cards and mock bank pages are accepted only by sandbox credentials. A sandbox route can never be promoted by swapping a client-side key; the connection environment and server credential must both be changed and revalidated.
- India/Razorpay checkout is an interactive `SDK` action. The iOS client uses Razorpay's supported web checkout path and verifies the resulting order through this service before capture. Auto top-up fails closed when Razorpay requires customer action; unattended recurring funding needs a separately approved token/mandate product.
- Stripe 3DS actions without a redirect URL require Stripe's iOS SDK and client secret. Adyen Checkout sessions likewise require the Adyen iOS component. Until those packages are installed, the iOS client reports the missing provider SDK and charging does not start.
- `MOCK` execution is disabled by default and is rejected by the production deployment. No provider operation may fall back to a mock adapter after route or credential failure.
