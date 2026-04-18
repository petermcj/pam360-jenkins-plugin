# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this plugin does

A Jenkins plugin that fetches credentials from **ManageEngine PAM360** (Privileged Access Management) and exposes them as environment variables inside a pipeline block.

```groovy
withPAM360(credentialId: 'pam360-api-token',   // Jenkins Secret text credential
           resourceId:   '42',                  // PAM360 resource ID
           accountId:    '7',                   // PAM360 account ID
           variable:     'DB_PASSWORD') {       // env var name for the block
    sh 'echo $DB_PASSWORD'
}
```

The **API token is per-step**, not global. Each `withPAM360` call references its own Jenkins *Secret text* credential that holds the PAM360 `AUTHTOKEN` for that resource. The PAM360 base URL is set globally in **Manage Jenkins → PAM360 Configuration** and can be overridden per-step with `apiUrl`.

## Build commands

```bash
# Full build (compile + test + package)
mvn verify

# Build without tests
mvn package -DskipTests

# Run all tests
mvn test

# Run a single test class
mvn test -Dtest=PAM360ClientTest

# Run a single test method
mvn test -Dtest=PAM360ClientTest#fetchPassword_returnsPassword_onSuccessResponse

# Start a local Jenkins with the plugin installed (opens on http://localhost:8080)
mvn hpi:run
```

## Architecture

The plugin targets **Jenkins 2.504.3 LTS** (Java 17+) and is a standard Jenkins HPI project with four Java classes and matching Jelly UI files.

### Class responsibilities

| Class | Purpose |
|---|---|
| `PAM360GlobalConfiguration` | `GlobalConfiguration` extension — persists the default PAM360 base URL to Jenkins' `config.xml`. |
| `PAM360Client` | Plain Java HTTP client (`java.net.http.HttpClient`). Calls `GET {baseUrl}/api/pam360/resources/{rid}/accounts/{aid}/password` with `AUTHTOKEN` header, parses the JSON response without a JSON library. |
| `PAM360Step` | `Step` descriptor — declares the `withPAM360` DSL function, its parameters (`@DataBoundConstructor` + `@DataBoundSetter`), and the credential dropdown (`doFillCredentialIdItems`). |
| `PAM360StepExecution` | `StepExecution` — runs on the step's start: resolves URL, looks up the `StringCredentials` token, calls `PAM360Client`, then invokes the body block with an `EnvironmentExpander` that injects the password. Must remain serializable for pipeline durability (no `HttpClient` stored as a field). |

### Key design decisions

- **No JSON library**: `PAM360Client.extractJsonStringValue` hand-parses the fixed PAM360 response shape. This keeps the dependency footprint minimal.
- **Token per resource**: The `credentialId` parameter points to a Jenkins *Secret text* credential, so each resource fetch can use a different PAM360 API token without sharing credentials.
- **`EnvironmentExpander` instead of direct `EnvVars` mutation**: Follows the pipeline-safe pattern; the variable is only visible inside the `withPAM360` block and is not leaked to subsequent steps.
- **`PAM360StepExecution` is serializable**: `PAM360Client` is created in `start()`, not stored as a field. `SingleVarExpander` implements `Serializable`.

## Testing approach (TDD)

Tests are written first. There are two test classes:

- **`PAM360ClientTest`** — pure unit tests, no Jenkins. Uses WireMock to stub the PAM360 HTTP endpoint.  
- **`PAM360StepTest`** — integration tests with an embedded Jenkins (`JenkinsRule`). Uses WireMock for the PAM360 API. Tests cover the happy path, per-step URL override, env var scoping, missing credential, missing URL, and PAM360 API error.

## Dependency policy

Keep runtime dependencies to the minimum already declared in `pom.xml`:
- `workflow-step-api` (pipeline step framework)
- `credentials` + `plain-credentials` (Jenkins credential framework; `StringCredentials` is the token holder)

Do **not** add general-purpose HTTP or JSON libraries. Do **not** add logging frameworks — use `TaskListener` for build-log output.

## PAM360 API contract

```
GET {baseUrl}/api/pam360/resources/{resourceId}/accounts/{accountId}/password
AUTHTOKEN: <token>

200 OK
{
  "operation": {
    "result": { "status": "SUCCESS", "message": "..." },
    "Details": { "PASSWORD": "<plaintext-password>" }
  }
}

200 OK  (error case)
{
  "operation": {
    "result": { "status": "FAILED", "message": "<reason>" }
  }
}
```
