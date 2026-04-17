package io.jenkins.plugins.pam360;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Thin HTTP client for the ManageEngine PAM360 REST API.
 *
 * PAM360 password endpoint:
 *   GET {baseUrl}/api/pam360/resources/{resourceId}/accounts/{accountId}/password
 *   Header: AUTHTOKEN: {apiToken}
 *
 * Success response shape:
 *   {"operation":{"result":{"status":"SUCCESS","message":"..."},"Details":{"PASSWORD":"<value>"}}}
 *
 * The HttpClient is not stored as a field so that PAM360StepExecution stays
 * serializable for pipeline durability.
 */
public class PAM360Client {

    private final String baseUrl;
    private final HttpClient httpClient;

    public PAM360Client(String baseUrl) {
        this(baseUrl, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build());
    }

    /** Package-private constructor used by tests to inject a custom HttpClient. */
    PAM360Client(String baseUrl, HttpClient httpClient) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.httpClient = httpClient;
    }

    /**
     * Fetches the password for a given resource/account from PAM360.
     *
     * @param apiToken   the PAM360 AUTHTOKEN (retrieved from a Jenkins Secret text credential)
     * @param resourceId PAM360 resource ID
     * @param accountId  PAM360 account ID within the resource
     * @return the plaintext password
     */
    public String fetchPassword(String apiToken, String resourceId, String accountId)
            throws IOException, InterruptedException {

        String url = baseUrl + "/api/pam360/resources/" + resourceId
                + "/accounts/" + accountId + "/password";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("AUTHTOKEN", apiToken)
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("PAM360 returned HTTP " + response.statusCode()
                    + " for resource " + resourceId + "/" + accountId + ": " + response.body());
        }

        return parsePassword(response.body());
    }

    private String parsePassword(String json) throws IOException {
        String status = extractJsonStringValue(json, "status");
        if ("FAILED".equals(status)) {
            String msg = extractJsonStringValue(json, "message");
            throw new IOException("PAM360 API error: " + (msg != null ? msg : "unknown error"));
        }

        String password = extractJsonStringValue(json, "PASSWORD");
        if (password == null) {
            throw new IOException("PASSWORD field not found in PAM360 response: " + json);
        }
        return password;
    }

    /**
     * Minimal JSON string-value extractor — avoids pulling in a JSON library.
     * Handles JSON string escapes (\", \\, \n, \r, \t, \/).
     */
    String extractJsonStringValue(String json, String key) {
        String search = "\"" + key + "\"";
        int keyPos = json.indexOf(search);
        if (keyPos < 0) {
            return null;
        }

        int colonPos = json.indexOf(':', keyPos + search.length());
        if (colonPos < 0) {
            return null;
        }

        // Skip whitespace to find opening quote
        int i = colonPos + 1;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) {
            i++;
        }
        if (i >= json.length() || json.charAt(i) != '"') {
            return null;
        }
        i++; // step past opening quote

        // Read the string value, handling escape sequences
        StringBuilder value = new StringBuilder();
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(i + 1);
                switch (next) {
                    case '"':  value.append('"');  break;
                    case '\\': value.append('\\'); break;
                    case '/':  value.append('/');  break;
                    case 'n':  value.append('\n'); break;
                    case 'r':  value.append('\r'); break;
                    case 't':  value.append('\t'); break;
                    default:   value.append('\\').append(next); break;
                }
                i += 2;
            } else if (c == '"') {
                break; // closing quote
            } else {
                value.append(c);
                i++;
            }
        }
        return value.toString();
    }
}
