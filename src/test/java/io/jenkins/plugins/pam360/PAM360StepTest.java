package io.jenkins.plugins.pam360;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.domains.Domain;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import hudson.util.Secret;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.Assert.*;

/**
 * Integration tests for the withPAM360 pipeline step.
 * A real (embedded) Jenkins instance runs the pipeline; PAM360 is mocked with WireMock.
 */
public class PAM360StepTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    private WireMockServer wireMock;

    @Before
    public void startWireMock() {
        wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMock.start();
    }

    @After
    public void stopWireMock() {
        wireMock.stop();
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private void addSecretCredential(String id, String secret) throws Exception {
        SystemCredentialsProvider.getInstance().getStore()
                .addCredentials(Domain.global(),
                        new StringCredentialsImpl(CredentialsScope.GLOBAL, id, id,
                                Secret.fromString(secret)));
    }

    private void stubPam360Password(String resourceId, String accountId, String token, String password) {
        wireMock.stubFor(get(urlEqualTo(
                "/api/pam360/resources/" + resourceId + "/accounts/" + accountId + "/password"))
                .withHeader("AUTHTOKEN", equalTo(token))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("{\"operation\":{\"result\":{\"status\":\"SUCCESS\"}," +
                                "\"Details\":{\"PASSWORD\":\"" + password + "\"}}}")));
    }

    private WorkflowRun runPipeline(String script) throws Exception {
        WorkflowJob job = j.createProject(WorkflowJob.class);
        job.setDefinition(new CpsFlowDefinition(script, true));
        return j.buildAndAssertSuccess(job);
    }

    // ── tests ────────────────────────────────────────────────────────────────

    @Test
    public void step_injectsPasswordAsEnvVar_usingGlobalUrl() throws Exception {
        PAM360GlobalConfiguration.get().setApiUrl("http://localhost:" + wireMock.port());
        addSecretCredential("my-pam360-token", "tok-123");
        stubPam360Password("42", "7", "tok-123", "db_pass_value");

        WorkflowRun run = runPipeline(
                "withPAM360(credentialId: 'my-pam360-token', resourceId: '42'," +
                "           accountId: '7', variable: 'DB_PASS') {\n" +
                "    echo \"result=${env.DB_PASS}\"\n" +
                "}");

        j.assertLogContains("result=db_pass_value", run);
    }

    @Test
    public void step_injectsPasswordAsEnvVar_usingPerStepUrl() throws Exception {
        // Global URL intentionally not set
        addSecretCredential("tok-cred", "tok-xyz");
        stubPam360Password("10", "3", "tok-xyz", "overridden_pass");

        WorkflowRun run = runPipeline(
                "withPAM360(credentialId: 'tok-cred', resourceId: '10'," +
                "           accountId: '3', variable: 'SECRET'," +
                "           apiUrl: 'http://localhost:" + wireMock.port() + "') {\n" +
                "    echo \"result=${env.SECRET}\"\n" +
                "}");

        j.assertLogContains("result=overridden_pass", run);
    }

    @Test
    public void step_envVarIsNotLeakedOutsideBlock() throws Exception {
        PAM360GlobalConfiguration.get().setApiUrl("http://localhost:" + wireMock.port());
        addSecretCredential("leak-cred", "tok-leak");
        stubPam360Password("1", "1", "tok-leak", "inner_secret");

        WorkflowRun run = runPipeline(
                "withPAM360(credentialId: 'leak-cred', resourceId: '1'," +
                "           accountId: '1', variable: 'INNER') {\n" +
                "    echo \"inside=${env.INNER}\"\n" +
                "}\n" +
                "echo \"outside=${env.INNER}\"");

        j.assertLogContains("inside=inner_secret", run);
        // env.INNER is null outside the block → Groovy renders it as "null"
        j.assertLogContains("outside=null", run);
    }

    @Test
    public void step_failsBuild_whenCredentialIdNotFound() throws Exception {
        PAM360GlobalConfiguration.get().setApiUrl("http://localhost:" + wireMock.port());
        // credential "missing-cred" is never added

        WorkflowJob job = j.createProject(WorkflowJob.class);
        job.setDefinition(new CpsFlowDefinition(
                "withPAM360(credentialId: 'missing-cred', resourceId: '1'," +
                "           accountId: '1', variable: 'X') { echo 'ok' }", true));

        WorkflowRun run = j.buildAndAssertStatus(
                hudson.model.Result.FAILURE, job);
        j.assertLogContains("missing-cred", run);
    }

    @Test
    public void step_failsBuild_whenNoApiUrlConfigured() throws Exception {
        // Neither global URL nor step-level apiUrl is set
        PAM360GlobalConfiguration.get().setApiUrl(null);
        addSecretCredential("no-url-cred", "tok");

        WorkflowJob job = j.createProject(WorkflowJob.class);
        job.setDefinition(new CpsFlowDefinition(
                "withPAM360(credentialId: 'no-url-cred', resourceId: '1'," +
                "           accountId: '1', variable: 'X') { echo 'ok' }", true));

        WorkflowRun run = j.buildAndAssertStatus(
                hudson.model.Result.FAILURE, job);
        j.assertLogContains("no API URL", run);
    }

    @Test
    public void step_failsBuild_whenPam360ReturnsError() throws Exception {
        PAM360GlobalConfiguration.get().setApiUrl("http://localhost:" + wireMock.port());
        addSecretCredential("err-cred", "tok-bad");

        wireMock.stubFor(get(anyUrl())
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("{\"operation\":{\"result\":{\"status\":\"FAILED\"," +
                                "\"message\":\"Invalid AUTHTOKEN\"}}}")));

        WorkflowJob job = j.createProject(WorkflowJob.class);
        job.setDefinition(new CpsFlowDefinition(
                "withPAM360(credentialId: 'err-cred', resourceId: '1'," +
                "           accountId: '1', variable: 'X') { echo 'ok' }", true));

        WorkflowRun run = j.buildAndAssertStatus(
                hudson.model.Result.FAILURE, job);
        j.assertLogContains("Invalid AUTHTOKEN", run);
    }
}
