package io.jenkins.plugins.pam360;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.EnvVars;
import hudson.model.Run;
import hudson.model.TaskListener;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.jenkinsci.plugins.workflow.steps.BodyExecutionCallback;
import org.jenkinsci.plugins.workflow.steps.EnvironmentExpander;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepExecution;

import java.io.IOException;
import java.io.Serial;
import java.io.Serializable;

/**
 * Execution logic for {@link PAM360Step}.
 *
 * Flow:
 *   1. Resolves the PAM360 base URL (step parameter → global config).
 *   2. Looks up the PAM360 API token from a Jenkins Secret text credential.
 *   3. Calls PAM360 to fetch the resource password.
 *   4. Injects the password as an environment variable for the enclosed block body.
 *
 * The class is serializable so that long-running pipelines can survive Jenkins
 * restarts while waiting for the body to complete.
 */
class PAM360StepExecution extends StepExecution {

    @Serial
    private static final long serialVersionUID = 1L;

    private final PAM360Step step;

    PAM360StepExecution(PAM360Step step, StepContext context) {
        super(context);
        this.step = step;
    }

    @Override
    public boolean start() throws Exception {
        Run<?, ?> run = getContext().get(Run.class);
        TaskListener listener = getContext().get(TaskListener.class);

        String apiUrl = resolveApiUrl();

        StringCredentials tokenCredential = CredentialsProvider.findCredentialById(
                step.getCredentialId(), StringCredentials.class, run);

        if (tokenCredential == null) {
            throw new IllegalArgumentException(
                    "PAM360: credential not found: '" + step.getCredentialId()
                    + "'. Create a Secret text credential with this ID.");
        }

        String apiToken = tokenCredential.getSecret().getPlainText();

        listener.getLogger().println("[PAM360] Fetching credential — resource "
                + step.getResourceId() + ", account " + step.getAccountId());

        // PAM360Client is created here (not stored as a field) so this class stays serializable.
        String password = new PAM360Client(apiUrl).fetchPassword(
                apiToken, step.getResourceId(), step.getAccountId());

        EnvironmentExpander expander = EnvironmentExpander.merge(
                getContext().get(EnvironmentExpander.class),
                new SingleVarExpander(step.getVariable(), password));

        getContext().newBodyInvoker()
                .withContext(expander)
                .withCallback(BodyExecutionCallback.wrap(getContext()))
                .start();

        return false; // asynchronous — will complete when body finishes
    }

    @Override
    public void stop(@NonNull Throwable cause) throws Exception {
        getContext().onFailure(cause);
    }

    private String resolveApiUrl() {
        String url = step.getApiUrl();
        if (url == null || url.isBlank()) {
            PAM360GlobalConfiguration cfg = PAM360GlobalConfiguration.get();
            url = cfg != null ? cfg.getApiUrl() : null;
        }
        if (url == null || url.isBlank()) {
            throw new IllegalStateException(
                    "PAM360: no API URL configured. Set it in Manage Jenkins → PAM360 Configuration "
                    + "or pass apiUrl to the withPAM360 step.");
        }
        return url;
    }

    /** Expands a single environment variable for the block body. */
    private static final class SingleVarExpander extends EnvironmentExpander implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        private final String key;
        private final String value;

        SingleVarExpander(String key, String value) {
            this.key = key;
            this.value = value;
        }

        @Override
        public void expand(@NonNull EnvVars env) throws IOException, InterruptedException {
            env.override(key, value);
        }
    }
}
