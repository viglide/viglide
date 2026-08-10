# Revolut X Integration Task Definition

## Product Requirements Document (PRD)

### 1. Objective
Integrate `viglide` with the Revolut X Crypto Exchange API to allow users to trade crypto directly using their own Revolut accounts. The integration will enable automated placing of orders (such as limit or market orders) and retrieving market data.

### 2. Target Audience
Users of the `viglide` platform who have active Revolut accounts and wish to leverage automated trading strategies using their personal funds.

### 3. Core Features & Use Cases
1. **Public Market Data Retrieval:** Access public Revolut X endpoints (e.g., `/public/order-book/BTC-USD`) to fetch order books and ticker data without requiring authentication.
2. **Secure Authentication:** Support Ed25519 key pair-based authentication required by Revolut X API.
3. **Automated Order Placement:** Place, modify, and track spot orders programmatically via Revolut X authenticated endpoints.
4. **Order Status Management:** Query active orders and order history to maintain trading state in `viglide`.

### 4. Security & Privacy
- **Credential Storage:** User API Keys and Ed25519 Private Keys must **never** be hardcoded or stored in plain text. They should be loaded securely via environment variables or encrypted configuration files.
- **Permissions:** Recommend users generate "per-purpose" API keys in the Revolut X portal (e.g., read-only for monitoring, and spot-trade only for automated execution).

### 5. Open Source Feasibility
**Can this be developed in the open-source part of the system?**
**Yes.** The integration logic consists entirely of a standardized API client implementation. It acts as an agnostic wrapper around the public Revolut X REST API and does not contain any proprietary trading strategies, private user data, or trade secrets. All secrets will be injected at runtime by the user.

---

## Technical Low-Level Design (LLD)

### 1. Module Structure
Create a dedicated package or submodule to isolate the Revolut X client logic.
- **Path:** `viglide-core/src/main/java/com/viglide/revolutx/` (or a separate `viglide-revolut-x` gradle module).
- **Core Components:**
  - `RevolutXPublicClient`: For unauthenticated endpoints (market data).
  - `RevolutXAuthenticatedClient`: For endpoints requiring authentication (account, trading).
  - `RevolutXAuthenticator`: Handles the custom Ed25519 signature generation.

### 2. Authentication Implementation
Revolut X uses a custom Ed25519 signature scheme. Every authenticated request must include three headers:
- `X-Revx-API-Key`: 64-character alphanumeric string.
- `X-Revx-Timestamp`: Current Unix timestamp in milliseconds.
- `X-Revx-Signature`: Base64-encoded signature of the request payload.

**Signature Generation Algorithm:**
1. Concatenate strings without separators: `Timestamp` + `HTTP Method (uppercase)` + `Request Path (from /api)` + `Query String (without ?)` + `Request Body (minified JSON)`.
2. Example Message: `1765360896219POST/api/1.0/orders{"client_order_id":"...","symbol":"BTC-USD","side":"BUY",...}`
3. Sign the message using the Ed25519 private key (from `private.pem`).
4. Base64 encode the resulting signature.

**Required Dependency:**
Add a cryptography provider like BouncyCastle (`org.bouncycastle:bcprov-jdk15on`) or `net.i2p.crypto:eddsa` to handle Ed25519 signing in Java.

### 3. API Client & Order Placement
**HTTP Client:** Use the existing HTTP client standard in `viglide` (e.g., Java `HttpClient`, OkHttp, or Ktor).

**Order Placement Endpoint:**
- **URL:** `POST https://revx.revolut.com/api/1.0/orders` (Base URL: `https://revx.revolut.com/api/`)
- **Request Body (Limit Order Example):**
  ```json
  {
    "client_order_id": "<uuid>",
    "symbol": "BTC-USD",
    "side": "BUY",
    "order_configuration": {
      "limit": {
        "base_size": "0.1",
        "price": "90000.1"
      }
    }
  }
  ```

### 4. Step-by-Step Execution Plan
1. **Dependencies:** Add required cryptographic libraries to `build.gradle.kts` for Ed25519 support.
2. **Key Management:** Implement a utility to parse Ed25519 `.pem` files and extract the raw private key bytes.
3. **Authentication Interceptor:** Create an HTTP interceptor or filter that automatically computes the `X-Revx-Signature` and injects the authentication headers into outgoing requests.
4. **Data Models:** Define Java/Kotlin Data Classes for Request/Response bodies (e.g., `OrderRequest`, `OrderResponse`, `OrderBook`).
5. **Client Implementation:** Implement the `placeOrder`, `cancelOrder`, and `getActiveOrders` methods.
6. **Testing:** Write unit tests for the `RevolutXAuthenticator` to ensure the concatenation and Base64 Ed25519 signing matches Revolut's exact specifications.
