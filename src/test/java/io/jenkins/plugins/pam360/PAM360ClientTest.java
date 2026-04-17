package io.jenkins.plugins.pam360;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.Assert.*;

/**
 * Unit tests for PAM360Client.
 * No Jenkins instance — HTTP interactions are stubbed with WireMock.
 */
public class PAM360ClientTest {

    private WireMockServer wireMock;
    private String baseUrl;

    @Before
    public void startWireMock() {
        wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMock.start();
        baseUrl = "http://localhost:" + wireMock.port();
    }

    @After
    public void stopWireMock() {
        wireMock.stop();
    }

    // --- fetchPassword success ---

    @Test
    public void fetchPassword_returnsPassword_onSuccessResponse() throws Exception {
        wireMock.stubFor(get(urlEqualTo("/api/pam360/resources/42/accounts/7/password"))
                .withHeader("AUTHTOKEN", equalTo("tok-abc"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("{\"operation\":{\"result\":{\"status\":\"SUCCESS\"," +
                                "\"message\":\"Password fetched successfully\"}," +
                                "\"Details\":{\"PASSWORD\":\"s3cr3t\"}}}")));

        PAM360Client client = new PAM360Client(baseUrl);
        assertEquals("s3cr3t", client.fetchPassword("tok-abc", "42", "7"));
    }

    @Test
    public void fetchPassword_handlesEscapedQuoteInPassword() throws Exception {
        wireMock.stubFor(get(urlEqualTo("/api/pam360/resources/1/accounts/1/password"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("{\"operation\":{\"result\":{\"status\":\"SUCCESS\"}," +
                                "\"Details\":{\"PASSWORD\":\"p@ss\\\"word\"}}}")));

        PAM360Client client = new PAM360Client(baseUrl);
        assertEquals("p@ss\"word", client.fetchPassword("tok", "1", "1"));
    }

    @Test
    public void fetchPassword_stripsTrailingSlashFromBaseUrl() throws Exception {
        wireMock.stubFor(get(urlEqualTo("/api/pam360/resources/1/accounts/2/password"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("{\"operation\":{\"result\":{\"status\":\"SUCCESS\"}," +
                                "\"Details\":{\"PASSWORD\":\"pass\"}}}")));

        PAM360Client client = new PAM360Client(baseUrl + "/"); // trailing slash
        assertEquals("pass", client.fetchPassword("tok", "1", "2"));
    }

    // --- fetchPassword failures ---

    @Test
    public void fetchPassword_throwsIOException_onNon200Status() {
        wireMock.stubFor(get(anyUrl())
                .willReturn(aResponse().withStatus(403).withBody("Forbidden")));

        PAM360Client client = new PAM360Client(baseUrl);
        IOException ex = assertThrows(IOException.class,
                () -> client.fetchPassword("bad-tok", "1", "1"));
        assertTrue(ex.getMessage().contains("403"));
    }

    @Test
    public void fetchPassword_throwsIOException_whenStatusIsFailed() {
        wireMock.stubFor(get(anyUrl())
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("{\"operation\":{\"result\":{\"status\":\"FAILED\"," +
                                "\"message\":\"Invalid AUTHTOKEN\"}}}")));

        PAM360Client client = new PAM360Client(baseUrl);
        IOException ex = assertThrows(IOException.class,
                () -> client.fetchPassword("bad", "1", "1"));
        assertTrue("Should include API error message", ex.getMessage().contains("Invalid AUTHTOKEN"));
    }

    @Test
    public void fetchPassword_throwsIOException_whenPasswordFieldMissing() {
        wireMock.stubFor(get(anyUrl())
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("{\"operation\":{\"result\":{\"status\":\"SUCCESS\"}," +
                                "\"Details\":{}}}")));

        PAM360Client client = new PAM360Client(baseUrl);
        assertThrows(IOException.class, () -> client.fetchPassword("tok", "1", "1"));
    }

    // --- extractJsonStringValue unit tests ---

    @Test
    public void extractJsonStringValue_returnsValue_forSimpleKey() {
        PAM360Client client = new PAM360Client(baseUrl);
        String json = "{\"key\":\"value\"}";
        assertEquals("value", client.extractJsonStringValue(json, "key"));
    }

    @Test
    public void extractJsonStringValue_returnsNull_forMissingKey() {
        PAM360Client client = new PAM360Client(baseUrl);
        assertEquals(null, client.extractJsonStringValue("{\"other\":\"x\"}", "missing"));
    }

    @Test
    public void extractJsonStringValue_handlesWhitespaceAroundColon() {
        PAM360Client client = new PAM360Client(baseUrl);
        assertEquals("val", client.extractJsonStringValue("{\"k\" : \"val\"}", "k"));
    }

    @Test
    public void extractJsonStringValue_handlesBackslashEscapes() {
        PAM360Client client = new PAM360Client(baseUrl);
        assertEquals("a\tb\nc", client.extractJsonStringValue("{\"k\":\"a\\tb\\nc\"}", "k"));
    }
}
