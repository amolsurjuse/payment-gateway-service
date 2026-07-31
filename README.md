# ElectraHub Payment Gateway Service

Provider-neutral routing, encrypted payment-method tokens, durable financial operations, and verified webhook ingestion.

## Current sandbox coverage

| Region | Provider | Available without legal onboarding | Adapter state |
|---|---|---|---|
| US, Canada, UK | Stripe | Anonymous, expiring developer sandbox | Authorize, partial/full capture, void, refund, inquiry, 3DS action, signed webhooks |
| Singapore | 2C2P | Public `JT01` hosted-payment demo | Hosted SGD checkout, inquiry, signed backend response |
| India | Razorpay | Code only; test keys require an account login | Order checkout, authorize webhook, capture, refund, inquiry |
| Netherlands, Germany | Mollie | Code only; test key requires an eligible account | Hosted manual authorization, capture, void, refund, authenticated webhook re-fetch |
| Sweden and EU fallback | Adyen | Code only; test account approval is required | Checkout session, capture, cancel, refund, batched HMAC webhooks |

The 2C2P public demo does not expose the exchange keys needed for maintenance operations, so capture, void, and refund are intentionally unavailable in that profile. No live-money connection is seeded or activated by Liquibase.

## Safety boundaries

- Gateway execution and token-vault APIs reject raw PAN and CVV values. Browser and mobile clients must tokenize with the provider SDK or hosted checkout.
- Provider tokens are AES-256-GCM encrypted with random nonces and account/connection-bound associated data.
- Webhook signatures are verified before insertion. Mollie callbacks are verified by fetching the referenced payment with the configured API key.
- Webhook payloads are not stored; only normalized fields and a SHA-256 payload hash are retained.
- Provider event IDs are unique per connection, and terminal operations cannot be downgraded by delayed events.
- Provider credentials and webhook secrets are write-only environment references. They are never returned by the admin API.
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
  --dry-run=client -o yaml | kubectl apply -f -
```

Use these write-only references when creating a gateway connection:

- Credential: `env:APP_GATEWAY_STRIPE_CREDENTIAL`
- Webhook: `env:APP_GATEWAY_STRIPE_WEBHOOK_SECRET`

Provider credential value formats:

```text
Stripe:   sk_test_... or {"secretKey":"sk_test_..."}
Razorpay: {"keyId":"rzp_test_...","keySecret":"..."}
Mollie:   test_... or {"apiKey":"test_..."}
Adyen:    {"apiKey":"...","merchantAccount":"...","clientKey":"...","countryCode":"SE","manualCaptureEnabled":true}
2C2P:     {"merchantId":"...","secretKey":"..."} (private merchant profile only)
```

Adyen's webhook secret is the hex HMAC key. Razorpay and Stripe use the webhook secret generated in their dashboards. The 2C2P public demo signing key is supplied through `APP_GATEWAY_2C2P_DEMO_SECRET_KEY` at runtime and is not committed.

## Activation order

1. Create a sandbox connection with the matching endpoint profile.
2. Validate and activate the connection.
3. Create and activate the merchant account.
4. Create the payment route disabled, then enable it in a separately audited action.
5. Configure the token-vault secret in the gateway deployment.
6. Tokenize a card in the provider UI/SDK and register it through payment-service `POST /api/v1/payment/cards/tokenized`.
7. Add one network to `PAYMENT_GATEWAY_EXECUTION_PILOT_NETWORK_IDS`.
8. Set `PAYMENT_GATEWAY_EXECUTION_ENABLED=true` only in the sandbox environment.
9. Exercise authorize, customer action, capture/void, webhook, and reconciliation flows.
10. Keep `APP_GATEWAY_PRODUCTION_ENABLED=false` until merchant onboarding, PCI scope, terms, refund policy, and reconciliation ownership are approved.

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
